package com.app.tracker.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.notification.consumer.SlackNotificationConsumer;
import com.app.tracker.notification.service.SlackNotificationService;
import com.app.tracker.notification.service.SlackPermanentException;
import com.app.tracker.notification.service.SlackTransientException;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Faz 3 / Dilim 3.5 — Slack cikis bildirimi, gercek Postgres (RLS) + Kafka + Redis ile. Gonderici
 * {@link RecordingSlackSender} ile degistirilmistir (gercek Slack'e cikilmaz). Cogu test consumer'i
 * DOGRUDAN cagirir (Kafka zamanlamasindan bagimsiz, deterministik); tek uctan uca test outbox ->
 * Kafka -> consumer zincirini gercekten yurutur.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SlackNotificationIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final String PATH = "/api/v1/integrations/slack";

  private record Tenant(
      UUID workspaceId, UUID adminUserId, String adminToken, UUID projectId, List<Task> tasks) {}

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthService authService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private SlackNotificationConsumer consumer;

  // ---- yonetim API'si ---------------------------------------------------------------------------

  @Test
  void adminConfiguresReadsUpdatesAndDeletesWithoutEverSeeingTheUrl() throws Exception {
    Tenant tenant = newTenant("ENG", 0);
    String url = newSlackUrl();

    JsonNode created = putSlack(tenant, "{\"webhookUrl\":\"" + url + "\"}", 200);
    assertTrue(created.path("enabled").asBoolean(), "enabled verilmezse acik");
    assertNoUrl(created.toString(), url);

    JsonNode read = getSlack(tenant, 200);
    assertNoUrl(read.toString(), url);

    JsonNode disabled = putSlack(tenant, "{\"webhookUrl\":\"" + url + "\",\"enabled\":false}", 200);
    assertFalse(disabled.path("enabled").asBoolean());
    assertEquals(1L, slackRowCount(tenant.workspaceId()), "PUT ayni satiri gunceller, cogaltmaz");

    mockMvc
        .perform(
            delete(PATH)
                .header("Authorization", "Bearer " + tenant.adminToken())
                .header("X-Workspace-Id", tenant.workspaceId().toString()))
        .andExpect(status().isNoContent());
    getSlack(tenant, 404);
    assertEquals(0L, slackRowCount(tenant.workspaceId()));
  }

  @Test
  void urlIsStoredEncryptedNeverAsPlaintext() throws Exception {
    Tenant tenant = newTenant("ENG", 0);
    String url = newSlackUrl();
    putSlack(tenant, "{\"webhookUrl\":\"" + url + "\"}", 200);

    String stored = storedCiphertext(tenant.workspaceId());

    assertTrue(stored.startsWith("v1."));
    assertFalse(stored.contains("hooks.slack.com"));
    assertFalse(stored.contains(url.substring(url.lastIndexOf('/') + 1)), "token gorunmemeli");
  }

  @Test
  void rejectsNonSlackAddressesAndStoresNothing() throws Exception {
    Tenant tenant = newTenant("ENG", 0);

    for (String bad :
        new String[] {
          "https://evil.example/services/T1/B2/abc",
          "http://hooks.slack.com/services/T1/B2/abc",
          "https://hooks.slack.com@evil.example/services/T1/B2/abc",
          "http://169.254.169.254/latest/meta-data/",
          "https://hooks.slack.com/services/T1/B2/abc?redirect=x"
        }) {
      JsonNode problem = putSlack(tenant, "{\"webhookUrl\":\"" + bad + "\"}", 400);
      assertFalse(problem.toString().contains("evil.example"), "girdi yanita yansitilmamali");
    }
    // Bean Validation ihlali (bos/eksik alan) projede 422 (GlobalExceptionHandler), is kurali 400.
    putSlack(tenant, "{\"webhookUrl\":\"\"}", 422);
    putSlack(tenant, "{}", 422);
    assertEquals(0L, slackRowCount(tenant.workspaceId()));
  }

  @Test
  void managementIsTenantIsolatedAndRowsAreInvisibleWithoutTenantContext() throws Exception {
    Tenant a = newTenant("ENG", 0);
    Tenant b = newTenant("ENG", 0);
    putSlack(a, "{\"webhookUrl\":\"" + newSlackUrl() + "\"}", 200);

    getSlack(b, 404);
    mockMvc
        .perform(
            delete(PATH)
                .header("Authorization", "Bearer " + b.adminToken())
                .header("X-Workspace-Id", b.workspaceId().toString()))
        .andExpect(status().isNotFound());
    assertEquals(1L, slackRowCount(a.workspaceId()), "B'nin silme denemesi A'yi etkilememeli");

    assertEquals(0L, slackRowCount(null), "context yoksa fail-closed olmali");
    assertEquals(0L, slackRowCount(UUID.randomUUID()));
  }

  // ---- Notification Worker (consumer seviyesi) --------------------------------------------------

  @Test
  void statusChangeIsSentOnceEvenIfTheEventIsRedelivered() throws Exception {
    Tenant tenant = newTenant("ENG", 1);
    URI url = configureSlack(tenant);
    UUID eventId = UUID.randomUUID();
    String envelope =
        statusEnvelope(eventId, tenant, tenant.tasks().get(0), "To Do", "In Progress");

    consumer.onMessage(envelope);
    consumer.onMessage(envelope); // at-least-once: ayni olay yeniden teslim

    assertEquals(
        List.of("*ENG-1* Gorev 1: To Do → In Progress"), RecordingSlackSender.textsSentTo(url));
    assertEquals(1L, deliveredMarkerCount(eventId));
  }

  @Test
  void createdEventIsSentWithProjectKeyAndTitle() throws Exception {
    Tenant tenant = newTenant("ENG", 1);
    URI url = configureSlack(tenant);
    Task task = tenant.tasks().get(0);

    consumer.onMessage(createdEnvelope(UUID.randomUUID(), tenant, task));

    assertEquals(List.of("New task *ENG-1*: Gorev 1"), RecordingSlackSender.textsSentTo(url));
  }

  @Test
  void nothingIsSentWhenNotConfiguredOrDisabledAndNoMarkerIsLeft() throws Exception {
    Tenant tenant = newTenant("ENG", 1);
    UUID whileUnconfigured = UUID.randomUUID();
    consumer.onMessage(
        statusEnvelope(whileUnconfigured, tenant, tenant.tasks().get(0), "To Do", "Done"));
    assertEquals(0L, deliveredMarkerCount(whileUnconfigured));

    URI url = configureSlack(tenant);
    putSlack(tenant, "{\"webhookUrl\":\"" + url + "\",\"enabled\":false}", 200);
    UUID whileDisabled = UUID.randomUUID();
    consumer.onMessage(
        statusEnvelope(whileDisabled, tenant, tenant.tasks().get(0), "To Do", "Done"));

    assertEquals(List.of(), RecordingSlackSender.textsSentTo(url));
    assertEquals(0L, deliveredMarkerCount(whileDisabled));
  }

  @Test
  void aTenantNeverGetsNotificationsAboutAnotherTenantsTask() throws Exception {
    Tenant a = newTenant("ENG", 1);
    Tenant b = newTenant("ENG", 1);
    URI urlA = configureSlack(a);
    URI urlB = configureSlack(b);

    // Zarf A'ya ait ama taskId B'nin: gorev arama RLS'e tabi, eslesemez -> mesaj yok.
    UUID crafted = UUID.randomUUID();
    consumer.onMessage(statusEnvelope(crafted, a, b.tasks().get(0), "To Do", "Done"));

    assertEquals(List.of(), RecordingSlackSender.textsSentTo(urlA));
    assertEquals(List.of(), RecordingSlackSender.textsSentTo(urlB));
    assertEquals(0L, deliveredMarkerCount(crafted));
  }

  @Test
  void eachTenantsEventsGoOnlyToItsOwnChannel() throws Exception {
    Tenant a = newTenant("ENG", 1);
    Tenant b = newTenant("ENG", 1);
    URI urlA = configureSlack(a);
    URI urlB = configureSlack(b);

    consumer.onMessage(statusEnvelope(UUID.randomUUID(), a, a.tasks().get(0), "To Do", "Done"));

    assertEquals(1, RecordingSlackSender.textsSentTo(urlA).size());
    assertEquals(List.of(), RecordingSlackSender.textsSentTo(urlB));
  }

  @Test
  void transientFailureIsThrownForRetryAndLeavesNoMarkerThenSucceedsOnRedelivery()
      throws Exception {
    Tenant tenant = newTenant("ENG", 1);
    URI url = configureSlack(tenant);
    UUID eventId = UUID.randomUUID();
    String envelope = statusEnvelope(eventId, tenant, tenant.tasks().get(0), "To Do", "Review");

    RecordingSlackSender.failWith(url, new SlackTransientException("slack down"));
    assertThrows(SlackTransientException.class, () -> consumer.onMessage(envelope));
    assertEquals(0L, deliveredMarkerCount(eventId), "gonderilemeyen olay islendi sayilmamali");
    assertEquals(List.of(), RecordingSlackSender.textsSentTo(url));

    RecordingSlackSender.stopFailing(url);
    consumer.onMessage(envelope); // Kafka error handler'in yeniden denemesi
    assertEquals(1, RecordingSlackSender.textsSentTo(url).size());
    assertEquals(1L, deliveredMarkerCount(eventId));
  }

  @Test
  void permanentSlackRejectionIsSwallowedSoTheDeadLetterTopicIsNotFlooded() throws Exception {
    Tenant tenant = newTenant("ENG", 1);
    URI url = configureSlack(tenant);
    UUID eventId = UUID.randomUUID();
    RecordingSlackSender.failWith(url, new SlackPermanentException("HTTP 404"));

    consumer.onMessage(statusEnvelope(eventId, tenant, tenant.tasks().get(0), "To Do", "Done"));

    assertEquals(0L, deliveredMarkerCount(eventId));
    assertEquals(List.of(), RecordingSlackSender.textsSentTo(url));
  }

  @Test
  void uninterestingEventsAreIgnoredAndMalformedMessagesAreRejectedWithoutRetry() throws Exception {
    Tenant tenant = newTenant("ENG", 1);
    URI url = configureSlack(tenant);
    Task task = tenant.tasks().get(0);

    // Ilgisiz olay tipleri: payload bozuk olsa bile sessizce atlanir.
    consumer.onMessage(
        "{\"eventType\":\"TASK_SPRINT_CHANGED\",\"eventId\":\"x\",\"workspaceId\":\"y\"}");
    consumer.onMessage("{\"eventType\":\"SPRINT_COMPLETED\"}");
    assertEquals(List.of(), RecordingSlackSender.textsSentTo(url));

    // Bozuk mesajlar IllegalArgumentException (KafkaConsumerConfig: yeniden denemesiz DLT).
    assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("not json"));
    assertThrows(
        IllegalArgumentException.class,
        () -> consumer.onMessage("{\"eventType\":\"TASK_STATUS_UPDATED\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            consumer.onMessage(
                statusEnvelope(UUID.randomUUID(), tenant, task, "To Do", "Done")
                    .replace(task.getId().toString(), "not-a-uuid")));
  }

  @Test
  void ciphertextCopiedFromAnotherTenantIsNotUsable() throws Exception {
    Tenant a = newTenant("ENG", 1);
    Tenant b = newTenant("ENG", 1);
    URI urlA = configureSlack(a);
    // B'nin satirina A'nin sifreli adresini koy (DB'ye erisen birinin kopyalama denemesi).
    String copied = storedCiphertext(a.workspaceId());
    inTenant(
        b.workspaceId(),
        () ->
            entityManager
                .createNativeQuery(
                    "INSERT INTO slack_integrations (workspace_id, webhook_url_encrypted) "
                        + "VALUES (?1, ?2)")
                .setParameter(1, b.workspaceId())
                .setParameter(2, copied)
                .executeUpdate());
    UUID eventId = UUID.randomUUID();

    assertThrows(
        IllegalStateException.class,
        () -> consumer.onMessage(statusEnvelope(eventId, b, b.tasks().get(0), "To Do", "Done")),
        "cozulemeyen adres sessizce 'bildirim yok' demek yerine fark edilmeli");

    assertEquals(List.of(), RecordingSlackSender.textsSentTo(urlA));
  }

  // ---- uctan uca --------------------------------------------------------------------------------

  @Test
  void taskLifecycleReachesSlackThroughOutboxAndKafka() throws Exception {
    Tenant tenant = newTenant("ENG", 0);
    URI url = configureSlack(tenant);

    // Consumer group 'latest' okur: atama tamamlanmadan uretilen olaylar kacabilir. Bir olay
    // gorunene kadar sonda gorev uretip bekleriz (mesaj metni benzersiz).
    awaitConsumerReady(tenant, url);

    Task task =
        tenantExecutor.runAs(
            tenant.workspaceId(), () -> taskService.createTask(tenant.projectId(), "E2E gorev"));
    String created = "New task *ENG-" + task.getTaskNumber() + "*: E2E gorev";
    await(() -> RecordingSlackSender.textsSentTo(url).contains(created), "TASK_CREATED gelmedi");

    tenantExecutor.runAs(
        tenant.workspaceId(),
        () -> taskService.updateStatus(task.getId(), TaskStatus.IN_PROGRESS, tenant.adminUserId()));
    String moved =
        "*ENG-" + task.getTaskNumber() + "* E2E gorev: To Do → " + TaskStatus.IN_PROGRESS;
    await(() -> RecordingSlackSender.textsSentTo(url).contains(moved), "durum degisikligi gelmedi");

    long occurrences = RecordingSlackSender.textsSentTo(url).stream().filter(moved::equals).count();
    assertEquals(1L, occurrences, "her olay tek mesaj uretmeli");
  }

  // ---- yardimcilar ------------------------------------------------------------------------------

  private void awaitConsumerReady(Tenant tenant, URI url) throws Exception {
    long deadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < deadline) {
      if (!RecordingSlackSender.textsSentTo(url).isEmpty()) {
        return;
      }
      tenantExecutor.runAs(
          tenant.workspaceId(), () -> taskService.createTask(tenant.projectId(), "warmup"));
      Thread.sleep(1000);
    }
    fail("Slack consumer 60 saniyede hazir olmadi");
  }

  private static void await(Supplier<Boolean> condition, String failure) throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      if (condition.get()) {
        return;
      }
      Thread.sleep(200);
    }
    fail("30 saniye icinde: " + failure);
  }

  private Tenant newTenant(String projectKey, int taskCount) {
    UUID workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Slack WS"));
    String email = "slack-admin-" + UUID.randomUUID() + "@tracker.local";
    UUID adminId = authService.register(email, PASSWORD, "Slack Admin").getId();
    membershipService.addMember(workspaceId, adminId, WorkspaceRole.ADMIN);
    String token = authService.login(email, PASSWORD, "127.0.0.1").accessToken();

    Project project =
        tenantExecutor.runAs(workspaceId, () -> projectService.createProject(projectKey, "Proje"));
    List<Task> tasks = new java.util.ArrayList<>();
    for (int i = 1; i <= taskCount; i++) {
      String title = "Gorev " + i;
      tasks.add(
          tenantExecutor.runAs(workspaceId, () -> taskService.createTask(project.getId(), title)));
    }
    return new Tenant(workspaceId, adminId, token, project.getId(), tasks);
  }

  /** Her cagri BENZERSIZ, allow-list'e uyan bir adres uretir (testler adresle suzer). */
  private static String newSlackUrl() {
    String id = UUID.randomUUID().toString().replace("-", "");
    return "https://hooks.slack.com/services/T"
        + id.substring(0, 8)
        + "/B"
        + id.substring(8, 16)
        + "/"
        + id.substring(16);
  }

  private URI configureSlack(Tenant tenant) throws Exception {
    String url = newSlackUrl();
    putSlack(tenant, "{\"webhookUrl\":\"" + url + "\"}", 200);
    return URI.create(url);
  }

  private JsonNode putSlack(Tenant tenant, String body, int expectedStatus) throws Exception {
    String response =
        mockMvc
            .perform(
                put(PATH)
                    .header("Authorization", "Bearer " + tenant.adminToken())
                    .header("X-Workspace-Id", tenant.workspaceId().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().is(expectedStatus))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response);
  }

  private JsonNode getSlack(Tenant tenant, int expectedStatus) throws Exception {
    String response =
        mockMvc
            .perform(
                get(PATH)
                    .header("Authorization", "Bearer " + tenant.adminToken())
                    .header("X-Workspace-Id", tenant.workspaceId().toString()))
            .andExpect(status().is(expectedStatus))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response);
  }

  private static void assertNoUrl(String responseBody, String url) {
    assertFalse(responseBody.contains(url), "yanit adresi icermemeli");
    assertFalse(responseBody.contains("hooks.slack.com"), "yanit adresi icermemeli");
    assertFalse(responseBody.contains("webhookUrl"));
  }

  private String statusEnvelope(
      UUID eventId, Tenant tenant, Task task, String oldStatus, String newStatus) {
    var payload = objectMapper.createObjectNode();
    payload.put("taskId", task.getId().toString());
    payload.put("projectId", tenant.projectId().toString());
    payload.put("oldStatus", oldStatus);
    payload.put("newStatus", newStatus);
    return envelope(eventId, "TASK_STATUS_UPDATED", tenant.workspaceId(), payload);
  }

  private String createdEnvelope(UUID eventId, Tenant tenant, Task task) {
    var payload = objectMapper.createObjectNode();
    payload.put("taskId", task.getId().toString());
    payload.put("projectId", tenant.projectId().toString());
    payload.put("taskNumber", task.getTaskNumber());
    payload.put("title", task.getTitle());
    payload.put("status", task.getStatus());
    return envelope(eventId, "TASK_CREATED", tenant.workspaceId(), payload);
  }

  private String envelope(
      UUID eventId,
      String eventType,
      UUID workspaceId,
      tools.jackson.databind.node.ObjectNode payload) {
    var envelope = objectMapper.createObjectNode();
    envelope.put("eventId", eventId.toString());
    envelope.put("eventType", eventType);
    envelope.put("schemaVersion", 1);
    envelope.put("workspaceId", workspaceId.toString());
    envelope.set("payload", payload);
    return objectMapper.writeValueAsString(envelope);
  }

  private long deliveredMarkerCount(UUID eventId) {
    return ((Number)
            transactionTemplate.execute(
                status ->
                    entityManager
                        .createNativeQuery(
                            "SELECT COUNT(*) FROM processed_events WHERE consumer = ?1 AND event_id = ?2")
                        .setParameter(1, SlackNotificationService.CONSUMER)
                        .setParameter(2, eventId)
                        .getSingleResult()))
        .longValue();
  }

  private long slackRowCount(UUID tenant) {
    return inTenant(
        tenant,
        () ->
            ((Number)
                    entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM slack_integrations")
                        .getSingleResult())
                .longValue());
  }

  private String storedCiphertext(UUID tenant) {
    return inTenant(
        tenant,
        () ->
            (String)
                entityManager
                    .createNativeQuery("SELECT webhook_url_encrypted FROM slack_integrations")
                    .getSingleResult());
  }

  /**
   * TransactionTemplate AOP'tan gecmedigi icin tenant context'i (SET LOCAL esdegeri) elle kurulur.
   */
  private <T> T inTenant(UUID tenant, Supplier<T> action) {
    return transactionTemplate.execute(
        status -> {
          if (tenant != null) {
            entityManager
                .createNativeQuery("SELECT set_config('app.current_workspace_id', ?1, true)")
                .setParameter(1, tenant.toString())
                .getSingleResult();
          }
          return action.get();
        });
  }
}
