package com.app.tracker.security.support;

import com.app.tracker.security.support.SecurityFixture.Actor;
import com.app.tracker.security.support.SecurityFixture.Tenant;
import com.app.tracker.workspace.model.WorkspaceRole;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * D4: bir kiracida her kaynak tipinden bir kayit, GEREKTIKCE (lazy) ve kiracinin kendi ADMIN'i ile
 * HTTP uzerinden uretilir. Her harness ucu taze bir set kullanir: bir ucun yazma/silme etkisi
 * (ornegin A'nin kendi gorevini silen kabul esi) sonraki uclarin sonucunu bozmaz.
 *
 * <p>Uretilen her kaydin metin alanlarina kiracinin {@code marker}'i yazilir (TEN-03 sizinti
 * aramasi icin); {@link #tokens()} kiraciya ait aranabilir tum dizgeleri (id, anahtar, e-posta,
 * isaret) dondurur.
 */
public final class TenantResources {

  /** Kaynak tipleri; path parametresi esleme tablosu bunlara baglanir. */
  public enum Type {
    PROJECT,
    TASK,
    OTHER_TASK,
    /** {@link #TASK}'in alt gorevi (yalniz okuma uclarinin bos olmayan yanit vermesi icin). */
    SUBTASK,
    SPRINT,
    COMMENT,
    TAG,
    GOAL,
    MEETING,
    SAVED_VIEW,
    RETRO_ITEM,
    NOTIFICATION,
    WEBHOOK,
    INVITATION,
    MEMBER,
    PAT,
    /** Geri bildirim kaydi (FEEDBACK_READ listesi icin). */
    FEEDBACK
  }

  /** Takvim/standup/toplanti icin ortak sabit gun (gelecekte; gecmis tarih reddedilir). */
  public static final String DAY = "2099-06-15";

  public static final String RANGE_FROM = "2099-06-01";
  public static final String RANGE_TO = "2099-06-30";

  private final SecurityHttp http;
  private final ObjectMapper mapper;
  private final SecurityFixture fixture;
  private final Tenant tenant;
  private final String marker;
  private final Map<Type, UUID> ids = new EnumMap<>(Type.class);
  private final Set<String> extraTokens = new LinkedHashSet<>();
  private String projectKey;
  private final List<String> taskKeys = new ArrayList<>();
  private final Map<UUID, String> keyByTask = new java.util.HashMap<>();
  private Actor member;
  private UUID doneTaskId;
  private UUID wipTaskId;
  private UUID activeSprintId;
  private String slackCreatedAt;

  public TenantResources(
      SecurityFixture fixture, ObjectMapper mapper, Tenant tenant, String marker) {
    this.fixture = fixture;
    this.http = fixture.http();
    this.mapper = mapper;
    this.tenant = tenant;
    this.marker = marker;
  }

  public Tenant tenant() {
    return tenant;
  }

  public String marker() {
    return marker;
  }

  public Actor admin() {
    return tenant.admin();
  }

  /** {@link Type#TASK} gorevinin {@code KEY-NO} anahtari. */
  public String taskKey() {
    return keyByTask.get(id(Type.TASK));
  }

  /** Taze uye (MEMBER tipi); ilk cagrida olusturulur. */
  public Actor member() {
    id(Type.MEMBER);
    return member;
  }

  /** Tipin kimligi; yoksa kiracinin ADMIN'i ile olusturulur. */
  public UUID id(Type type) {
    UUID existing = ids.get(type);
    if (existing != null) {
      return existing;
    }
    UUID created = create(type);
    ids.put(type, created);
    return created;
  }

  /**
   * Tum tipleri uretir (TEN-03 icin tam set) ve okuma yuzeylerinin kendi verisini gosterebilmesi
   * icin durum kurar: tamamlanmis sprint (puanli, onaylanmis gorevle), devam eden gorev, Slack
   * yapilandirmasi, bilinen bildirim tercihleri.
   */
  public TenantResources seedAll() {
    for (Type t : Type.values()) {
      id(t);
    }
    seedLifecycle();
    seedSlack();
    seedPreferences();
    return this;
  }

