package com.app.tracker.task.service;

import com.app.tracker.task.repository.TaskEventRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 1.0 — task_events partition'larini onceden hazir tutar ("ayin
 * 1'inde INSERT patlamasi" riskini onler: partition yoksa INSERT hata verir). Gercek DDL, {@code
 * ensure_task_events_partitions} SECURITY DEFINER fonksiyonundadir (V10); bu sinif yalnizca
 * tetikler, fonksiyon idempotent ve advisory lock'lu oldugu icin coklu pod'da guvenlidir.
 *
 * <p>Baslangicta da calisir: uygulama uzun sure kapali kaldiysa ilk yazmadan once acik kalan aylari
 * kapatir. Bu sinifin kendi metotlarina {@code @Transactional} konmaz (self-invocation tuzagi);
 * transaction siniri {@code TaskEventRepository.ensurePartitions}'tadir.
 */
@Component
@Profile("!migrate")
public class TaskEventPartitionJob {

  private static final Logger log = LoggerFactory.getLogger(TaskEventPartitionJob.class);

  private final TaskEventRepository taskEventRepository;
  private final int monthsAhead;

  public TaskEventPartitionJob(
      TaskEventRepository taskEventRepository,
      @Value("${app.task-events.partitions-ahead-months:3}") int monthsAhead) {
    this.taskEventRepository = taskEventRepository;
    this.monthsAhead = monthsAhead;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Scheduled(cron = "0 30 2 * * *")
  public void ensurePartitions() {
    // Sinirlar UTC ay basidir (V10); "bugun" de UTC'ye gore hesaplanir.
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    int created = taskEventRepository.ensurePartitions(today, today.plusMonths(monthsAhead));
    if (created > 0) {
      log.info("task_events: {} yeni aylik partition olusturuldu.", created);
    }
  }
}
