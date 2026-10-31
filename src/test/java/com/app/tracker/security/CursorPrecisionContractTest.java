package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.*;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.web.CursorInstantCodec;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Tenant;
import com.app.tracker.security.support.SecurityHttp;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.NestedTestConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice 04: independent codec/cursor checks and real HTTP traversal of SQL microsecond fixtures.
 */
class CursorPrecisionContractTest {

  private static final UUID ID = UUID.fromString("12345678-90ab-cdef-8123-456789abcdef");
  private static final Instant PRECISE = Instant.parse("2026-10-31T10:00:00.123456789Z");
  private static final Instant LEGACY = Instant.parse("2026-10-31T10:00:00.123Z");

  private record Sample(String instant, String encoded) {}

  @Test
  void K1_codecUsesNineDigitsAndRoundTripsEveryNanosecond() {
    for (Sample sample :
        List.of(
            new Sample("2026-10-31T10:00:00.123456Z", "1793440800.123456000"),
            new Sample("2026-10-31T10:00:00.123456789Z", "1793440800.123456789"),
            new Sample("2026-10-31T10:00:00.000001Z", "1793440800.000001000"),
            new Sample("2026-10-31T10:00:00.000000001Z", "1793440800.000000001"),
            new Sample("2026-10-31T10:00:00Z", "1793440800.000000000"),
            new Sample("2026-10-31T10:00:00.999999999Z", "1793440800.999999999"),
            new Sample("1970-01-01T00:00:00Z", "0.000000000"))) {
      Instant instant = Instant.parse(sample.instant());
      assertEquals(sample.encoded(), CursorInstantCodec.encode(instant), sample.instant());
      assertEquals(instant, CursorInstantCodec.decode(sample.encoded()), sample.encoded());
      assertEquals(instant, CursorInstantCodec.decode(CursorInstantCodec.encode(instant)));
    }
  }

  @Test
  void K2_codecAcceptsLegacyMillisecondsIncludingBoundaries() {
    assertEquals(LEGACY, CursorInstantCodec.decode("1793440800123"));
    assertEquals(Instant.EPOCH, CursorInstantCodec.decode("0"));
    assertEquals(
        Instant.ofEpochMilli(Long.MAX_VALUE), CursorInstantCodec.decode("9223372036854775807"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"5", "12", "123", "1234", "12345", "123456", "1234567", "12345678", "123456789"})
  void K3_codecAcceptsOneToNineFractionDigitsAndPadsRight(String fraction) {
    assertEquals(
        Instant.parse("2026-10-31T10:00:00." + fraction + "Z"),
        CursorInstantCodec.decode("1793440800." + fraction));
  }

  @Test
  void K4_R1_beforeEpochRejectedWithEpochAcceptedTwin() {
    assertEquals("0.000000000", CursorInstantCodec.encode(Instant.EPOCH));
    assertEquals(Instant.EPOCH, CursorInstantCodec.decode("0.000000000"));
    for (Instant negative :
        List.of(Instant.ofEpochSecond(-5), Instant.ofEpochSecond(-1, 999999999))) {
      assertThrows(IllegalArgumentException.class, () -> CursorInstantCodec.encode(negative));
    }
    for (String negative : List.of("-5", "-1.5", "-0.5")) {
      assertThrows(IllegalArgumentException.class, () -> CursorInstantCodec.decode(negative));
    }
  }

  static Stream<String> malformedTimes() {
    return Stream.of(
        null,
        "",
        "abc",
        "1.2.3",
        "-5",
        "-1.5",
        "1.",
        ".5",
        "1.1234567890",
        "1 .5",
        "9223372036854775808",
        "9223372036854775808.1",
        "9223372036854775807.1",
        "+1",
        " 1",
        "1 ",
        "1\n",
        "1e3");
  }

  @ParameterizedTest
  @MethodSource("malformedTimes")
  void R1_K1_codecRejectsMalformedTimestampWithAcceptedTwin(String rejected) {
    assertEquals(PRECISE, CursorInstantCodec.decode("1793440800.123456789"));
    assertThrows(IllegalArgumentException.class, () -> CursorInstantCodec.decode(rejected));
  }

  static Stream<Class<?>> cursorTypes() {
    return Stream.of(
            "com.app.tracker.comment.service.CommentCursor",
            "com.app.tracker.task.service.TaskCursor",
            "com.app.tracker.task.service.TaskActivityCursor",
            "com.app.tracker.notification.service.NotificationCursor")
        .map(
            name -> {
              try {
                return Class.forName(name);
              } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
              }
            });
  }

