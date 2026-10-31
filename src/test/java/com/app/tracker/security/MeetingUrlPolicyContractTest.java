package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.*;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.meeting.dto.HttpsUrlValidator;
import com.app.tracker.meeting.dto.MeetingRequest;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Tenant;
import com.app.tracker.security.support.SecurityHttp;
import jakarta.validation.Validation;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.NestedTestConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Contract 03: Docker-free validation checks plus real RANDOM_PORT HTTP/persistence checks. */
class MeetingUrlPolicyContractTest {

  private static final String GOOD = "https://meet.example.com/x";
  private static final String UPDATED = "https://meet.example.com/y?room=1";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  static Stream<String> acceptedUrls() {
    return Stream.of(
        null, "", "   ", GOOD, UPDATED, "HTTPS://Meet.Example.com/x", "  " + GOOD + "  ");
  }

  static Stream<Arguments> rejectedUrls() {
    return Stream.of(
        Arguments.of("R1", "javascript:alert(1)"),
        Arguments.of("R1", "JaVaScRiPt:alert(1)"),
        Arguments.of("R1", "data:text/html,<script>alert(1)</script>"),
        Arguments.of("R1", "vbscript:msgbox(1)"),
        Arguments.of("R2", "http://meet.example.com/x"),
        Arguments.of("R3", "/toplanti"),
        Arguments.of("R3", "meet.example.com/x"),
        Arguments.of("R3", "//meet.example.com/x"),
        Arguments.of("R4", "https://"),
        Arguments.of("R4", "https:///x"),
        Arguments.of("R5", "https://meet.example.com/a b"),
        Arguments.of("R5", GOOD + '\0'),
        Arguments.of("R5", "\0" + GOOD),
        Arguments.of("R5", "https://meet.example.com/a\tb"),
        Arguments.of("R5", "https://meet.example.com/a\nb"),
        Arguments.of("R5", "https://meet.example.com/a\u00a0b"),
        Arguments.of("R5", "https://meet.example.com/a\u2003b"),
        Arguments.of("R5", "https://meet.example.com/a\u202fb"),
        Arguments.of("R5", "https://meet.example.com/a\u3000b"));
  }

  @ParameterizedTest
  @MethodSource("acceptedUrls")
  void K1_K2_K3_K4_K5_validatorAcceptsContractInputs(String url) {
    assertTrue(new HttpsUrlValidator().isValid(url, null));
  }

  @ParameterizedTest(name = "{0} validator rejection [{index}]")
  @MethodSource("rejectedUrls")
  void R1_R2_R3_R4_R5_K1_validatorRejectsWithAcceptedTwin(String rule, String url) {
    HttpsUrlValidator validator = new HttpsUrlValidator();
    assertTrue(validator.isValid(GOOD, null), rule + " accepted twin");
    assertFalse(validator.isValid(url, null), rule);
  }

