package com.app.tracker.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * HTTP katmaninda yetkilendirme: {@code @PreAuthorize} annotation'lari, {@code SecurityFilterChain}
 * ve {@code WorkspaceContextFilter} birlikte, gercek filtre zinciriyle sinanir. Servis katmani
 * testleri method-security proxy'sini hic devreye sokmadigi icin bir annotation silinse bile
 * gecerdi; bu test o boslugu kapatir.
 *
 * <p>Endpoint matrisi (endpoint x rol) bilerek KODDAN turetilmez, burada elle yazilir: annotation
 * degisirse test kirilmali ve degisiklik bilincli olarak burada da yapilmali. "Izinli" durum icin
 * gercek is sonucu (404/201/...) degil, "401/403 DEGIL" dogrulanir — kaynaklar rastgele UUID'dir,
 * amac yetkilendirme katmanini gecmektir, is mantigini sinamak degil.
 *
 * <p>Gecerli govde ZORUNLUDUR: {@code @Valid} argument cozumlemesi method-security'den ONCE
 * calisir, gecersiz govde 403 yerine 422 dondurup reddi maskelerdi.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpAuthorizationIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final String SYSTEM_ADMIN_EMAIL = "http-authz-owner@tracker.local";

  private static final Set<String> ALL_ROLES =
      Set.of(
          WorkspaceRole.ADMIN,
          WorkspaceRole.MANAGER,
          WorkspaceRole.DEVELOPER,
          WorkspaceRole.VIEWER);
  private static final Set<String> MANAGE = Set.of(WorkspaceRole.ADMIN, WorkspaceRole.MANAGER);
  private static final Set<String> WRITE =
      Set.of(WorkspaceRole.ADMIN, WorkspaceRole.MANAGER, WorkspaceRole.DEVELOPER);
  private static final Set<String> ADMIN_ONLY = Set.of(WorkspaceRole.ADMIN);

  // 2099: SprintService artik gecmis baslangic tarihini reddediyor (rejectPastStartDate) —
  // 2026-01-01 bu testin kosuldugu "bugun"den ONCE kalir ve MANAGE rolleri bile 400 alirdi.
  private static final String SPRINT_JSON =
      "{\"name\":\"S\",\"startDate\":\"2099-01-01\",\"endDate\":\"2099-01-14\"}";

  // 2099: MeetingService de ayni gerekceyle gecmis baslangic tarihini reddediyor (bkz. yukarida).
  private static final String MEETING_JSON =
      "{\"title\":\"M\",\"startDate\":\"2099-01-01\",\"startTime\":\"10:00:00\","
          + "\"durationMinutes\":30,\"frequency\":\"ONCE\",\"intervalCount\":1}";

  // 2099: hedefler ileriye donuk tanimlanir; testin kosuldugu yildan bagimsiz kalsin diye sabit
  // uzak bir donem secildi (GoalService donem gecmiste diye reddetmez, ama sayilar anlamsiz
  // olurdu).
  private static final String GOAL_JSON =
      "{\"title\":\"H\",\"metricType\":\"COMPLETED_TASKS\",\"targetValue\":10,\"year\":2099}";

  /** {@code {id}} her istekte rastgele bir UUID ile degistirilir. */
  private record Endpoint(
      HttpMethod method, String path, Supplier<String> body, Set<String> allowedRoles) {

    static Endpoint of(HttpMethod method, String path, String body, Set<String> allowedRoles) {
      return new Endpoint(method, path, () -> body, allowedRoles);
    }

    MockHttpServletRequestBuilder build() {
      MockHttpServletRequestBuilder builder =
          request(method, path.replace("{id}", UUID.randomUUID().toString()));
      String json = body.get();
      return json == null ? builder : builder.contentType(MediaType.APPLICATION_JSON).content(json);
    }

    boolean roleProtected() {
      return !allowedRoles.equals(ALL_ROLES);
    }

    @Override
    public String toString() {
      return method + " " + path;
    }
  }

  static Stream<Endpoint> endpoints() {
    return Stream.of(
        // Proje: olusturma yalniz ADMIN/MANAGER; anahtar her cagrida essiz (10 karakter siniri).
        new Endpoint(
            HttpMethod.POST,
            "/api/v1/projects",
            () ->
                "{\"key\":\"K"
                    + UUID.randomUUID().toString().substring(0, 8)
                    + "\",\"name\":\"Proje\"}",
            MANAGE),
        Endpoint.of(HttpMethod.GET, "/api/v1/projects", null, ALL_ROLES),
        // Sprint yasam dongusu: yalniz ADMIN/MANAGER.
        Endpoint.of(HttpMethod.POST, "/api/v1/projects/{id}/sprints", SPRINT_JSON, MANAGE),
        Endpoint.of(HttpMethod.GET, "/api/v1/projects/{id}/sprints", null, ALL_ROLES),
        Endpoint.of(HttpMethod.POST, "/api/v1/sprints/{id}/start", null, MANAGE),
        Endpoint.of(HttpMethod.POST, "/api/v1/sprints/{id}/complete", null, MANAGE),
        // Gorev: yazma ADMIN/MANAGER/DEVELOPER, VIEWER yalniz okur.
        Endpoint.of(HttpMethod.POST, "/api/v1/projects/{id}/tasks", "{\"title\":\"T\"}", WRITE),
        Endpoint.of(HttpMethod.GET, "/api/v1/projects/{id}/tasks", null, ALL_ROLES),
        Endpoint.of(HttpMethod.PATCH, "/api/v1/tasks/{id}", "{\"status\":\"Done\"}", WRITE),
        Endpoint.of(HttpMethod.PUT, "/api/v1/tasks/{id}/sprint", "{\"sprintId\":null}", WRITE),
        Endpoint.of(HttpMethod.PUT, "/api/v1/tasks/{id}/story-point", "{\"storyPoint\":3}", WRITE),
        Endpoint.of(
            HttpMethod.GET,
            "/api/v1/projects/{id}/tasks/calendar?from=2026-09-01&to=2026-10-12",
            null,
            ALL_ROLES),
        Endpoint.of(
            HttpMethod.PUT, "/api/v1/tasks/{id}/due-date", "{\"dueDate\":\"2099-09-30\"}", WRITE),
        // Onay: ADMIN/MANAGER (gorevi yapan DEVELOPER kendi isini onaylayamaz); silme yalniz ADMIN.
        Endpoint.of(HttpMethod.GET, "/api/v1/projects/{id}/tasks/approved", null, ALL_ROLES),
        Endpoint.of(HttpMethod.POST, "/api/v1/tasks/{id}/approval", null, MANAGE),
        Endpoint.of(HttpMethod.DELETE, "/api/v1/tasks/{id}/approval", null, MANAGE),
        Endpoint.of(HttpMethod.DELETE, "/api/v1/tasks/{id}", null, ADMIN_ONLY),
        // Analitik okuma: rol siniri yok, workspace uyeligi yeterli (AnalyticsController javadoc).
        Endpoint.of(HttpMethod.GET, "/api/v1/projects/{id}/analytics/velocity", null, ALL_ROLES),
        Endpoint.of(HttpMethod.GET, "/api/v1/projects/{id}/analytics/throughput", null, ALL_ROLES),
        Endpoint.of(HttpMethod.GET, "/api/v1/projects/{id}/analytics/cycle-time", null, ALL_ROLES),
        // Webhook entegrasyonlari: dis bir sisteme gorev durumu degistirme yetkisi veren kimlik
        // bilgisi; yalniz workspace ADMIN (MANAGER bile degil).
        Endpoint.of(HttpMethod.POST, "/api/v1/integrations/webhooks", null, ADMIN_ONLY),
        Endpoint.of(HttpMethod.GET, "/api/v1/integrations/webhooks", null, ADMIN_ONLY),
        Endpoint.of(
            HttpMethod.POST, "/api/v1/integrations/webhooks/{id}/rotate-secret", null, ADMIN_ONLY),
        Endpoint.of(HttpMethod.DELETE, "/api/v1/integrations/webhooks/{id}", null, ADMIN_ONLY),
        // Slack entegrasyonu: sunucuyu bir dis adrese HTTP atmaya yonlendiren kimlik bilgisi;
        // yalniz
        // ADMIN. PUT govdesi bilerek GECERSIZ adres: yetkili rol 400 (is kurali) alir, kayit
        // olusmaz.
        Endpoint.of(
            HttpMethod.PUT,
            "/api/v1/integrations/slack",
            "{\"webhookUrl\":\"https://invalid.example\"}",
            ADMIN_ONLY),
        Endpoint.of(HttpMethod.GET, "/api/v1/integrations/slack", null, ADMIN_ONLY),
        Endpoint.of(HttpMethod.DELETE, "/api/v1/integrations/slack", null, ADMIN_ONLY),
        // Etiketler: tanim (olustur/degistir/sil) ADMIN/MANAGER (tum projeleri etkiler), listeleme
        // her uye; goreve atama/kaldirma diger gorev mutasyonlariyla AYNI (yazma) roller.
        Endpoint.of(
            HttpMethod.POST, "/api/v1/tags", "{\"name\":\"Bug\",\"color\":\"#FF0000\"}", MANAGE),
        Endpoint.of(HttpMethod.GET, "/api/v1/tags", null, ALL_ROLES),
        Endpoint.of(
            HttpMethod.PUT,
            "/api/v1/tags/{id}",
            "{\"name\":\"Bug\",\"color\":\"#00FF00\"}",
            MANAGE),
        Endpoint.of(HttpMethod.DELETE, "/api/v1/tags/{id}", null, MANAGE),
        Endpoint.of(HttpMethod.PUT, "/api/v1/tasks/{id}/tags/{id}", null, WRITE),
        Endpoint.of(HttpMethod.DELETE, "/api/v1/tasks/{id}/tags/{id}", null, WRITE),
        // Subtask/Dependency: tag'lerle AYNI yazma rolleri (gorev duzenlemenin bir parcasi);
        // listeleme (subtasks) her uye.
        Endpoint.of(HttpMethod.PUT, "/api/v1/tasks/{id}/parent/{id}", null, WRITE),
        Endpoint.of(HttpMethod.DELETE, "/api/v1/tasks/{id}/parent", null, WRITE),
        Endpoint.of(HttpMethod.GET, "/api/v1/tasks/{id}/subtasks", null, ALL_ROLES),
        Endpoint.of(HttpMethod.PUT, "/api/v1/tasks/{id}/dependencies/{id}", null, WRITE),
        Endpoint.of(HttpMethod.DELETE, "/api/v1/tasks/{id}/dependencies/{id}", null, WRITE),
        // Workspace uyeligi: ekleme/rol degistirme/cikarma yalniz ADMIN; listeleme her uye.
        Endpoint.of(
            HttpMethod.POST,
            "/api/v1/workspaces/members",
            "{\"email\":\"someone@example.com\",\"role\":\"DEVELOPER\"}",
            ADMIN_ONLY),
        Endpoint.of(HttpMethod.GET, "/api/v1/workspaces/members", null, ALL_ROLES),
        Endpoint.of(
            HttpMethod.PATCH,
            "/api/v1/workspaces/members/{id}",
            "{\"role\":\"DEVELOPER\"}",
            ADMIN_ONLY),
        Endpoint.of(HttpMethod.DELETE, "/api/v1/workspaces/members/{id}", null, ADMIN_ONLY),
        // Inbox bildirimleri: rol siniri yok (hep KENDI bildirimlerim, ownership
        // NotificationService
        // katmaninda), workspace uyeligi yeterli — TaskController/TagController'in GET uc
        // noktalariyla
        // AYNI desen.
        Endpoint.of(HttpMethod.GET, "/api/v1/notifications", null, ALL_ROLES),
        Endpoint.of(HttpMethod.GET, "/api/v1/notifications/unread-count", null, ALL_ROLES),
        Endpoint.of(HttpMethod.POST, "/api/v1/notifications/{id}/read", null, ALL_ROLES),
        Endpoint.of(HttpMethod.POST, "/api/v1/notifications/read-all", null, ALL_ROLES),
        // Toplanti planlama: tanim (olustur/degistir/sil) ADMIN/MANAGER (Tags ile AYNI gerekce —
        // workspace geneli paylasilan yapi), listeleme/occurrence sorgusu her uye.
        Endpoint.of(HttpMethod.POST, "/api/v1/meetings", MEETING_JSON, MANAGE),
        Endpoint.of(HttpMethod.GET, "/api/v1/meetings", null, ALL_ROLES),
        Endpoint.of(
            HttpMethod.GET,
            "/api/v1/meetings/occurrences?from=2026-09-01&to=2026-10-12",
            null,
            ALL_ROLES),
        Endpoint.of(HttpMethod.PUT, "/api/v1/meetings/{id}", MEETING_JSON, MANAGE),
        Endpoint.of(HttpMethod.DELETE, "/api/v1/meetings/{id}", null, MANAGE),
        // Donemsel rapor: okuma rol siniri YOK (AnalyticsController ile ayni gerekce).
        Endpoint.of(HttpMethod.GET, "/api/v1/reports/period?year=2099", null, ALL_ROLES),
        Endpoint.of(HttpMethod.GET, "/api/v1/reports/period?year=2099&quarter=2", null, ALL_ROLES),
        // Hedefler: tanim ve elle ilerleme ADMIN/MANAGER (Tags/Meetings ile ayni gerekce),
        // listeleme her uye.
        Endpoint.of(HttpMethod.GET, "/api/v1/goals?year=2099", null, ALL_ROLES),
        Endpoint.of(HttpMethod.POST, "/api/v1/goals", GOAL_JSON, MANAGE),
        Endpoint.of(
            HttpMethod.PUT, "/api/v1/goals/{id}", "{\"title\":\"H\",\"targetValue\":5}", MANAGE),
        Endpoint.of(HttpMethod.PUT, "/api/v1/goals/{id}/progress", "{\"value\":1}", MANAGE),
        Endpoint.of(HttpMethod.DELETE, "/api/v1/goals/{id}", null, MANAGE));
  }

  static Stream<Arguments> endpointsByRole() {
    return endpoints()
        .flatMap(endpoint -> ALL_ROLES.stream().sorted().map(role -> Arguments.of(endpoint, role)));
  }

  static Stream<Endpoint> roleProtectedEndpoints() {
    return endpoints().filter(Endpoint::roleProtected);
  }

  @DynamicPropertySource
  static void registerAdminEmail(DynamicPropertyRegistry registry) {
    registry.add("app.security.system-admin-emails", () -> SYSTEM_ADMIN_EMAIL);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthService authService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TenantExecutor tenantExecutor;

  private UUID workspaceId;
  private Map<String, String> tokenByRole;
  private String outsiderToken;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Authz WS"));
    tokenByRole =
        Map.of(
            WorkspaceRole.ADMIN, memberToken(WorkspaceRole.ADMIN),
            WorkspaceRole.MANAGER, memberToken(WorkspaceRole.MANAGER),
            WorkspaceRole.DEVELOPER, memberToken(WorkspaceRole.DEVELOPER),
            WorkspaceRole.VIEWER, memberToken(WorkspaceRole.VIEWER));
    outsiderToken = newUserToken("outsider-" + UUID.randomUUID() + "@tracker.local");
  }

  private String memberToken(String role) {
    String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@tracker.local";
    UUID userId = authService.register(email, PASSWORD, "Authz User").getId();
    membershipService.addMember(workspaceId, userId, role);
    return authService.login(email, PASSWORD, "127.0.0.1").accessToken();
  }

  private String newUserToken(String email) {
    authService.register(email, PASSWORD, "Authz User");
    return authService.login(email, PASSWORD, "127.0.0.1").accessToken();
  }

  private int status(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request).andReturn().getResponse().getStatus();
  }

  private int statusAs(Endpoint endpoint, String token, boolean withWorkspace) throws Exception {
    MockHttpServletRequestBuilder request = endpoint.build();
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    if (withWorkspace) {
      request.header("X-Workspace-Id", workspaceId.toString());
    }
    return status(request);
  }

  @ParameterizedTest(name = "{0} as {1}")
  @MethodSource("endpointsByRole")
  void memberIsAllowedOnlyWhenRoleIsDeclaredOnEndpoint(Endpoint endpoint, String role)
      throws Exception {
    int status = statusAs(endpoint, tokenByRole.get(role), true);

    if (endpoint.allowedRoles().contains(role)) {
      assertTrue(
          status != 401 && status != 403,
          role + " icin yetkilendirme gecmeliydi ama status=" + status);
    } else {
      assertEquals(403, status, role + " reddedilmeliydi");
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("endpoints")
  void nonMemberOfWorkspaceIsForbiddenEverywhere(Endpoint endpoint) throws Exception {
    assertEquals(403, statusAs(endpoint, outsiderToken, true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("endpoints")
  void anonymousRequestIsRejected(Endpoint endpoint) throws Exception {
    int status = statusAs(endpoint, null, true);

    assertTrue(status == 401 || status == 403, "anonim istek gecti, status=" + status);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("endpoints")
  void invalidTokenIsRejected(Endpoint endpoint) throws Exception {
    int status = statusAs(endpoint, "not-a-real-jwt", true);

    assertTrue(status == 401 || status == 403, "gecersiz token gecti, status=" + status);
  }

  /**
   * Workspace header'i yoksa TenantContext bos kalir; rol kontrolu fail-closed olmali (ADMIN bile
   * gecmemeli). Yalniz rol-korumali endpoint'ler: okuma endpoint'leri icin bu durum RLS'e kalir.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("roleProtectedEndpoints")
  void missingWorkspaceHeaderFailsClosedEvenForAdmin(Endpoint endpoint) throws Exception {
    assertEquals(403, statusAs(endpoint, tokenByRole.get(WorkspaceRole.ADMIN), false));
  }

  @Test
  void viewerCanReadRealProjectDataAndAnalyticsParamsAreClamped() throws Exception {
    Project project =
        tenantExecutor.runAs(
            workspaceId, () -> projectService.createProject("VW", "Viewer Project"));
    String viewer = tokenByRole.get(WorkspaceRole.VIEWER);
    String base = "/api/v1/projects/" + project.getId();

    List<String> paths =
        List.of(
            "/api/v1/projects",
            "/api/v1/tags",
            "/api/v1/workspaces/members",
            base + "/sprints",
            base + "/tasks",
            base + "/analytics/velocity",
            base + "/analytics/throughput",
            base + "/analytics/cycle-time",
            // Sinir disi parametreler 400 degil, kirpilarak 200 doner
            // (AnalyticsController.bounded).
            base + "/analytics/velocity?sprints=0",
            base + "/analytics/velocity?sprints=9999",
            base + "/analytics/throughput?weeks=-5",
            base + "/analytics/cycle-time?days=100000");
    for (String path : paths) {
      int status =
          status(
              get(path)
                  .header("Authorization", "Bearer " + viewer)
                  .header("X-Workspace-Id", workspaceId.toString()));
      assertEquals(200, status, path);
    }
  }

  @Test
  void authenticatedUserCanCreateWorkspaceWithoutWorkspaceHeader() throws Exception {
    int status =
        status(
            post("/api/v1/workspaces")
                .header("Authorization", "Bearer " + outsiderToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Yeni WS\"}"));

    assertEquals(201, status);
  }

  // ---- SYSTEM_ADMIN (workspace rolu DEGIL, global authority) ----------------------------------

  /** Topic '-dlt' ile bitmedigi icin servis 400 verir: yetki gecti, Kafka'ya hic dokunulmaz. */
  private static final String REPLAY_JSON =
      "{\"dltTopic\":\"plain-topic\",\"partition\":0,\"fromOffset\":0,\"toOffset\":0}";

  @Test
  void kafkaReplayIsForbiddenForWorkspaceAdminWithoutSystemAdminAuthority() throws Exception {
    // WORKSPACE_ADMIN olmak SYSTEM_ADMIN olmak DEGILDIR.
    int status =
        status(
            post("/api/v1/admin/kafka/replay")
                .header("Authorization", "Bearer " + tokenByRole.get(WorkspaceRole.ADMIN))
                .header("X-Workspace-Id", workspaceId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(REPLAY_JSON));

    assertEquals(403, status);
  }

  @Test
  void kafkaReplayPassesAuthorizationForSystemAdmin() throws Exception {
    String token = newUserToken(SYSTEM_ADMIN_EMAIL);

    int status =
        status(
            post("/api/v1/admin/kafka/replay")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REPLAY_JSON));

    assertEquals(400, status);
  }

  @Test
  void kafkaReplayRejectsAnonymousRequest() throws Exception {
    int status =
        status(
            post("/api/v1/admin/kafka/replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REPLAY_JSON));

    assertTrue(status == 401 || status == 403, "status=" + status);
  }
}