  @ParameterizedTest
  @MethodSource("cursorTypes")
  void K5_eachCursorPreservesTimeIdAndUnpaddedUrlSafeWireFormat(Class<?> type) throws Exception {
    for (Sample sample :
        List.of(
            new Sample("2026-10-31T10:00:00.123001Z", "1793440800.123001000"),
            new Sample("2026-10-31T10:00:00.123002Z", "1793440800.123002000"),
            new Sample("2026-10-31T10:00:00.123456789Z", "1793440800.123456789"),
            new Sample("2026-10-31T10:00:00Z", "1793440800.000000000"),
            new Sample("1970-01-01T00:00:00.000000001Z", "0.000000001"))) {
      Instant time = Instant.parse(sample.instant());
      var constructor = type.getDeclaredConstructor(Instant.class, UUID.class);
      constructor.setAccessible(true);
      Object cursor = constructor.newInstance(time, ID);
      String encoded = (String) invoke(type, "encode", cursor);
      assertEquals(b64(sample.encoded() + ":" + ID), encoded, type.getName());
      assertTrue(encoded.matches("[A-Za-z0-9_-]+"), encoded);
      assertCursor(type, decode(type, encoded), time, ID);
    }
  }

  @ParameterizedTest
  @MethodSource("cursorTypes")
  void K6_eachCursorAcceptsLegacyMillisecondsAndKeepsId(Class<?> type) throws Exception {
    assertCursor(type, decode(type, b64("1793440800123:" + ID)), LEGACY, ID);
    assertCursor(type, decode(type, b64("0:" + ID)), Instant.EPOCH, ID);
  }

  private static List<String> malformedCursors() {
    List<String> values = new ArrayList<>();
    Collections.addAll(
        values,
        null,
        "!!!not-base64!!!",
        b64("1793440800.123456789"),
        b64("1793440800.123456789:not-a-uuid"),
        b64("1793440800.123456789:" + ID + ":extra"));
    malformedTimes()
        .filter(value -> value != null)
        .forEach(value -> values.add(b64(value + ":" + ID)));
    return values;
  }

  @ParameterizedTest
  @MethodSource("cursorTypes")
  void R2_K5_eachCursorMapsMalformedInputsToBusinessRuleWithAcceptedTwin(Class<?> type)
      throws Exception {
    for (String rejected : malformedCursors()) {
      assertCursor(type, decode(type, b64("1793440800.123456789:" + ID)), PRECISE, ID);
      BusinessRuleException failure =
          assertThrows(
              BusinessRuleException.class,
              () -> decode(type, rejected),
              type.getName() + " input=" + rejected);
      assertEquals("Gecersiz cursor.", failure.getMessage());
    }
  }

  private static Object decode(Class<?> type, String cursor) throws Exception {
    Method method = type.getDeclaredMethod("decode", String.class);
    method.setAccessible(true);
    return call(method, null, cursor);
  }

  private static Object invoke(Class<?> type, String name, Object cursor) throws Exception {
    Method method = type.getDeclaredMethod(name);
    method.setAccessible(true);
    return call(method, cursor);
  }

  private static Object call(Method method, Object receiver, Object... arguments) throws Exception {
    try {
      return method.invoke(receiver, arguments);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new AssertionError(e.getCause());
    }
  }

