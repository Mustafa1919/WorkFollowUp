package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.app.tracker.automation.AutomationActor;
import com.app.tracker.automation.AutomationEventConsumer;
import com.app.tracker.automation.AutomationRuleEngine;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.GlobalExceptionHandler;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.security.support.MailpitInbox;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityHttp;
import com.app.tracker.security.support.TenantResources;
import com.app.tracker.task.dto.BulkOperation;
import com.app.tracker.task.dto.BulkTaskRequest;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.telemetry.TelemetryEventPublisher;
import jakarta.validation.Validation;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Expectations come exclusively from dilim-09-sozlesme.md. Run the integration class with Docker;
 * CoverageGapLocalContractTest (in this file) also runs without Docker. No production mutations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoverageGapContractTest extends AbstractIntegrationTest {
  @LocalServerPort private int port;
  @Autowired private ApplicationContext context;
  @Autowired private ObjectMapper mapper;
  @Autowired private TenantExecutor tenants;
  @Autowired private TaskService tasks;
  @Autowired private KafkaTemplate<String, String> kafka;
  private SecurityFixture fixture;
  private SecurityHttp http;

  @BeforeEach
  void fixture() {
    fixture = SecurityFixture.create(context, port);
    http = fixture.http();
  }

  @Test
  void K1_pendingInvitationCanBeAcceptedByVerifiedMatchingAccount() throws Exception {
    String token = inviteVerifiedOutsider();
    assertEquals("PENDING", preview(token));
    assertEquals("0", membershipCount());
    var accepted = accept(token);
    assertEquals(200, accepted.statusCode(), accepted.body());
    assertEquals("1", membershipCount());
  }

  @Test
  void R1_expiredInvitationIsPreviewedAsExpiredAndCannotCreateMembership() throws Exception {
    String token = inviteVerifiedOutsider();
    assertEquals("PENDING", preview(token));
    assertEquals(
        1,
        update(
            "UPDATE workspace_invitations SET expires_at = now() - interval '1 day'"
                + " WHERE workspace_id = ? AND email = ? AND status = 'PENDING'",
            fixture.a().workspaceId(),
            fixture.outsider().email()));
    assertEquals("EXPIRED", preview(token));
    var rejected = accept(token);
    assert4xx(rejected);
    assertEquals(
        "Gecersiz veya suresi dolmus davet.",
        mapper.readTree(rejected.body()).path("detail").asString());
    assertEquals("0", membershipCount());
  }

  @Test
  void K2_bulkAcceptsExactly100DistinctTasksAndChangesEveryTask() throws Exception {
    List<UUID> ids = bulkTasks(100);
    assertEquals(100, ids.stream().distinct().count());
    var accepted = assign(ids);
    assert2xx(accepted);
    assertEquals(100, mapper.readTree(accepted.body()).size());
    for (UUID id : ids) assertEquals(fixture.a().manager().userId().toString(), assignee(id));
  }

  @Test
  void R2_bulkRejects101TasksWithoutChangingAnyTaskOrActivity() throws Exception {
    List<UUID> ids = bulkTasks(101);
    assertEquals(101, ids.stream().distinct().count());
    var before = snapshot();
    var rejected = assign(ids);
    assertTrue(
        rejected.statusCode() == 400 || rejected.statusCode() == 422,
        () -> rejected.statusCode() + ": " + rejected.body());
    assertEquals(before, snapshot());
  }

  @Test
  void K3_unapprovedDoneTaskCanBeAssignedInBulk() throws Exception {
    UUID id = fixture.a().otherTaskId();
    assertEquals(1, update("UPDATE tasks SET status = 'Done' WHERE id = ?", id));
    assertNull(scalar("SELECT approved_at::text FROM tasks WHERE id = ?", id));
    assert2xx(assign(List.of(id)));
    assertEquals(fixture.a().manager().userId().toString(), assignee(id));
    assertEquals("Done", scalar("SELECT status FROM tasks WHERE id = ?", id));
  }

  @Test
  void R3_approvedTaskRejectsWholeBatchIncludingEarlierMutableTask() throws Exception {
    UUID mutable = fixture.a().otherTaskId();
    UUID approved = fixture.a().taskId();
    assertEquals(1, update("UPDATE tasks SET status = 'Done' WHERE id = ?", mutable));
    assertEquals(
        1,
        update(
            "UPDATE tasks SET status = 'Done', approved_at = now(), approved_by = ? WHERE id = ?",
            fixture.a().admin().userId(),
            approved));
    assertNotNull(scalar("SELECT approved_at::text FROM tasks WHERE id = ?", approved));
    var before = snapshot();
    // A mutable item FIRST detects implementations that commit each item separately.
    var rejected = assign(List.of(mutable, approved));
    assert4xx(rejected);
    assertEquals(before, snapshot(), "R3: no partial task or activity writes may survive");
  }

  @Test
  void K4_runtimeTransactionWithLocalTenantSeesOnlyThatTenantsTasks() throws Exception {
    try (Connection connection = openRuntimeConnection()) {
      connection.setAutoCommit(false);
      setLocalTenant(connection);
      assertVisibleTenant(connection);
      connection.rollback();
    }
  }

  @Test
  void R4_samePhysicalConnectionLosesLocalTenantAfterCommit() throws Exception {
    try (Connection connection = openRuntimeConnection()) {
      connection.setAutoCommit(false);
      String backend = scalar(connection, "SELECT pg_backend_pid()::text");
      setLocalTenant(connection);
      assertVisibleTenant(connection);
      connection.commit();
      // The first SELECT starts the second transaction on the very same JDBC connection.
      assertEquals(backend, scalar(connection, "SELECT pg_backend_pid()::text"));
      String guc = scalar(connection, "SELECT current_setting('app.current_workspace_id', true)");
      assertTrue(guc == null || guc.isEmpty(), "R4: tenant GUC leaked: " + guc);
      assertEquals("0", scalar(connection, "SELECT count(*)::text FROM tasks"));
      connection.rollback();
    }
  }

  @Test
  void K5_K2_normal500ProbeProducesOneEcsJsonErrorEvent() throws Exception {
    CoverageGapLogProbe.assertNormalRequest();
  }

  @Test
  void R5_R2_R3_clientHeaderIsPreservedInOneEncodedJsonLine() throws Exception {
    CoverageGapLogProbe.assertInjectedRequest();
  }

  @Test
  void K6_userStatusChangeTriggersEnabledAutomationExactlyOnce() throws Exception {
    TaskPair pair = automationPair();
    List<String> before = events(pair.parent());
    tenants.runAs(
        fixture.a().workspaceId(),
        () -> tasks.updateStatus(pair.child(), TaskStatus.DONE, fixture.a().admin().userId()));
    await(
        "K6: enabled rule did not move parent to Review",
        () -> TaskStatus.REVIEW.equals(status(pair.parent())));
    // A marker on every partition waits for the consumer, rather than assuming a sleep is enough.
    awaitConsumerBarriers();
    assertEquals(before.size() + 1, events(pair.parent()).size());
    assertEquals(
        "1",
        scalar(
            "SELECT count(*)::text FROM task_events WHERE task_id = ? AND actor_id = ?",
            pair.parent(),
            AutomationActor.SYSTEM_USER_ID));
    assertEquals(TaskStatus.REVIEW, status(pair.parent()));
  }

  @Test
  void R6_publishedSystemStatusEventCreatesNoAutomationChangeOrSecondActivity() throws Exception {
    TaskPair pair = automationPair();
    int earlierActivities = events(pair.child()).size();
    assertEquals(1, update("UPDATE tasks SET status = 'Done' WHERE id = ?", pair.child()));
    // The original system-authored transition exists; only a chained change is forbidden.
    assertEquals(
        1,
        update(
            "INSERT INTO task_events(id, task_id, actor_id, event_type, old_value, new_value)"
                + " VALUES (?, ?, ?, 'STATUS_CHANGED', '{\"status\":\"To Do\"}'::jsonb,"
                + " '{\"status\":\"Done\"}'::jsonb)",
            UUID.randomUUID(),
            pair.child(),
            AutomationActor.SYSTEM_USER_ID));
    var before = snapshot();
    String envelope =
        CoverageGapLocalContractTest.envelope(
            fixture.a().workspaceId(), pair.child(), AutomationActor.SYSTEM_USER_ID);
    RecordMetadata record =
        kafka
            .send("task.events", 0, pair.child().toString(), envelope)
            .get(10, TimeUnit.SECONDS)
            .getRecordMetadata();
    awaitConsumed(record);
    assertEquals(before, snapshot(), "R6: system event must not start another automation");
    assertEquals(TaskStatus.TO_DO, status(pair.parent()));
    assertEquals(earlierActivities + 1, events(pair.child()).size());
  }

  private String inviteVerifiedOutsider() throws Exception {
    assertEquals(
        1,
        update("UPDATE users SET email_verified = true WHERE id = ?", fixture.outsider().userId()));
    assertEquals(
        "true",
        scalar("SELECT email_verified::text FROM users WHERE id = ?", fixture.outsider().userId()));
    var invited =
        http.post(
            "/api/v1/workspaces/members/invitations",
            fixture.a().admin(),
            fixture.a().workspaceId(),
            mapper.writeValueAsString(
                Map.of("email", fixture.outsider().email(), "role", "DEVELOPER")));
    assert2xx(invited);
    return new MailpitInbox(p -> mailpitApiUrl(p), mapper).inviteToken(fixture.outsider().email());
  }

  private String preview(String token) {
    var response = http.get("/api/v1/invitations/" + token, (String) null, null, null);
    assertEquals(200, response.statusCode(), response.body());
    return mapper.readTree(response.body()).path("status").asString();
  }

  private HttpResponse<String> accept(String token) {
    return http.post("/api/v1/invitations/" + token + "/accept", fixture.outsider(), null, null);
  }

  private String membershipCount() throws SQLException {
    return scalar(
        "SELECT count(*)::text FROM workspace_users WHERE workspace_id = ? AND user_id = ?",
        fixture.a().workspaceId(),
        fixture.outsider().userId());
  }

  private List<UUID> bulkTasks(int count) throws SQLException {
    var resources = new TenantResources(fixture, mapper, fixture.a(), "D09-" + UUID.randomUUID());
    UUID project = resources.id(TenantResources.Type.PROJECT);
    List<UUID> ids = new ArrayList<>();
    try (Connection connection = openMigratorConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO tasks(id, workspace_id, project_id, task_number, title, status)"
                    + " VALUES (?, ?, ?, ?, ?, 'To Do')")) {
      for (int i = 1; i <= count; i++) {
        UUID id = UUID.randomUUID();
        ids.add(id);
        statement.setObject(1, id);
        statement.setObject(2, fixture.a().workspaceId());
        statement.setObject(3, project);
        statement.setInt(4, i);
        statement.setString(5, "D09 bulk task " + i);
        statement.addBatch();
      }
      assertEquals(count, statement.executeBatch().length);
    }
    return ids;
  }

  private HttpResponse<String> assign(List<UUID> ids) {
    return http.post(
        "/api/v1/tasks/bulk",
        fixture.a().admin(),
        fixture.a().workspaceId(),
        mapper.writeValueAsString(
            Map.of(
                "taskIds",
                ids,
                "operation",
                "ASSIGNEE",
                "assigneeId",
                fixture.a().manager().userId())));
  }

  private String assignee(UUID id) throws SQLException {
    return scalar("SELECT assignee_id::text FROM tasks WHERE id = ?", id);
  }

  private String status(UUID id) {
    try {
      return scalar("SELECT status FROM tasks WHERE id = ?", id);
    } catch (SQLException e) {
      throw new AssertionError(e);
    }
  }

  private List<String> snapshot() throws SQLException {
    List<String> rows = new ArrayList<>();
    rows.addAll(
        query(
            "SELECT 'task:' || to_jsonb(t)::text FROM tasks t WHERE workspace_id = ? ORDER BY id",
            fixture.a().workspaceId()));
    rows.addAll(
        query(
            "SELECT 'event:' || to_jsonb(e)::text FROM task_events e JOIN tasks t ON t.id = e.task_id"
                + " WHERE t.workspace_id = ? ORDER BY e.id, e.created_at",
            fixture.a().workspaceId()));
    return rows;
  }

  private List<String> events(UUID id) throws SQLException {
    return query(
        "SELECT to_jsonb(e)::text FROM task_events e WHERE task_id = ? ORDER BY id, created_at",
        id);
  }

  private void setLocalTenant(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("SET LOCAL app.current_workspace_id = '" + fixture.a().workspaceId() + "'");
    }
  }

  private void assertVisibleTenant(Connection connection) throws SQLException {
    assertEquals("app_runtime", scalar(connection, "SELECT current_user"));
    assertEquals(
        fixture.a().workspaceId().toString(),
        scalar(connection, "SELECT current_setting('app.current_workspace_id', true)"));
    assertEquals(
        "1",
        scalar(connection, "SELECT count(*)::text FROM tasks WHERE id = ?", fixture.a().taskId()));
    assertEquals(
        "0",
        scalar(connection, "SELECT count(*)::text FROM tasks WHERE id = ?", fixture.b().taskId()));
    assertEquals(
        "0",
        scalar(
            connection,
            "SELECT count(*)::text FROM tasks WHERE workspace_id <> ?",
            fixture.a().workspaceId()));
  }

  private record TaskPair(UUID parent, UUID child) {}

  private TaskPair automationPair() {
    var tenant = fixture.a();
    // Arrange a real parent/child graph with exactly one child, then enable the contract's
    // template.
    Task parent =
        tenants.runAs(
            tenant.workspaceId(), () -> tasks.createTask(tenant.projectId(), "D09 parent"));
    Task child =
        tenants.runAs(
            tenant.workspaceId(), () -> tasks.createTask(tenant.projectId(), "D09 child"));
    tenants.runAs(
        tenant.workspaceId(),
        () -> tasks.setParent(child.getId(), parent.getId(), tenant.admin().userId()));
    assert2xx(
        http.put(
            "/api/v1/projects/"
                + tenant.projectId()
                + "/automation-rules/SUBTASK_ALL_DONE_PARENT_TO_REVIEW",
            tenant.admin(),
            tenant.workspaceId(),
            "{\"enabled\":true}"));
    assertEquals(TaskStatus.TO_DO, status(parent.getId()));
    return new TaskPair(parent.getId(), child.getId());
  }

  private void awaitConsumerBarriers() throws Exception {
    for (var partition : kafka.partitionsFor("task.events")) {
      String marker =
          mapper.writeValueAsString(
              Map.of(
                  "eventId",
                  UUID.randomUUID().toString(),
                  "eventType",
                  "D09_BARRIER",
                  "workspaceId",
                  fixture.a().workspaceId().toString(),
                  "occurredAt",
                  java.time.Instant.now().toString(),
                  "payload",
                  Map.of()));
      var record =
          kafka
              .send("task.events", partition.partition(), "d09-barrier", marker)
              .get(10, TimeUnit.SECONDS)
              .getRecordMetadata();
      awaitConsumed(record);
    }
  }

  private void awaitConsumed(RecordMetadata record) throws Exception {
    Map<String, Object> configuration =
        Map.of(
            "bootstrap.servers",
            kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"),
            "default.api.timeout.ms",
            5000,
            "request.timeout.ms",
            5000);
    try (AdminClient admin = AdminClient.create(configuration)) {
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (System.nanoTime() < deadline) {
        var offsets =
            admin
                .listConsumerGroupOffsets(AutomationRuleEngine.CONSUMER)
                .partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS);
        var offset = offsets.get(new TopicPartition(record.topic(), record.partition()));
        if (offset != null && offset.offset() > record.offset()) return;
        Thread.sleep(100);
      }
      fail("K6/R6: automation consumer did not commit " + record + " within 30 seconds");
    }
  }

  private static void await(String failure, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(100);
    }
    fail(failure);
  }

  private static void assert2xx(HttpResponse<String> response) {
    assertTrue(
        response.statusCode() >= 200 && response.statusCode() < 300,
        () -> response.statusCode() + ": " + response.body());
  }

  private static void assert4xx(HttpResponse<String> response) {
    assertTrue(
        response.statusCode() >= 400 && response.statusCode() < 500,
        () -> response.statusCode() + ": " + response.body());
  }

  private static int update(String sql, Object... args) throws SQLException {
    try (Connection connection = openMigratorConnection();
        var statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      return statement.executeUpdate();
    }
  }

  private static String scalar(String sql, Object... args) throws SQLException {
    try (Connection connection = openMigratorConnection()) {
      return scalar(connection, sql, args);
    }
  }

  private static String scalar(Connection connection, String sql, Object... args)
      throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      try (var rows = statement.executeQuery()) {
        assertTrue(rows.next(), sql);
        String result = rows.getString(1);
        assertFalse(rows.next(), sql);
        return result;
      }
    }
  }

  private static List<String> query(String sql, Object... args) throws SQLException {
    try (Connection connection = openMigratorConnection();
        var statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      try (var rows = statement.executeQuery()) {
        List<String> results = new ArrayList<>();
        while (rows.next()) results.add(rows.getString(1));
        return results;
      }
    }
  }

  private static void bind(PreparedStatement statement, Object... args) throws SQLException {
    for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
  }
}

