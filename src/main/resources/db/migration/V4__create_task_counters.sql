-- PHASE_1_DETAILED_DESIGN.md Bolum 6 / DATABASE_SCHEMA.md 2.6 referans alinarak
-- olusturulmustur. V3 numarasi bilincli olarak atlanmistir: bu adimda tasarlanan
-- app_migrator/app_runtime rol ayrimi Faz 0'da docker-init script'i ile zaten
-- kuruldu (bkz. proje Mimari.md, 2026-09-17 kararlari) — burada tekrarlanmiyor.
--
-- Not: Bu tabloya RLS uygulanmaz; workspace_id kolonu yoktur, erisim yalnizca
-- RLS'e tabi projects tablosu uzerinden (project_id ile) dolayli korunur.

CREATE TABLE task_counters (
    project_id   UUID PRIMARY KEY REFERENCES projects(id),
    last_number  INTEGER NOT NULL DEFAULT 0
);