  /** Puanli + onayli tamamlanan gorev ({@code SPRINT} tamamlanir). */
  public UUID doneTaskId() {
    return doneTaskId;
  }

  /** "In Progress"teki (aging) gorev. */
  public UUID wipTaskId() {
    return wipTaskId;
  }

  /**
   * {@code PUT /integrations/slack} yanitindaki {@code createdAt} (bu kiracinin bilinen degeri).
   */
  public String slackCreatedAt() {
    return slackCreatedAt;
  }

  /** ADMIN'e yazilan bildirim tercihleri (A ve B farkli). */
  public boolean prefEmailOnAssign() {
    return marker.startsWith("A") ? false : true;
  }

  public boolean prefEmailOnMention() {
    return !prefEmailOnAssign();
  }

  private void seedLifecycle() {
    UUID ws = tenant.workspaceId();
    Actor admin = admin();
    UUID sprintId = id(Type.SPRINT);
    doneTaskId = createTask(admin, marker + " tamamlanan gorev");
    ok(
        http.put("/api/v1/tasks/" + doneTaskId + "/story-point", admin, ws, "{\"storyPoint\":5}"),
        "puan");
    ok(
        http.put(
            "/api/v1/tasks/" + doneTaskId + "/sprint",
            admin,
            ws,
            "{\"sprintId\":\"" + sprintId + "\"}"),
        "sprint'e alma");
    ok(http.post("/api/v1/sprints/" + sprintId + "/start", admin, ws, null), "sprint baslatma");
    ok(
        http.patch("/api/v1/tasks/" + doneTaskId, admin, ws, "{\"status\":\"In Progress\"}"),
        "In Progress");
    ok(http.patch("/api/v1/tasks/" + doneTaskId, admin, ws, "{\"status\":\"Done\"}"), "Done");
    ok(http.post("/api/v1/tasks/" + doneTaskId + "/approval", admin, ws, null), "onay");
    ok(http.post("/api/v1/sprints/" + sprintId + "/complete", admin, ws, null), "sprint tamamlama");
    // Aging WIP esigi en az 10 tamamlanmis (In Progress'ten gecmis) gorev ister
    // (AgingWipLevels.MIN_SAMPLE_SIZE); toplam COMPLETED_TASKS gorev Done olur.
    for (int i = 1; i < COMPLETED_TASKS; i++) {
      UUID t = createTask(admin, marker + " tamamlanan " + i);
      ok(http.patch("/api/v1/tasks/" + t, admin, ws, "{\"status\":\"In Progress\"}"), "IP");
      ok(http.patch("/api/v1/tasks/" + t, admin, ws, "{\"status\":\"Done\"}"), "Done");
    }
    wipTaskId = createTask(admin, marker + " devam eden gorev");
    ok(
        http.patch("/api/v1/tasks/" + wipTaskId, admin, ws, "{\"status\":\"In Progress\"}"),
        "wip In Progress");
    // Aktif sprint (sprint tahmini icin acik isli): ilk sprint tamamlandiktan sonra baslatilir.
    activeSprintId =
        uuid(
            created(
                    http.post(
                        "/api/v1/projects/" + id(Type.PROJECT) + "/sprints",
                        admin,
                        ws,
                        "{\"name\":\""
                            + marker
                            + " aktif sprint\",\"startDate\":\"2099-07-01\","
                            + "\"endDate\":\"2099-07-14\"}"),
                    "aktif sprint")
                .path("id"));
    extraTokens.add(activeSprintId.toString());
    UUID sprintTask = createTask(admin, marker + " aktif sprint gorevi");
    ok(
        http.put(
            "/api/v1/tasks/" + sprintTask + "/sprint",
            admin,
            ws,
            "{\"sprintId\":\"" + activeSprintId + "\"}"),
        "aktif sprint'e alma");
    ok(http.post("/api/v1/sprints/" + activeSprintId + "/start", admin, ws, null), "aktif sprint");
    // Otomasyon: projeye ozel kayitli (configured) bir kural.
    ok(
        http.put(
            "/api/v1/projects/" + id(Type.PROJECT) + "/automation-rules/" + AUTOMATION_TEMPLATE,
            admin,
            ws,
            "{\"enabled\":true}"),
        "otomasyon kurali");
  }

