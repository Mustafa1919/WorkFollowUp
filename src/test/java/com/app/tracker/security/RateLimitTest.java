package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.security.support.MailpitInbox;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Actor;
import com.app.tracker.security.support.SecurityFixture.Tenant;
import com.app.tracker.security.support.SecurityHttp;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.workspace.model.WorkspaceRole;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;
import javax.sql.DataSource;
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

/**
 * Guvenlik katalogu DOS-01..04, DOS-07, TOK-16: hiz siniri, kaynak tuketimi ve sayfa boyutu
 * ustsinirlari. Olcum kurali (sozlesme): bir uca en cok 60 istek atilir; 429 gorulmezse KALDI.
 * Redis sayaclari testler arasinda sizmasin diye her akis benzersiz kullanici / IP ({@code
 * X-Forwarded-For}) / token kullanir.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RateLimitTest extends AbstractIntegrationTest {

  /** Sozlesme: makul esik <= 30 istek/dk, yani 31. istekte 429 gorulmeli. */
  private static final int MAX_REASONABLE_THRESHOLD = 30;

  private static final int BUDGET = 60;
  private static final int DOS04_TASKS = 230;
  private static final int DOS04_COMMENTS = 210;
  private static final int DOS04_APPROVED = 210;
  private static final int PAGE_CAP = 200;

  @LocalServerPort private int port;
  @Autowired private ApplicationContext ctx;
  @Autowired private ObjectMapper mapper;
  @Autowired private DataSource dataSource;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private TaskService taskService;

  private SecurityFixture fixture;
  private SecurityHttp http;
  private MailpitInbox inbox;
  private boolean dos04Seeded;

  @BeforeAll
  void setUp() {
    http = new SecurityHttp(port);
    fixture = SecurityFixture.create(ctx, port);
    inbox = new MailpitInbox(path -> mailpitApiUrl(path), mapper);
  }

  /** Bir akisin sonucu: ilk 429'un sirasi (1 tabanli, yoksa -1) ve gozlenen durumlarin ozeti. */
  private record Flood(int first429, int sent, Map<Integer, Integer> statusCounts) {
    String summary() {
      return "gonderilen=" + sent + ", ilk429=" + first429 + ", durumlar=" + statusCounts;
    }
  }

  private static Flood flood(int max, IntFunction<HttpResponse<String>> call) {
    Map<Integer, Integer> counts = new java.util.TreeMap<>();
    for (int i = 1; i <= max; i++) {
      int status = call.apply(i).statusCode();
      counts.merge(status, 1, Integer::sum);
      if (status == 429) {
        return new Flood(i, i, counts);
      }
    }
    return new Flood(-1, max, counts);
  }

  private static void assertThrottled(Flood flood, String what) {
    assertTrue(
        flood.first429() >= 1 && flood.first429() <= MAX_REASONABLE_THRESHOLD + 1,
        what
            + ": beklenen 429 en gec "
            + (MAX_REASONABLE_THRESHOLD + 1)
            + ". istekte (esik <= "
            + MAX_REASONABLE_THRESHOLD
            + "/dk); "
            + flood.summary());
  }

  // =========================================================================================
  // DOS-01 kimliksiz / kaynak yaratan uclarda hiz siniri
  // =========================================================================================

  @Test
  @DisplayName("DOS-01 | login: ayni IP'den farkli hesaplarla esik ustu 429")
  void dos01_loginFromSameIpIsRateLimited() {
    String ip = randomIp();
    Flood f =
        flood(
            BUDGET,
            i ->
                postAnonymous(
                    "/api/v1/auth/login",
                    "{\"email\":\"sec-dos01l-"
                        + UUID.randomUUID()
                        + "@tracker.local\",\"password\":\"wrong-password-"
                        + i
                        + "\"}",
                    ip));
    assertThrottled(f, "login (ayni IP, farkli hesaplar)");
  }

  @Test
  @DisplayName("DOS-01 | login K: esik altinda gercek hesapla dogru parola 200")
  void dos01_acceptance_loginBelowThresholdSucceeds() {
    Actor user = fixture.addMember(fixture.a(), WorkspaceRole.VIEWER, "dos01lk");
    HttpResponse<String> r =
        postAnonymous(
            "/api/v1/auth/login",
            "{\"email\":\""
                + user.email()
                + "\",\"password\":\""
                + SecurityFixture.PASSWORD
                + "\"}",
            randomIp());
    assertEquals(200, r.statusCode(), r.body());
    assertTrue(r.body().contains("accessToken"));
  }

  @Test
  @DisplayName("DOS-01 | kayit: ayni IP'den esik ustu 429")
  void dos01_registerIsRateLimited() {
    String ip = randomIp();
    Flood f =
        flood(
            BUDGET,
            i ->
                postAnonymous(
                    "/api/v1/auth/register",
                    registerBody("sec-dos01r-" + UUID.randomUUID() + "@tracker.local"),
                    ip));
    assertThrottled(f, "kayit");
  }

  @Test
  @DisplayName("DOS-01 | kayit K: esik altinda ilk kayit 201")
  void dos01_acceptance_registerBelowThresholdSucceeds() {
    HttpResponse<String> r =
        postAnonymous(
            "/api/v1/auth/register",
            registerBody("sec-dos01rk-" + UUID.randomUUID() + "@tracker.local"),
            randomIp());
    assertEquals(201, r.statusCode(), r.body());
  }

  @Test
  @DisplayName("DOS-01 | parola sifirlama istegi: ayni IP'den esik ustu 429")
  void dos01_passwordResetRequestIsRateLimited() {
    String ip = randomIp();
    Flood f =
        flood(
            BUDGET,
            i ->
                postAnonymous(
                    "/api/v1/auth/password-reset/request",
                    "{\"email\":\"sec-dos01p-" + UUID.randomUUID() + "@tracker.local\"}",
                    ip));
    assertThrottled(f, "parola sifirlama istegi");
  }

  @Test
  @DisplayName("DOS-01 | parola sifirlama K: esik altinda ilk istek 202")
  void dos01_acceptance_passwordResetBelowThresholdSucceeds() {
    HttpResponse<String> r =
        postAnonymous(
            "/api/v1/auth/password-reset/request",
            "{\"email\":\"sec-dos01pk-" + UUID.randomUUID() + "@tracker.local\"}",
            randomIp());
    assertEquals(202, r.statusCode(), r.body());
  }

  @Test
  @DisplayName("DOS-01 | davet gonderme: ayni kullanici icin esik ustu 429")
  void dos01_invitationSendIsRateLimited() {
    Tenant a = fixture.a();
    Actor admin = fixture.addMember(a, WorkspaceRole.ADMIN, "dos01i");
    Flood f = flood(BUDGET, i -> invite(a, admin));
    assertThrottled(f, "davet gonderme");
  }

  @Test
  @DisplayName("DOS-01 | davet K: esik altinda ilk davet 201")
  void dos01_acceptance_invitationBelowThresholdSucceeds() {
    Tenant a = fixture.a();
    Actor admin = fixture.addMember(a, WorkspaceRole.ADMIN, "dos01ik");
    HttpResponse<String> r = invite(a, admin);
    assertEquals(201, r.statusCode(), r.body());
  }

  // =========================================================================================
  // DOS-02 e-posta bombardimanı
  // =========================================================================================

  @Test
  @DisplayName("DOS-02 | ayni alicıya 1 dk icinde 20 sifirlama istegi en cok 5 e-posta uretir")
  void dos02_repeatedPasswordResetDoesNotFloodRecipient() throws Exception {
    Actor victim = fixture.addMember(fixture.a(), WorkspaceRole.VIEWER, "dos02v");
    String ip = randomIp();
    for (int i = 0; i < 20; i++) {
      HttpResponse<String> r =
          postAnonymous(
              "/api/v1/auth/password-reset/request", "{\"email\":\"" + victim.email() + "\"}", ip);
      // Hiz siniri varsa 429 da kabul edilir (e-posta uretilmedi); 5xx kabul edilmez.
      assertTrue(r.statusCode() == 202 || r.statusCode() == 429, "durum " + r.statusCode());
    }
    int mails = settledCount(victim.email(), "sifirlama", 20, 20_000);
    assertTrue(mails <= 5, "20 istek sonrasi aliciya dusen sifirlama e-postasi: " + mails);
  }

  @Test
  @DisplayName("DOS-02 | K: tek sifirlama istegi tek e-posta uretir")
  void dos02_acceptance_singleRequestProducesSingleMail() throws Exception {
    Actor user = fixture.addMember(fixture.a(), WorkspaceRole.VIEWER, "dos02k");
    HttpResponse<String> r =
        postAnonymous(
            "/api/v1/auth/password-reset/request",
            "{\"email\":\"" + user.email() + "\"}",
            randomIp());
    assertEquals(202, r.statusCode(), r.body());
    int mails = settledCount(user.email(), "sifirlama", 1, 30_000);
    // Birinci posta geldikten sonra 3 sn daha beklenir: ikinci posta uretilmemeli.
    Thread.sleep(3_000);
    assertEquals(1, mails);
    assertEquals(1, inbox.count(user.email(), "sifirlama"));
  }

  // =========================================================================================
  // DOS-03 pahali okuma uclari
  // =========================================================================================

  private record Surface(String name, String path) {}

  private List<Surface> expensiveSurfaces() {
    Tenant a = fixture.a();
    return List.of(
        new Surface("arama", "/api/v1/search?q=dos03"),
        new Surface("donem raporu", "/api/v1/reports/period?year=2099"),
        new Surface(
            "Monte Carlo (backlog tahmini)",
            "/api/v1/projects/" + a.projectId() + "/forecast/backlog"),
        new Surface(
            "analitik velocity", "/api/v1/projects/" + a.projectId() + "/analytics/velocity"),
        new Surface(
            "analitik throughput", "/api/v1/projects/" + a.projectId() + "/analytics/throughput"),
        new Surface(
            "analitik cycle-time", "/api/v1/projects/" + a.projectId() + "/analytics/cycle-time"));
  }

  @Test
  @DisplayName("DOS-03 | K: pahali uclar normal istekte 200 doner")
  void dos03_acceptance_expensiveSurfacesServeNormalRequest() {
    Tenant a = fixture.a();
    Actor user = fixture.addMember(a, WorkspaceRole.ADMIN, "dos03k");
    List<String> bad = new ArrayList<>();
    for (Surface s : expensiveSurfaces()) {
      HttpResponse<String> r = http.get(s.path(), user, a.workspaceId(), null);
      if (r.statusCode() != 200) {
        bad.add(s.name() + "=" + r.statusCode());
      }
    }
    assertTrue(bad.isEmpty(), "200 beklenirken: " + bad);
  }

  @Test
  @DisplayName(
      "DOS-03 | pahali uclarda kullanici basina hiz siniri ve/veya DB statement_timeout var")
  void dos03_expensiveSurfacesAreBounded() throws Exception {
    Tenant a = fixture.a();
    String appTimeout = statementTimeout(false);
    String runtimeTimeout = statementTimeout(true);
    boolean timeoutDefined = !isZero(appTimeout);

    List<String> unbounded = new ArrayList<>();
    List<String> limited = new ArrayList<>();
    for (Surface s : expensiveSurfaces()) {
      Actor user = fixture.addMember(a, WorkspaceRole.ADMIN, "dos03");
      Flood f = flood(BUDGET, i -> http.get(s.path(), user, a.workspaceId(), null));
      if (f.first429() > 0) {
        limited.add(s.name() + "(429@" + f.first429() + ")");
      } else if (!timeoutDefined) {
        unbounded.add(s.name() + " [" + f.summary() + "]");
      }
    }
    assertTrue(
        unbounded.isEmpty(),
        "ne hiz siniri ne statement_timeout: "
            + unbounded
            + " | uygulama baglantisi statement_timeout="
            + appTimeout
            + ", app_runtime yeni baglanti statement_timeout="
            + runtimeTimeout
            + " | hiz sinirli: "
            + limited);
  }

  // =========================================================================================
  // DOS-04 sayfa boyutu ustsiniri
  // =========================================================================================

  @Test
  @DisplayName("DOS-04 | K: varsayilan sayfa doner (20 eleman) ve tohum veri 200'den fazla")
  void dos04_acceptance_defaultPageReturned() throws Exception {
    seedDos04();
    Tenant a = fixture.a();
    String tasks = "/api/v1/projects/" + a.projectId() + "/tasks";
    JsonNode dflt = json(http.get(tasks, a.admin(), a.workspaceId(), null));
    JsonNode capped = json(http.get(tasks + "?limit=200", a.admin(), a.workspaceId(), null));
    JsonNode comments =
        json(
            http.get(
                "/api/v1/tasks/" + a.taskId() + "/comments?limit=200",
                a.admin(),
                a.workspaceId(),
                null));
    JsonNode mine =
        json(http.get("/api/v1/me/tasks?limit=200", a.developer(), a.workspaceId(), null));
    assertAll(
        () -> assertEquals(20, dflt.path("data").size(), "varsayilan sayfa"),
        () ->
            assertEquals(
                PAGE_CAP, capped.path("data").size(), "limit=200 tam 200 donmeli (tohum yeterli)"),
        () ->
            assertEquals(
                PAGE_CAP,
                json(http.get(tasks + "/approved?limit=200", a.admin(), a.workspaceId(), null))
                    .path("data")
                    .size(),
                "onayli gorev tohumu yetersiz"),
        () -> assertTrue(capped.path("has_more").asBoolean(false), "gorevler: baska sayfa olmali"),
        () -> assertEquals(PAGE_CAP, comments.path("data").size(), "yorum tohumu yetersiz"),
        () -> assertEquals(PAGE_CAP, mine.path("data").size(), "me/tasks tohumu yetersiz"),
        () -> assertTrue(waitUnreadAbove(a, PAGE_CAP), "bildirim tohumu yetersiz (<= 200)"));
  }

  @Test
  @DisplayName(
      "DOS-04 | liste uclari limit/size=100000 isteginde en cok 200 eleman doner ya da reddeder")
  void dos04_hugePageSizeIsBounded() throws Exception {
    seedDos04();
    Tenant a = fixture.a();
    waitUnreadAbove(a, PAGE_CAP);
    String big = "limit=100000&size=100000";
    record Probe(String name, String path, Actor actor) {}
    List<Probe> probes =
        List.of(
            new Probe(
                "proje gorevleri",
                "/api/v1/projects/" + a.projectId() + "/tasks?" + big,
                a.admin()),
            new Probe(
                "onayli gorevler",
                "/api/v1/projects/" + a.projectId() + "/tasks/approved?" + big,
                a.admin()),
            new Probe("me/tasks", "/api/v1/me/tasks?" + big, a.developer()),
            new Probe("yorumlar", "/api/v1/tasks/" + a.taskId() + "/comments?" + big, a.admin()),
            new Probe(
                "aktivite (veri az)",
                "/api/v1/tasks/" + a.taskId() + "/activity?" + big,
                a.admin()),
            new Probe("bildirimler", "/api/v1/notifications?" + big, a.developer()),
            new Probe("arama", "/api/v1/search?q=dos04&" + big, a.admin()));
    List<String> violations = new ArrayList<>();
    List<String> observed = new ArrayList<>();
    for (Probe p : probes) {
      HttpResponse<String> r = http.get(p.path(), p.actor(), a.workspaceId(), null);
      int status = r.statusCode();
      if (status >= 500) {
        violations.add(p.name() + ": " + status);
        continue;
      }
      if (status >= 400) {
        observed.add(p.name() + "=reddedildi(" + status + ")");
        continue;
      }
      JsonNode parsed = mapper.readTree(r.body());
      boolean hasExpectedLists =
          p.name().equals("arama")
              ? parsed.path("tasks").isArray() && parsed.path("comments").isArray()
              : parsed.path("data").isArray();
      if (!hasExpectedLists) {
        violations.add(p.name() + ": 2xx yanitta liste alani yok: " + r.body());
        continue;
      }
      int count = largestArray(parsed);
      observed.add(p.name() + "=" + count);
      if (p.name().equals("onayli gorevler")
          && !(count == PAGE_CAP && parsed.path("has_more").asBoolean(false))) {
        violations.add(
            "onayli gorevler: 210 onayli gorev varken 200 eleman + has_more bekleniyordu, gozlenen "
                + count);
      }
      if (count > PAGE_CAP) {
        violations.add(p.name() + ": " + count + " eleman (ust sinir " + PAGE_CAP + ")");
      }
    }
    System.out.println("[D7-DOS04] limit/size=100000 gozlenen eleman sayilari: " + observed);
    assertTrue(violations.isEmpty(), "sinirsiz sayfa: " + violations + " | gozlenen: " + observed);
  }

  // =========================================================================================
  // DOS-07 telemetri ve geri bildirim
  // =========================================================================================

  @Test
  @DisplayName("DOS-07 | telemetri kullanim ucu: kullanici basina hiz siniri (60 istekte 429)")
  void dos07_usageTelemetryIsRateLimited() {
    Tenant a = fixture.a();
    Actor user = fixture.addMember(a, WorkspaceRole.DEVELOPER, "dos07u");
    Flood f =
        flood(
            BUDGET,
            i -> http.post("/api/v1/telemetry/usage", user, a.workspaceId(), usageBatch(1)));
    assertTrue(f.first429() > 0, "429 gorulmedi: " + f.summary());
  }

  @Test
  @DisplayName("DOS-07 | telemetri hata ucu: kullanici basina hiz siniri (60 istekte 429)")
  void dos07_errorTelemetryIsRateLimited() {
    Tenant a = fixture.a();
    Actor user = fixture.addMember(a, WorkspaceRole.DEVELOPER, "dos07e");
    Flood f =
        flood(
            BUDGET, i -> http.post("/api/v1/telemetry/errors", user, a.workspaceId(), errorBody()));
    assertTrue(f.first429() > 0, "429 gorulmedi: " + f.summary());
  }

  @Test
  @DisplayName("DOS-07 | geri bildirim ucu: kullanici basina hiz siniri (60 istekte 429)")
  void dos07_feedbackIsRateLimited() {
    Tenant a = fixture.a();
    Actor user = fixture.addMember(a, WorkspaceRole.DEVELOPER, "dos07f");
    Flood f =
        flood(
            BUDGET,
            i ->
                http.post(
                    "/api/v1/feedback",
                    user,
                    a.workspaceId(),
                    "{\"message\":\"dos07 geri bildirim " + i + "\",\"pagePath\":\"/x\"}"));
    assertTrue(f.first429() > 0, "429 gorulmedi: " + f.summary());
  }

  @Test
  @DisplayName("DOS-07 | 10 000 olayli telemetri partisi reddedilir")
  void dos07_hugeUsageBatchIsRejected() {
    Tenant a = fixture.a();
    Actor user = fixture.addMember(a, WorkspaceRole.DEVELOPER, "dos07b");
    HttpResponse<String> r =
        http.post("/api/v1/telemetry/usage", user, a.workspaceId(), usageBatch(10_000));
    assertTrue(
        r.statusCode() >= 400 && r.statusCode() < 500,
        "10 000 olayli parti 4xx ile reddedilmeli, gozlenen " + r.statusCode());
  }

  @Test
  @DisplayName("DOS-07 | K: normal telemetri partisi, hata raporu ve geri bildirim kabul edilir")
  void dos07_acceptance_normalRequestsAccepted() {
    Tenant a = fixture.a();
    Actor user = fixture.addMember(a, WorkspaceRole.DEVELOPER, "dos07k");
    assertAll(
        () ->
            assertEquals(
                202,
                http.post("/api/v1/telemetry/usage", user, a.workspaceId(), usageBatch(5))
                    .statusCode()),
        () ->
            assertEquals(
                202,
                http.post("/api/v1/telemetry/errors", user, a.workspaceId(), errorBody())
                    .statusCode()),
        () ->
            assertEquals(
                201,
                http.post(
                        "/api/v1/feedback",
                        user,
                        a.workspaceId(),
                        "{\"message\":\"dos07 normal\",\"pagePath\":\"/x\"}")
                    .statusCode()));
  }

  // =========================================================================================
  // TOK-16 PAT hiz siniri token basina
  // =========================================================================================

  @Test
  @DisplayName(
      "TOK-16 | PAT esigi asinca 429 + Retry-After; ayni kullanicinin ikinci PAT'i etkilenmez")
  void tok16_rateLimitIsPerToken() {
    Tenant a = fixture.a();
    Actor owner = fixture.addMember(a, WorkspaceRole.ADMIN, "tok16");
    String pat1 = createPat(owner, "tok16-1");
    String pat2 = createPat(owner, "tok16-2");

    // K: esik altinda (ilk 60 istek) hepsi 200.
    List<Integer> below = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      below.add(callWithPat(pat1, a).statusCode());
    }
    HttpResponse<String> over = callWithPat(pat1, a);
    HttpResponse<String> other = callWithPat(pat2, a);

    assertAll(
        () ->
            assertTrue(
                below.stream().allMatch(s -> s == 200), "esik altinda tum istekler 200: " + below),
        () -> assertEquals(429, over.statusCode(), "esik asildi: " + over.body()),
        () -> {
          String retryAfter = over.headers().firstValue("Retry-After").orElse("");
          assertFalse(retryAfter.isEmpty(), "Retry-After yok");
          assertTrue(Long.parseLong(retryAfter) > 0, "Retry-After pozitif olmali: " + retryAfter);
        },
        () -> assertEquals(200, other.statusCode(), "ikinci PAT etkilendi: " + other.body()));
  }

  @Test
  @DisplayName("TOK-16 | 100 gecersiz PAT denemesi gecerli PAT'in butcesini tuketmez")
  void tok16_invalidPatAttemptsDoNotConsumeValidBudget() {
    Tenant a = fixture.a();
    Actor owner = fixture.addMember(a, WorkspaceRole.ADMIN, "tok16i");
    String valid = createPat(owner, "tok16-valid");

    List<Integer> invalid = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      invalid.add(callWithPat("wf_pat_" + UUID.randomUUID() + UUID.randomUUID(), a).statusCode());
    }
    List<Integer> validStatuses = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      validStatuses.add(callWithPat(valid, a).statusCode());
    }
    HttpResponse<String> over = callWithPat(valid, a);

    assertAll(
        () ->
            assertTrue(
                invalid.stream().allMatch(s -> s == 401),
                "gecersiz PAT'ler 401 olmali: " + invalid),
        () ->
            assertTrue(
                validStatuses.stream().allMatch(s -> s == 200),
                "gecersiz denemeler butceyi tuketti, gecerli PAT 60 istek yapamadi: "
                    + validStatuses),
        () -> assertEquals(429, over.statusCode(), "61. istek 429 olmali"));
  }

  // =========================================================================================
  // Yardimcilar
  // =========================================================================================

  private HttpResponse<String> postAnonymous(String path, String body, String ip) {
    return http.send("POST", path, null, null, body, Map.of("X-Forwarded-For", ip));
  }

  private static String randomIp() {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    return "10." + r.nextInt(1, 255) + "." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
  }

  private static String registerBody(String email) {
    return "{\"email\":\""
        + email
        + "\",\"password\":\""
        + SecurityFixture.PASSWORD
        + "\",\"fullName\":\"Sec Dos\",\"privacyNoticeAccepted\":true,"
        + "\"privacyNoticeVersion\":\""
        + PrivacyNotice.CURRENT_VERSION
        + "\"}";
  }

  private HttpResponse<String> invite(Tenant tenant, Actor admin) {
    return http.post(
        "/api/v1/workspaces/members/invitations",
        admin,
        tenant.workspaceId(),
        "{\"email\":\"sec-dos01i-"
            + UUID.randomUUID()
            + "@tracker.local\",\"role\":\""
            + WorkspaceRole.VIEWER
            + "\"}");
  }

  /**
   * Sayi {@code expected}e ulasana ya da {@code maxMillis} dolana kadar bekler, sonra son degeri
   * doner.
   */
  private int settledCount(String email, String subjectPart, int expected, long maxMillis)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + maxMillis;
    int count = inbox.count(email, subjectPart);
    int stableRounds = 0;
    while (System.currentTimeMillis() < deadline && count < expected) {
      Thread.sleep(500);
      int next = inbox.count(email, subjectPart);
      stableRounds = next == count ? stableRounds + 1 : 0;
      count = next;
      // Sayim 4 sn degismediyse ve en az bir posta geldiyse ya da 8 sn sessizse yeterince bekledik.
      if (stableRounds >= 8 && count > 0) {
        break;
      }
    }
    return count;
  }

  private String statementTimeout(boolean freshRuntimeConnection) throws Exception {
    try (Connection c =
            freshRuntimeConnection ? openRuntimeConnection() : dataSource.getConnection();
        java.sql.Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SHOW statement_timeout")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static boolean isZero(String timeout) {
    return timeout == null || timeout.equals("0") || timeout.equals("0ms") || timeout.equals("0s");
  }

  private JsonNode json(HttpResponse<String> r) {
    assertEquals(200, r.statusCode(), r.body());
    return mapper.readTree(r.body());
  }

  private static int largestArray(JsonNode node) {
    if (node.isArray()) {
      return node.size();
    }
    int max = 0;
    for (JsonNode child : node) {
      if (child.isArray()) {
        max = Math.max(max, child.size());
      }
    }
    return max;
  }

  private boolean waitUnreadAbove(Tenant a, int threshold) throws Exception {
    long deadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < deadline) {
      HttpResponse<String> r =
          http.get("/api/v1/notifications/unread-count", a.developer(), a.workspaceId(), null);
      if (r.statusCode() == 200 && mapper.readTree(r.body()).path("count").asLong(0) > threshold) {
        return true;
      }
      Thread.sleep(500);
    }
    return false;
  }

  /** 230 gorev (DEVELOPER'a atanmis: me/tasks ve bildirim icin) + 210 yorum. Tek sefer. */
  private synchronized void seedDos04() {
    if (dos04Seeded) {
      return;
    }
    Tenant a = fixture.a();
    for (int i = 0; i < DOS04_TASKS; i++) {
      int n = i;
      tenantExecutor.runAs(
          a.workspaceId(),
          () -> {
            Task t = taskService.createTask(a.projectId(), "dos04 gorev " + n, null, null);
            taskService.assign(t.getId(), a.developer().userId(), a.admin().userId());
          });
    }
    for (int i = 0; i < DOS04_COMMENTS; i++) {
      HttpResponse<String> r =
          http.post(
              "/api/v1/tasks/" + a.taskId() + "/comments",
              a.admin(),
              a.workspaceId(),
              "{\"body\":\"dos04 yorum " + i + "\"}");
      assertEquals(201, r.statusCode(), "yorum tohumu: " + r.body());
    }
    // Onayli gorevler: Done + onay, servis katmaninda (HTTP'den ~3x hizli; sure sinirinin altinda).
    for (int i = 0; i < DOS04_APPROVED; i++) {
      int n = i;
      tenantExecutor.runAs(
          a.workspaceId(),
          () -> {
            Task t = taskService.createTask(a.projectId(), "dos04 onayli " + n, null, null);
            taskService.updateStatus(t.getId(), TaskStatus.DONE, a.admin().userId());
            taskService.approve(t.getId(), a.admin().userId());
          });
    }
    dos04Seeded = true;
  }

  private static String usageBatch(int events) {
    StringBuilder sb = new StringBuilder("{\"events\":[");
    for (int i = 0; i < events; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"feature\":\"board\",\"action\":\"open\"}");
    }
    return sb.append("]}").toString();
  }

  private static String errorBody() {
    return "{\"errorType\":\"TypeError\",\"message\":\"dos07\",\"path\":\"/board\"}";
  }

  private String createPat(Actor owner, String name) {
    HttpResponse<String> r =
        http.post("/api/v1/access-tokens", owner, null, "{\"name\":\"" + name + "\"}");
    assertEquals(201, r.statusCode(), r.body());
    return mapper.readTree(r.body()).path("rawToken").asString("");
  }

  private HttpResponse<String> callWithPat(String pat, Tenant tenant) {
    return http.get("/api/v1/projects", pat, tenant.workspaceId(), null);
  }
}
