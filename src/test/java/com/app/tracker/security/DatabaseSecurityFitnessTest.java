package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.accesstoken.service.PersonalAccessTokenService;
import com.app.tracker.comment.service.CommentService;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.notification.service.SlackIntegrationService;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.workspace.service.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Guvenlik fitness testi, veritabani katmani (DB-01..08). Katalog sorgulari {@code app_migrator}
 * (tablo sahibi) baglantisindan, "uygulama rolu ne gorur" sorgulari {@code app_runtime}
 * baglantisindan duz JDBC ile calisir. Istisna listeleri bilerek ELLE yazilmistir; migration'dan
 * turetilmez.
 */
@SpringBootTest
class DatabaseSecurityFitnessTest extends AbstractIntegrationTest {

  private static final String PASSWORD_PREFIX = "Pw-";

  /** RLS'siz altyapi tablolari. */
  private static final Set<String> NO_RLS_INFRA =
      Set.of("outbox_events", "processed_events", "task_counters", "usage_events", "error_events");

  /** Kimlik-oncesi katman: kiracidan once sorgulanir, RLS yok. */
  private static final Set<String> PRE_IDENTITY =
      Set.of(
          "users",
          "workspace_users",
          "verification_tokens",
          "workspace_invitations",
          "refresh_tokens",
          "personal_access_tokens");

  /** Tek bilincli istisna: RLS acik, FORCE kapali (SECURITY DEFINER lookup icin). */
  private static final String RLS_WITHOUT_FORCE = "webhook_integrations";

  private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$.{56}$");
  private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

  @Autowired private AuthService authService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private CommentService commentService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private PersonalAccessTokenService patService;
  @Autowired private SlackIntegrationService slackService;
  @Autowired private tools.jackson.databind.ObjectMapper objectMapper;

  // ---------------------------------------------------------------- DB-01

  private record TableInfo(String name, boolean rls, boolean force, boolean hasWorkspaceId) {}

