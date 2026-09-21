package com.app.tracker.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.integration.consumer.GithubWebhookConsumer;
import com.app.tracker.integration.service.GithubEventProcessor;
import com.app.tracker.integration.service.GithubSignatureVerifier;
import com.app.tracker.integration.service.WebhookIngestionService;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Faz 3 / Dilim 3.4 — webhook ingestion + Integration Worker, gercek HTTP filtre zinciri + gercek
 * Postgres/Kafka/Redis ile. Kafka'ya "hicbir sey yayinlanmadi" iddiasi, ayni anahtarli (entegrasyon
 * id'si) mesajlar Kafka'da SIRALI oldugu icin, sonda gonderilen bir "sentinel" teslimatina kadar
 * topic'i okuyan bagimsiz bir consumer ile dogrulanir (bekleme suresine dayanmaz).
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebhookIngestionIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final String ADMIN_ONLY_PATH = "/api/v1/integrations/webhooks";

  private record Tenant(
      UUID workspaceId, UUID adminUserId, String adminToken, UUID projectId, List<Task> tasks) {}

  private record Integration(UUID id, String secret) {}

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
  @Autowired private GithubWebhookConsumer consumer;

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  // ---- uctan uca ------------------------------------------------------------------------------

  @Test
  void pushPullRequestAndMergeAdvanceTaskAndLateEventsNeverRewindIt() throws Exception {
    Tenant tenant = newTenant("ENG", 1);
    Integration integration = createIntegration(tenant);
    UUID taskId = tenant.tasks().get(0).getId();

    UUID push = deliverOk(integration, "push", pushBody("ENG-1 start work"));
    awaitStatus(tenant, taskId, TaskStatus.IN_PROGRESS);
    UUID opened =
        deliverOk(integration, "pull_request", prBody("opened", false, "ENG-1 add login"));
    awaitStatus(tenant, taskId, TaskStatus.REVIEW);
    UUID merged = deliverOk(integration, "pull_request", prBody("closed", true, "ENG-1 add login"));
    awaitStatus(tenant, taskId, TaskStatus.DONE);

    // Sirasiz/gec gelen bir push: gorev Done'dan geri SARILMAMALI.
    UUID late = deliverOk(integration, "push", pushBody("ENG-1 one more commit"));
    awaitProcessed(integration, late);

    assertEquals(TaskStatus.DONE, taskStatus(tenant, taskId));
    assertEquals(
        3L,
        systemStatusChanges(tenant, taskId),
        "yalniz 3 ileri gecis (push, opened, merged) sistem kullanicisi adina yazilmali");
    assertTrue(
        processed(integration, push)
            && processed(integration, opened)
            && processed(integration, merged));
  }

  @Test
  void redeliveredWebhookIsProcessedExactlyOnce() throws Exception {
    Tenant tenant = newTenant("ENG", 2);
    Integration integration = createIntegration(tenant);
    UUID first = tenant.tasks().get(0).getId();
    UUID second = tenant.tasks().get(1).getId();

    UUID delivery = deliverOk(integration, "push", pushBody("ENG-1 first"));
    awaitStatus(tenant, first, TaskStatus.IN_PROGRESS);

    // Gorevi elle geri al: yinelenen teslimat GERCEKTEN atlanirsa tekrar ilerletemez (monoton
    // kural tek basina bunu ayirt edemez, cunku To Do -> In Progress ileri bir gecistir).
    tenantExecutor.runAs(
        tenant.workspaceId(),
        () -> taskService.updateStatus(first, TaskStatus.TO_DO, tenant.adminUserId()));
    assertEquals(
        202,
        deliverRaw(
            integration.id(),
            sign(integration, pushBody("ENG-1 first")),
            "push",
            delivery.toString(),
            pushBody("ENG-1 first")));

    // Ayni anahtarli (sirali) sentinel: ondan once gelen tekrar da islenmis olmali.
    deliverOk(integration, "push", pushBody("ENG-2 sentinel"));
    awaitStatus(tenant, second, TaskStatus.IN_PROGRESS);

    assertEquals(
        TaskStatus.TO_DO, taskStatus(tenant, first), "yinelenen teslimat ikinci kez islenmemeli");
    assertEquals(1L, processedCount(integration, delivery));
  }

  @Test
  void sameProjectKeyInAnotherTenantIsNeverTouched() throws Exception {
    Tenant a = newTenant("ENG", 1);
    Tenant b = newTenant("ENG", 1);
    Integration integrationA = createIntegration(a);

    UUID delivery = deliverOk(integrationA, "push", pushBody("ENG-1 work"));
    awaitStatus(a, a.tasks().get(0).getId(), TaskStatus.IN_PROGRESS);
    awaitProcessed(integrationA, delivery);

    assertEquals(
        TaskStatus.TO_DO,
        taskStatus(b, b.tasks().get(0).getId()),
        "baska tenant'in ayni anahtarli gorevi degismemeli (RLS)");
  }

  @Test
  void publishedEnvelopeCarriesRawBodyAndTenantFromTheIntegrationRecord() throws Exception {
    Tenant tenant = newTenant("ENG", 1);
    Integration integration = createIntegration(tenant);
    String rawBody = "{ \"ref\" :  \"refs/heads/x\",\n   \"commits\": [ ] }";
    UUID delivery = UUID.randomUUID();

    assertEquals(
        202,
        deliverRaw(
            integration.id(), sign(integration, rawBody), "push", delivery.toString(), rawBody));

    JsonNode envelope = envelopesUntil(integration.id(), delivery).get(0);
    assertEquals(WebhookIngestionService.EVENT_TYPE, envelope.path("eventType").asString());
    assertEquals(tenant.workspaceId().toString(), envelope.path("workspaceId").asString());
    assertEquals(integration.id().toString(), envelope.path("aggregateId").asString());
    assertEquals(eventIdFor(integration, delivery).toString(), envelope.path("eventId").asString());
    assertEquals("push", envelope.path("payload").path("githubEvent").asString());
    assertEquals(
        rawBody,
        envelope.path("payload").path("body").asString(),
        "govde PARSE EDILMEDEN, birebir tasinmali");
  }

  // ---- reddedilen / uc durumlar -----------------------------------------------------------------

  @Test
  void unauthenticatedRequestsPublishNothing() throws Exception {
    Tenant a = newTenant("ENG", 1);
    Tenant b = newTenant("ENG", 1);
    Integration integrationA = createIntegration(a);
    Integration integrationB = createIntegration(b);
    String body = pushBody("ENG-1 x");

    // Imza yok / yanlis / govde degismis / baska tenant'in secret'i / bilinmeyen entegrasyon.
    assertEquals(401, deliverRaw(integrationA.id(), null, "push", uuid(), body));
    assertEquals(
        401, deliverRaw(integrationA.id(), "sha256=" + "0".repeat(64), "push", uuid(), body));
    assertEquals(
        401, deliverRaw(integrationA.id(), sign(integrationA, body + " "), "push", uuid(), body));
    assertEquals(
        401,
        deliverRaw(integrationA.id(), sign(integrationB, body), "push", uuid(), body),
        "B'nin secret'i A'nin entegrasyonunda gecmemeli");
    assertEquals(
        401, deliverRaw(UUID.randomUUID(), sign(integrationA, body), "push", uuid(), body));

    UUID sentinel = deliverOk(integrationA, "push", pushBody("ENG-1 sentinel"));

    List<String> published = deliveriesUntil(integrationA.id(), sentinel);
    assertEquals(List.of(sentinel.toString()), published, "yalniz imzali sentinel yayinlanmali");
  }

  @Test
  void protocolEdgeCases() throws Exception {
    Tenant tenant = newTenant("ENG", 1);
    Integration integration = createIntegration(tenant);
    String body = pushBody("ENG-1 x");
    String signature = sign(integration, body);

    assertEquals(
        200, deliverRaw(integration.id(), sign(integration, "{}"), "ping", uuid(), "{}"), "ping");
    assertEquals(
        400, deliverRaw(integration.id(), signature, null, uuid(), body), "olay basligi yok");
    assertEquals(400, deliverRaw(integration.id(), signature, "push", null, body), "delivery yok");
    assertEquals(
        400, deliverRaw(integration.id(), signature, "push", "not-a-uuid", body), "delivery bozuk");

    UUID ignored = UUID.randomUUID();
    assertEquals(
        202,
        deliverRaw(integration.id(), sign(integration, "{}"), "issues", ignored.toString(), "{}"),
        "desteklenmeyen olay: kabul, yayin yok");

    // 415: yalniz application/json (GitHub'in form-encoded secenegi desteklenmez).
    MockHttpServletRequestBuilder form =
        post("/api/v1/webhooks/github/" + integration.id())
            .contentType(MediaType.TEXT_PLAIN)
            .content(body)
            .header("X-Hub-Signature-256", signature)
            .header("X-GitHub-Event", "push")
            .header("X-GitHub-Delivery", uuid());
    mockMvc.perform(form).andExpect(status().isUnsupportedMediaType());

    // 413: imza kontrolunden ONCE, kimliksiz ucta sinirsiz govde okunmamali.
    String oversized = "x".repeat(600 * 1024);
    assertEquals(413, deliverRaw(integration.id(), null, "push", uuid(), oversized));

    // Gecersiz path degiskeni Spring tarafindan 400'e cevrilir, sunucu hatasi olmaz.
    mockMvc
        .perform(
            post("/api/v1/webhooks/github/not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());

    UUID sentinel = deliverOk(integration, "push", pushBody("ENG-1 sentinel"));
    assertEquals(
        List.of(sentinel.toString()),
        deliveriesUntil(integration.id(), sentinel),
        "ping/desteklenmeyen/reddedilen hicbiri yayinlanmamali");
  }

  @Test
  void rotatingTheSecretOrDeletingTheIntegrationStopsAcceptingOldCredentials() throws Exception {
    Tenant tenant = newTenant("ENG", 1);
    Integration integration = createIntegration(tenant);
    String body = pushBody("ENG-1 x");
    assertEquals(202, deliverRaw(integration.id(), sign(integration, body), "push", uuid(), body));

    JsonNode rotated =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post(ADMIN_ONLY_PATH + "/" + integration.id() + "/rotate-secret")
                        .header("Authorization", "Bearer " + tenant.adminToken())
                        .header("X-Workspace-Id", tenant.workspaceId().toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    Integration afterRotation =
        new Integration(integration.id(), rotated.path("secret").asString());

    assertTrue(!afterRotation.secret().equals(integration.secret()));
    assertEquals(2, rotated.path("integration").path("secretVersion").asInt());
    assertEquals(
        401,
        deliverRaw(integration.id(), sign(integration, body), "push", uuid(), body),
        "eski secret");
    assertEquals(
        202,
        deliverRaw(integration.id(), sign(afterRotation, body), "push", uuid(), body),
        "yeni secret");

    mockMvc
        .perform(
            delete(ADMIN_ONLY_PATH + "/" + integration.id())
                .header("Authorization", "Bearer " + tenant.adminToken())
                .header("X-Workspace-Id", tenant.workspaceId().toString()))
        .andExpect(status().isNoContent());
    assertEquals(
        401,
        deliverRaw(integration.id(), sign(afterRotation, body), "push", uuid(), body),
        "silinmis");
  }

  // ---- yonetim API'si: tenant izolasyonu
  // ----------------------------------------------------------

  @Test
  void managementIsTenantIsolated() throws Exception {
    Tenant a = newTenant("ENG", 1);
    Tenant b = newTenant("ENG", 1);
    Integration integrationA = createIntegration(a);

    mockMvc
        .perform(
            post(ADMIN_ONLY_PATH + "/" + integrationA.id() + "/rotate-secret")
                .header("Authorization", "Bearer " + b.adminToken())
                .header("X-Workspace-Id", b.workspaceId().toString()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            delete(ADMIN_ONLY_PATH + "/" + integrationA.id())
                .header("Authorization", "Bearer " + b.adminToken())
                .header("X-Workspace-Id", b.workspaceId().toString()))
        .andExpect(status().isNotFound());

    assertEquals(0, listIntegrations(b).size());
    List<JsonNode> listedForA = listIntegrations(a);
    assertEquals(1, listedForA.size());
    assertEquals(integrationA.id().toString(), listedForA.get(0).path("id").asString());
    assertTrue(listedForA.get(0).path("secret").isMissingNode(), "listeleme secret icermemeli");
    assertEquals(
        1,
        listedForA.get(0).path("secretVersion").asInt(),
        "B'nin basarisiz rotate denemesi A'nin surumunu degistirmemeli");
  }

  @Test
  void integrationRowsAreInvisibleWithoutTenantContext() throws Exception {
    Tenant tenant = newTenant("ENG", 1);
    createIntegration(tenant);

    assertEquals(0L, integrationRowCount(null), "context yoksa fail-closed olmali");
    assertEquals(0L, integrationRowCount(UUID.randomUUID()));
    assertEquals(1L, integrationRowCount(tenant.workspaceId()));
  }

  @Test
  void webhookEndpointIsPublicButManagementEndpointsAreNot() throws Exception {
    mockMvc.perform(get(ADMIN_ONLY_PATH)).andExpect(status().is4xxClientError());
    // Public: JWT olmadan imza kontrolune kadar ulasir (401 = imza, 403 = gateway degil).
    assertEquals(401, deliverRaw(UUID.randomUUID(), null, "push", uuid(), "{}"));
  }

  // ---- Integration Worker (consumer seviyesi, Kafka zamanlamasindan bagimsiz) ------------------

  @Test
  void consumerRejectsMalformedMessagesAndLeavesNoProcessedMarker() throws Exception {
    Tenant tenant = newTenant("ENG", 1);
    UUID eventId = UUID.randomUUID();

    assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("not json"));
    assertThrows(
        IllegalArgumentException.class,
        () -> consumer.onMessage(envelope(eventId, tenant.workspaceId(), "push", null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> consumer.onMessage(envelope(eventId, tenant.workspaceId(), "push", "{not json")));

    assertEquals(
        0L,
        rawProcessedCount(eventId),
        "gecersiz govde isaret BIRAKMAMALI (yeniden islenebilsin / DLT'de incelenebilsin)");
  }

  @Test
  void consumerIgnoresOtherProvidersAndUnknownProjectsAreHarmless() throws Exception {
    Tenant tenant = newTenant("ENG", 1);

    consumer.onMessage("{\"eventType\":\"TASK_CREATED\"}");
    consumer.onMessage(
        envelope(UUID.randomUUID(), tenant.workspaceId(), "push", pushBody("ENG-1 x"))
            .replace("\"provider\":\"github\"", "\"provider\":\"gitlab\""));
    assertEquals(TaskStatus.TO_DO, taskStatus(tenant, tenant.tasks().get(0).getId()));

    // Var olmayan proje anahtari / gorev numarasi: hata DEGIL, sessiz no-op (isaretlenir).
    UUID unknown = UUID.randomUUID();
    consumer.onMessage(
        envelope(unknown, tenant.workspaceId(), "push", pushBody("NOPE-1 and ENG-99")));

    assertEquals(1L, rawProcessedCount(unknown));
    assertEquals(TaskStatus.TO_DO, taskStatus(tenant, tenant.tasks().get(0).getId()));
  }

  // ---- yardimcilar ------------------------------------------------------------------------------

  private Tenant newTenant(String projectKey, int taskCount) {
    UUID workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "WH WS"));
    String email = "wh-admin-" + UUID.randomUUID() + "@tracker.local";
    UUID adminId = authService.register(email, PASSWORD, "WH Admin").getId();
    membershipService.addMember(workspaceId, adminId, WorkspaceRole.ADMIN);
    String token = authService.login(email, PASSWORD, "127.0.0.1").accessToken();

    Project project =
        tenantExecutor.runAs(workspaceId, () -> projectService.createProject(projectKey, "Proje"));
    List<Task> tasks = new ArrayList<>();
    for (int i = 0; i < taskCount; i++) {
      tasks.add(
          tenantExecutor.runAs(workspaceId, () -> taskService.createTask(project.getId(), "T")));
    }
    return new Tenant(workspaceId, adminId, token, project.getId(), tasks);
  }

  private Integration createIntegration(Tenant tenant) throws Exception {
    String response =
        mockMvc
            .perform(
                post(ADMIN_ONLY_PATH)
                    .header("Authorization", "Bearer " + tenant.adminToken())
                    .header("X-Workspace-Id", tenant.workspaceId().toString()))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode json = objectMapper.readTree(response);
    assertTrue(
        json.path("integration")
            .path("webhookPath")
            .asString()
            .startsWith("/api/v1/webhooks/github/"));
    return new Integration(
        UUID.fromString(json.path("integration").path("id").asString()),
        json.path("secret").asString());
  }

  private List<JsonNode> listIntegrations(Tenant tenant) throws Exception {
    JsonNode array =
        objectMapper.readTree(
            mockMvc
                .perform(
                    get(ADMIN_ONLY_PATH)
                        .header("Authorization", "Bearer " + tenant.adminToken())
                        .header("X-Workspace-Id", tenant.workspaceId().toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    List<JsonNode> result = new ArrayList<>();
    array.forEach(result::add);
    return result;
  }

  private static String uuid() {
    return UUID.randomUUID().toString();
  }

  private static String sign(Integration integration, String body) {
    return GithubSignatureVerifier.sign(
        integration.secret(), body.getBytes(StandardCharsets.UTF_8));
  }

  /** 202 bekler; yeni bir delivery id ile gonderir ve onu dondurur. */
  private UUID deliverOk(Integration integration, String event, String body) throws Exception {
    UUID delivery = UUID.randomUUID();
    assertEquals(
        202,
        deliverRaw(integration.id(), sign(integration, body), event, delivery.toString(), body));
    return delivery;
  }

  private int deliverRaw(
      UUID integrationId, String signature, String event, String delivery, String body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        post("/api/v1/webhooks/github/" + integrationId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.getBytes(StandardCharsets.UTF_8));
    if (signature != null) {
      request.header("X-Hub-Signature-256", signature);
    }
    if (event != null) {
      request.header("X-GitHub-Event", event);
    }
    if (delivery != null) {
      request.header("X-GitHub-Delivery", delivery);
    }
    return mockMvc.perform(request).andReturn().getResponse().getStatus();
  }

  private static String pushBody(String commitMessage) {
    return "{\"ref\":\"refs/heads/main\",\"commits\":[{\"message\":\"" + commitMessage + "\"}]}";
  }

  private static String prBody(String action, boolean merged, String title) {
    return "{\"action\":\""
        + action
        + "\",\"pull_request\":{\"merged\":"
        + merged
        + ",\"draft\":false,\"title\":\""
        + title
        + "\",\"body\":\"\",\"head\":{\"ref\":\"feature\"}}}";
  }

  private String envelope(UUID eventId, UUID workspaceId, String githubEvent, String body) {
    var payload = objectMapper.createObjectNode();
    payload.put("provider", "github");
    payload.put("githubEvent", githubEvent);
    if (body != null) {
      payload.put("body", body);
    }
    var envelope = objectMapper.createObjectNode();
    envelope.put("eventId", eventId.toString());
    envelope.put("eventType", WebhookIngestionService.EVENT_TYPE);
    envelope.put("schemaVersion", 1);
    envelope.put("workspaceId", workspaceId.toString());
    envelope.set("payload", payload);
    return objectMapper.writeValueAsString(envelope);
  }

  /** Ingestion'daki turetme formulunun test tarafi kopyasi: sozlesmenin kendisi test edilir. */
  private static UUID eventIdFor(Integration integration, UUID delivery) {
    return UUID.nameUUIDFromBytes(
        (integration.id() + ":" + delivery).getBytes(StandardCharsets.UTF_8));
  }

  private String taskStatus(Tenant tenant, UUID taskId) {
    return inTenant(
        tenant.workspaceId(),
        () ->
            (String)
                entityManager
                    .createNativeQuery("SELECT status FROM tasks WHERE id = ?1")
                    .setParameter(1, taskId)
                    .getSingleResult());
  }

  private long systemStatusChanges(Tenant tenant, UUID taskId) {
    return inTenant(
        tenant.workspaceId(),
        () ->
            ((Number)
                    entityManager
                        .createNativeQuery(
                            "SELECT COUNT(*) FROM task_events WHERE task_id = ?1 "
                                + "AND actor_id = ?2 AND event_type = 'status_changed'")
                        .setParameter(1, taskId)
                        .setParameter(2, IntegrationActor.SYSTEM_USER_ID)
                        .getSingleResult())
                .longValue());
  }

  private void awaitStatus(Tenant tenant, UUID taskId, String expected) throws Exception {
    awaitCondition(
        () -> expected.equals(taskStatus(tenant, taskId)),
        "gorev " + expected + " durumuna gecmedi (webhook -> Kafka -> worker zinciri)");
  }

  private void awaitProcessed(Integration integration, UUID delivery) throws Exception {
    awaitCondition(() -> processed(integration, delivery), "teslimat worker tarafindan islenmedi");
  }

  private static void awaitCondition(Supplier<Boolean> condition, String failure) throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      if (condition.get()) {
        return;
      }
      Thread.sleep(200);
    }
    fail("30 saniye icinde: " + failure);
  }

  private boolean processed(Integration integration, UUID delivery) {
    return processedCount(integration, delivery) == 1L;
  }

  private long processedCount(Integration integration, UUID delivery) {
    return rawProcessedCount(eventIdFor(integration, delivery));
  }

  private long rawProcessedCount(UUID eventId) {
    return ((Number)
            transactionTemplate.execute(
                status ->
                    entityManager
                        .createNativeQuery(
                            "SELECT COUNT(*) FROM processed_events WHERE consumer = ?1 AND event_id = ?2")
                        .setParameter(1, GithubEventProcessor.CONSUMER)
                        .setParameter(2, eventId)
                        .getSingleResult()))
        .longValue();
  }

  private long integrationRowCount(UUID tenant) {
    return inTenant(
        tenant,
        () ->
            ((Number)
                    entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM webhook_integrations")
                        .getSingleResult())
                .longValue());
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

  private List<String> deliveriesUntil(UUID integrationId, UUID sentinel) {
    return envelopesUntil(integrationId, sentinel).stream()
        .map(envelope -> envelope.path("payload").path("deliveryId").asString())
        .toList();
  }

  /**
   * {@code webhooks.incoming}'i bagimsiz bir consumer ile bastan okur ve bu entegrasyonun (mesaj
   * anahtari) {@code sentinel} teslimatina KADAR olan tum zarflarini dondurur (sentinel dahil).
   */
  private List<JsonNode> envelopesUntil(UUID integrationId, UUID sentinel) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

    List<JsonNode> seen = new ArrayList<>();
    long deadline = System.currentTimeMillis() + 30_000;
    try (KafkaConsumer<String, String> probe = new KafkaConsumer<>(props)) {
      probe.subscribe(List.of(WebhookIngestionService.TOPIC));
      while (System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, String> record : probe.poll(Duration.ofMillis(500))) {
          if (!integrationId.toString().equals(record.key())) {
            continue;
          }
          JsonNode envelope = objectMapper.readTree(record.value());
          seen.add(envelope);
          if (sentinel.toString().equals(envelope.path("payload").path("deliveryId").asString())) {
            return seen;
          }
        }
      }
    }
    fail("sentinel teslimat topic'te gorunmedi; gorulenler: " + seen.size());
    return seen;
  }
}
