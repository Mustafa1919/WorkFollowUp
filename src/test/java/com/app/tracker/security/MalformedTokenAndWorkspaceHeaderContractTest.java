package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.realtime.WebSocketAuthInterceptor;
import com.app.tracker.core.realtime.WebSocketSessionRegistry;
import com.app.tracker.core.security.DecodedJwt;
import com.app.tracker.core.security.JwtKeyProvider;
import com.app.tracker.core.security.JwtProperties;
import com.app.tracker.core.security.JwtService;
import com.app.tracker.core.security.JwtValidationException;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.core.tenancy.WorkspaceContextFilter;
import com.app.tracker.security.support.JwtForge;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Tenant;
import com.app.tracker.security.support.SecurityHttp;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.NestedTestConfiguration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dilim 02: expectations come from the contract. Outer tests need no Docker; ServletContract uses a
 * real RANDOM_PORT servlet, including container error dispatch. No MockMvc.
 */
class MalformedTokenAndWorkspaceHeaderContractTest {

  private static final String PROBLEM =
      "{\"type\":\"https://api.app.com/errors/bad-request\",\"title\":\"Gecersiz Istek\","
          + "\"status\":400,\"detail\":\"X-Workspace-Id basligi gecersiz.\"}";
  private static final UUID USER = UUID.fromString("7a8b9c0d-1111-4222-8333-123456abcdef");
  private static final UUID WORKSPACE = UUID.fromString("abcdef12-3456-4789-abcd-123456abcdef");
  private static final String JTI = "contract-sensitive-jti-canary";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private final JwtProperties properties = properties();
  private final JwtKeyProvider keys =
      new JwtKeyProvider(properties, new DefaultResourceLoader(), new StandardEnvironment());
  private final JwtService jwt = new JwtService(keys, properties, MAPPER);
  private final JwtForge forge = new JwtForge(keys);

  private static JwtProperties properties() {
    JwtProperties result = new JwtProperties();
    result.setPrivateKeyLocation("classpath:keys/dev-jwt-private.pem");
    result.setPublicKeyLocation("classpath:keys/dev-jwt-public.pem");
    result.setAccessTokenTtl(Duration.ofMinutes(15));
    return result;
  }

  @AfterEach
  void clearThreadState() {
    SecurityContextHolder.clearContext();
    TenantContext.clear();
  }

  @Test
  void K1_issuedTokenPreservesAllDecodedFieldsAndExpiry() {
    long before = Instant.now().getEpochSecond();
    String token = jwt.issueAccessToken(USER, List.of("USER", "SYSTEM_ADMIN"), JTI);
    long after = Instant.now().getEpochSecond();
    DecodedJwt decoded = assertDoesNotThrow(() -> jwt.verify(token));
    assertAll(
        () -> assertEquals(USER, decoded.userId()),
        () -> assertEquals(List.of("USER", "SYSTEM_ADMIN"), decoded.roles()),
        () -> assertEquals(JTI, decoded.jti()),
        () -> assertTrue(decoded.expiresAt().getEpochSecond() >= before + 900),
        () -> assertTrue(decoded.expiresAt().getEpochSecond() <= after + 900));
  }

  @Test
  void K2_K1_expiredTokenHasExactControlledMessageAndFreshTwinPasses() {
    Map<String, Object> claims = validClaims(USER);
    claims.put("exp", Instant.now().minusSeconds(60).getEpochSecond());
    String token = forge.signRs256(MAPPER.writeValueAsString(claims));
    JwtValidationException failure =
        assertThrowsExactly(JwtValidationException.class, () -> jwt.verify(token));
    assertEquals("Token suresi dolmus.", failure.getMessage());
    assertValidTwin();
  }

  @Test
  void R1_K1_invalidSignatureBase64IsControlledAndValidTwinPasses() throws Exception {
    assertRuleRejected("R1");
  }

  @Test
  void R2_K1_invalidPayloadBase64WithValidSignatureIsControlledAndValidTwinPasses()
      throws Exception {
    assertRuleRejected("R2");
  }

  @Test
  void R3_K1_missingOrNonNumericExpiryIsControlledAndValidTwinPasses() throws Exception {
    assertRuleRejected("R3");
  }

  @Test
  void R4_K1_missingNonStringOrNonUuidSubjectIsControlledAndValidTwinPasses() throws Exception {
    assertRuleRejected("R4");
  }

