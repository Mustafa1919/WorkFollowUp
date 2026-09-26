package com.app.tracker.accesstoken;

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
 * ADR-0018 — gercek servlet container uzerinden (WorkspaceContextFilterHttpIntegrationTest ile AYNI
 * kurulum deseni): filter zincirinin (Pat/Jwt ayrimi, rate limit, AUTH_PAT reddi) gercek HTTP
 * uzerinden calistigini dogrular, MockMvc DEGIL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessTokenIntegrationTest extends AbstractIntegrationTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String PASSWORD = "correct-horse-battery";

  @LocalServerPort private int port;
  @Autowired private AuthService authService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private TenantExecutor tenantExecutor;

  private String jwt;
  private UUID workspaceId;

  @BeforeEach
  void setUp() {
    String email = "pat-" + UUID.randomUUID() + "@tracker.local";
    UUID userId = authService.register(email, PASSWORD, "PAT User").getId();
    jwt = authService.login(email, PASSWORD, "127.0.0.1").accessToken();
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "PAT WS"));
    membershipService.addMember(workspaceId, userId, WorkspaceRole.ADMIN);
  }

  @Test
  void createdTokenAuthenticatesAndSeesOwnWorkspace() throws Exception {
    String rawToken = createToken("otomasyon", null);
    assertTrue(rawToken.startsWith("wf_pat_"));

    HttpResponse<String> response = callProjects(rawToken);
    assertEquals(200, response.statusCode());
  }

  @Test
  void revokedTokenIsRejected() throws Exception {
    String createBody =
        HTTP.send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/access-tokens")))
                    .header("Authorization", "Bearer " + jwt)
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString("{\"name\":\"revoke-me\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .body();
    String rawToken = extractField(createBody, "rawToken");
    String tokenId = extractField(createBody, "id");

    HttpResponse<Void> revokeResponse =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/access-tokens/" + tokenId)))
                .header("Authorization", "Bearer " + jwt)
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertEquals(204, revokeResponse.statusCode());

    assertEquals(401, callProjects(rawToken).statusCode());
  }

  @Test
  void patCannotManageAccessTokens() throws Exception {
    String rawToken = createToken("no-self-service", null);

    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/access-tokens")))
                .header("Authorization", "Bearer " + rawToken)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(403, response.statusCode());
  }

  @Test
  void exceedingRateLimitReturns429WithRetryAfter() throws Exception {
    String rawToken = createToken("rate-limited", null);

    HttpResponse<String> last = null;
    for (int i = 0; i < 61; i++) {
      last = callProjects(rawToken);
    }

    assertEquals(429, last.statusCode());
    assertTrue(last.headers().firstValue("Retry-After").isPresent());
  }

  // ---- yardimcilar --------------------------------------------------------------------------

  private String createToken(String name, Integer expiresInDays) throws Exception {
    String body =
        expiresInDays == null
            ? "{\"name\":\"" + name + "\"}"
            : "{\"name\":\"" + name + "\",\"expiresInDays\":" + expiresInDays + "}";
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/access-tokens")))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(201, response.statusCode(), response.body());
    return extractField(response.body(), "rawToken");
  }

  private HttpResponse<String> callProjects(String bearerToken) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(url("/api/v1/projects")))
            .header("Authorization", "Bearer " + bearerToken)
            .header("X-Workspace-Id", workspaceId.toString())
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private static String extractField(String json, String field) {
    var matcher =
        java.util.regex.Pattern.compile("\"" + field + "\":\"?([^\",}]+)\"?").matcher(json);
    assertTrue(matcher.find(), "Alan bulunamadi: " + field + " -> " + json);
    return matcher.group(1);
  }
}
