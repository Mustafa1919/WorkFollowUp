package com.app.tracker.analytics.model;

/**
 * Bir sprint'in kapanis kesitindeki (completedAt) gorev/puan toplami. task_events tarihcesinden
 * yeniden kurulur, tasks'in anlik degerinden DEGIL (bkz. {@code SprintSnapshotRepository}).
 */
public record SprintSnapshot(
    int committedTasks, int completedTasks, long committedPoints, long completedPoints) {}