  /** {@link #seedAll()} sonrasi projede Done olan gorev sayisi. */
  public static final int COMPLETED_TASKS = 11;

  public static final String AUTOMATION_TEMPLATE = "SUBTASK_ALL_DONE_PARENT_TO_REVIEW";

  /** Baslatilmis, acik gorevli sprint ({@link #seedAll()} sonrasi). */
  public UUID activeSprintId() {
    return activeSprintId;
  }

  private void seedSlack() {
    JsonNode r =
        created(
            http.put(
                "/api/v1/integrations/slack",
                admin(),
                tenant.workspaceId(),
                "{\"webhookUrl\":\"https://hooks.slack.com/services/T0SEC/B0SEC/x0"
                    + rand(6)
                    + "\",\"enabled\":false}"),
            "Slack");
    slackCreatedAt = r.path("createdAt").asString("");
  }

  private void seedPreferences() {
    ok(
        http.put(
            "/api/v1/me/notification-preferences",
            admin(),
            tenant.workspaceId(),
            "{\"emailOnAssign\":"
                + prefEmailOnAssign()
                + ",\"emailOnMention\":"
                + prefEmailOnMention()
                + "}"),
        "bildirim tercihi");
  }

  private void ok(HttpResponse<String> r, String what) {
    if (r.statusCode() / 100 != 2) {
      throw new IllegalStateException(what + " basarisiz: " + r.statusCode() + " " + r.body());
    }
  }

  public boolean has(Type type) {
    return ids.containsKey(type);
  }

  /**
   * Bu kiraciya ait, baska kiracinin yanitinda ASLA gecmemesi gereken dizgeler: workspace id,
   * fikstur uyelerinin id/e-postasi, uretilen kayitlarin id'leri, gorev anahtarlari, isaret.
   */
  public Set<String> tokens() {
    Set<String> t = new LinkedHashSet<>();
    t.add(marker);
    t.add(tenant.workspaceId().toString());
    t.add(tenant.projectId().toString());
    t.add(tenant.taskId().toString());
    t.add(tenant.otherTaskId().toString());
    for (Actor a : tenant.actors().values()) {
      t.add(a.userId().toString());
      t.add(a.email());
    }
    ids.values().forEach(id -> t.add(id.toString()));
    t.addAll(taskKeys);
    t.addAll(extraTokens);
    return t;
  }

  /**
   * Kiracinin KENDI ADMIN'inin gozunden, uretilmis kayitlari kapsayan okuma yollari. Yazma/silme
   * uclarinda "B'nin kaynagi degismedi" bununla dogrulanir.
   */
  public List<String> snapshotPaths() {
    List<String> paths = new ArrayList<>();
    paths.add("/api/v1/projects");
    paths.add("/api/v1/tags");
    paths.add("/api/v1/goals?year=2099");
    paths.add("/api/v1/meetings");
    paths.add("/api/v1/workspaces/members");
    paths.add("/api/v1/workspaces/members/invitations");
    paths.add("/api/v1/integrations/webhooks");
    paths.add("/api/v1/access-tokens");
    if (has(Type.PROJECT)) {
      String p = "/api/v1/projects/" + ids.get(Type.PROJECT);
      paths.add(p + "/tasks?limit=100");
      paths.add(p + "/tasks/approved");
      paths.add(p + "/sprints");
      paths.add(p + "/saved-views");
      paths.add(p + "/automation-rules");
    }
    for (Type t : List.of(Type.TASK, Type.OTHER_TASK)) {
      if (has(t)) {
        String base = "/api/v1/tasks/" + ids.get(t);
        paths.add(base + "/detail");
        paths.add(base + "/comments");
        paths.add(base + "/subtasks");
      }
    }
    if (has(Type.SPRINT)) {
      paths.add("/api/v1/sprints/" + ids.get(Type.SPRINT) + "/retro/items");
    }
    if (has(Type.MEETING)) {
      paths.add("/api/v1/standups?meetingId=" + ids.get(Type.MEETING) + "&date=" + DAY);
    }
    if (has(Type.NOTIFICATION)) {
      paths.add("/api/v1/notifications?limit=100");
    }
    return paths;
  }

