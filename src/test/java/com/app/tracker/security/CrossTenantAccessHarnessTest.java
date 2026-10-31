package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.security.support.OpenApiCatalog;
import com.app.tracker.security.support.OpenApiCatalog.Operation;
import com.app.tracker.security.support.OpenApiCatalog.QueryParam;
import com.app.tracker.security.support.OpenApiCatalog.Schema;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Actor;
import com.app.tracker.security.support.TenantResources;
import com.app.tracker.security.support.TenantResources.Type;
import com.app.tracker.workspace.model.WorkspaceRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Guvenlik katalogu §3: kiracilar arasi erisim (BOLA) — TEN-01..06, TEN-09, TEN-10, AUTHZ-14.
 *
 * <p><b>Harness (TEN-01/02/03):</b> uc listesi ELLE yazilmaz; kosan uygulamanin {@code GET
 * /v3/api-docs} yanitindan turetilir. Path sablonundaki her {@code {param}} {@link #PATH_PARAMS}
 * esleme tablosuyla bir kaynak tipine baglanir ve o kiracida (lazy, ucun kendisi icin taze)
 * uretilmis gercek bir kayit id'si yerlestirilir. Esleme tablosunda karsiligi olmayan bir parametre
 * (yeni uc) ya da {@link #BODIES} tablosunda gecerli govdesi olmayan bir sema testi KIRAR; sessiz
 * atlama yoktur. Bilincli disarida birakilanlar {@link #EXCLUDED}'da gerekcelidir.
 *
 * <p>Govdedeki id'ler (TEN-01) DAIMA istegi atan A kiracisinindir: red yalniz path id'sine
 * baglanabilsin diye. Govde id'lerinin kendisi TEN-04/TEN-10'da sinanir.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossTenantAccessHarnessTest extends AbstractIntegrationTest {

  // ---- esleme tablolari ---------------------------------------------------------------------

  /**
   * Path parametresi -> kaynak tipi. Anahtar once {@code onceki-segment/{param}}, sonra {@code
   * {param}}.
   */
  private static final Map<String, Type> PATH_PARAMS =
      Map.ofEntries(
          Map.entry("{taskId}", Type.TASK),
          Map.entry("tasks/{parentTaskId}", Type.TASK),
          Map.entry("parent/{parentTaskId}", Type.OTHER_TASK),
          Map.entry("dependencies/{blockingTaskId}", Type.OTHER_TASK),
          Map.entry("{projectId}", Type.PROJECT),
          Map.entry("{sprintId}", Type.SPRINT),
          Map.entry("{commentId}", Type.COMMENT),
          Map.entry("{tagId}", Type.TAG),
          Map.entry("{goalId}", Type.GOAL),
          Map.entry("{meetingId}", Type.MEETING),
          Map.entry("{viewId}", Type.SAVED_VIEW),
          Map.entry("retro-items/{id}", Type.RETRO_ITEM),
          Map.entry("notifications/{id}", Type.NOTIFICATION),
          Map.entry("webhooks/{id}", Type.WEBHOOK),
          Map.entry("{invitationId}", Type.INVITATION),
          Map.entry("members/{userId}", Type.MEMBER),
          Map.entry("{tokenId}", Type.PAT));

  /** Kiraciya ait olmayan, sabit degerli path parametreleri (id degil). */
  private static final Map<String, String> PATH_LITERALS =
      Map.of(
          "{templateKey}",
          "SUBTASK_ALL_DONE_PARENT_TO_REVIEW", // otomasyon sablon anahtari (enum)
          "{date}",
          TenantResources.DAY); // standup gunu

  /** Sorgu parametresi -> kaynak tipi (id tasiyan sorgu parametreleri; TEN-03'te B ile denenir). */
  private static final Map<String, Type> QUERY_IDS =
      Map.of("meetingId", Type.MEETING, "projectIds", Type.PROJECT);

  /** Zorunlu, id olmayan sorgu parametreleri. {@code q} ayrica isaret dizgesiyle doldurulur. */
  private static final Map<String, String> QUERY_LITERALS =
      Map.of(
          "year", "2099",
          "from", TenantResources.RANGE_FROM,
          "to", TenantResources.RANGE_TO,
          "date", TenantResources.DAY);

  /** Bilincli disarida birakilan uclar ve gerekceleri. */
  private static final Map<String, String> EXCLUDED =
      Map.of(
          "POST /api/v1/webhooks/github/{integrationId}",
          "GitHub webhook alma ucu: JWT tasimaz (permitAll), kimlik dogrulamasi HMAC imzasi,"
              + " kiraci path'teki entegrasyondan gelir; X-Workspace-Id/oturum kavrami yok",
          "GET /api/v1/invitations/{token}",
          "Davet onizleme: permitAll, {token} kiraci kaynak id'si degil gizli davet token'i",
          "POST /api/v1/invitations/{token}/accept",
          "Davet kabul: {token} gizli davet token'i (id degil); kiraci token'dan, e-posta eslesmesi"
              + " serviste — B'nin 'id'si' tahmin edilemez bir sir");

  /** {@code /api/v1/auth/**}: kimlik-oncesi uclar (oturum/kiraci baglami yok). */
  private static final String AUTH_PREFIX = "/api/v1/auth/";

  /**
   * Istek semasi -> sema-gecerli asgari govde (istegi atan kiracinin kaynaklariyla). Burada olmayan
   * bir sema "govde uretilemedi" ile testi kirar.
   */
  private static final Map<String, Function<TenantResources, String>> BODIES =
      Map.ofEntries(
          Map.entry("AssignSprintRequest", s -> "{\"sprintId\":\"" + s.id(Type.SPRINT) + "\"}"),
          Map.entry("AutomationRuleToggleRequest", s -> "{\"enabled\":true}"),
          Map.entry("CommentRequest", s -> "{\"body\":\"" + s.marker() + " harness yorum\"}"),
          Map.entry("GoalProgressRequest", s -> "{\"value\":1}"),
          Map.entry(
              "GoalUpdateRequest",
              s -> "{\"title\":\"" + s.marker() + " hedef2\",\"targetValue\":5}"),
          Map.entry("MeetingRequest", TenantResources::meetingJson),
          Map.entry("NoteRequest", s -> "{\"note\":\"bugun bunu yapacagim\"}"),
          Map.entry(
              "RetroItemRequest",
              s -> "{\"kind\":\"went_well\",\"body\":\"" + s.marker() + " retro2\"}"),
          Map.entry(
              "SavedViewRequest", s -> "{\"name\":\"" + s.marker() + " v2\",\"query\":\"{}\"}"),
          Map.entry(
              "CreateSprintRequest",
              s -> "{\"name\":\"S2\",\"startDate\":\"2099-07-01\",\"endDate\":\"2099-07-14\"}"),
          Map.entry("CreateTaskRequest", s -> "{\"title\":\"" + s.marker() + " yeni gorev\"}"),
          Map.entry("CreateSubtaskRequest", s -> "{\"title\":\"" + s.marker() + " alt gorev\"}"),
          Map.entry(
              "TagRequest",
              s ->
                  "{\"name\":\""
                      + s.marker()
                      + "-"
                      + TenantResources.rand(6)
                      + "\",\"color\":\"#0000FF\"}"),
          Map.entry(
              "UpdateAssigneeRequest", s -> "{\"assigneeId\":\"" + s.admin().userId() + "\"}"),
          Map.entry("UpdateDescriptionRequest", s -> "{\"description\":\"aciklama\"}"),
          Map.entry("UpdateDueDateRequest", s -> "{\"dueDate\":\"2099-09-30\"}"),
          Map.entry("UpdateStoryPointRequest", s -> "{\"storyPoint\":3}"),
          Map.entry("UpdateTaskStatusRequest", s -> "{\"status\":\"In Progress\"}"),
          Map.entry("ChangeWorkspaceMemberRoleRequest", s -> "{\"role\":\"VIEWER\"}"));

  private static final Set<Integer> TENANT_DENIED = Set.of(403, 404);

  // ---- durum -------------------------------------------------------------------------------

  private static SecurityFixture fx;
  private static final String A_MARK = "AMRK" + UUID.randomUUID().toString().substring(0, 8);
  private static final String B_MARK = "BMRK" + UUID.randomUUID().toString().substring(0, 8);
  private static final Map<String, String> REPORT = new LinkedHashMap<>();

  @LocalServerPort private int port;
  @Autowired private ApplicationContext ctx;
  @Autowired private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    if (fx == null) {
      fx = SecurityFixture.create(ctx, port);
    }
  }

  private TenantResources resA() {
    return new TenantResources(fx, mapper, fx.a(), A_MARK);
  }

  private TenantResources resB() {
    return new TenantResources(fx, mapper, fx.b(), B_MARK);
  }

  private OpenApiCatalog catalog() {
    HttpResponse<String> docs = fx.http().get("/v3/api-docs", (String) null, null, null);
    assertEquals(200, docs.statusCode(), "OpenAPI dokumani okunamadi");
    return OpenApiCatalog.parse(mapper.readTree(docs.body()));
  }

  private static boolean excluded(Operation op) {
    return EXCLUDED.containsKey(op.toString()) || op.path().startsWith(AUTH_PREFIX);
  }

  // =========================================================================================
  // TEN-01 id'li her uc: B'nin id'leriyle 404/403, A'nin kendi id'leriyle kabul
  // =========================================================================================

  @TestFactory
  @DisplayName("TEN-01 | OpenAPI'den turetilen id'li uclar")
  Stream<DynamicTest> ten01_crossTenantIdsAreDeniedOnEveryIdEndpoint() {
    OpenApiCatalog catalog = catalog();
    List<Operation> idOps = catalog.operations().stream().filter(Operation::hasPathParams).toList();
    List<Operation> targets = idOps.stream().filter(op -> !excluded(op)).toList();
    List<String> unmapped = new ArrayList<>();
    List<String> noBody = new ArrayList<>();
    for (Operation op : targets) {
      for (String p : op.pathParams()) {
        if (resolveParam(op.path(), p) == null) {
          unmapped.add(op + " {" + p + "}");
        }
      }
      String bodyProblem = bodyProblem(catalog, op);
      if (bodyProblem != null) {
        noBody.add(op + ": " + bodyProblem);
      }
    }
    List<String> rows = new ArrayList<>();
    // Okuma uclari durum degistirmez: tek, TAM dolu bir set paylasilir (B'nin alt gorevi, retro
    // maddesi... gercekten var olsun; bos liste "200 []" sizintiyi gizleyemesin). Yazma uclari ise
    // her biri taze set kullanir.
    TenantResources[] shared = new TenantResources[2];
    List<DynamicTest> tests = new ArrayList<>();
    tests.add(
        DynamicTest.dynamicTest(
            "TEN-01 | esleme tablosu tum path parametrelerini kapsiyor",
            () ->
                assertTrue(unmapped.isEmpty(), "EsLEMESI OLMAYAN path parametresi: " + unmapped)));
    tests.add(
        DynamicTest.dynamicTest(
            "TEN-01 | her govdeli uc icin gecerli govde uretildi",
            () -> assertTrue(noBody.isEmpty(), "govde uretilemedi: " + noBody)));
    for (Operation op : targets) {
      tests.add(
          DynamicTest.dynamicTest(
              "TEN-01 | " + op.method() + " " + op.path(),
              () -> runTen01(catalog, op, rows, shared)));
    }
    tests.add(
        DynamicTest.dynamicTest(
            "TEN-01 | ozet rapor",
            () -> {
              StringBuilder sb = new StringBuilder();
              sb.append("OpenAPI islem sayisi: ")
                  .append(catalog.operations().size())
                  .append("; id'li: ")
                  .append(idOps.size())
                  .append("; harness'e giren: ")
                  .append(targets.size())
                  .append("\nDislananlar:\n");
              idOps.stream()
                  .filter(CrossTenantAccessHarnessTest::excluded)
                  .forEach(
                      op ->
                          sb.append("- ")
                              .append(op)
                              .append(": ")
                              .append(EXCLUDED.get(op.toString()))
                              .append('\n'));
              sb.append("Esleme yok: ").append(unmapped).append('\n');
              sb.append("Govde uretilemedi: ").append(noBody).append('\n');
              sb.append(
                  "| Uc | R (B id) | K (A id) | B degisti mi | Sonuc |\n|---|---|---|---|---|\n");
              rows.forEach(r -> sb.append(r).append('\n'));
              writeReport("TEN-01", sb.toString());
            }));
    return tests.stream();
  }

  private void runTen01(
      OpenApiCatalog catalog, Operation op, List<String> rows, TenantResources[] shared) {
    for (String p : op.pathParams()) {
      if (resolveParam(op.path(), p) == null) {
        rows.add("| " + op + " | - | - | - | KALDI (esleme yok: {" + p + "}) |");
        fail("EsLEMESI OLMAYAN path parametresi {" + p + "} — harness esleme tablosuna ekleyin");
      }
    }
    String bodyProblem = bodyProblem(catalog, op);
    if (bodyProblem != null) {
      rows.add("| " + op + " | - | - | - | KALDI (govde uretilemedi) |");
      fail("govde uretilemedi: " + bodyProblem);
    }
    TenantResources a;
    TenantResources b;
    if (op.isRead()) {
      if (shared[0] == null) {
        shared[0] = resA().seedAll();
        shared[1] = resB().seedAll();
      }
      a = shared[0];
      b = shared[1];
    } else {
      a = resA();
      b = resB();
    }
    Actor actor = a.admin();
    UUID wsA = a.tenant().workspaceId();
    Set<String> bTokens = b.tokens();

    String rPath = fill(op, b, b, B_MARK);
    String rBody = body(op, a);
    String before = op.isRead() ? null : b.snapshot();
    HttpResponse<String> r = send(op.method(), rPath, actor, wsA, rBody);
    String after = op.isRead() ? null : b.snapshot();
    boolean bChanged = before != null && !before.equals(after);

    String kPath = fill(op, a, a, A_MARK);
    HttpResponse<String> k = send(op.method(), kPath, actor, wsA, body(op, a));

    List<String> leaked =
        bTokens.stream()
            .filter(t -> !rPath.contains(t) && r.body().contains(t))
            .toList(); // istekte gonderilen id'nin hata mesajinda yankilanmasi sizinti degil
    boolean rOk = TENANT_DENIED.contains(r.statusCode()) && leaked.isEmpty();
    boolean kOk = acceptablePair(k.statusCode());
    rows.add(
        "| "
            + op
            + " | "
            + r.statusCode()
            + " | "
            + k.statusCode()
            + " | "
            + (op.isRead() ? "-" : bChanged ? "EVET" : "hayir")
            + " | "
            + (rOk && kOk && !bChanged ? "GECTI" : "KALDI")
            + " |");
    assertAll(
        () ->
            assertTrue(
                TENANT_DENIED.contains(r.statusCode()),
                "R: B'nin id'leriyle "
                    + rPath
                    + " -> 404/403 beklenirdi, gelen "
                    + r.statusCode()
                    + " "
                    + abbreviate(r.body())),
        () -> assertTrue(leaked.isEmpty(), "R: yanit B'ye ait veri iceriyor: " + leaked),
        () ->
            assertFalse(
                bChanged, "R: B'nin kaynagi degisti:\nONCE\n" + before + "\nSONRA\n" + after),
        () ->
            assertTrue(
                kOk,
                "K: A'nin kendi id'leriyle "
                    + kPath
                    + " -> 2xx ya da 404/401/403/5xx DISI 4xx beklenirdi, gelen "
                    + k.statusCode()
                    + " "
                    + abbreviate(k.body())));
  }

  /** Kabul esi: 2xx ya da is kurali 4xx; 404 (uc bozuk), 401/403 (red maskelenir), 5xx DEGIL. */
  private static boolean acceptablePair(int status) {
    return status / 100 == 2
        || (status / 100 == 4 && status != 404 && status != 401 && status != 403);
  }

  // =========================================================================================
  // TEN-02 uye olunmayan workspace basligi
  // =========================================================================================

  @TestFactory
  @DisplayName("TEN-02 | X-Workspace-Id: B (uye degil) -> 403, A ile 200")
  Stream<DynamicTest> ten02_foreignWorkspaceHeaderForbidden() {
    OpenApiCatalog catalog = catalog();
    TenantResources a = resA();
    // GET /integrations/slack yapilandirilmamis workspace'te 404 doner (is kurali); kabul esinin
    // 200 olabilmesi icin A'ya kapali (enabled=false, teslimat denenmez) bir yapilandirma kurulur.
    HttpResponse<String> slack =
        fx.http()
            .put(
                "/api/v1/integrations/slack",
                a.admin(),
                a.tenant().workspaceId(),
                "{\"webhookUrl\":\"https://hooks.slack.com/services/T0SEC/B0SEC/x0sec\","
                    + "\"enabled\":false}");
    assertEquals(2, slack.statusCode() / 100, "Slack kurulumu: " + slack.body());
    List<String> rows = new ArrayList<>();
    List<DynamicTest> tests = new ArrayList<>();
    for (Operation op : catalog.operations()) {
      if (!op.isRead() || op.hasPathParams() || excluded(op)) {
        continue;
      }
      tests.add(
          DynamicTest.dynamicTest(
              "TEN-02 | GET " + op.path(),
              () -> {
                String path = fill(op, a, a, A_MARK);
                HttpResponse<String> r = fx.http().get(path, a.admin(), fx.b().workspaceId(), null);
                HttpResponse<String> k =
                    fx.http().get(path, a.admin(), a.tenant().workspaceId(), null);
                rows.add("| " + op + " | " + r.statusCode() + " | " + k.statusCode() + " |");
                assertAll(
                    () -> assertEquals(403, r.statusCode(), "R: " + path + " ws=B"),
                    () ->
                        assertEquals(
                            200, k.statusCode(), "K: " + path + " ws=A " + abbreviate(k.body())));
              }));
    }
    tests.add(
        DynamicTest.dynamicTest(
            "TEN-02 | ozet rapor",
            () ->
                writeReport(
                    "TEN-02",
                    "| Uc | R (ws=B) | K (ws=A) |\n|---|---|---|\n" + String.join("\n", rows))));
    return tests.stream();
  }

  // =========================================================================================
  // TEN-03 koleksiyon/okuma uclarinda B izi yok
  // =========================================================================================

  @TestFactory
  @DisplayName("TEN-03 | A'nin okuma yanitlarinda B'nin id/anahtar/isareti gecmez")
  Stream<DynamicTest> ten03_readResponsesNeverContainOtherTenantData() {
    OpenApiCatalog catalog = catalog();
    TenantResources a = resA().seedAll();
    TenantResources b = resB().seedAll();
    Set<String> bTokens = new LinkedHashSet<>(b.tokens());
    bTokens.add("fx-b-"); // B fikstur uyelerinin ad/e-posta parcasi
    List<String> rows = new ArrayList<>();
    List<DynamicTest> tests = new ArrayList<>();
    for (Operation op : catalog.operations()) {
      if (!op.isRead() || excluded(op)) {
        continue;
      }
      tests.add(
          DynamicTest.dynamicTest(
              "TEN-03 | GET " + op.path(), () -> runTen03(op, a, b, bTokens, rows)));
    }
    tests.add(
        DynamicTest.dynamicTest(
            "TEN-03 | ozet rapor",
            () ->
                writeReport(
                    "TEN-03",
                    "| Uc | R durum | B izi | K durum | A kaniti | Sonuc |\n|---|---|---|---|---|---|\n"
                        + String.join("\n", rows)
                        + "\n\nKabul kaniti uretilemeyen yuzey sayisi: "
                        + TEN03_NO_EVIDENCE.size()
                        + " "
                        + TEN03_NO_EVIDENCE
                        + "\n\n| Uc | K govdesi (kisaltilmis) |\n|---|---|\n"
                        + String.join("\n", BODY_DUMP))));
    return tests.stream();
  }

  private void runTen03(
      Operation op, TenantResources a, TenantResources b, Set<String> bTokens, List<String> rows) {
    Actor actor = a.admin();
    UUID wsA = a.tenant().workspaceId();
    String rPathRaw = fill(op, a, b, B_MARK);
    String rPath =
        TEN03_ACTIVE_SPRINT.contains(op.path())
            ? rPathRaw.replace(a.id(Type.SPRINT).toString(), a.activeSprintId().toString())
            : rPathRaw;
    HttpResponse<String> r = fx.http().get(rPath, actor, wsA, null);
    List<String> leaked = bTokens.stream().filter(t -> r.body().contains(t)).toList();

    String kPathRaw = fill(op, a, a, A_MARK);
    String kPath =
        TEN03_ACTIVE_SPRINT.contains(op.path())
            ? kPathRaw.replace(a.id(Type.SPRINT).toString(), a.activeSprintId().toString())
            : kPathRaw;
    String noEvidence = TEN03_NO_EVIDENCE.get(op.path());
    HttpResponse<String> k = fx.http().get(kPath, actor, wsA, null);
    String evidence = k.statusCode() == 200 ? ownEvidence(op.path(), k.body(), a) : null;
    long deadline = System.currentTimeMillis() + 20_000;
    while (noEvidence == null && evidence == null && System.currentTimeMillis() < deadline) {
      sleep(500); // arama, inbox, analitik projeksiyonlari Kafka tuketicisiyle asenkron beslenir
      k = fx.http().get(kPath, actor, wsA, null);
      evidence = k.statusCode() == 200 ? ownEvidence(op.path(), k.body(), a) : null;
    }
    HttpResponse<String> kFinal = k;
    String evidenceFinal = evidence;
    List<String> leakedK = bTokens.stream().filter(t -> kFinal.body().contains(t)).toList();
    boolean kOk = kFinal.statusCode() == 200 && (noEvidence != null || evidenceFinal != null);
    boolean pass = leaked.isEmpty() && leakedK.isEmpty() && r.statusCode() < 500 && kOk;
    rows.add(
        "| "
            + op
            + " | "
            + r.statusCode()
            + " | "
            + (leaked.isEmpty() && leakedK.isEmpty() ? "yok" : "VAR " + leaked + leakedK)
            + " | "
            + kFinal.statusCode()
            + " | "
            + (evidenceFinal != null
                ? evidenceFinal
                : noEvidence != null ? "kanit uretilemedi: " + noEvidence : "YOK")
            + " | "
            + (pass ? "GECTI" : "KALDI")
            + " |");
    BODY_DUMP.add("| " + op + " | " + abbreviate(kFinal.body()).replace("|", "/") + " |");
    assertAll(
        () ->
            assertTrue(
                leaked.isEmpty(),
                "R: " + rPath + " yanitinda B izi: " + leaked + " " + abbreviate(r.body())),
        () -> assertTrue(leakedK.isEmpty(), "R: " + kPath + " yanitinda B izi: " + leakedK),
        () -> assertTrue(r.statusCode() < 500, "R: " + rPath + " -> " + r.statusCode()),
        () ->
            assertEquals(200, kFinal.statusCode(), "K: " + kPath + " " + abbreviate(kFinal.body())),
        () ->
            assertTrue(
                noEvidence != null || evidenceFinal != null,
                "K: "
                    + kPath
                    + " A'nin kendi verisini kanitlamiyor: "
                    + abbreviate(kFinal.body())));
  }

  private static final List<String> BODY_DUMP = new java.util.concurrent.CopyOnWriteArrayList<>();

  /**
   * TEN-03 kabul kaniti: yanit A'nin verisini gosteriyor mu? Kanit aciklamasi ya da {@code null}.
   * Varsayilan: A'nin isaret dizgesi (metin alani) ya da A'ya ait id/anahtar/e-posta. Yalniz sayi
   * ya da kimliksiz nesne donen yuzeyler {@link #TEN03_NUMERIC} ile A'da bilinen veriye karsi
   * dogrulanir.
   */
  private String ownEvidence(String path, String body, TenantResources a) {
    Function<Ten03Ctx, String> special = TEN03_NUMERIC.get(path);
    if (special != null) {
      return special.apply(new Ten03Ctx(mapper.readTree(body), a));
    }
    if (body.contains(a.marker())) {
      return "isaret";
    }
    return a.tokens().stream()
        .filter(body::contains)
        .findFirst()
        .map(t -> "A verisi " + t)
        .orElse(null);
  }

  private record Ten03Ctx(JsonNode json, TenantResources a) {}

  /**
   * Sayisal / kimliksiz yuzeyler: A'da bilinen (seedAll ile uretilen, cogu DB'den bagimsiz sayilan)
   * veriyle eslesen, sifirdan farkli kanit. Kanit yoksa {@code null}.
   */
  private static final Map<String, Function<Ten03Ctx, String>> TEN03_NUMERIC =
      Map.of(
          "/api/v1/integrations/slack",
          c ->
              sameInstant(
                      c.json().path("createdAt").asString("1970-01-01T00:00:00Z"),
                      c.a().slackCreatedAt())
                  ? "createdAt = A'nin Slack kaydi " + c.a().slackCreatedAt()
                  : null,
          "/api/v1/me/notification-preferences",
          c ->
              c.json().path("emailOnAssign").asBoolean() == c.a().prefEmailOnAssign()
                      && c.json().path("emailOnMention").asBoolean() == c.a().prefEmailOnMention()
                  ? "A ADMIN'inin yazdigi tercih ("
                      + c.a().prefEmailOnAssign()
                      + "/"
                      + c.a().prefEmailOnMention()
                      + ")"
                  : null,
          "/api/v1/me/permissions",
          c ->
              WorkspaceRole.ADMIN.equals(c.json().path("role").asString(""))
                      && perms(c.json())
                          .equals(
                              new TreeSet<>(
                                  CrossTenantAccessHarnessTest.ADR0019_PERMISSIONS.get(
                                      WorkspaceRole.ADMIN)))
                  ? "A'daki uyelik rolu " + WorkspaceRole.ADMIN + " ve izinleri"
                  : null,
          "/api/v1/notifications/unread-count",
          c -> {
            long expected =
                dbCount(
                    "SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL",
                    c.a().admin().userId());
            long count = c.json().path("count").asLong(-1);
            return expected > 0 && count == expected ? "count=" + count + " (DB)" : null;
          },
          "/api/v1/projects/{projectId}/analytics/cycle-time",
          c ->
              c.json().path("sampleSize").asInt() == TenantResources.COMPLETED_TASKS
                  ? "sampleSize=" + TenantResources.COMPLETED_TASKS
                  : null,
          "/api/v1/projects/{projectId}/analytics/throughput",
          c -> {
            int sum = 0;
            for (JsonNode w : c.json().path("weeks")) {
              sum += w.path("completedTasks").asInt();
            }
            return sum == TenantResources.COMPLETED_TASKS ? "toplam completedTasks=" + sum : null;
          },
          "/api/v1/projects/{projectId}/automation-rules",
          c -> {
            for (JsonNode rule : c.json()) {
              if (TenantResources.AUTOMATION_TEMPLATE.equals(rule.path("templateKey").asString(""))
                  && rule.path("configured").asBoolean()
                  && rule.path("enabled").asBoolean()) {
                return "A projesinde kayitli " + TenantResources.AUTOMATION_TEMPLATE;
              }
            }
            return null;
          },
          "/api/v1/projects/{projectId}/forecast/backlog",
          c -> {
            long expected =
                dbCount(
                    "SELECT count(*) FROM tasks WHERE project_id = ? AND status <> 'Done'",
                    c.a().id(Type.PROJECT));
            long remaining = c.json().path("remainingItems").asLong(-1);
            return expected > 0 && remaining == expected
                ? "remainingItems=" + remaining + " (DB)"
                : null;
          },
          "/api/v1/sprints/{sprintId}/forecast",
          c -> {
            long expected =
                dbCount(
                    "SELECT count(*) FROM tasks WHERE sprint_id = ? AND status <> 'Done'",
                    c.a().activeSprintId());
            long remaining = c.json().path("remainingItems").asLong(-1);
            return expected > 0 && remaining == expected
                ? "aktif sprint remainingItems=" + remaining + " (DB)"
                : null;
          });

  /**
   * Bu yuzeylerde {@code {sprintId}} tamamlanmis {@code SPRINT} yerine A'nin aktif sprintiyle
   * doldurulur (tamamlanmis sprintin tahmini tanim geregi bostur).
   */
  private static final Set<String> TEN03_ACTIVE_SPRINT =
      Set.of("/api/v1/sprints/{sprintId}/forecast");

  /**
   * PUT yanitindaki an nanosaniye hassasiyetli, DB'den okunan an Postgres'in mikro saniyeye
   * YUVARLADIGI degerdir; 1 mikro saniyeye kadar fark ayni an sayilir.
   */
  private static boolean sameInstant(String a, String b) {
    long nanos = java.time.Duration.between(Instant.parse(a), Instant.parse(b)).abs().toNanos();
    return nanos <= 1_000;
  }

  /** Duz JDBC sayim, {@code app_migrator} (tablo sahibi) baglantisiyla. */
  private static long dbCount(String sql, Object param) {
    try (Connection c = openMigratorConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, param);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Hicbir yolla kabul kaniti uretilemeyen yuzeyler (gerekceli). K bunlarda yalniz 200 ister. */
  private static final Map<String, String> TEN03_NO_EVIDENCE = Map.of();

  // =========================================================================================
  // TEN-04 / TEN-10 govdede (ya da karisik path'te) B id'si
  // =========================================================================================

  private record Pair(TenantResources a, TenantResources b) {}

  private record BodyIdCase(
      String id,
      String name,
      String method,
      Function<Pair, String> rPath,
      Function<Pair, String> rBody,
      Function<Pair, String> kPath,
      Function<Pair, String> kBody) {}

  private static String bulk(List<UUID> ids, String rest) {
    return "{\"taskIds\":["
        + ids.stream().map(i -> "\"" + i + "\"").collect(Collectors.joining(","))
        + "],"
        + rest
        + "}";
  }

  private static String task(TenantResources s, Type t) {
    return "/api/v1/tasks/" + s.id(t);
  }

  @TestFactory
  @DisplayName("TEN-04 | govdede/karisik path'te B id'si reddedilir, kismi degisiklik yok")
  Stream<DynamicTest> ten04_foreignIdsInBodyAreRejectedAtomically() {
    List<BodyIdCase> cases =
        List.of(
            new BodyIdCase(
                "TEN-04",
                "toplu STATUS [A gorevi, B gorevi]",
                "POST",
                p -> "/api/v1/tasks/bulk",
                p ->
                    bulk(
                        List.of(p.a().id(Type.TASK), p.b().id(Type.TASK)),
                        "\"operation\":\"STATUS\",\"status\":\"In Progress\""),
                p -> "/api/v1/tasks/bulk",
                p ->
                    bulk(
                        List.of(p.a().id(Type.TASK), p.a().id(Type.OTHER_TASK)),
                        "\"operation\":\"STATUS\",\"status\":\"In Progress\"")),
            new BodyIdCase(
                "TEN-04",
                "toplu SPRINT (B sprint'i)",
                "POST",
                p -> "/api/v1/tasks/bulk",
                p ->
                    bulk(
                        List.of(p.a().id(Type.TASK)),
                        "\"operation\":\"SPRINT\",\"sprintId\":\"" + p.b().id(Type.SPRINT) + "\""),
                p -> "/api/v1/tasks/bulk",
                p ->
                    bulk(
                        List.of(p.a().id(Type.TASK)),
                        "\"operation\":\"SPRINT\",\"sprintId\":\"" + p.a().id(Type.SPRINT) + "\"")),
            new BodyIdCase(
                "TEN-04",
                "toplu ASSIGNEE (B uyesi)",
                "POST",
                p -> "/api/v1/tasks/bulk",
                p ->
                    bulk(
                        List.of(p.a().id(Type.TASK)),
                        "\"operation\":\"ASSIGNEE\",\"assigneeId\":\""
                            + p.b().admin().userId()
                            + "\""),
                p -> "/api/v1/tasks/bulk",
                p ->
                    bulk(
                        List.of(p.a().id(Type.TASK)),
                        "\"operation\":\"ASSIGNEE\",\"assigneeId\":\""
                            + p.a().admin().userId()
                            + "\"")),
            new BodyIdCase(
                "TEN-04",
                "toplu ADD_TAG (B etiketi)",
                "POST",
                p -> "/api/v1/tasks/bulk",
                p ->
                    bulk(
                        List.of(p.a().id(Type.TASK)),
                        "\"operation\":\"ADD_TAG\",\"tagId\":\"" + p.b().id(Type.TAG) + "\""),
                p -> "/api/v1/tasks/bulk",
                p ->
                    bulk(
                        List.of(p.a().id(Type.TASK)),
                        "\"operation\":\"ADD_TAG\",\"tagId\":\"" + p.a().id(Type.TAG) + "\"")),
            new BodyIdCase(
                "TEN-04",
                "bagimlilik: A gorevi <- B gorevi",
                "PUT",
                p -> task(p.a(), Type.TASK) + "/dependencies/" + p.b().id(Type.TASK),
                p -> null,
                p -> task(p.a(), Type.TASK) + "/dependencies/" + p.a().id(Type.OTHER_TASK),
                p -> null),
            new BodyIdCase(
                "TEN-04",
                "bagimlilik: B gorevi <- A gorevi",
                "PUT",
                p -> task(p.b(), Type.TASK) + "/dependencies/" + p.a().id(Type.TASK),
                p -> null,
                p -> task(p.a(), Type.OTHER_TASK) + "/dependencies/" + p.a().id(Type.TASK),
                p -> null),
            new BodyIdCase(
                "TEN-04",
                "parent: A gorevinin ustu B gorevi",
                "PUT",
                p -> task(p.a(), Type.TASK) + "/parent/" + p.b().id(Type.TASK),
                p -> null,
                p -> task(p.a(), Type.TASK) + "/parent/" + p.a().id(Type.OTHER_TASK),
                p -> null),
            new BodyIdCase(
                "TEN-04",
                "etiket atama: A gorevine B etiketi",
                "PUT",
                p -> task(p.a(), Type.TASK) + "/tags/" + p.b().id(Type.TAG),
                p -> null,
                p -> task(p.a(), Type.TASK) + "/tags/" + p.a().id(Type.TAG),
                p -> null),
            new BodyIdCase(
                "TEN-04",
                "sprint'e tasima: A gorevi B sprint'ine",
                "PUT",
                p -> task(p.a(), Type.TASK) + "/sprint",
                p -> "{\"sprintId\":\"" + p.b().id(Type.SPRINT) + "\"}",
                p -> task(p.a(), Type.TASK) + "/sprint",
                p -> "{\"sprintId\":\"" + p.a().id(Type.SPRINT) + "\"}"),
            new BodyIdCase(
                "TEN-04",
                "atanan: A gorevine B uyesi",
                "PUT",
                p -> task(p.a(), Type.TASK) + "/assignee",
                p -> "{\"assigneeId\":\"" + p.b().admin().userId() + "\"}",
                p -> task(p.a(), Type.TASK) + "/assignee",
                p -> "{\"assigneeId\":\"" + p.a().admin().userId() + "\"}"),
            new BodyIdCase(
                "TEN-04",
                "hedef olusturma: B projesi",
                "POST",
                p -> "/api/v1/goals",
                p -> goalJson(p.b().id(Type.PROJECT)),
                p -> "/api/v1/goals",
                p -> goalJson(p.a().id(Type.PROJECT))),
            new BodyIdCase(
                "TEN-04",
                "hedef guncelleme: B projesi",
                "PUT",
                p -> "/api/v1/goals/" + p.a().id(Type.GOAL),
                p ->
                    "{\"title\":\"G\",\"targetValue\":5,\"projectId\":\""
                        + p.b().id(Type.PROJECT)
                        + "\"}",
                p -> "/api/v1/goals/" + p.a().id(Type.GOAL),
                p ->
                    "{\"title\":\"G\",\"targetValue\":5,\"projectId\":\""
                        + p.a().id(Type.PROJECT)
                        + "\"}"));
    return cases.stream()
        .map(c -> DynamicTest.dynamicTest(c.id() + " | " + c.name(), () -> runBodyIdCase(c)));
  }

  private static String goalJson(UUID projectId) {
    return "{\"title\":\"G\",\"metricType\":\"COMPLETED_TASKS\",\"targetValue\":10,\"year\":2099,"
        + "\"projectId\":\""
        + projectId
        + "\"}";
  }

  @TestFactory
  @DisplayName("TEN-10 | alt gorev / genisletilmis gorev olusturmada B id'si reddedilir")
  Stream<DynamicTest> ten10_taskCreationWithForeignIdsRejected() {
    List<BodyIdCase> cases =
        List.of(
            new BodyIdCase(
                "TEN-10",
                "alt gorev: B parent",
                "POST",
                p -> task(p.b(), Type.TASK) + "/subtasks",
                p -> "{\"title\":\"alt\"}",
                p -> task(p.a(), Type.TASK) + "/subtasks",
                p -> "{\"title\":\"alt\"}"),
            new BodyIdCase(
                "TEN-10",
                "alt gorev: B atanan",
                "POST",
                p -> task(p.a(), Type.TASK) + "/subtasks",
                p -> "{\"title\":\"alt\",\"assigneeId\":\"" + p.b().admin().userId() + "\"}",
                p -> task(p.a(), Type.TASK) + "/subtasks",
                p -> "{\"title\":\"alt\",\"assigneeId\":\"" + p.a().admin().userId() + "\"}"),
            new BodyIdCase(
                "TEN-10",
                "alt gorev: B etiketi",
                "POST",
                p -> task(p.a(), Type.TASK) + "/subtasks",
                p -> "{\"title\":\"alt\",\"tagIds\":[\"" + p.b().id(Type.TAG) + "\"]}",
                p -> task(p.a(), Type.TASK) + "/subtasks",
                p -> "{\"title\":\"alt\",\"tagIds\":[\"" + p.a().id(Type.TAG) + "\"]}"),
            new BodyIdCase(
                "TEN-10",
                "gorev: B atanan",
                "POST",
                p -> "/api/v1/projects/" + p.a().id(Type.PROJECT) + "/tasks",
                p -> "{\"title\":\"g\",\"assigneeId\":\"" + p.b().admin().userId() + "\"}",
                p -> "/api/v1/projects/" + p.a().id(Type.PROJECT) + "/tasks",
                p -> "{\"title\":\"g\",\"assigneeId\":\"" + p.a().admin().userId() + "\"}"),
            new BodyIdCase(
                "TEN-10",
                "gorev: B sprint'i",
                "POST",
                p -> "/api/v1/projects/" + p.a().id(Type.PROJECT) + "/tasks",
                p -> "{\"title\":\"g\",\"sprintId\":\"" + p.b().id(Type.SPRINT) + "\"}",
                p -> "/api/v1/projects/" + p.a().id(Type.PROJECT) + "/tasks",
                p -> "{\"title\":\"g\",\"sprintId\":\"" + p.a().id(Type.SPRINT) + "\"}"),
            new BodyIdCase(
                "TEN-10",
                "gorev: B etiketi",
                "POST",
                p -> "/api/v1/projects/" + p.a().id(Type.PROJECT) + "/tasks",
                p -> "{\"title\":\"g\",\"tagIds\":[\"" + p.b().id(Type.TAG) + "\"]}",
                p -> "/api/v1/projects/" + p.a().id(Type.PROJECT) + "/tasks",
                p -> "{\"title\":\"g\",\"tagIds\":[\"" + p.a().id(Type.TAG) + "\"]}"));
    return cases.stream()
        .map(c -> DynamicTest.dynamicTest(c.id() + " | " + c.name(), () -> runBodyIdCase(c)));
  }

  private void runBodyIdCase(BodyIdCase c) throws Exception {
    Pair p = new Pair(resA(), resB());
    Actor actor = p.a().admin();
    UUID wsA = p.a().tenant().workspaceId();
    String rPath = c.rPath().apply(p);
    String rBody = c.rBody().apply(p);
    String kPath = c.kPath().apply(p);
    String kBody = c.kBody().apply(p);
    String aBefore = p.a().snapshot();
    String bBefore = p.b().snapshot();
    long aCount = taskCount(wsA);
    long bCount = taskCount(p.b().tenant().workspaceId());

    HttpResponse<String> r = send(c.method(), rPath, actor, wsA, rBody);
    String aAfter = p.a().snapshot();
    String bAfter = p.b().snapshot();
    long aCountAfter = taskCount(wsA);
    long bCountAfter = taskCount(p.b().tenant().workspaceId());

    HttpResponse<String> k = send(c.method(), kPath, actor, wsA, kBody);
    record(
        c.id(),
        c.name(),
        "R "
            + r.statusCode()
            + ", A degisti="
            + !aBefore.equals(aAfter)
            + ", B degisti="
            + !bBefore.equals(bAfter)
            + ", gorev sayisi A "
            + aCount
            + "->"
            + aCountAfter
            + " B "
            + bCount
            + "->"
            + bCountAfter
            + "; K "
            + k.statusCode());
    assertAll(
        () ->
            assertTrue(
                r.statusCode() / 100 == 4 && r.statusCode() != 401,
                "R: "
                    + c.method()
                    + " "
                    + rPath
                    + " reddedilmeliydi (4xx), gelen "
                    + r.statusCode()
                    + " "
                    + abbreviate(r.body())),
        () -> assertEquals(aBefore, aAfter, "R: A'da kismi degisiklik kaldi"),
        () -> assertEquals(bBefore, bAfter, "R: B'nin kaynagi degisti"),
        () -> assertEquals(aCount, aCountAfter, "R: A'nin gorev sayisi degisti"),
        () -> assertEquals(bCount, bCountAfter, "R: B'nin gorev sayisi degisti"),
        () ->
            assertTrue(
                k.statusCode() / 100 == 2,
                "K: yalniz A id'leriyle "
                    + c.method()
                    + " "
                    + kPath
                    + " basarili olmaliydi, gelen "
                    + k.statusCode()
                    + " "
                    + abbreviate(k.body())));
  }

  /** Gorev sayisi; duz JDBC, {@code app_migrator} (tablo sahibi, RLS'ten muaf) ile. */
  private static long taskCount(UUID workspaceId) throws Exception {
    try (Connection c = openMigratorConnection();
        PreparedStatement ps =
            c.prepareStatement("SELECT count(*) FROM tasks WHERE workspace_id = ?")) {
      ps.setObject(1, workspaceId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  // =========================================================================================
  // TEN-05 RLS'siz tablolar: uyeler, davetler, bildirim tercihleri, PAT
  // =========================================================================================

  @Test
  @DisplayName("TEN-05 | uye listesi: B uyesi gorunmez; A uyesi gorunur")
  void ten05_memberListIsolated() {
    TenantResources a = resA();
    TenantResources b = resB();
    Actor bMember = b.member();
    Actor aMember = a.member();
    HttpResponse<String> list =
        fx.http().get("/api/v1/workspaces/members", a.admin(), a.tenant().workspaceId(), null);
    record("TEN-05", "uye listesi", "R/K " + list.statusCode());
    assertAll(
        () -> assertEquals(200, list.statusCode()),
        () ->
            assertFalse(
                list.body().contains(bMember.userId().toString()), "B uyesinin id'si A listesinde"),
        () ->
            assertFalse(list.body().contains(bMember.email()), "B uyesinin e-postasi A listesinde"),
        () ->
            assertFalse(
                list.body().contains(fx.b().admin().userId().toString()), "B ADMIN'i A listesinde"),
        () ->
            assertTrue(
                list.body().contains(aMember.userId().toString()), "K: A uyesi listede yok"));
  }

  @Test
  @DisplayName("TEN-05 | uye kaydi: B uyesinin rolu degistirilemez/cikarilamaz; A uyesininki olur")
  void ten05_foreignMemberNotModifiable() {
    TenantResources a = resA();
    TenantResources b = resB();
    UUID wsA = a.tenant().workspaceId();
    Actor bMember = b.member();
    String bBefore = b.snapshot();
    HttpResponse<String> patch =
        fx.http()
            .patch(
                "/api/v1/workspaces/members/" + bMember.userId(),
                a.admin(),
                wsA,
                "{\"role\":\"VIEWER\"}");
    HttpResponse<String> del =
        fx.http().delete("/api/v1/workspaces/members/" + bMember.userId(), a.admin(), wsA, null);
    String bAfter = b.snapshot();
    Actor aMember = a.member();
    HttpResponse<String> kPatch =
        fx.http()
            .patch(
                "/api/v1/workspaces/members/" + aMember.userId(),
                a.admin(),
                wsA,
                "{\"role\":\"VIEWER\"}");
    HttpResponse<String> kDel =
        fx.http().delete("/api/v1/workspaces/members/" + aMember.userId(), a.admin(), wsA, null);
    record(
        "TEN-05",
        "uye degistir/cikar",
        "R PATCH "
            + patch.statusCode()
            + " DELETE "
            + del.statusCode()
            + "; K PATCH "
            + kPatch.statusCode()
            + " DELETE "
            + kDel.statusCode());
    assertAll(
        () ->
            assertTrue(TENANT_DENIED.contains(patch.statusCode()), "R PATCH " + patch.statusCode()),
        () -> assertTrue(TENANT_DENIED.contains(del.statusCode()), "R DELETE " + del.statusCode()),
        () -> assertEquals(bBefore, bAfter, "B'nin uyeligi degisti"),
        () -> assertEquals(2, kPatch.statusCode() / 100, "K PATCH " + kPatch.statusCode()),
        () -> assertEquals(2, kDel.statusCode() / 100, "K DELETE " + kDel.statusCode()));
  }

  @Test
  @DisplayName("TEN-05 | davetler: B daveti gorunmez/iptal edilemez; A daveti gorunur/iptal edilir")
  void ten05_invitationsIsolated() {
    TenantResources a = resA();
    TenantResources b = resB();
    UUID wsA = a.tenant().workspaceId();
    UUID bInv = b.id(Type.INVITATION);
    UUID aInv = a.id(Type.INVITATION);
    HttpResponse<String> list =
        fx.http().get("/api/v1/workspaces/members/invitations", a.admin(), wsA, null);
    String bBefore = b.snapshot();
    HttpResponse<String> del =
        fx.http().delete("/api/v1/workspaces/members/invitations/" + bInv, a.admin(), wsA, null);
    String bAfter = b.snapshot();
    HttpResponse<String> kDel =
        fx.http().delete("/api/v1/workspaces/members/invitations/" + aInv, a.admin(), wsA, null);
    record(
        "TEN-05",
        "davetler",
        "liste "
            + list.statusCode()
            + ", R DELETE "
            + del.statusCode()
            + ", K DELETE "
            + kDel.statusCode());
    assertAll(
        () -> assertEquals(200, list.statusCode()),
        () -> assertFalse(list.body().contains(bInv.toString()), "B daveti A listesinde"),
        () -> assertTrue(list.body().contains(aInv.toString()), "K: A daveti listede yok"),
        () -> assertTrue(TENANT_DENIED.contains(del.statusCode()), "R DELETE " + del.statusCode()),
        () -> assertEquals(bBefore, bAfter, "B'nin daveti degisti"),
        () -> assertEquals(2, kDel.statusCode() / 100, "K DELETE " + kDel.statusCode()));
  }

  @Test
  @DisplayName("TEN-05 | bildirim tercihleri: A kullanicisi B'nin tercihini goremez/degistiremez")
  void ten05_notificationPreferencesIsolated() {
    Actor aUser = fx.addMember(fx.a(), WorkspaceRole.DEVELOPER, "ten05-pref-a");
    Actor bUser = fx.addMember(fx.b(), WorkspaceRole.DEVELOPER, "ten05-pref-b");
    UUID wsA = fx.a().workspaceId();
    UUID wsB = fx.b().workspaceId();
    String path = "/api/v1/me/notification-preferences";
    HttpResponse<String> bBefore = fx.http().get(path, bUser, wsB, null);
    HttpResponse<String> foreignRead = fx.http().get(path, aUser, wsB, null);
    HttpResponse<String> foreignWrite =
        fx.http().put(path, aUser, wsB, "{\"emailOnAssign\":false,\"emailOnMention\":false}");
    HttpResponse<String> ownWrite =
        fx.http().put(path, aUser, wsA, "{\"emailOnAssign\":false,\"emailOnMention\":false}");
    HttpResponse<String> bAfter = fx.http().get(path, bUser, wsB, null);
    HttpResponse<String> ownRead = fx.http().get(path, aUser, wsA, null);
    record(
        "TEN-05",
        "bildirim tercihleri",
        "R ws=B GET "
            + foreignRead.statusCode()
            + " PUT "
            + foreignWrite.statusCode()
            + "; K PUT "
            + ownWrite.statusCode()
            + " GET "
            + ownRead.body());
    assertAll(
        () -> assertEquals(200, bBefore.statusCode()),
        () -> assertEquals(403, foreignRead.statusCode(), "R: B workspace'iyle tercih okuma"),
        () -> assertEquals(403, foreignWrite.statusCode(), "R: B workspace'iyle tercih yazma"),
        () -> assertEquals(bBefore.body(), bAfter.body(), "R: B kullanicisinin tercihi degisti"),
        () -> assertEquals(2, ownWrite.statusCode() / 100, "K: kendi tercihi yazilamadi"),
        () ->
            assertTrue(
                ownRead.body().contains("\"emailOnAssign\":false"),
                "K: kendi tercihi okunmadi: " + ownRead.body()));
  }

  @Test
  @DisplayName("TEN-05 | PAT: B'nin token'i A listesinde yok, iptal edilemez; A'ninki edilir")
  void ten05_personalAccessTokensIsolated() {
    TenantResources a = resA();
    TenantResources b = resB();
    UUID bPat = b.id(Type.PAT);
    UUID aPat = a.id(Type.PAT);
    HttpResponse<String> list = fx.http().get("/api/v1/access-tokens", a.admin(), null, null);
    HttpResponse<String> del =
        fx.http().delete("/api/v1/access-tokens/" + bPat, a.admin(), (UUID) null, null);
    HttpResponse<String> bList = fx.http().get("/api/v1/access-tokens", b.admin(), null, null);
    HttpResponse<String> kDel =
        fx.http().delete("/api/v1/access-tokens/" + aPat, a.admin(), (UUID) null, null);
    record("TEN-05", "PAT", "R DELETE " + del.statusCode() + "; K DELETE " + kDel.statusCode());
    assertAll(
        () -> assertFalse(list.body().contains(bPat.toString()), "B PAT'i A listesinde"),
        () -> assertFalse(list.body().contains(B_MARK), "B PAT adi A listesinde"),
        () -> assertTrue(list.body().contains(aPat.toString()), "K: A PAT'i listede yok"),
        () -> assertTrue(TENANT_DENIED.contains(del.statusCode()), "R DELETE " + del.statusCode()),
        () ->
            assertTrue(
                bList.body().contains(bPat.toString())
                    && !bList.body().contains("\"revokedAt\":\""),
                "B PAT'i iptal edilmis/silinmis: " + abbreviate(bList.body())),
        () -> assertEquals(2, kDel.statusCode() / 100, "K DELETE " + kDel.statusCode()));
  }

  // =========================================================================================
  // TEN-06 gecersiz X-Workspace-Id
  // =========================================================================================

  @TestFactory
  @DisplayName("TEN-06 | gecersiz X-Workspace-Id -> 400/403, 500 degil, stack trace yok")
  Stream<DynamicTest> ten06_invalidWorkspaceHeader() {
    Actor admin = fx.a().admin();
    String wsA = fx.a().workspaceId().toString();
    String wsB = fx.b().workspaceId().toString();
    Map<String, List<Map.Entry<String, String>>> variants = new LinkedHashMap<>();
    variants.put("UUID degil", List.of(Map.entry("X-Workspace-Id", "not-a-uuid")));
    variants.put("bos", List.of(Map.entry("X-Workspace-Id", "")));
    variants.put(
        "cok uzun (2000 karakter)", List.of(Map.entry("X-Workspace-Id", "a".repeat(2000))));
    variants.put("UUID + fazla karakter", List.of(Map.entry("X-Workspace-Id", wsA + "0")));
    variants.put(
        "iki baslik (A, B)",
        List.of(Map.entry("X-Workspace-Id", wsA), Map.entry("X-Workspace-Id", wsB)));
    variants.put(
        "iki baslik (B, A)",
        List.of(Map.entry("X-Workspace-Id", wsB), Map.entry("X-Workspace-Id", wsA)));
    List<DynamicTest> tests = new ArrayList<>();
    variants.forEach(
        (name, headers) ->
            tests.add(
                DynamicTest.dynamicTest(
                    "TEN-06 | " + name,
                    () -> {
                      HttpResponse<String> r =
                          fx.http()
                              .sendWithHeaders(
                                  "GET", "/api/v1/projects", admin.accessToken(), headers, null);
                      record("TEN-06", name, r.statusCode() + " " + abbreviate(r.body()));
                      assertAll(
                          () ->
                              assertTrue(
                                  r.statusCode() == 400 || r.statusCode() == 403,
                                  name + ": 400/403 beklenirdi, gelen " + r.statusCode()),
                          () ->
                              assertFalse(
                                  leaksInternals(r.body()),
                                  name + ": govdede ic bilgi: " + abbreviate(r.body())));
                    })));
    tests.add(
        DynamicTest.dynamicTest(
            "TEN-06 | K gecerli uyelik basligi 200",
            () ->
                assertEquals(
                    200,
                    fx.http()
                        .get("/api/v1/projects", admin, fx.a().workspaceId(), null)
                        .statusCode())));
    return tests.stream();
  }

  // =========================================================================================
  // TEN-09 mention ile kiracilar arasi bildirim
  // =========================================================================================

  @Test
  @DisplayName(
      "TEN-09 | A yorumunda B uyesinin mention'i bildirim/e-posta uretmez; A uyesininki uretir")
  void ten09_crossTenantMentionDoesNotNotify() throws Exception {
    TenantResources a = resA();
    UUID wsA = a.tenant().workspaceId();
    Actor aMember = fx.addMember(fx.a(), WorkspaceRole.DEVELOPER, "ten09-a");
    Actor bMember = fx.addMember(fx.b(), WorkspaceRole.DEVELOPER, "ten09-b");
    UUID taskId = a.id(Type.TASK);
    long start = System.currentTimeMillis();
    HttpResponse<String> comment =
        fx.http()
            .post(
                "/api/v1/tasks/" + taskId + "/comments",
                a.admin(),
                wsA,
                "{\"body\":\"bakar misiniz @["
                    + aMember.userId()
                    + "] ve @["
                    + bMember.userId()
                    + "]\"}");
    assertEquals(201, comment.statusCode(), comment.body());

    boolean aInbox = false;
    long deadline = start + 30_000;
    while (!aInbox && System.currentTimeMillis() < deadline) {
      aInbox =
          fx.http()
              .get("/api/v1/notifications", aMember, wsA, null)
              .body()
              .contains(taskId.toString());
      if (!aInbox) {
        sleep(250);
      }
    }
    long inboxElapsed = System.currentTimeMillis() - start;
    boolean aMail = false;
    while (!aMail && System.currentTimeMillis() < deadline) {
      aMail = mentionMailCount(aMember.email()) > 0;
      if (!aMail) {
        sleep(250);
      }
    }
    long elapsed = System.currentTimeMillis() - start;
    // Ret tarafi: kabul esinin bekledigi sure kadar daha bekle, sonra yoklugu dogrula.
    sleep(Math.max(elapsed, 2_000));
    long bNotifications = notificationCount(bMember.userId());
    int bMail = mentionMailCount(bMember.email());
    record(
        "TEN-09",
        "mention",
        "K inbox="
            + aInbox
            + " ("
            + inboxElapsed
            + " ms) mail="
            + aMail
            + " ("
            + elapsed
            + " ms); R B bildirim="
            + bNotifications
            + " B mail="
            + bMail);
    boolean aInboxF = aInbox;
    boolean aMailF = aMail;
    assertAll(
        () -> assertTrue(aInboxF, "K: A uyesine mention bildirimi gelmedi"),
        () -> assertTrue(aMailF, "K: A uyesine mention e-postasi gelmedi"),
        () -> assertEquals(0, bNotifications, "R: B uyesine bildirim uretildi"),
        () -> assertEquals(0, bMail, "R: B uyesine e-posta gitti"));
  }

  private static long notificationCount(UUID userId) throws Exception {
    try (Connection c = openMigratorConnection();
        PreparedStatement ps =
            c.prepareStatement("SELECT count(*) FROM notifications WHERE user_id = ?")) {
      ps.setObject(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** Konusu mention sablonuyla ("Bir yorumda etiketlendin") eslesen, aliciya giden postalar. */
  private int mentionMailCount(String email) throws Exception {
    HttpResponse<String> r =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(mailpitApiUrl("/api/v1/messages?limit=1000")))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    int n = 0;
    for (JsonNode msg : mapper.readTree(r.body()).path("messages")) {
      if (!msg.path("Subject").asString("").contains("etiketlendin")) {
        continue; // kayit dogrulama postasi vb. sayilmaz
      }
      for (JsonNode to : msg.path("To")) {
        if (email.equalsIgnoreCase(to.path("Address").asString(""))) {
          n++;
        }
      }
    }
    return n;
  }

  // =========================================================================================
  // AUTHZ-14 /me/permissions
  // =========================================================================================

  @Test
  @DisplayName("AUTHZ-14 | /me/permissions: uye olunmayan ws 403, basliksiz 400")
  void authz14_permissionsRejectsForeignOrMissingWorkspace() {
    Actor admin = fx.a().admin();
    HttpResponse<String> foreign =
        fx.http().get("/api/v1/me/permissions", admin, fx.b().workspaceId(), null);
    HttpResponse<String> missing = fx.http().get("/api/v1/me/permissions", admin, null, null);
    record("AUTHZ-14", "R", "ws=B " + foreign.statusCode() + ", basliksiz " + missing.statusCode());
    assertAll(
        () -> assertEquals(403, foreign.statusCode()),
        () -> assertEquals(400, missing.statusCode(), missing.body()));
  }

  /**
   * ADR-0019 karar 2'deki izin tablosu, ELLE yazildi (koddan/enum'dan turetilmez): eksik de fazla
   * da testi kirar.
   */
  private static final Map<String, Set<String>> ADR0019_PERMISSIONS =
      Map.of(
          WorkspaceRole.ADMIN,
          Set.of(
              "TASK_CREATE",
              "TASK_EDIT",
              "TASK_PLAN",
              "TASK_SELF_ASSIGN",
              "SUBTASK_CREATE_OWN",
              "TASK_APPROVE",
              "TASK_DELETE",
              "COMMENT_WRITE",
              "COMMENT_MODERATE",
              "RETRO_ITEM_WRITE",
              "PROJECT_CREATE",
              "SPRINT_MANAGE",
              "TAG_MANAGE",
              "MEETING_MANAGE",
              "GOAL_MANAGE",
              "AUTOMATION_MANAGE",
              "FEEDBACK_READ",
              "MEMBER_MANAGE",
              "INTEGRATION_MANAGE"),
          WorkspaceRole.MANAGER,
          Set.of(
              "TASK_CREATE",
              "TASK_EDIT",
              "TASK_PLAN",
              "TASK_SELF_ASSIGN",
              "SUBTASK_CREATE_OWN",
              "TASK_APPROVE",
              "COMMENT_WRITE",
              "RETRO_ITEM_WRITE",
              "PROJECT_CREATE",
              "SPRINT_MANAGE",
              "TAG_MANAGE",
              "MEETING_MANAGE",
              "GOAL_MANAGE",
              "AUTOMATION_MANAGE",
              "FEEDBACK_READ"),
          WorkspaceRole.DEVELOPER,
          Set.of(
              "TASK_EDIT",
              "TASK_SELF_ASSIGN",
              "SUBTASK_CREATE_OWN",
              "COMMENT_WRITE",
              "RETRO_ITEM_WRITE"),
          WorkspaceRole.VIEWER,
          Set.of());

  @TestFactory
  @DisplayName("AUTHZ-14 | /me/permissions: uye olunan ws 200 ve ADR-0019 ile birebir izinler")
  Stream<DynamicTest> authz14_permissionsMatchRole() {
    UUID wsA = fx.a().workspaceId();
    return ADR0019_PERMISSIONS.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            e ->
                DynamicTest.dynamicTest(
                    "AUTHZ-14 | K " + e.getKey(),
                    () -> {
                      Actor actor = fx.a().actors().get(e.getKey());
                      HttpResponse<String> r =
                          fx.http().get("/api/v1/me/permissions", actor, wsA, null);
                      record("AUTHZ-14", "K " + e.getKey(), r.statusCode() + " " + r.body());
                      assertEquals(200, r.statusCode(), r.body());
                      JsonNode body = mapper.readTree(r.body());
                      assertAll(
                          () -> assertEquals(e.getKey(), body.path("role").asString("")),
                          () ->
                              assertEquals(
                                  new TreeSet<>(e.getValue()),
                                  perms(body),
                                  e.getKey() + " izin kumesi ADR-0019 ile birebir esit olmali"));
                    }));
  }

  private static Set<String> perms(JsonNode node) {
    Set<String> s = new TreeSet<>();
    node.path("permissions").forEach(p -> s.add(p.asString("")));
    return s;
  }

  // =========================================================================================
  // yardimcilar
  // =========================================================================================

  /** Path parametresinin esleme anahtari; yoksa {@code null}. */
  private static Object resolveParam(String path, String param) {
    String token = "{" + param + "}";
    String[] parts = path.split("/");
    for (int i = 1; i < parts.length; i++) {
      if (parts[i].equals(token)) {
        String scoped = parts[i - 1] + "/" + token;
        if (PATH_PARAMS.containsKey(scoped)) {
          return PATH_PARAMS.get(scoped);
        }
      }
    }
    if (PATH_PARAMS.containsKey(token)) {
      return PATH_PARAMS.get(token);
    }
    return PATH_LITERALS.get(token);
  }

  /**
   * Path ve sorgu dizgesi: path id'leri {@code pathSet}'ten, id tasiyan sorgu parametreleri {@code
   * querySet}'ten, {@code q} {@code searchText} ile.
   */
  private static String fill(
      Operation op, TenantResources pathSet, TenantResources querySet, String searchText) {
    String path = op.path();
    for (String p : op.pathParams()) {
      Object m = resolveParam(op.path(), p);
      String value = m instanceof Type t ? pathSet.id(t).toString() : (String) m;
      path = path.replace("{" + p + "}", value);
    }
    List<String> query = new ArrayList<>();
    for (QueryParam q : op.queryParams()) {
      if (QUERY_IDS.containsKey(q.name())) {
        query.add(q.name() + "=" + querySet.id(QUERY_IDS.get(q.name())));
      } else if ("q".equals(q.name())) {
        query.add("q=" + searchText);
      } else if ("cutoff".equals(q.name())) {
        query.add("cutoff=" + Instant.now());
      } else if (QUERY_LITERALS.containsKey(q.name())) {
        query.add(q.name() + "=" + QUERY_LITERALS.get(q.name()));
      } else if (q.required()) {
        throw new AssertionError("zorunlu sorgu parametresi uretilemedi: " + op + " ?" + q.name());
      }
    }
    return query.isEmpty() ? path : path + "?" + String.join("&", query);
  }

  /** Govde sorunu; sorun yoksa {@code null}. */
  private String bodyProblem(OpenApiCatalog catalog, Operation op) {
    if (op.bodySchema() == null) {
      return null;
    }
    Function<TenantResources, String> template = BODIES.get(op.bodySchema());
    if (template == null) {
      return "sema " + op.bodySchema() + " icin govde sablonu yok";
    }
    Schema schema = catalog.schema(op.bodySchema());
    if (schema == null) {
      return "sema " + op.bodySchema() + " OpenAPI'de yok";
    }
    Set<String> keys = templateKeys(op.bodySchema());
    if (!schema.properties().containsAll(keys)) {
      return "sablonda semada olmayan alan: " + keys + " vs " + schema.properties();
    }
    if (!keys.containsAll(schema.required())) {
      return "sablonda zorunlu alan eksik: " + schema.required();
    }
    return null;
  }

  /** Sablonun yazdigi alan adlari (A kiracisinin kaynak setiyle uretilmis ornek govdeden). */
  private Set<String> templateKeys(String schemaName) {
    String json = BODIES.get(schemaName).apply(resA());
    Set<String> keys = new LinkedHashSet<>();
    mapper.readTree(json).propertyNames().forEach(keys::add);
    return keys;
  }

  private String body(Operation op, TenantResources actorSet) {
    return op.bodySchema() == null ? null : BODIES.get(op.bodySchema()).apply(actorSet);
  }

  private static HttpResponse<String> send(
      String method, String path, Actor actor, UUID ws, String body) {
    return fx.http().send(method, path, actor.accessToken(), ws, body, Map.of());
  }

  private static boolean containsAny(String body, Set<String> tokens) {
    return tokens.stream().anyMatch(body::contains);
  }

  private static boolean leaksInternals(String body) {
    return body.contains("Exception")
        || body.contains("\tat ")
        || body.contains("java.")
        || body.contains("org.springframework")
        || body.contains("\"trace\"");
  }

  private static String abbreviate(String s) {
    return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
  }

  private static void record(String id, String name, String observed) {
    synchronized (REPORT) {
      REPORT.put(id + " | " + name, observed);
      StringBuilder sb = new StringBuilder();
      REPORT.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append('\n'));
      writeReport("diger", sb.toString());
    }
  }

  private static void writeReport(String name, String content) {
    try {
      Path dir = Path.of("target", "security-reports");
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("dilim-04-" + name + ".md"), content, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    System.out.println("[D4-RAPOR " + name + "]\n" + content);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
