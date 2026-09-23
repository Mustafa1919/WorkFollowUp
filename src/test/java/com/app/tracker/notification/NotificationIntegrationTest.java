package com.app.tracker.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.notification.consumer.InboxNotificationConsumer;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.notification.service.NotificationService;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Faz 4 oncesi ara adim — In-app Inbox (RAKIP_ANALIZI.md Bolum 3). SlackNotificationIntegrationTest
 * ile AYNI desen: consumer'i Kafka zamanlamasindan bagimsiz DOGRUDAN cagirir (deterministik).
 *
 * <p><b>Tuzak (bu dosyayi yazarken yasandi):</b> okuma yardimcisi ilk halinde {@code
 * NotificationRepository}'yi DOGRUDAN cagiriyordu (servis katmani DISINDAN) — Backend-Notlar
 * 2026-09-20 "TenancyGuardAspect ve Spring Data repository @Transactional" tuzaginin birebir
 * tekrari: repository'nin kendi {@code @Transactional} proxy'si TenancyGuardAspect'ten farkli
 * sekilde davranip {@code SET LOCAL app.current_workspace_id} hic uygulanmadan calisiyor, satir
 * DB'de var olsa bile RLS'ten hicbir sey donmuyordu (native SQL + elle {@code set_config} ile
 * dogrulanip kanitlandi). Duzeltme: her okuma {@link NotificationService} (kendi ACIK transaction'i
 * olan servis katmani) uzerinden yapilir, repository dogrudan cagrilmaz.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  private record Tenant(
      UUID workspaceId,
      UUID adminId,
      String adminToken,
      UUID developerId,
      String developerToken,
      UUID viewerId,
      UUID projectId) {}

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthService authService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private InboxNotificationConsumer consumer;
  @Autowired private NotificationService notificationService;

  // ---- fanout (consumer seviyesi)
  // -----------------------------------------------------------------

  @Test
  void statusChangeNotifiesEligibleMembersButNeverTheActor() {
    Tenant tenant = newTenant();
    Task task = createTask(tenant);

    consumer.onMessage(
        statusEnvelope(UUID.randomUUID(), tenant, task, "To Do", "In Progress", tenant.adminId()));

    List<Notification> developerInbox = forUser(tenant, tenant.developerId());
    assertEquals(1, developerInbox.size());
    assertEquals("TASK_STATUS_UPDATED", developerInbox.get(0).getType());
    assertTrue(developerInbox.get(0).getBody().contains("In Progress"));

    assertTrue(forUser(tenant, tenant.adminId()).isEmpty(), "aktor kendine bildirim almaz");
    assertTrue(forUser(tenant, tenant.viewerId()).isEmpty(), "VIEWER alici degildir");
  }

  @Test
  void redeliveryOfTheSameEventIsIdempotent() {
    Tenant tenant = newTenant();
    Task task = createTask(tenant);
    String envelope =
        statusEnvelope(UUID.randomUUID(), tenant, task, "To Do", "Done", tenant.adminId());

    consumer.onMessage(envelope);
    consumer.onMessage(envelope); // at-least-once: ayni olay yeniden teslim

    assertEquals(1, forUser(tenant, tenant.developerId()).size());
  }

  @Test
  void approvalAndDueDateChangesAreNotifiableButUnrelatedEventTypesAreNot() {
    Tenant tenant = newTenant();
    Task task = createTask(tenant);

    consumer.onMessage(approvedEnvelope(UUID.randomUUID(), tenant, task, tenant.adminId()));
    assertEquals(1, forUser(tenant, tenant.developerId()).size());

    consumer.onMessage(dueDateEnvelope(UUID.randomUUID(), tenant, task, tenant.adminId()));
    assertEquals(2, forUser(tenant, tenant.developerId()).size());

    // Bildirime KONU OLMAYAN tip (etiket/sprint/story point degisikligi bu ailededir): tip
    // filtresinde ELENIR, tenant baglami/DB'ye hic dokunulmaz.
    consumer.onMessage(
        "{\"eventId\":\""
            + UUID.randomUUID()
            + "\",\"eventType\":\"TASK_SPRINT_CHANGED\",\"workspaceId\":\""
            + tenant.workspaceId()
            + "\",\"payload\":{}}");
    assertEquals(2, forUser(tenant, tenant.developerId()).size());
  }

  @Test
  void unknownOrDeletedTaskIsSkippedWithoutError() {
    Tenant tenant = newTenant();
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("taskId", UUID.randomUUID().toString());
    payload.put("projectId", tenant.projectId().toString());
    payload.put("oldStatus", "To Do");
    payload.put("newStatus", "Done");
    payload.put("actorId", tenant.adminId().toString());

    consumer.onMessage(
        envelope(UUID.randomUUID(), "TASK_STATUS_UPDATED", tenant.workspaceId(), payload));

    assertTrue(forUser(tenant, tenant.developerId()).isEmpty());
  }

  // ---- REST: yalniz kendi bildirimlerim
  // -------------------------------------------------------------

  @Test
  void memberCanOnlyReadAndMarkTheirOwnNotifications() throws Exception {
    Tenant tenant = newTenant();
    Task task = createTask(tenant);
    consumer.onMessage(
        statusEnvelope(UUID.randomUUID(), tenant, task, "To Do", "In Progress", tenant.adminId()));
    Notification developerNotification = forUser(tenant, tenant.developerId()).get(0);

    // ADMIN'in kendi gelen kutusu bos (aktordu) — listeleme uzerinden dogrulanir.
    mockMvc
        .perform(authed(get("/api/v1/notifications"), tenant, tenant.adminToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));

    // ADMIN, DEVELOPER'in bildirimini id'sini bilse bile okundu isaretleyemez -> 404 (varlik degil
    // ownership hatasi; ayni workspace icinde bile).
    mockMvc
        .perform(
            authed(
                post("/api/v1/notifications/" + developerNotification.getId() + "/read"),
                tenant,
                tenant.adminToken()))
        .andExpect(status().isNotFound());

    // DEVELOPER kendi bildirimini gorur ve okundu isaretleyebilir.
    mockMvc
        .perform(authed(get("/api/v1/notifications"), tenant, tenant.developerToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));
    mockMvc
        .perform(
            authed(
                post("/api/v1/notifications/" + developerNotification.getId() + "/read"),
                tenant,
                tenant.developerToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.read").value(true));

    assertEquals(
        0L, inWorkspace(tenant, () -> notificationService.unreadCount(tenant.developerId())));
  }

  @Test
  void markAllReadOnlyAffectsTheCallersOwnRows() throws Exception {
    Tenant tenant = newTenant();
    Task task = createTask(tenant);
    consumer.onMessage(
        statusEnvelope(UUID.randomUUID(), tenant, task, "To Do", "In Progress", tenant.adminId()));
    consumer.onMessage(approvedEnvelope(UUID.randomUUID(), tenant, task, tenant.developerId()));

    mockMvc
        .perform(authed(post("/api/v1/notifications/read-all"), tenant, tenant.developerToken()))
        .andExpect(status().isOk());

    assertEquals(
        0L, inWorkspace(tenant, () -> notificationService.unreadCount(tenant.developerId())));
    // Onay bildirimi aktoru DEVELOPER'di, alicisi ADMIN'di: ADMIN'in okunmamis sayaci
    // ETKILENMEMELI.
    assertEquals(1L, inWorkspace(tenant, () -> notificationService.unreadCount(tenant.adminId())));
  }

  // ---- RLS izolasyonu
  // -----------------------------------------------------------------------------

  @Test
  void otherWorkspacesNotificationsAreInvisible() {
    Tenant a = newTenant();
    Tenant b = newTenant();
    consumer.onMessage(
        statusEnvelope(UUID.randomUUID(), a, createTask(a), "To Do", "In Progress", a.adminId()));

    List<Notification> aInbox = forUser(a, a.developerId());
    assertEquals(1, aInbox.size());
    UUID notificationId = aInbox.get(0).getId();

    // B baglaminda ayni id + A'nin kullanicisi: RLS workspace'i eslesmedigi icin satir hic
    // gorunmez, servis "bulunamadi" olarak yorumlar (veri sizintisi degil, tutarli 404).
    assertThrows(
        ResourceNotFoundException.class,
        () ->
            tenantExecutor.runAs(
                b.workspaceId(),
                () -> notificationService.markRead(notificationId, a.developerId())));

    assertTrue(forUser(b, a.developerId()).isEmpty());
  }

  // ---- yardimcilar
  // --------------------------------------------------------------------------------

  private Task createTask(Tenant tenant) {
    return tenantExecutor.runAs(
        tenant.workspaceId(), () -> taskService.createTask(tenant.projectId(), "Bildirim gorevi"));
  }

  /** {@link NotificationService} UZERINDEN okur (bkz. sinif javadoc'undaki tuzak). */
  private List<Notification> forUser(Tenant tenant, UUID userId) {
    return inWorkspace(tenant, () -> notificationService.listMine(userId, 50, null, false).data());
  }

  private <T> T inWorkspace(Tenant tenant, Supplier<T> action) {
    return tenantExecutor.runAs(tenant.workspaceId(), action);
  }

  private MockHttpServletRequestBuilder authed(
      MockHttpServletRequestBuilder request, Tenant tenant, String token) {
    return request
        .header("Authorization", "Bearer " + token)
        .header("X-Workspace-Id", tenant.workspaceId().toString());
  }

  private Tenant newTenant() {
    UUID workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Inbox WS"));

    String adminEmail = "inbox-admin-" + UUID.randomUUID() + "@tracker.local";
    UUID adminId = authService.register(adminEmail, PASSWORD, "Admin").getId();
    membershipService.addMember(workspaceId, adminId, WorkspaceRole.ADMIN);
    String adminToken = authService.login(adminEmail, PASSWORD, "127.0.0.1").accessToken();

    String devEmail = "inbox-dev-" + UUID.randomUUID() + "@tracker.local";
    UUID devId = authService.register(devEmail, PASSWORD, "Dev").getId();
    membershipService.addMember(workspaceId, devId, WorkspaceRole.DEVELOPER);
    String devToken = authService.login(devEmail, PASSWORD, "127.0.0.1").accessToken();

    String viewerEmail = "inbox-viewer-" + UUID.randomUUID() + "@tracker.local";
    UUID viewerId = authService.register(viewerEmail, PASSWORD, "Viewer").getId();
    membershipService.addMember(workspaceId, viewerId, WorkspaceRole.VIEWER);

    Project project =
        tenantExecutor.runAs(workspaceId, () -> projectService.createProject("INB", "Inbox Proje"));

    return new Tenant(workspaceId, adminId, adminToken, devId, devToken, viewerId, project.getId());
  }

  private String statusEnvelope(
      UUID eventId, Tenant tenant, Task task, String oldStatus, String newStatus, UUID actorId) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("taskId", task.getId().toString());
    payload.put("projectId", tenant.projectId().toString());
    payload.put("oldStatus", oldStatus);
    payload.put("newStatus", newStatus);
    payload.put("actorId", actorId.toString());
    return envelope(eventId, "TASK_STATUS_UPDATED", tenant.workspaceId(), payload);
  }

  private String approvedEnvelope(UUID eventId, Tenant tenant, Task task, UUID actorId) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("taskId", task.getId().toString());
    payload.put("projectId", tenant.projectId().toString());
    payload.put("actorId", actorId.toString());
    return envelope(eventId, "TASK_APPROVED", tenant.workspaceId(), payload);
  }

  private String dueDateEnvelope(UUID eventId, Tenant tenant, Task task, UUID actorId) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("taskId", task.getId().toString());
    payload.put("projectId", tenant.projectId().toString());
    payload.putNull("oldDueDate");
    payload.put("newDueDate", "2099-09-30");
    payload.put("actorId", actorId.toString());
    return envelope(eventId, "TASK_DUE_DATE_CHANGED", tenant.workspaceId(), payload);
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
}
