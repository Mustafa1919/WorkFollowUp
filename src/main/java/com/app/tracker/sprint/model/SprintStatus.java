package com.app.tracker.sprint.model;

/** DATABASE_SCHEMA.md 2.5 — sprints.status icin izinli degerler (V9'da CHECK ile de zorlanir). */
public final class SprintStatus {

  private SprintStatus() {}

  public static final String PLANNED = "planned";
  public static final String ACTIVE = "active";
  public static final String COMPLETED = "completed";
}