/** Flat companion: -Dtest=CoverageGapLocalContractTest; no Spring context or container startup. */
class CoverageGapLocalContractTest {
  @Test
  void K2_dtoAccepts100DistinctTaskIds() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      assertTrue(factory.getValidator().validate(request(100)).isEmpty());
    }
  }

  @Test
  void R2_dtoRejects101TaskIdsSpecificallyAtTaskIdsSizeConstraint() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var violations = factory.getValidator().validate(request(101));
      assertEquals(1, violations.size());
      var violation = violations.iterator().next();
      assertEquals("taskIds", violation.getPropertyPath().toString());
      assertEquals(
          jakarta.validation.constraints.Size.class,
          violation.getConstraintDescriptor().getAnnotation().annotationType());
    }
  }

  @Test
  void K5_K2_normal500ProbeProducesOneEcsJsonErrorEvent() throws Exception {
    CoverageGapLogProbe.assertNormalRequest();
  }

  @Test
  void R5_R2_R3_clientHeaderIsPreservedInOneEncodedJsonLine() throws Exception {
    CoverageGapLogProbe.assertInjectedRequest();
  }

  @Test
  void K6_consumerDispatchesUserDoneEventOnceWithTenantContext() {
    var engine = mock(AutomationRuleEngine.class);
    var consumer = new AutomationEventConsumer(new ObjectMapper(), new TenantExecutor(), engine);
    UUID workspace = UUID.randomUUID();
    UUID task = UUID.randomUUID();
    doAnswer(
            invocation -> {
              assertEquals(workspace, com.app.tracker.core.tenancy.TenantContext.getWorkspaceId());
              return null;
            })
        .when(engine)
        .onTaskDone(any(), eq(task));
    consumer.onMessage(envelope(workspace, task, UUID.randomUUID()));
    verify(engine, times(1)).onTaskDone(any(), eq(task));
    verifyNoMoreInteractions(engine);
  }

  @Test
  void R6_consumerDoesNotDispatchSystemDoneEvent() {
    var engine = mock(AutomationRuleEngine.class);
    var consumer = new AutomationEventConsumer(new ObjectMapper(), new TenantExecutor(), engine);
    consumer.onMessage(
        envelope(UUID.randomUUID(), UUID.randomUUID(), AutomationActor.SYSTEM_USER_ID));
    verifyNoInteractions(engine);
  }

  static String envelope(UUID workspace, UUID task, UUID actor) {
    return new ObjectMapper()
        .writeValueAsString(
            Map.of(
                "eventId",
                UUID.randomUUID().toString(),
                "eventType",
                "TASK_STATUS_UPDATED",
                "workspaceId",
                workspace.toString(),
                "occurredAt",
                java.time.Instant.now().toString(),
                "payload",
                Map.of(
                    "taskId",
                    task.toString(),
                    "actorId",
                    actor.toString(),
                    "oldStatus",
                    TaskStatus.TO_DO,
                    "newStatus",
                    TaskStatus.DONE)));
  }

  private static BulkTaskRequest request(int count) {
    return new BulkTaskRequest(
        IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList(),
        BulkOperation.ASSIGNEE,
        null,
        null,
        UUID.randomUUID(),
        null);
  }
}