  /**
   * {@link #snapshotPaths()} yanitlarinin kanonik birlesimi, kiracinin ADMIN'i ile. Her okuma 200
   * olmalidir (degilse kurulum hatasi). Karsilastirma ham govde uzerinden DEGIL: JSON nesne
   * anahtarlari sirali, diziler ogelerin kanonik bicimine gore sirali. Sirasi sozlesmeyle
   * belirlenmemis koleksiyonlar (ornegin {@code findAll()} ile donen proje listesi) boylece nesne
   * kumesi olarak karsilastirilir; sira farki degisiklik sayilmaz, alan degisikligi sayilir.
   */
  public String snapshot() {
    StringBuilder sb = new StringBuilder();
    for (String p : snapshotPaths()) {
      HttpResponse<String> r = http.get(p, admin(), tenant.workspaceId(), null);
      if (r.statusCode() != 200) {
        throw new IllegalStateException(
            "anlik goruntu okumasi " + p + " -> " + r.statusCode() + " " + r.body());
      }
      sb.append(p).append(" -> ").append(canonical(mapper.readTree(r.body()))).append('\n');
    }
    return sb.toString();
  }

  /** Anahtar sirasindan ve dizi sirasindan bagimsiz kanonik JSON metni. */
  public static String canonical(JsonNode node) {
    if (node.isObject()) {
      java.util.TreeMap<String, String> fields = new java.util.TreeMap<>();
      for (Map.Entry<String, JsonNode> e : node.properties()) {
        fields.put(e.getKey(), canonical(e.getValue()));
      }
      StringBuilder sb = new StringBuilder("{");
      fields.forEach((k, v) -> sb.append('"').append(k).append("\":").append(v).append(','));
      return sb.append('}').toString();
    }
    if (node.isArray()) {
      List<String> items = new ArrayList<>();
      node.forEach(n -> items.add(canonical(n)));
      java.util.Collections.sort(items);
      return "[" + String.join(",", items) + "]";
    }
    return node.toString();
  }

  // ---- uretim ------------------------------------------------------------------------------