  @Test
  void R5_K1_missingNonListOrNonStringRolesAreControlledAndValidTwinPasses() throws Exception {
    assertRuleRejected("R5");
  }

  @Test
  void R6_K1_missingEmptyOrNonStringJtiIsControlledAndValidTwinPasses() throws Exception {
    assertRuleRejected("R6");
  }

  @Test
  void R7_K1_nonObjectOrInvalidJsonIsControlledAndValidTwinPasses() throws Exception {
    assertRuleRejected("R7");
  }

  @Test
  void R3_K1_outOfRangeExpiryLeaksNoOtherRuntimeException() {
    assertValidTwin();
    Map<String, Object> claims = validClaims(USER);
    claims.put("exp", Long.MAX_VALUE);
    String token = forge.signRs256(MAPPER.writeValueAsString(claims));
    assertThrowsExactly(JwtValidationException.class, () -> jwt.verify(token));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "a", "a.b", "a.b.c.d", "a.b.", "...", " ", "a.b.c\u0000"})
  void R1_K1_malformedCompactStructureHasOnlyControlledFailure(String token) {
    assertValidTwin();
    assertThrowsExactly(JwtValidationException.class, () -> jwt.verify(token));
  }

  @Test
  void R1_K1_nullInputHasOnlyControlledFailure() {
    assertValidTwin();
    assertThrowsExactly(JwtValidationException.class, () -> jwt.verify(null));
  }

  @Test
  void R1_R2_R3_R4_R5_R6_R7_K1_stompConnectRejectsBadTokensAndAcceptsValidTwins() throws Exception {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.hasKey(anyString())).thenReturn(false);
    WebSocketAuthInterceptor interceptor =
        new WebSocketAuthInterceptor(
            jwt,
            redis,
            mock(WorkspaceMembershipService.class),
            new WebSocketSessionRegistry(),
            Clock.systemUTC());
    MessageChannel channel = mock(MessageChannel.class);
    assertAll(
        malformedTokens(forge, keys, USER).stream()
            .map(
                bad ->
                    (org.junit.jupiter.api.function.Executable)
                        () -> {
                          StompHeaderAccessor accepted =
                              connect(jwt.issueAccessToken(USER, List.of("USER"), JTI));
                          Message<byte[]> good = frame(accepted);
                          assertSame(good, interceptor.preSend(good, channel));
                          assertNotNull(accepted.getUser());
                          assertEquals(USER.toString(), accepted.getUser().getName());
                          StompHeaderAccessor rejected = connect(bad.token());
                          Message<byte[]> message = frame(rejected);
                          MessagingException failure =
                              assertThrowsExactly(
                                  MessagingException.class,
                                  () -> interceptor.preSend(message, channel),
                                  bad.label());
                          assertInstanceOf(JwtValidationException.class, failure.getCause());
                          assertNull(rejected.getUser(), bad.label());
                        }));
  }

  private static StompHeaderAccessor connect(String token) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setSessionId(UUID.randomUUID().toString());
    accessor.setNativeHeader("Authorization", "Bearer " + token);
    accessor.setLeaveMutable(true);
    return accessor;
  }

  private static Message<byte[]> frame(StompHeaderAccessor accessor) {
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  private void assertValidTwin() {
    String token = jwt.issueAccessToken(USER, List.of("USER"), JTI);
    DecodedJwt decoded = assertDoesNotThrow(() -> jwt.verify(token));
    assertEquals(USER, decoded.userId());
    assertEquals(List.of("USER"), decoded.roles());
    assertEquals(JTI, decoded.jti());
  }

  private void assertRuleRejected(String rule) throws Exception {
    List<BadToken> cases =
        malformedTokens(forge, keys, USER).stream().filter(b -> b.rule().equals(rule)).toList();
    assertFalse(cases.isEmpty());
    assertAll(
        cases.stream()
            .map(
                bad ->
                    (org.junit.jupiter.api.function.Executable)
                        () -> {
                          assertValidTwin();
                          JwtValidationException failure =
                              assertThrowsExactly(
                                  JwtValidationException.class,
                                  () -> jwt.verify(bad.token()),
                                  bad.label());
                          assertNoInternalDetails(failure.getMessage());
                          assertFalse(failure.getMessage().contains(JTI));
                          assertFalse(failure.getMessage().contains(USER.toString()));
                          assertFalse(failure.getMessage().contains("sensitive-claim-canary"));
                          assertFalse(failure.getMessage().contains(bad.token()));
                        }));
  }

  private record BadToken(String rule, String label, String token) {}

  private static Map<String, Object> validClaims(UUID user) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("sub", user.toString());
    claims.put("roles", List.of("USER"));
    claims.put("jti", JTI);
    claims.put("exp", Instant.now().plusSeconds(600).getEpochSecond());
    return claims;
  }

  private static List<BadToken> malformedTokens(JwtForge forge, JwtKeyProvider keys, UUID user)
      throws Exception {
    List<BadToken> result = new ArrayList<>();
    result.add(new BadToken("R1", "R1 literal invalid signature", "a.b.%%%"));
    String valid = forge.signRs256(MAPPER.writeValueAsString(validClaims(user)));
    result.add(
        new BadToken(
            "R1",
            "R1 valid body invalid signature",
            valid.substring(0, valid.lastIndexOf('.') + 1) + "%%%"));
    result.add(
        new BadToken("R2", "R2 invalid payload, valid RSA signature", signRawPayload(keys, "%%%")));
    addClaimCases(
        result,
        forge,
        user,
        "R3",
        "exp",
        List.of("null", "\"sensitive-claim-canary\"", "true", "[]", "{}"));
    addClaimCases(
        result,
        forge,
        user,
        "R4",
        "sub",
        List.of("null", "42", "{}", "\"sensitive-claim-canary\""));
    addClaimCases(
        result,
        forge,
        user,
        "R5",
        "roles",
        List.of("null", "\"USER\"", "{}", "[\"USER\",42]", "[\"USER\",null]", "[true]"));
    addClaimCases(result, forge, user, "R6", "jti", List.of("null", "\"\"", "42", "[]"));
    for (String body : List.of("[]", "\"sensitive-claim-canary\"", "null", "42", "true", "{")) {
      result.add(new BadToken("R7", "R7 body=" + body, forge.signRs256(body)));
    }
    return result;
  }

  private static void addClaimCases(
      List<BadToken> result,
      JwtForge forge,
      UUID user,
      String rule,
      String field,
      List<String> values) {
    Map<String, Object> missing = validClaims(user);
    missing.remove(field);
    result.add(
        new BadToken(
            rule, rule + " missing " + field, forge.signRs256(MAPPER.writeValueAsString(missing))));
    for (String raw : values) {
      Map<String, Object> claims = validClaims(user);
      claims.put(field, MAPPER.readValue(raw, Object.class));
      result.add(
          new BadToken(
              rule,
              rule + " " + field + "=" + raw,
              forge.signRs256(MAPPER.writeValueAsString(claims))));
    }
  }

  private static String signRawPayload(JwtKeyProvider keys, String encodedPayload)
      throws Exception {
    String input = JwtForge.b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "." + encodedPayload;
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keys.getPrivateKey());
    signature.update(input.getBytes(StandardCharsets.UTF_8));
    return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
  }

  @Test
  void R9_K4_nonUuidHeaderIsRejectedAndMemberTwinPasses() throws Exception {
    rejectHeaderAndAcceptTwin("not-a-uuid");
  }

  @Test
  void R10_K4_emptyHeaderIsRejectedAndMemberTwinPasses() throws Exception {
    rejectHeaderAndAcceptTwin("");
  }

  @Test
  void R11_K4_twoThousandCharacterHeaderIsRejectedAndMemberTwinPasses() throws Exception {
    rejectHeaderAndAcceptTwin("a".repeat(2000));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1-1-1-1-1",
        "abcdef12-3456-4789-abcd-123456abcdef0",
        "abcdef12-3456-4789-abcd-123456abcdeg",
        "+bcdef12-3456-4789-abcd-123456abcdef"
      })
  void R12_K4_nonCanonicalHeaderIsRejectedAndCanonicalTwinPasses(String header) throws Exception {
    rejectHeaderAndAcceptTwin(header);
  }

  @Test
  void R13_K4_duplicateHeadersAreRejectedForBothOrdersAndEqualValues() throws Exception {
    String member = WORKSPACE.toString();
    String other = UUID.randomUUID().toString();
    for (List<String> pair :
        List.of(
            List.of(member, member),
            List.of(member, other),
            List.of(other, member),
            List.of(member, "not-a-uuid"),
            List.of("not-a-uuid", member))) {
      rejectHeaderAndAcceptTwin(pair.toArray(String[]::new));
    }
  }

  @Test
  void K4_memberHeaderAcceptsBothCasesAndClearsContextAfterChain() throws Exception {
    assertMemberHeader(WORKSPACE.toString());
    assertMemberHeader(WORKSPACE.toString().toUpperCase(Locale.ROOT));
  }

  @Test
  void K5_K4_nonMemberGetsDirect403AndMemberTwinPasses() throws Exception {
    authenticate();
    WorkspaceMembershipService membership = mock(WorkspaceMembershipService.class);
    when(membership.findRole(USER, WORKSPACE)).thenReturn(Optional.empty());
    AtomicBoolean reached = new AtomicBoolean();
    MockHttpServletResponse response = new MockHttpServletResponse();
    new WorkspaceContextFilter(membership)
        .doFilter(request(WORKSPACE.toString()), response, (rq, rs) -> reached.set(true));
    assertEquals(403, response.getStatus());
    assertEquals(
        "{\"type\":\"https://api.app.com/errors/forbidden\",\"title\":\"Erisim Reddedildi\",\"status\":403,\"detail\":\"Bu workspace'e uye degilsiniz.\"}",
        response.getContentAsString(StandardCharsets.UTF_8));
    assertNull(response.getErrorMessage(), "sendError must not start /error dispatch");
    assertFalse(reached.get());
    assertNull(TenantContext.getWorkspaceId());
    assertMemberHeader(WORKSPACE.toString());
  }

  @Test
  void K6_authenticatedRequestWithoutHeaderReachesChain() throws Exception {
    authenticate();
    WorkspaceMembershipService membership = mock(WorkspaceMembershipService.class);
    AtomicBoolean reached = new AtomicBoolean();
    new WorkspaceContextFilter(membership)
        .doFilter(
            request(),
            new MockHttpServletResponse(),
            (rq, rs) -> {
              reached.set(true);
              assertNull(TenantContext.getWorkspaceId());
            });
    assertTrue(reached.get());
    verifyNoInteractions(membership);
    assertNull(TenantContext.getWorkspaceId());
  }

  @Test
  void K7_R9_unauthenticatedMalformedHeaderIsIgnoredButAuthenticatedTwinRejects() throws Exception {
    WorkspaceMembershipService membership = mock(WorkspaceMembershipService.class);
    AtomicBoolean reached = new AtomicBoolean();
    MockHttpServletResponse response = new MockHttpServletResponse();
    new WorkspaceContextFilter(membership)
        .doFilter(request("not-a-uuid"), response, (rq, rs) -> reached.set(true));
    assertEquals(200, response.getStatus());
    assertTrue(reached.get());
    verifyNoInteractions(membership);
    rejectHeaderAndAcceptTwin("not-a-uuid");
  }

  @Test
  void K4_contextIsClearedEvenWhenDownstreamThrows() {
    authenticate();
    WorkspaceMembershipService membership = memberService();
    RuntimeException sentinel = new RuntimeException("downstream-sentinel");
    RuntimeException failure =
        assertThrowsExactly(
            RuntimeException.class,
            () ->
                new WorkspaceContextFilter(membership)
                    .doFilter(
                        request(WORKSPACE.toString()),
                        new MockHttpServletResponse(),
                        (rq, rs) -> {
                          assertEquals(WORKSPACE, TenantContext.getWorkspaceId());
                          throw sentinel;
                        }));
    assertSame(sentinel, failure);
    assertNull(TenantContext.getWorkspaceId());
  }

  private void rejectHeaderAndAcceptTwin(String... headers) throws Exception {
    authenticate();
    WorkspaceMembershipService membership = memberService();
    AtomicBoolean reached = new AtomicBoolean();
    MockHttpServletResponse response = new MockHttpServletResponse();
    TenantContext.setWorkspaceId(UUID.randomUUID());
    new WorkspaceContextFilter(membership)
        .doFilter(request(headers), response, (rq, rs) -> reached.set(true));
    assertAll(
        () -> assertEquals(400, response.getStatus()),
        () -> assertEquals(PROBLEM, response.getContentAsString(StandardCharsets.UTF_8)),
        () -> assertNotNull(response.getContentType()),
        () -> assertTrue(response.getContentType().startsWith("application/problem+json")),
        () -> assertEquals("UTF-8", response.getCharacterEncoding()),
        () -> assertNull(response.getErrorMessage()),
        () -> assertFalse(reached.get()),
        () ->
            assertNull(
                TenantContext.getWorkspaceId(), "400 must clear pre-existing thread context"),
        () -> verifyNoInteractions(membership));
    assertMemberHeader(WORKSPACE.toString());
  }

  private void assertMemberHeader(String header) throws Exception {
    authenticate();
    AtomicBoolean reached = new AtomicBoolean();
    AtomicReference<UUID> seen = new AtomicReference<>();
    MockHttpServletResponse response = new MockHttpServletResponse();
    new WorkspaceContextFilter(memberService())
        .doFilter(
            request(header),
            response,
            (rq, rs) -> {
              reached.set(true);
              seen.set(TenantContext.getWorkspaceId());
            });
    assertEquals(200, response.getStatus());
    assertTrue(reached.get());
    assertEquals(WORKSPACE, seen.get());
    assertNull(TenantContext.getWorkspaceId());
  }

  private static WorkspaceMembershipService memberService() {
    WorkspaceMembershipService membership = mock(WorkspaceMembershipService.class);
    when(membership.findRole(any(UUID.class), any(UUID.class))).thenReturn(Optional.of("ADMIN"));
    return membership;
  }

  private static void authenticate() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(USER, null, List.of()));
  }

  private static MockHttpServletRequest request(String... headers) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
    for (String value : headers) {
      request.addHeader("X-Workspace-Id", value);
    }
    return request;
  }

  private static void assertNoInternalDetails(String body) {
    assertNotNull(body);
    String lower = body.toLowerCase(Locale.ROOT);
    for (String forbidden :
        List.of(
            "exception",
            "stacktrace",
            "stack trace",
            "com.app.tracker",
            "java.lang",
            "java.time",
            "jwtservice",
            "workspacecontextfilter",
            "sensitive-claim-canary")) {
      assertFalse(
          lower.contains(forbidden), "internal detail leaked: " + forbidden + " in " + body);
    }
  }

  @Nested
  @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class ServletContract extends AbstractIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;
    @Autowired private JwtKeyProvider serverKeys;
    private Tenant tenant;
    private UUID otherWorkspace;
    private SecurityHttp http;

    @BeforeAll
    void setUp() {
      SecurityFixture fixture = SecurityFixture.create(context, port);
      tenant = fixture.a();
      otherWorkspace = fixture.b().workspaceId();
      http = new SecurityHttp(port);
    }

    @Test
    void R8_K3_allMalformedJwtFamiliesReturn401WithoutInternalBodyOrErrorLogs() throws Exception {
      JwtForge serverForge = new JwtForge(serverKeys);
      assertAll(
          malformedTokens(serverForge, serverKeys, tenant.admin().userId()).stream()
              .map(
                  bad ->
                      (org.junit.jupiter.api.function.Executable)
                          () -> {
                            HttpResponse<String> accepted =
                                projects(
                                    tenant.admin().accessToken(),
                                    List.of(tenant.workspaceId().toString()));
                            assertEquals(200, accepted.statusCode(), accepted.body());
                            Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
                            ListAppender<ILoggingEvent> appender = new ListAppender<>();
                            appender.list = new CopyOnWriteArrayList<>();
                            appender.setContext(root.getLoggerContext());
                            appender.start();
                            root.addAppender(appender);
                            try {
                              HttpResponse<String> response =
                                  projects(bad.token(), List.of(tenant.workspaceId().toString()));
                              assertAll(
                                  bad.label(),
                                  () -> assertEquals(401, response.statusCode(), response.body()),
                                  () -> assertNoInternalDetails(response.body()),
                                  () -> assertFalse(response.body().contains(bad.token())),
                                  () -> assertFalse(response.body().contains(JTI)),
                                  () ->
                                      assertTrue(
                                          appender.list.stream()
                                              .noneMatch(
                                                  event ->
                                                      event
                                                          .getLevel()
                                                          .isGreaterOrEqual(Level.ERROR)),
                                          () ->
                                              "ERROR logs: "
                                                  + appender.list.stream()
                                                      .filter(
                                                          event ->
                                                              event
                                                                  .getLevel()
                                                                  .isGreaterOrEqual(Level.ERROR))
                                                      .map(
                                                          event ->
                                                              event.getLoggerName()
                                                                  + ": "
                                                                  + event.getFormattedMessage())
                                                      .toList()));
                            } finally {
                              root.detachAppender(appender);
                              appender.stop();
                            }
                          }));
    }

    @Test
    void K3_K4_validTokenAndBothWorkspaceCasesReturn200() {
      for (String header :
          List.of(
              tenant.workspaceId().toString(),
              tenant.workspaceId().toString().toUpperCase(Locale.ROOT))) {
        HttpResponse<String> response = projects(tenant.admin().accessToken(), List.of(header));
        assertEquals(200, response.statusCode(), response.body());
      }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "", "LONG", "SUFFIX", "1-1-1-1-1"})
    void R9_R10_R11_R12_K4_malformedHeaderHasExact400OnRealServletAndValidTwinPasses(String value) {
      String header =
          switch (value) {
            case "LONG" -> "a".repeat(2000);
            case "SUFFIX" -> tenant.workspaceId() + "0";
            default -> value;
          };
      assertHttpRejectedAndAccepted(List.of(header));
    }

    @Test
    void R13_K4_duplicateHeaderOrderAndEqualityNeverAvoid400() {
      String member = tenant.workspaceId().toString();
      String other = otherWorkspace.toString();
      for (List<String> pair :
          List.of(
              List.of(member, member),
              List.of(member, other),
              List.of(other, member),
              List.of(member, "not-a-uuid"),
              List.of("not-a-uuid", member))) {
        assertHttpRejectedAndAccepted(pair);
      }
    }

    @Test
    void K5_K4_nonMemberStays403AcrossRealErrorDispatchAndMemberTwinPasses() {
      HttpResponse<String> rejected =
          projects(tenant.admin().accessToken(), List.of(otherWorkspace.toString()));
      assertEquals(403, rejected.statusCode(), rejected.body());
      assertEquals(403, MAPPER.readTree(rejected.body()).path("status").asInt());
      HttpResponse<String> accepted =
          projects(tenant.admin().accessToken(), List.of(tenant.workspaceId().toString()));
      assertEquals(200, accepted.statusCode(), accepted.body());
    }

    @Test
    void K6_authenticatedWorkspaceListingNeedsNoWorkspaceHeader() {
      HttpResponse<String> response = http.get("/api/v1/workspaces", tenant.admin(), null, null);
      assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void K7_R9_anonymousLoginIgnoresMalformedHeaderAndAuthenticatedTwinRejects() {
      String body =
          MAPPER.writeValueAsString(
              Map.of("email", tenant.admin().email(), "password", SecurityFixture.PASSWORD));
      HttpResponse<String> response =
          http.sendWithHeaders(
              "POST",
              "/api/v1/auth/login",
              null,
              List.of(Map.entry("X-Workspace-Id", "not-a-uuid")),
              body);
      assertEquals(200, response.statusCode(), response.body());
      assertFalse(MAPPER.readTree(response.body()).path("accessToken").asString().isBlank());
      assertHttpRejectedAndAccepted(List.of("not-a-uuid"));
    }

    private void assertHttpRejectedAndAccepted(List<String> headers) {
      HttpResponse<String> accepted =
          projects(tenant.admin().accessToken(), List.of(tenant.workspaceId().toString()));
      assertEquals(200, accepted.statusCode(), accepted.body());
      HttpResponse<String> response = projects(tenant.admin().accessToken(), headers);
      assertAll(
          () -> assertEquals(400, response.statusCode(), response.body()),
          () -> assertEquals(PROBLEM, response.body()),
          () ->
              assertTrue(
                  response
                      .headers()
                      .firstValue("Content-Type")
                      .orElse("")
                      .toLowerCase(Locale.ROOT)
                      .matches("application/problem\\+json;\\s*charset=utf-8")));
    }

    private HttpResponse<String> projects(String token, List<String> headers) {
      return http.sendWithHeaders(
          "GET",
          "/api/v1/projects",
          token,
          headers.stream().map(value -> Map.entry("X-Workspace-Id", value)).toList(),
          null);
    }
  }
}