/** Real MVC exception dispatch encoded as ECS JSON with the resolved prod console format. */
final class CoverageGapLogProbe implements AutoCloseable {
  private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private final LoggerContext context = new LoggerContext();
  private final StructuredLogEncoder encoder = new StructuredLogEncoder();
  private final CaptureAppender appender = new CaptureAppender();
  private final ByteArrayOutputStream output = new ByteArrayOutputStream();

  private CoverageGapLogProbe() {
    var environment = new StandardEnvironment();
    environment.setActiveProfiles("prod");
    ConfigDataEnvironmentPostProcessor.applyTo(environment);
    String format = environment.getProperty("logging.structured.format.console");
    assertNotNull(format, "K2/R2: encoder format must come from the real prod configuration");
    context.putObject(org.springframework.core.env.Environment.class.getName(), environment);
    context.start();
    encoder.setContext(context);
    encoder.setCharset(StandardCharsets.UTF_8);
    encoder.setFormat(format);
    encoder.start();
    appender.setContext(context);
    appender.setEncoder(encoder);
    appender.setOutputStream(output);
    appender.start();
    assertTrue(
        appender.isStarted(), "K2/R2/R3: the structured appender must actually encode events");
    logger.addAppender(appender);
  }

  static void assertNormalRequest() throws Exception {
    try (var probe = new CoverageGapLogProbe()) {
      String value = "ordinary-client-value";
      probe.request(value);
      probe.assertEncodedEvent(value);
    }
  }

