package com.app.tracker.task.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TaskStatusLabelTest {

  @Test
  void translatesDisplayNamesOfKnownStatuses() {
    assertEquals("Yapılacak", TaskStatus.label(TaskStatus.TO_DO));
    assertEquals("Devam ediyor", TaskStatus.label(TaskStatus.IN_PROGRESS));
    assertEquals("İncelemede", TaskStatus.label(TaskStatus.REVIEW));
    assertEquals("Tamamlandı", TaskStatus.label(TaskStatus.DONE));
  }

  @Test
  void preservesUnknownValue() {
    assertEquals("Özel durum <&>", TaskStatus.label("Özel durum <&>"));
  }
}
