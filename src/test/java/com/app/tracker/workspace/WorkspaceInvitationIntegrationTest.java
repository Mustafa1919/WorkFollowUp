package com.app.tracker.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dalga 1.4 — token'li davet. Gercek Mailpit'e karsi UCTAN UCA (EmailDeliveryIntegrationTest ile
 * AYNI desen): outbox -> OutboxRelay -> Kafka -> EmailDeliveryConsumer -> SMTP, hepsi gercekten
 * calisir; ham token TESTTEN GORULMEZ (WorkspaceInvitationResponse tasimaz), e-postanin gercek
 * govdesinden regex ile cikarilir — production'da bir kullanicinin yapacagi seyin aynisi.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceInvitationIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final Pattern TOKEN_IN_LINK = Pattern.compile("/invitations/([A-Za-z0-9_-]+)");

  @Autowired private ObjectMapper objectMapper;
  @Autowired private MockMvc mockMvc;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;

  private UUID workspaceId;
  private String adminToken;

  @BeforeEach
  void setUp() throws Exception {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Invite WS"));
    String email = "invite-admin-" + UUID.randomUUID() + "@tracker.local";
    UUID adminId = authService.register(email, PASSWORD, "Admin").getId();
    membershipService.addMember(workspaceId, adminId, WorkspaceRole.ADMIN);
    adminToken = authService.login(email, PASSWORD, "127.0.0.1").accessToken();

    HTTP.send(
        HttpRequest.newBuilder(URI.create(mailpitApiUrl("/api/v1/messages"))).DELETE().build(),
        HttpResponse.BodyHandlers.discarding());
  }

  @Test
  void invitedUnregisteredEmailCanRegisterAndAcceptTheInvite() throws Exception {
    String inviteeEmail = "invitee-" + UUID.randomUUID() + "@tracker.local";

    mockMvc
        .perform(
            post("/api/v1/workspaces/members/invitations")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Workspace-Id", workspaceId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\""
                        + inviteeEmail
                        + "\",\"role\":\""
                        + WorkspaceRole.DEVELOPER
                        + "\"}"))
        .andReturn();

    String token = extractTokenFromEmail(inviteeEmail);

    // Public onizleme, kimlik dogrulamasi/workspace header'i GEREKMEZ.
    String previewBody =
        mockMvc
            .perform(get("/api/v1/invitations/" + token))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(previewBody.contains("\"status\":\"PENDING\""), previewBody);
    assertTrue(previewBody.contains(inviteeEmail), previewBody);
    assertTrue(previewBody.contains("Invite WS"), previewBody);

    // Henuz kayitsiz: register + login, sonra kabul et.
    authService.register(inviteeEmail, PASSWORD, "Davetli");
    String inviteeToken = authService.login(inviteeEmail, PASSWORD, "127.0.0.1").accessToken();

    String acceptBody =
        mockMvc
            .perform(
                post("/api/v1/invitations/" + token + "/accept")
                    .header("Authorization", "Bearer " + inviteeToken))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(acceptBody.contains(workspaceId.toString()), acceptBody);
    assertTrue(acceptBody.contains("\"role\":\"" + WorkspaceRole.DEVELOPER + "\""), acceptBody);

    String membersBody =
        mockMvc
            .perform(
                get("/api/v1/workspaces/members")
                    .header("Authorization", "Bearer " + adminToken)
                    .header("X-Workspace-Id", workspaceId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(membersBody.contains(inviteeEmail), membersBody);

    // Ayni token ikinci kez kullanilamaz (artik ACCEPTED, isPending() false).
    assertEquals(
        400,
        mockMvc
            .perform(
                post("/api/v1/invitations/" + token + "/accept")
                    .header("Authorization", "Bearer " + inviteeToken))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  @Test
  void acceptingWithADifferentAccountsEmailIsRejected() throws Exception {
    String inviteeEmail = "mismatch-target-" + UUID.randomUUID() + "@tracker.local";
    mockMvc.perform(
        post("/api/v1/workspaces/members/invitations")
            .header("Authorization", "Bearer " + adminToken)
            .header("X-Workspace-Id", workspaceId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + inviteeEmail + "\",\"role\":\"VIEWER\"}"));
    String token = extractTokenFromEmail(inviteeEmail);

    String otherEmail = "someone-else-" + UUID.randomUUID() + "@tracker.local";
    authService.register(otherEmail, PASSWORD, "Baskasi");
    String otherToken = authService.login(otherEmail, PASSWORD, "127.0.0.1").accessToken();

    assertEquals(
        400,
        mockMvc
            .perform(
                post("/api/v1/invitations/" + token + "/accept")
                    .header("Authorization", "Bearer " + otherToken))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  @Test
  void revokedInvitationCannotBeAccepted() throws Exception {
    String inviteeEmail = "revoke-me-" + UUID.randomUUID() + "@tracker.local";
    String createBody =
        mockMvc
            .perform(
                post("/api/v1/workspaces/members/invitations")
                    .header("Authorization", "Bearer " + adminToken)
                    .header("X-Workspace-Id", workspaceId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + inviteeEmail + "\",\"role\":\"VIEWER\"}"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID invitationId = extractId(createBody);
    String token = extractTokenFromEmail(inviteeEmail);

    String pendingBody =
        mockMvc
            .perform(
                get("/api/v1/workspaces/members/invitations")
                    .header("Authorization", "Bearer " + adminToken)
                    .header("X-Workspace-Id", workspaceId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(pendingBody.contains(inviteeEmail), pendingBody);

    assertEquals(
        204,
        mockMvc
            .perform(
                delete("/api/v1/workspaces/members/invitations/" + invitationId)
                    .header("Authorization", "Bearer " + adminToken)
                    .header("X-Workspace-Id", workspaceId.toString()))
            .andReturn()
            .getResponse()
            .getStatus());

    String pendingAfter =
        mockMvc
            .perform(
                get("/api/v1/workspaces/members/invitations")
                    .header("Authorization", "Bearer " + adminToken)
                    .header("X-Workspace-Id", workspaceId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(pendingAfter.contains(inviteeEmail), pendingAfter);

    authService.register(inviteeEmail, PASSWORD, "Iptal Edilen");
    String inviteeToken = authService.login(inviteeEmail, PASSWORD, "127.0.0.1").accessToken();
    assertEquals(
        400,
        mockMvc
            .perform(
                post("/api/v1/invitations/" + token + "/accept")
                    .header("Authorization", "Bearer " + inviteeToken))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  @Test
  void reInvitingTheSameEmailRevokesThePreviousPendingInvitation() throws Exception {
    String inviteeEmail = "resend-" + UUID.randomUUID() + "@tracker.local";
    mockMvc.perform(
        post("/api/v1/workspaces/members/invitations")
            .header("Authorization", "Bearer " + adminToken)
            .header("X-Workspace-Id", workspaceId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + inviteeEmail + "\",\"role\":\"VIEWER\"}"));
    mockMvc.perform(
        post("/api/v1/workspaces/members/invitations")
            .header("Authorization", "Bearer " + adminToken)
            .header("X-Workspace-Id", workspaceId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + inviteeEmail + "\",\"role\":\"MANAGER\"}"));

    String pendingBody =
        mockMvc
            .perform(
                get("/api/v1/workspaces/members/invitations")
                    .header("Authorization", "Bearer " + adminToken)
                    .header("X-Workspace-Id", workspaceId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long occurrences = pendingBody.split(inviteeEmail, -1).length - 1;
    assertEquals(1, occurrences, "onceki PENDING davet REVOKED'e cekilmis olmali: " + pendingBody);
  }

  @Test
  void inviteRequiresAdminRole() throws Exception {
    String devEmail = "notadmin-" + UUID.randomUUID() + "@tracker.local";
    UUID devId = authService.register(devEmail, PASSWORD, "Dev").getId();
    membershipService.addMember(workspaceId, devId, WorkspaceRole.DEVELOPER);
    String devToken = authService.login(devEmail, PASSWORD, "127.0.0.1").accessToken();

    assertEquals(
        403,
        mockMvc
            .perform(
                post("/api/v1/workspaces/members/invitations")
                    .header("Authorization", "Bearer " + devToken)
                    .header("X-Workspace-Id", workspaceId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\"x-"
                            + UUID.randomUUID()
                            + "@tracker.local\",\"role\":\"VIEWER\"}"))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  private static final Pattern ID_PATTERN = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");

  private static UUID extractId(String json) {
    Matcher matcher = ID_PATTERN.matcher(json);
    if (!matcher.find()) {
      throw new AssertionError("id bulunamadi: " + json);
    }
    return UUID.fromString(matcher.group(1));
  }

  /** Mailpit'ten davet e-postasinin GOVDESINI okuyup linkteki token'i cikarir. */
  private String extractTokenFromEmail(String recipientEmail) throws Exception {
    String messageId = waitForMessageId(recipientEmail);
    HttpResponse<String> detail =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(mailpitApiUrl("/api/v1/message/" + messageId)))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    Matcher matcher = TOKEN_IN_LINK.matcher(detail.body());
    if (!matcher.find()) {
      throw new AssertionError("E-posta govdesinde davet linki bulunamadi: " + detail.body());
    }
    return matcher.group(1);
  }

  private String waitForMessageId(String recipientEmail) {
    long deadline = System.currentTimeMillis() + 5000;
    do {
      String id = findMessageId(recipientEmail);
      if (id != null) {
        return id;
      }
      sleep(100);
    } while (System.currentTimeMillis() < deadline);
    throw new AssertionError("Davet e-postasi " + recipientEmail + " adresine ulasmadi");
  }

  private String findMessageId(String recipientEmail) {
    try {
      HttpResponse<String> response =
          HTTP.send(
              HttpRequest.newBuilder(URI.create(mailpitApiUrl("/api/v1/messages?limit=100")))
                  .timeout(Duration.ofSeconds(5))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      JsonNode root = objectMapper.readTree(response.body());
      for (JsonNode msg : listOf(root.path("messages"))) {
        for (JsonNode to : listOf(msg.path("To"))) {
          if (recipientEmail.equalsIgnoreCase(to.path("Address").asString(""))
              && msg.path("Subject").asString("").contains("davet edildin")) {
            return msg.path("ID").asString();
          }
        }
      }
      return null;
    } catch (Exception e) {
      throw new IllegalStateException("Mailpit'ten okunamadi", e);
    }
  }

  private static List<JsonNode> listOf(JsonNode arrayNode) {
    List<JsonNode> out = new ArrayList<>();
    arrayNode.forEach(out::add);
    return out;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