  private static void assertCursor(Class<?> type, Object cursor, Instant time, UUID id)
      throws Exception {
    assertEquals(time, invoke(type, "createdAt", cursor), type.getName());
    assertEquals(id, invoke(type, "id", cursor), type.getName());
  }

  private static String b64(String raw) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  @Nested
  @Tag("docker")
  @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class ServletContract extends AbstractIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private Tenant tenant;
    private SecurityHttp http;
    private UUID project;
    private UUID approvedProject;
    private final List<UUID> tasks = new ArrayList<>();
    private final List<UUID> approved = new ArrayList<>();
    private final List<UUID> comments = new ArrayList<>();
    private final List<UUID> activity = new ArrayList<>();
    private final List<UUID> notifications = new ArrayList<>();

    @BeforeAll
    void seedSqlMicroseconds() throws Exception {
      tenant = SecurityFixture.create(context, port).a();
      http = new SecurityHttp(port);
      project = UUID.randomUUID();
      approvedProject = UUID.randomUUID();
      try (Connection c = openMigratorConnection()) {
        c.setAutoCommit(false);
        sql(
            c,
            "INSERT INTO projects (id, workspace_id, key, name) VALUES (?, ?, 'CP4', 'Cursor precision')",
            project,
            tenant.workspaceId());
        sql(
            c,
            "INSERT INTO projects (id, workspace_id, key, name) VALUES (?, ?, 'CA4', 'Cursor approval')",
            approvedProject,
            tenant.workspaceId());
        try (var statement = c.createStatement()) {
          statement.execute(
              "SELECT ensure_task_events_partitions(DATE '2026-10-01', DATE '2026-10-01')");
        }
        // Descending UUIDs oppose ascending time: sorting by ID alone cannot pass.
        long group = UUID.randomUUID().getMostSignificantBits();
        for (int i = 1; i <= 5; i++) {
          UUID task = new UUID(group, 100 - i);
          UUID archived = new UUID(group, 200 - i);
          tasks.add(task);
          approved.add(archived);
          sql(
              c,
              "INSERT INTO tasks (id, workspace_id, project_id, task_number, title, status, assignee_id, created_at)"
                  + " VALUES (?, ?, ?, ?, 'Precision task', 'To Do', ?, ?)",
              task,
              tenant.workspaceId(),
              project,
              i,
              tenant.admin().userId(),
              time(i));
          // approved_at is deliberately ordered opposite to created_at.
          sql(
              c,
              "INSERT INTO tasks (id, workspace_id, project_id, task_number, title, status, approved_at, approved_by, created_at)"
                  + " VALUES (?, ?, ?, ?, 'Precision approved', 'Done', ?, ?, ?)",
              archived,
              tenant.workspaceId(),
              approvedProject,
              i,
              time(i),
              tenant.admin().userId(),
              time(6 - i));
          UUID comment = new UUID(group, 300 - i);
          comments.add(comment);
          sql(
              c,
              "INSERT INTO comments (id, workspace_id, task_id, author_id, body, created_at)"
                  + " VALUES (?, ?, ?, ?, 'Precision comment', ?)",
              comment,
              tenant.workspaceId(),
              tasks.getFirst(),
              tenant.admin().userId(),
              time(i));
          UUID event = new UUID(group, 400 - i);
          activity.add(event);
          sql(
              c,
              "INSERT INTO task_events (id, task_id, actor_id, event_type, old_value, new_value, created_at)"
                  + " VALUES (?, ?, ?, 'status_changed', '{\"status\":\"To Do\"}'::jsonb,"
                  + " '{\"status\":\"In Progress\"}'::jsonb, ?)",
              event,
              tasks.getFirst(),
              tenant.admin().userId(),
              time(i));
          UUID notification = new UUID(group, 500 - i);
          notifications.add(notification);
          // Viewer receives no task-event fanout; this inbox contains exactly these five rows.
          sql(
              c,
              "INSERT INTO notifications (id, workspace_id, user_id, type, task_id, project_id, title, body, created_at)"
                  + " VALUES (?, ?, ?, 'TASK_STATUS_CHANGED', ?, ?, 'Precision', 'Precision body', ?)",
              notification,
              tenant.workspaceId(),
              tenant.viewer().userId(),
              tasks.getFirst(),
              project,
              time(i));
        }
        assertSqlPrecision(c, "tasks", "created_at", "project_id", project);
        assertSqlPrecision(c, "tasks", "created_at", "project_id", approvedProject);
        assertSqlPrecision(c, "tasks", "approved_at", "project_id", approvedProject);
        assertSqlPrecision(c, "comments", "created_at", "task_id", tasks.getFirst());
        assertSqlPrecision(c, "task_events", "created_at", "task_id", tasks.getFirst());
        assertSqlPrecision(c, "notifications", "created_at", "user_id", tenant.viewer().userId());
        c.commit();
      }
    }