  static void assertInjectedRequest() throws Exception {
    try (var probe = new CoverageGapLogProbe()) {
      String forged = "2026-10-31 ERROR forged-d10-" + UUID.randomUUID();
      String value = "client-value\r\n" + forged;
      // A servlet-level raw CRLF header exercises the sink even when HTTP clients reject CRLF.
      probe.request(value);
      probe.assertEncodedEvent(value);
      assertFalse(
          probe
              .rendered()
              .lines()
              .anyMatch(line -> line.startsWith("2026-10-31 ERROR forged-d10-")),
          "R2: client input must not create a forged physical log line");
    }
  }

  private void assertEncodedEvent(String clientValue) throws Exception {
    assertEquals(1, appender.events, "K2/R2: expected exactly one handler log event");
    String rendered = rendered();
    assertTrue(rendered.endsWith("\n"), "K2/R2: encoded event must have a trailing line ending");
    String body = rendered.substring(0, rendered.length() - (rendered.endsWith("\r\n") ? 2 : 1));
    assertFalse(body.contains("\r"), "K2/R2: CR must be JSON-escaped");
    assertFalse(body.contains("\n"), "K2/R2: LF must be JSON-escaped");
    assertEquals(1, rendered.lines().count(), "K2/R2: exactly one physical output line");
    var json = new ObjectMapper().readTree(body);
    assertTrue(json.isObject(), "K2/R2: structured output must be a JSON object");
    assertEquals("ERROR", json.at("/log/level").asString());
    assertEquals("Beklenmeyen hata", json.path("message").asString());
    var errorMessage = json.at("/error/message");
    var stackTrace = json.at("/error/stack_trace");
    assertTrue(errorMessage.isString(), "K2/R2: ECS error.message must be a string");
    assertTrue(stackTrace.isString(), "K2/R2: ECS error.stack_trace must be a string");
    assertTrue(
        errorMessage.asString().contains(clientValue)
            || stackTrace.asString().contains(clientValue),
        "K2/R2: decoded error must retain the original client value, including CR and LF");
  }

  private void request(String value) throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler(mock(TelemetryEventPublisher.class)))
            .build();
    var response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/contract/gap09-probe")
                    .header("X-Contract-Value", value))
            .andReturn()
            .getResponse();
    assertEquals(500, response.getStatus(), response.getContentAsString());
  }

  private String rendered() {
    return output.toString(StandardCharsets.UTF_8);
  }

  private static final class CaptureAppender extends OutputStreamAppender<ILoggingEvent> {
    private int events;

    @Override
    protected void append(ILoggingEvent event) {
      events++;
      super.append(event);
    }
  }

  @RestController
  @org.springframework.boot.test.context.TestComponent
  static class ProbeController {
    @GetMapping("/api/v1/contract/gap09-probe")
    String failRequest(@RequestHeader("X-Contract-Value") String value) {
      throw new IllegalStateException("D09 probe failed for " + value);
    }
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
    appender.stop();
    encoder.stop();
    context.stop();
  }
}
