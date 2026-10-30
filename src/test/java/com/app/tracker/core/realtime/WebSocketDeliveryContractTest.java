package com.app.tracker.core.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

/** Dilim 03 sozlesmesinden turetilen bagimsiz, sahte saatli teslim testleri. */
class WebSocketDeliveryContractTest {
  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final String SESSION = "contract-session";
  private final UUID user = UUID.randomUUID();
  private final UUID workspace = UUID.randomUUID();
  private final UUID project = UUID.randomUUID();
  private final AtomicInteger blacklistQueries = new AtomicInteger();
  private final AtomicInteger membershipQueries = new AtomicInteger();
  private final AtomicBoolean revoked = new AtomicBoolean();
  private final AtomicBoolean member = new AtomicBoolean(true);
  private final TestClock clock = new TestClock();
  private WebSocketSessionRegistry registry;
  private WebSocketDeliveryGuard guard;

  @BeforeEach
  void setUp() {
    registry = new WebSocketSessionRegistry();
    guard =
        new WebSocketDeliveryGuard(
            registry,
            clock,
            jti -> {
              assertEquals("contract-jti", jti);
              blacklistQueries.incrementAndGet();
              return revoked.get();
            },
            (userId, workspaceId) -> {
              assertEquals(user, userId);
              membershipQueries.incrementAndGet();
              return member.get();
            });
  }

  @Test
  void K1_registeredMemberMessageIsDelivered() {
    register(T0.plusSeconds(60));
    Message<?> message = topic(workspace);
    assertSame(message, guard.preSend(message, null));
    assertEquals(1, blacklistQueries.get());
    assertEquals(1, membershipQueries.get());
  }

  @Test
  void K1_failClosed_unknownSessionDropsButRegisteredSessionPasses() {
    Message<?> message = topic(workspace);
    assertNull(guard.preSend(message, null), "2.a: kayitsiz oturum fail-closed olmali");
    register(T0.plusSeconds(60));
    assertSame(message, guard.preSend(message, null));
    assertNull(guard.preSend(message(null, "/queue/notifications"), null));
  }

  @Test
  void R3_R4_expiryBoundaryRejectsAtExactExpirationAfterAcceptingBeforeIt() {
    Instant exp = T0.plusSeconds(8);
    register(exp);
    Message<?> message = topic(workspace);
    clock.now = exp.minusNanos(1);
    assertSame(message, guard.preSend(message, null));
    clock.now = exp;
    assertError(guard.preSend(message, null), "Oturum suresi doldu");
    assertNull(guard.preSend(message, null), "R4: kapanan oturuma ikinci ERROR uretilmemeli");
  }

  @Test
  void R2_R4_blacklistTakesEffectOnVeryNextMessageWithinMembershipCacheWindow() {
    register(T0.plusSeconds(60));
    Message<?> message = topic(workspace);
    assertSame(message, guard.preSend(message, null));
    revoked.set(true);
    assertError(guard.preSend(message, null), "Yetki geri alindi");
    assertEquals(2, blacklistQueries.get());
    assertNull(guard.preSend(message, null));
  }

  @Test
  void R1_R4_membershipRemovalRejectsAfterTenSecondsWithValidPeerUnaffected() {
    register(T0.plusSeconds(60));
    Message<?> message = topic(workspace);
    assertSame(message, guard.preSend(message, null));
    clock.now = T0.plusSeconds(10);
    member.set(false);
    assertError(guard.preSend(message, null), "Yetki geri alindi");
    assertNull(guard.preSend(message, null));
    member.set(true);
    registry.register("peer", new WebSocketSessionState(user, "contract-jti", T0.plusSeconds(60)));
    Message<?> peer = message("peer", destination(workspace));
    assertSame(peer, guard.preSend(peer, null));
  }

  @Test
  void R5_userQueueChecksExpiryButDoesNotRequireWorkspaceMembership() {
    register(T0.plusSeconds(8));
    member.set(false);
    Message<?> inbox = message(SESSION, "/queue/notifications-user-session");
    assertSame(inbox, guard.preSend(inbox, null));
    assertEquals(0, membershipQueries.get());
    clock.now = T0.plusSeconds(8);
    assertError(guard.preSend(inbox, null), "Oturum suresi doldu");
  }

  @Test
  void R2_R5_userQueueChecksBlacklistOnEveryDelivery() {
    register(T0.plusSeconds(60));
    Message<?> inbox = message(SESSION, "/queue/notifications-user-session");
    assertSame(inbox, guard.preSend(inbox, null));
    revoked.set(true);
    assertError(guard.preSend(inbox, null), "Yetki geri alindi");
    assertEquals(2, blacklistQueries.get());
    assertEquals(0, membershipQueries.get());
  }

  @Test
  void R7_membershipCachedForFiveSecondsWhileBlacklistIsCheckedEveryTime() {
    register(T0.plusSeconds(60));
    Message<?> message = topic(workspace);
    for (int i = 0; i < 10; i++) {
      clock.now = T0.plusMillis(i * 500L);
      assertSame(message, guard.preSend(message, null));
    }
    assertEquals(10, blacklistQueries.get());
    assertEquals(1, membershipQueries.get());
    clock.now = T0.plusSeconds(5).plusNanos(1);
    assertSame(message, guard.preSend(message, null));
    assertEquals(11, blacklistQueries.get());
    assertEquals(2, membershipQueries.get(), "R7: 5 sn gecince uyelik yeniden sorgulanmali");
  }

