package com.app.tracker.analytics.model;

import com.app.tracker.task.model.TaskStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 1 — Cycle Time: {@code CT = T_done - T_in_progress}. RLS'e tabi
 * bir READ MODEL'dir (V11); yazma modeli ({@code tasks}) ile FK bagi yoktur.
 *
 * <p>Tanimlar (dokumanin acik biraktigi noktalar burada sabitlenir):
 *
 * <ul>
 *   <li>T_in_progress = gorevin ILK kez "In Progress"e gectigi an (geri donup tekrar girmek
 *       baslangici sifirlamaz; rework suresi de cycle time'a dahildir).
 *   <li>T_done = SON "Done" gecisi. Gorev "Done"dan cikarsa (yeniden acilma) tamamlanmis sayilmaz:
 *       done_at ve cycle time temizlenir; tekrar Done olunca yeniden hesaplanir.
 *   <li>"In Progress"e hic girmeden dogrudan Done olan gorevde cycle time TANIMSIZDIR (null);
 *       yalnizca done_at kaydedilir (Throughput icin gerekli).
 *   <li>Eski tarihli (stale) olay durumu geri saramaz: DLT replay'i ve sirasi bozulmus teslimatlar
 *       icin ikinci savunma hatti (birincisi ProcessedEventStore).
 * </ul>
 */
@Entity
@Table(name = "task_analytics")
@Getter
@NoArgsConstructor
public class TaskAnalytics {

  @Id private UUID taskId;

  private UUID workspaceId;

  private UUID projectId;

  private Instant firstInProgressAt;

  private Instant doneAt;

  private Long cycleTimeSeconds;

  private Instant lastEventAt;

  public static TaskAnalytics create(UUID taskId, UUID workspaceId, UUID projectId) {
    TaskAnalytics analytics = new TaskAnalytics();
    analytics.taskId = taskId;
    analytics.workspaceId = workspaceId;
    analytics.projectId = projectId;
    return analytics;
  }

  /**
   * @return {@code false}: olay mevcut durumdan ESKI, hicbir sey degismedi (cagiran kaydetmemeli).
   */
  public boolean applyStatusChange(String newStatus, Instant at) {
    if (lastEventAt != null && at.isBefore(lastEventAt)) {
      return false;
    }
    if (TaskStatus.IN_PROGRESS.equals(newStatus) && firstInProgressAt == null) {
      firstInProgressAt = at;
    }
    doneAt = TaskStatus.DONE.equals(newStatus) ? at : null;
    cycleTimeSeconds =
        (doneAt != null && firstInProgressAt != null)
            ? Math.max(0L, Duration.between(firstInProgressAt, doneAt).getSeconds())
            : null;
    lastEventAt = at;
    return true;
  }
}