  private Map<String, TableInfo> publicTables() throws SQLException {
    String sql =
        "SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity, "
            + "EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid "
            + "AND a.attname = 'workspace_id' AND NOT a.attisdropped) "
            + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE n.nspname = 'public' AND c.relkind IN ('r','p') AND NOT c.relispartition "
            + "ORDER BY c.relname";
    Map<String, TableInfo> result = new LinkedHashMap<>();
    try (Connection c = openMigratorConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        result.put(
            rs.getString(1),
            new TableInfo(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4)));
      }
    }
    return result;
  }

  private static Set<String> allExemptions() {
    Set<String> all = new TreeSet<>();
    all.addAll(NO_RLS_INFRA);
    all.addAll(PRE_IDENTITY);
    all.add(RLS_WITHOUT_FORCE);
    return all;
  }

  @Test
  @DisplayName(
      "DB-01 | workspace_id sutunlu her tabloda RLS ENABLE + FORCE (istisna listesi disinda)")
  void db01_everyWorkspaceTableHasForcedRls() throws Exception {
    Set<String> exempt = allExemptions();
    List<String> violations = new ArrayList<>();
    int checked = 0;
    for (TableInfo t : publicTables().values()) {
      if (!t.hasWorkspaceId() || exempt.contains(t.name())) {
        continue;
      }
      checked++;
      if (!t.rls() || !t.force()) {
        violations.add(t.name() + " (rls=" + t.rls() + ", force=" + t.force() + ")");
      }
    }
    assertTrue(checked > 5, "Kabul esigi: kapsanan tablo sayisi beklenenden az: " + checked);
    assertTrue(violations.isEmpty(), "RLS/FORCE eksik tablolar: " + violations);
  }

  @Test
  @DisplayName("DB-01 | istisna listesi bayatlamamis: listedeki tablo var ve hala kurala uymuyor")
  void db01_exemptionListIsNotStale() throws Exception {
    Map<String, TableInfo> tables = publicTables();
    List<String> stale = new ArrayList<>();
    for (String name : allExemptions()) {
      TableInfo t = tables.get(name);
      if (t == null) {
        stale.add(name + " (tablo yok)");
        continue;
      }
      if (name.equals(RLS_WITHOUT_FORCE)) {
        if (!t.rls()) {
          stale.add(name + " (RLS kapali; yalniz FORCE kapali olmali)");
        }
        if (t.force()) {
          stale.add(name + " (FORCE artik acik; istisnadan cikar)");
        }
      } else if (t.rls() && t.force()) {
        stale.add(name + " (artik RLS+FORCE; istisnadan cikar)");
      }
    }
    assertTrue(stale.isEmpty(), "Istisna listesi bayat: " + stale);
  }

  // ---------------------------------------------------------------- DB-02

  private record Seed(UUID wsA, UUID wsB) {}

  private Seed seedTwoWorkspaces() {
    String email = "sec-db02-" + UUID.randomUUID() + "@tracker.local";
    UUID actor =
        authService
            .register(
                email,
                PASSWORD_PREFIX + UUID.randomUUID(),
                "Db User",
                PrivacyNotice.CURRENT_VERSION)
            .getId();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    for (UUID ws : List.of(a, b)) {
      tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(ws, "WS " + ws));
      Project p = tenantExecutor.runAs(ws, () -> projectService.createProject("SEC", "Security"));
      Task t =
          tenantExecutor.runAs(
              ws,
              () ->
                  taskService.createTask(
                      p.getId(), "task " + ws, LocalDate.now().plusDays(3), actor));
      tenantExecutor.runAs(ws, () -> commentService.addComment(t.getId(), "comment " + ws, actor));
    }
    return new Seed(a, b);
  }

  private static long countRuntime(String table, String guc, String... extraWhere)
      throws SQLException {
    try (Connection c = openRuntimeConnection()) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        if (guc != null) {
          st.execute("SET LOCAL app.current_workspace_id = '" + guc + "'");
        }
        String where = extraWhere.length == 0 ? "" : " WHERE " + extraWhere[0];
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table + where)) {
          rs.next();
          return rs.getLong(1);
        }
      } finally {
        c.rollback();
      }
    }
  }

  @Test
  @DisplayName("DB-02 | GUC=A iken yalniz A'nin satirlari gorunur (tasks, projects, comments)")
  void db02_guc_showsOnlyOwnRows() throws Exception {
    Seed s = seedTwoWorkspaces();
    for (String table : List.of("tasks", "projects", "comments")) {
      assertTrue(
          countRuntime(table, s.wsA().toString()) > 0, table + ": A kendi satirlarini gormeli");
      assertEquals(
          0,
          countRuntime(table, s.wsA().toString(), "workspace_id = '" + s.wsB() + "'"),
          table + ": A baglaminda B'nin satiri gorunmemeli");
      assertTrue(
          countRuntime(table, s.wsB().toString()) > 0, table + ": B kendi satirlarini gormeli");
      assertEquals(
          0,
          countRuntime(table, s.wsB().toString(), "workspace_id = '" + s.wsA() + "'"),
          table + ": B baglaminda A'nin satiri gorunmemeli");
    }
  }

  @Test
  @DisplayName("DB-02 | GUC set edilmemisse 0 satir, hata yok (fail-closed)")
  void db02_unsetGuc_returnsNothing() throws Exception {
    seedTwoWorkspaces();
    for (String table : List.of("tasks", "projects", "comments")) {
      assertEquals(0, countRuntime(table, null), table + ": GUC yokken satir gorunmemeli");
    }
  }

  @Test
  @DisplayName("DB-02 | GUC bos string iken 0 satir, hata yok (NULLIF deseni)")
  void db02_emptyGuc_returnsNothing() throws Exception {
    seedTwoWorkspaces();
    for (String table : List.of("tasks", "projects", "comments")) {
      assertEquals(0, countRuntime(table, ""), table + ": bos GUC'ta satir gorunmemeli");
    }
  }

  @Test
  @DisplayName("DB-02 | var olmayan workspace UUID'si ile 0 satir")
  void db02_randomUuid_returnsNothing() throws Exception {
    seedTwoWorkspaces();
    String random = UUID.randomUUID().toString();
    for (String table : List.of("tasks", "projects", "comments")) {
      assertEquals(
          0, countRuntime(table, random), table + ": bilinmeyen UUID'de satir gorunmemeli");
    }
  }

  // ---------------------------------------------------------------- DB-03

  private String scalarString(Connection c, String sql, String... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setString(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  /** pgjdbc boolean sutunu getString ile "t"/"f" doner. */
  private static boolean isTrue(String pgBool) {
    return "t".equals(pgBool) || "true".equalsIgnoreCase(pgBool);
  }

  private boolean scalarBool(String sql, String... params) throws SQLException {
    try (Connection c = openMigratorConnection()) {
      return isTrue(scalarString(c, sql, params));
    }
  }

  @Test
  @DisplayName("DB-03 | app_runtime super/bypassrls/createrole/createdb degil")
  void db03_runtimeRoleIsUnprivileged() throws Exception {
    try (Connection c = openMigratorConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT rolsuper, rolbypassrls, rolcreaterole, rolcreatedb "
                    + "FROM pg_roles WHERE rolname = 'app_runtime'")) {
      assertTrue(rs.next(), "app_runtime rolu bulunamadi");
      assertFalse(rs.getBoolean("rolsuper"), "rolsuper");
      assertFalse(rs.getBoolean("rolbypassrls"), "rolbypassrls");
      assertFalse(rs.getBoolean("rolcreaterole"), "rolcreaterole");
      assertFalse(rs.getBoolean("rolcreatedb"), "rolcreatedb");
    }
  }

  @Test
  @DisplayName("DB-03 | app_runtime public semasindaki hicbir nesnenin sahibi degil")
  void db03_runtimeOwnsNothing() throws Exception {
    List<String> owned = new ArrayList<>();
    try (Connection c = openMigratorConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "JOIN pg_roles r ON r.oid = c.relowner "
                    + "WHERE n.nspname = 'public' AND r.rolname = 'app_runtime' "
                    + "AND c.relkind IN ('r','p','v','m','S')")) {
      while (rs.next()) {
        owned.add(rs.getString(1));
      }
    }
    assertTrue(owned.isEmpty(), "app_runtime'in sahibi oldugu nesneler: " + owned);
  }

  @Test
  @DisplayName("DB-03 | kabul esigi: app_migrator en az bir tablonun sahibi")
  void db03_migratorOwnsTables() throws Exception {
    try (Connection c = openMigratorConnection()) {
      long n =
          Long.parseLong(
              scalarString(
                  c,
                  "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' "
                      + "AND tableowner = 'app_migrator'"));
      assertTrue(n > 0, "app_migrator hicbir tablonun sahibi degil: sorgu bos");
    }
  }

  // ---------------------------------------------------------------- DB-04

  @Test
  @DisplayName("DB-04 | app_runtime public semasinda CREATE yetkisine sahip degil")
  void db04_runtimeCannotCreateInPublic() throws Exception {
    assertFalse(
        scalarBool("SELECT has_schema_privilege('app_runtime', 'public', 'CREATE')"),
        "app_runtime public semasinda CREATE yetkisine sahip");
  }

  @Test
  @DisplayName("DB-04 | kabul esigi: app_runtime public semasini kullanabilir (USAGE)")
  void db04_runtimeHasUsageOnPublic() throws Exception {
    assertTrue(scalarBool("SELECT has_schema_privilege('app_runtime', 'public', 'USAGE')"));
  }

  // ---------------------------------------------------------------- DB-05

  @Test
  @DisplayName("DB-05 | task_events uzerinde app_runtime UPDATE yetkisine sahip degil")
  void db05_noUpdateOnTaskEvents() throws Exception {
    assertFalse(
        scalarBool("SELECT has_table_privilege('app_runtime', 'public.task_events', 'UPDATE')"),
        "app_runtime task_events uzerinde UPDATE yapabilir (yalniz-ekleme bozuk)");
  }

  @Test
  @DisplayName("DB-05 | task_events uzerinde app_runtime DELETE yetkisine sahip degil")
  void db05_noDeleteOnTaskEvents() throws Exception {
    assertFalse(
        scalarBool("SELECT has_table_privilege('app_runtime', 'public.task_events', 'DELETE')"),
        "app_runtime task_events uzerinde DELETE yapabilir (yalniz-ekleme bozuk)");
  }

  @Test
  @DisplayName("DB-05 | app_runtime flyway_schema_history uzerinde hicbir yetkiye sahip degil")
  void db05_runtimeHasNoAccessToFlywayHistory() throws Exception {
    for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
      assertFalse(
          scalarBool(
              "SELECT has_table_privilege('app_runtime', 'public.flyway_schema_history', '"
                  + privilege
                  + "')"),
          "app_runtime flyway_schema_history uzerinde " + privilege + " yetkisine sahip");
    }
  }

  @Test
  @DisplayName("DB-05 | kabul esigi: flyway_schema_history var ve migrator okuyabilir")
  void db05_migratorReadsFlywayHistory() throws Exception {
    assertTrue(
        scalarBool(
            "SELECT has_table_privilege(current_user, 'public.flyway_schema_history', 'SELECT')"));
  }

  @Test
  @DisplayName("DB-05 | kabul esigi: app_runtime task_events'e INSERT ve SELECT yapabilir")
  void db05_runtimeCanInsertAndSelectTaskEvents() throws Exception {
    Seed s = seedTwoWorkspaces();
    try (Connection c = openRuntimeConnection()) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.execute("SET LOCAL app.current_workspace_id = '" + s.wsA() + "'");
        UUID taskId;
        UUID actor;
        try (ResultSet rs = st.executeQuery("SELECT id FROM tasks LIMIT 1")) {
          assertTrue(rs.next());
          taskId = rs.getObject(1, UUID.class);
        }
        try (ResultSet rs = st.executeQuery("SELECT actor_id FROM task_events LIMIT 1")) {
          assertTrue(rs.next());
          actor = rs.getObject(1, UUID.class);
        }
        UUID eventId = UUID.randomUUID();
        int inserted =
            st.executeUpdate(
                "INSERT INTO task_events (id, task_id, actor_id, event_type) VALUES ('"
                    + eventId
                    + "', '"
                    + taskId
                    + "', '"
                    + actor
                    + "', 'SEC_PROBE')");
        assertEquals(1, inserted);
        try (ResultSet rs =
            st.executeQuery("SELECT count(*) FROM task_events WHERE id = '" + eventId + "'")) {
          rs.next();
          assertEquals(1, rs.getLong(1));
        }
      } finally {
        c.rollback();
      }
    }
  }

  @Test
  @DisplayName("DB-05 | kanit (bilgi): app_runtime'in UPDATE/DELETE yetkisi olan tablolar")
  void db05_evidence_tablesWithUpdateOrDelete() throws Exception {
    List<String> tables = new ArrayList<>();
    try (Connection c = openMigratorConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT c.relname, has_table_privilege('app_runtime', c.oid, 'UPDATE'), "
                    + "has_table_privilege('app_runtime', c.oid, 'DELETE') "
                    + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = 'public' AND c.relkind IN ('r','p') "
                    + "AND (has_table_privilege('app_runtime', c.oid, 'UPDATE') "
                    + "OR has_table_privilege('app_runtime', c.oid, 'DELETE')) ORDER BY 1")) {
      while (rs.next()) {
        tables.add(
            rs.getString(1)
                + "["
                + (rs.getBoolean(2) ? "U" : "-")
                + (rs.getBoolean(3) ? "D" : "-")
                + "]");
      }
    }
    System.out.println("DB05_EVIDENCE (" + tables.size() + "): " + String.join(", ", tables));
    assertFalse(tables.isEmpty(), "kabul esigi: app_runtime hicbir tabloyu guncelleyemiyor");
  }

  // ---------------------------------------------------------------- DB-06

  private record SecDefiner(long oid, String signature, List<String> config) {}

  private List<SecDefiner> securityDefiners() throws SQLException {
    List<SecDefiner> result = new ArrayList<>();
    try (Connection c = openMigratorConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT p.oid, p.oid::regprocedure::text, p.proconfig "
                    + "FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                    + "WHERE n.nspname = 'public' AND p.prosecdef ORDER BY 2")) {
      while (rs.next()) {
        java.sql.Array arr = rs.getArray(3);
        List<String> cfg = arr == null ? List.of() : List.of((String[]) arr.getArray());
        result.add(new SecDefiner(rs.getLong(1), rs.getString(2), cfg));
      }
    }
    return result;
  }

  private static List<String> searchPathOf(SecDefiner f) {
    for (String entry : f.config()) {
      if (entry.startsWith("search_path=")) {
        List<String> schemas = new ArrayList<>();
        for (String part : entry.substring("search_path=".length()).split(",")) {
          schemas.add(part.trim().replace("\"", ""));
        }
        return schemas;
      }
    }
    return null;
  }

  @Test
  @DisplayName("DB-06 | kabul esigi: en az bir SECURITY DEFINER fonksiyon bulunur")
  void db06_securityDefinerFunctionsExist() throws Exception {
    assertFalse(securityDefiners().isEmpty(), "SECURITY DEFINER fonksiyon bulunamadi: sorgu bos");
  }

  @Test
  @DisplayName("DB-06 | her SECURITY DEFINER fonksiyonda acik search_path ayari var")
  void db06_securityDefinerHasExplicitSearchPath() throws Exception {
    List<String> bad = new ArrayList<>();
    for (SecDefiner f : securityDefiners()) {
      if (searchPathOf(f) == null) {
        bad.add(f.signature());
      }
    }
    assertTrue(bad.isEmpty(), "search_path ayarsiz SECURITY DEFINER fonksiyonlar: " + bad);
  }

  @Test
  @DisplayName(
      "DB-06 | SECURITY DEFINER search_path'indeki hicbir semada app_runtime CREATE yapamaz")
  void db06_searchPathSchemasAreNotWritableByRuntime() throws Exception {
    List<String> bad = new ArrayList<>();
    try (Connection c = openMigratorConnection()) {
      for (SecDefiner f : securityDefiners()) {
        List<String> path = searchPathOf(f);
        if (path == null) {
          continue; // ayrica DB-06 search_path testinde raporlanir
        }
        for (String schema : path) {
          if (schema.equals("pg_temp") || schema.isEmpty()) {
            continue;
          }
          String canCreate =
              scalarString(c, "SELECT has_schema_privilege('app_runtime', ?, 'CREATE')", schema);
          if (isTrue(canCreate)) {
            bad.add(f.signature() + " -> " + schema);
          }
        }
      }
    }
    assertTrue(bad.isEmpty(), "app_runtime'in CREATE yetkisi olan semalar search_path'te: " + bad);
  }

  @Test
  @DisplayName("DB-06 | search_path'te pg_temp acikca ve EN SONDA yer alir")
  void db06_pgTempExplicitAndLast() throws Exception {
    List<String> bad = new ArrayList<>();
    for (SecDefiner f : securityDefiners()) {
      List<String> path = searchPathOf(f);
      if (path == null
          || path.stream().filter("pg_temp"::equals).count() != 1
          || !"pg_temp".equals(path.get(path.size() - 1))) {
        bad.add(f.signature() + " search_path=" + path);
      }
    }
    assertTrue(bad.isEmpty(), "pg_temp acik ve son degil: " + bad);
  }

  @Test
  @DisplayName("DB-06 | SECURITY DEFINER fonksiyonlarda PUBLIC'in EXECUTE yetkisi yok")
  void db06_noPublicExecute() throws Exception {
    List<String> bad = new ArrayList<>();
    try (Connection c = openMigratorConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT count(*) FROM pg_proc p, "
                    + "aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a "
                    + "WHERE p.oid = ? AND a.grantee = 0 AND a.privilege_type = 'EXECUTE'")) {
      for (SecDefiner f : securityDefiners()) {
        ps.setLong(1, f.oid());
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          if (rs.getLong(1) > 0) {
            bad.add(f.signature());
          }
        }
      }
    }
    assertTrue(bad.isEmpty(), "PUBLIC'e EXECUTE acik SECURITY DEFINER fonksiyonlar: " + bad);
  }

  // ---------------------------------------------------------------- DB-07

  @Test
  @DisplayName(
      "DB-07 | app_runtime task_events partition'larindan hicbirine dogrudan SELECT yapamaz")
  void db07_noDirectSelectOnPartitions() throws Exception {
    List<String> partitions = new ArrayList<>();
    List<String> bad = new ArrayList<>();
    try (Connection c = openMigratorConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT i.inhrelid::regclass::text, "
                    + "has_table_privilege('app_runtime', i.inhrelid, 'SELECT') "
                    + "FROM pg_inherits i WHERE i.inhparent = 'public.task_events'::regclass")) {
      while (rs.next()) {
        partitions.add(rs.getString(1));
        if (rs.getBoolean(2)) {
          bad.add(rs.getString(1));
        }
      }
    }
    assertFalse(partitions.isEmpty(), "kabul esigi: task_events partition'i bulunamadi");
    assertTrue(bad.isEmpty(), "app_runtime'in SELECT yetkisi olan partition'lar: " + bad);
  }

  @Test
  @DisplayName("DB-07 | kabul esigi: ana tablo task_events uzerinden (GUC ile) okuma calisir")
  void db07_parentReadWorks() throws Exception {
    Seed s = seedTwoWorkspaces();
    assertTrue(countRuntime("task_events", s.wsA().toString()) > 0, "A kendi olaylarini okuyamadi");
    assertEquals(0, countRuntime("task_events", null), "GUC yokken task_events satiri gorunmemeli");
  }

  // ---------------------------------------------------------------- DB-08

  private static String sha256(String raw) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * public semadaki tum metin/json sutunlarinda {@code needle} geciyor mu; "tablo.sutun" listesi.
   */
  private List<String> columnsContaining(String needle) throws SQLException {
    List<String> hits = new ArrayList<>();
    try (Connection c = openMigratorConnection();
        Statement st = c.createStatement()) {
      List<String[]> columns = new ArrayList<>();
      try (ResultSet rs =
          st.executeQuery(
              "SELECT table_name, column_name FROM information_schema.columns "
                  + "WHERE table_schema = 'public' "
                  + "AND data_type IN ('text','character varying','character','json','jsonb') "
                  + "AND table_name IN (SELECT tablename FROM pg_tables WHERE schemaname = 'public')")) {
        while (rs.next()) {
          columns.add(new String[] {rs.getString(1), rs.getString(2)});
        }
      }
      for (String[] tc : columns) {
        String sql =
            "SELECT count(*) FROM public.\""
                + tc[0]
                + "\" WHERE strpos(\""
                + tc[1]
                + "\"::text, ?) > 0";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
          ps.setString(1, needle);
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            if (rs.getLong(1) > 0) {
              hits.add(tc[0] + "." + tc[1]);
            }
          }
        }
      }
    }
    return hits;
  }

  private record Secrets(
      UUID userId,
      String email,
      String password,
      String refreshRaw,
      String patRaw,
      String slackUrl) {}

  private Secrets createSecrets() throws Exception {
    String email = "sec-db08-" + UUID.randomUUID() + "@tracker.local";
    String password = PASSWORD_PREFIX + UUID.randomUUID();
    UUID userId =
        authService.register(email, password, "Db08 User", PrivacyNotice.CURRENT_VERSION).getId();
    String refresh = authService.login(email, password, "127.0.0.1").refreshToken();
    authService.requestPasswordReset(email);
    String patRaw = patService.create(userId, "db08", null).rawToken();
    UUID ws = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(ws, "DB08 WS"));
    String slackUrl =
        "https://hooks.slack.com/services/T0SEC0001/B0SEC0001/"
            + UUID.randomUUID().toString().replace("-", "");
    tenantExecutor.runAs(ws, () -> slackService.upsert(slackUrl, true));
    return new Secrets(userId, email, password, refresh, patRaw, slackUrl);
  }

  private List<String> stringsOf(String sql, UUID userId) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection c = openMigratorConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          values.add(rs.getString(1));
        }
      }
    }
    return values;
  }

  @Test
  @DisplayName("DB-08 | users parola sutunu bcrypt, duz parola hicbir sutunda yok")
  void db08_passwordStoredAsBcryptOnly() throws Exception {
    Secrets s = createSecrets();
    List<String> hashes = stringsOf("SELECT password_hash FROM users WHERE id = ?", s.userId());
    assertEquals(1, hashes.size());
    assertTrue(
        BCRYPT.matcher(hashes.get(0)).matches(), "bcrypt degil: " + hashes.get(0).substring(0, 4));
    assertEquals(List.of(), columnsContaining(s.password()), "duz parola su sutunlarda bulundu");
  }

  @Test
  @DisplayName(
      "DB-08 | refresh token DB'de yalniz SHA-256 hash (64 hex), duz deger hicbir sutunda yok")
  void db08_refreshTokenHashedOnly() throws Exception {
    Secrets s = createSecrets();
    List<String> hashes =
        stringsOf("SELECT token_hash FROM refresh_tokens WHERE user_id = ?", s.userId());
    assertFalse(hashes.isEmpty(), "kabul esigi: refresh token satiri yok");
    for (String h : hashes) {
      assertTrue(SHA256_HEX.matcher(h).matches(), "refresh token_hash 64 hex degil");
    }
    assertTrue(hashes.contains(sha256(s.refreshRaw())), "hash, duz degerin SHA-256'si degil");
    assertEquals(List.of(), columnsContaining(s.refreshRaw()), "duz refresh token bulundu");
  }

  @Test
  @DisplayName("DB-08 | dogrulama/sifirlama token'lari DB'de yalniz SHA-256 hash (64 hex)")
  void db08_verificationTokensHashedOnly() throws Exception {
    Secrets s = createSecrets();
    List<String> hashes =
        stringsOf("SELECT token_hash FROM verification_tokens WHERE user_id = ?", s.userId());
    assertEquals(2, hashes.size(), "beklenen: EMAIL_VERIFICATION + PASSWORD_RESET");
    for (String h : hashes) {
      assertTrue(SHA256_HEX.matcher(h).matches(), "verification token_hash 64 hex degil");
    }
    // Her amac icin ayri ayri: duz deger Mailpit'ten okunur, tablodaki deger sha256(duz) olmali.
    assertPurposeHashedOnly(s, "EMAIL_VERIFICATION", "dogrula");
    assertPurposeHashedOnly(s, "PASSWORD_RESET", "sifirlama");
  }

  private void assertPurposeHashedOnly(Secrets s, String purpose, String subjectPart)
      throws Exception {
    String raw = rawTokenFromMail(s.email(), subjectPart);
    List<String> stored =
        stringsOf(
            "SELECT token_hash FROM verification_tokens WHERE user_id = ? AND purpose = '"
                + purpose
                + "'",
            s.userId());
    assertEquals(1, stored.size(), purpose + ": tam bir satir beklenir");
    assertEquals(sha256(raw), stored.get(0), purpose + ": token_hash sha256(duz) degil");
    assertFalse(raw.equals(stored.get(0)), purpose + ": duz deger saklanmis");
    List<String> leaks =
        columnsContaining(raw).stream().filter(c -> c.startsWith("verification_tokens.")).toList();
    assertEquals(List.of(), leaks, purpose + ": duz token token tablosunda geciyor");
  }

  /**
   * Mailpit'e gelen e-postanin metninden {@code token=} degerini okur (kuyruk asenkron: bekler).
   */
  private String rawTokenFromMail(String email, String subjectPart) throws Exception {
    java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
    long deadline = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < deadline) {
      String listBody =
          http.send(
                  java.net.http.HttpRequest.newBuilder(
                          java.net.URI.create(mailpitApiUrl("/api/v1/messages?limit=200")))
                      .GET()
                      .build(),
                  java.net.http.HttpResponse.BodyHandlers.ofString())
              .body();
      for (tools.jackson.databind.JsonNode msg : objectMapper.readTree(listBody).path("messages")) {
        boolean toMatches = false;
        for (tools.jackson.databind.JsonNode to : msg.path("To")) {
          toMatches |= email.equalsIgnoreCase(to.path("Address").asString(""));
        }
        if (!toMatches || !msg.path("Subject").asString("").contains(subjectPart)) {
          continue;
        }
        String detail =
            http.send(
                    java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(
                                mailpitApiUrl("/api/v1/message/" + msg.path("ID").asString(""))))
                        .GET()
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString())
                .body();
        String text = objectMapper.readTree(detail).path("Text").asString("");
        java.util.regex.Matcher m = Pattern.compile("token=([^\\s\"<&]+)").matcher(text);
        if (m.find()) {
          return java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        }
      }
      Thread.sleep(250);
    }
    throw new AssertionError("Mailpit'te '" + subjectPart + "' e-postasi bulunamadi: " + email);
  }

  @Test
  @DisplayName("DB-08 | PAT DB'de yalniz SHA-256 hash (64 hex), duz deger hicbir sutunda yok")
  void db08_patHashedOnly() throws Exception {
    Secrets s = createSecrets();
    List<String> hashes =
        stringsOf("SELECT token_hash FROM personal_access_tokens WHERE user_id = ?", s.userId());
    assertEquals(1, hashes.size());
    assertTrue(SHA256_HEX.matcher(hashes.get(0)).matches(), "PAT token_hash 64 hex degil");
    assertEquals(sha256(s.patRaw()), hashes.get(0));
    assertEquals(List.of(), columnsContaining(s.patRaw()), "duz PAT bulundu");
  }

  @Test
  @DisplayName("DB-08 | Slack webhook adresi DB'de duz metin (hooks.slack.com) olarak gecmez")
  void db08_slackUrlNotStoredInPlaintext() throws Exception {
    Secrets s = createSecrets();
    try (Connection c = openMigratorConnection()) {
      long rows = Long.parseLong(scalarString(c, "SELECT count(*) FROM slack_integrations"));
      assertTrue(rows > 0, "kabul esigi: slack_integrations satiri yok");
    }
    assertEquals(List.of(), columnsContaining("hooks.slack.com"), "duz Slack adresi bulundu");
    assertEquals(List.of(), columnsContaining(s.slackUrl()));
  }
}
