package com.app.tracker.retro;

import java.util.UUID;

/** Dalga 2.4 — retro yanitindaki gorev satirlari icin ortak, JSON-serilestirilebilir kimlik. */
public record RetroTaskRef(UUID taskId, String projectKey, int taskNumber, String title) {}
