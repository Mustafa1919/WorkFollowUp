package com.app.tracker.notification.email;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.notification.preferences.service.NotificationPreferencesService;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Mailpit'e (gercek SMTP + HTTP okuma API'si, bkz. AbstractIntegrationTest) karsi uctan uca —
 * consumer'lar SlackNotificationIntegrationTest/NotificationIntegrationTest ile AYNI desenle Kafka
 * zamanlamasindan bagimsiz DOGRUDAN cagrilir.
 */
@SpringBootTest
class EmailDeliveryIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthService authService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private EmailDeliveryConsumer deliveryConsumer;
  @Autowired private EmailTaskEventConsumer taskEventConsumer;
  @Autowired private NotificationPreferencesService preferencesService;

  private final Map<UUID, String> userEmails = new HashMap<>();

  private record Tenant(
      UUID workspaceId, UUID adminId, UUID developerId, UUID viewerId, UUID projectId) {}

  @BeforeEach
  void clearMailbox() throws Exception {
    HTTP.send(
        HttpRequest.newBuilder(URI.create(mailpitApiUrl("/api/v1/messages"))).DELETE().build(),
        HttpResponse.BodyHandlers.discarding());
  }

  @Test
  void registrationSendsAVerificationEmailToTheNewUser() {
    String email = "verify-" + UUID.randomUUID() + "@tracker.local";
    authService.register(email, PASSWORD, "Yeni Kullanici");

    assertEquals(1, messagesTo(email, "dogrula", 1).size());
  }

  @Test
  void redeliveryOfTheSameSecurityAlertEventSendsOnlyOneEmail() {
    // Kayit kendi dogrulama e-postasini da gonderir (ayni alici) — bu test onunla
    // CAKISMASIN diye farkli bir olay tipi (security_alert, konusu "Guvenlik") kullanilir.
    String email = "dup-" + UUID.randomUUID() + "@tracker.local";
    UUID userId = register(email, "Tekrar");
    String envelope = securityAlertEnvelope(UUID.randomUUID(), userId, "password_changed");

    deliveryConsumer.onMessage(envelope);
    deliveryConsumer.onMessage(envelope); // at-least-once: ayni olay yeniden teslim

    assertEquals(1, messagesTo(email, "Guvenlik", 1).size());
  }

  @Test
  void assignmentEmailReachesTheNewAssigneeButNotTheActor() {
    Tenant tenant = newTenant();
    Task task = createTask(tenant);

    taskEventConsumer.onMessage(
        assignedEnvelope(UUID.randomUUID(), tenant, task, tenant.adminId(), tenant.developerId()));

    assertEquals(1, messagesTo(userEmails.get(tenant.developerId()), "atandi", 1).size());
    assertEquals(
        0,
        messagesTo(userEmails.get(tenant.adminId()), "atandi", 0).size(),
        "aktore e-posta gitmemeli");
  }

  @Test
  void assignmentEmailIsSkippedWhenThePreferenceIsOff() {
    Tenant tenant = newTenant();
    Task task = createTask(tenant);
    preferencesService.update(tenant.developerId(), false, true);

    taskEventConsumer.onMessage(
        assignedEnvelope(UUID.randomUUID(), tenant, task, tenant.adminId(), tenant.developerId()));

    assertEquals(0, messagesTo(userEmails.get(tenant.developerId()), "atandi", 0).size());
  }

  @Test
  void mentionEmailReachesEachMentionedMemberButNotTheActorOrANonMember() {
    Tenant tenant = newTenant();
    Task task = createTask(tenant);
    UUID outsiderId = register("outsider-" + UUID.randomUUID() + "@tracker.local", "Disarida");

    taskEventConsumer.onMessage(
        mentionEnvelope(
            UUID.randomUUID(),
            tenant,
            task,
            tenant.adminId(),
            List.of(tenant.developerId(), tenant.adminId(), outsiderId)));

    assertEquals(1, messagesTo(userEmails.get(tenant.developerId()), "etiketlendin", 1).size());
    assertEquals(
        0,
        messagesTo(userEmails.get(tenant.adminId()), "etiketlendin", 0).size(),
        "aktor kendine mention e-postasi almaz");
    assertEquals(
        0,
        messagesTo(userEmails.get(outsiderId), "etiketlendin", 0).size(),
        "workspace uyesi olmayan alici degildir");
  }

  @Test
  void redeliveryOfTheSameMentionEventIsIdempotentPerRecipient() {
    Tenant tenant = newTenant();
    Task task = createTask(tenant);
    String envelope =
        mentionEnvelope(
            UUID.randomUUID(), tenant, task, tenant.adminId(), List.of(tenant.developerId()));

    taskEventConsumer.onMessage(envelope);
    taskEventConsumer.onMessage(envelope);

    assertEquals(1, messagesTo(userEmails.get(tenant.developerId()), "etiketlendin", 1).size());
  }

  // ---- yardimcilar --------------------------------------------------------------------------

  private UUID register(String email, String fullName) {
    UUID id = authService.register(email, PASSWORD, fullName).getId();
    userEmails.put(id, email);
    return id;
  }

  private Tenant newTenant() {
    UUID workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Email WS"));

    UUID adminId = register("email-admin-" + UUID.randomUUID() + "@tracker.local", "Admin");
    membershipService.addMember(workspaceId, adminId, WorkspaceRole.ADMIN);

    UUID devId = register("email-dev-" + UUID.randomUUID() + "@tracker.local", "Dev");
    membershipService.addMember(workspaceId, devId, WorkspaceRole.DEVELOPER);

    UUID viewerId = register("email-viewer-" + UUID.randomUUID() + "@tracker.local", "Viewer");
    membershipService.addMember(workspaceId, viewerId, WorkspaceRole.VIEWER);

    Project project =
        tenantExecutor.runAs(workspaceId, () -> projectService.createProject("EML", "Email Proje"));

    return new Tenant(workspaceId, adminId, devId, viewerId, project.getId());
  }

  private Task createTask(Tenant tenant) {
    return tenantExecutor.runAs(
        tenant.workspaceId(), () -> taskService.createTask(tenant.projectId(), "E-posta gorevi"));
  }

  private String securityAlertEnvelope(UUID eventId, UUID userId, String reason) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("userId", userId.toString());
    payload.put("reason", reason);
    ObjectNode envelope = objectMapper.createObjectNode();
    envelope.put("eventId", eventId.toString());
    envelope.put("eventType", "email.security_alert");
    envelope.put("schemaVersion", 1);
    envelope.putNull("workspaceId");
    envelope.set("payload", payload);
    return objectMapper.writeValueAsString(envelope);
  }

  private String assignedEnvelope(
      UUID eventId, Tenant tenant, Task task, UUID actorId, UUID newAssigneeId) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("taskId", task.getId().toString());
    payload.put("projectId", tenant.projectId().toString());
    payload.put("actorId", actorId.toString());
    payload.putNull("oldAssigneeId");
    payload.put("newAssigneeId", newAssigneeId.toString());
    return envelope(eventId, "TASK_ASSIGNED", tenant.workspaceId(), payload);
  }

  private String mentionEnvelope(
      UUID eventId, Tenant tenant, Task task, UUID actorId, List<UUID> mentionedUserIds) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("taskId", task.getId().toString());
    payload.put("projectId", tenant.projectId().toString());
    payload.put("actorId", actorId.toString());
    payload.put("commentId", UUID.randomUUID().toString());
    var mentioned = payload.putArray("mentionedUserIds");
    mentionedUserIds.forEach(id -> mentioned.add(id.toString()));
    return envelope(eventId, "COMMENT_MENTION", tenant.workspaceId(), payload);
  }

  private String envelope(UUID eventId, String eventType, UUID workspaceId, ObjectNode payload) {
    ObjectNode envelope = objectMapper.createObjectNode();
    envelope.put("eventId", eventId.toString());
    envelope.put("eventType", eventType);
    envelope.put("schemaVersion", 1);
    envelope.put("workspaceId", workspaceId.toString());
    envelope.set("payload", payload);
    return objectMapper.writeValueAsString(envelope);
  }

  /**
   * Mailpit'e HEMEN ulasir (SMTP cagrisi consumer icinde SENKRON, arka plandaki gercek Kafka
   * pipeline'i icinse kisa bir tolerans yeterli). {@code subjectContains} filtresi ZORUNLU: ayni
   * alici, testin kendi kurulumunun (ör. kayit) ürettigi BASKA turden bir e-postayi da almis
   * olabilir (bkz. redeliveryOfTheSameSecurityAlertEventSendsOnlyOneEmail yorumu).
   */
  private List<JsonNode> messagesTo(String email, String subjectContains, int expectedAtLeast) {
    long deadline = System.currentTimeMillis() + 3000;
    List<JsonNode> matches = List.of();
    do {
      matches = fetchMessages(email, subjectContains);
      if (matches.size() >= expectedAtLeast) {
        break;
      }
      sleep(100);
    } while (System.currentTimeMillis() < deadline);
    return matches;
  }

  private List<JsonNode> fetchMessages(String email, String subjectContains) {
    try {
      HttpResponse<String> response =
          HTTP.send(
              HttpRequest.newBuilder(URI.create(mailpitApiUrl("/api/v1/messages?limit=100")))
                  .timeout(Duration.ofSeconds(5))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      JsonNode root = objectMapper.readTree(response.body());
      List<JsonNode> out = new ArrayList<>();
      root.path("messages")
          .forEach(
              msg -> {
                boolean toMatches = false;
                for (JsonNode to : msg.path("To")) {
                  if (email.equalsIgnoreCase(to.path("Address").asString(""))) {
                    toMatches = true;
                  }
                }
                if (toMatches && msg.path("Subject").asString("").contains(subjectContains)) {
                  out.add(msg);
                }
              });
      return out;
    } catch (Exception e) {
      throw new IllegalStateException("Mailpit'ten okunamadi", e);
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
