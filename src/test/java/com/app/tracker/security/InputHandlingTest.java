package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.security.CorsProperties;
import com.app.tracker.core.security.SecurityConfig;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.notification.consumer.InboxNotificationConsumer;
import com.app.tracker.notification.service.NotificationMessageFormatter;
import com.app.tracker.notification.service.SlackMessageFormatter;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Actor;
import com.app.tracker.security.support.SecurityHttp;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.telemetry.TelemetryConsumer;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.workspace.model.WorkspaceRole;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dilim 06 -- girdi isleme, hata yanitlari, CORS, e-posta/Slack kacislama, hata telemetrisi
 * (INJ-04/05/06/08/09, CFG-04/06, XSS-03/04, EXT-06, LOG-05). Gercek servlet (RANDOM_PORT)
 * uzerinden HTTP; e-posta Mailpit API'sinden okunur; error_events/notifications sorgulari
 * app_runtime JdbcTemplate'i ile (bu tablolar RLS'siz, bildirimler HTTP'den okunur). LOG-05'in
 * backend 500 tetikleyicisi icin yalniz testte yuklenen bir uc import edilir (bkz. {@link
 * Log05ProbeConfiguration}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InputHandlingTest.Log05ProbeConfiguration.class)
class InputHandlingTest extends AbstractIntegrationTest {

  /** LOG-05 tetikleyicisinin istisna mesajina konan ham sir; maskelemeden sonra kalmamali. */
  static final String LOG05_PROBE_PAT = "wf_pat_" + "Q".repeat(43);

  /**
   * Eski tetikleyici (NUL'lu arama → Postgres hatasi → 500) SEC-20 duzeltmesiyle 400'e dondu
   * (INJ-04 bunu sart kosuyor). Backend hata yolu artik bu kasitli olarak istisna atan uc ile
   * tetiklenir; mesajinda e-posta, PAT ve JWT tasir ki maskeleme gercekten olculsun.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class Log05ProbeConfiguration {
    @Bean
    Log05ProbeController log05ProbeController() {
      return new Log05ProbeController();
    }
  }

  @RestController
  @TestComponent
  static class Log05ProbeController {
    @GetMapping("/api/v1/contract/log05-probe")
    String boom(
        @RequestParam("email") String email,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
      throw new IllegalStateException(
          "probe failed for " + email + " token " + LOG05_PROBE_PAT + " auth " + authorization);
    }
  }

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  /** Yanit govdesinde ASLA gorunmemesi gereken ic bilgi parcalari (CFG-04). */
  private static final List<String> LEAK_TOKENS =
      List.of(
          "com.app.",
          "org.springframework",
          "java.lang",
          "java.util",
          "java.io",
          "jakarta.",
          "org.hibernate",
          "org.postgresql",
          "org.apache",
          "tools.jackson",
          "com.fasterxml",
          "\tat ",
          "stackTrace",
          "Caused by");

  private static final Pattern SQL_FRAGMENT =
      Pattern.compile(
          "(?is)(select\\s.+\\sfrom\\s|insert\\s+into\\s|update\\s+\\w+\\s+set\\s|delete\\s+from\\s"
              + "|violates\\s|syntax error at|\\bpsql\\b|SQLState|could not execute)");
  private static final Pattern FILE_PATH =
      Pattern.compile("([A-Za-z]:\\\\|/(home|usr|var|opt|src)/|\\.java\\b|\\.class\\b)");

  @LocalServerPort private int port;
  @Autowired private ObjectMapper mapper;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private com.app.tracker.comment.service.CommentService commentService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TelemetryConsumer telemetryConsumer;
  @Autowired private InboxNotificationConsumer inboxConsumer;

  @Value("${app.notification.email.public-url}")
  private String publicUrl;

  private SecurityFixture fixture;
  private UUID inpProjectId;

  @Autowired private org.springframework.context.ApplicationContext applicationContext;

  // ================================================================ ortak yardimcilar

  private SecurityFixture fx() {
    if (fixture == null) {
      fixture = SecurityFixture.create(applicationContext, port);
    }
    return fixture;
  }

  private SecurityHttp http() {
    return fx().http();
  }

  private Actor adminA() {
    return fx().a().admin();
  }

  private UUID wsA() {
    return fx().a().workspaceId();
  }