  @Test
  void R7_membershipCacheIsSeparateForEachWorkspace() {
    register(T0.plusSeconds(60));
    UUID other = UUID.randomUUID();
    Message<?> first = topic(workspace);
    Message<?> second = topic(other);
    assertSame(first, guard.preSend(first, null));
    assertSame(second, guard.preSend(second, null));
    assertSame(first, guard.preSend(first, null));
    assertSame(second, guard.preSend(second, null));
    assertEquals(2, membershipQueries.get());
    assertEquals(4, blacklistQueries.get());
  }

  @Test
  void R6_stompDisconnectRemovesOnlyTargetThenRegistryReturnsToZero() {
    register(T0.plusSeconds(60));
    registry.register("peer", new WebSocketSessionState(user, "contract-jti", T0.plusSeconds(60)));
    assertEquals(2, registry.size());
    WebSocketAuthInterceptor inbound =
        new WebSocketAuthInterceptor(null, null, null, registry, clock);
    inbound.preSend(disconnect(SESSION), null);
    assertEquals(1, registry.size());
    assertNull(registry.get(SESSION));
    Message<?> peer = message("peer", destination(workspace));
    assertSame(peer, guard.preSend(peer, null));
    inbound.preSend(disconnect("peer"), null);
    assertEquals(0, registry.size());
    assertNull(guard.preSend(peer, null));
  }

  @Test
  void R6_transportDisconnectEventEmptiesRegistryWithoutStompDisconnect() {
    register(T0.plusSeconds(60));
    Message<?> message = topic(workspace);
    assertEquals(1, registry.size());
    assertSame(message, guard.preSend(message, null));
    registry.onSessionDisconnect(
        new SessionDisconnectEvent(this, disconnect(SESSION), SESSION, CloseStatus.SERVER_ERROR));
    assertEquals(0, registry.size());
    assertNull(guard.preSend(message, null));
    registry.onSessionDisconnect(
        new SessionDisconnectEvent(this, disconnect(SESSION), SESSION, CloseStatus.SERVER_ERROR));
    assertEquals(0, registry.size(), "R6: temizlik idempotent olmali");
  }

  @Test
  void R4_nonMessageFramesRemainUntouchedWithoutRegistryEntry() {
    for (StompCommand command :
        new StompCommand[] {StompCommand.CONNECTED, StompCommand.RECEIPT, StompCommand.ERROR}) {
      StompHeaderAccessor headers = StompHeaderAccessor.create(command);
      headers.setSessionId(SESSION);
      Message<?> message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
      assertSame(message, guard.preSend(message, null));
    }
    assertEquals(0, blacklistQueries.get());
    assertEquals(0, membershipQueries.get());
  }

  @Test
  void R1_R7_concurrentDeliveryMustNotBypassPendingMembershipRevalidation() throws Exception {
    register(T0.plusSeconds(60));
    Message<?> message = topic(workspace);
    assertSame(message, guard.preSend(message, null));
    clock.now = T0.plusSeconds(10);
    CountDownLatch checking = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    WebSocketDeliveryGuard concurrentGuard =
        new WebSocketDeliveryGuard(
            registry,
            clock,
            jti -> false,
            (u, ws) -> {
              checking.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("test uyelik sorgusunu serbest birakmadi");
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
              return false;
            });
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> concurrentGuard.preSend(message, null));
      try {
        assertTrue(checking.await(3, TimeUnit.SECONDS));
        var second = executor.submit(() -> concurrentGuard.preSend(message, null));
        Message<?> early = null;
        boolean finished = false;
        try {
          early = second.get(300, TimeUnit.MILLISECONDS);
          finished = true;
        } catch (TimeoutException expected) {
          // Sonucu beklemek de guvenli; dogrulama bitince ret beklenir.
        } finally {
          release.countDown();
        }
        Message<?> later = finished ? early : second.get(3, TimeUnit.SECONDS);
        assertNotSame(
            message,
            later,
            "R1: uyeligi kaldirilan kullaniciya, kontrol surerken paralel MESSAGE sizdi");
        assertNotSame(message, first.get(3, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
    }
  }

  private void register(Instant expiresAt) {
    registry.register(SESSION, new WebSocketSessionState(user, "contract-jti", expiresAt));
  }

  private Message<?> topic(UUID workspaceId) {
    return message(SESSION, destination(workspaceId));
  }

  private String destination(UUID workspaceId) {
    return "/topic/workspace.%s.project.%s".formatted(workspaceId, project);
  }

  private static Message<?> message(String session, String destination) {
    SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    headers.setSessionId(session);
    headers.setDestination(destination);
    return MessageBuilder.createMessage(new byte[] {1}, headers.getMessageHeaders());
  }

  private static Message<byte[]> disconnect(String session) {
    StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.DISCONNECT);
    headers.setSessionId(session);
    return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
  }

  private static void assertError(Message<?> result, String reason) {
    assertNotNull(result, "R4: sessiz dusurme yerine ERROR frame'i gerekli");
    StompHeaderAccessor headers = StompHeaderAccessor.wrap(result);
    assertEquals(StompCommand.ERROR, headers.getCommand());
    assertEquals(reason, headers.getMessage());
    assertEquals(SESSION, headers.getSessionId());
  }

  private static final class TestClock extends Clock {
    private volatile Instant now = T0;

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
