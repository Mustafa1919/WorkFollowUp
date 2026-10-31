package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.web.QueryParameterGuardFilter;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Tenant;
import com.app.tracker.security.support.SecurityHttp;
import com.app.tracker.telemetry.TelemetryConsumer;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Dilim 01. Beklentiler yalniz sozlesmeden gelir. Dis sinif filtre API'sini Docker olmadan sinar;
 * ServletContract gercek Tomcat HTTP ve dogrudan tuketici + JDBC kullanir. MockMvc yoktur.
 */
class QueryParameterAndTelemetryContractTest {

  private static final String PROBLEM =
      "{\"type\":\"https://api.app.com/errors/bad-request\",\"title\":\"Gecersiz Istek\","
          + "\"status\":400,\"detail\":\"Sorgu parametresi gecersiz karakter iceriyor.\"}";

  @Test
  void R2_R3_filterHasRequiredRegistrationBeforeSecurity() {
    assertTrue(OncePerRequestFilter.class.isAssignableFrom(QueryParameterGuardFilter.class));
    assertTrue(QueryParameterGuardFilter.class.isAnnotationPresent(Component.class));
    Order order = QueryParameterGuardFilter.class.getAnnotation(Order.class);
    assertNotNull(order);
    assertEquals(Ordered.HIGHEST_PRECEDENCE + 5, order.value());
  }

  @ParameterizedTest
  @ValueSource(strings = {"q=a%00b", "q=%00", "n%00ame=x", "%00=x", "=%00", "q=ok&q=%00"})
  void R2_R3_K10_nulNeverReachesBusinessChainAndCleanTwinDoes(String query) throws Exception {
    AtomicBoolean reached = new AtomicBoolean();
    MockHttpServletResponse rejected = new MockHttpServletResponse();
    new QueryParameterGuardFilter()
        .doFilter(request(query), rejected, (rq, rs) -> reached.set(true));
    assertAll(
        () -> assertEquals(400, rejected.getStatus()),
        () -> assertEquals(PROBLEM, rejected.getContentAsString(StandardCharsets.UTF_8)),
        () -> assertTrue(rejected.getContentType().startsWith("application/problem+json")),
        () -> assertEquals("UTF-8", rejected.getCharacterEncoding()),
        () -> assertFalse(reached.get(), "is katmanina ulasti"));
    MockHttpServletResponse accepted = new MockHttpServletResponse();
    new QueryParameterGuardFilter()
        .doFilter(request(query.replace("%00", "")), accepted, (rq, rs) -> reached.set(true));
    assertEquals(200, accepted.getStatus());
    assertTrue(reached.get(), "NUL'suz kabul esi zincire ulasmali");
  }

