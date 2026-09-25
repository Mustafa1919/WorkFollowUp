package com.app.tracker.standup;

import java.util.UUID;

/** Dalga 2.3 — standup ozetindeki her gorev satiri icin ortak, JSON-serilestirilebilir kimlik. */
public record StandupTaskRef(UUID taskId, String projectKey, int taskNumber, String title) {}
