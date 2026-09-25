package com.app.tracker.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * GERCEK servlet container uzerinden (MockMvc DEGIL): MockMvc {@code sendError} sonrasi {@code
 * /error} dispatch'ini yapmadigi icin, uye olmayan workspace header'ina donen 403'un 401'e
 * donusmesini (bkz. WorkspaceContextFilter javadoc'u) yalniz bu test yakalar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceContextFilterHttpIntegrationTest extends AbstractIntegrationTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @LocalServerPort private int port;
  @Autowired private AuthService authService;

  @Test
  void nonMemberWorkspaceHeaderIs403NotUnauthorized() throws Exception {
    String email = "filter-" + UUID.randomUUID() + "@tracker.local";
    authService.register(email, "correct-horse-battery", "Filter User");
    String token = authService.login(email, "correct-horse-battery", "127.0.0.1").accessToken();

    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/projects"))
                .header("Authorization", "Bearer " + token)
                .header("X-Workspace-Id", UUID.randomUUID().toString())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(403, response.statusCode());
    assertTrue(response.body().contains("\"status\":403"), response.body());
  }
}