  private UUID create(Type type) {
    UUID ws = tenant.workspaceId();
    Actor admin = admin();
    switch (type) {
      case PROJECT -> {
        String key = randomKey();
        JsonNode p =
            created(
                http.post(
                    "/api/v1/projects",
                    admin,
                    ws,
                    "{\"key\":\"" + key + "\",\"name\":\"" + marker + " proje\"}"),
                "proje");
        projectKey = p.path("key").asString(key);
        return uuid(p.path("id"));
      }
      case TASK, OTHER_TASK -> {
        return createTask(admin, marker + " gorev " + type.name().toLowerCase());
      }
      case SUBTASK -> {
        JsonNode st =
            created(
                http.post(
                    "/api/v1/tasks/" + id(Type.TASK) + "/subtasks",
                    admin,
                    ws,
                    "{\"title\":\"" + marker + " alt gorev\"}"),
                "alt gorev");
        return uuid(st.path("id"));
      }
      case SPRINT -> {
        return uuid(
            created(
                    http.post(
                        "/api/v1/projects/" + id(Type.PROJECT) + "/sprints",
                        admin,
                        ws,
                        "{\"name\":\""
                            + marker
                            + " sprint\",\"startDate\":\""
                            + RANGE_FROM
                            + "\",\"endDate\":\"2099-06-14\"}"),
                    "sprint")
                .path("id"));
      }
      case COMMENT -> {
        return uuid(
            created(
                    http.post(
                        "/api/v1/tasks/" + id(Type.TASK) + "/comments",
                        admin,
                        ws,
                        "{\"body\":\"" + marker + " yorum\"}"),
                    "yorum")
                .path("id"));
      }
      case TAG -> {
        return uuid(
            created(
                    http.post(
                        "/api/v1/tags",
                        admin,
                        ws,
                        "{\"name\":\"" + marker + "-" + rand(6) + "\",\"color\":\"#00AA00\"}"),
                    "etiket")
                .path("id"));
      }
      case GOAL -> {
        return uuid(
            created(
                    http.post(
                        "/api/v1/goals",
                        admin,
                        ws,
                        "{\"title\":\""
                            + marker
                            + " hedef\",\"metricType\":\"COMPLETED_TASKS\",\"targetValue\":10,"
                            + "\"year\":2099}"),
                    "hedef")
                .path("id"));
      }
      case MEETING -> {
        UUID meetingId =
            uuid(
                created(http.post("/api/v1/meetings", admin, ws, meetingJson()), "toplanti")
                    .path("id"));
        seedStandupDigest(meetingId);
        return meetingId;
      }
      case SAVED_VIEW -> {
        return uuid(
            created(
                    http.post(
                        "/api/v1/projects/" + id(Type.PROJECT) + "/saved-views",
                        admin,
                        ws,
                        "{\"name\":\"" + marker + " gorunum\",\"query\":\"{}\"}"),
                    "kayitli gorunum")
                .path("id"));
      }
      case RETRO_ITEM -> {
        return uuid(
            created(
                    http.post(
                        "/api/v1/sprints/" + id(Type.SPRINT) + "/retro/items",
                        admin,
                        ws,
                        "{\"kind\":\"went_well\",\"body\":\"" + marker + " retro\"}"),
                    "retro maddesi")
                .path("id"));
      }
      case NOTIFICATION -> {
        return createNotificationForAdmin();
      }
      case WEBHOOK -> {
        return uuid(
            created(http.post("/api/v1/integrations/webhooks", admin, ws, null), "webhook")
                .path("integration")
                .path("id"));
      }
      case INVITATION -> {
        String email = "sec-inv-" + UUID.randomUUID() + "@tracker.local";
        extraTokens.add(email);
        return uuid(
            created(
                    http.post(
                        "/api/v1/workspaces/members/invitations",
                        admin,
                        ws,
                        "{\"email\":\"" + email + "\",\"role\":\"DEVELOPER\"}"),
                    "davet")
                .path("id"));
      }
      case MEMBER -> {
        member = fixture.addMember(tenant, WorkspaceRole.DEVELOPER, "ten-member-" + rand(4));
        extraTokens.add(member.email());
        return member.userId();
      }
      case PAT -> {
        return uuid(
            created(
                    http.post(
                        "/api/v1/access-tokens", admin, null, "{\"name\":\"" + marker + " pat\"}"),
                    "PAT")
                .path("token")
                .path("id"));
      }
      case FEEDBACK -> {
        return uuid(
            created(
                    http.post(
                        "/api/v1/feedback",
                        admin,
                        ws,
                        "{\"message\":\"" + marker + " geri bildirim\",\"pagePath\":\"/sec\"}"),
                    "geri bildirim")
                .path("id"));
      }
      default -> throw new IllegalStateException("bilinmeyen tip " + type);
    }
  }

  /** Kiracinin projesinde gorev (ADMIN ile), takvim araligina dusen bitis tarihiyle. */
  public UUID createTask(Actor actor, String title) {
    JsonNode t =
        created(
            http.post(
                "/api/v1/projects/" + id(Type.PROJECT) + "/tasks",
                actor,
                tenant.workspaceId(),
                "{\"title\":\"" + title + "\",\"dueDate\":\"" + DAY + "\"}"),
            "gorev");
    String key = projectKey + "-" + t.path("taskNumber").asInt();
    taskKeys.add(key);
    UUID id = uuid(t.path("id"));
    keyByTask.put(id, key);
    extraTokens.add(id.toString());
    return id;
  }

