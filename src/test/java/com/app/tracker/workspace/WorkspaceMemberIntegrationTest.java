package com.app.tracker.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Workspace uyeligi yonetimi (RAKIP_ANALIZI.md acik notu — davet ucusu yoktu): e-postayla ekleme
 * (ONCEDEN kayitli kullanici), listeleme, rol degistirme, cikarma; "son ADMIN kaldirilamaz/
 * degistirilemez" degismezi. {@code workspace_users}/{@code users} RLS'e tabi OLMADIGI icin
 * izolasyon WorkspaceMemberService'in kendi workspaceId filtresine dayanir.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceMemberIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final Pattern USER_ID_PATTERN =
      Pattern.compile("\"userId\":\"([0-9a-fA-F-]{36})\"");

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private MockMvc mockMvc;

  private UUID workspaceId;
  private String adminToken;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Member WS"));
    String email = "member-admin-" + UUID.randomUUID() + "@tracker.local";
    UUID adminId = authService.register(email, PASSWORD, "Admin").getId();
    membershipService.addMember(workspaceId, adminId, WorkspaceRole.ADMIN);
    adminToken = authService.login(email, PASSWORD, "127.0.0.1").accessToken();
  }

  private String registerPlainUser(String email) {
    authService.register(email, PASSWORD, "Someone");
    return email;
  }

  private String body(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc
        .perform(
            request
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Workspace-Id", workspaceId.toString()))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private int status(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc
        .perform(
            request
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Workspace-Id", workspaceId.toString()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private static UUID extractUserId(String json) {
    Matcher matcher = USER_ID_PATTERN.matcher(json);
    if (!matcher.find()) {
      throw new AssertionError("userId bulunamadi: " + json);
    }
    return UUID.fromString(matcher.group(1));
  }

  @Test
  void addingByEmailListingChangingRoleAndRemovingWorks() throws Exception {
    String email = registerPlainUser("dev-" + UUID.randomUUID() + "@tracker.local");

    String addBody =
        body(
            post("/api/v1/workspaces/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"" + email + "\",\"role\":\"" + WorkspaceRole.DEVELOPER + "\"}"));
    assertTrue(addBody.contains("\"role\":\"" + WorkspaceRole.DEVELOPER + "\""), addBody);
    UUID userId = extractUserId(addBody);

    String listBody = body(get("/api/v1/workspaces/members"));
    assertTrue(listBody.contains(email), listBody);

    assertEquals(
        200,
        status(
            patch("/api/v1/workspaces/members/" + userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"" + WorkspaceRole.MANAGER + "\"}")));

    assertEquals(204, status(delete("/api/v1/workspaces/members/" + userId)));
  }

  @Test
  void unknownEmailIsNotFound() throws Exception {
    assertEquals(
        404,
        status(
            post("/api/v1/workspaces/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"nobody-"
                        + UUID.randomUUID()
                        + "@tracker.local\",\"role\":\"DEVELOPER\"}")));
  }

  @Test
  void addingAlreadyExistingMemberIsRejected() throws Exception {
    String email = registerPlainUser("dup-" + UUID.randomUUID() + "@tracker.local");
    mockMvc.perform(
        post("/api/v1/workspaces/members")
            .header("Authorization", "Bearer " + adminToken)
            .header("X-Workspace-Id", workspaceId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"role\":\"DEVELOPER\"}"));

    assertEquals(
        400,
        status(
            post("/api/v1/workspaces/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"VIEWER\"}")));
  }

  @Test
  void lastAdminCannotBeDemotedOrRemoved() throws Exception {
    // setUp'taki ADMIN workspace'in TEK uyesi (son ADMIN).
    UUID adminUserId = extractUserId(body(get("/api/v1/workspaces/members")));

    assertEquals(
        400,
        status(
            patch("/api/v1/workspaces/members/" + adminUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"DEVELOPER\"}")));

    assertEquals(400, status(delete("/api/v1/workspaces/members/" + adminUserId)));
  }

  @Test
  void secondAdminCanBeRemovedOnceThereAreTwo() throws Exception {
    String email = registerPlainUser("admin2-" + UUID.randomUUID() + "@tracker.local");
    String addBody =
        body(
            post("/api/v1/workspaces/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"" + WorkspaceRole.ADMIN + "\"}"));
    UUID secondAdminId = extractUserId(addBody);

    // Artik 2 ADMIN var: ikincisini cikarmak serbest olmali.
    assertEquals(204, status(delete("/api/v1/workspaces/members/" + secondAdminId)));
  }
}
