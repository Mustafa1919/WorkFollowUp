package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.outbox.OutboxRelay;
import com.app.tracker.core.retention.RetentionPurgeJob;
import com.app.tracker.core.retention.RetentionPurgeResult;
import com.app.tracker.security.support.SecurityHttp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** SEC-10: expectations come from dilim-04-sozlesme.md; all DB evidence uses owned rows. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RetentionPurgeContractTest extends AbstractIntegrationTest {

  @Autowired private RetentionPurgeJob job;
  @LocalServerPort private int port;
  // A relay must not deliver fixture rows while purge eligibility is being checked.
  @MockitoBean private OutboxRelay outboxRelay;

  @Test
  void K1_R1_expiredRefreshIsDeletedAndLiveRefreshSurvivesRegardlessOfUseOrRevocation()
      throws Exception {
    UUID user = user();
    UUID[] expired = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
    UUID[] live = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
    // Prepare first, then set both sides of the one-second boundary in one DB round trip.
    for (int state = 0; state < 4; state++) {
      refresh(expired[state], user, Instant.now().plus(1, ChronoUnit.DAYS), state);
      refresh(live[state], user, Instant.now().plus(1, ChronoUnit.DAYS), state);
    }
    Instant now = Instant.now();
    execute(
        "UPDATE refresh_tokens SET expires_at = CASE WHEN id IN (?, ?, ?, ?) THEN ?::timestamptz ELSE ?::timestamptz END"
            + " WHERE user_id = ?",
        expired[0],
        expired[1],
        expired[2],
        expired[3],
        now.minusSeconds(1),
        now.plusSeconds(1),
        user);
    job.purge();
    for (int state = 0; state < 4; state++) {
      assertRow("refresh_tokens", expired[state], 0);
      assertRow("refresh_tokens", live[state], 1);
    }
  }

  @Test
  void K2_R2_usedOrBeyondGraceIsDeletedButUnusedGraceAndValidHttpLinkSurvive() throws Exception {
    UUID user = user();
    Instant now = Instant.now();
    UUID used = UUID.randomUUID();
    UUID stale = UUID.randomUUID();
    UUID withinGrace = UUID.randomUUID();
    UUID valid = UUID.randomUUID();
    UUID validReset = UUID.randomUUID();
    String raw = UUID.randomUUID().toString();
    verification(used, user, now.plus(1, ChronoUnit.DAYS), now, UUID.randomUUID().toString());
    verification(
        stale,
        user,
        now.minus(1, ChronoUnit.DAYS).minusSeconds(60),
        null,
        UUID.randomUUID().toString());
    verification(
        withinGrace,
        user,
        now.minus(1, ChronoUnit.DAYS).plusSeconds(60),
        null,
        UUID.randomUUID().toString());
    verification(valid, user, now.plus(1, ChronoUnit.DAYS), null, raw);
    verification(
        validReset, user, now.plus(1, ChronoUnit.DAYS), null, UUID.randomUUID().toString());
    execute("UPDATE verification_tokens SET purpose = 'PASSWORD_RESET' WHERE id = ?", validReset);

    job.purge();

    assertRow("verification_tokens", used, 0);
    assertRow("verification_tokens", stale, 0);
    assertRow("verification_tokens", withinGrace, 1);
    assertRow("verification_tokens", valid, 1);
    assertRow("verification_tokens", validReset, 1);
    var response =
        new SecurityHttp(port)
            .get("/api/v1/auth/verify-email?token=" + raw, (String) null, null, null);
    assertTrue(
        response.statusCode() >= 200 && response.statusCode() < 300,
        "R2: retained verification link must succeed over HTTP: " + response.statusCode());
    assertEquals("true", scalar("SELECT email_verified::text FROM users WHERE id = ?", user));
  }

  @Test
  void K3_R3_bothTelemetryTablesRespect365DayBoundaryWithOneMinutePairs() throws Exception {
    Instant cutoff = Instant.now().minus(365, ChronoUnit.DAYS);
    for (String table : List.of("usage_events", "error_events")) {
      UUID old = UUID.randomUUID();
      UUID recent = UUID.randomUUID();
      telemetry(table, old, cutoff.minusSeconds(60));
      telemetry(table, recent, cutoff.plusSeconds(60));
      job.purge();
      assertRow(table, old, 0);
      assertRow(table, recent, 1);
    }
  }

  @Test
  void K4_R4_onlyOldUndeliveredEmailIsDeleted() throws Exception {
    Instant now = Instant.now();
    Instant old = now.minus(7, ChronoUnit.DAYS).minusSeconds(60);
    UUID eligible = outbox("notification.email", old, null);
    UUID processed = outbox("notification.email", old, now);
    UUID otherTopic = outbox("retention.contract." + UUID.randomUUID(), old, null);
    UUID young = outbox("notification.email", now.minus(7, ChronoUnit.DAYS).plusSeconds(60), null);

    job.purge();

    assertRow("outbox_events", eligible, 0);
    assertRow("outbox_events", processed, 1);
    assertRow("outbox_events", otherTopic, 1);
    assertRow("outbox_events", young, 1);
  }

  @Test
  void K5_allFiveTablesDrainMoreThan10000RowsAndReportExactTotals() throws Exception {
    // Drain eligible leftovers before arranging this test; never count another test's rows.
    job.purge();
    UUID user = user();
    UUID marker = UUID.randomUUID();
    Instant old = Instant.now().minus(400, ChronoUnit.DAYS);
    int rows = 10_001;
    execute(
        "INSERT INTO refresh_tokens(id,user_id,family_id,token_hash,expires_at)"
            + " SELECT gen_random_uuid(), ?, ?, md5(? || n::text), ?"
            + " FROM generate_series(1, ?) n",
        user,
        marker,
        marker.toString(),
        old,
        rows);
    execute(
        "INSERT INTO verification_tokens(id,user_id,token_hash,purpose,expires_at)"
            + " SELECT gen_random_uuid(), ?, md5(? || n::text), 'PASSWORD_RESET', ?"
            + " FROM generate_series(1, ?) n",
        user,
        marker.toString(),
        old,
        rows);
    execute(
        "INSERT INTO usage_events(id,workspace_id,feature,action,occurred_at)"
            + " SELECT gen_random_uuid(), ?, 'retention', 'contract', ?"
            + " FROM generate_series(1, ?) n",
        marker,
        old,
        rows);
    execute(
        "INSERT INTO error_events(id,workspace_id,source,error_type,occurred_at)"
            + " SELECT gen_random_uuid(), ?, 'test', 'retention.contract', ?"
            + " FROM generate_series(1, ?) n",
        marker,
        old,
        rows);
    execute(
        "INSERT INTO outbox_events(id,topic,event_type,aggregate_id,workspace_id,payload,created_at)"
            + " SELECT gen_random_uuid(), 'notification.email', 'RETENTION_CONTRACT', ?, ?,"
            + " '{}'::jsonb, ? FROM generate_series(1, ?) n",
        marker,
        marker,
        old,
        rows);
    for (String table : List.of("refresh_tokens", "verification_tokens")) {
      assertEquals(rows, count(table, "user_id", user));
    }
    for (String table : List.of("usage_events", "error_events", "outbox_events")) {
      assertEquals(rows, count(table, "workspace_id", marker));
    }

    RetentionPurgeResult result = job.purge();

    assertEquals(new RetentionPurgeResult(rows, rows, rows, rows, rows), result);
    for (String table : List.of("refresh_tokens", "verification_tokens")) {
      assertEquals(0, count(table, "user_id", user));
    }
    for (String table : List.of("usage_events", "error_events", "outbox_events")) {
      assertEquals(0, count(table, "workspace_id", marker));
    }
    assertEquals(new RetentionPurgeResult(0, 0, 0, 0, 0), job.purge());
  }

  @Test
  void R5_businessDataSurvivesAlongsideAnEligibleDeletion() throws Exception {
    UUID user = user();
    UUID workspace = UUID.randomUUID();
    UUID project = UUID.randomUUID();
    UUID task = UUID.randomUUID();
    UUID comment = UUID.randomUUID();
    UUID feedback = UUID.randomUUID();
    Instant old = Instant.now().minus(800, ChronoUnit.DAYS);
    execute(
        "INSERT INTO workspaces(id,name,created_at) VALUES (?, 'Retention contract', ?)",
        workspace,
        old);
    execute(
        "INSERT INTO workspace_users(workspace_id,user_id,role) VALUES (?,?,'ADMIN')",
        workspace,
        user);
    execute(
        "INSERT INTO projects(id,workspace_id,key,name) VALUES (?,?,'RET','Retention')",
        project,
        workspace);
    execute(
        "INSERT INTO tasks(id,workspace_id,project_id,task_number,title,status,created_at,updated_at)"
            + " VALUES (?,?,?,1,'Retention contract','todo',?,?)",
        task,
        workspace,
        project,
        old,
        old);
    execute(
        "INSERT INTO comments(id,workspace_id,task_id,author_id,body,created_at,updated_at)"
            + " VALUES (?,?,?,?,'retained comment',?,?)",
        comment,
        workspace,
        task,
        user,
        old,
        old);
    execute(
        "INSERT INTO feedback(id,workspace_id,user_id,message,created_at)"
            + " VALUES (?,?,?,'retained feedback',?)",
        feedback,
        workspace,
        user,
        old);
    UUID expired = UUID.randomUUID();
    refresh(expired, user, old, 0);

    job.purge();

    assertRow("refresh_tokens", expired, 0);
    assertRow("users", user, 1);
    assertRow("workspaces", workspace, 1);
    assertRow("projects", project, 1);
    assertRow("tasks", task, 1);
    assertRow("comments", comment, 1);
    assertRow("feedback", feedback, 1);
    assertEquals("retained comment", scalar("SELECT body FROM comments WHERE id = ?", comment));
    assertEquals(
        "retained feedback", scalar("SELECT message FROM feedback WHERE id = ?", feedback));
    assertEquals(
        1,
        Integer.parseInt(
            scalar(
                "SELECT count(*) FROM workspace_users WHERE workspace_id = ? AND user_id = ?",
                workspace,
                user)));
  }

  @Test
  void K1_K2_K3_K4_runtimeHasDeletePrivilegesOnAllPurgeTables() throws Exception {
    for (String table :
        List.of(
            "refresh_tokens",
            "verification_tokens",
            "usage_events",
            "error_events",
            "outbox_events")) {
      assertEquals(
          "true",
          scalar("SELECT has_table_privilege('app_runtime', ?, 'DELETE')::text", "public." + table),
          table);
    }
  }

  @Test
  void K3_usageOccurredAtIndexExistsAndIsValid() throws Exception {
    assertEquals(
        "true",
        scalar(
            "SELECT (i.indisvalid AND i.indisready)::text FROM pg_index i"
                + " JOIN pg_class c ON c.oid = i.indexrelid"
                + " WHERE c.oid = to_regclass('public.idx_usage_events_occurred_at')"
                + " AND i.indrelid = 'public.usage_events'::regclass"
                + " AND pg_get_indexdef(i.indexrelid, 1, true) = 'occurred_at'"));
  }

  private UUID user() throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO users(id,email,password_hash,full_name) VALUES (?,?,'hash','Retention')",
        id,
        "retention-" + id + "@example.test");
    return id;
  }

  private void refresh(UUID id, UUID user, Instant expires, int state) throws SQLException {
    execute(
        "INSERT INTO refresh_tokens(id,user_id,family_id,token_hash,expires_at,used_at,revoked)"
            + " VALUES (?,?,?,?,?,?,?)",
        id,
        user,
        UUID.randomUUID(),
        id.toString(),
        expires,
        (state & 1) != 0 ? Instant.now() : null,
        (state & 2) != 0);
  }

  private void verification(UUID id, UUID user, Instant expires, Instant used, String raw)
      throws Exception {
    String hash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    execute(
        "INSERT INTO verification_tokens(id,user_id,token_hash,purpose,expires_at,used_at)"
            + " VALUES (?,?,?,'EMAIL_VERIFICATION',?,?)",
        id,
        user,
        hash,
        expires,
        used);
  }

  private void telemetry(String table, UUID id, Instant occurred) throws SQLException {
    if (table.equals("usage_events")) {
      execute(
          "INSERT INTO usage_events(id,feature,action,occurred_at) VALUES (?,'retention','test',?)",
          id,
          occurred);
    } else {
      execute(
          "INSERT INTO error_events(id,source,error_type,occurred_at) VALUES (?,'test','retention',?)",
          id,
          occurred);
    }
  }

  private UUID outbox(String topic, Instant created, Instant processed) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO outbox_events(id,topic,event_type,aggregate_id,payload,created_at,processed_at)"
            + " VALUES (?,?,'RETENTION_CONTRACT',?,'{}'::jsonb,?,?)",
        id,
        topic,
        UUID.randomUUID(),
        created,
        processed);
    return id;
  }

  private void assertRow(String table, UUID id, int expected) throws SQLException {
    assertEquals(expected, count(table, "id", id), table + ": " + id);
  }

  private int count(String table, String column, UUID marker) throws SQLException {
    return Integer.parseInt(
        scalar("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", marker));
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (Connection connection = openMigratorConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      statement.executeUpdate();
    }
  }

  private String scalar(String sql, Object... parameters) throws SQLException {
    try (Connection connection = openMigratorConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next(), "Expected DB evidence: " + sql);
        return rows.getString(1);
      }
    }
  }

  private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
    for (int i = 0; i < parameters.length; i++) {
      Object value = parameters[i];
      statement.setObject(
          i + 1, value instanceof Instant instant ? Timestamp.from(instant) : value);
    }
  }
}