  @Test
  void K9_wrapperFiltersAllFourViewsAndPreservesRawQueryAndUtf8Duplicates() throws Exception {
    String query = "q=%C3%A7a%C4%9Fr%C4%B1+%2B&=&x=1&x=2&=discard&flag&equals=a%3Db";
    AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
    new QueryParameterGuardFilter()
        .doFilter(
            request(query),
            new MockHttpServletResponse(),
            (rq, rs) -> seen.set((HttpServletRequest) rq));
    HttpServletRequest wrapped = seen.get();
    assertNotNull(wrapped);
    assertEquals(query, wrapped.getQueryString());
    Map<String, List<String>> expected =
        Map.of(
            "q",
            List.of("çağrı +"),
            "x",
            List.of("1", "2"),
            "flag",
            List.of(""),
            "equals",
            List.of("a=b"));
    assertEquals(expected.keySet(), wrapped.getParameterMap().keySet());
    assertEquals(
        expected.keySet(), new java.util.HashSet<>(Collections.list(wrapped.getParameterNames())));
    for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
      String[] values = entry.getValue().toArray(String[]::new);
      assertEquals(values[0], wrapped.getParameter(entry.getKey()));
      assertArrayEquals(values, wrapped.getParameterValues(entry.getKey()));
      assertArrayEquals(values, wrapped.getParameterMap().get(entry.getKey()));
    }
    for (String absent : List.of("", "missing")) {
      assertNull(wrapped.getParameter(absent));
      assertNull(wrapped.getParameterValues(absent));
      assertFalse(wrapped.getParameterMap().containsKey(absent));
      assertFalse(Collections.list(wrapped.getParameterNames()).contains(absent));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "application/x-www-form-urlencoded,q=clean",
    "application/x-www-form-urlencoded,q=clean&=x",
    "multipart/form-data; boundary=contract,q=clean",
    "multipart/form-data; boundary=contract,q=clean&=x"
  })
  void R4_K11_filterDecisionNeverReadsFormOrMultipartBody(String contentType, String query)
      throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/contract/body") {
          @Override
          public Map<String, String[]> getParameterMap() {
            throw new AssertionError("Karar govde parametrelerini okudu");
          }

          @Override
          public String getParameter(String name) {
            throw new AssertionError("Karar getParameter cagirdi");
          }

          @Override
          public String[] getParameterValues(String name) {
            throw new AssertionError("Karar getParameterValues cagirdi");
          }

          @Override
          public Enumeration<String> getParameterNames() {
            throw new AssertionError("Karar getParameterNames cagirdi");
          }

          @Override
          public ServletInputStream getInputStream() {
            throw new AssertionError("Karar govdeyi okudu");
          }

          @Override
          public BufferedReader getReader() {
            throw new AssertionError("Karar govdeyi okudu");
          }
        };
    request.setQueryString(query);
    request.setContentType(contentType);
    byte[] body = "body-field=untouched".getBytes(StandardCharsets.UTF_8);
    request.setContent(body);
    AtomicBoolean reached = new AtomicBoolean();
    MockHttpServletResponse response = new MockHttpServletResponse();
    new QueryParameterGuardFilter().doFilter(request, response, (rq, rs) -> reached.set(true));
    if (query.contains("&=x")) {
      assertEquals(400, response.getStatus());
      assertEquals(PROBLEM, response.getContentAsString(StandardCharsets.UTF_8));
      assertFalse(reached.get());
    } else {
      assertEquals(200, response.getStatus());
      assertTrue(reached.get());
    }
    assertArrayEquals(body, request.getContentAsByteArray());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"application/x-www-form-urlencoded", "multipart/form-data; boundary=contract"})
  void R4_K11_emptyQueryNameRejectsBodyAndCleanTwinPreservesParameters(String contentType)
      throws Exception {
    // Servlet parametre API'si sorgu ve govde alanlarini birlikte sunar. Karar sirasinda
    // bu API'ye erisilmez; alanlari yalniz asagidaki is zinciri okur.
    Map<String, List<String>> baseline = downstreamParameters(contentType, "q=sec");
    assertEquals(
        Map.of("q", List.of("sec"), "bodyField", List.of("preserved", "second")), baseline);
    MockHttpServletRequest rejected = request("q=sec&=discard");
    rejected.setMethod("POST");
    rejected.setContentType(contentType);
    rejected.setContent("bodyField=preserved&bodyField=second".getBytes(StandardCharsets.UTF_8));
    AtomicBoolean reached = new AtomicBoolean();
    MockHttpServletResponse response = new MockHttpServletResponse();
    new QueryParameterGuardFilter().doFilter(rejected, response, (rq, rs) -> reached.set(true));
    assertEquals(400, response.getStatus());
    assertEquals(PROBLEM, response.getContentAsString(StandardCharsets.UTF_8));
    assertTrue(response.getContentType().startsWith("application/problem+json"));
    assertEquals("UTF-8", response.getCharacterEncoding());
    assertFalse(reached.get(), "R4 reddi is katmanina ulasmamali");
  }

  private static Map<String, List<String>> downstreamParameters(String contentType, String query)
      throws Exception {
    MockHttpServletRequest request = request(query);
    request.setMethod("POST");
    request.setContentType(contentType);
    request.setContent("bodyField=preserved&bodyField=second".getBytes(StandardCharsets.UTF_8));
    request.addParameter("q", "sec");
    request.addParameter("bodyField", "preserved", "second");
    Map<String, List<String>> seen = new LinkedHashMap<>();
    new QueryParameterGuardFilter()
        .doFilter(
            request,
            new MockHttpServletResponse(),
            (rq, rs) -> {
              HttpServletRequest downstream = (HttpServletRequest) rq;
              for (String name : Collections.list(downstream.getParameterNames())) {
                if (!name.isEmpty()) {
                  String[] values = downstream.getParameterValues(name);
                  assertEquals(values[0], downstream.getParameter(name));
                  assertArrayEquals(values, downstream.getParameterMap().get(name));
                  seen.put(name, List.of(values));
                }
              }
            });
    return seen;
  }

  private static MockHttpServletRequest request(String query) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/search");
    request.setQueryString(query);
    return request;
  }

  @Nested
  @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @Import(FormProbeConfiguration.class)
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class ServletContract extends AbstractIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TelemetryConsumer consumer;
    private Tenant tenant;
    private SecurityHttp http;
    private String searchWord;
    private String searchTaskId;

    @BeforeAll
    void setUp() {
      tenant = SecurityFixture.create(context, port).a();
      http = new SecurityHttp(port);
      searchWord = "Contractquery" + UUID.randomUUID().toString().replace("-", "");
      HttpResponse<String> created =
          http.post(
              "/api/v1/projects/" + tenant.projectId() + "/tasks",
              tenant.admin(),
              tenant.workspaceId(),
              mapper.writeValueAsString(Map.of("title", searchWord + " sec arama kaydi")));
      assertEquals(201, created.statusCode(), created.body());
      searchTaskId = mapper.readTree(created.body()).path("id").asString();
      assertFalse(searchTaskId.isBlank());
    }

    @Test
    void K1_longErrorTypePersistsFirst200Characters() {
      String value = "ErrorPrefix" + "a".repeat(239);
      assertEquals(250, value.length());
      UUID id = consume("ERROR_OCCURRED", error("backend", value, "message", "/api/v1/x"));
      assertEquals(value.substring(0, 200), errorRow(id).get("error_type"));
    }

    @Test
    void K2_longPathPersistsFirst255Characters() {
      String path = "/api/v1/" + "p".repeat(292);
      assertEquals(300, path.length());
      UUID id = consume("ERROR_OCCURRED", error("backend", "Error", "message", path));
      assertEquals(path.substring(0, 255), errorRow(id).get("path"));
    }

    @Test
    void K3_longSourceIsBoundedWithoutPermanentFailure() {
      String source = "source" + "s".repeat(24);
      UUID id = consume("ERROR_OCCURRED", error(source, "Error", "message", "/x"));
      assertBoundedPrefix(source, (String) errorRow(id).get("source"), 20);
    }

    @Test
    void K3_longFeatureAndActionAreBoundedWithoutPermanentFailure() {
      String feature = "feature" + "f".repeat(73);
      String action = "action" + "a".repeat(74);
      UUID id = consume("FEATURE_USED", Map.of("feature", feature, "action", action));
      Map<String, Object> stored = usageRow(id);
      assertBoundedPrefix(feature, (String) stored.get("feature"), 60);
      assertBoundedPrefix(action, (String) stored.get("action"), 60);
    }

    @Test
    void K4_shortErrorAndFeatureFieldsAreStoredExactly() {
      UUID errorId =
          consume("ERROR_OCCURRED", error("backend", "IllegalStateException", "kisa", "/api/v1/x"));
      Map<String, Object> row = errorRow(errorId);
      assertAll(
          () -> assertEquals("backend", row.get("source")),
          () -> assertEquals("IllegalStateException", row.get("error_type")),
          () -> assertEquals("kisa", row.get("message")),
          () -> assertEquals("/api/v1/x", row.get("path")));
      Map<String, Object> usage =
          usageRow(consume("FEATURE_USED", Map.of("feature", "search", "action", "open")));
      assertEquals("search", usage.get("feature"));
      assertEquals("open", usage.get("action"));
    }

    @Test
    void K4_exactAsciiLimitsArePreservedAndOptionalPathMayBeAbsent() {
      Map<String, Object> row =
          errorRow(
              consume(
                  "ERROR_OCCURRED", error("s".repeat(20), "e".repeat(200), "", "p".repeat(255))));
      assertEquals("s".repeat(20), row.get("source"));
      assertEquals("e".repeat(200), row.get("error_type"));
      assertEquals("p".repeat(255), row.get("path"));
      Map<String, Object> usage =
          usageRow(
              consume("FEATURE_USED", Map.of("feature", "f".repeat(60), "action", "a".repeat(60))));
      assertEquals("f".repeat(60), usage.get("feature"));
      assertEquals("a".repeat(60), usage.get("action"));
      assertNull(
          errorRow(consume("ERROR_OCCURRED", Map.of("source", "backend", "errorType", "Error")))
              .get("path"));
    }

    @ParameterizedTest
    @CsvSource({"source,20", "errorType,200", "path,255", "feature,60", "action,60"})
    void K5_boundaryEmojiIsNeverSplit(String field, int limit) {
      boolean usage = field.equals("feature") || field.equals("action");
      for (int prefixLength : new int[] {limit - 2, limit - 1}) {
        String value = "a".repeat(prefixLength) + "😀" + "tail";
        Map<String, Object> payload =
            usage
                ? new LinkedHashMap<>(Map.of("feature", "search", "action", "open"))
                : error("backend", "Error", "message", "/x");
        payload.put(field, value);
        UUID id = consume(usage ? "FEATURE_USED" : "ERROR_OCCURRED", payload);
        Map<String, Object> row = usage ? usageRow(id) : errorRow(id);
        String column = field.equals("errorType") ? "error_type" : field;
        String stored = (String) row.get(column);
        assertBoundedPrefix(value, stored, limit);
        assertTrue(
            stored.startsWith("a".repeat(prefixLength)), "sinir oncesindeki karakterler korunmali");
        assertWellFormedUtf16(stored);
      }
    }

    @Test
    void K6_consumerLeavesLongAndPreviouslyMaskedMessagesExactlyUntouched() {
      for (String message :
          List.of("m".repeat(5000) + "😀", "[REDACTED] " + "x".repeat(3989), "kisa ileti")) {
        UUID id = consume("ERROR_OCCURRED", error("backend", "Error", message, "/x"));
        assertEquals(message, errorRow(id).get("message"));
      }
      Map<String, Object> payload = error("backend", "Error", "unused", "/x");
      payload.remove("message");
      assertNull(errorRow(consume("ERROR_OCCURRED", payload)).get("message"));
    }

    @ParameterizedTest
    @CsvSource({
      "ERROR_OCCURRED,source",
      "ERROR_OCCURRED,errorType",
      "FEATURE_USED,feature",
      "FEATURE_USED,action"
    })
    void R1_requiredFieldsStayRequiredAndCompleteTwinPersists(String type, String field) {
      Map<String, Object> valid =
          type.equals("ERROR_OCCURRED")
              ? error("backend", "Error", "message", "/x")
              : new LinkedHashMap<>(Map.of("feature", "search", "action", "open"));
      for (String missingKind : List.of("absent", "null", "blank")) {
        Map<String, Object> invalid = new LinkedHashMap<>(valid);
        if (missingKind.equals("absent")) {
          invalid.remove(field);
        } else {
          invalid.put(field, missingKind.equals("null") ? null : " \t ");
        }
        UUID id = UUID.randomUUID();
        assertThrows(
            IllegalArgumentException.class,
            () -> consumer.onMessage(envelope(id, type, invalid)),
            field + ":" + missingKind);
        String table = type.equals("ERROR_OCCURRED") ? "error_events" : "usage_events";
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id));
      }
      UUID accepted = consume(type, valid);
      assertFalse(
          (type.equals("ERROR_OCCURRED") ? errorRow(accepted) : usageRow(accepted)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"q=a%00b", "q=%00", "unused=%00&q=sec", "q=sec&q=%00"})
    void R2_nulValueHasExactProblemForAuthenticatedAndAnonymousRequests(String query) {
      assertRejectedWithCleanTwin(query);
    }

    @ParameterizedTest
    @ValueSource(strings = {"n%00ame=x", "%00=x", "q=sec&extra%00=x"})
    void R3_nulNameHasExactProblemForAuthenticatedAndAnonymousRequests(String query) {
      assertRejectedWithCleanTwin(query);
    }

    @Test
    void K7_normalSearchReturnsKnownTask() {
      assertSearchFindsKnownTask("/api/v1/search?q=" + enc(searchWord));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "'", "\\", "&", ":*", "<->"})
    void K8_specialCharactersNeverProduceServerFailureAndNormalTwinStillFindsTask(String query) {
      HttpResponse<String> response = get("/api/v1/search?q=" + enc(query));
      assertTrue(response.statusCode() < 500, response.body());
      assertSearchFindsKnownTask("/api/v1/search?q=" + enc(searchWord));
    }

    @Test
    void K9_dast01_velocityEmptyNameEqualsNoParameter() {
      assertEquivalent(
          "/api/v1/projects/" + tenant.projectId() + "/analytics/velocity", "", "=", 200);
    }

    @Test
    void K9_dast02_throughputEmptyNameEqualsNoParameter() {
      assertEquivalent(
          "/api/v1/projects/" + tenant.projectId() + "/analytics/throughput", "", "=", 200);
    }

    @Test
    void K9_dast03_cycleTimeEmptyNameEqualsNoParameter() {
      assertEquivalent(
          "/api/v1/projects/" + tenant.projectId() + "/analytics/cycle-time", "", "=", 200);
    }

    @Test
    void K9_dast04_periodEmptyNameRetainsMissingYear400() {
      assertEquivalent("/api/v1/reports/period", "", "=", 400);
      assertEquals(200, get("/api/v1/reports/period?year=2026").statusCode());
    }

    @Test
    void K9_dast05_periodQuarterEmptyNameRetainsMissingYear400() {
      assertEquivalent("/api/v1/reports/period", "quarter=4", "=&quarter=4", 400);
      assertEquals(200, get("/api/v1/reports/period?year=2026&quarter=4").statusCode());
    }

    @Test
    void K9_dast06_periodProjectFilterSurvivesEmptyName() {
      String clean = "year=2026&projectIds=" + tenant.projectId();
      assertEquivalent(
          "/api/v1/reports/period", clean, "year=2026&=&projectIds=" + tenant.projectId(), 200);
    }

    @Test
    void K9_dast07_searchEmptyNameRetainsLimit() {
      assertEquivalent("/api/v1/search", "limit=5", "=&limit=5", 400);
      assertSearchFindsKnownTask("/api/v1/search?q=" + enc(searchWord) + "&limit=5");
    }

    @Test
    void K9_dast08_searchRetainsSecQuery() {
      assertEquivalent("/api/v1/search", "q=sec", "q=sec&=", 200);
      assertSearchFindsKnownTask("/api/v1/search?q=" + enc(searchWord) + "&=");
    }

    @Test
    void K9_dast09_taskActivityEmptyNameEqualsNoParameter() {
      assertEquivalent("/api/v1/tasks/" + tenant.taskId() + "/activity", "", "=", 200);
    }

    @Test
    void K9_nonemptyValueOfEmptyNameIsAlsoIgnored() {
      assertEquivalent(
          "/api/v1/search", "q=" + enc(searchWord), "q=" + enc(searchWord) + "&=x", 200);
    }

    @Test
    void K10_emptyNameNulIsRejectedBeforeIgnoringAndCleanTwinIsAccepted() {
      for (boolean authenticated : new boolean[] {true, false}) {
        assertProblem(get("/api/v1/search?q=sec&=%00", authenticated));
        assertEquals(
            authenticated ? 200 : 401, get("/api/v1/search?q=sec&=x", authenticated).statusCode());
      }
    }

    @Test
    void R2_R3_guardAppliesToEveryApiPathBeforeAuthentication() {
      for (String path :
          List.of(
              "/api/v1/projects/" + tenant.projectId() + "/analytics/velocity",
              "/api/v1/tasks/" + tenant.taskId() + "/activity",
              "/api/v1/reports/period?year=2026")) {
        String separator = path.contains("?") ? "&" : "?";
        for (String query : List.of("unused=%00", "na%00me=x")) {
          for (boolean authenticated : new boolean[] {true, false}) {
            assertProblem(get(path + separator + query, authenticated));
            assertEquals(
                authenticated ? 200 : 401,
                get(path + separator + query.replace("%00", ""), authenticated).statusCode());
          }
        }
      }
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"application/x-www-form-urlencoded", "multipart/form-data; boundary=contract"})
    void R4_K11_realHttpFormAndMultipartRejectEmptyQueryAndAcceptCleanTwin(String contentType)
        throws Exception {
      String body =
          contentType.startsWith("multipart/")
              ? "--contract\r\nContent-Disposition: form-data; name=\"bodyField\"\r\n\r\npreserved\r\n"
                  + "--contract\r\nContent-Disposition: form-data; name=\"bodyField\"\r\n\r\nsecond\r\n"
                  + "--contract--\r\n"
              : "bodyField=preserved&bodyField=second";
      var baseline = postBodyToProbe("q=sec", contentType, body, true);
      assertEquals(200, baseline.statusCode(), baseline.body());
      var expected = mapper.readTree(baseline.body());
      assertEquals("sec", expected.path("q").get(0).asString());
      assertEquals("preserved", expected.path("bodyField").get(0).asString());
      assertEquals("second", expected.path("bodyField").get(1).asString());
      for (boolean authenticated : new boolean[] {true, false}) {
        var rejected = postBodyToProbe("q=sec&=discard", contentType, body, authenticated);
        assertProblem(rejected);
        assertEquals(PROBLEM, rejected.body(), "R4 tam problem govdesi");
      }
      assertEquals(401, postBodyToProbe("q=sec", contentType, body, false).statusCode());
    }

    private HttpResponse<String> postBodyToProbe(
        String query, String contentType, String body, boolean authenticated) throws Exception {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://localhost:" + port + "/api/v1/contract/parameter-probe?" + query))
              .timeout(Duration.ofSeconds(30))
              .header("Content-Type", contentType)
              .POST(HttpRequest.BodyPublishers.ofString(body));
      if (authenticated) {
        request
            .header("Authorization", "Bearer " + tenant.admin().accessToken())
            .header("X-Workspace-Id", tenant.workspaceId().toString());
      }
      return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private UUID consume(String type, Map<String, Object> payload) {
      UUID id = UUID.randomUUID();
      assertDoesNotThrow(
          () -> consumer.onMessage(envelope(id, type, payload)), "Tuketici kalici hata verdi");
      return id;
    }

    private String envelope(UUID id, String type, Map<String, Object> payload) {
      return mapper.writeValueAsString(
          Map.of(
              "eventId",
              id.toString(),
              "eventType",
              type,
              "schemaVersion",
              1,
              "timestamp",
              Instant.now().toString(),
              "workspaceId",
              tenant.workspaceId().toString(),
              "payload",
              payload));
    }

    private Map<String, Object> errorRow(UUID id) {
      return jdbc.queryForMap(
          "SELECT source, error_type, message, path FROM error_events WHERE id = ?", id);
    }

    private Map<String, Object> usageRow(UUID id) {
      return jdbc.queryForMap("SELECT feature, action FROM usage_events WHERE id = ?", id);
    }

    private HttpResponse<String> get(String path) {
      return get(path, true);
    }

    private HttpResponse<String> get(String path, boolean authenticated) {
      return http.get(
          path,
          authenticated ? tenant.admin().accessToken() : null,
          authenticated ? tenant.workspaceId() : null,
          null);
    }

    private void assertRejectedWithCleanTwin(String query) {
      for (boolean authenticated : new boolean[] {true, false}) {
        assertProblem(get("/api/v1/search?" + query, authenticated));
        HttpResponse<String> accepted =
            get(
                "/api/v1/search?q=" + enc(searchWord) + "&" + query.replace("%00", ""),
                authenticated);
        assertEquals(authenticated ? 200 : 401, accepted.statusCode(), accepted.body());
      }
    }

    private void assertProblem(HttpResponse<String> response) {
      assertAll(
          () -> assertEquals(400, response.statusCode(), response.body()),
          () -> assertEquals(mapper.readTree(PROBLEM), mapper.readTree(response.body())),
          () ->
              assertTrue(
                  response
                      .headers()
                      .firstValue("Content-Type")
                      .orElse("")
                      .startsWith("application/problem+json")));
    }

    private void assertSearchFindsKnownTask(String path) {
      HttpResponse<String> response = get(path);
      assertEquals(200, response.statusCode(), response.body());
      boolean found = false;
      for (var task : mapper.readTree(response.body()).path("tasks")) {
        found |= searchTaskId.equals(task.path("id").asString());
      }
      assertTrue(found, response.body());
    }

    private void assertEquivalent(String path, String clean, String withEmpty, int status) {
      HttpResponse<String> baseline = get(path + (clean.isEmpty() ? "" : "?" + clean));
      HttpResponse<String> actual = get(path + "?" + withEmpty);
      assertEquals(status, baseline.statusCode(), baseline.body());
      assertEquals(status, actual.statusCode(), actual.body());
      if (status == 200) {
        assertEquals(
            mapper.readTree(baseline.body()),
            mapper.readTree(actual.body()),
            "Bos adli parametre sonucu degistirdi");
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FormProbeConfiguration {
    @Bean
    FormProbeController formProbeController() {
      return new FormProbeController();
    }
  }

  @RestController
  @TestComponent
  static class FormProbeController {
    @PostMapping("/api/v1/contract/parameter-probe")
    Map<String, List<String>> parameters(HttpServletRequest request) {
      Map<String, List<String>> values = new LinkedHashMap<>();
      for (String name : Collections.list(request.getParameterNames())) {
        String[] fieldValues = request.getParameterValues(name);
        assertEquals(fieldValues[0], request.getParameter(name));
        assertArrayEquals(fieldValues, request.getParameterMap().get(name));
        values.put(name, List.of(fieldValues));
      }
      return values;
    }
  }

  private static Map<String, Object> error(
      String source, String type, String message, String path) {
    return new LinkedHashMap<>(
        Map.of("source", source, "errorType", type, "message", message, "path", path));
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static void assertBoundedPrefix(String original, String stored, int limit) {
    assertNotNull(stored);
    assertFalse(stored.isEmpty());
    assertTrue(stored.codePointCount(0, stored.length()) <= limit, stored);
    assertTrue(original.startsWith(stored), "Yazilan deger giris oneki olmali");
  }

  private static void assertWellFormedUtf16(String value) {
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (Character.isHighSurrogate(current)) {
        assertTrue(
            i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)),
            "Yarim surrogate cifti");
        i++;
      } else {
        assertFalse(Character.isLowSurrogate(current), "Eslesmeyen alt surrogate");
      }
    }
  }
}
