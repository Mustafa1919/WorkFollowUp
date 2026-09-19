package com.app.tracker.task.model;

/**
 * Kanban durum makinesi (PHASE_1_DETAILED_DESIGN Bolum 4). {@code TaskService} dogrulamasi ve
 * analitik worker'lar (Cycle Time) ayni sabitleri paylasir; boylece "In Progress" yazimindaki bir
 * typo sessizce metrik uretmemeye yol acamaz.
 */
public final class TaskStatus {

  private TaskStatus() {}

  public static final String TO_DO = "To Do";
  public static final String IN_PROGRESS = "In Progress";
  public static final String REVIEW = "Review";
  public static final String DONE = "Done";
}
