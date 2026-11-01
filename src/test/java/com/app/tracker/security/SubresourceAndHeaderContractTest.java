package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.realtime.WebSocketAuthInterceptor;
import com.app.tracker.core.realtime.WebSocketDeliveryGuard;
import com.app.tracker.core.realtime.WebSocketPrincipal;
import com.app.tracker.core.realtime.WebSocketSessionRegistry;
import com.app.tracker.core.security.AuthController;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.security.CorsProperties;
import com.app.tracker.core.security.DecodedJwt;
import com.app.tracker.core.security.JwtService;
import com.app.tracker.core.security.JwtValidationException;
import com.app.tracker.core.web.ClientIpResolver;
import com.app.tracker.retro.RetroItemRepository;
import com.app.tracker.retro.RetroItemService;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Actor;
import com.app.tracker.security.support.SecurityHttp;
import com.app.tracker.security.support.TenantResources;
import com.app.tracker.security.support.TenantResources.Type;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.repository.SprintRepository;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskRepository;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.user.repository.UserRepository;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompDecoder;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Expectations come from dilim-03-sozlesme.md. Flat companion class runs without Docker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubresourceAndHeaderContractTest extends AbstractIntegrationTest {
  @LocalServerPort private int port;
  @Autowired private ApplicationContext context;
  @Autowired private ObjectMapper mapper;
  @Autowired private CorsProperties cors;
  @Autowired private SimpMessagingTemplate broker;
  private SecurityFixture fixture;
  private SecurityHttp http;

  @BeforeAll
  void setUp() {
    fixture = SecurityFixture.create(context, port);
    http = fixture.http();
  }

  private TenantResources resources(SecurityFixture.Tenant tenant) {
    return new TenantResources(fixture, mapper, tenant, "D03-" + UUID.randomUUID());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void K1_visibleTaskReturnsEmptyOrPopulatedSubtasks(boolean populated) throws Exception {
    TenantResources own = resources(fixture.a());
    UUID task = own.id(Type.TASK);
    UUID child = populated ? own.id(Type.SUBTASK) : null;
    JsonNode list = okList(getTaskChildren(task));
    assertEquals(populated ? 1 : 0, list.size());
    if (populated) {
      assertEquals(child.toString(), list.get(0).path("id").asString());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "foreign", "deleted"})
  void R1_invisibleTaskMatchesParent404Shape(String kind) throws Exception {
    TenantResources own = resources(fixture.a());
    UUID id =
        switch (kind) {
          case "foreign" -> resources(fixture.b()).id(Type.TASK);
          case "deleted" -> own.id(Type.TASK);
          default -> UUID.randomUUID();
        };
    if (kind.equals("deleted")) {
      assertEquals(
          204,
          http.delete("/api/v1/tasks/" + id, own.admin(), fixture.a().workspaceId(), null)
              .statusCode());
    }
    HttpResponse<String> parent =
        http.get(
            "/api/v1/tasks/" + id + "/detail",
            fixture.a().admin(),
            fixture.a().workspaceId(),
            null);
    HttpResponse<String> children = getTaskChildren(id);
    assertEquals(404, parent.statusCode(), parent.body());
    assertEquals(404, children.statusCode(), children.body());
    JsonNode expected = mapper.readTree(parent.body());
    JsonNode actual = mapper.readTree(children.body());
    assertEquals(expected.propertyNames(), actual.propertyNames());
    for (String field : List.of("status", "title", "type", "detail")) {
      assertFalse(expected.path(field).isMissingNode(), "404 field: " + field);
      assertEquals(expected.path(field), actual.path(field), field);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void K2_visibleSprintReturnsEmptyOrPopulatedRetro(boolean populated) throws Exception {
    TenantResources own = resources(fixture.a());
    UUID sprint = own.id(Type.SPRINT);
    UUID item = populated ? own.id(Type.RETRO_ITEM) : null;
    JsonNode list = okList(getRetro(sprint));
    assertEquals(populated ? 1 : 0, list.size());
    if (populated) {
      assertEquals(item.toString(), list.get(0).path("id").asString());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void R2_missingOrForeignSprintReturns404(boolean foreign) {
    UUID id = foreign ? resources(fixture.b()).id(Type.SPRINT) : UUID.randomUUID();
    HttpResponse<String> response = getRetro(id);
    assertEquals(404, response.statusCode(), response.body());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void K3_memberProjectAndUserQueueSubscriptionsReceiveMessages(boolean userQueue)
      throws Exception {
    try (WireStomp client = connect()) {
      String destination = userQueue ? "/user/queue/notifications" : topic(fixture.a());
      client.subscribe(destination);
      String probe = "D03-" + UUID.randomUUID();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      Message<byte[]> received = null;
      // The simple broker has no receipts. Repeated probes cover asynchronous SUBSCRIBE setup.
      while (received == null && System.nanoTime() < deadline) {
        if (userQueue) {
          broker.convertAndSendToUser(
              fixture.a().developer().userId().toString(), "/queue/notifications", probe);
        } else {
          broker.convertAndSend(destination, probe);
        }
        received = client.frames.poll(200, TimeUnit.MILLISECONDS);
      }
      assertNotNull(received, "kabul abonesine gercek mesaj ulasmali");
      assertEquals(StompCommand.MESSAGE, StompHeaderAccessor.wrap(received).getCommand());
      assertTrue(new String(received.getPayload(), StandardCharsets.UTF_8).contains(probe));
    }
  }

  @Test
  void R3_invalidDestinationErrorHeaderAndBodyNeverEchoTarget() throws Exception {
    String target = "/topic/evil<script>";
    try (WireStomp client = connect()) {
      client.subscribe(target);
      assertWireError(client.expect(StompCommand.ERROR), "Gecersiz kanal.", target);
    }
  }

  @Test
  void R4_foreignWorkspaceErrorHeaderAndBodyNeverEchoUuid() throws Exception {
    try (WireStomp client = connect()) {
      client.subscribe(topic(fixture.b()));
      assertWireError(
          client.expect(StompCommand.ERROR),
          "Bu kanala erisim yetkiniz yok.",
          fixture.b().workspaceId().toString());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"same-origin", "ABSENT", "none", "foo"})
  void K4_K5_originlessRefreshAndLogoutAcceptPermittedFetchSite(String site) throws Exception {
    String cookie = loginCookie();
    HttpResponse<String> refreshed = cookiePost("refresh", cookie, site);
    assertRefresh(refreshed, cookie);
    assertEquals(204, cookiePost("logout", refreshCookie(refreshed), site).statusCode());
  }

  @ParameterizedTest
  @MethodSource("rejectedRequests")
  void R6_R7_rejectionDoesNotConsumeRefreshOrRevokeLogoutToken(String endpoint, String site)
      throws Exception {
    HttpResponse<String> login = login();
    String cookie = refreshCookie(login);
    HttpResponse<String> rejected = cookiePost(endpoint, cookie, site);
    assertEquals(400, rejected.statusCode(), rejected.body());
    assertEquals(
        "Geçersiz istek kaynağı.", mapper.readTree(rejected.body()).path("detail").asString());
    assertTrue(
        rejected.headers().allValues("Set-Cookie").isEmpty(), "ret cookie'yi degistirmemeli");
    if (endpoint.equals("logout")) {
      String access = mapper.readTree(login.body()).path("accessToken").asString();
      HttpResponse<String> stillAuthenticated =
          http.get("/api/v1/projects", access, fixture.a().workspaceId(), null);
      assertEquals(
          200,
          stillAuthenticated.statusCode(),
          "reddedilen logout access token'i iptal etmemeli: " + stillAuthenticated.body());
    }
    // Real server proof for BOTH endpoints: the exact rejected cookie still refreshes.
    HttpResponse<String> accepted = cookiePost("refresh", cookie, "same-origin");
    assertRefresh(accepted, cookie);
    assertEquals(204, cookiePost("logout", refreshCookie(accepted), "same-origin").statusCode());
  }

  static Stream<Arguments> rejectedRequests() {
    return Stream.of("refresh", "logout")
        .flatMap(
            endpoint ->
                Stream.of("cross-site", "same-site", "CrOsS-SiTe", "SaMe-SiTe")
                    .map(site -> Arguments.of(endpoint, site)));
  }

  private HttpResponse<String> getTaskChildren(UUID id) {
    return http.get(
        "/api/v1/tasks/" + id + "/subtasks", fixture.a().admin(), fixture.a().workspaceId(), null);
  }

  private HttpResponse<String> getRetro(UUID id) {
    return http.get(
        "/api/v1/sprints/" + id + "/retro/items",
        fixture.a().admin(),
        fixture.a().workspaceId(),
        null);
  }

  private JsonNode okList(HttpResponse<String> response) throws Exception {
    assertEquals(200, response.statusCode(), response.body());
    JsonNode list = mapper.readTree(response.body());
    assertTrue(list.isArray(), response.body());
    return list;
  }

  private String loginCookie() throws Exception {
    return refreshCookie(login());
  }

  private HttpResponse<String> login() throws Exception {
    Actor actor = fixture.a().admin();
    HttpResponse<String> login =
        http.send(
            "POST",
            "/api/v1/auth/login",
            null,
            null,
            mapper.writeValueAsString(
                Map.of("email", actor.email(), "password", SecurityFixture.PASSWORD)),
            Map.of());
    assertEquals(200, login.statusCode(), login.body());
    return login;
  }

  private String refreshCookie(HttpResponse<String> response) {
    return response.headers().allValues("Set-Cookie").stream()
        .filter(value -> value.startsWith("refresh_token="))
        .map(value -> value.split(";", 2)[0])
        .findFirst()
        .orElseThrow();
  }

  private HttpResponse<String> cookiePost(String endpoint, String cookie, String site) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Cookie", cookie);
    if (!site.equals("ABSENT")) {
      headers.put("Sec-Fetch-Site", site);
    }
    return http.send("POST", "/api/v1/auth/" + endpoint, null, null, null, headers);
  }

  private void assertRefresh(HttpResponse<String> response, String previousCookie)
      throws Exception {
    assertEquals(200, response.statusCode(), response.body());
    assertFalse(mapper.readTree(response.body()).path("accessToken").asString("").isBlank());
    assertNotEquals(previousCookie, refreshCookie(response), "refresh yeni token vermeli");
  }

  private String topic(SecurityFixture.Tenant tenant) {
    return "/topic/workspace." + tenant.workspaceId() + ".project." + tenant.projectId();
  }

  private WireStomp connect() throws Exception {
    String origin =
        cors.getAllowedOrigins().isEmpty()
            ? "http://localhost:" + port
            : cors.getAllowedOrigins().get(0);
    WireStomp client = new WireStomp();
    try {
      client.socket =
          HttpClient.newHttpClient()
              .newWebSocketBuilder()
              .header("Origin", origin)
              .subprotocols("v12.stomp")
              .buildAsync(URI.create("ws://localhost:" + port + "/ws/connect"), client)
              .get(10, TimeUnit.SECONDS);
      client.send(
          "CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer "
              + fixture.a().developer().accessToken()
              + "\n\n\0");
      client.expect(StompCommand.CONNECTED);
      return client;
    } catch (Exception | AssertionError error) {
      client.close();
      throw error;
    }
  }

  private void assertWireError(Message<byte[]> frame, String fixedText, String secret) {
    String header = StompHeaderAccessor.wrap(frame).getMessage();
    String body = new String(frame.getPayload(), StandardCharsets.UTF_8);
    assertNotNull(header, "gercek ERROR message basligi gerekli");
    assertTrue(header.contains(fixedText), header);
    assertFalse(header.contains(secret), header);
    assertFalse(body.contains(secret), body);
    // Spring may wrap the constant in a delivery exception; body may be empty.
    assertTrue(body.isEmpty() || body.contains(fixedText), body);
  }

  /** Reads actual server frames, including ERROR payload, without relying on a client exception. */
  private static final class WireStomp implements WebSocket.Listener, AutoCloseable {
    private final BlockingQueue<Message<byte[]>> frames = new LinkedBlockingQueue<>();
    private final StringBuilder pending = new StringBuilder();
    private volatile Throwable failure;
    private WebSocket socket;

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      pending.append(data);
      int end;
      while ((end = pending.indexOf("\0")) >= 0) {
        byte[] bytes = pending.substring(0, end + 1).getBytes(StandardCharsets.UTF_8);
        pending.delete(0, end + 1);
        frames.addAll(new StompDecoder().decode(ByteBuffer.wrap(bytes)));
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      failure = error;
    }

    void send(String frame) throws Exception {
      socket.sendText(frame, true).get(10, TimeUnit.SECONDS);
    }

    void subscribe(String destination) throws Exception {
      send(
          "SUBSCRIBE\nid:contract\ndestination:"
              + destination
              + "\nreceipt:sub-ok\nack:auto\n\n\0");
    }

    Message<byte[]> expect(StompCommand command) throws Exception {
      Message<byte[]> frame = frames.poll(10, TimeUnit.SECONDS);
      assertNotNull(frame, "STOMP " + command + " gelmedi: " + failure);
      assertEquals(command, StompHeaderAccessor.wrap(frame).getCommand(), frame.toString());
      return frame;
    }

    @Override
    public void close() {
      if (socket != null) {
        socket.abort();
      }
    }
  }
}

/** Docker-free acceptance pairs and mutation checks; explicitly select this flat class in Maven. */
class SubresourceAndHeaderServiceContractTest {
  private TaskRepository tasks;
  private TaskService taskService;
  private RetroItemRepository items;
  private SprintRepository sprints;
  private RetroItemService retro;
  private JwtService jwt;
  private StringRedisTemplate redis;
  private WorkspaceMembershipService membership;
  private WebSocketSessionRegistry registry;
  private WebSocketAuthInterceptor ws;
  private AuthService auth;
  private AuthController controller;
  private final UUID id = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-10-31T09:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    tasks = mock(TaskRepository.class);
    taskService =
        new TaskService(
            null, tasks, null, null, null, null, null, null, null, null, clock, null, null);
    items = mock(RetroItemRepository.class);
    sprints = mock(SprintRepository.class);
    retro = new RetroItemService(items, sprints, taskService, mock(UserRepository.class));
    jwt = mock(JwtService.class);
    redis = mock(StringRedisTemplate.class);
    membership = mock(WorkspaceMembershipService.class);
    registry = new WebSocketSessionRegistry();
    ws = new WebSocketAuthInterceptor(jwt, redis, membership, registry, clock);
    auth = mock(AuthService.class);
    CorsProperties cors = new CorsProperties();
    cors.setAllowedOrigins(List.of("https://app.example"));
    controller = new AuthController(auth, cors, mock(ClientIpResolver.class));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void K1_visibleTaskListsEmptyAndNonemptyChildren(boolean populated) {
    Task parent = mock(Task.class);
    when(tasks.findById(id)).thenReturn(Optional.of(parent));
    List<Task> expected = populated ? List.of(mock(Task.class)) : List.of();
    when(tasks.findByParentTaskIdOrderByTaskNumber(id)).thenReturn(expected);
    assertEquals(expected, taskService.listChildren(id));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void R1_missingOrSoftDeletedTaskRejectsBeforeListing(boolean deleted) {
    Task parent = mock(Task.class);
    if (deleted) {
      when(parent.isDeleted()).thenReturn(true);
    }
    when(tasks.findById(id)).thenReturn(deleted ? Optional.of(parent) : Optional.empty());
    assertThrows(ResourceNotFoundException.class, () -> taskService.listChildren(id));
    verify(tasks, never()).findByParentTaskIdOrderByTaskNumber(any());
  }

  @Test
  void K2_visibleSprintCanHaveNoRetroItems() {
    when(sprints.findById(id)).thenReturn(Optional.of(mock(Sprint.class)));
    when(items.findBySprintId(id)).thenReturn(List.of());
    assertEquals(List.of(), retro.list(id));
  }

  @Test
  void R2_invisibleSprintRejectsBeforeListing() {
    when(sprints.findById(id)).thenReturn(Optional.empty());
    assertThrows(ResourceNotFoundException.class, () -> retro.list(id));
    verifyNoInteractions(items);
  }

  @Test
  void K1_K2_R2_parentLookupsExecuteInsideServiceTransaction() {
    // Real Spring transaction advice, with no external resource: observe the repository boundary.
    AbstractPlatformTransactionManager manager =
        new AbstractPlatformTransactionManager() {
          @Override
          protected Object doGetTransaction() {
            return new Object();
          }

          @Override
          protected void doBegin(Object transaction, TransactionDefinition definition) {}

          @Override
          protected void doCommit(DefaultTransactionStatus status) {}

          @Override
          protected void doRollback(DefaultTransactionStatus status) {}
        };
    TransactionInterceptor advice =
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource());
    when(tasks.findById(id))
        .thenAnswer(
            call -> {
              assertTrue(
                  TransactionSynchronizationManager.isActualTransactionActive(),
                  "task parent lookup");
              return Optional.of(mock(Task.class));
            });
    when(tasks.findByParentTaskIdOrderByTaskNumber(id))
        .thenAnswer(
            call -> {
              assertTrue(
                  TransactionSynchronizationManager.isActualTransactionActive(),
                  "task children lookup");
              return List.of();
            });
    when(sprints.findById(id))
        .thenAnswer(
            call -> {
              assertTrue(
                  TransactionSynchronizationManager.isActualTransactionActive(),
                  "sprint parent lookup");
              return Optional.of(mock(Sprint.class));
            });
    when(items.findBySprintId(id))
        .thenAnswer(
            call -> {
              assertTrue(
                  TransactionSynchronizationManager.isActualTransactionActive(),
                  "retro items lookup");
              return List.of();
            });
    assertEquals(List.of(), ((TaskService) transactional(taskService, advice)).listChildren(id));
    assertEquals(List.of(), ((RetroItemService) transactional(retro, advice)).list(id));
    assertFalse(
        TransactionSynchronizationManager.isActualTransactionActive(),
        "transaction must be closed");
  }

  private Object transactional(Object service, TransactionInterceptor advice) {
    ProxyFactory proxy = new ProxyFactory(service);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(advice);
    return proxy.getProxy();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void K3_memberProjectAndUserQueueAreAccepted(boolean userQueue) {
    String destination = userQueue ? "/user/queue/notifications" : topic();
    if (!userQueue) {
      when(membership.findRole(id, id)).thenReturn(Optional.of(WorkspaceRole.DEVELOPER));
    }
    Message<byte[]> message = subscribe(destination, true);
    assertSame(message, ws.preSend(message, null));
    if (userQueue) {
      verifyNoInteractions(membership);
    }
  }

  @Test
  void R3_invalidTargetProducesOnlyFixedText() {
    String target = "/topic/evil<script>";
    MessagingException error =
        assertThrows(MessagingException.class, () -> ws.preSend(subscribe(target, true), null));
    assertEquals("Gecersiz kanal.", error.getMessage());
    assertFalse(error.getMessage().contains(target));
  }

  @Test
  void R4_foreignWorkspaceProducesOnlyFixedText() {
    when(membership.findRole(id, id)).thenReturn(Optional.empty());
    MessagingException error =
        assertThrows(MessagingException.class, () -> ws.preSend(subscribe(topic(), true), null));
    assertEquals("Bu kanala erisim yetkiniz yok.", error.getMessage());
    assertFalse(error.getMessage().contains(id.toString()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-auth", "invalid-auth", "missing-target", "missing-principal"})
  void R5_existingInterceptorMessagesRemainFixed(String branch) {
    Message<byte[]> message;
    String expected;
    switch (branch) {
      case "missing-auth" -> {
        message = connect(null);
        expected = "Authorization header eksik.";
      }
      case "invalid-auth" -> {
        when(jwt.verify("invalid")).thenThrow(new JwtValidationException("private detail"));
        message = connect("invalid");
        expected = "Kimlik dogrulama basarisiz.";
      }
      case "missing-target" -> {
        message = subscribe(null, true);
        expected = "Hedef kanal belirtilmemis.";
      }
      default -> {
        message = subscribe(topic(), false);
        expected = "Once kimlik dogrulanmali.";
      }
    }
    assertEquals(
        expected,
        assertThrows(MessagingException.class, () -> ws.preSend(message, null)).getMessage());
  }

  @Test
  void K3_R5_validConnectAuthenticatesAndDeliveryIsAccepted() {
    authenticate(now.plusSeconds(60));
    Message<byte[]> outbound = outbound();
    assertSame(
        outbound,
        new WebSocketDeliveryGuard(registry, clock, redis, membership).preSend(outbound, null));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void R5_deliveryGuardExpiryAndRevocationMessagesRemainFixed(boolean expired) {
    authenticate(expired ? now.minusSeconds(1) : now.plusSeconds(60));
    if (!expired) {
      when(redis.hasKey("jwt:blacklist:jti")).thenReturn(true);
    }
    Message<?> error =
        new WebSocketDeliveryGuard(registry, clock, redis, membership).preSend(outbound(), null);
    assertNotNull(error);
    StompHeaderAccessor headers = StompHeaderAccessor.wrap(error);
    assertEquals(StompCommand.ERROR, headers.getCommand());
    assertEquals(expired ? "Oturum suresi doldu" : "Yetki geri alindi", headers.getMessage());
  }

  @ParameterizedTest
  @MethodSource("cookieDecisions")
  void K4_K5_R6_R7_originAndFetchSiteDecisionTable(
      String endpoint, String origin, String site, boolean reject) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (origin != null) {
      request.addHeader("Origin", origin);
    }
    if (site != null) {
      request.addHeader("Sec-Fetch-Site", site);
    }
    if (reject) {
      BusinessRuleException error =
          assertThrows(BusinessRuleException.class, () -> cookieCall(endpoint, request));
      assertEquals("Geçersiz istek kaynağı.", error.getMessage());
      verifyNoInteractions(auth);
      // Rejection must happen before rotating/revoking the token; acceptance pair uses same cookie.
      request.removeHeader("Origin");
      request.removeHeader("Sec-Fetch-Site");
      request.addHeader("Sec-Fetch-Site", "same-origin");
    }
    if (endpoint.equals("refresh")) {
      when(auth.refresh("cookie"))
          .thenReturn(new AuthService.LoginResult("access", "next", Duration.ofDays(7)));
    }
    ResponseEntity<?> accepted = cookieCall(endpoint, request);
    assertEquals(endpoint.equals("refresh") ? 200 : 204, accepted.getStatusCode().value());
    if (endpoint.equals("refresh")) {
      verify(auth).refresh("cookie");
    } else {
      verify(auth).logout("cookie");
    }
    verifyNoMoreInteractions(auth);
  }

  static Stream<Arguments> cookieDecisions() {
    List<Arguments> cases = new ArrayList<>();
    for (String endpoint : List.of("refresh", "logout")) {
      for (String site :
          new String[] {
            null, "same-origin", "none", "foo", "same-site", "cross-site", "SaMe-SiTe", "CrOsS-SiTe"
          }) {
        boolean cross =
            site != null
                && (site.equalsIgnoreCase("same-site") || site.equalsIgnoreCase("cross-site"));
        cases.add(Arguments.of(endpoint, null, site, cross));
        cases.add(Arguments.of(endpoint, "https://app.example", site, false));
        cases.add(Arguments.of(endpoint, "https://evil.example", site, true));
      }
    }
    return cases.stream();
  }

  private ResponseEntity<?> cookieCall(String endpoint, MockHttpServletRequest request) {
    return endpoint.equals("refresh")
        ? controller.refresh("cookie", request)
        : controller.logout("cookie", request);
  }

  private String topic() {
    return "/topic/workspace." + id + ".project." + UUID.randomUUID();
  }

  private Message<byte[]> subscribe(String destination, boolean authenticated) {
    StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    headers.setSessionId("session");
    headers.setDestination(destination);
    if (authenticated) {
      headers.setUser(new WebSocketPrincipal(id));
    }
    return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
  }

  private Message<byte[]> connect(String token) {
    StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.CONNECT);
    headers.setSessionId("session");
    if (token != null) {
      headers.setNativeHeader("Authorization", "Bearer " + token);
    }
    headers.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
  }

  private void authenticate(Instant expiry) {
    when(jwt.verify("valid")).thenReturn(new DecodedJwt(id, List.of(), "jti", expiry));
    Message<byte[]> connect = connect("valid");
    assertSame(connect, ws.preSend(connect, null));
    assertEquals(new WebSocketPrincipal(id), StompHeaderAccessor.wrap(connect).getUser());
  }

  private Message<byte[]> outbound() {
    StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.MESSAGE);
    headers.setSessionId("session");
    headers.setDestination("/queue/notifications");
    return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
  }
}
