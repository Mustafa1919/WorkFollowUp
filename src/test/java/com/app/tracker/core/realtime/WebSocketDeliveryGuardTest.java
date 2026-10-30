package com.app.tracker.core.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Saf birim testi: sahte saat + sayacli sahte blacklist/uyelik bagimliliklari. */
class WebSocketDeliveryGuardTest {

  private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
  private static final String SESSION = "s-1";

  private final UUID userId = UUID.randomUUID();
  private final UUID workspaceId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();

  private MutableClock clock;
  private WebSocketSessionRegistry registry;
  private AtomicInteger blacklistQueries;
  private AtomicInteger membershipQueries;
  private AtomicBoolean revoked;
  private AtomicBoolean member;
  private AtomicBoolean failQueries;
  private AtomicBoolean failMembership;
  private WebSocketDeliveryGuard guard;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(T0);
    registry = new WebSocketSessionRegistry();
    blacklistQueries = new AtomicInteger();
    membershipQueries = new AtomicInteger();
    revoked = new AtomicBoolean(false);
    member = new AtomicBoolean(true);
    failQueries = new AtomicBoolean(false);
    failMembership = new AtomicBoolean(false);
    guard =
        new WebSocketDeliveryGuard(
            registry,
            clock,
            jti -> {
              blacklistQueries.incrementAndGet();
              if (failQueries.get()) {
                throw new IllegalStateException("redis yok");
              }
              return revoked.get();
            },
            (u, ws) -> {
              membershipQueries.incrementAndGet();
              if (failQueries.get() || failMembership.get()) {
                throw new IllegalStateException("db yok");
              }
              return member.get();
            });
  }

  private void register(Instant expiresAt) {
    registry.register(SESSION, new WebSocketSessionState(userId, "jti-1", expiresAt));
  }

  private Message<?> topicMessage() {
    return message("/topic/workspace.%s.project.%s".formatted(workspaceId, projectId));
  }

  private static Message<?> message(String destination) {
    SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    accessor.setSessionId(SESSION);
    accessor.setDestination(destination);
    return MessageBuilder.createMessage(new byte[] {1}, accessor.getMessageHeaders());
  }

  private static void assertError(Message<?> result, String reason) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
    assertEquals(StompCommand.ERROR, accessor.getCommand());
    assertEquals(reason, accessor.getMessage());
    assertEquals(SESSION, accessor.getSessionId());
  }

  @Test
  void validSessionMessagePasses() {
    register(T0.plusSeconds(60));
    Message<?> m = topicMessage();
    assertSame(m, guard.preSend(m, null));
  }

  @Test
  void unknownSessionMessageIsDropped() {
    assertNull(guard.preSend(topicMessage(), null));
  }

  @Test
  void nonMessageFramesAreUntouched() {
    SimpMessageHeaderAccessor accessor =
        SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT_ACK);
    accessor.setSessionId(SESSION);
    Message<?> m = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    assertSame(m, guard.preSend(m, null));
  }

  @Test
  void expiredTokenYieldsSingleErrorThenDrops() {
    register(T0.plusSeconds(8));
    clock.set(T0.plusSeconds(7));
    Message<?> beforeExpiry = topicMessage();
    assertSame(beforeExpiry, guard.preSend(beforeExpiry, null));
    clock.set(T0.plusSeconds(8));
    assertError(guard.preSend(topicMessage(), null), WebSocketDeliveryGuard.EXPIRED_MESSAGE);
    assertNull(guard.preSend(topicMessage(), null));
    assertNull(guard.preSend(message("/queue/notifications-user" + SESSION), null));
  }

  @Test
  void expiredTokenAlsoBlocksUserQueue() {
    register(T0.plusSeconds(8));
    clock.set(T0.plusSeconds(10));
    assertError(
        guard.preSend(message("/queue/notifications-user" + SESSION), null),
        WebSocketDeliveryGuard.EXPIRED_MESSAGE);
  }

  @Test
  void blacklistQueriedOnEveryMessageWithoutCache() {
    register(T0.plusSeconds(600));
    for (int i = 0; i < 10; i++) {
      guard.preSend(message("/queue/notifications-user" + SESSION), null);
      guard.preSend(topicMessage(), null);
    }
    // Sozlesme 2.c [Duzeltme]: blacklist onbelleklenmez; uyelik 5 sn'de 1 kez.
    assertEquals(20, blacklistQueries.get());
    assertEquals(1, membershipQueries.get());
  }

  @Test
  void membershipQueriedAtMostOncePerIntervalPerWorkspace() {
    register(T0.plusSeconds(600));
    for (int i = 0; i < 10; i++) {
      guard.preSend(topicMessage(), null);
    }
    assertEquals(1, membershipQueries.get());
    clock.set(T0.plusSeconds(4));
    guard.preSend(topicMessage(), null);
    assertEquals(1, membershipQueries.get());
    clock.set(T0.plusSeconds(5));
    guard.preSend(topicMessage(), null);
    guard.preSend(topicMessage(), null);
    assertEquals(2, membershipQueries.get());
  }

  @Test
  void membershipRecordedAtSubscribeSkipsFirstQuery() {
    register(T0.plusSeconds(600));
    registry.get(SESSION).recordMembershipCheck(workspaceId, T0);
    guard.preSend(topicMessage(), null);
    assertEquals(0, membershipQueries.get());
  }

  @Test
  void userQueueDoesNotQueryMembership() {
    register(T0.plusSeconds(600));
    guard.preSend(message("/queue/notifications-user" + SESSION), null);
    assertEquals(0, membershipQueries.get());
  }

  @Test
  void revokedTokenYieldsError() {
    register(T0.plusSeconds(600));
    Message<?> m = topicMessage();
    assertSame(m, guard.preSend(m, null));
    // Logout hemen sonraki mesajda etkili (aralik beklenmez).
    revoked.set(true);
    assertError(guard.preSend(topicMessage(), null), WebSocketDeliveryGuard.REVOKED_MESSAGE);
    assertNull(guard.preSend(topicMessage(), null));
  }

  @Test
  void removedMemberYieldsError() {
    register(T0.plusSeconds(600));
    registry.get(SESSION).recordMembershipCheck(workspaceId, T0);
    member.set(false);
    assertNotNullPass(guard.preSend(topicMessage(), null));
    clock.set(T0.plusSeconds(5));
    assertError(guard.preSend(topicMessage(), null), WebSocketDeliveryGuard.REVOKED_MESSAGE);
  }

  @Test
  void blacklistFailureDropsMessageWithoutClosing() {
    register(T0.plusSeconds(600));
    failQueries.set(true);
    assertNull(guard.preSend(topicMessage(), null));
    assertEquals(1, blacklistQueries.get());
    failQueries.set(false);
    Message<?> m = topicMessage();
    assertSame(m, guard.preSend(m, null));
    assertEquals(2, blacklistQueries.get());
    assertEquals(1, membershipQueries.get());
  }

  @Test
  void concurrentDeliveryWaitsForPendingMembershipResultAndSharesIt() throws Exception {
    register(T0.plusSeconds(600));
    java.util.concurrent.CountDownLatch checking = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    AtomicInteger queries = new AtomicInteger();
    WebSocketDeliveryGuard slow =
        new WebSocketDeliveryGuard(
            registry,
            clock,
            jti -> false,
            (u, ws) -> {
              queries.incrementAndGet();
              checking.countDown();
              try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return true;
            });
    Message<?> m = topicMessage();
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> slow.preSend(m, null));
      checking.await(3, java.util.concurrent.TimeUnit.SECONDS);
      var second = executor.submit(() -> slow.preSend(m, null));
      Thread.sleep(200);
      assertEquals(false, second.isDone(), "suren sorgu varken ikinci teslimat beklemeli");
      release.countDown();
      assertSame(m, first.get(3, java.util.concurrent.TimeUnit.SECONDS));
      assertSame(m, second.get(3, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertEquals(1, queries.get());
  }

  @Test
  void membershipFailureReleasesClaimAndRetriesNextMessage() {
    register(T0.plusSeconds(600));
    failMembership.set(true);
    assertNull(guard.preSend(topicMessage(), null));
    assertEquals(1, membershipQueries.get());
    failMembership.set(false);
    Message<?> m = topicMessage();
    assertSame(m, guard.preSend(m, null));
    assertEquals(2, membershipQueries.get());
  }

  @Test
  void registryClearedOnDisconnectEvent() {
    register(T0.plusSeconds(600));
    registry.register("s-2", new WebSocketSessionState(userId, "jti-2", T0.plusSeconds(60)));
    assertEquals(2, registry.size());
    registry.onSessionDisconnect(
        new SessionDisconnectEvent(this, disconnectMessage(SESSION), SESSION, CloseStatus.NORMAL));
    registry.onSessionDisconnect(
        new SessionDisconnectEvent(this, disconnectMessage("s-2"), "s-2", CloseStatus.NORMAL));
    assertEquals(0, registry.size());
  }

  @Test
  void registryClearedOnStompDisconnectFrame() {
    register(T0.plusSeconds(600));
    WebSocketAuthInterceptor inbound =
        new WebSocketAuthInterceptor(null, null, null, registry, clock);
    inbound.preSend(disconnectMessage(SESSION), null);
    assertEquals(0, registry.size());
    assertNull(guard.preSend(topicMessage(), null));
  }

  private static void assertNotNullPass(Message<?> result) {
    assertEquals(
        SimpMessageType.MESSAGE, SimpMessageHeaderAccessor.getMessageType(result.getHeaders()));
  }

  private static Message<byte[]> disconnectMessage(String sessionId) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
    accessor.setSessionId(sessionId);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  /** Testte ileri sarilabilen saat. */
  private static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void set(Instant instant) {
      this.now = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