  @Test
  void R2_K1_requestAnnotationRejectsHttpAndAcceptsHttps() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertTrue(validator.validate(request(GOOD)).isEmpty());
      assertTrue(
          validator.validate(request("http://meet.example.com/x")).stream()
              .anyMatch(v -> v.getPropertyPath().toString().equals("meetingUrl")));
    }
  }

  @Test
  void R5_K1_existingSizeBoundaryAccepts500AndRejects501() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      String atLimit = GOOD + "x".repeat(500 - GOOD.length());
      assertEquals(500, atLimit.length());
      assertTrue(validator.validate(request(atLimit)).isEmpty());
      assertTrue(
          validator.validate(request(atLimit + "x")).stream()
              .anyMatch(v -> v.getPropertyPath().toString().equals("meetingUrl")));
    }
  }

  private static MeetingRequest request(String url) {
    return new MeetingRequest(
        "Contract 03",
        null,
        url,
        LocalDate.of(2099, 6, 15),
        LocalTime.of(10, 0),
        30,
        "ONCE",
        1,
        null,
        null,
        null,
        null,
        false);
  }

  @Nested
  @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class ServletContract extends AbstractIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;
    private Tenant tenant;
    private SecurityHttp http;

    @BeforeAll
    void setUp() {
      tenant = SecurityFixture.create(context, port).a();
      http = new SecurityHttp(port);
    }

    @Test
    void K1_httpsCreatePreservesExactValue() throws Exception {
      UUID id = create(GOOD, GOOD);
      assertStored(id, GOOD);
    }

    @Test
    void K2_httpsUpdatePreservesExactValue() throws Exception {
      UUID id = create(GOOD, GOOD);
      updateAccepted(id, UPDATED, UPDATED);
      assertStored(id, UPDATED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OMITTED", "NULL", "EMPTY", "SPACES"})
    void K3_optionalUrlFormsStoreNullOnCreateAndUpdate(String form) throws Exception {
      Map<String, Object> body = body(GOOD);
      switch (form) {
        case "OMITTED" -> body.remove("meetingUrl");
        case "NULL" -> body.put("meetingUrl", null);
        case "EMPTY" -> body.put("meetingUrl", "");
        case "SPACES" -> body.put("meetingUrl", "   ");
        default -> fail(form);
      }
      HttpResponse<String> created = post(body);
      assertEquals(201, created.statusCode(), created.body());
      UUID createdId = UUID.fromString(json(created).path("id").asString());
      assertStored(createdId, null);
      UUID existingId = create(GOOD, GOOD);
      HttpResponse<String> updated = put(existingId, body);
      assertEquals(200, updated.statusCode(), updated.body());
      assertStored(existingId, null);
    }

    @Test
    void K4_uppercaseSchemeAcceptedOnCreateAndUpdate() throws Exception {
      String url = "HTTPS://Meet.Example.com/x";
      UUID id = create(url, url);
      updateAccepted(id, url, url);
      assertStored(id, url);
    }

    @Test
    void K5_paddedUrlTrimmedOnCreateAndUpdate() throws Exception {
      UUID id = create("  " + GOOD + "  ", GOOD);
      assertStored(id, GOOD);
      updateAccepted(id, "  " + UPDATED + "  ", UPDATED);
      assertStored(id, UPDATED);
    }

    @ParameterizedTest(name = "{0} HTTP rejection and persistence [{index}]")
    @MethodSource("com.app.tracker.security.MeetingUrlPolicyContractTest#rejectedUrls")
    void R1_R2_R3_R4_R5_R6_K1_K2_createAndUpdateRejectWithAcceptedTwinsAndKeepOldUrl(
        String rule, String rejectedUrl) throws Exception {
      UUID id = create(GOOD, GOOD);
      HttpResponse<String> rejectedCreate = post(body(rejectedUrl));
      assertEquals(422, rejectedCreate.statusCode(), rule + " create: " + rejectedCreate.body());
      updateAccepted(id, UPDATED, UPDATED);
      HttpResponse<String> rejectedUpdate = put(id, body(rejectedUrl));
      assertEquals(422, rejectedUpdate.statusCode(), rule + " update: " + rejectedUpdate.body());
      assertStored(id, UPDATED);
      HttpResponse<String> list =
          http.get("/api/v1/meetings", tenant.admin(), tenant.workspaceId(), null);
      assertEquals(200, list.statusCode(), list.body());
      assertEquals(UPDATED, findMeeting(json(list), id).path("meetingUrl").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://meet.example.com/x", "javascript:alert(1)"})
    void K6_legacySqlValuesListUnchangedWithoutReadValidation(String legacyUrl) throws Exception {
      UUID id = UUID.randomUUID();
      try (Connection c = openMigratorConnection();
          var statement =
              c.prepareStatement(
                  "INSERT INTO meetings (id, workspace_id, title, meeting_url, start_date, start_time,"
                      + " duration_minutes, frequency, interval_count, created_by)"
                      + " VALUES (?, ?, ?, ?, DATE '2099-06-15', TIME '10:00:00', 30, 'ONCE', 1, ?)")) {
        statement.setObject(1, id);
        statement.setObject(2, tenant.workspaceId());
        statement.setString(3, "Contract 03 legacy");
        statement.setString(4, legacyUrl);
        statement.setObject(5, tenant.admin().userId());
        assertEquals(1, statement.executeUpdate());
      }
      HttpResponse<String> list =
          http.get("/api/v1/meetings", tenant.admin(), tenant.workspaceId(), null);
      assertEquals(200, list.statusCode(), list.body());
      assertEquals(legacyUrl, findMeeting(json(list), id).path("meetingUrl").asString());
      assertStored(id, legacyUrl);
    }

    @Test
    void R5_K1_K2_sizeBoundaryAccepts500AndRejects501WithoutChangingOldValue() throws Exception {
      String atLimit = GOOD + "x".repeat(500 - GOOD.length());
      UUID id = create(atLimit, atLimit);
      HttpResponse<String> rejectedCreate = post(body(atLimit + "x"));
      assertEquals(422, rejectedCreate.statusCode(), rejectedCreate.body());
      updateAccepted(id, atLimit, atLimit);
      HttpResponse<String> rejectedUpdate = put(id, body(atLimit + "x"));
      assertEquals(422, rejectedUpdate.statusCode(), rejectedUpdate.body());
      assertStored(id, atLimit);
    }

    private UUID create(String url, String expected) {
      HttpResponse<String> response = post(body(url));
      assertEquals(201, response.statusCode(), response.body());
      assertEquals(expected, json(response).path("meetingUrl").asString());
      return UUID.fromString(json(response).path("id").asString());
    }

    private void updateAccepted(UUID id, String url, String expected) {
      HttpResponse<String> response = put(id, body(url));
      assertEquals(200, response.statusCode(), response.body());
      assertEquals(expected, json(response).path("meetingUrl").asString());
    }

    private HttpResponse<String> post(Map<String, Object> body) {
      return http.post(
          "/api/v1/meetings",
          tenant.admin(),
          tenant.workspaceId(),
          MAPPER.writeValueAsString(body));
    }

    private HttpResponse<String> put(UUID id, Map<String, Object> body) {
      return http.put(
          "/api/v1/meetings/" + id,
          tenant.admin(),
          tenant.workspaceId(),
          MAPPER.writeValueAsString(body));
    }

    private void assertStored(UUID id, String expected) throws Exception {
      try (Connection c = openMigratorConnection();
          var statement =
              c.prepareStatement(
                  "SELECT meeting_url FROM meetings WHERE id = ? AND workspace_id = ?")) {
        statement.setObject(1, id);
        statement.setObject(2, tenant.workspaceId());
        try (var rows = statement.executeQuery()) {
          assertTrue(rows.next(), "Meeting must exist: " + id);
          assertEquals(expected, rows.getString(1));
          assertFalse(rows.next());
        }
      }
    }
  }

  private static Map<String, Object> body(String url) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("title", "Contract 03");
    result.put("startDate", "2099-06-15");
    result.put("startTime", "10:00:00");
    result.put("durationMinutes", 30);
    result.put("frequency", "ONCE");
    result.put("intervalCount", 1);
    result.put("meetingUrl", url);
    return result;
  }

  private static JsonNode json(HttpResponse<String> response) {
    return MAPPER.readTree(response.body());
  }

  private static JsonNode findMeeting(JsonNode list, UUID id) {
    assertTrue(list.isArray(), "GET meetings must return an array");
    for (JsonNode meeting : list) {
      if (id.toString().equals(meeting.path("id").asString())) {
        return meeting;
      }
    }
    throw new AssertionError("Meeting missing from list: " + id);
  }
}
