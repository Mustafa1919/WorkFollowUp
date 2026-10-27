package com.app.tracker.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dilim 03 backend sozlesmesi: gercek servlet zincirinde JWT/PAT kimligi ve workspace uyeligi.
 * Beklenen izinler ADR-0019 tablosundan elle yazilir; uretim esleme metodu cagrilmaz.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeControllerIntegrationTest extends AbstractIntegrationTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String PASSWORD = "correct-horse-battery";
  private static final String ME = "/api/v1/me";
  private static final String PERMISSIONS = "/api/v1/me/permissions";

  @LocalServerPort private int port;
  @Autowired private AuthService authService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private ObjectMapper objectMapper;

  private UUID workspaceId;

  private record Actor(UUID id, String email, String fullName, String jwt) {}

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(
        null, () -> workspaceService.createWorkspace(workspaceId, "Me contract WS"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Çağrı Şenyurt", ""})
  void K1_authenticatedUserWithoutMembershipOrWorkspaceGetsOwnIdentity(String fullName)
      throws Exception {
    Actor first = newUser(fullName);
    Actor second = newUser("Başka Kullanıcı");

    // Neither user has workspace membership. Alternate identities on the same HTTP client.
    assertMe(get(ME, first.jwt(), null), first);
    assertMe(get(ME, second.jwt(), null), second);
    assertMe(get(ME, first.jwt(), null), first);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void R1_anonymousMeIs401WithAndWithoutWorkspace(boolean withWorkspace) throws Exception {
    Actor actor = member(WorkspaceRole.VIEWER);
    UUID header = withWorkspace ? workspaceId : null;

    // Acceptance pair: identical path/header, only authentication changes.
    assertMe(get(ME, actor.jwt(), header), actor);
    assertStatus(401, get(ME, null, header));
  }

  @ParameterizedTest(name = "K2 role={0}")
  @MethodSource("adrPermissionsByRole")
  void K2_eachRoleGetsExactAdrPermissionsInEnumOrder(String role, List<String> expected)
      throws Exception {
    Actor actor = member(role);

    assertPermissions(get(PERMISSIONS, actor.jwt(), workspaceId), role, expected);
    // A second request checks that the order remains deterministic.
    assertPermissions(get(PERMISSIONS, actor.jwt(), workspaceId), role, expected);
  }

  @Test
  void K2_sameUserGetsRoleAndPermissionsOfSelectedWorkspace() throws Exception {
    Actor actor = member(WorkspaceRole.VIEWER);
    UUID otherWorkspace = UUID.randomUUID();
    tenantExecutor.runAs(
        null, () -> workspaceService.createWorkspace(otherWorkspace, "Me second WS"));
    membershipService.addMember(otherWorkspace, actor.id(), WorkspaceRole.MANAGER);

    assertPermissions(get(PERMISSIONS, actor.jwt(), workspaceId), WorkspaceRole.VIEWER, List.of());
    assertPermissions(
        get(PERMISSIONS, actor.jwt(), otherWorkspace), WorkspaceRole.MANAGER, managerPermissions());
    assertPermissions(get(PERMISSIONS, actor.jwt(), workspaceId), WorkspaceRole.VIEWER, List.of());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void R2a_missingWorkspaceIs400ForJwtAndPat(boolean usePat) throws Exception {
    Actor actor = member(WorkspaceRole.DEVELOPER);
    String bearer = usePat ? createPat(actor) : actor.jwt();

    // Acceptance pair: the same identity succeeds when the required header is supplied.
    assertPermissions(
        get(PERMISSIONS, bearer, workspaceId), WorkspaceRole.DEVELOPER, developerPermissions());
    assertStatus(400, get(PERMISSIONS, bearer, null));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void R2b_nonMemberOfExistingWorkspaceIs403ForJwtAndPat(boolean usePat) throws Exception {
    Actor actor = member(WorkspaceRole.DEVELOPER);
    UUID foreignWorkspace = UUID.randomUUID();
    tenantExecutor.runAs(
        null, () -> workspaceService.createWorkspace(foreignWorkspace, "Me foreign WS"));
    Actor owner = newUser("Workspace Sahibi");
    membershipService.addMember(foreignWorkspace, owner.id(), WorkspaceRole.ADMIN);
    String bearer = usePat ? createPat(actor) : actor.jwt();

    assertPermissions(
        get(PERMISSIONS, bearer, workspaceId), WorkspaceRole.DEVELOPER, developerPermissions());
    // Target exists and is readable by a member, so a missing target cannot explain rejection.
    assertPermissions(
        get(PERMISSIONS, owner.jwt(), foreignWorkspace), WorkspaceRole.ADMIN, adminPermissions());
    assertStatus(403, get(PERMISSIONS, bearer, foreignWorkspace));
    assertPermissions(
        get(PERMISSIONS, bearer, workspaceId), WorkspaceRole.DEVELOPER, developerPermissions());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void R2c_anonymousPermissionsIs401WithAndWithoutWorkspace(boolean withWorkspace)
      throws Exception {
    Actor actor = member(WorkspaceRole.VIEWER);

    assertPermissions(get(PERMISSIONS, actor.jwt(), workspaceId), WorkspaceRole.VIEWER, List.of());
    assertStatus(401, get(PERMISSIONS, null, withWorkspace ? workspaceId : null));
  }

  @ParameterizedTest(name = "K3 /me role={0}")
  @MethodSource("adrPermissionsByRole")
  void K3_patCanReadOwnIdentityWithoutWorkspace(String role, List<String> expected)
      throws Exception {
    Actor actor = member(role);
    String pat = createPat(actor);

    assertMe(get(ME, pat, null), actor);
    assertMe(get(ME, pat, workspaceId), actor);
    assertPermissions(get(PERMISSIONS, pat, workspaceId), role, expected);
  }

  @ParameterizedTest(name = "K3 /me/permissions role={0}")
  @MethodSource("adrPermissionsByRole")
  void K3_patCanReadWorkspacePermissions(String role, List<String> expected) throws Exception {
    Actor actor = member(role);
    String pat = createPat(actor);

    assertPermissions(get(PERMISSIONS, pat, workspaceId), role, expected);
    assertPermissions(get(PERMISSIONS, actor.jwt(), workspaceId), role, expected);
  }

  // ADR-0019 section 2, in WorkspacePermission declaration order. Keep these lists independent
  // of the production mapping and enum iteration: exact list equality also detects duplicates.
  private static List<String> adminPermissions() {
    return List.of(
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
        "INTEGRATION_MANAGE");
  }

  private static List<String> managerPermissions() {
    return List.of(
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
        "FEEDBACK_READ");
  }

  private static List<String> developerPermissions() {
    return List.of(
        "TASK_EDIT", "TASK_SELF_ASSIGN", "SUBTASK_CREATE_OWN", "COMMENT_WRITE", "RETRO_ITEM_WRITE");
  }

  private static Stream<Arguments> adrPermissionsByRole() {
    return Stream.of(
        Arguments.of(WorkspaceRole.ADMIN, adminPermissions()),
        Arguments.of(WorkspaceRole.MANAGER, managerPermissions()),
        Arguments.of(WorkspaceRole.DEVELOPER, developerPermissions()),
        Arguments.of(WorkspaceRole.VIEWER, List.of()));
  }

  private Actor newUser(String fullName) {
    String email = "me-contract-" + UUID.randomUUID() + "@tracker.local";
    UUID id = authService.register(email, PASSWORD, fullName).getId();
    String jwt = authService.login(email, PASSWORD, "127.0.0.1").accessToken();
    return new Actor(id, email, fullName, jwt);
  }

  private Actor member(String role) {
    Actor actor = newUser("İzin Kullanıcısı");
    membershipService.addMember(workspaceId, actor.id(), role);
    return actor;
  }

  private String createPat(Actor actor) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/access-tokens")))
                .header("Authorization", "Bearer " + actor.jwt())
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"name\":\"me-contract\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertStatus(201, response);
    String pat = objectMapper.readTree(response.body()).get("rawToken").asText();
    assertTrue(pat.startsWith("wf_pat_"), "PAT creation must return a PAT, not a JWT");
    return pat;
  }

  private HttpResponse<String> get(String path, String bearer, UUID workspace) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path))).GET();
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    if (workspace != null) {
      request.header("X-Workspace-Id", workspace.toString());
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void assertMe(HttpResponse<String> response, Actor actor) throws Exception {
    assertStatus(200, response);
    JsonNode expected =
        objectMapper.valueToTree(
            Map.of(
                "id", actor.id().toString(), "email", actor.email(), "fullName", actor.fullName()));
    assertEquals(expected, objectMapper.readTree(response.body()), response.body());
  }

  private void assertPermissions(HttpResponse<String> response, String role, List<String> expected)
      throws Exception {
    assertStatus(200, response);
    JsonNode body = objectMapper.readTree(response.body());
    assertEquals(role, body.get("role").asText(), response.body());
    JsonNode permissions = body.get("permissions");
    assertTrue(permissions.isArray(), response.body());
    List<String> actual = new ArrayList<>();
    for (JsonNode permission : permissions) {
      actual.add(permission.asText());
    }
    assertEquals(expected, actual, response.body());
  }

  private static void assertStatus(int expected, HttpResponse<String> response) {
    assertEquals(expected, response.statusCode(), response.body());
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
