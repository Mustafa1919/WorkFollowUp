package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.integration.service.GithubEventProcessor;
import com.app.tracker.integration.service.GithubSignatureVerifier;
import com.app.tracker.security.support.JwtForge;
import com.app.tracker.security.support.OpenApiCatalog;
import com.app.tracker.security.support.OpenApiCatalog.Operation;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Actor;
import com.app.tracker.security.support.SecurityFixture.Tenant;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.workspace.model.WorkspaceRole;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Guvenlik katalogu §4 (dikey yetki): AUTHZ-02, 03, 04, 06, 10, 11, 12, 13. Gercek servlet
 * (RANDOM_PORT) uzerinden, ADR-0019/0020/0021'e gore. Beklenen izinler testte ELLE yazilidir;
 * {@code RolePermissions} cagrilmaz. Ortak fikstur sinif basina bir kez kurulur; her test kendi
 * gorevlerini/sprint'lerini uretir, fikstur kayitlarini (taskId/otherTaskId) DEGISTIRMEZ.
 *
 * <p>AUTHZ-02 kapsam kumesi yontemi: {@code HttpAuthorizationIntegrationTest} kaynak dosyasi
 * metinsel okunur, {@code Endpoint.of(...)} / {@code new Endpoint(...)} cagrilarindaki (HttpMethod,
 * path dizgesi) ciftleri regex ile cikarilir; sorgu dizgesi atilir, OpenAPI {@code {param}}
 * segmenti kapsanan path'teki herhangi bir segmentle (ornek {@code {id}}) eslesir.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissionPathsTest extends AbstractIntegrationTest {

  private static final String AUTHZ_MATRIX_SOURCE =
      "src/test/java/com/app/tracker/core/security/HttpAuthorizationIntegrationTest.java";
  private static final String TODO = "To Do";
  private static final String IN_PROGRESS = "In Progress";
  private static final String REVIEW = "Review";
  private static final String DONE = "Done";
  private static final String WEBHOOK_DLT_TOPIC = "webhooks.incoming-dlt";

  /** AUTHZ-02: bilincli dislananlar ve gerekceleri. */
  private static final Map<String, String> AUTHZ02_EXCLUDED =
      Map.of(
          "POST /api/v1/webhooks/github/{integrationId}",
          "GitHub webhook alma ucu: JWT tasimaz (permitAll), kimlik HMAC imzasiyla dogrulanir",
          "GET /api/v1/invitations/{token}",
          "Davet onizleme: permitAll (SecurityConfig), {token} gizli davet token'i");

  @LocalServerPort private int port;
  @Autowired private ApplicationContext ctx;
  @Autowired private AuthService authService;
  @Autowired private ObjectMapper mapper;

  private SecurityFixture fx;
  private Tenant a;
  private Tenant b;
  private UUID ws;
  private Actor admin;
  private Actor manager;
  private Actor dev;
  private Actor viewer;

  @BeforeAll
  void setUp() {
    fx = SecurityFixture.create(ctx, port);
    a = fx.a();
    b = fx.b();
    ws = a.workspaceId();
    admin = a.admin();
    manager = a.manager();
    dev = a.developer();
    viewer = a.viewer();
  }

  // =========================================================================================
  // AUTHZ-02 HttpAuthorizationIntegrationTest kapsami
  // =========================================================================================

  @Test
  @DisplayName(
      "AUTHZ-02 | OpenAPI'deki her /api/v1 ucu HttpAuthorizationIntegrationTest matrisinde var")
  void authz02_everyOpenApiOperationIsInAuthorizationMatrix() throws IOException {
    List<String[]> covered = coveredByMatrix();
    List<Operation> ops = apiOperations();

    List<String> missing = new ArrayList<>();
    List<String> excludedSeen = new ArrayList<>();
    int matched = 0;
    for (Operation op : ops) {
      String id = op.method() + " " + op.path();
      if (op.path().startsWith("/api/v1/auth/")) {
        excludedSeen.add(id + " (kimlik oncesi, permitAll)");
        continue;
      }
      if (AUTHZ02_EXCLUDED.containsKey(id)) {
        excludedSeen.add(id + " (" + AUTHZ02_EXCLUDED.get(id) + ")");
        continue;
      }
      if (isCovered(op.method(), op.path(), covered)) {
        matched++;
      } else {
        missing.add(id);
      }
    }
    System.out.println(
        "[D5-AUTHZ-02] OpenAPI /api/v1 islem="
            + ops.size()
            + " kapsanan="
            + matched
            + " eksik="
            + missing.size()
            + " dislanan="
            + excludedSeen.size());
    missing.forEach(m -> System.out.println("[D5-AUTHZ-02] EKSIK " + m));
    assertTrue(
        missing.isEmpty(),
        "HttpAuthorizationIntegrationTest matrisinde olmayan uclar ("
            + missing.size()
            + "): "
            + missing);
  }

  @Test
  @DisplayName(
      "AUTHZ-02 K | eslestirici bos-bos gecmiyor: kapsanan uclar var, uydurma uc kapsanmiyor")
  void authz02_k_matcherIsNotVacuous() throws IOException {
    List<String[]> covered = coveredByMatrix();
    assertTrue(covered.size() >= 80, "matris kaynagindan cikarilan uc sayisi: " + covered.size());
    assertTrue(isCovered("POST", "/api/v1/tasks/{parentTaskId}/subtasks", covered));
    assertTrue(isCovered("POST", "/api/v1/tasks/bulk", covered));
    assertTrue(isCovered("PUT", "/api/v1/standups/{meetingId}/{date}/note", covered));
    assertFalse(isCovered("GET", "/api/v1/hic-yok/{id}", covered));
    assertFalse(isCovered("DELETE", "/api/v1/tasks/bulk", covered));

    long matched =
        apiOperations().stream().filter(op -> isCovered(op.method(), op.path(), covered)).count();
    assertTrue(matched >= 80, "OpenAPI ile eslesen uc sayisi: " + matched);
  }

  private List<Operation> apiOperations() {
    HttpResponse<String> docs = fx.http().get("/v3/api-docs", (String) null, null, null);
    assertEquals(200, docs.statusCode(), "OpenAPI dokumani okunamadi");
    return OpenApiCatalog.parse(mapper.readTree(docs.body())).operations().stream()
        .filter(op -> op.path().startsWith("/api/v1/"))
        .toList();
  }

  private static List<String[]> coveredByMatrix() throws IOException {
    String src = Files.readString(Path.of(AUTHZ_MATRIX_SOURCE), StandardCharsets.UTF_8);
    Matcher m =
        Pattern.compile("(?:Endpoint\\.of|new Endpoint)\\(\\s*HttpMethod\\.(\\w+),\\s*\"([^\"]+)\"")
            .matcher(src);
    List<String[]> out = new ArrayList<>();
    while (m.find()) {
      String path = m.group(2);
      int q = path.indexOf('?');
      out.add(new String[] {m.group(1), q >= 0 ? path.substring(0, q) : path});
    }
    // Ayni siniftaki standalone testler (MockMvc: post("/api/v1/..."), get("/api/v1/...")) da
    // kapsama sayilir; ornek: workspace olusturma, kafka replay.
    Matcher standalone =
        Pattern.compile("\\b(get|post|put|patch|delete)\\(\\s*\"(/api/v1/[^\"]+)\"").matcher(src);
    while (standalone.find()) {
      String path = standalone.group(2);
      int q = path.indexOf('?');
      out.add(
          new String[] {standalone.group(1).toUpperCase(), q >= 0 ? path.substring(0, q) : path});
    }
    return out;
  }

  private static boolean isCovered(String method, String opPath, List<String[]> covered) {
    String[] o = opPath.split("/");
    for (String[] c : covered) {
      if (!c[0].equals(method)) {
        continue;
      }
      String[] cs = c[1].split("/");
      if (cs.length != o.length) {
        continue;
      }
      boolean same = true;
      for (int i = 0; i < o.length && same; i++) {
        same = o[i].startsWith("{") || o[i].equals(cs[i]);
      }
      if (same) {
        return true;
      }
    }
    return false;
  }

  // =========================================================================================
  // AUTHZ-03 mass assignment
  // =========================================================================================

  @Test
  @DisplayName("AUTHZ-03 | gorev olusturma: fazladan alanlar yok sayilir ya da istek reddedilir")
  void authz03_taskCreateIgnoresInjectedFields() {
    UUID injectedId = UUID.randomUUID();
    String title = "ma-create-" + uid();
    HttpResponse<String> r =
        send(
            "POST",
            "/api/v1/projects/" + a.projectId() + "/tasks",
            manager,
            injected(obj().put("title", title), injectedId));
    int sc = r.statusCode();
    assertTrue(sc == 201 || sc == 400 || sc == 422, "beklenmeyen durum " + sc + " " + r.body());
    if (sc == 201) {
      assertCreatedTaskIsClean(json(r), injectedId, manager);
    } else {
      assertEquals(0, count("SELECT count(*) FROM tasks WHERE title = ?", title));
    }
    // K: fazladan alan olmadan ayni istek beklenen kaydi olusturur.
    String plainTitle = "ma-create-plain-" + uid();
    HttpResponse<String> ok =
        send(
            "POST",
            "/api/v1/projects/" + a.projectId() + "/tasks",
            manager,
            obj().put("title", plainTitle));
    assertEquals(201, ok.statusCode(), ok.body());
    assertEquals(plainTitle, json(ok).path("title").asString(""));
  }

  @Test
  @DisplayName("AUTHZ-03 | alt gorev olusturma: fazladan alanlar yok sayilir ya da reddedilir")
  void authz03_subtaskCreateIgnoresInjectedFields() {
    UUID parent = newTask("ma-parent");
    UUID injectedId = UUID.randomUUID();
    String title = "ma-sub-" + uid();
    HttpResponse<String> r =
        send(
            "POST",
            "/api/v1/tasks/" + parent + "/subtasks",
            manager,
            injected(obj().put("title", title), injectedId));
    int sc = r.statusCode();
    assertTrue(sc == 201 || sc == 400 || sc == 422, "beklenmeyen durum " + sc + " " + r.body());
    if (sc == 201) {
      JsonNode t = json(r);
      assertEquals(parent.toString(), t.path("parentTaskId").asString(""));
      assertCreatedTaskIsClean(t, injectedId, manager);
    } else {
      assertEquals(0, count("SELECT count(*) FROM tasks WHERE title = ?", title));
    }
    HttpResponse<String> ok =
        send(
            "POST",
            "/api/v1/tasks/" + parent + "/subtasks",
            manager,
            obj().put("title", "ma-sub-plain-" + uid()));
    assertEquals(201, ok.statusCode(), ok.body());
    assertEquals(parent.toString(), json(ok).path("parentTaskId").asString(""));
  }

  private void assertCreatedTaskIsClean(JsonNode t, UUID injectedId, Actor creator) {
    UUID id = UUID.fromString(t.path("id").asString(""));
    assertNotEquals(injectedId, id, "istemcinin verdigi id kullanildi");
    assertNotEquals(
        99999, t.path("taskNumber").asInt(), "istemcinin verdigi taskNumber kullanildi");
    assertTrue(t.path("approvedAt").isNull(), "approvedAt istemciden alindi: " + t);
    assertEquals(0, count("SELECT count(*) FROM tasks WHERE id = ?", injectedId));
    assertEquals(
        ws + "|" + creator.userId() + "|-|-",
        q(
            "SELECT workspace_id || '|' || coalesce(created_by::text,'-') || '|' ||"
                + " coalesce(deleted_at::text,'-') || '|' || coalesce(approved_at::text,'-')"
                + " FROM tasks WHERE id = ?",
            id),
        "workspace/olusturan/silinme/onay alani istemciden etkilendi");
    assertTrue(
        listedTaskIds().contains(id), "gorev A'nin listesinde gorunmuyor (deletedAt etkisi?)");
  }

  @Test
  @DisplayName("AUTHZ-03 | durum guncelleme (PATCH /tasks/{id}): fazladan alanlar etkisiz")
  void authz03_statusUpdateIgnoresInjectedFields() {
    massUpdate(
        "PATCH",
        "",
        () -> obj().put("status", IN_PROGRESS),
        t -> IN_PROGRESS.equals(taskOf(t).path("status").asString("")));
  }

  @Test
  @DisplayName("AUTHZ-03 | aciklama guncelleme: fazladan alanlar etkisiz")
  void authz03_descriptionUpdateIgnoresInjectedFields() {
    massUpdate(
        "PUT",
        "/description",
        () -> obj().put("description", "yeni aciklama"),
        t -> "yeni aciklama".equals(q("SELECT description FROM tasks WHERE id = ?", t)));
  }

  @Test
  @DisplayName("AUTHZ-03 | son tarih guncelleme: fazladan alanlar etkisiz")
  void authz03_dueDateUpdateIgnoresInjectedFields() {
    massUpdate(
        "PUT",
        "/due-date",
        () -> obj().put("dueDate", "2099-09-30"),
        t -> "2099-09-30".equals(taskOf(t).path("dueDate").asString("")));
  }

  @Test
  @DisplayName("AUTHZ-03 | story point guncelleme: fazladan alanlar etkisiz")
  void authz03_storyPointUpdateIgnoresInjectedFields() {
    massUpdate(
        "PUT",
        "/story-point",
        () -> obj().put("storyPoint", 3),
        t -> taskOf(t).path("storyPoint").asInt(-1) == 3);
  }

  @Test
  @DisplayName("AUTHZ-03 | sprint atama: fazladan alanlar etkisiz")
  void authz03_sprintAssignIgnoresInjectedFields() {
    UUID sprint = newSprint();
    massUpdate(
        "PUT",
        "/sprint",
        () -> obj().put("sprintId", sprint.toString()),
        t -> sprint.toString().equals(taskOf(t).path("sprintId").asString("")));
  }

  @Test
  @DisplayName("AUTHZ-03 | atama guncelleme: fazladan alanlar etkisiz")
  void authz03_assigneeUpdateIgnoresInjectedFields() {
    massUpdate(
        "PUT",
        "/assignee",
        () -> obj().put("assigneeId", dev.userId().toString()),
        t -> dev.userId().toString().equals(taskOf(t).path("assigneeId").asString("")));
  }

  /**
   * Fazladan alanli istek: 2xx ise degismez alanlar ayni, beklenen alan guncel; 400/422 ise HIC bir
   * sey degismez. K: ayni istek fazladan alansiz baska gorevde beklenen alani gunceller.
   */
  private void massUpdate(
      String method, String suffix, Supplier<ObjectNode> body, Predicate<UUID> applied) {
    UUID injectedId = UUID.randomUUID();
    UUID t1 = newTask("ma-upd");
    String fpBefore = taskFingerprint(t1);
    JsonNode viewBefore = taskOf(t1);

    HttpResponse<String> r =
        send(method, "/api/v1/tasks/" + t1 + suffix, manager, injected(body.get(), injectedId));
    int sc = r.statusCode();
    assertTrue(
        sc / 100 == 2 || sc == 400 || sc == 422, "beklenmeyen durum " + sc + " govde=" + r.body());
    assertEquals(
        fpBefore, taskFingerprint(t1), "degismez alanlar (workspace/id/numara/onay/silme)");
    assertEquals(0, count("SELECT count(*) FROM tasks WHERE id = ?", injectedId));
    JsonNode viewAfter = taskOf(t1);
    assertEquals(viewBefore.path("taskNumber"), viewAfter.path("taskNumber"));
    assertEquals(viewBefore.path("projectId"), viewAfter.path("projectId"));
    assertTrue(viewAfter.path("approvedAt").isNull(), "approvedAt istemciden alindi");
    if (sc / 100 == 2) {
      assertTrue(applied.test(t1), "2xx dondu ama beklenen alan guncellenmedi");
    } else {
      assertEquals(viewBefore, viewAfter, "reddedilen istek gorevi degistirdi");
    }

    UUID t2 = newTask("ma-upd-k");
    HttpResponse<String> ok = send(method, "/api/v1/tasks/" + t2 + suffix, manager, body.get());
    assertEquals(200, ok.statusCode(), "K: fazladan alansiz istek " + ok.body());
    assertTrue(applied.test(t2), "K: beklenen alan guncellenmedi");
  }

  @Test
  @DisplayName("AUTHZ-03 | proje olusturma: workspaceId/id/ownerId istemciden alinmaz")
  void authz03_projectCreateIgnoresInjectedFields() {
    UUID injectedId = UUID.randomUUID();
    String key = "M" + uid();
    HttpResponse<String> r =
        send(
            "POST",
            "/api/v1/projects",
            manager,
            injected(obj().put("key", key).put("name", "ma proje"), injectedId));
    int sc = r.statusCode();
    assertTrue(sc == 201 || sc == 400 || sc == 422, "beklenmeyen durum " + sc + " " + r.body());
    if (sc == 201) {
      JsonNode p = json(r);
      assertNotEquals(injectedId.toString(), p.path("id").asString(""));
      assertEquals(ws.toString(), p.path("workspaceId").asString(""));
      assertEquals(
          ws.toString(),
          q(
              "SELECT workspace_id::text FROM projects WHERE id = ?",
              UUID.fromString(p.path("id").asString(""))));
      assertEquals(0, count("SELECT count(*) FROM projects WHERE id = ?", injectedId));
      assertEquals(
          0,
          count(
              "SELECT count(*) FROM projects WHERE workspace_id = ? AND key = ?",
              b.workspaceId(),
              key),
          "proje baska kiracida olustu");
    } else {
      assertEquals(0, count("SELECT count(*) FROM projects WHERE key = ?", key));
    }
    HttpResponse<String> ok =
        send(
            "POST",
            "/api/v1/projects",
            manager,
            obj().put("key", "M" + uid()).put("name", "ma proje k"));
    assertEquals(201, ok.statusCode(), ok.body());
  }

  @Test
  @DisplayName("AUTHZ-03 | workspace olusturma: id/planType/baska kiraci istemciden alinmaz")
  void authz03_workspaceCreateIgnoresInjectedFields() {
    Actor creator = fx.addMember(a, WorkspaceRole.VIEWER, "ma-ws-creator");
    UUID injectedId = UUID.randomUUID();
    long bMembers =
        count("SELECT count(*) FROM workspace_users WHERE workspace_id = ?", b.workspaceId());
    String bName = q("SELECT name FROM workspaces WHERE id = ?", b.workspaceId());
    ObjectNode body = injected(obj().put("name", "ma-ws-" + uid()), injectedId);
    body.put("planType", "enterprise");
    HttpResponse<String> r = fx.http().post("/api/v1/workspaces", creator, null, body.toString());
    int sc = r.statusCode();
    assertTrue(sc == 201 || sc == 400 || sc == 422, "beklenmeyen durum " + sc + " " + r.body());
    if (sc == 201) {
      UUID newId = UUID.fromString(json(r).path("id").asString(""));
      assertNotEquals(injectedId, newId);
      assertNotEquals(b.workspaceId(), newId);
      assertEquals(0, count("SELECT count(*) FROM workspaces WHERE id = ?", injectedId));
      assertEquals("free", q("SELECT plan_type FROM workspaces WHERE id = ?", newId));
      assertEquals(1, count("SELECT count(*) FROM workspace_users WHERE workspace_id = ?", newId));
      assertEquals(
          WorkspaceRole.ADMIN,
          q(
              "SELECT role FROM workspace_users WHERE workspace_id = ? AND user_id = ?",
              newId,
              creator.userId()));
    }
    assertEquals(
        bMembers,
        count("SELECT count(*) FROM workspace_users WHERE workspace_id = ?", b.workspaceId()));
    assertEquals(bName, q("SELECT name FROM workspaces WHERE id = ?", b.workspaceId()));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM workspace_users WHERE workspace_id = ? AND user_id = ?",
            b.workspaceId(),
            creator.userId()),
        "olusturan baska kiracida uye oldu");
    HttpResponse<String> ok =
        fx.http()
            .post(
                "/api/v1/workspaces",
                creator,
                null,
                obj().put("name", "ma-ws-k-" + uid()).toString());
    assertEquals(201, ok.statusCode(), ok.body());
  }

  @Test
  @DisplayName("AUTHZ-03 | uye ekleme/rol degistirme: roles/workspaceId/id istemciden alinmaz")
  void authz03_membershipIgnoresInjectedFields() {
    UUID injectedId = UUID.randomUUID();
    // Ekleme: rol alani meşru (DEVELOPER); roles/workspaceId gibi fazlalar yok sayilmali.
    Actor candidate = registerFresh("ma-member");
    HttpResponse<String> add =
        send(
            "POST",
            "/api/v1/workspaces/members",
            admin,
            injected(
                obj().put("email", candidate.email()).put("role", WorkspaceRole.DEVELOPER),
                injectedId));
    int sc = add.statusCode();
    assertTrue(sc == 201 || sc == 400 || sc == 422, "beklenmeyen durum " + sc + " " + add.body());
    if (sc == 201) {
      assertEquals(
          WorkspaceRole.DEVELOPER,
          q(
              "SELECT role FROM workspace_users WHERE workspace_id = ? AND user_id = ?",
              ws,
              candidate.userId()));
    }
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM workspace_users WHERE workspace_id = ? AND user_id = ?",
            b.workspaceId(),
            candidate.userId()),
        "aday baska kiracida uye oldu");

    // K: fazladan alansiz uye ekleme, yeni aday, dogru rol ve uyelik.
    Actor candidate2 = registerFresh("ma-member-k");
    HttpResponse<String> addOk =
        send(
            "POST",
            "/api/v1/workspaces/members",
            admin,
            obj().put("email", candidate2.email()).put("role", WorkspaceRole.DEVELOPER));
    assertEquals(201, addOk.statusCode(), addOk.body());
    assertEquals(candidate2.userId().toString(), json(addOk).path("userId").asString(""));
    assertEquals(WorkspaceRole.DEVELOPER, json(addOk).path("role").asString(""));
    assertEquals(
        WorkspaceRole.DEVELOPER,
        q(
            "SELECT role FROM workspace_users WHERE workspace_id = ? AND user_id = ?",
            ws,
            candidate2.userId()));

    // Rol degistirme.
    Actor target = fx.addMember(a, WorkspaceRole.DEVELOPER, "ma-role");
    HttpResponse<String> patch =
        send(
            "PATCH",
            "/api/v1/workspaces/members/" + target.userId(),
            admin,
            injected(obj().put("role", WorkspaceRole.VIEWER), injectedId));
    int sc2 = patch.statusCode();
    assertTrue(sc2 == 200 || sc2 == 400 || sc2 == 422, "beklenmeyen " + sc2 + " " + patch.body());
    assertEquals(
        sc2 == 200 ? WorkspaceRole.VIEWER : WorkspaceRole.DEVELOPER,
        q(
            "SELECT role FROM workspace_users WHERE workspace_id = ? AND user_id = ?",
            ws,
            target.userId()));
    assertEquals(
        1, count("SELECT count(*) FROM workspace_users WHERE user_id = ?", target.userId()));
    // K: fazladan alansiz rol degisimi.
    Actor target2 = fx.addMember(a, WorkspaceRole.DEVELOPER, "ma-role-k");
    HttpResponse<String> ok =
        send(
            "PATCH",
            "/api/v1/workspaces/members/" + target2.userId(),
            admin,
            obj().put("role", WorkspaceRole.VIEWER));
    assertEquals(200, ok.statusCode(), ok.body());
    assertEquals(
        WorkspaceRole.VIEWER,
        q(
            "SELECT role FROM workspace_users WHERE workspace_id = ? AND user_id = ?",
            ws,
            target2.userId()));
  }

  @Test
  @DisplayName("AUTHZ-03 | kullanici kaydi: roles/systemAdmin/emailVerified/id istemciden alinmaz")
  void authz03_registerIgnoresInjectedFields() {
    // Kullanici profili guncelleme ucu yok (yalniz GET /me); kullanici olusturan tek govde
    // kayittir.
    UUID injectedId = UUID.randomUUID();
    String email = "sec-ma-reg-" + UUID.randomUUID() + "@tracker.local";
    ObjectNode body =
        injected(
            obj()
                .put("email", email)
                .put("password", SecurityFixture.PASSWORD)
                .put("fullName", "Ma Reg")
                .put("privacyNoticeAccepted", true)
                .put("privacyNoticeVersion", PrivacyNotice.CURRENT_VERSION),
            injectedId);
    body.put("systemAdmin", true).put("system_admin", true).put("emailVerified", true);
    HttpResponse<String> r =
        fx.http().post("/api/v1/auth/register", (String) null, null, body.toString());
    int sc = r.statusCode();
    assertTrue(sc / 100 == 2 || sc == 400 || sc == 422, "beklenmeyen durum " + sc + " " + r.body());
    if (sc / 100 == 2) {
      assertEquals(
          "f", q("SELECT system_admin::text FROM users WHERE email = ?", email).substring(0, 1));
      assertEquals(
          "f", q("SELECT email_verified::text FROM users WHERE email = ?", email).substring(0, 1));
      assertEquals(0, count("SELECT count(*) FROM users WHERE id = ?", injectedId));
      assertEquals(
          0,
          count(
              "SELECT count(*) FROM workspace_users wu JOIN users u ON u.id = wu.user_id"
                  + " WHERE u.email = ?",
              email));
      String jwt = authService.login(email, SecurityFixture.PASSWORD, "127.0.0.1").accessToken();
      assertFalse(JwtForge.decodePayload(jwt).contains("SYSTEM_ADMIN"), "JWT SYSTEM_ADMIN tasiyor");
    } else {
      assertEquals(0, count("SELECT count(*) FROM users WHERE email = ?", email));
    }
    // K: fazladan alansiz kayit.
    String okEmail = "sec-ma-reg-k-" + UUID.randomUUID() + "@tracker.local";
    HttpResponse<String> ok =
        fx.http()
            .post(
                "/api/v1/auth/register",
                (String) null,
                null,
                obj()
                    .put("email", okEmail)
                    .put("password", SecurityFixture.PASSWORD)
                    .put("fullName", "Ma Reg K")
                    .put("privacyNoticeAccepted", true)
                    .put("privacyNoticeVersion", PrivacyNotice.CURRENT_VERSION)
                    .toString());
    assertTrue(ok.statusCode() / 100 == 2, "K: " + ok.statusCode() + " " + ok.body());
    assertEquals(1, count("SELECT count(*) FROM users WHERE email = ?", okEmail));
  }

  @Test
  @DisplayName(
      "AUTHZ-03 | yorum olusturma/duzenleme: authorId/taskId/id/deletedAt istemciden alinmaz")
  void authz03_commentIgnoresInjectedFields() {
    UUID task = newTask("ma-comment");
    UUID otherTask = newTask("ma-comment-other");
    UUID injectedId = UUID.randomUUID();
    ObjectNode create =
        injected(obj().put("body", "ilk yorum"), injectedId)
            .put("authorId", manager.userId().toString())
            .put("taskId", otherTask.toString())
            .put("deleted", true);
    HttpResponse<String> r = send("POST", "/api/v1/tasks/" + task + "/comments", dev, create);
    int sc = r.statusCode();
    assertTrue(sc == 201 || sc == 400 || sc == 422, "beklenmeyen durum " + sc + " " + r.body());
    UUID commentId;
    if (sc == 201) {
      JsonNode c = json(r);
      commentId = UUID.fromString(c.path("id").asString(""));
      assertNotEquals(injectedId, commentId);
      assertEquals(dev.userId().toString(), c.path("authorId").asString(""));
      assertEquals(task.toString(), c.path("taskId").asString(""));
      assertFalse(c.path("deleted").asBoolean(true), "yorum silinmis olarak olustu");
    } else {
      assertEquals(0, count("SELECT count(*) FROM comments WHERE task_id = ?", task));
      commentId = createComment(dev, task, "ilk yorum");
    }
    // Duzenleme.
    ObjectNode edit =
        injected(obj().put("body", "duzenlendi"), injectedId)
            .put("authorId", manager.userId().toString())
            .put("taskId", otherTask.toString())
            .put("deleted", true);
    HttpResponse<String> er = send("PATCH", "/api/v1/comments/" + commentId, dev, edit);
    int esc = er.statusCode();
    assertTrue(
        esc == 200 || esc == 400 || esc == 422, "beklenmeyen durum " + esc + " " + er.body());
    JsonNode after = commentOf(task, commentId);
    assertEquals(dev.userId().toString(), after.path("authorId").asString(""));
    assertEquals(task.toString(), after.path("taskId").asString(""));
    assertFalse(after.path("deleted").asBoolean(true));
    if (esc == 200) {
      assertEquals("duzenlendi", after.path("body").asString(""));
    }
    // K: fazladan alansiz duzenleme.
    HttpResponse<String> ok =
        send("PATCH", "/api/v1/comments/" + commentId, dev, obj().put("body", "k duzenleme"));
    assertEquals(200, ok.statusCode(), ok.body());
    assertEquals("k duzenleme", commentOf(task, commentId).path("body").asString(""));
  }

  @Test
  @DisplayName("AUTHZ-03 | kayitli gorunum ve retro maddesi: userId/authorId istemciden alinmaz")
  void authz03_savedViewAndRetroItemIgnoreInjectedFields() {
    UUID injectedId = UUID.randomUUID();
    ObjectNode view =
        injected(obj().put("name", "ma-view-" + uid()).put("query", "{}"), injectedId)
            .put("userId", dev.userId().toString());
    HttpResponse<String> vr =
        send("POST", "/api/v1/projects/" + a.projectId() + "/saved-views", manager, view);
    assertTrue(
        vr.statusCode() == 201 || vr.statusCode() == 400 || vr.statusCode() == 422,
        vr.statusCode() + " " + vr.body());
    if (vr.statusCode() == 201) {
      String viewId = json(vr).path("id").asString("");
      assertNotEquals(injectedId.toString(), viewId);
      assertTrue(savedViewIds(manager).contains(viewId), "gorunum sahibinin listesinde yok");
      assertFalse(savedViewIds(dev).contains(viewId), "gorunum baska kullanicinin listesine dustu");
    }
    HttpResponse<String> okView =
        send(
            "POST",
            "/api/v1/projects/" + a.projectId() + "/saved-views",
            manager,
            obj().put("name", "ma-view-k-" + uid()).put("query", "{}"));
    assertEquals(201, okView.statusCode(), okView.body());

    UUID sprint = newSprint();
    ObjectNode retro =
        injected(obj().put("kind", "improve").put("body", "ma retro"), injectedId)
            .put("authorId", manager.userId().toString());
    HttpResponse<String> rr =
        send("POST", "/api/v1/sprints/" + sprint + "/retro/items", dev, retro);
    assertTrue(
        rr.statusCode() == 201 || rr.statusCode() == 400 || rr.statusCode() == 422,
        rr.statusCode() + " " + rr.body());
    if (rr.statusCode() == 201) {
      JsonNode item = json(rr);
      assertNotEquals(injectedId.toString(), item.path("id").asString(""));
      assertEquals(dev.userId().toString(), item.path("authorId").asString(""));
    }
    HttpResponse<String> okRetro =
        send(
            "POST",
            "/api/v1/sprints/" + sprint + "/retro/items",
            dev,
            obj().put("kind", "improve").put("body", "ma retro k"));
    assertEquals(201, okRetro.statusCode(), okRetro.body());
    assertEquals(dev.userId().toString(), json(okRetro).path("authorId").asString(""));
  }

  @Test
  @DisplayName(
      "AUTHZ-03 | bildirim tercihleri: userId/workspaceId ile baska kullanicinin tercihi degismez")
  void authz03_notificationPreferencesIgnoreInjectedFields() {
    String path = "/api/v1/me/notification-preferences";
    JsonNode bBefore = json(fx.http().get(path, b.admin(), b.workspaceId(), null));
    ObjectNode body =
        injected(obj().put("emailOnAssign", false).put("emailOnMention", false), UUID.randomUUID())
            .put("userId", b.admin().userId().toString());
    HttpResponse<String> r = send("PUT", path, manager, body);
    assertTrue(
        r.statusCode() == 200 || r.statusCode() == 400 || r.statusCode() == 422,
        r.statusCode() + " " + r.body());
    assertEquals(bBefore, json(fx.http().get(path, b.admin(), b.workspaceId(), null)));
    HttpResponse<String> ok =
        send("PUT", path, manager, obj().put("emailOnAssign", false).put("emailOnMention", false));
    assertEquals(200, ok.statusCode(), ok.body());
    JsonNode mine = json(send("GET", path, manager, null));
    assertFalse(mine.path("emailOnAssign").asBoolean(true));
    assertFalse(mine.path("emailOnMention").asBoolean(true));
  }

  // =========================================================================================
  // AUTHZ-04 ayni kiracida baskasinin kaynaklari
  // =========================================================================================

  @Test
  @DisplayName("AUTHZ-04 | kayitli gorunum: baskasi listede goremez, silemez; sahibi siler")
  void authz04_savedViewOwnerOnly() {
    HttpResponse<String> created =
        send(
            "POST",
            "/api/v1/projects/" + a.projectId() + "/saved-views",
            manager,
            obj().put("name", "own-view-" + uid()).put("query", "{}"));
    assertEquals(201, created.statusCode(), created.body());
    String viewId = json(created).path("id").asString("");

    assertTrue(savedViewIds(manager).contains(viewId), "K: sahibi kendi gorunumunu goruyor");
    for (Actor other : List.of(dev, admin, viewer)) {
      assertFalse(savedViewIds(other).contains(viewId), "baskasinin gorunumu listede");
      int sc = send("DELETE", "/api/v1/saved-views/" + viewId, other, null).statusCode();
      assertTrue(sc == 403 || sc == 404, "baskasinin gorunumunu silme: " + sc);
    }
    assertTrue(savedViewIds(manager).contains(viewId), "reddedilen silme gorunumu yok etti");
    assertEquals(204, send("DELETE", "/api/v1/saved-views/" + viewId, manager, null).statusCode());
    assertFalse(savedViewIds(manager).contains(viewId));
  }

  @Test
  @DisplayName("AUTHZ-04 | yorum: baskasi duzenleyemez/silemez; yazan duzenler ve siler")
  void authz04_commentOnlyAuthorEditsOrDeletes() {
    UUID task = newTask("c-task");
    UUID cid = createComment(dev, task, "dev yorumu");

    for (Actor other : List.of(manager, admin, viewer)) {
      int e =
          send("PATCH", "/api/v1/comments/" + cid, other, obj().put("body", "ele gecirildi"))
              .statusCode();
      assertTrue(e == 403 || e == 404, "baskasinin yorumunu duzenleme: " + e);
    }
    for (Actor other : List.of(manager, viewer)) {
      int d = send("DELETE", "/api/v1/comments/" + cid, other, null).statusCode();
      assertTrue(d == 403 || d == 404, "baskasinin yorumunu silme: " + d);
    }
    JsonNode untouched = commentOf(task, cid);
    assertEquals("dev yorumu", untouched.path("body").asString(""));
    assertFalse(untouched.path("deleted").asBoolean(true));

    // K: yazan duzenler ve siler.
    HttpResponse<String> edit =
        send("PATCH", "/api/v1/comments/" + cid, dev, obj().put("body", "dev duzenledi"));
    assertEquals(200, edit.statusCode(), edit.body());
    assertEquals("dev duzenledi", commentOf(task, cid).path("body").asString(""));
    assertEquals(204, send("DELETE", "/api/v1/comments/" + cid, dev, null).statusCode());
    assertTrue(commentOf(task, cid).path("deleted").asBoolean(false));
  }

  @Test
  @DisplayName(
      "AUTHZ-04 | yorum: workspace ADMIN baskasinin yorumunu siler (COMMENT_MODERATE), duzenleyemez")
  void authz04_adminModeratesCommentButCannotEdit() {
    UUID task = newTask("c-mod");
    UUID cid = createComment(dev, task, "moderasyon");
    int e = send("PATCH", "/api/v1/comments/" + cid, admin, obj().put("body", "x")).statusCode();
    assertTrue(e == 403 || e == 404, "ADMIN baskasinin yorumunu duzenledi: " + e);
    assertEquals("moderasyon", commentOf(task, cid).path("body").asString(""));

    assertEquals(204, send("DELETE", "/api/v1/comments/" + cid, admin, null).statusCode());
    JsonNode after = commentOf(task, cid);
    assertTrue(after.path("deleted").asBoolean(false));
    assertEquals("[silindi]", after.path("body").asString(""));
  }

  @Test
  @DisplayName(
      "AUTHZ-04 | retro maddesi: baskasi silemez (ADMIN dahil), yazan siler; duzenleme ucu yok")
  void authz04_retroItemOnlyAuthorDeletes() {
    UUID sprint = newSprint();
    HttpResponse<String> created =
        send(
            "POST",
            "/api/v1/sprints/" + sprint + "/retro/items",
            dev,
            obj().put("kind", "improve").put("body", "dev retro"));
    assertEquals(201, created.statusCode(), created.body());
    String itemId = json(created).path("id").asString("");

    for (Actor other : List.of(manager, admin)) {
      int sc = send("DELETE", "/api/v1/retro-items/" + itemId, other, null).statusCode();
      assertTrue(sc == 403 || sc == 404, "baskasinin retro maddesini silme: " + sc);
    }
    assertTrue(retroItemIds(sprint).contains(itemId), "reddedilen silme maddeyi yok etti");
    // Duzenleme: OpenAPI'de retro maddesini degistiren (PUT/PATCH) uc yok.
    assertTrue(
        apiOperations().stream()
            .noneMatch(
                op ->
                    op.path().contains("retro-items")
                        && (op.method().equals("PUT") || op.method().equals("PATCH"))),
        "retro maddesi icin duzenleme ucu eklenmis; sahiplik testi eklenmeli");

    assertEquals(204, send("DELETE", "/api/v1/retro-items/" + itemId, dev, null).statusCode());
    assertFalse(retroItemIds(sprint).contains(itemId));
  }

  // =========================================================================================
  // AUTHZ-06 SYSTEM_ADMIN
  // =========================================================================================

  @Test
  @DisplayName(
      "AUTHZ-06 | SYSTEM_ADMIN ucu: ADMIN dahil tum workspace rolleri 403; SYSTEM_ADMIN 200")
  void authz06_systemAdminEndpointsRejectEveryWorkspaceRole() throws Exception {
    List<String> adminOps =
        apiOperations().stream()
            .filter(op -> op.path().startsWith("/api/v1/admin/"))
            .map(op -> op.method() + " " + op.path())
            .sorted()
            .toList();
    assertEquals(
        List.of("POST /api/v1/admin/kafka/replay"),
        adminOps,
        "yeni SYSTEM_ADMIN ucu eklenmis: bu teste rol matrisi eklenmeli");

    String topic = "sec-authz06-" + UUID.randomUUID() + "-dlt";
    createTopic(topic);
    String body =
        "{\"dltTopic\":\"" + topic + "\",\"partition\":0,\"fromOffset\":0,\"toOffset\":0}";
    String path = "/api/v1/admin/kafka/replay";

    for (Actor who : List.of(admin, manager, dev, viewer)) {
      HttpResponse<String> r = fx.http().post(path, who, ws, body);
      assertEquals(403, r.statusCode(), who.email() + ": " + r.body());
    }
    assertEquals(
        403, fx.http().post(path, admin, null, body).statusCode(), "workspace basliksiz ADMIN");
    assertEquals(
        403, fx.http().post(path, fx.outsider(), null, body).statusCode(), "uyesiz kullanici");
    assertEquals(401, fx.http().post(path, (String) null, null, body).statusCode(), "anonim");

    // K: SYSTEM_ADMIN KATI 200, izole bos konuda 0 mesaj.
    String email = "sec-authz06-" + UUID.randomUUID() + "@tracker.local";
    UUID uid =
        authService
            .register(
                email, SecurityFixture.PASSWORD, "Sec sysadmin", PrivacyNotice.CURRENT_VERSION)
            .getId();
    assertEquals(1, execute("UPDATE users SET system_admin = true WHERE id = ?", uid));
    String jwt = authService.login(email, SecurityFixture.PASSWORD, "127.0.0.1").accessToken();
    assertTrue(JwtForge.decodePayload(jwt).contains("SYSTEM_ADMIN"), "on kosul");
    HttpResponse<String> ok = fx.http().post(path, jwt, null, body);
    assertEquals(200, ok.statusCode(), ok.body());
    assertTrue(ok.body().contains("\"replayedCount\":0"), ok.body());
  }

  // =========================================================================================
  // AUTHZ-10 DEVELOPER gorev olusturamaz / planlayamaz
  // =========================================================================================

  @Test
  @DisplayName("AUTHZ-10 | DEVELOPER gorev olusturamaz: tekil, genisletilmis, retro->gorev 403")
  void authz10_developerCannotCreateTasks() {
    UUID sprint = newSprint();
    UUID tag = newTag();
    String t1 = "dev-create-" + uid();
    String t2 = "dev-create-ext-" + uid();
    String path = "/api/v1/projects/" + a.projectId() + "/tasks";

    assertEquals(403, send("POST", path, dev, obj().put("title", t1)).statusCode());
    ObjectNode extended =
        obj()
            .put("title", t2)
            .put("description", "ayrinti")
            .put("dueDate", "2099-09-30")
            .put("assigneeId", dev.userId().toString())
            .put("storyPoint", 5)
            .put("sprintId", sprint.toString());
    extended.putArray("tagIds").add(tag.toString());
    HttpResponse<String> er = send("POST", path, dev, extended);
    assertEquals(403, er.statusCode(), er.body());
    assertEquals(0, count("SELECT count(*) FROM tasks WHERE title IN (?, ?)", t1, t2));

    // Retro maddesini goreve cevirme de gorev olusturmadir.
    HttpResponse<String> item =
        send(
            "POST",
            "/api/v1/sprints/" + sprint + "/retro/items",
            dev,
            obj().put("kind", "action").put("body", "yapilacak " + uid()));
    assertEquals(201, item.statusCode(), item.body());
    String itemId = json(item).path("id").asString("");
    assertEquals(
        403,
        send("POST", "/api/v1/retro-items/" + itemId + "/convert-to-task", dev, null).statusCode());
    assertTrue(json(item).path("taskId").isNull());
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM retro_items WHERE id = ? AND task_id IS NOT NULL",
            UUID.fromString(itemId)),
        "retro maddesi goreve donustu");

    // Toplu OLUSTURMA ucu yok: gorev ureten tek POST yollari bilinen uclardir.
    Set<String> creators =
        new TreeSet<>(
            apiOperations().stream()
                .filter(
                    op ->
                        op.method().equals("POST")
                            && (op.path().endsWith("/tasks")
                                || op.path().endsWith("/subtasks")
                                || op.path().contains("convert-to-task")
                                || op.path().contains("/tasks/bulk")
                                || op.path().contains("import")))
                .map(op -> op.method() + " " + op.path())
                .toList());
    assertEquals(
        new TreeSet<>(
            Set.of(
                "POST /api/v1/projects/{projectId}/tasks",
                "POST /api/v1/tasks/{parentTaskId}/subtasks",
                "POST /api/v1/retro-items/{id}/convert-to-task",
                "POST /api/v1/tasks/bulk")),
        creators,
        "yeni gorev uretme yolu eklenmis: bu teste DEVELOPER ret testi eklenmeli");

    // K: MANAGER ayni istekleri yapar.
    HttpResponse<String> mOk = send("POST", path, manager, obj().put("title", "mgr-" + uid()));
    assertEquals(201, mOk.statusCode(), mOk.body());
    UUID sprint2 = newSprint();
    ObjectNode mExt =
        obj()
            .put("title", "mgr-ext-" + uid())
            .put("description", "ayrinti")
            .put("dueDate", "2099-09-30")
            .put("assigneeId", dev.userId().toString())
            .put("storyPoint", 5)
            .put("sprintId", sprint2.toString());
    mExt.putArray("tagIds").add(tag.toString());
    HttpResponse<String> mExtR = send("POST", path, manager, mExt);
    assertEquals(201, mExtR.statusCode(), mExtR.body());
    JsonNode created = json(mExtR);
    assertEquals(5, created.path("storyPoint").asInt(-1));
    assertEquals(sprint2.toString(), created.path("sprintId").asString(""));
    assertEquals(dev.userId().toString(), created.path("assigneeId").asString(""));
    HttpResponse<String> conv =
        send("POST", "/api/v1/retro-items/" + itemId + "/convert-to-task", manager, null);
    assertEquals(201, conv.statusCode(), conv.body());
  }

  @Test
  @DisplayName(
      "AUTHZ-10 | DEVELOPER planlama alanlarini degistiremez (tekil ve toplu); hicbir degisiklik uygulanmaz")
  void authz10_developerCannotChangePlanningFields() {
    UUID mine = newTaskAssignedTo(dev, "plan-mine");
    UUID free = newTask("plan-free");
    UUID sprint = newSprint();
    JsonNode mineBefore = taskOf(mine);
    JsonNode freeBefore = taskOf(free);

    assertEquals(
        403,
        send("PUT", "/api/v1/tasks/" + mine + "/story-point", dev, obj().put("storyPoint", 8))
            .statusCode());
    assertEquals(
        403,
        send("PUT", "/api/v1/tasks/" + mine + "/due-date", dev, obj().put("dueDate", "2099-09-30"))
            .statusCode());
    assertEquals(
        403,
        send(
                "PUT",
                "/api/v1/tasks/" + mine + "/sprint",
                dev,
                obj().put("sprintId", sprint.toString()))
            .statusCode());
    assertEquals(
        403,
        send(
                "POST",
                "/api/v1/tasks/bulk",
                dev,
                bulk(List.of(mine, free), "SPRINT").put("sprintId", sprint.toString()))
            .statusCode());
    assertEquals(
        403,
        send(
                "POST",
                "/api/v1/tasks/bulk",
                dev,
                bulk(List.of(mine, free), "ASSIGNEE").put("assigneeId", dev.userId().toString()))
            .statusCode());
    assertEquals(mineBefore, taskOf(mine), "DEVELOPER istegi kendi gorevini degistirdi");
    assertEquals(freeBefore, taskOf(free), "toplu istek kismen uygulandi");

    // K: MANAGER ayni planlama islemlerini basariyla yapar.
    assertEquals(
        8,
        json(send(
                "PUT",
                "/api/v1/tasks/" + mine + "/story-point",
                manager,
                obj().put("storyPoint", 8)))
            .path("storyPoint")
            .asInt(-1));
    assertEquals(
        "2099-09-30",
        json(send(
                "PUT",
                "/api/v1/tasks/" + mine + "/due-date",
                manager,
                obj().put("dueDate", "2099-09-30")))
            .path("dueDate")
            .asString(""));
    assertEquals(
        sprint.toString(),
        json(send(
                "PUT",
                "/api/v1/tasks/" + mine + "/sprint",
                manager,
                obj().put("sprintId", sprint.toString())))
            .path("sprintId")
            .asString(""));
    HttpResponse<String> bulkOk =
        send(
            "POST",
            "/api/v1/tasks/bulk",
            manager,
            bulk(List.of(free), "SPRINT").put("sprintId", sprint.toString()));
    assertEquals(200, bulkOk.statusCode(), bulkOk.body());
    assertEquals(sprint.toString(), taskOf(free).path("sprintId").asString(""));
    HttpResponse<String> bulkAssign =
        send(
            "POST",
            "/api/v1/tasks/bulk",
            manager,
            bulk(List.of(free), "ASSIGNEE").put("assigneeId", dev.userId().toString()));
    assertEquals(200, bulkAssign.statusCode(), bulkAssign.body());
    assertEquals(dev.userId().toString(), taskOf(free).path("assigneeId").asString(""));
  }

  @Test
  @DisplayName("AUTHZ-10 K | DEVELOPER kendi gorevinde durumu degistirir (tekil ve toplu)")
  void authz10_k_developerChangesStatusOfOwnTask() {
    UUID mine = newTaskAssignedTo(dev, "work");
    HttpResponse<String> r =
        send("PATCH", "/api/v1/tasks/" + mine, dev, obj().put("status", IN_PROGRESS));
    assertEquals(200, r.statusCode(), r.body());
    assertEquals(IN_PROGRESS, json(r).path("status").asString(""));
    assertEquals(IN_PROGRESS, taskOf(mine).path("status").asString(""));

    HttpResponse<String> bulk =
        send(
            "POST", "/api/v1/tasks/bulk", dev, bulk(List.of(mine), "STATUS").put("status", REVIEW));
    assertEquals(200, bulk.statusCode(), bulk.body());
    assertEquals(REVIEW, taskOf(mine).path("status").asString(""));
  }

  // =========================================================================================
  // AUTHZ-11 TASK_SELF_ASSIGN
  // =========================================================================================

  @Test
  @DisplayName("AUTHZ-11 | DEVELOPER baskasina atayamaz, baskasina atanmis gorevi devralamaz")
  void authz11_developerCannotAssignToOthersOrTakeOverAssignedTask() {
    UUID unassigned = newTask("sa-unassigned");
    UUID assignedToManager = newTaskAssignedTo(manager, "sa-mgr");

    // Baskasina atama.
    int toOther =
        send(
                "PUT",
                "/api/v1/tasks/" + unassigned + "/assignee",
                dev,
                obj().put("assigneeId", manager.userId().toString()))
            .statusCode();
    assertEquals(403, toOther);
    assertTrue(taskOf(unassigned).path("assigneeId").isNull(), "atama baskasina yapildi");

    // Baska birine atanmis gorevi devralma.
    int takeOver =
        send(
                "PUT",
                "/api/v1/tasks/" + assignedToManager + "/assignee",
                dev,
                obj().put("assigneeId", dev.userId().toString()))
            .statusCode();
    assertEquals(403, takeOver);
    // Baskasinin atamasini birakma.
    int release =
        send(
                "PUT",
                "/api/v1/tasks/" + assignedToManager + "/assignee",
                dev,
                obj().putNull("assigneeId"))
            .statusCode();
    assertEquals(403, release);
    assertEquals(
        manager.userId().toString(), taskOf(assignedToManager).path("assigneeId").asString(""));

    // VIEWER kendini bile atayamaz.
    assertEquals(
        403,
        send(
                "PUT",
                "/api/v1/tasks/" + unassigned + "/assignee",
                viewer,
                obj().put("assigneeId", viewer.userId().toString()))
            .statusCode());
    assertTrue(taskOf(unassigned).path("assigneeId").isNull());
  }

  @Test
  @DisplayName("AUTHZ-11 K | DEVELOPER atanmamis gorevi kendine atar, kendi atamasini birakir")
  void authz11_k_developerSelfAssignsUnassignedTask() {
    UUID unassigned = newTask("sa-k");
    HttpResponse<String> r =
        send(
            "PUT",
            "/api/v1/tasks/" + unassigned + "/assignee",
            dev,
            obj().put("assigneeId", dev.userId().toString()));
    assertEquals(200, r.statusCode(), r.body());
    assertEquals(dev.userId().toString(), json(r).path("assigneeId").asString(""));
    assertEquals(dev.userId().toString(), taskOf(unassigned).path("assigneeId").asString(""));

    HttpResponse<String> release =
        send("PUT", "/api/v1/tasks/" + unassigned + "/assignee", dev, obj().putNull("assigneeId"));
    assertEquals(200, release.statusCode(), release.body());
    assertTrue(taskOf(unassigned).path("assigneeId").isNull());
  }

  // =========================================================================================
  // AUTHZ-12 SUBTASK_CREATE_OWN
  // =========================================================================================

  @Test
  @DisplayName("AUTHZ-12 | DEVELOPER kendisine atanmamis gorevin altina alt gorev acamaz")
  void authz12_developerCannotCreateSubtaskUnderForeignTask() {
    UUID unassigned = newTask("st-unassigned");
    UUID toManager = newTaskAssignedTo(manager, "st-mgr");
    UUID toAdmin = newTaskAssignedTo(admin, "st-adm");
    for (UUID parent : List.of(unassigned, toManager, toAdmin)) {
      HttpResponse<String> r =
          send(
              "POST",
              "/api/v1/tasks/" + parent + "/subtasks",
              dev,
              obj().put("title", "yetkisiz-alt-" + uid()));
      assertEquals(403, r.statusCode(), r.body());
      assertEquals(0, subtasksOf(parent).size(), "alt gorev yine de olustu");
    }
    assertEquals(
        403,
        send(
                "POST",
                "/api/v1/tasks/" + toManager + "/subtasks",
                viewer,
                obj().put("title", "viewer-alt"))
            .statusCode());
  }

  @Test
  @DisplayName("AUTHZ-12 | puanli alt gorev reddedilir: puan yalniz ust gorevde (ADR-0021)")
  void authz12_pointedSubtaskIsRejected() {
    UUID parent = newTaskAssignedTo(dev, "st-points");

    // Alt gorev olusturma isteginde puan: reddedilir ya da yok sayilir, alt gorevde puan olmaz.
    HttpResponse<String> r =
        send(
            "POST",
            "/api/v1/tasks/" + parent + "/subtasks",
            dev,
            obj().put("title", "puanli-alt-" + uid()).put("storyPoint", 5));
    int sc = r.statusCode();
    assertTrue(sc == 201 || sc == 400 || sc == 422, "beklenmeyen " + sc + " " + r.body());
    if (sc == 201) {
      assertTrue(json(r).path("storyPoint").isNull(), "alt gorev puanla olustu: " + r.body());
      // Saklanan puan (yanittan bagimsiz, liste okumasi) NULL olmali.
      UUID created = UUID.fromString(json(r).path("id").asString(""));
      assertTrue(taskOf(created).path("storyPoint").isNull(), "saklanan puan null degil");
    }
    for (JsonNode child : subtasksOf(parent)) {
      assertTrue(child.path("storyPoint").isNull(), "alt gorevde puan var");
    }

    // Mevcut alt goreve puan girilemez (yetkili rol bile).
    HttpResponse<String> ok =
        send(
            "POST",
            "/api/v1/tasks/" + parent + "/subtasks",
            dev,
            obj().put("title", "alt-" + uid()));
    assertEquals(201, ok.statusCode(), ok.body());
    UUID child = UUID.fromString(json(ok).path("id").asString(""));
    HttpResponse<String> pr =
        send("PUT", "/api/v1/tasks/" + child + "/story-point", manager, obj().put("storyPoint", 5));
    assertEquals(400, pr.statusCode(), pr.body());
    assertTrue(taskOf(child).path("storyPoint").isNull(), "puan alt goreve yazildi");

    // Puanli gorev alt gorev olarak baglanamaz.
    UUID pointed = newTask("st-pointed");
    assertEquals(
        200,
        send(
                "PUT",
                "/api/v1/tasks/" + pointed + "/story-point",
                manager,
                obj().put("storyPoint", 3))
            .statusCode());
    HttpResponse<String> link =
        send("PUT", "/api/v1/tasks/" + pointed + "/parent/" + parent, manager, null);
    assertEquals(400, link.statusCode(), link.body());
    assertTrue(taskOf(pointed).path("parentTaskId").isNull(), "puanli gorev alt gorev oldu");

    // K: puansiz bagimsiz gorev ayni uc ile baglanir (2xx).
    UUID unpointed = newTask("st-unpointed");
    HttpResponse<String> linkOk =
        send("PUT", "/api/v1/tasks/" + unpointed + "/parent/" + parent, manager, null);
    assertTrue(linkOk.statusCode() / 100 == 2, "K: " + linkOk.statusCode() + " " + linkOk.body());
    assertEquals(parent.toString(), taskOf(unpointed).path("parentTaskId").asString(""));
  }

  @Test
  @DisplayName(
      "AUTHZ-12 K | DEVELOPER kendi gorevinin altina puansiz alt gorev acar (alanlar ust gorevden)")
  void authz12_k_developerCreatesSubtaskUnderOwnTask() {
    UUID parent = newTaskAssignedTo(dev, "st-own");
    UUID sprint = newSprint();
    assertEquals(
        200,
        send(
                "PUT",
                "/api/v1/tasks/" + parent + "/sprint",
                manager,
                obj().put("sprintId", sprint.toString()))
            .statusCode());

    HttpResponse<String> r =
        send(
            "POST",
            "/api/v1/tasks/" + parent + "/subtasks",
            dev,
            obj().put("title", "kendi-alt-" + uid()));
    assertEquals(201, r.statusCode(), r.body());
    JsonNode child = json(r);
    assertEquals(parent.toString(), child.path("parentTaskId").asString(""));
    assertTrue(child.path("storyPoint").isNull());
    assertEquals(
        dev.userId().toString(), child.path("assigneeId").asString(""), "atanan ust gorevden");
    assertEquals(sprint.toString(), child.path("sprintId").asString(""), "sprint ust gorevden");
    assertEquals(1, subtasksOf(parent).size());

    // DEVELOPER alt gorevin atananini baskasi yapamaz (ust gorevden turetilir).
    HttpResponse<String> foreign =
        send(
            "POST",
            "/api/v1/tasks/" + parent + "/subtasks",
            dev,
            obj()
                .put("title", "baskasina-alt-" + uid())
                .put("assigneeId", manager.userId().toString()));
    assertEquals(403, foreign.statusCode(), foreign.body());
    assertEquals(1, subtasksOf(parent).size());
  }

  // =========================================================================================
  // AUTHZ-13 acik bloklayan varken Done yok
  // =========================================================================================

  @Test
  @DisplayName("AUTHZ-13 | acik bloklayanli gorev tekil ve toplu durum guncellemesinde Done olamaz")
  void authz13_blockedTaskCannotBeDoneViaSingleOrBulk() {
    JsonNode blockerJson = newTaskJson("bl-blocker");
    UUID blocker = UUID.fromString(blockerJson.path("id").asString(""));
    String blockerKey = "SEC-" + blockerJson.path("taskNumber").asInt();
    UUID blocked = newTaskAssignedTo(dev, "bl-blocked");
    UUID free = newTask("bl-free");
    link(blocked, blocker);

    // Tekil, hem yetkili rol hem DEVELOPER (kendi gorevi) ile.
    for (Actor who : List.of(manager, dev)) {
      HttpResponse<String> r =
          send("PATCH", "/api/v1/tasks/" + blocked, who, obj().put("status", DONE));
      assertTrue(
          r.statusCode() / 100 == 4, "tekil Done 4xx olmali: " + r.statusCode() + " " + r.body());
      assertEquals(400, r.statusCode(), r.body());
      assertTrue(
          r.body().contains(blockerKey), "mesaj bloklayan anahtarini listelemeli: " + r.body());
      assertEquals(TODO, taskOf(blocked).path("status").asString(""));
    }
    // Toplu: hepsi ya da hicbiri.
    HttpResponse<String> bulk =
        send(
            "POST",
            "/api/v1/tasks/bulk",
            manager,
            bulk(List.of(free, blocked), "STATUS").put("status", DONE));
    assertEquals(400, bulk.statusCode(), bulk.body());
    assertTrue(bulk.body().contains(blockerKey), bulk.body());
    assertEquals(TODO, taskOf(blocked).path("status").asString(""));
    assertEquals(TODO, taskOf(free).path("status").asString(""), "toplu istek kismen uygulandi");
    // Bloklu gorev Done disi durumlarda serbest (ADR-0020 madde 2).
    assertEquals(
        200,
        send("PATCH", "/api/v1/tasks/" + blocked, manager, obj().put("status", IN_PROGRESS))
            .statusCode());

    // Board surukle-birak icin ayri bir uc yok: durum yazan tek uc PATCH /tasks/{id} ve bulk.
    List<String> statusWriters =
        apiOperations().stream()
            .filter(
                op ->
                    op.path().matches(".*(board|move|reorder|drag|transition).*")
                        && !op.method().equals("GET"))
            .map(op -> op.method() + " " + op.path())
            .toList();
    assertTrue(
        statusWriters.isEmpty(),
        "board/surukle-birak ucu eklenmis; bloklayan kuralina karsi test edilmeli: "
            + statusWriters);

    // K: bloklayan Done olunca ayni gecisler basarili.
    assertEquals(
        200,
        send("PATCH", "/api/v1/tasks/" + blocker, manager, obj().put("status", DONE)).statusCode());
    HttpResponse<String> okSingle =
        send("PATCH", "/api/v1/tasks/" + blocked, manager, obj().put("status", DONE));
    assertEquals(200, okSingle.statusCode(), okSingle.body());
    assertEquals(DONE, taskOf(blocked).path("status").asString(""));

    UUID blocker2 = newTask("bl-blocker2");
    UUID blocked2 = newTask("bl-blocked2");
    link(blocked2, blocker2);
    assertEquals(
        400,
        send("PATCH", "/api/v1/tasks/" + blocked2, manager, obj().put("status", DONE))
            .statusCode());
    assertEquals(
        200,
        send("PATCH", "/api/v1/tasks/" + blocker2, manager, obj().put("status", DONE))
            .statusCode());
    HttpResponse<String> okBulk =
        send(
            "POST",
            "/api/v1/tasks/bulk",
            manager,
            bulk(List.of(free, blocked2), "STATUS").put("status", DONE));
    assertEquals(200, okBulk.statusCode(), okBulk.body());
    assertEquals(DONE, taskOf(free).path("status").asString(""));
    assertEquals(DONE, taskOf(blocked2).path("status").asString(""));
  }

  @Test
  @DisplayName(
      "AUTHZ-13 | GitHub PR-merge bloklu gorevi Done yapmaz, TASK_TRANSITION_BLOCKED bildirimi yazar, DLT'ye dusmez")
  void authz13_githubMergeSkipsBlockedTaskAndNotifies() throws Exception {
    JsonNode blockerJson = newTaskJson("gh-blocker");
    UUID blocker = UUID.fromString(blockerJson.path("id").asString(""));
    JsonNode blockedJson = newTaskJson("gh-blocked");
    UUID blocked = UUID.fromString(blockedJson.path("id").asString(""));
    String blockedKey = "SEC-" + blockedJson.path("taskNumber").asInt();
    assertEquals(
        200,
        send(
                "PUT",
                "/api/v1/tasks/" + blocked + "/assignee",
                manager,
                obj().put("assigneeId", dev.userId().toString()))
            .statusCode());
    link(blocked, blocker);
    Hook hook = createHook();

    // Bloklu: PR merge Done'a tasimaz.
    UUID delivery = deliverMerge(hook, blockedKey);
    awaitProcessed(hook, delivery);
    assertEquals(
        TODO, taskOf(blocked).path("status").asString(""), "bloklu gorev PR merge ile Done oldu");
    JsonNode notification =
        await(
            () -> blockedNotification(dev, blocked),
            n -> n != null,
            45_000,
            "TASK_TRANSITION_BLOCKED bildirimi gelmedi (gorev " + blocked + ")");
    assertEquals("TASK_TRANSITION_BLOCKED", notification.path("type").asString(""));
    assertFalse(
        dltContains(delivery.toString()), "webhook olayi " + WEBHOOK_DLT_TOPIC + " konusuna dustu");

    // K: bloklayan Done olduktan sonra ayni PR merge basarili.
    assertEquals(
        200,
        send("PATCH", "/api/v1/tasks/" + blocker, manager, obj().put("status", DONE)).statusCode());
    UUID delivery2 = deliverMerge(hook, blockedKey);
    awaitProcessed(hook, delivery2);
    await(
        () -> taskOf(blocked).path("status").asString(""),
        s -> DONE.equals(s),
        30_000,
        "bloklayan Done oldu ama PR merge gorevi Done yapmadi");
    assertFalse(dltContains(delivery2.toString()));
  }

  @Test
  @DisplayName(
      "AUTHZ-13 | otomasyon kurali (SUBTASK_ALL_DONE_PARENT_TO_REVIEW) bloklu gorevi Done'a tasimaz")
  void authz13_automationNeverMovesBlockedTaskToDone() {
    assertEquals(
        200,
        send(
                "PUT",
                "/api/v1/projects/"
                    + a.projectId()
                    + "/automation-rules/SUBTASK_ALL_DONE_PARENT_TO_REVIEW",
                manager,
                obj().put("enabled", true))
            .statusCode());
    UUID blocker = newTask("au-blocker");
    UUID parent = newTask("au-parent");
    link(parent, blocker);
    HttpResponse<String> sub =
        send(
            "POST",
            "/api/v1/tasks/" + parent + "/subtasks",
            manager,
            obj().put("title", "au-sub-" + uid()));
    assertEquals(201, sub.statusCode(), sub.body());
    UUID child = UUID.fromString(json(sub).path("id").asString(""));
    assertEquals(
        200,
        send("PATCH", "/api/v1/tasks/" + child, manager, obj().put("status", DONE)).statusCode());

    // Kural tetiklenir (ust gorev Review'a gider) ama Done'a ASLA.
    String status =
        await(
            () -> taskOf(parent).path("status").asString(""),
            s -> !TODO.equals(s),
            45_000,
            "otomasyon kurali ust gorevi tasimadi (ayni kural olculemedi)");
    assertEquals(REVIEW, status, "otomasyon ust gorevi beklenen durumda degil");
    assertNotEquals(DONE, taskOf(parent).path("status").asString(""));

    // K: bloklayan Done olduktan sonra gecis basarili.
    assertEquals(
        200,
        send("PATCH", "/api/v1/tasks/" + blocker, manager, obj().put("status", DONE)).statusCode());
    assertEquals(
        200,
        send("PATCH", "/api/v1/tasks/" + parent, manager, obj().put("status", DONE)).statusCode());
    assertEquals(DONE, taskOf(parent).path("status").asString(""));
  }

  // =========================================================================================
  // Yardimcilar: HTTP / JSON
  // =========================================================================================

  private ObjectNode obj() {
    return mapper.createObjectNode();
  }

  private JsonNode json(HttpResponse<String> r) {
    return mapper.readTree(r.body());
  }

  private HttpResponse<String> send(String method, String path, Actor who, JsonNode body) {
    return fx.http()
        .send(method, path, who.accessToken(), ws, body == null ? null : body.toString(), Map.of());
  }

  private static String uid() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  /** Istemcinin set edemeyecegi alanlari ekler; govdede zaten mesru olarak bulunani EZMEZ. */
  private ObjectNode injected(ObjectNode body, UUID injectedId) {
    putIfAbsent(body, "workspaceId", b.workspaceId().toString());
    putIfAbsent(body, "ownerId", b.admin().userId().toString());
    putIfAbsent(body, "createdBy", b.admin().userId().toString());
    putIfAbsent(body, "role", WorkspaceRole.ADMIN);
    if (!body.has("roles")) {
      body.putArray("roles").add(WorkspaceRole.ADMIN).add("SYSTEM_ADMIN");
    }
    putIfAbsent(body, "approvedAt", "2020-01-01T00:00:00Z");
    putIfAbsent(body, "deletedAt", "2020-01-01T00:00:00Z");
    putIfAbsent(body, "id", injectedId.toString());
    if (!body.has("taskNumber")) {
      body.put("taskNumber", 99999);
    }
    return body;
  }

  private static void putIfAbsent(ObjectNode body, String key, String value) {
    if (!body.has(key)) {
      body.put(key, value);
    }
  }

  private ObjectNode bulk(List<UUID> ids, String operation) {
    ObjectNode body = obj();
    var arr = body.putArray("taskIds");
    ids.forEach(i -> arr.add(i.toString()));
    body.put("operation", operation);
    return body;
  }

  // ---- kaynak uretimi -------------------------------------------------------------------------

  private JsonNode newTaskJson(String prefix) {
    HttpResponse<String> r =
        send(
            "POST",
            "/api/v1/projects/" + a.projectId() + "/tasks",
            manager,
            obj().put("title", prefix + "-" + uid()));
    assertEquals(201, r.statusCode(), "gorev kurulumu: " + r.body());
    return json(r);
  }

  private UUID newTask(String prefix) {
    return UUID.fromString(newTaskJson(prefix).path("id").asString(""));
  }

  private UUID newTaskAssignedTo(Actor assignee, String prefix) {
    UUID id = newTask(prefix);
    HttpResponse<String> r =
        send(
            "PUT",
            "/api/v1/tasks/" + id + "/assignee",
            manager,
            obj().put("assigneeId", assignee.userId().toString()));
    assertEquals(200, r.statusCode(), "atama kurulumu: " + r.body());
    return id;
  }

  private UUID newSprint() {
    HttpResponse<String> r =
        send(
            "POST",
            "/api/v1/projects/" + a.projectId() + "/sprints",
            manager,
            obj()
                .put("name", "s-" + uid())
                .put("startDate", "2099-01-01")
                .put("endDate", "2099-01-14"));
    assertEquals(201, r.statusCode(), "sprint kurulumu: " + r.body());
    return UUID.fromString(json(r).path("id").asString(""));
  }

  private UUID newTag() {
    HttpResponse<String> r =
        send(
            "POST",
            "/api/v1/tags",
            manager,
            obj().put("name", "t-" + uid()).put("color", "#00AA00"));
    assertEquals(201, r.statusCode(), "etiket kurulumu: " + r.body());
    return UUID.fromString(json(r).path("id").asString(""));
  }

  private void link(UUID blocked, UUID blocker) {
    HttpResponse<String> r =
        send("PUT", "/api/v1/tasks/" + blocked + "/dependencies/" + blocker, manager, null);
    assertEquals(200, r.statusCode(), "bagimlilik kurulumu: " + r.body());
  }

  private UUID createComment(Actor by, UUID task, String text) {
    HttpResponse<String> r =
        send("POST", "/api/v1/tasks/" + task + "/comments", by, obj().put("body", text));
    assertEquals(201, r.statusCode(), "yorum kurulumu: " + r.body());
    return UUID.fromString(json(r).path("id").asString(""));
  }

  private Actor registerFresh(String tag) {
    String email = "sec-" + tag + "-" + UUID.randomUUID() + "@tracker.local";
    UUID id =
        authService
            .register(email, SecurityFixture.PASSWORD, "Sec " + tag, PrivacyNotice.CURRENT_VERSION)
            .getId();
    return new Actor(
        id, email, authService.login(email, SecurityFixture.PASSWORD, "127.0.0.1").accessToken());
  }

  // ---- okuma ----------------------------------------------------------------------------------

  private List<JsonNode> listedTasks() {
    HttpResponse<String> r =
        fx.http().get("/api/v1/projects/" + a.projectId() + "/tasks?limit=200", admin, ws, null);
    assertEquals(200, r.statusCode(), r.body());
    List<JsonNode> out = new ArrayList<>();
    json(r).path("data").forEach(out::add);
    return out;
  }

  private Set<UUID> listedTaskIds() {
    Set<UUID> ids = new LinkedHashSet<>();
    listedTasks().forEach(t -> ids.add(UUID.fromString(t.path("id").asString(""))));
    return ids;
  }

  private JsonNode taskOf(UUID id) {
    for (JsonNode t : listedTasks()) {
      if (id.toString().equals(t.path("id").asString(""))) {
        return t;
      }
    }
    return fail("gorev A'nin listesinde yok: " + id);
  }

  private List<JsonNode> subtasksOf(UUID parent) {
    HttpResponse<String> r = send("GET", "/api/v1/tasks/" + parent + "/subtasks", admin, null);
    assertEquals(200, r.statusCode(), r.body());
    List<JsonNode> out = new ArrayList<>();
    json(r).forEach(out::add);
    return out;
  }

  private JsonNode commentOf(UUID task, UUID commentId) {
    HttpResponse<String> r =
        send("GET", "/api/v1/tasks/" + task + "/comments?limit=200", admin, null);
    assertEquals(200, r.statusCode(), r.body());
    for (JsonNode c : json(r).path("data")) {
      if (commentId.toString().equals(c.path("id").asString(""))) {
        return c;
      }
    }
    return fail("yorum listede yok: " + commentId);
  }

  private Set<String> savedViewIds(Actor who) {
    HttpResponse<String> r =
        send("GET", "/api/v1/projects/" + a.projectId() + "/saved-views", who, null);
    assertEquals(200, r.statusCode(), r.body());
    Set<String> ids = new LinkedHashSet<>();
    json(r).forEach(v -> ids.add(v.path("id").asString("")));
    return ids;
  }

  private Set<String> retroItemIds(UUID sprint) {
    HttpResponse<String> r = send("GET", "/api/v1/sprints/" + sprint + "/retro/items", admin, null);
    assertEquals(200, r.statusCode(), r.body());
    Set<String> ids = new LinkedHashSet<>();
    json(r).forEach(v -> ids.add(v.path("id").asString("")));
    return ids;
  }

  private JsonNode blockedNotification(Actor who, UUID task) {
    HttpResponse<String> r = send("GET", "/api/v1/notifications?limit=100", who, null);
    assertEquals(200, r.statusCode(), r.body());
    for (JsonNode n : json(r).path("data")) {
      if (task.toString().equals(n.path("taskId").asString(""))
          && "TASK_TRANSITION_BLOCKED".equals(n.path("type").asString(""))) {
        return n;
      }
    }
    return null;
  }

  // ---- GitHub webhook -------------------------------------------------------------------------

  private record Hook(UUID id, String secret) {}

  private Hook createHook() {
    HttpResponse<String> r = send("POST", "/api/v1/integrations/webhooks", admin, null);
    assertEquals(201, r.statusCode(), r.body());
    JsonNode j = json(r);
    return new Hook(
        UUID.fromString(j.path("integration").path("id").asString("")),
        j.path("secret").asString(""));
  }

  private UUID deliverMerge(Hook hook, String taskKey) {
    String body =
        "{\"action\":\"closed\",\"pull_request\":{\"merged\":true,\"draft\":false,\"title\":\""
            + taskKey
            + " bitir\",\"body\":\"\",\"head\":{\"ref\":\"feature\"}}}";
    UUID delivery = UUID.randomUUID();
    HttpResponse<String> r =
        fx.http()
            .send(
                "POST",
                "/api/v1/webhooks/github/" + hook.id(),
                null,
                null,
                body,
                Map.of(
                    "X-Hub-Signature-256",
                    GithubSignatureVerifier.sign(
                        hook.secret(), body.getBytes(StandardCharsets.UTF_8)),
                    "X-GitHub-Event",
                    "pull_request",
                    "X-GitHub-Delivery",
                    delivery.toString()));
    assertEquals(202, r.statusCode(), r.body());
    return delivery;
  }

  private void awaitProcessed(Hook hook, UUID delivery) {
    UUID eventId =
        UUID.nameUUIDFromBytes((hook.id() + ":" + delivery).getBytes(StandardCharsets.UTF_8));
    await(
        () ->
            count(
                "SELECT count(*) FROM processed_events WHERE consumer = ? AND event_id = ?",
                GithubEventProcessor.CONSUMER,
                eventId),
        c -> c == 1L,
        45_000,
        "teslimat worker tarafindan islenmedi (processed_events isareti yok)");
  }

  private boolean dltContains(String needle) {
    String bootstrap = ctx.getEnvironment().getProperty("spring.kafka.bootstrap-servers");
    try (AdminClient admin =
        AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
      if (!admin.listTopics().names().get().contains(WEBHOOK_DLT_TOPIC)) {
        return false;
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    java.util.Properties props = new java.util.Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
      List<TopicPartition> tps =
          c.partitionsFor(WEBHOOK_DLT_TOPIC).stream()
              .map(p -> new TopicPartition(WEBHOOK_DLT_TOPIC, p.partition()))
              .toList();
      c.assign(tps);
      c.seekToBeginning(tps);
      Map<TopicPartition, Long> end = c.endOffsets(tps);
      long deadline = System.currentTimeMillis() + 15_000;
      while (tps.stream().anyMatch(tp -> c.position(tp) < end.get(tp))
          && System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, String> rec : c.poll(Duration.ofMillis(500))) {
          if (rec.value() != null && rec.value().contains(needle)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private void createTopic(String topic) throws Exception {
    String bootstrap = ctx.getEnvironment().getProperty("spring.kafka.bootstrap-servers");
    try (AdminClient client =
        AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
      client.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
    }
  }

  private static <T> T await(Supplier<T> source, Predicate<T> done, long millis, String failure) {
    long deadline = System.currentTimeMillis() + millis;
    T value = source.get();
    while (!done.test(value) && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(300);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      value = source.get();
    }
    assertTrue(done.test(value), failure + " (son deger: " + value + ")");
    return value;
  }

  // ---- DB (app_migrator, tablo sahibi: kiraci baglami gerekmez) ---------------------------------

  private String taskFingerprint(UUID id) {
    return q(
        "SELECT id || '|' || workspace_id || '|' || project_id || '|' || task_number || '|' ||"
            + " coalesce(created_by::text,'-') || '|' || coalesce(approved_at::text,'-') || '|' ||"
            + " coalesce(approved_by::text,'-') || '|' || coalesce(deleted_at::text,'-') || '|' ||"
            + " coalesce(deleted_by::text,'-') FROM tasks WHERE id = ?",
        id);
  }

  private static long count(String sql, Object... args) {
    return Long.parseLong(q(sql, args));
  }

  /** Ilk satirin ilk sutunu (metin); satir yoksa {@code null}. */
  private static String q(String sql, Object... args) {
    try (Connection c = openMigratorConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, args);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int execute(String sql, Object... args) {
    try (Connection c = openMigratorConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, args);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) {
      ps.setObject(i + 1, args[i]);
    }
  }
}
