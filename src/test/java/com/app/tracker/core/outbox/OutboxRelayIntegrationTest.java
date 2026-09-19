package com.app.tracker.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PHASE_2_DETAILED_DESIGN.md Bolum 2 — Outbox Pattern'in uctan uca kanit: {@code
 * TaskService.createTask} sadece {@code tasks} tablosuna yazar, olayi kendisi Kafka'ya BASMAZ;
 * {@code OutboxRelay}'in {@code @Scheduled} tick'i {@code outbox_events}'teki satiri okuyup gercek
 * Kafka broker'ina (Testcontainers) gonderdigini dogrular.
 */
@SpringBootTest
class OutboxRelayIntegrationTest extends AbstractIntegrationTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private ObjectMapper objectMapper;

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Test
  void taskCreationIsRelayedToKafkaViaOutbox() {
    UUID workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Outbox Ws"));
    Project project =
        tenantExecutor.runAs(workspaceId, () -> projectService.createProject("OBX", "Outbox"));
    Task task =
        tenantExecutor.runAs(
            workspaceId, () -> taskService.createTask(project.getId(), "Relayed task"));

    ConsumerRecord<String, String> record = pollForRecord("task.events", task.getId().toString());
    JsonNode envelope = objectMapper.readTree(record.value());

    assertEquals("TASK_CREATED", envelope.get("eventType").asText());
    assertEquals(task.getId().toString(), envelope.get("aggregateId").asText());
    assertEquals(workspaceId.toString(), envelope.get("workspaceId").asText());
    assertEquals(task.getId().toString(), envelope.path("payload").get("taskId").asText());
    assertNotNull(envelope.get("eventId"));
  }

  private ConsumerRecord<String, String> pollForRecord(String topic, String expectedKey) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(java.util.List.of(topic));
      long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
      while (System.currentTimeMillis() < deadline) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> record : records) {
          if (expectedKey.equals(record.key())) {
            return record;
          }
        }
      }
    }
    return fail("15 saniye icinde outbox relay'den beklenen mesaj gelmedi: key=" + expectedKey);
  }
}
