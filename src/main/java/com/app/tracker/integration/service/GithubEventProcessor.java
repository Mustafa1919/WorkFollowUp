package com.app.tracker.integration.service;

import com.app.tracker.core.idempotency.ProcessedEventStore;
import com.app.tracker.integration.IntegrationActor;
import com.app.tracker.integration.service.GithubEventInterpreter.StatusIntent;
import com.app.tracker.integration.service.GithubEventInterpreter.TaskReference;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.repository.TaskRepository;
import com.app.tracker.task.service.TaskService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration Worker'in is mantigi (PHASE_3 Bolum 2, "Isleme"): webhook olayindaki gorev
 * referanslarini bulur ve gorevleri {@link TaskService#updateStatus} uzerinden ILERLETIR — boylece
 * task_events, outbox, WebSocket yayini ve Cycle Time analitigi elle degisiklikle AYNI zincirden
 * akar.
 *
 * <p>Durum degisikligi YALNIZ ILERI yonludur (To Do &lt; In Progress &lt; Review &lt; Done). Bu bir
 * guvenlik agi degil, tasarimin tasiyici kolonudur: webhook teslimati at-least-once ve SIRASIZ
 * olabilir (ornegin gec gelen bir push, cok once merge edilmis PR'in "Done" durumunu "In Progress"e
 * geri sarmamali). Ileri-yonlu-monoton kural ayni zamanda tekrar teslimi ve DLT replay'i zararsiz
 * kilar. Geri alma (reopen) bilerek elle yapilir.
 *
 * <p>Tek transaction: {@code markProcessed} ile is yazimi birlikte commit/rollback olur (bkz.
 * ProcessedEventStore). Tenant baglami cagiran tarafindan ({@code TenantExecutor}) olayin
 * workspaceId'sinden kurulur; proje/gorev aramalari RLS'e tabidir, yani baska tenant'in ayni
 * anahtarli projesi HICBIR ZAMAN eslesemez.
 */
@Service
@Profile("!migrate")
public class GithubEventProcessor {

  public static final String CONSUMER = "integration-github";

  private static final Logger log = LoggerFactory.getLogger(GithubEventProcessor.class);

  private static final Map<String, Integer> RANK =
      Map.of(
          TaskStatus.TO_DO, 0,
          TaskStatus.IN_PROGRESS, 1,
          TaskStatus.REVIEW, 2,
          TaskStatus.DONE, 3);

  private final ObjectMapper objectMapper;
  private final ProcessedEventStore processedEventStore;
  private final ProjectRepository projectRepository;
  private final TaskRepository taskRepository;
  private final TaskService taskService;

  public GithubEventProcessor(
      ObjectMapper objectMapper,
      ProcessedEventStore processedEventStore,
      ProjectRepository projectRepository,
      TaskRepository taskRepository,
      TaskService taskService) {
    this.objectMapper = objectMapper;
    this.processedEventStore = processedEventStore;
    this.projectRepository = projectRepository;
    this.taskRepository = taskRepository;
    this.taskService = taskService;
  }

  /**
   * @return durumu gercekten ilerletilen gorev sayisi (yinelenen teslimatta ve niyet yoksa 0)
   * @throws IllegalArgumentException govde gecerli JSON degilse (yeniden denemek anlamsiz: DLT)
   */
  @Transactional
  public int process(UUID eventId, String githubEvent, String bodyJson) {
    // Once ayristir, sonra isaretle: bozuk govde isaret birakmadan patlar (zaten geri alinir).
    JsonNode payload = parse(bodyJson);
    Optional<StatusIntent> intent = GithubEventInterpreter.interpret(githubEvent, payload);

    if (!processedEventStore.markProcessed(CONSUMER, eventId)) {
      return 0;
    }
    if (intent.isEmpty()) {
      return 0;
    }

    StatusIntent statusIntent = intent.get();
    int advanced = 0;
    for (TaskReference reference : statusIntent.references()) {
      if (advance(reference, statusIntent.targetStatus())) {
        advanced++;
      }
    }
    return advanced;
  }

  private boolean advance(TaskReference reference, String targetStatus) {
    Optional<Project> project = projectRepository.findByKey(reference.projectKey());
    if (project.isEmpty()) {
      return false;
    }
    Optional<Task> task =
        taskRepository.findByProjectIdAndTaskNumber(project.get().getId(), reference.taskNumber());
    if (task.isEmpty() || !isForward(task.get().getStatus(), targetStatus)) {
      return false;
    }
    taskService.updateStatus(task.get().getId(), targetStatus, IntegrationActor.SYSTEM_USER_ID);
    log.info(
        "Webhook gorevi ilerletti: {}-{} {} -> {}",
        reference.projectKey(),
        reference.taskNumber(),
        task.get().getStatus(),
        targetStatus);
    return true;
  }

  /**
   * Bilinmeyen (ozel) bir durumdaki gorevlere DOKUNULMAZ: sirasi bilinmeyen durum ilerletilemez.
   */
  static boolean isForward(String currentStatus, String targetStatus) {
    // Map.of(...).get(null) NPE firlatir; null durum "bilinmeyen" sayilir.
    if (currentStatus == null || targetStatus == null) {
      return false;
    }
    Integer current = RANK.get(currentStatus);
    Integer target = RANK.get(targetStatus);
    return current != null && target != null && target > current;
  }

  private JsonNode parse(String bodyJson) {
    try {
      return objectMapper.readTree(bodyJson);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("Gecersiz webhook govdesi", e);
    }
  }
}