    private Timestamp time(int micros) {
      return Timestamp.from(Instant.parse("2026-10-31T10:00:00.123000Z").plusNanos(micros * 1000L));
    }

    private void assertSqlPrecision(
        Connection c, String table, String field, String scope, UUID scopeId) throws Exception {
      // Verify the fixture in PostgreSQL: five distinct microseconds inside exactly one
      // millisecond.
      String query =
          "SELECT COUNT(*), COUNT(DISTINCT "
              + field
              + "), COUNT(DISTINCT date_trunc('milliseconds', "
              + field
              + ")), MIN("
              + field
              + "), MAX("
              + field
              + ") FROM "
              + table
              + " WHERE "
              + scope
              + " = ?";
      try (PreparedStatement statement = c.prepareStatement(query)) {
        statement.setObject(1, scopeId);
        try (var rows = statement.executeQuery()) {
          assertTrue(rows.next(), query);
          assertEquals(5, rows.getInt(1), query);
          assertEquals(5, rows.getInt(2), query);
          assertEquals(1, rows.getInt(3), query);
          assertEquals(time(1).toInstant(), rows.getTimestamp(4).toInstant(), query);
          assertEquals(time(5).toInstant(), rows.getTimestamp(5).toInstant(), query);
        }
      }
    }

    private void sql(Connection c, String query, Object... values) throws Exception {
      try (PreparedStatement statement = c.prepareStatement(query)) {
        for (int i = 0; i < values.length; i++) {
          statement.setObject(i + 1, values[i]);
        }
        assertEquals(1, statement.executeUpdate(), query);
      }
    }

    private record Endpoint(String path, SecurityFixture.Actor actor, List<UUID> expected) {}

    private Endpoint endpoint(String name) {
      return switch (name) {
        case "comments" ->
            new Endpoint(
                "/api/v1/tasks/" + tasks.getFirst() + "/comments", tenant.admin(), comments);
        case "tasks" ->
            new Endpoint("/api/v1/projects/" + project + "/tasks", tenant.admin(), tasks);
        case "activity" ->
            new Endpoint(
                "/api/v1/tasks/" + tasks.getFirst() + "/activity", tenant.admin(), activity);
        case "notifications" ->
            new Endpoint("/api/v1/notifications", tenant.viewer(), notifications);
        case "approved" ->
            new Endpoint(
                "/api/v1/projects/" + approvedProject + "/tasks/approved",
                tenant.admin(),
                approved);
        case "mine" -> new Endpoint("/api/v1/me/tasks", tenant.admin(), tasks);
        default -> throw new AssertionError(name);
      };
    }

    @Test
    void K7_commentsTraverseEveryMicrosecondExactlyOnceAscending() {
      assertEquals(comments, traverse(endpoint("comments")));
    }

    @Test
    void K8_projectTasksTraverseEveryMicrosecondExactlyOnceDescending() {
      assertEquals(tasks.reversed(), traverse(endpoint("tasks")));
    }

    @Test
    void K9_activityTraversesEveryMicrosecondExactlyOnce() {
      // K9's reference to K7 does not explicitly resolve ASC vs existing history direction.
      // Completeness/uniqueness is checked without guessing; see the contract finding.
      traverse(endpoint("activity"));
    }

