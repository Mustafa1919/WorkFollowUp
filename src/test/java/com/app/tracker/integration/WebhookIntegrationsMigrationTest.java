package com.app.tracker.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V13'un guvenlik varsayimi: {@code webhook_integrations} tablosu FORCE OLMADAN RLS'lidir ve {@code
 * resolve_webhook_integration} SECURITY DEFINER fonksiyonu, tablo SAHIBININ RLS'ten muafiyetine
 * dayanarak tenant baglami olmadan satiri bulur. AbstractIntegrationTest'in migrator'u SUPERUSER
 * oldugundan orada sahip muafiyeti zaten her durumda gecerlidir ve bu varsayim hic sinanmaz;
 * prod'da migrator superuser olmamali. Bu test (TaskEventsPartitionMigrationTest ile ayni desen)
 * migration'i SUPERUSER OLMAYAN bir sahiple uygular ve calisma zamani rolu ({@code app_runtime})
 * ile sinar.
 */
class WebhookIntegrationsMigrationTest {

  private static final String WS_A = "00000000-0000-0000-0000-0000000000a1";
  private static final String WS_B = "00000000-0000-0000-0000-0000000000b1";
  private static final String INTEGRATION_A = "00000000-0000-0000-0000-00000000aa01";

  private static PostgreSQLContainer<?> postgres;
  private static String url;

  @BeforeAll
  static void migrateAsNonSuperuserOwner() throws Exception {
    postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    postgres.start();
    try (Connection admin =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("CREATE ROLE mig LOGIN NOSUPERUSER PASSWORD 'mig'");
      st.execute("CREATE ROLE app_runtime LOGIN NOSUPERUSER PASSWORD 'app_runtime'");
      st.execute("CREATE ROLE stranger LOGIN NOSUPERUSER PASSWORD 'stranger'");
      st.execute("CREATE DATABASE migtest OWNER mig");
    }
    url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/migtest");

    try (Connection c = DriverManager.getConnection(url, "mig", "mig");
        Statement st = c.createStatement()) {
      // Prod docker-init'in yaptigi gibi: app_runtime, mig'in olusturdugu tablolarda DML alir.
      st.execute(
          "ALTER DEFAULT PRIVILEGES FOR ROLE mig IN SCHEMA public "
              + "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_runtime");
    }
    Flyway.configure()
        .dataSource(url, "mig", "mig")
        .locations("classpath:db/migration")
        .load()
        .migrate();

    // Sahip (mig) FORCE OLMADIGI icin tenant baglami olmadan tohumlayabilir; bu da varsayimin bir
    // parcasidir (FORCE eklenirse bu INSERT sessizce degil, hata ile patlar).
    try (Connection c = DriverManager.getConnection(url, "mig", "mig");
        Statement st = c.createStatement()) {
      st.execute(
          "INSERT INTO workspaces (id, name) VALUES ('" + WS_A + "', 'A'), ('" + WS_B + "', 'B')");
      st.execute(
          "INSERT INTO webhook_integrations (id, workspace_id, provider, secret_version) VALUES ('"
              + INTEGRATION_A
              + "', '"
              + WS_A
              + "', 'github', 3)");
    }
  }

  @AfterAll
  static void stop() {
    postgres.stop();
  }

  private static Connection runtime() throws SQLException {
    return DriverManager.getConnection(url, "app_runtime", "app_runtime");
  }

