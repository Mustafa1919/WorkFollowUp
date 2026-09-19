package com.app.tracker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V10'un EN riskli yolu: VAR OLAN veriyi kaybetmeden partitioned tabloya tasimak. Ayri, Spring'siz
 * bir Postgres'te calisir ve migration'i bilerek SUPERUSER OLMAYAN bir tablo sahibiyle uygular
 * (prod'da migrator superuser olmamali). Boylece FORCE ROW LEVEL SECURITY tablo sahibine de
 * uygulandigi icin "INSERT ... SELECT"in sessizce 0 satir tasima tuzagi (V10 tuzak 1) gercekten
 * sinanir: AbstractIntegrationTest'in migrator'u superuser oldugundan orada bu yol hic calismazdi.
 */
class TaskEventsPartitionMigrationTest {

  private static PostgreSQLContainer<?> postgres;
  private static String url;

  @BeforeAll
  static void startPostgres() throws Exception {
    postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    postgres.start();
    try (Connection admin =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("CREATE ROLE mig LOGIN NOSUPERUSER PASSWORD 'mig'");
      st.execute("CREATE DATABASE migtest OWNER mig");
    }
    url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/migtest");
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void existingEventsSurviveAndLandInTheirMonthlyPartitions() throws Exception {
    flyway("9").migrate();

    String ws = "00000000-0000-0000-0000-000000000001";
    try (Connection c = DriverManager.getConnection(url, "mig", "mig");
        Statement st = c.createStatement()) {
      // FORCE RLS sahibe de uygulandigi icin seed verisi de tenant context'iyle yazilir.
      st.execute("SELECT set_config('app.current_workspace_id', '" + ws + "', false)");
      st.execute("INSERT INTO workspaces (id, name) VALUES ('" + ws + "', 'W')");
      st.execute(
          "INSERT INTO users (id, email, password_hash, full_name) VALUES "
              + "('00000000-0000-0000-0000-0000000000a1', 'a@x.io', 'h', 'A')");
      st.execute(
          "INSERT INTO projects (id, workspace_id, key, name) VALUES "
              + "('00000000-0000-0000-0000-0000000000b1', '"
              + ws
              + "', 'P', 'P')");
      st.execute(
          "INSERT INTO tasks (id, workspace_id, project_id, task_number, title, status) VALUES "
              + "('00000000-0000-0000-0000-0000000000c1', '"
              + ws
              + "', '00000000-0000-0000-0000-0000000000b1', 1, 'T', 'To Do')");
      st.execute(
          "INSERT INTO task_events (id, task_id, actor_id, event_type, created_at) VALUES "
              + "('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000c1', "
              + "'00000000-0000-0000-0000-0000000000a1', 'status_changed', '2025-11-10 10:00:00+00'),"
              + "('00000000-0000-0000-0000-0000000000d2', '00000000-0000-0000-0000-0000000000c1', "
              + "'00000000-0000-0000-0000-0000000000a1', 'status_changed', '2026-02-20 10:00:00+00')");
    }

    flyway(null).migrate();

    try (Connection c = DriverManager.getConnection(url, "mig", "mig");
        Statement st = c.createStatement()) {
      st.execute("SELECT set_config('app.current_workspace_id', '" + ws + "', false)");
      List<String> rows = new ArrayList<>();
      try (ResultSet rs =
          st.executeQuery(
              "SELECT id::text, tableoid::regclass::text FROM task_events ORDER BY created_at")) {
        while (rs.next()) {
          rows.add(rs.getString(1).substring(34) + "@" + rs.getString(2));
        }
      }
      assertEquals(
          List.of("d1@task_events_2025_11", "d2@task_events_2026_02"),
          rows,
          "eski satirlar KAYBOLMAMALI ve kendi aylarinin partition'inda olmali");

      try (ResultSet rs = st.executeQuery("SELECT to_regclass('task_events_old')::text")) {
        rs.next();
        assertNull(rs.getString(1), "eski tablo silinmis olmali");
      }
      try (ResultSet rs =
          st.executeQuery(
              "SELECT relrowsecurity, relforcerowsecurity FROM pg_class "
                  + "WHERE oid = 'task_events'::regclass")) {
        rs.next();
        assertTrue(rs.getBoolean(1), "RLS yeni tabloda acik olmali");
        assertTrue(rs.getBoolean(2), "FORCE RLS yeni tabloda da acik olmali");
      }
    }
  }

  private static Flyway flyway(String target) {
    var config =
        Flyway.configure().dataSource(url, "mig", "mig").locations("classpath:db/migration");
    if (target != null) {
      config.target(target);
    }
    return config.load();
  }
}