  /**
   * MANAGER bir gorev olusturup ADMIN'e atar; atama olayi Kafka tuketicisi uzerinden ADMIN'in
   * Inbox'ina bildirim dusurur (asenkron, beklenir). Ayni gorev ADMIN'in "bana atananlar"inda da
   * gorunur.
   */
  private UUID createNotificationForAdmin() {
    UUID ws = tenant.workspaceId();
    UUID taskId = createTask(tenant.manager(), marker + " bildirim gorevi");
    HttpResponse<String> assign =
        http.put(
            "/api/v1/tasks/" + taskId + "/assignee",
            tenant.manager(),
            ws,
            "{\"assigneeId\":\"" + admin().userId() + "\"}");
    if (assign.statusCode() / 100 != 2) {
      throw new IllegalStateException("bildirim icin atama basarisiz: " + assign.statusCode());
    }
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      JsonNode page =
          mapper.readTree(http.get("/api/v1/notifications?limit=100", admin(), ws, null).body());
      for (JsonNode n : page.path("data")) {
        if (taskId.toString().equals(n.path("taskId").asString(""))) {
          return uuid(n.path("id"));
        }
      }
      sleep(250);
    }
    throw new IllegalStateException("ADMIN'e bildirim 30 sn icinde gelmedi (gorev " + taskId + ")");
  }

  /**
   * Standup ozeti normalde zamanlanmis is ({@code StandupDigestJob}) tarafindan uretilir; HTTP ucu
   * yoktur. Not yazma ucunun (PUT .../note) kabul esi icin ADMIN'in {@link #DAY} ozeti burada
   * dogrudan eklenir. Duz JDBC, {@code app_migrator} (tablo sahibi) baglantisiyla.
   */
  private void seedStandupDigest(UUID meetingId) {
    try (java.sql.Connection c =
            com.app.tracker.core.AbstractIntegrationTest.openMigratorConnection();
        java.sql.PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO standup_digests (workspace_id, meeting_id, occurrence_date, user_id,"
                    + " facts) VALUES (?, ?, ?, ?, ?::jsonb)")) {
      ps.setObject(1, tenant.workspaceId());
      ps.setObject(2, meetingId);
      ps.setObject(3, java.time.LocalDate.parse(DAY));
      ps.setObject(4, admin().userId());
      // StandupFacts semasiyla gecerli bos olgular (job'un urettigi bicim).
      ps.setString(
          5,
          "{\"completedYesterday\":[],\"progressedYesterday\":[],\"inProgress\":[],"
              + "\"blocked\":[],\"aging\":[],\"githubActivity\":[]}");
      ps.executeUpdate();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("standup ozeti eklenemedi", e);
    }
  }

  public String meetingJson() {
    return "{\"title\":\""
        + marker
        + " toplanti\",\"startDate\":\""
        + DAY
        + "\",\"startTime\":\"10:00:00\",\"durationMinutes\":30,\"frequency\":\"ONCE\","
        + "\"intervalCount\":1,\"standupEnabled\":true}";
  }

  private JsonNode created(HttpResponse<String> r, String what) {
    if (r.statusCode() / 100 != 2) {
      throw new IllegalStateException(what + " olusturulamadi: " + r.statusCode() + " " + r.body());
    }
    return mapper.readTree(r.body());
  }

  private static UUID uuid(JsonNode node) {
    String s = node.asString("");
    if (s.isEmpty()) {
      throw new IllegalStateException("yanitta id yok");
    }
    return UUID.fromString(s);
  }

  private static String randomKey() {
    StringBuilder sb = new StringBuilder("Q");
    for (int i = 0; i < 7; i++) {
      sb.append((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
    }
    return sb.toString();
  }

  public static String rand(int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
    }
    return sb.toString();
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