  private static long count(Statement st, String sql) throws SQLException {
    try (ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void runtimeRoleSeesNoRowsDirectlyWithoutOrWithForeignTenantContext() throws Exception {
    try (Connection c = runtime();
        Statement st = c.createStatement()) {
      assertEquals(
          0L,
          count(st, "SELECT COUNT(*) FROM webhook_integrations"),
          "tenant baglami yokken app_runtime satir GOREMEMELI (fail-closed)");

      st.execute("SELECT set_config('app.current_workspace_id', '" + WS_B + "', false)");
      assertEquals(
          0L,
          count(st, "SELECT COUNT(*) FROM webhook_integrations"),
          "baska tenant'in baglaminda A'nin satiri GORUNMEMELI");

      st.execute("SELECT set_config('app.current_workspace_id', '" + WS_A + "', false)");
      assertEquals(1L, count(st, "SELECT COUNT(*) FROM webhook_integrations"));
    }
  }

  @Test
  void resolveFunctionFindsTheRowWithoutTenantContextForNonSuperuserOwner() throws Exception {
    try (Connection c = runtime();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT workspace_id::text, provider, secret_version "
                    + "FROM resolve_webhook_integration('"
                    + INTEGRATION_A
                    + "')")) {
      assertTrue(rs.next(), "SECURITY DEFINER fonksiyonu sahibin muafiyetiyle satiri bulmali");
      assertEquals(WS_A, rs.getString(1));
      assertEquals("github", rs.getString(2));
      assertEquals(3, rs.getInt(3));
      assertFalse(rs.next());
    }
  }

  @Test
  void resolveFunctionReturnsNothingForUnknownIdAndExposesOnlyThreeColumns() throws Exception {
    try (Connection c = runtime();
        Statement st = c.createStatement()) {
      assertEquals(
          0L,
          count(
              st,
              "SELECT COUNT(*) FROM resolve_webhook_integration('00000000-0000-0000-0000-00000000ffff')"));
      try (ResultSet rs =
          st.executeQuery("SELECT * FROM resolve_webhook_integration('" + INTEGRATION_A + "')")) {
        assertEquals(
            3,
            rs.getMetaData().getColumnCount(),
            "RLS'i delme yuzeyi workspace/provider/secret_version ile SINIRLI kalmali");
      }
    }
  }

  @Test
  void resolveFunctionIsExecutableOnlyByTheRuntimeRole() throws Exception {
    try (Connection c = DriverManager.getConnection(url, "stranger", "stranger");
        Statement st = c.createStatement()) {
      SQLException e =
          assertThrows(
              SQLException.class,
              () ->
                  st.executeQuery(
                      "SELECT * FROM resolve_webhook_integration('" + INTEGRATION_A + "')"));
      assertEquals("42501", e.getSQLState(), "PUBLIC'ten EXECUTE geri alinmis olmali");
    }
  }

  @Test
  void rowLevelSecurityIsEnabledButNotForcedAndFunctionIsSecurityDefiner() throws Exception {
    try (Connection c = runtime();
        Statement st = c.createStatement()) {
      try (ResultSet rs =
          st.executeQuery(
              "SELECT relrowsecurity, relforcerowsecurity FROM pg_class "
                  + "WHERE oid = 'webhook_integrations'::regclass")) {
        rs.next();
        assertTrue(rs.getBoolean(1), "RLS acik olmali");
        assertFalse(
            rs.getBoolean(2),
            "FORCE bilerek YOK: fonksiyon sahibi RLS'e takilirsa lookup hic satir gormez");
      }
      try (ResultSet rs =
          st.executeQuery(
              "SELECT prosecdef, proconfig::text FROM pg_proc "
                  + "WHERE proname = 'resolve_webhook_integration'")) {
        rs.next();
        assertTrue(rs.getBoolean(1), "SECURITY DEFINER olmali");
        String config = rs.getString(2); // sabitlenmemisse proconfig NULL doner
        assertTrue(
            config != null && config.contains("search_path=public, pg_temp"),
            "search_path sabitlenmis olmali (SECURITY DEFINER'da yol enjeksiyonunu onler)");
      }
    }
  }

  @Test
  void systemActorIsSeededAndCannotLogIn() throws Exception {
    try (Connection c = runtime();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT email, password_hash, email_verified FROM users "
                    + "WHERE id = '00000000-0000-0000-0000-00000000a001'")) {
      assertTrue(rs.next(), "sistem kullanicisi V13 ile tohumlanmis olmali");
      assertTrue(rs.getString(1).endsWith(".invalid"), "teslim edilemez TLD");
      assertFalse(
          rs.getString(2).startsWith("$2"), "gecerli bir BCrypt hash'i OLMAMALI (giris yapilamaz)");
    }
  }
}