  private JsonNode json(HttpResponse<String> response) {
    return mapper.readTree(response.body());
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String describe(HttpResponse<String> r) {
    String body = r.body() == null ? "" : r.body();
    return r.statusCode() + " " + (body.length() > 300 ? body.substring(0, 300) + "..." : body);
  }

  /** Verilen Content-Type ile (SecurityHttp her zaman application/json ekler) gonderim. */
  private HttpResponse<String> call(
      String method, String path, String bearer, UUID ws, String contentType, String body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(120))
            .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
    if (contentType != null) {
      request.header("Content-Type", contentType);
    }
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    if (ws != null) {
      request.header("X-Workspace-Id", ws.toString());
    }
    try {
      return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(method + " " + path + " -> " + e, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void assertStatusIn(HttpResponse<String> r, String what, int... allowed) {
    for (int status : allowed) {
      if (r.statusCode() == status) {
        return;
      }
    }
    fail(what + ": beklenen " + java.util.Arrays.toString(allowed) + ", gozlenen " + describe(r));
  }

  private static void assertNoLeak(HttpResponse<String> r, String what) {
    String body = r.body() == null ? "" : r.body();
    for (String token : LEAK_TOKENS) {
      assertFalse(
          body.contains(token), what + ": govdede ic bilgi '" + token + "' var -> " + describe(r));
    }
    assertFalse(
        SQL_FRAGMENT.matcher(body).find(), what + ": govdede SQL parcasi -> " + describe(r));
    assertFalse(FILE_PATH.matcher(body).find(), what + ": govdede dosya yolu -> " + describe(r));
  }

  /** CFG-04 biçimi: application/problem+json, status ve title alanlari, ic bilgi yok. */
  private void assertProblem(HttpResponse<String> r, int expectedStatus, String what) {
    assertEquals(expectedStatus, r.statusCode(), what + " -> " + describe(r));
    String contentType = r.headers().firstValue("Content-Type").orElse("");
    assertTrue(
        contentType.startsWith("application/problem+json"),
        what + ": Content-Type application/problem+json olmali, gozlenen '" + contentType + "'");
    JsonNode problem = json(r);
    assertEquals(expectedStatus, problem.path("status").asInt(-1), what + ": status alani");
    assertFalse(problem.path("title").asString("").isBlank(), what + ": title alani bos");
    assertNoLeak(r, what);
  }

  private static void assertFailures(List<String> failures) {
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  // ---------------------------------------------------------------- Mailpit

  private HttpResponse<String> mailGet(String path) {
    try {
      return CLIENT.send(
          HttpRequest.newBuilder(URI.create(mailpitApiUrl(path))).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private List<JsonNode> mailSummariesTo(String address) {
    JsonNode list =
        mapper.readTree(mailGet("/api/v1/search?limit=100&query=" + enc("to:" + address)).body());
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode message : list.path("messages")) {
      out.add(message);
    }
    return out;
  }

  /** Alicisi {@code address} olan ve ayrintisi {@code matches}'e uyan ilk iletiyi bekler. */
  private JsonNode awaitMail(String address, Predicate<JsonNode> matches, String what) {
    long deadline = System.currentTimeMillis() + 45_000;
    try {
      while (System.currentTimeMillis() < deadline) {
        for (JsonNode summary : mailSummariesTo(address)) {
          JsonNode detail =
              mapper.readTree(mailGet("/api/v1/message/" + summary.path("ID").asString("")).body());
          if (matches.test(detail)) {
            return detail;
          }
        }
        Thread.sleep(300);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    throw new AssertionError("Mailpit'te e-posta gelmedi (" + what + "): " + address);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> mailHeaders(JsonNode detail) {
    String id = detail.path("ID").asString("");
    return mapper.readValue(mailGet("/api/v1/message/" + id + "/headers").body(), Map.class);
  }

  /** INJ-06: ek baslik / ek alici yok, konu tek satir, alici tam olarak beklenen adres. */
  private void assertMailNotInjected(JsonNode detail, String recipient, String what) {
    String subject = detail.path("Subject").asString("");
    assertFalse(subject.contains("\r") || subject.contains("\n"), what + ": konu cok satirli");
    assertEquals(0, detail.path("Bcc").size(), what + ": Bcc alicisi var: " + detail.path("Bcc"));
    assertEquals(0, detail.path("Cc").size(), what + ": Cc alicisi var: " + detail.path("Cc"));
    assertEquals(1, detail.path("To").size(), what + ": alici sayisi");
    assertEquals(
        recipient.toLowerCase(),
        detail.path("To").get(0).path("Address").asString("").toLowerCase(),
        what + ": alici");
    Map<String, Object> headers = mailHeaders(detail);
    for (String name : headers.keySet()) {
      String lower = name.toLowerCase();
      assertFalse(
          lower.equals("bcc") || lower.equals("cc") || lower.startsWith("x-inj"),
          what + ": enjekte baslik '" + name + "' -> " + headers.keySet());
    }
    Object subjectHeader = headers.get("Subject");
    assertNotNull(subjectHeader, what + ": Subject basligi yok");
    assertEquals(1, ((List<?>) subjectHeader).size(), what + ": birden cok Subject basligi");
  }

  // ================================================================ INJ-04

  private static final List<String> SEARCH_QUERIES =
      List.of(
          "'",
          "\"",
          "\\",
          "%",
          "_",
          "&",
          "|",
          "!",
          ":*",
          "(",
          ")",
          "<->",
          "--",
          "/*",
          "\u0000",
          "a\u0000b",
          "ab\u0000",
          "a".repeat(2000),
          "     ",
          "\uD83D\uDE00\uD83D\uDD25",
          "\u0130 \u0131 \u0130stanbul \u0131spanak",
          "' OR 1=1 --",
          "'; DROP TABLE tasks; --",
          "foo & ! ( | :* <-> bar",
          "a:*&(b|!c)",
          "%' OR '%'='",
          "\\' \\\\ \\");

  @Test
  @DisplayName("INJ-04 | ozel karakterli arama sorgulari 500 uretmez (200/400/422)")
  void inj04_specialCharacterSearchQueriesNeverReturn500() {
    List<String> failures = new ArrayList<>();
    for (String query : SEARCH_QUERIES) {
      String label =
          query.length() > 20 ? query.substring(0, 20) + "...(" + query.length() + ")" : query;
      HttpResponse<String> r = http().get("/api/v1/search?q=" + enc(query), adminA(), wsA(), null);
      int status = r.statusCode();
      if (status != 200 && status != 400 && status != 422) {
        failures.add("q=" + label.replace("\u0000", "<NUL>") + " -> " + describe(r));
        continue;
      }
      try {
        assertNoLeak(r, "q=" + label);
        if (status == 200) {
          JsonNode body = json(r);
          assertTrue(body.path("tasks").isArray(), "tasks dizisi yok: " + describe(r));
          assertTrue(body.path("comments").isArray(), "comments dizisi yok: " + describe(r));
        }
      } catch (AssertionError e) {
        failures.add(e.getMessage());
      }
    }
    assertFailures(failures);
  }

  @Test
  @DisplayName("INJ-04 | normal sorgu bilinen kaydi bulur (ozel karakterli sorgulardan sonra da)")
  void inj04_normalQueryFindsKnownTask() {
    String word = "Zanzibarquux" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    HttpResponse<String> created =
        http()
            .post(
                "/api/v1/projects/" + fx().a().projectId() + "/tasks",
                adminA(),
                wsA(),
                "{\"title\":\"" + word + " arama kaydi\"}");
    assertEquals(201, created.statusCode(), describe(created));
    String taskId = json(created).path("id").asString("");

    // Once bir "zehirli" sorgu, sonra normal sorgu: arama durumsuz kalmali.
    http().get("/api/v1/search?q=" + enc("' & ! ( :*"), adminA(), wsA(), null);
    HttpResponse<String> r = http().get("/api/v1/search?q=" + enc(word), adminA(), wsA(), null);
    assertEquals(200, r.statusCode(), describe(r));
    boolean found = false;
    for (JsonNode task : json(r).path("tasks")) {
      found |= taskId.equals(task.path("id").asString(""));
    }
    assertTrue(found, "Bilinen gorev bulunamadi: " + describe(r));

    HttpResponse<String> none =
        http().get("/api/v1/search?q=" + enc(word + "yok"), adminA(), wsA(), null);
    assertEquals(200, none.statusCode(), describe(none));
    assertEquals(0, json(none).path("tasks").size(), "Olmayan sorgu sonuc dondurmemeli");
  }

  // ================================================================ INJ-05

  private static final int INP_TASKS = 205;
  private static final int PAGING_TOTAL = 205;

  private UUID inpProjectId2;
  private UUID inaProjectId;
  private UUID inqProjectId;
  private UUID pagingTaskId;
  private boolean pagingSeeded;

  private UUID makeProject(String key, int taskCount) {
    UUID workspaceId = wsA();
    Project project =
        tenantExecutor.runAs(workspaceId, () -> projectService.createProject(key, "Paging " + key));
    for (int i = 0; i < taskCount; i++) {
      final int n = i;
      tenantExecutor.runAs(
          workspaceId,
          () -> taskService.createTask(project.getId(), key + " task " + n, null, null));
    }
    return project.getId();
  }

  private void updateTasks(String sql, Object... params) {
    try (java.sql.Connection connection = openMigratorConnection();
        java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.executeUpdate();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Her sayfalama ucu icin sinirdan (200; inbox 100) buyuk veri: proje gorevleri (INP),
   * onaylananlar (INA; onay app_migrator JDBC ile), benim islerim (INQ; atama JDBC ile), aktivite
   * ve yorumlar (205 yorum + 205 vade degisikligi), inbox (205 olay).
   */
  private synchronized void seedPaging() {
    if (pagingSeeded) {
      return;
    }
    UUID workspaceId = wsA();
    inpProjectId2 = makeProject("INP", INP_TASKS);
    inaProjectId = makeProject("INA", PAGING_TOTAL);
    updateTasks(
        "UPDATE tasks SET status = 'Done', approved_at = now() - (task_number * interval '1 second')"
            + " WHERE project_id = ?",
        inaProjectId);
    inqProjectId = makeProject("INQ", PAGING_TOTAL);
    updateTasks(
        "UPDATE tasks SET assignee_id = ? WHERE project_id = ?",
        fx().a().developer().userId(),
        inqProjectId);

    UUID taskId =
        tenantExecutor
            .runAs(
                workspaceId,
                () -> taskService.createTask(inpProjectId2, "Paging activity task", null, null))
            .getId();
    pagingTaskId = taskId;
    UUID adminId = adminA().userId();
    for (int i = 0; i < PAGING_TOTAL; i++) {
      final int n = i;
      tenantExecutor.runAs(
          workspaceId, () -> commentService.addComment(taskId, "yorum " + n, adminId));
      tenantExecutor.runAs(
          workspaceId,
          () ->
              taskService.updateDueDate(
                  taskId, java.time.LocalDate.of(2099, 1, 1).plusDays(n), adminId));
    }
    for (int i = 0; i < PAGING_TOTAL; i++) {
      inboxConsumer.onMessage(
          inboxEnvelope(UUID.randomUUID(), fx().a().taskId(), List.of("PAGE-" + i, "PAGE-X")));
    }
    pagingSeeded = true;
  }

  private UUID inpProject() {
    seedPaging();
    return inpProjectId2;
  }

  private record Paged(String name, Actor actor, String path) {}

  private List<Paged> pagedEndpoints() {
    seedPaging();
    return List.of(
        new Paged("proje gorevleri", adminA(), "/api/v1/projects/" + inpProjectId2 + "/tasks"),
        new Paged(
            "onaylanan gorevler", adminA(), "/api/v1/projects/" + inaProjectId + "/tasks/approved"),
        new Paged("benim islerim", fx().a().developer(), "/api/v1/me/tasks"),
        new Paged("aktivite akisi", adminA(), "/api/v1/tasks/" + pagingTaskId + "/activity"),
        new Paged("yorumlar", adminA(), "/api/v1/tasks/" + pagingTaskId + "/comments"),
        new Paged("inbox", fx().a().developer(), "/api/v1/notifications"));
  }

  private String b64(String raw) {
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("INJ-05 | bozuk cursor (base64 degil, gecerli base64 bozuk icerik, uzun) 400/422")
  void inj05_malformedCursorRejected() {
    List<String> cursors =
        List.of(
            "!!!not-base64!!!",
            b64("hello world"),
            b64("abc:def"),
            b64("1700000000000:not-a-uuid"),
            b64("notanumber:" + UUID.randomUUID()),
            "A".repeat(5000));
    List<String> failures = new ArrayList<>();
    for (Paged endpoint : pagedEndpoints()) {
      for (String cursor : cursors) {
        HttpResponse<String> r =
            http().get(endpoint.path() + "?cursor=" + enc(cursor), endpoint.actor(), wsA(), null);
        String label =
            endpoint.name()
                + " cursor="
                + (cursor.length() > 30 ? cursor.substring(0, 30) + "..." : cursor);
        if (r.statusCode() != 400 && r.statusCode() != 422) {
          failures.add(label + " -> " + describe(r));
        }
      }
    }
    assertFailures(failures);
  }

  @Test
  @DisplayName("INJ-05 | bozuk cursor yanitlari ic bilgi sizdirmaz, sonraki gecerli istek calisir")
  void inj05_malformedCursorResponsesAreSafe() {
    List<String> failures = new ArrayList<>();
    for (Paged endpoint : pagedEndpoints()) {
      HttpResponse<String> bad =
          http().get(endpoint.path() + "?cursor=" + enc("%%%"), endpoint.actor(), wsA(), null);
      try {
        assertProblem(bad, 400, endpoint.name() + " bozuk cursor");
      } catch (AssertionError e) {
        failures.add(e.getMessage());
      }
      HttpResponse<String> ok = http().get(endpoint.path(), endpoint.actor(), wsA(), null);
      if (ok.statusCode() != 200 || !json(ok).path("data").isArray()) {
        failures.add(endpoint.name() + " kabul esigi (cursor'suz) -> " + describe(ok));
      }
    }
    assertFailures(failures);
  }

  @Test
  @DisplayName(
      "INJ-05 | baska kiracinin cursor'u: 4xx ya da 200 ile yalniz A verisi, B gorev id kesisimi bos")
  void inj05_foreignTenantCursorNeverLeaksTenantData() {
    // B'nin tum gorev id'leri (projede 2 gorev) ve gercek bir next_cursor (limit=1).
    HttpResponse<String> bAll =
        http()
            .get(
                "/api/v1/projects/" + fx().b().projectId() + "/tasks?limit=200",
                fx().b().admin(),
                fx().b().workspaceId(),
                null);
    assertEquals(200, bAll.statusCode(), describe(bAll));
    Set<String> bIds = new HashSet<>();
    for (JsonNode t : json(bAll).path("data")) {
      bIds.add(t.path("id").asString(""));
    }
    assertTrue(bIds.size() >= 2, "B gorev kumesi bos: " + describe(bAll));
    HttpResponse<String> bPage =
        http()
            .get(
                "/api/v1/projects/" + fx().b().projectId() + "/tasks?limit=1",
                fx().b().admin(),
                fx().b().workspaceId(),
                null);
    String foreignCursor = json(bPage).path("next_cursor").asString("");
    assertFalse(foreignCursor.isBlank(), "B listesinde next_cursor yok: " + describe(bPage));

    // Kabul esigi: A kendi cursor'uyla 200 ve A gorevleri.
    HttpResponse<String> aFirst =
        http().get("/api/v1/projects/" + inpProject() + "/tasks?limit=1", adminA(), wsA(), null);
    assertEquals(200, aFirst.statusCode(), describe(aFirst));
    String ownCursor = json(aFirst).path("next_cursor").asString("");
    HttpResponse<String> own =
        http()
            .get(
                "/api/v1/projects/" + inpProject() + "/tasks?cursor=" + enc(ownCursor),
                adminA(),
                wsA(),
                null);
    assertEquals(200, own.statusCode(), describe(own));
    assertTrue(json(own).path("data").size() > 0, "Kendi cursor'u veri dondurmeli");

    List<String> failures = new ArrayList<>();
    for (Paged endpoint : pagedEndpoints()) {
      HttpResponse<String> r =
          http()
              .get(
                  endpoint.path() + "?cursor=" + enc(foreignCursor), endpoint.actor(), wsA(), null);
      if (r.statusCode() >= 500) {
        failures.add(endpoint.name() + " -> " + describe(r));
        continue;
      }
      if (r.statusCode() == 400 || r.statusCode() == 422) {
        continue;
      }
      if (r.statusCode() != 200) {
        failures.add(endpoint.name() + " beklenmeyen durum -> " + describe(r));
        continue;
      }
      for (JsonNode item : json(r).path("data")) {
        String id = item.path("id").asString("");
        if (bIds.contains(id)
            || fx().b().workspaceId().toString().equals(item.path("workspaceId").asString(""))
            || fx().b().projectId().toString().equals(item.path("projectId").asString(""))) {
          failures.add(endpoint.name() + " B kaydi dondu: " + id);
        }
      }
    }
    assertFailures(failures);
  }

  @Test
  @DisplayName("INJ-05 | sayi olmayan limit 400/422, 500 degil (tum sayfalama uclari)")
  void inj05_nonNumericLimitRejected() {
    List<String> limits = List.of("abc", "1.5", "99999999999999999999", "1e3", "%00");
    List<String> failures = new ArrayList<>();
    for (Paged endpoint : pagedEndpoints()) {
      for (String limit : limits) {
        HttpResponse<String> r =
            http().get(endpoint.path() + "?limit=" + enc(limit), endpoint.actor(), wsA(), null);
        if (r.statusCode() != 400 && r.statusCode() != 422) {
          failures.add(endpoint.name() + " limit='" + limit + "' -> " + describe(r));
        }
      }
    }
    assertFailures(failures);
  }

  @Test
  @DisplayName(
      "INJ-05 | negatif/sifir/cok buyuk limit [1,200]'e kirpilir (her uc, iceriden olculur)")
  void inj05_outOfRangeLimitIsClampedOnEveryEndpoint() {
    List<String> failures = new ArrayList<>();
    for (Paged endpoint : pagedEndpoints()) {
      for (String limit : List.of("-1", "0", "-2147483648", "2147483647", "100000")) {
        HttpResponse<String> r =
            http().get(endpoint.path() + "?limit=" + limit, endpoint.actor(), wsA(), null);
        if (r.statusCode() == 400 || r.statusCode() == 422) {
          continue; // reddetmek de kabul
        }
        if (r.statusCode() != 200) {
          failures.add(endpoint.name() + " limit=" + limit + " -> " + describe(r));
          continue;
        }
        int size = json(r).path("data").size();
        if (size < 1 || size > 200) {
          failures.add(
              endpoint.name() + " limit=" + limit + " -> " + size + " eleman (1..200 olmali)");
        }
        if (!json(r).path("has_more").asBoolean(false)) {
          failures.add(endpoint.name() + " limit=" + limit + " -> has_more false (veri 205+ var)");
        }
        if (limit.startsWith("-") || limit.equals("0")) {
          if (size > 200) {
            failures.add(endpoint.name() + " limit=" + limit + " kirpilmadi");
          }
        }
      }
      HttpResponse<String> normal =
          http().get(endpoint.path() + "?limit=5", endpoint.actor(), wsA(), null);
      if (normal.statusCode() != 200 || json(normal).path("data").size() != 5) {
        failures.add(endpoint.name() + " kabul esigi limit=5 -> " + describe(normal));
      }
    }
    assertFailures(failures);
  }

  @Test
  @DisplayName(
      "INJ-05 | gecerli cursor sonraki sayfayi doner: her uc icin ortusmeyen sayfalar, eksiksiz")
  void inj05_validCursorReturnsNextPageOnEveryEndpoint() {
    List<String> failures = new ArrayList<>();
    for (Paged endpoint : pagedEndpoints()) {
      Set<String> seen = new HashSet<>();
      String cursor = null;
      int pages = 0;
      do {
        String path =
            endpoint.path() + "?limit=100" + (cursor == null ? "" : "&cursor=" + enc(cursor));
        HttpResponse<String> r = http().get(path, endpoint.actor(), wsA(), null);
        if (r.statusCode() != 200) {
          failures.add(endpoint.name() + " sayfa " + pages + " -> " + describe(r));
          break;
        }
        JsonNode page = json(r);
        for (JsonNode item : page.path("data")) {
          if (!seen.add(item.path("id").asString(""))) {
            failures.add(
                endpoint.name() + " sayfalar arasi tekrar: " + item.path("id").asString(""));
          }
        }
        cursor =
            page.path("has_more").asBoolean(false) ? page.path("next_cursor").asString("") : null;
        pages++;
      } while (cursor != null && !cursor.isBlank() && pages < 20);
      if (seen.size() < PAGING_TOTAL || pages < 3) {
        failures.add(
            endpoint.name()
                + ": "
                + seen.size()
                + " kayit, "
                + pages
                + " sayfa (>= "
                + PAGING_TOTAL
                + " ve >= 3 beklenir)");
      }
    }
    assertFailures(failures);
  }

  // ================================================================ INJ-06

  private static final String EVIL_BCC_PREFIX = "sec-evilbcc-";

  private String evilAddress() {
    return EVIL_BCC_PREFIX + UUID.randomUUID() + "@tracker.local";
  }

  private HttpResponse<String> createWorkspace(String name) {
    return http()
        .post(
            "/api/v1/workspaces", adminA(), null, mapper.writeValueAsString(Map.of("name", name)));
  }

  @Test
  @DisplayName("INJ-06 | satir sonu iceren workspace adi davet e-postasina ek baslik tasimaz")
  void inj06_workspaceNameCannotInjectMailHeaders() {
    String evil = evilAddress();
    List<String> names =
        List.of(
            "Acme\r\nBcc: " + evil,
            "Acme\nBcc: " + evil,
            "Acme\r\nX-Injected: 1\r\n\r\nGovde",
            "Acme%0d%0aBcc: " + evil,
            "Acme\r\nSubject: pwned");
    List<String> failures = new ArrayList<>();
    for (String name : names) {
      String label = name.replace("\r", "\\r").replace("\n", "\\n");
      HttpResponse<String> created = createWorkspace(name);
      System.out.println("[D6-INJ06] workspace '" + label + "' -> " + created.statusCode());
      if (created.statusCode() == 400 || created.statusCode() == 422) {
        continue; // girdide reddedildi: ek baslik tasinamaz
      }
      if (created.statusCode() != 201) {
        failures.add("workspace '" + label + "' -> " + describe(created));
        continue;
      }
      UUID workspaceId = UUID.fromString(json(created).path("id").asString(""));
      String invitee = "sec-inj06-" + UUID.randomUUID() + "@tracker.local";
      HttpResponse<String> invite =
          http()
              .post(
                  "/api/v1/workspaces/members/invitations",
                  adminA(),
                  workspaceId,
                  "{\"email\":\"" + invitee + "\",\"role\":\"" + WorkspaceRole.DEVELOPER + "\"}");
      if (invite.statusCode() != 201) {
        failures.add("davet '" + label + "' -> " + describe(invite));
        continue;
      }
      try {
        JsonNode mail = awaitMail(invitee, m -> true, "davet " + label);
        System.out.println(
            "[D6-INJ06] konu="
                + mail.path("Subject").asString("").replace("\r", "\\r").replace("\n", "\\n"));
        assertMailNotInjected(mail, invitee, "workspace adi '" + label + "'");
      } catch (AssertionError e) {
        failures.add(e.getMessage());
      }
    }
    assertFailures(failures);
  }

  @Test
  @DisplayName("INJ-06 | normal workspace adi davet e-postasinin konusunda gorunur")
  void inj06_normalWorkspaceNameAppearsInSubject() {
    String name = "Acme Insaat " + UUID.randomUUID().toString().substring(0, 8);
    HttpResponse<String> created = createWorkspace(name);
    assertEquals(201, created.statusCode(), describe(created));
    UUID workspaceId = UUID.fromString(json(created).path("id").asString(""));
    String invitee = "sec-inj06k-" + UUID.randomUUID() + "@tracker.local";
    HttpResponse<String> invite =
        http()
            .post(
                "/api/v1/workspaces/members/invitations",
                adminA(),
                workspaceId,
                "{\"email\":\"" + invitee + "\",\"role\":\"" + WorkspaceRole.DEVELOPER + "\"}");
    assertEquals(201, invite.statusCode(), describe(invite));
    JsonNode mail = awaitMail(invitee, m -> true, "normal davet");
    assertTrue(
        mail.path("Subject").asString("").contains(name),
        "Konuda workspace adi yok: " + mail.path("Subject").asString(""));
    assertMailNotInjected(mail, invitee, "normal ad");
  }

  @Test
  @DisplayName("INJ-06 | satir sonu iceren davet alicisi reddedilir, ek alici olusmaz")
  void inj06_recipientWithLineBreakRejected() {
    String evil = evilAddress();
    String victim = "sec-inj06r-" + UUID.randomUUID() + "@tracker.local";
    HttpResponse<String> r =
        http()
            .post(
                "/api/v1/workspaces/members/invitations",
                adminA(),
                wsA(),
                mapper.writeValueAsString(
                    Map.of("email", victim + "\r\nBcc: " + evil, "role", WorkspaceRole.DEVELOPER)));
    assertStatusIn(r, "satir sonlu alici", 400, 422);

    // Kabul esigi: duz adres kabul edilir ve tek alicili posta gider.
    String clean = "sec-inj06c-" + UUID.randomUUID() + "@tracker.local";
    HttpResponse<String> ok =
        http()
            .post(
                "/api/v1/workspaces/members/invitations",
                adminA(),
                wsA(),
                "{\"email\":\"" + clean + "\",\"role\":\"" + WorkspaceRole.DEVELOPER + "\"}");
    assertEquals(201, ok.statusCode(), describe(ok));
    assertMailNotInjected(awaitMail(clean, m -> true, "temiz davet"), clean, "temiz alici");
    assertEquals(0, mailSummariesTo(evil).size(), "Enjekte adrese posta gitti");
  }

  @Test
  @DisplayName("INJ-06 | satir sonlu kullanici adi dogrulama e-postasina baslik tasimaz")
  void inj06_userNameCannotInjectMailHeaders() {
    String evil = evilAddress();
    String email = "sec-inj06u-" + UUID.randomUUID() + "@tracker.local";
    String body =
        mapper.writeValueAsString(
            Map.of(
                "email",
                email,
                "password",
                SecurityFixture.PASSWORD,
                "fullName",
                "Ali\r\nBcc: " + evil,
                "privacyNoticeAccepted",
                true,
                "privacyNoticeVersion",
                PrivacyNotice.CURRENT_VERSION));
    HttpResponse<String> r = http().post("/api/v1/auth/register", (String) null, null, body);
    if (r.statusCode() == 400 || r.statusCode() == 422) {
      return; // girdide reddedildi
    }
    assertEquals(201, r.statusCode(), describe(r));
    JsonNode mail = awaitMail(email, m -> true, "dogrulama");
    assertMailNotInjected(mail, email, "kullanici adi");
    assertEquals(0, mailSummariesTo(evil).size(), "Enjekte adrese posta gitti");
  }

  @Test
  @DisplayName(
      "INJ-06 | satir sonlu gorev basligi atama e-postasina baslik tasimaz; normal baslik gorunur")
  void inj06_taskTitleCannotInjectMailHeaders() {
    Actor developer = fx().a().developer();
    String evil = evilAddress();
    String marker = "mk" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    HttpResponse<String> plain = assignTask(marker + "n", developer);
    assertEquals(201, plain.statusCode(), describe(plain));
    JsonNode plainMail =
        awaitMail(
            developer.email(),
            m -> m.path("Text").asString("").contains(marker + "n"),
            "normal baslik");
    assertMailNotInjected(plainMail, developer.email(), "normal baslik");
    assertTrue(
        plainMail.path("Text").asString("").contains(marker + "n"), "Normal baslik metinde yok");

    String evilTitle = marker + "x\r\nBcc: " + evil + "\r\nX-Injected: 1";
    HttpResponse<String> r = assignTask(evilTitle, developer);
    if (r.statusCode() == 400 || r.statusCode() == 422) {
      return;
    }
    assertEquals(201, r.statusCode(), describe(r));
    JsonNode mail =
        awaitMail(
            developer.email(),
            m -> m.path("Text").asString("").contains(marker + "x"),
            "evil baslik");
    assertMailNotInjected(mail, developer.email(), "gorev basligi");
    assertEquals(0, mailSummariesTo(evil).size(), "Enjekte adrese posta gitti");
  }

  private HttpResponse<String> assignTask(String title, Actor assignee) {
    return http()
        .post(
            "/api/v1/projects/" + fx().a().projectId() + "/tasks",
            adminA(),
            wsA(),
            mapper.writeValueAsString(Map.of("title", title, "assigneeId", assignee.userId())));
  }

  // ================================================================ INJ-08

  private String bigErrorBody(int bytes) {
    return "{\"errorType\":\"TypeError\",\"message\":\"" + "a".repeat(bytes) + "\"}";
  }

  @Test
  @DisplayName("INJ-08 | 10 MB JSON govde 413/400 doner, 500 degil")
  void inj08_tenMegabyteBodyRejected() {
    String body = bigErrorBody(10 * 1024 * 1024);
    HttpResponse<String> authed =
        call(
            "POST",
            "/api/v1/telemetry/errors",
            adminA().accessToken(),
            wsA(),
            "application/json",
            body);
    assertStatusIn(authed, "10 MB govde (kimlikli)", 413, 400);
    assertNoLeak(authed, "10 MB govde");
    // Sunucu ayakta ve normal govde kabul.
    assertNormalErrorReportAccepted();
  }

  @Test
  @DisplayName("INJ-08 | kimliksiz 10 MB JSON govde (login) 413/400 doner")
  void inj08_tenMegabyteAnonymousBodyRejected() {
    HttpResponse<String> anon =
        call(
            "POST",
            "/api/v1/auth/login",
            null,
            null,
            "application/json",
            "{\"email\":\"a@b.c\",\"password\":\"" + "p".repeat(10 * 1024 * 1024) + "\"}");
    assertStatusIn(anon, "10 MB govde (kimliksiz login)", 413, 400);
  }

  @Test
  @DisplayName(
      "INJ-08 | 10 000 seviye ic ice JSON dizi/obje reddedilir (400/413/422), sunucu ayakta")
  void inj08_deeplyNestedJsonRejected() {
    String arrays = "[".repeat(10_000) + "]".repeat(10_000);
    String objects = "{\"a\":".repeat(10_000) + "1" + "}".repeat(10_000);
    String errorWrapped = "{\"errorType\":\"T\",\"message\":" + arrays + "}";
    List<String> failures = new ArrayList<>();
    for (Map.Entry<String, String> body :
        Map.of("dizi", arrays, "obje", objects, "alan icinde dizi", errorWrapped).entrySet()) {
      HttpResponse<String> r =
          call(
              "POST",
              "/api/v1/telemetry/errors",
              adminA().accessToken(),
              wsA(),
              "application/json",
              body.getValue());
      if (r.statusCode() != 400 && r.statusCode() != 413 && r.statusCode() != 422) {
        failures.add(body.getKey() + " -> " + describe(r));
        continue;
      }
      try {
        assertNoLeak(r, body.getKey());
      } catch (AssertionError e) {
        failures.add(e.getMessage());
      }
    }
    assertFailures(failures);
    assertNormalErrorReportAccepted();
  }

  @Test
  @DisplayName("INJ-08 | normal boyutlu govde kabul edilir")
  void inj08_normalBodyAccepted() {
    assertNormalErrorReportAccepted();
  }

  private void assertNormalErrorReportAccepted() {
    HttpResponse<String> ok =
        call(
            "POST",
            "/api/v1/telemetry/errors",
            adminA().accessToken(),
            wsA(),
            "application/json",
            "{\"errorType\":\"TypeError-inj08\",\"message\":\"x is undefined\",\"path\":\"/x\"}");
    assertEquals(202, ok.statusCode(), describe(ok));
  }

  // ================================================================ INJ-09

  private String inboxEnvelope(UUID eventId, UUID taskId, List<String> blockerKeys) {
    return mapper.writeValueAsString(
        Map.of(
            "eventId", eventId.toString(),
            "eventType", NotificationMessageFormatter.TASK_TRANSITION_BLOCKED,
            "workspaceId", wsA().toString(),
            "payload",
                Map.of(
                    "taskId", taskId.toString(),
                    "actorId", adminA().userId().toString(),
                    "blockerKeys", blockerKeys)));
  }

  private JsonNode developerNotificationContaining(String needle) {
    HttpResponse<String> r =
        http().get("/api/v1/notifications?limit=100", fx().a().developer(), wsA(), null);
    assertEquals(200, r.statusCode(), describe(r));
    for (JsonNode n : json(r).path("data")) {
      if (NotificationMessageFormatter.TASK_TRANSITION_BLOCKED.equals(n.path("type").asString(""))
          && n.path("body").asString("").contains(needle)) {
        return n;
      }
    }
    return null;
  }

  @Test
  @DisplayName(
      "INJ-09 | cok bloklayanli TASK_TRANSITION_BLOCKED bildirimi kalici hata uretmez, govde <= 500")
  void inj09_manyBlockersNotificationFitsColumn() {
    List<String> keys = new ArrayList<>();
    for (int i = 1; i <= 300; i++) {
      keys.add("LONGPROJKEY-" + (100_000_000 + i));
    }
    String envelope = inboxEnvelope(UUID.randomUUID(), fx().a().taskId(), keys);
    assertDoesNotThrow(() -> inboxConsumer.onMessage(envelope), "tuketici kalici hata verdi (DLT)");

    JsonNode notification = developerNotificationContaining("LONGPROJKEY-100000001");
    assertNotNull(notification, "Bildirim yazilmadi (gelen kutusunda yok)");
    String body = notification.path("body").asString("");
    assertTrue(body.length() <= 500, "govde " + body.length() + " karakter (sinir 500)");
    assertTrue(notification.path("title").asString("").length() <= 255, "baslik 255'i asti");
    assertTrue(
        body.contains("daha"), "Kirpilan liste ozetlenmeli ('... ve N gorev daha'): " + body);
  }

  @Test
  @DisplayName("INJ-09 | kisa bloklayan listesinde tam metin yazilir")
  void inj09_shortBlockerListWrittenInFull() {
    String envelope =
        inboxEnvelope(UUID.randomUUID(), fx().a().taskId(), List.of("ZZINJ-2", "YYINJ-7"));
    assertDoesNotThrow(() -> inboxConsumer.onMessage(envelope));
    JsonNode notification = developerNotificationContaining("ZZINJ-2");
    assertNotNull(notification, "Bildirim yazilmadi");
    assertEquals(
        "PR merge edildi ama gorev Done'a tasinmadi; once bitmesi gereken: ZZINJ-2, YYINJ-7",
        notification.path("body").asString(""));
  }

  @Test
  @DisplayName(
      "INJ-09 | 5000 karakterlik gorev basligi bildirim basligini 255'e sigdirir; API 5000'liyi 422 ile reddeder")
  void inj09_veryLongTaskTitleIsBoundedAndRejectedAtApi() {
    // Gorev basligi sutunu VARCHAR(255): 5000 karakterlik baslik kayda hic girmemeli (422).
    HttpResponse<String> tooLong =
        http()
            .post(
                "/api/v1/projects/" + fx().a().projectId() + "/tasks",
                adminA(),
                wsA(),
                mapper.writeValueAsString(Map.of("title", "T".repeat(5000))));
    assertStatusIn(tooLong, "5000 karakterlik baslik", 422);
    assertNoLeak(tooLong, "5000 karakterlik baslik");

    // Kabul esigi: 255 karakter (sinir) kabul edilir ve bildirim basligi sutuna sigar.
    String maxTitle = "M".repeat(255);
    HttpResponse<String> ok =
        http()
            .post(
                "/api/v1/projects/" + fx().a().projectId() + "/tasks",
                adminA(),
                wsA(),
                mapper.writeValueAsString(Map.of("title", maxTitle)));
    assertEquals(201, ok.statusCode(), describe(ok));

    // Bicimleyici 5000 karakterlik baslikla (eski/ice veri) da basligi sutuna sigdirmali.
    var summary =
        new NotificationMessageFormatter.TaskSummary("ABCDEFGHIJ", 999_999_999, "T".repeat(5000));
    String[] message =
        NotificationMessageFormatter.format(
                NotificationMessageFormatter.COMMENT_ADDED, mapper.createObjectNode(), summary)
            .orElseThrow();
    assertTrue(message[0].length() <= 255, "baslik " + message[0].length() + " > 255");
    assertTrue(message[1].length() <= 500, "govde > 500");
  }

  private UUID consumeError(String errorType, String message, String path) {
    UUID eventId = UUID.randomUUID();
    String envelope =
        mapper.writeValueAsString(
            Map.of(
                "eventId", eventId.toString(),
                "eventType", "ERROR_OCCURRED",
                "schemaVersion", 1,
                "timestamp", Instant.now().toString(),
                "workspaceId", wsA().toString(),
                "payload",
                    Map.of(
                        "source", "backend",
                        "errorType", errorType,
                        "message", message,
                        "path", path)));
    assertDoesNotThrow(
        () -> telemetryConsumer.onMessage(envelope),
        "tuketici sutun sinirini asan alanda kalici hata verdi (DLT'ye gider)");
    return eventId;
  }

  @Test
  @DisplayName("INJ-09 | cok uzun (5000) telemetri mesaji tuketicide kalici hata uretmez")
  void inj09_longTelemetryMessageDoesNotBreakConsumer() {
    UUID eventId = consumeError("IllegalStateException", "m".repeat(5000), "/api/v1/x");
    String stored =
        jdbc.queryForObject("SELECT message FROM error_events WHERE id = ?", String.class, eventId);
    assertNotNull(stored);
    assertTrue(stored.length() <= 5000);
  }

  @Test
  @DisplayName(
      "INJ-09 | 255'ten uzun istek yolu (backend 500 yolu = getRequestURI) tuketicide kalici hata uretmez")
  void inj09_longTelemetryPathDoesNotBreakConsumer() {
    UUID eventId = consumeError("IllegalStateException", "kisa", "/api/v1/" + "p".repeat(300));
    String path =
        jdbc.queryForObject("SELECT path FROM error_events WHERE id = ?", String.class, eventId);
    assertNotNull(path);
    assertTrue(path.length() <= 255, "path > 255");
  }

  @Test
  @DisplayName("INJ-09 | 200'den uzun hata tipi tuketicide kalici hata uretmez")
  void inj09_longTelemetryErrorTypeDoesNotBreakConsumer() {
    UUID eventId = consumeError("E".repeat(250), "kisa", "/api/v1/x");
    String type =
        jdbc.queryForObject(
            "SELECT error_type FROM error_events WHERE id = ?", String.class, eventId);
    assertNotNull(type);
    assertTrue(type.length() <= 200, "error_type > 200");
  }

  @Test
  @DisplayName("INJ-09 | kisa hata telemetrisi tam metin yazilir")
  void inj09_shortTelemetryErrorWrittenInFull() {
    UUID eventId = UUID.randomUUID();
    String envelope =
        mapper.writeValueAsString(
            Map.of(
                "eventId", eventId.toString(),
                "eventType", "ERROR_OCCURRED",
                "schemaVersion", 1,
                "timestamp", Instant.now().toString(),
                "workspaceId", wsA().toString(),
                "payload",
                    Map.of(
                        "source", "backend",
                        "errorType", "IllegalStateException",
                        "message", "kisa mesaj",
                        "path", "/api/v1/x")));
    telemetryConsumer.onMessage(envelope);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT error_type, message, path FROM error_events WHERE id = ?", eventId);
    assertEquals("IllegalStateException", row.get("error_type"));
    assertEquals("kisa mesaj", row.get("message"));
    assertEquals("/api/v1/x", row.get("path"));
  }

  // ================================================================ CFG-04

  @Test
  @DisplayName(
      "CFG-04 | hata yanitlari ProblemDetail, ic bilgi sizdirmaz (404/405/415/400/422/403)")
  void cfg04_errorResponsesAreSafeProblemDetails() {
    Actor admin = adminA();
    String token = admin.accessToken();
    List<String> failures = new ArrayList<>();
    record Case(String name, int status, HttpResponse<String> response) {}
    List<Case> cases =
        List.of(
            new Case(
                "404 olmayan uc",
                404,
                call("GET", "/api/v1/does-not-exist", token, wsA(), null, null)),
            new Case(
                "405 metod", 405, call("DELETE", "/api/v1/search?q=x", token, wsA(), null, null)),
            new Case(
                "415 icerik tipi",
                415,
                call(
                    "POST", "/api/v1/telemetry/errors", token, wsA(), "text/plain", "errorType=x")),
            new Case(
                "400 bozuk JSON",
                400,
                call(
                    "POST",
                    "/api/v1/telemetry/errors",
                    token,
                    wsA(),
                    "application/json",
                    "{\"errorType\": ")),
            new Case(
                "400 eksik parametre",
                400,
                call("GET", "/api/v1/search", token, wsA(), null, null)),
            new Case(
                "400 yol parametresi tipi",
                400,
                call("GET", "/api/v1/projects/not-a-uuid/tasks", token, wsA(), null, null)),
            new Case(
                "422 dogrulama",
                422,
                call(
                    "POST",
                    "/api/v1/telemetry/errors",
                    token,
                    wsA(),
                    "application/json",
                    "{\"errorType\":\"\"}")),
            new Case(
                "403 yetki",
                403,
                call(
                    "POST",
                    "/api/v1/projects/" + fx().a().projectId() + "/tasks",
                    fx().a().viewer().accessToken(),
                    wsA(),
                    "application/json",
                    "{\"title\":\"x\"}")),
            new Case(
                "404 kaynak",
                404,
                call(
                    "GET",
                    "/api/v1/tasks/" + UUID.randomUUID() + "/detail",
                    token,
                    wsA(),
                    null,
                    null)),
            new Case(
                "400 is kurali (bozuk cursor)",
                400,
                call(
                    "GET",
                    "/api/v1/projects/" + fx().a().projectId() + "/tasks?cursor=%25%25",
                    token,
                    wsA(),
                    null,
                    null)));
    for (Case c : cases) {
      try {
        assertProblem(c.response(), c.status(), c.name());
      } catch (AssertionError e) {
        failures.add(e.getMessage());
      }
    }
    assertFailures(failures);
  }

  @Test
  @DisplayName("CFG-04 | 500'u tetikleyebilen girdilerin yanitlari ProblemDetail ve ic bilgisiz")
  void cfg04_potential500InputsStayProblemDetails() {
    List<String> failures = new ArrayList<>();
    List<String> paths =
        List.of(
            "/api/v1/search?q=" + enc("\u0000"),
            "/api/v1/search?q=" + enc("a\u0000b"),
            "/api/v1/search?q=" + enc("'\"\\"),
            "/api/v1/projects/" + fx().a().projectId() + "/tasks?limit=" + enc("\u0000"),
            "/api/v1/projects/" + fx().a().projectId() + "/tasks/calendar?from=x&to=y",
            "/api/v1/meetings/occurrences?from=2099-01-01&to=2099-12-31");
    for (String path : paths) {
      HttpResponse<String> r = call("GET", path, adminA().accessToken(), wsA(), null, null);
      if (r.statusCode() < 400) {
        continue; // hata degil
      }
      // 500'un kendisi INJ-04'un konusu; burada yalniz bicim (problem+json, status/title) ve ic
      // bilgi yoklugu olculur, 500 dahil.
      try {
        assertProblem(r, r.statusCode(), path.replace("\u0000", "<NUL>"));
      } catch (AssertionError e) {
        failures.add(e.getMessage());
      }
    }
    assertFailures(failures);
  }

  @Test
  @DisplayName(
      "CFG-04 | 422 yanitinda ProblemDetail alanlari (status, title, invalid_params) mevcut")
  void cfg04_validationProblemDetailHasFields() {
    HttpResponse<String> r =
        call(
            "POST",
            "/api/v1/telemetry/errors",
            adminA().accessToken(),
            wsA(),
            "application/json",
            "{\"errorType\":\"\"}");
    assertProblem(r, 422, "422 dogrulama");
    JsonNode problem = json(r);
    assertTrue(
        problem.path("invalid_params").isArray(), "invalid_params dizisi yok: " + describe(r));
    assertEquals("errorType", problem.path("invalid_params").get(0).path("field").asString(""));
  }

  // ================================================================ CFG-06

  private HttpResponse<String> preflight(String origin) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Access-Control-Request-Method", "POST");
    headers.put("Access-Control-Request-Headers", "authorization,content-type,x-workspace-id");
    if (origin != null) {
      headers.put("Origin", origin);
    }
    return http()
        .send(
            "OPTIONS",
            "/api/v1/projects/" + fx().a().projectId() + "/tasks",
            (String) null,
            null,
            null,
            headers);
  }

  @Test
  @DisplayName(
      "CFG-06 | izinli olmayan Origin ve null Origin ile preflight Access-Control-Allow-Origin dondurmez")
  void cfg06_unlistedAndNullOriginPreflightRejected() {
    List<String> failures = new ArrayList<>();
    for (String origin :
        List.of(
            "https://evil.example",
            "null",
            "http://localhost:5173.evil.example",
            "https://evil.example:443")) {
      HttpResponse<String> r = preflight(origin);
      String acao = r.headers().firstValue("Access-Control-Allow-Origin").orElse(null);
      if (acao != null) {
        failures.add("Origin " + origin + " -> ACAO '" + acao + "' (" + describe(r) + ")");
      }
      if (r.statusCode() / 100 == 2) {
        failures.add("Origin " + origin + " preflight 2xx: " + describe(r));
      }
      if (r.statusCode() >= 500) {
        failures.add("Origin " + origin + " preflight 5xx: " + describe(r));
      }
    }
    assertFailures(failures);
  }

  @Test
  @DisplayName("CFG-06 | hicbir yanitta ACAO:* ile Allow-Credentials:true birlikte yok")
  void cfg06_noWildcardWithCredentials() {
    List<HttpResponse<String>> responses = new ArrayList<>();
    for (String origin : List.of("https://evil.example", "null", "http://localhost:5173")) {
      responses.add(preflight(origin));
      Map<String, String> headers = Map.of("Origin", origin);
      responses.add(
          http().send("GET", "/api/v1/search?q=x", adminA().accessToken(), wsA(), null, headers));
      responses.add(
          http()
              .send("GET", "/api/v1/does-not-exist", adminA().accessToken(), wsA(), null, headers));
      responses.add(http().send("GET", "/v3/api-docs", (String) null, null, null, headers));
      responses.add(http().send("GET", "/api/v1/search?q=x", (String) null, null, null, headers));
    }
    List<String> failures = new ArrayList<>();
    for (HttpResponse<String> r : responses) {
      String acao = r.headers().firstValue("Access-Control-Allow-Origin").orElse("");
      String acac = r.headers().firstValue("Access-Control-Allow-Credentials").orElse("");
      if ("*".equals(acao) && "true".equalsIgnoreCase(acac)) {
        failures.add(r.request().method() + " " + r.request().uri() + " -> ACAO:* + credentials");
      }
      if ("null".equals(acao)) {
        failures.add(r.request().method() + " " + r.request().uri() + " -> ACAO:null");
      }
    }
    assertFailures(failures);
  }

  private DefaultCorsProcessorResult processCors(List<String> allowedOrigins, String origin) {
    CorsProperties properties = new CorsProperties();
    properties.setAllowedOrigins(allowedOrigins);
    // Uretim yapilandirmasinin kendisi (SecurityConfig.corsConfigurationSource); filtre
    // bagimliliklari
    // yalniz bu bean metodu icin gereksiz.
    SecurityConfig config = new SecurityConfig(null, null, null, null, properties);
    MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/tasks");
    request.addHeader("Origin", origin);
    request.addHeader("Access-Control-Request-Method", "POST");
    request.addHeader(
        "Access-Control-Request-Headers", "authorization,content-type,x-workspace-id");
    CorsConfiguration cors = config.corsConfigurationSource().getCorsConfiguration(request);
    MockHttpServletResponse response = new MockHttpServletResponse();
    try {
      boolean proceed = new DefaultCorsProcessor().processRequest(cors, request, response);
      return new DefaultCorsProcessorResult(proceed, response);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private record DefaultCorsProcessorResult(boolean proceed, MockHttpServletResponse response) {}

  @Test
  @DisplayName(
      "CFG-06 | izinli origin ile preflight izin basliklarini doner; izinsiz/null origin donmez")
  void cfg06_allowedOriginPreflightGetsAllowHeaders() {
    // Calisan context'te izinli origin tanimli degil (CORS_ALLOWED_ORIGINS bos); yapilandirma
    // uretimdeki SecurityConfig.corsConfigurationSource() ile izinli origin verilerek sinanir.
    List<String> allowed = List.of("https://app.example");
    MockHttpServletResponse ok = processCors(allowed, "https://app.example").response();
    assertEquals(200, ok.getStatus());
    assertEquals("https://app.example", ok.getHeader("Access-Control-Allow-Origin"));
    assertEquals("true", ok.getHeader("Access-Control-Allow-Credentials"));
    assertTrue(ok.getHeader("Access-Control-Allow-Methods").contains("POST"));
    String allowHeaders = ok.getHeader("Access-Control-Allow-Headers").toLowerCase();
    assertTrue(allowHeaders.contains("authorization"), allowHeaders);
    assertTrue(allowHeaders.contains("x-workspace-id"), allowHeaders);

    for (String bad : List.of("https://evil.example", "null", "https://app.example.evil.example")) {
      MockHttpServletResponse denied = processCors(allowed, bad).response();
      assertEquals(
          null,
          denied.getHeader("Access-Control-Allow-Origin"),
          "Izinsiz origin '" + bad + "' ACAO aldi");
      assertEquals(403, denied.getStatus(), "Izinsiz origin '" + bad + "' preflight");
    }
  }

  // ================================================================ XSS-03

  private String meetingBody(String title, String meetingUrl) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", title);
    body.put("startDate", "2099-06-15");
    body.put("startTime", "10:00:00");
    body.put("durationMinutes", 30);
    body.put("frequency", "ONCE");
    body.put("intervalCount", 1);
    if (meetingUrl != null) {
      body.put("meetingUrl", meetingUrl);
    }
    return mapper.writeValueAsString(body);
  }

  private static final List<String> BAD_MEETING_URLS =
      List.of(
          "javascript:alert(1)",
          "data:text/html,<script>alert(1)</script>",
          "vbscript:msgbox(1)",
          "JaVaScRiPt:alert(1)",
          "  javascript:alert(1)",
          "\tjavascript:alert(1)",
          "\njavascript:alert(1)",
          "java\tscript:alert(1)",
          "javascript://%0aalert(1)",
          "file:///etc/passwd",
          "http://meet.example.com/x");

  @Test
  @DisplayName("XSS-03 | tehlikeli semali meetingUrl ile olusturma reddedilir (400/422)")
  void xss03_dangerousMeetingUrlRejectedOnCreate() {
    List<String> failures = new ArrayList<>();
    for (String url : BAD_MEETING_URLS) {
      HttpResponse<String> r =
          http().post("/api/v1/meetings", adminA(), wsA(), meetingBody("xss03-create", url));
      if (r.statusCode() != 400 && r.statusCode() != 422) {
        failures.add(
            "create url='" + url.replace("\n", "\\n").replace("\t", "\\t") + "' -> " + describe(r));
      }
    }
    assertFailures(failures);
  }

  @Test
  @DisplayName("XSS-03 | tehlikeli semali meetingUrl ile guncelleme reddedilir, eski deger korunur")
  void xss03_dangerousMeetingUrlRejectedOnUpdate() {
    String goodUrl = "https://meet.example.com/x";
    HttpResponse<String> created =
        http().post("/api/v1/meetings", adminA(), wsA(), meetingBody("xss03-update", goodUrl));
    assertEquals(201, created.statusCode(), describe(created));
    String id = json(created).path("id").asString("");

    List<String> failures = new ArrayList<>();
    for (String url : BAD_MEETING_URLS) {
      HttpResponse<String> r =
          http().put("/api/v1/meetings/" + id, adminA(), wsA(), meetingBody("xss03-update", url));
      if (r.statusCode() != 400 && r.statusCode() != 422) {
        failures.add(
            "update url='" + url.replace("\n", "\\n").replace("\t", "\\t") + "' -> " + describe(r));
      }
    }
    assertFailures(failures);

    HttpResponse<String> list = http().get("/api/v1/meetings", adminA(), wsA(), null);
    assertEquals(200, list.statusCode(), describe(list));
    String stored = null;
    for (JsonNode meeting : json(list)) {
      if (id.equals(meeting.path("id").asString(""))) {
        stored = meeting.path("meetingUrl").asString("");
      }
    }
    assertEquals(goodUrl, stored, "Reddedilen guncellemelerden sonra eski URL korunmali");
  }

  @Test
  @DisplayName("XSS-03 | https toplanti linki olusturma ve guncellemede kabul edilir")
  void xss03_httpsMeetingUrlAccepted() {
    String url = "https://meet.example.com/x";
    HttpResponse<String> created =
        http().post("/api/v1/meetings", adminA(), wsA(), meetingBody("xss03-ok", url));
    assertEquals(201, created.statusCode(), describe(created));
    assertEquals(url, json(created).path("meetingUrl").asString(""));
    String id = json(created).path("id").asString("");
    String url2 = "https://meet.example.com/y?room=1";
    HttpResponse<String> updated =
        http().put("/api/v1/meetings/" + id, adminA(), wsA(), meetingBody("xss03-ok", url2));
    assertEquals(200, updated.statusCode(), describe(updated));
    assertEquals(url2, json(updated).path("meetingUrl").asString(""));
  }

  @Test
  @DisplayName("XSS-03 | yalniz https kabul: http:// olusturma ve guncelleme 400/422")
  void xss03_plainHttpMeetingUrlRejected() {
    String http = "http://meet.example.com/x";
    HttpResponse<String> created =
        http().post("/api/v1/meetings", adminA(), wsA(), meetingBody("xss03-http", http));
    assertStatusIn(created, "http:// olusturma", 400, 422);

    HttpResponse<String> ok =
        http()
            .post(
                "/api/v1/meetings",
                adminA(),
                wsA(),
                meetingBody("xss03-http", "https://meet.example.com/x"));
    assertEquals(201, ok.statusCode(), describe(ok));
    HttpResponse<String> updated =
        http()
            .put(
                "/api/v1/meetings/" + json(ok).path("id").asString(""),
                adminA(),
                wsA(),
                meetingBody("xss03-http", http));
    assertStatusIn(updated, "http:// guncelleme", 400, 422);
  }

  // ================================================================ XSS-04

  private static final List<String> XSS_PAYLOADS =
      List.of(
          "<script>alert(1)</script>",
          "<img src=x onerror=alert(1)>",
          "\"><a href=javascript:alert(1)>tikla</a>");

  @Test
  @DisplayName("XSS-04 | e-posta HTML govdesinde kullanici metni kacislanir, ham etiket yok")
  void xss04_emailHtmlEscapesUserText() {
    Actor developer = fx().a().developer();
    List<String> failures = new ArrayList<>();
    for (String payload : XSS_PAYLOADS) {
      String marker = "xs" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
      HttpResponse<String> created = assignTask(marker + " " + payload, developer);
      if (created.statusCode() != 201) {
        failures.add("gorev '" + payload + "' -> " + describe(created));
        continue;
      }
      JsonNode mail =
          awaitMail(developer.email(), m -> m.path("Text").asString("").contains(marker), payload);
      String html = mail.path("HTML").asString("");
      String lower = html.toLowerCase();
      if (lower.contains("<script") || lower.contains("<img") || lower.contains("onerror=\"")) {
        failures.add("ham etiket HTML'de: " + payload + " -> " + html);
      }
      if (!html.contains(marker)) {
        failures.add("isaret HTML'de yok: " + payload);
      }
      // Yalniz bizim "Gorevi ac" baglantisi <a> olmali; enjekte <a> yok.
      Matcher anchors = Pattern.compile("<a\\s", Pattern.CASE_INSENSITIVE).matcher(html);
      int count = 0;
      while (anchors.find()) {
        count++;
      }
      if (count != 1) {
        failures.add("HTML'de " + count + " adet <a> var (1 olmali): " + payload + " -> " + html);
      }
      if (payload.startsWith("<script")
          && !html.contains("&lt;script&gt;alert(1)&lt;/script&gt;")) {
        failures.add("kacislanmis form HTML'de yok: " + html);
      }
    }
    assertFailures(failures);
  }

  @Test
  @DisplayName("XSS-04 | e-posta HTML govdesinde duz metin aynen gorunur")
  void xss04_plainTextAppearsVerbatimInEmailHtml() {
    Actor developer = fx().a().developer();
    String marker = "pl" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String title = marker + " Sade gorev basligi 123";
    HttpResponse<String> created = assignTask(title, developer);
    assertEquals(201, created.statusCode(), describe(created));
    JsonNode mail =
        awaitMail(developer.email(), m -> m.path("Text").asString("").contains(marker), "sade");
    assertTrue(mail.path("HTML").asString("").contains(title), mail.path("HTML").asString(""));
  }

  @Test
  @DisplayName("XSS-04 | e-posta HTML gövdesinde workspace adi (davet) kacislanir")
  void xss04_inviteEmailEscapesWorkspaceName() {
    String name = "WS <img src=x onerror=alert(1)> " + UUID.randomUUID().toString().substring(0, 6);
    HttpResponse<String> created = createWorkspace(name);
    assertEquals(201, created.statusCode(), describe(created));
    UUID workspaceId = UUID.fromString(json(created).path("id").asString(""));
    String invitee = "sec-xss04i-" + UUID.randomUUID() + "@tracker.local";
    HttpResponse<String> invite =
        http()
            .post(
                "/api/v1/workspaces/members/invitations",
                adminA(),
                workspaceId,
                "{\"email\":\"" + invitee + "\",\"role\":\"" + WorkspaceRole.DEVELOPER + "\"}");
    assertEquals(201, invite.statusCode(), describe(invite));
    JsonNode mail = awaitMail(invitee, m -> true, "davet");
    String html = mail.path("HTML").asString("");
    assertFalse(html.toLowerCase().contains("<img"), html);
    assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"), html);
  }

  @Test
  @DisplayName("XSS-04 | Slack mesajinda <, >, & kacislanir; <!channel>, <@U123> etkin kalmaz")
  void xss04_slackMessageEscapesControlCharacters() {
    List<String> titles =
        List.of(
            "<!channel> acil",
            "<@U123> bak",
            "<http://evil.example|giris yap>",
            "<!here> & <!everyone>",
            "a & b < c > d");
    for (String title : titles) {
      var summary = new SlackMessageFormatter.TaskSummary("SEC", 7, title);
      String created =
          SlackMessageFormatter.format(
                  SlackMessageFormatter.TASK_CREATED, mapper.createObjectNode(), summary)
              .orElseThrow();
      var status = mapper.createObjectNode();
      status.put("oldStatus", "To Do <!channel>");
      status.put("newStatus", "<@U999> Done");
      String updated =
          SlackMessageFormatter.format(SlackMessageFormatter.TASK_STATUS_UPDATED, status, summary)
              .orElseThrow();
      for (String message : List.of(created, updated)) {
        assertFalse(message.contains("<!"), "Slack ozel komutu etkin: " + message);
        assertFalse(message.contains("<@"), "Slack mention etkin: " + message);
        assertFalse(message.contains("<http"), "Slack baglantisi etkin: " + message);
        assertEquals(
            0,
            message.chars().filter(c -> c == '<' || c == '>').count(),
            "Ham < / > var: " + message);
        // '&' yalnizca kacis dizilerinin basinda gorunmeli.
        assertFalse(
            Pattern.compile("&(?!amp;|lt;|gt;)").matcher(message).find(),
            "Kacissiz & var: " + message);
      }
    }
    assertTrue(
        SlackMessageFormatter.format(
                SlackMessageFormatter.TASK_CREATED,
                mapper.createObjectNode(),
                new SlackMessageFormatter.TaskSummary("SEC", 7, "<!channel> acil"))
            .orElseThrow()
            .contains("&lt;!channel&gt;"));
  }

  @Test
  @DisplayName("XSS-04 | Slack mesajinda duz metin aynen gorunur")
  void xss04_slackPlainTextVerbatim() {
    var summary = new SlackMessageFormatter.TaskSummary("SEC", 7, "Sade baslik 123");
    String message =
        SlackMessageFormatter.format(
                SlackMessageFormatter.TASK_CREATED, mapper.createObjectNode(), summary)
            .orElseThrow();
    assertTrue(message.contains("Sade baslik 123"), message);
    assertTrue(message.contains("SEC-7"), message);
  }

  // ================================================================ EXT-06

  /** Java HttpClient Host basligini kisitladigi icin ham soket: sahte Host + X-Forwarded-Host. */
  private int rawPost(String path, List<String> extraHeaders, String body) {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    StringBuilder request = new StringBuilder();
    request.append("POST ").append(path).append(" HTTP/1.1\r\n");
    for (String header : extraHeaders) {
      request.append(header).append("\r\n");
    }
    request.append("Content-Type: application/json\r\n");
    request.append("Content-Length: ").append(payload.length).append("\r\n");
    request.append("Connection: close\r\n\r\n");
    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(30_000);
      socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().write(payload);
      socket.getOutputStream().flush();
      String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      Matcher status = Pattern.compile("^HTTP/1\\.1 (\\d{3})").matcher(response);
      assertTrue(status.find(), "Gecersiz yanit: " + response);
      return Integer.parseInt(status.group(1));
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[^\\s\"<>]+");

  private void assertLinksUsePublicUrl(JsonNode mail, String what) {
    String text = mail.path("Text").asString("");
    String html = mail.path("HTML").asString("");
    assertFalse(
        text.contains("evil.example") || html.contains("evil.example"),
        what + ": sahte host: " + text);
    Matcher m = URL_IN_TEXT.matcher(text + "\n" + html);
    int links = 0;
    while (m.find()) {
      links++;
      assertTrue(
          m.group().startsWith(publicUrl + "/") || m.group().equals(publicUrl),
          what + ": baglanti APP_PUBLIC_URL (" + publicUrl + ") tabanli degil: " + m.group());
    }
    assertTrue(links > 0, what + ": e-postada baglanti yok: " + text);
  }

  private static final List<String> SPOOF_HEADERS =
      List.of(
          "Host: evil.example",
          "X-Forwarded-Host: evil.example",
          "X-Forwarded-Proto: https",
          "X-Forwarded-Port: 443",
          "Forwarded: host=evil.example;proto=https");

  @Test
  @DisplayName(
      "EXT-06 | sahte Host/X-Forwarded-Host ile parola sifirlama baglantisi APP_PUBLIC_URL tabanli")
  void ext06_passwordResetLinkIgnoresSpoofedHost() {
    Actor victim = fx().addMember(fx().a(), WorkspaceRole.VIEWER, "ext06r");
    int status =
        rawPost(
            "/api/v1/auth/password-reset/request",
            SPOOF_HEADERS,
            "{\"email\":\"" + victim.email() + "\"}");
    assertEquals(202, status);
    JsonNode mail =
        awaitMail(
            victim.email(), m -> m.path("Subject").asString("").contains("sifirlama"), "sifirlama");
    assertLinksUsePublicUrl(mail, "parola sifirlama");
  }

  @Test
  @DisplayName("EXT-06 | normal istekte de parola sifirlama baglantisi ayni taban")
  void ext06_passwordResetLinkNormalRequestUsesPublicUrl() {
    Actor user = fx().addMember(fx().a(), WorkspaceRole.VIEWER, "ext06rn");
    HttpResponse<String> r =
        http()
            .post(
                "/api/v1/auth/password-reset/request",
                (String) null,
                null,
                "{\"email\":\"" + user.email() + "\"}");
    assertEquals(202, r.statusCode(), describe(r));
    JsonNode mail =
        awaitMail(
            user.email(), m -> m.path("Subject").asString("").contains("sifirlama"), "sifirlama");
    assertLinksUsePublicUrl(mail, "normal sifirlama");
  }

  @Test
  @DisplayName(
      "EXT-06 | sahte Host ile kayit dogrulama baglantisi APP_PUBLIC_URL tabanli (normal istekte de)")
  void ext06_verificationLinkIgnoresSpoofedHost() {
    for (boolean spoof : new boolean[] {true, false}) {
      String email = "sec-ext06v" + spoof + "-" + UUID.randomUUID() + "@tracker.local";
      String body =
          mapper.writeValueAsString(
              Map.of(
                  "email",
                  email,
                  "password",
                  SecurityFixture.PASSWORD,
                  "fullName",
                  "Ext Sec",
                  "privacyNoticeAccepted",
                  true,
                  "privacyNoticeVersion",
                  PrivacyNotice.CURRENT_VERSION));
      int status =
          spoof
              ? rawPost("/api/v1/auth/register", SPOOF_HEADERS, body)
              : http().post("/api/v1/auth/register", (String) null, null, body).statusCode();
      assertEquals(201, status);
      JsonNode mail =
          awaitMail(email, m -> m.path("Subject").asString("").contains("dogrula"), "dogrulama");
      assertLinksUsePublicUrl(mail, "dogrulama (sahte=" + spoof + ")");
    }
  }

  @Test
  @DisplayName(
      "EXT-06 | sahte Host ile davet baglantisi APP_PUBLIC_URL tabanli (normal istekte de)")
  void ext06_inviteLinkIgnoresSpoofedHost() {
    for (boolean spoof : new boolean[] {true, false}) {
      String invitee = "sec-ext06i" + spoof + "-" + UUID.randomUUID() + "@tracker.local";
      String body = "{\"email\":\"" + invitee + "\",\"role\":\"" + WorkspaceRole.DEVELOPER + "\"}";
      int status;
      if (spoof) {
        List<String> headers = new ArrayList<>(SPOOF_HEADERS);
        headers.add("Authorization: Bearer " + adminA().accessToken());
        headers.add("X-Workspace-Id: " + wsA());
        status = rawPost("/api/v1/workspaces/members/invitations", headers, body);
      } else {
        status =
            http()
                .post("/api/v1/workspaces/members/invitations", adminA(), wsA(), body)
                .statusCode();
      }
      assertEquals(201, status);
      JsonNode mail = awaitMail(invitee, m -> true, "davet");
      assertLinksUsePublicUrl(mail, "davet (sahte=" + spoof + ")");
    }
  }

  // ================================================================ LOG-05

  private String awaitErrorMessage(String errorType) {
    long deadline = System.currentTimeMillis() + 45_000;
    try {
      while (System.currentTimeMillis() < deadline) {
        List<String> rows =
            jdbc.queryForList(
                "SELECT message FROM error_events WHERE error_type = ?", String.class, errorType);
        if (!rows.isEmpty()) {
          return rows.get(0);
        }
        Thread.sleep(300);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    throw new AssertionError("error_events satiri gelmedi: " + errorType);
  }

  private HttpResponse<String> reportError(String errorType, String message) {
    return http()
        .post(
            "/api/v1/telemetry/errors",
            adminA(),
            wsA(),
            mapper.writeValueAsString(
                Map.of("errorType", errorType, "message", message, "path", "/reports")));
  }

  @Test
  @DisplayName("LOG-05 | hata telemetrisi mesajindaki e-posta/PAT/JWT error_events'e ham yazilmaz")
  void log05_sensitiveValuesAreNotStoredRaw() {
    String email = "kisi.gizli" + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
    String pat = "wf_pat_" + UUID.randomUUID().toString().replace("-", "") + "ABCDEFGHIJKLMNOP";
    String jwt =
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3OCIsInJvbGVzIjpbXX0.c2lnbmF0dXJlLWRlbmVtZS1pbXphc2k";
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("log05-email-" + UUID.randomUUID(), "Failed to load user " + email + " from API");
    cases.put("log05-pat-" + UUID.randomUUID(), "401 for token " + pat + " on /reports");
    cases.put("log05-jwt-" + UUID.randomUUID(), "Bearer " + jwt + " rejected");
    Map<String, String> secrets = Map.of("email", email, "pat", pat, "jwt", jwt);

    List<String> failures = new ArrayList<>();
    List<String> types = new ArrayList<>(cases.keySet());
    for (String errorType : types) {
      HttpResponse<String> r = reportError(errorType, cases.get(errorType));
      assertEquals(202, r.statusCode(), describe(r));
    }
    for (String errorType : types) {
      String stored = awaitErrorMessage(errorType);
      String kind = errorType.split("-")[1];
      String secret = secrets.get(kind);
      if (stored != null && stored.contains(secret)) {
        failures.add(kind + ": error_events.message ham deger iceriyor -> " + stored);
      }
      if (stored != null
          && "pat".equals(kind)
          && stored.contains("wf_pat_" + pat.substring(7, 20))) {
        failures.add("pat: token govdesi kismen sizdi -> " + stored);
      }
    }
    assertFailures(failures);
  }

  @Test
  @DisplayName(
      "LOG-05 | backend yolu: NUL'lu arama 500 uretir, error_events'e source=backend satiri duser, ham e-posta/token yok")
  void log05_backendErrorPathWritesRowWithoutRawSecrets() {
    Instant start = Instant.now().minusSeconds(2);
    HttpResponse<String> r =
        http()
            .get(
                "/api/v1/contract/log05-probe?email=" + enc(adminA().email()),
                adminA(),
                wsA(),
                null);
    assertProblem(r, 500, "kasitli istisna atan test ucu (backend hata yolu tetikleyicisi)");

    Map<String, Object> row = null;
    long deadline = System.currentTimeMillis() + 45_000;
    while (row == null && System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> rows =
          jdbc.queryForList(
              "SELECT source, error_type, message, path, user_id, workspace_id FROM error_events "
                  + "WHERE source = 'backend' AND path = '/api/v1/contract/log05-probe' "
                  + "AND user_id = ? "
                  + "AND occurred_at >= ? ORDER BY occurred_at DESC",
              adminA().userId(),
              java.sql.Timestamp.from(start));
      if (!rows.isEmpty()) {
        row = rows.get(0);
      } else {
        try {
          Thread.sleep(300);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
    }
    assertNotNull(row, "error_events'e source=backend satiri dusmedi");
    assertEquals(wsA(), row.get("workspace_id"));
    String message = String.valueOf(row.get("message"));
    assertFalse(message.contains(adminA().email()), "ham e-posta: " + message);
    assertFalse(message.contains(LOG05_PROBE_PAT), "ham PAT: " + message);
    assertFalse(message.contains(adminA().accessToken()), "ham JWT: " + message);
    // Kabul eşi: satır tetikleyicinin mesajından geliyor (maskeleme mesajı tümden silmiyor).
    assertTrue(message.contains("probe failed for"), "beklenen mesaj govdesi yok: " + message);
  }

  @Test
  @DisplayName("LOG-05 | hassas veri icermeyen telemetri mesaji aynen saklanir")
  void log05_plainMessageStoredVerbatim() {
    String errorType = "log05-plain-" + UUID.randomUUID();
    String message = "x is undefined at reports view (line 42)";
    HttpResponse<String> r = reportError(errorType, message);
    assertEquals(202, r.statusCode(), describe(r));
    assertEquals(message, awaitErrorMessage(errorType));
  }
}
