package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.realtime.WebSocketSessionRegistry;
import com.app.tracker.core.security.CorsProperties;
import com.app.tracker.core.security.JwtKeyProvider;
import com.app.tracker.security.support.JwtForge;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Actor;
import com.app.tracker.security.support.SecurityFixture.Tenant;
import com.app.tracker.security.support.SecurityHttp;
import com.app.tracker.workspace.model.WorkspaceRole;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Guvenlik katalogu WS-03..05: STOMP el sikisma Origin kontrolu, uyelikten cikarilan kullanicinin
 * ve oturumu kapatilan/suresi dolan token'in acik baglantisinin davranisi. Olaylar gercek akistan
 * uretilir (HTTP ile gorev olusturma -> outbox -> Kafka -> {@code TaskEventBroadcastListener}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketSessionSecurityTest extends AbstractIntegrationTest {

  private static final String EVIL_ORIGIN = "https://evil.example";
  private static final long DELIVERY_WAIT_SECONDS = 25;

  @LocalServerPort private int port;
  @Autowired private ApplicationContext ctx;
  @Autowired private ObjectMapper mapper;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private JwtKeyProvider keyProvider;
  @Autowired private CorsProperties corsProperties;
  @Autowired private WebSocketSessionRegistry sessionRegistry;

  private SecurityFixture fixture;
  private SecurityHttp http;

  @BeforeAll
  void setUp() throws Exception {
    http = new SecurityHttp(port);
    fixture = SecurityFixture.create(ctx, port);
    // TaskEventBroadcastListener auto.offset.reset=latest: topic ve consumer atamasi hazir olsun
    // (bkz. WebSocketRealtimeIntegrationTest.warmUpBroadcastListener).
    kafkaTemplate.send("task.events", "warmup-" + UUID.randomUUID(), "{}");
    Thread.sleep(3_000);
  }

  /** Bir abonenin aldigi mesajlar ve baglanti durumu. */
  private static final class Listener {
    final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
    final AtomicBoolean closed = new AtomicBoolean(false);
    final LinkedBlockingQueue<StompHeaders> errors = new LinkedBlockingQueue<>();
    volatile WebSocketSession transport;
    StompSession session;

    /** Mesajlardan {@code needle} iceren biri {@code seconds} sn icinde gelirse true. */
    boolean receives(String needle, long seconds) throws InterruptedException {
      long deadline = System.currentTimeMillis() + seconds * 1000;
      while (System.currentTimeMillis() < deadline) {
        String m = messages.poll(200, TimeUnit.MILLISECONDS);
        if (m != null && m.contains(needle)) {
          return true;
        }
      }
      return false;
    }
  }

  // =========================================================================================
  // WS-03 Origin
  // =========================================================================================

  @Test
  @DisplayName("WS-03 | izinli listede olmayan Origin ile el sikisma reddedilir")
  void ws03_foreignOriginHandshakeIsRejected() {
    Actor user = fixture.a().developer();
    CompletableFuture<StompSession> future = connectAsync(user.accessToken(), EVIL_ORIGIN);
    assertThrows(
        ExecutionException.class,
        () -> future.get(10, TimeUnit.SECONDS),
        "https://evil.example kaynakli el sikisma kabul edildi");
  }

  @Test
  @DisplayName(
      "WS-03 | K: izinli (ayni kaynak / yapilandirilmis) Origin ile el sikisma ve CONNECT basarili")
  void ws03_acceptance_allowedOriginConnects() throws Exception {
    Actor user = fixture.a().developer();
    // Test ortaminda CORS_ALLOWED_ORIGINS bos olabilir: o durumda yalniz ayni kaynak izinlidir.
    String allowed =
        corsProperties.getAllowedOrigins().isEmpty()
            ? "http://localhost:" + port
            : corsProperties.getAllowedOrigins().get(0);
    StompSession session = connectAsync(user.accessToken(), allowed).get(10, TimeUnit.SECONDS);
    try {
      assertTrue(session.isConnected(), "izinli kaynak (" + allowed + ") ile CONNECT basarisiz");
    } finally {
      session.disconnect();
    }
  }

  // =========================================================================================
  // WS-04 uyelikten cikarilan kullanici
  // =========================================================================================

  @Test
  @DisplayName(
      "WS-04 | uyelikten cikarilan kullanici, cikarilmadan 10 sn sonra uretilen olaylari almaz")
  void ws04_removedMemberStopsReceivingEvents() throws Exception {
    Tenant a = fixture.a();
    Actor removed = fixture.addMember(a, WorkspaceRole.DEVELOPER, "ws04-removed");
    Actor control = fixture.addMember(a, WorkspaceRole.DEVELOPER, "ws04-control");
    Listener target = listen(removed.accessToken(), a);
    Listener witness = listen(control.accessToken(), a);
    try {
      // K: cikarilmadan once olay alinir.
      String before = createTask(a, "ws04 once");
      assertTrue(
          target.receives(before, DELIVERY_WAIT_SECONDS),
          "K: cikarilmadan once uye olayi almadi (olay hattini dogrula)");
      assertTrue(
          witness.receives(before, DELIVERY_WAIT_SECONDS), "K: kontrol abonesi olayi almadi");

      HttpResponse<String> remove =
          http.delete(
              "/api/v1/workspaces/members/" + removed.userId(), a.admin(), a.workspaceId(), null);
      assertEquals(204, remove.statusCode(), remove.body());
      long removedAt = System.currentTimeMillis();

      // Sozlesme: cikarildiktan SONRA 10 sn icinde almayi birakir. Pencereyi bekle, sonra olay
      // uret.
      Thread.sleep(10_000);
      drain(target);
      drain(witness);
      String after = createTask(a, "ws04 sonra");
      boolean witnessGot = witness.receives(after, DELIVERY_WAIT_SECONDS);
      assertTrue(
          witnessGot, "kontrol abonesi cikarma sonrasi olayi almadi (olay hatti calismiyor)");
      boolean targetGot = target.receives(after, 3);
      long sinceRemoval = (System.currentTimeMillis() - removedAt) / 1000;
      assertFalse(
          targetGot,
          "cikarilan kullanicinin acik baglantisi cikarmadan "
              + sinceRemoval
              + " sn sonra uretilen gorev olusturma olayini hala aliyor (yetki yalniz SUBSCRIBE'da"
              + " kontrol ediliyor)");
    } finally {
      close(target);
      close(witness);
    }
  }

  // =========================================================================================
  // WS-05 logout / suresi dolan token
  // =========================================================================================

  @Test
  @DisplayName(
      "WS-05 | logout ile kara listeye giren token'in acik baglantisi yeni olay almayi birakir")
  void ws05_loggedOutTokenStopsReceivingEvents() throws Exception {
    Tenant a = fixture.a();
    Actor user = fixture.addMember(a, WorkspaceRole.DEVELOPER, "ws05-logout");
    Actor control = fixture.addMember(a, WorkspaceRole.DEVELOPER, "ws05-control");
    HttpResponse<String> login =
        http.send(
            "POST",
            "/api/v1/auth/login",
            null,
            null,
            "{\"email\":\""
                + user.email()
                + "\",\"password\":\""
                + SecurityFixture.PASSWORD
                + "\"}",
            Map.of());
    assertEquals(200, login.statusCode(), login.body());
    String accessToken = mapper.readTree(login.body()).path("accessToken").asString("");
    String refresh = refreshCookie(login);

    Listener target = listen(accessToken, a);
    Listener witness = listen(control.accessToken(), a);
    try {
      String before = createTask(a, "ws05 once");
      assertTrue(target.receives(before, DELIVERY_WAIT_SECONDS), "K: logout oncesi olay alinmadi");
      assertTrue(witness.receives(before, DELIVERY_WAIT_SECONDS), "K: kontrol olayi almadi");

      HttpResponse<String> logout =
          http.send(
              "POST",
              "/api/v1/auth/logout",
              null,
              null,
              null,
              Map.of("Cookie", "refresh_token=" + refresh));
      assertEquals(204, logout.statusCode(), logout.body());
      assertEquals(
          401,
          http.get("/api/v1/projects", accessToken, a.workspaceId(), null).statusCode(),
          "on kosul: token logout sonrasi HTTP'de gecersiz olmali");
      long loggedOutAt = System.currentTimeMillis();

      drain(target);
      drain(witness);
      String after = createTask(a, "ws05 sonra");
      assertTrue(witness.receives(after, DELIVERY_WAIT_SECONDS), "kontrol abonesi olayi almadi");
      boolean targetGot = target.receives(after, 3);
      long since = (System.currentTimeMillis() - loggedOutAt) / 1000;
      assertFalse(
          targetGot,
          "logout'tan "
              + since
              + " sn sonra kara listedeki token'la kurulmus baglanti yeni olay almaya devam"
              + " ediyor");
    } finally {
      close(target);
      close(witness);
    }
  }

  @Test
  @DisplayName("WS-05 | suresi dolan token'la kurulmus acik baglanti yeni olay almayi birakir")
  void ws05_expiredTokenStopsReceivingEvents() throws Exception {
    Tenant a = fixture.a();
    Actor user = fixture.addMember(a, WorkspaceRole.DEVELOPER, "ws05-expiry");
    Actor control = fixture.addMember(a, WorkspaceRole.DEVELOPER, "ws05-expcontrol");
    // Uygulamanin gercek ozel anahtariyla imzali, 8 sn omurlu token.
    long exp = System.currentTimeMillis() / 1000 + 8;
    String shortLived =
        new JwtForge(keyProvider).signRs256(JwtForge.payloadJson(user.userId().toString(), exp));

    Listener target = listen(shortLived, a);
    Listener witness = listen(control.accessToken(), a);
    try {
      String before = createTask(a, "ws05e once");
      assertTrue(
          target.receives(before, 6), "K: token suresi dolmadan once olay alinmadi (8 sn omurlu)");
      assertTrue(witness.receives(before, DELIVERY_WAIT_SECONDS), "K: kontrol olayi almadi");

      long remainingMillis = exp * 1000 - System.currentTimeMillis();
      Thread.sleep(Math.max(0, remainingMillis) + 2_000);
      assertEquals(
          401,
          http.get("/api/v1/projects", shortLived, a.workspaceId(), null).statusCode(),
          "on kosul: token HTTP'de suresi dolmus sayilmali");

      drain(target);
      drain(witness);
      String after = createTask(a, "ws05e sonra");
      assertTrue(witness.receives(after, DELIVERY_WAIT_SECONDS), "kontrol abonesi olayi almadi");
      boolean targetGot = target.receives(after, 3);
      assertFalse(
          targetGot, "suresi dolmus token'la kurulmus baglanti yeni olay almaya devam ediyor");
    } finally {
      close(target);
      close(witness);
    }
  }

  @Test
  void R1_R4_removedMemberGetsRevokedErrorAndConnectionCloses() throws Exception {
    Tenant a = fixture.a();
    Actor user = fixture.addMember(a, WorkspaceRole.DEVELOPER, "r4-removed");
    Listener target = listen(user.accessToken(), a);
    Listener witness = listen(a.admin().accessToken(), a);
    try {
      assertBothReceive(a, target, witness, "K1 R4 once");
      HttpResponse<String> removed =
          http.delete(
              "/api/v1/workspaces/members/" + user.userId(), a.admin(), a.workspaceId(), null);
      assertEquals(204, removed.statusCode(), removed.body());
      Thread.sleep(10_000);
      assertViolation(a, target, witness, "Yetki geri alindi");
    } finally {
      close(target);
      close(witness);
    }
  }

  @Test
  void R2_R4_logoutGetsRevokedErrorAndConnectionCloses() throws Exception {
    Tenant a = fixture.a();
    Actor user = fixture.addMember(a, WorkspaceRole.DEVELOPER, "r4-logout");
    HttpResponse<String> login =
        http.send(
            "POST",
            "/api/v1/auth/login",
            null,
            null,
            "{\"email\":\""
                + user.email()
                + "\",\"password\":\""
                + SecurityFixture.PASSWORD
                + "\"}",
            Map.of());
    assertEquals(200, login.statusCode(), login.body());
    String token = mapper.readTree(login.body()).path("accessToken").asString("");
    Listener target = listen(token, a);
    Listener witness = listen(a.admin().accessToken(), a);
    try {
      assertBothReceive(a, target, witness, "K1 R4 logout once");
      HttpResponse<String> logout =
          http.send(
              "POST",
              "/api/v1/auth/logout",
              null,
              null,
              null,
              Map.of("Cookie", "refresh_token=" + refreshCookie(login)));
      assertEquals(204, logout.statusCode(), logout.body());
      // Logout yanitindan sonraki ILK olay: blacklist icin bekleme/onbellek payi yok.
      assertViolation(a, target, witness, "Yetki geri alindi");
    } finally {
      close(target);
      close(witness);
    }
  }

  @Test
  void R3_R4_expiredSessionGetsExpiryErrorAndConnectionCloses() throws Exception {
    Tenant a = fixture.a();
    Actor user = fixture.addMember(a, WorkspaceRole.DEVELOPER, "r4-expiry");
    Listener witness = listen(a.admin().accessToken(), a);
    long exp = System.currentTimeMillis() / 1000 + 8;
    String token =
        new JwtForge(keyProvider).signRs256(JwtForge.payloadJson(user.userId().toString(), exp));
    Listener target = listen(token, a);
    try {
      String before = createTask(a, "K1 R3 R4 once");
      assertTrue(target.receives(before, 6), "K1: sure dolmadan teslim olmali");
      assertTrue(witness.receives(before, DELIVERY_WAIT_SECONDS));
      assertOpen(target);
      Thread.sleep(Math.max(0, exp * 1000 - System.currentTimeMillis()) + 2_000);
      assertViolation(a, target, witness, "Oturum suresi doldu");
    } finally {
      close(target);
      close(witness);
    }
  }

  @Test
  void R5_expiredInboxGetsNoAssignmentButValidSessionOfSameUserReceivesIt() throws Exception {
    Tenant a = fixture.a();
    Actor user = fixture.addMember(a, WorkspaceRole.DEVELOPER, "r5-inbox");
    Listener witness = listenDestination(user.accessToken(), "/user/queue/notifications");
    String before = createTask(a, "R5 kabul");
    String after = createTask(a, "R5 ret");
    long exp = System.currentTimeMillis() / 1000 + 8;
    String token =
        new JwtForge(keyProvider).signRs256(JwtForge.payloadJson(user.userId().toString(), exp));
    Listener target = listenDestination(token, "/user/queue/notifications");
    try {
      assignTask(a, before, user);
      assertTrue(target.receives(before, 6), "R5 kabul: kisa omurlu oturum Inbox almali");
      assertTrue(
          witness.receives(before, DELIVERY_WAIT_SECONDS), "R5 kabul: gecerli oturum Inbox almali");
      assertOpen(target);
      Thread.sleep(Math.max(0, exp * 1000 - System.currentTimeMillis()) + 2_000);
      drain(target);
      drain(witness);
      assignTask(a, after, user);
      assertTrue(
          witness.receives(after, DELIVERY_WAIT_SECONDS), "R5: gercek atama bildirimi uretilmedi");
      assertFalse(target.receives(after, 3), "R5: suresi dolmus oturuma Inbox bildirimi sizdi");
      assertErrorAndClosed(target, "Oturum suresi doldu");
      assertOpen(witness);
    } finally {
      close(target);
      close(witness);
    }
  }

  @Test
  void R6_clientDisconnectEmptiesRegistry() throws Exception {
    assertRegistrySizeEventually(0);
    Listener target = listen(fixture.a().developer().accessToken(), fixture.a());
    try {
      assertRegistrySizeEventually(1);
      assertOpen(target);
      target.session.disconnect();
      assertRegistrySizeEventually(0);
    } finally {
      close(target);
    }
  }

  @Test
  void R6_transportCloseWithoutStompDisconnectEmptiesRegistry() throws Exception {
    assertRegistrySizeEventually(0);
    Listener target = listen(fixture.a().developer().accessToken(), fixture.a());
    try {
      assertRegistrySizeEventually(1);
      assertOpen(target);
      assertNotNull(target.transport);
      target.transport.close(); // STOMP DISCONNECT gonderilmeden transport kapanir.
      assertRegistrySizeEventually(0);
    } finally {
      close(target);
    }
  }

  @Test
  void K1_R4_validMemberKeepsConnectionOpenAfterDelivery() throws Exception {
    Listener target = listen(fixture.a().developer().accessToken(), fixture.a());
    try {
      assertTrue(target.receives(createTask(fixture.a(), "K1 R4 kabul"), DELIVERY_WAIT_SECONDS));
      assertOpen(target);
      assertTrue(target.errors.isEmpty());
    } finally {
      close(target);
    }
  }

  @Test
  void K1_inbound_otherWorkspaceSubscriptionIsRejectedWhileOwnWorkspaceWorks() throws Exception {
    Listener valid = listen(fixture.a().developer().accessToken(), fixture.a());
    Listener invalid = listen(fixture.a().developer().accessToken(), fixture.b());
    try {
      assertTrue(
          valid.receives(createTask(fixture.a(), "K1 kendi workspace"), DELIVERY_WAIT_SECONDS));
      assertOpen(valid);
      StompHeaders error = invalid.errors.poll(10, TimeUnit.SECONDS);
      assertNotNull(error, "baska workspace aboneligi ERROR ile reddedilmeli");
      assertEventuallyClosed(invalid);
      assertFalse(invalid.receives(createTask(fixture.b(), "K1 yabanci workspace"), 3));
    } finally {
      close(valid);
      close(invalid);
    }
  }

  private void assertBothReceive(Tenant a, Listener target, Listener witness, String title)
      throws Exception {
    String id = createTask(a, title);
    assertTrue(target.receives(id, DELIVERY_WAIT_SECONDS), "K1: hedef olayi almali");
    assertTrue(witness.receives(id, DELIVERY_WAIT_SECONDS), "K1: kontrol olayi almali");
    assertOpen(target);
    assertOpen(witness);
  }

  private void assertViolation(Tenant a, Listener target, Listener witness, String reason)
      throws Exception {
    drain(target);
    drain(witness);
    String id = createTask(a, "R4 ihlal");
    assertTrue(witness.receives(id, DELIVERY_WAIT_SECONDS), "K1: kontrol olayi almali");
    assertFalse(target.receives(id, 3), "R4: yasakli olay teslim edildi");
    assertErrorAndClosed(target, reason);
    assertOpen(witness);
  }

  private static void assertErrorAndClosed(Listener listener, String reason) throws Exception {
    // StompSessionHandler.handleFrame yalniz STOMP ERROR frame'leri icin cagrilir.
    StompHeaders error = listener.errors.poll(10, TimeUnit.SECONDS);
    assertNotNull(error, "R4: istemci STOMP ERROR frame'i almadi");
    assertEquals(reason, error.getFirst("message"), "R4: ERROR message basligi");
    assertEventuallyClosed(listener);
    assertTrue(listener.errors.isEmpty(), "R4: bir oturuma birden cok ERROR geldi");
  }

  private static void assertEventuallyClosed(Listener listener) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (listener.session.isConnected()
        && (listener.transport == null || listener.transport.isOpen())
        && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(
        !listener.session.isConnected()
            || (listener.transport != null && !listener.transport.isOpen()),
        "R4: baglanti kapanmadi");
  }

  private static void assertOpen(Listener listener) {
    assertTrue(listener.session.isConnected(), "K1: gecerli baglanti acik kalmali");
    assertFalse(listener.closed.get(), "K1: gecerli transport kapanmamali");
  }

  private void assertRegistrySizeEventually(int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    Integer actual;
    do {
      actual = ReflectionTestUtils.invokeMethod(sessionRegistry, "size");
      if (Integer.valueOf(expected).equals(actual)) {
        return;
      }
      Thread.sleep(50);
    } while (System.nanoTime() < deadline);
    assertEquals(expected, actual, "R6: sunucu oturum kayit sayisi");
  }

  private void assignTask(Tenant a, String taskId, Actor user) {
    HttpResponse<String> response =
        http.put(
            "/api/v1/tasks/" + taskId + "/assignee",
            a.admin(),
            a.workspaceId(),
            "{\"assigneeId\":\"" + user.userId() + "\"}");
    assertEquals(200, response.statusCode(), response.body());
  }

  private WebSocketStompClient observedClient(Listener listener) {
    StandardWebSocketClient delegate = new StandardWebSocketClient();
    WebSocketClient transportClient =
        new WebSocketClient() {
          @Override
          public CompletableFuture<WebSocketSession> execute(
              WebSocketHandler handler, String uri, Object... variables) {
            return capture(delegate.execute(handler, uri, variables));
          }

          @Override
          public CompletableFuture<WebSocketSession> execute(
              WebSocketHandler handler, WebSocketHttpHeaders headers, URI uri) {
            return capture(delegate.execute(handler, headers, uri));
          }

          private CompletableFuture<WebSocketSession> capture(
              CompletableFuture<WebSocketSession> result) {
            return result.thenApply(
                session -> {
                  listener.transport = session;
                  return session;
                });
          }
        };
    WebSocketStompClient client = new WebSocketStompClient(transportClient);
    client.setMessageConverter(new StringMessageConverter());
    return client;
  }

  // Yardimcilar

  private String wsUrl() {
    return "ws://localhost:" + port + "/ws/connect";
  }

  private WebSocketStompClient newClient() {
    WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(new StringMessageConverter());
    return client;
  }

  private CompletableFuture<StompSession> connectAsync(String token, String origin) {
    WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
    if (origin != null) {
      handshake.setOrigin(origin);
    }
    StompHeaders connect = new StompHeaders();
    connect.setHeartbeat(new long[] {0, 0});
    connect.add("Authorization", "Bearer " + token);
    return newClient()
        .connectAsync(wsUrl(), handshake, connect, new StompSessionHandlerAdapter() {});
  }

  /** Ayni kaynakla baglanir, workspace'in proje kanalina abone olur. */
  private Listener listen(String token, Tenant tenant) throws Exception {
    return listenDestination(
        token,
        "/topic/workspace.%s.project.%s".formatted(tenant.workspaceId(), tenant.projectId()));
  }

  private Listener listenDestination(String token, String destination) throws Exception {
    Listener listener = new Listener();
    WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
    handshake.setOrigin("http://localhost:" + port);
    StompHeaders connect = new StompHeaders();
    connect.setHeartbeat(new long[] {0, 0});
    connect.add("Authorization", "Bearer " + token);
    listener.session =
        observedClient(listener)
            .connectAsync(
                wsUrl(),
                handshake,
                connect,
                new StompSessionHandlerAdapter() {
                  @Override
                  public Type getPayloadType(StompHeaders headers) {
                    return String.class;
                  }

                  @Override
                  public void handleFrame(StompHeaders headers, Object payload) {
                    listener.errors.offer(headers);
                  }

                  @Override
                  public void handleTransportError(StompSession s, Throwable exception) {
                    listener.closed.set(true);
                  }
                })
            .get(10, TimeUnit.SECONDS);
    assertNotNull(listener.session);
    listener.session.subscribe(
        destination,
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return String.class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            listener.messages.offer((String) payload);
          }
        });
    Thread.sleep(500); // STOMP'ta SUBSCRIBE icin senkron onay yok.
    return listener;
  }

  /** HTTP ile gorev olusturur; olay gövdesinde aranacak gorev id'sini doner. */
  private String createTask(Tenant tenant, String title) {
    HttpResponse<String> r =
        http.post(
            "/api/v1/projects/" + tenant.projectId() + "/tasks",
            tenant.admin(),
            tenant.workspaceId(),
            "{\"title\":\"" + title + " " + UUID.randomUUID() + "\"}");
    assertEquals(201, r.statusCode(), r.body());
    return mapper.readTree(r.body()).path("id").asString("");
  }

  private static void drain(Listener l) {
    l.messages.clear();
  }

  private static void close(Listener l) {
    if (l.session != null && l.session.isConnected()) {
      l.session.disconnect();
    }
  }

  private static String refreshCookie(HttpResponse<String> response) {
    return response.headers().allValues("Set-Cookie").stream()
        .filter(c -> c.startsWith("refresh_token="))
        .map(c -> c.substring("refresh_token=".length(), c.indexOf(';')))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Set-Cookie refresh_token yok"));
  }
}
