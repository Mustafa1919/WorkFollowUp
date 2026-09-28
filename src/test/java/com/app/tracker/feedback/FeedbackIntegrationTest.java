package com.app.tracker.feedback;

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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * V33/AccessTokenIntegrationTest ile AYNI gercek-servlet deseni: senkron DB yazimi (Kafka DEGIL) --
 * geri bildirim gonderme -> hemen ayni istekte 201 + govde bekliyoruz (best-effort telemetrinin
 * aksine, burada dogrulanacak bir gecikme yok).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedbackIntegrationTest extends AbstractIntegrationTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String PASSWORD = "correct-horse-battery";

  @LocalServerPort private int port;
  @Autowired private AuthService authService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private TenantExecutor tenantExecutor;

  private UUID workspaceId;
  private String adminJwt;

  @BeforeEach
  void setUp() {
    String email = "feedback-admin-" + UUID.randomUUID() + "@tracker.local";
    UUID userId = authService.register(email, PASSWORD, "Admin").getId();
    adminJwt = authService.login(email, PASSWORD, "127.0.0.1").accessToken();
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Feedback WS"));
    membershipService.addMember(workspaceId, userId, WorkspaceRole.ADMIN);
  }

  @Test
  void memberCanSubmitAndAdminCanListIt() throws Exception {
    HttpResponse<String> submitResponse =
        submit(adminJwt, workspaceId, "Board sayfasi cok yavas.", "/board");
    assertEquals(201, submitResponse.statusCode(), submitResponse.body());
    assertTrue(submitResponse.body().contains("Board sayfasi cok yavas."));

    HttpResponse<String> listResponse = list(adminJwt, workspaceId);
    assertEquals(200, listResponse.statusCode());
    assertTrue(listResponse.body().contains("Board sayfasi cok yavas."));
  }

  @Test
  void developerCannotListFeedback() throws Exception {
    String email = "feedback-dev-" + UUID.randomUUID() + "@tracker.local";
    UUID devId = authService.register(email, PASSWORD, "Dev").getId();
    String devJwt = authService.login(email, PASSWORD, "127.0.0.1").accessToken();
    membershipService.addMember(workspaceId, devId, WorkspaceRole.DEVELOPER);

    // Gonderme herhangi bir uyeye acik.
    assertEquals(201, submit(devJwt, workspaceId, "Geri bildirim.", "/board").statusCode());
    // Listeleme ADMIN/MANAGER'a ozel.
    assertEquals(403, list(devJwt, workspaceId).statusCode());
  }

  @Test
  void submittingWithoutWorkspaceHeaderFailsWithBadRequest() throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/feedback")))
                .header("Authorization", "Bearer " + adminJwt)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"message\":\"workspace secilmeden\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, response.statusCode());
  }

  @Test
  void feedbackIsIsolatedPerWorkspace() throws Exception {
    submit(adminJwt, workspaceId, "WS1'e ozel geri bildirim.", "/board");

    UUID otherWorkspaceId = UUID.randomUUID();
    tenantExecutor.runAs(
        null, () -> workspaceService.createWorkspace(otherWorkspaceId, "Diger WS"));
    String email = "feedback-other-" + UUID.randomUUID() + "@tracker.local";
    UUID otherUserId = authService.register(email, PASSWORD, "Other Admin").getId();
    String otherJwt = authService.login(email, PASSWORD, "127.0.0.1").accessToken();
    membershipService.addMember(otherWorkspaceId, otherUserId, WorkspaceRole.ADMIN);

    HttpResponse<String> otherWorkspaceList = list(otherJwt, otherWorkspaceId);
    assertEquals(200, otherWorkspaceList.statusCode());
    assertTrue(
        !otherWorkspaceList.body().contains("WS1'e ozel geri bildirim."),
        "RLS: baska workspace'in geri bildirimini GOREMEMELI");
  }

  // ---- yardimcilar --------------------------------------------------------------------------

  private HttpResponse<String> submit(String jwt, UUID workspaceId, String message, String pagePath)
      throws Exception {
    String body = "{\"message\":\"%s\",\"pagePath\":\"%s\"}".formatted(message, pagePath);
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(url("/api/v1/feedback")))
            .header("Authorization", "Bearer " + jwt)
            .header("X-Workspace-Id", workspaceId.toString())
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> list(String jwt, UUID workspaceId) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(url("/api/v1/feedback")))
            .header("Authorization", "Bearer " + jwt)
            .header("X-Workspace-Id", workspaceId.toString())
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