    @Test
    void K9_notificationsTraverseEveryMicrosecondExactlyOnce() {
      traverse(endpoint("notifications"));
    }

    @Test
    void K10_approvedTasksUseApprovedAtAndTraverseEveryMicrosecondDescending() {
      assertEquals(approved.reversed(), traverse(endpoint("approved")));
    }

    @Test
    void K10_myTasksTraverseEveryMicrosecondExactlyOnceDescending() {
      assertEquals(tasks.reversed(), traverse(endpoint("mine")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"comments", "tasks", "activity", "notifications", "approved", "mine"})
    void R2_K5_httpMalformedCursorReturns400WithRealNextPageAcceptedTwin(String name) {
      Endpoint endpoint = endpoint(name);
      JsonNode first = page(endpoint, null);
      String good = first.path("next_cursor").asString();
      assertFalse(good.isBlank());
      assertEquals(2, page(endpoint, good).path("data").size());
      for (String rejected : malformedCursors()) {
        if (rejected == null) {
          continue; // No query parameter is the accepted first-page request above.
        }
        HttpResponse<String> response = get(endpoint, rejected);
        assertEquals(400, response.statusCode(), name + " " + rejected + ": " + response.body());
        assertTrue(response.body().contains("Gecersiz cursor."), response.body());
      }
      assertEquals(2, page(endpoint, good).path("data").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"comments", "tasks", "activity", "notifications", "approved", "mine"})
    void K6_httpAcceptsCursorAlreadyHeldByLegacyClient(String name) {
      Endpoint endpoint = endpoint(name);
      UUID boundaryId = endpoint.expected().getFirst();
      // K6 promises decoding/HTTP compatibility, not recovery of precision absent in old cursors.
      page(endpoint, b64("1793440800123:" + boundaryId));
    }

    private HttpResponse<String> get(Endpoint endpoint, String cursor) {
      String path = endpoint.path() + "?limit=2";
      if (cursor != null) {
        path += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
      }
      return http.get(path, endpoint.actor(), tenant.workspaceId(), null);
    }

    private JsonNode page(Endpoint endpoint, String cursor) {
      HttpResponse<String> response = get(endpoint, cursor);
      assertEquals(200, response.statusCode(), endpoint.path() + ": " + response.body());
      JsonNode result = mapper.readTree(response.body());
      assertTrue(result.path("data").isArray(), response.body());
      assertTrue(result.path("has_more").isBoolean(), response.body());
      return result;
    }

    private List<UUID> traverse(Endpoint endpoint) {
      List<UUID> result = new ArrayList<>();
      Set<UUID> seen = new HashSet<>();
      Set<String> cursors = new HashSet<>();
      String cursor = null;
      // Five records must finish in three pages; a fourth request cannot hide a cursor loop.
      for (int pageNumber = 0; pageNumber < 4; pageNumber++) {
        JsonNode page = page(endpoint, cursor);
        JsonNode data = page.path("data");
        assertEquals(pageNumber < 2 ? 2 : 1, data.size(), endpoint.path() + " page=" + pageNumber);
        for (JsonNode item : data) {
          UUID id = UUID.fromString(item.path("id").asString());
          assertTrue(seen.add(id), endpoint.path() + " duplicate=" + id);
          result.add(id);
        }
        if (!page.path("has_more").asBoolean()) {
          assertEquals(2, pageNumber, endpoint.path() + " ended early");
          assertEquals(
              new HashSet<>(endpoint.expected()), seen, endpoint.path() + " missing/foreign IDs");
          assertEquals(5, result.size());
          return result;
        }
        cursor = page.path("next_cursor").asString("");
        assertFalse(cursor.isBlank(), endpoint.path() + " missing next_cursor");
        assertTrue(cursors.add(cursor), endpoint.path() + " repeating next_cursor");
      }
      return fail(endpoint.path() + " traversal did not terminate");
    }
  }
}
