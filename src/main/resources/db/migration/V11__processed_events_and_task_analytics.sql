-- Faz 3 / Dilim 3.2: ortak Processed Event Store + Cycle Time read model'i.

-- Idempotent consumer altyapisi (PHASE_3 "Mimari Ipucu" + PHASE_2 DLT Replay ayni altyapiyi
-- paylasir). (consumer, event_id) cifti: ayni olay FARKLI consumer'lar tarafindan bagimsiz olarak
-- bir kez islenebilir. Outbox gibi bilerek RLS'siz: consumer'lar workspace'ler arasi calisan altyapi
-- bilesenleridir ve tablo is verisi tasimaz (bkz. Mimari.md, outbox_events notu).
CREATE TABLE processed_events (
    consumer      VARCHAR(100) NOT NULL,
    event_id      UUID         NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer, event_id)
);

-- Retention temizligi (ProcessedEventCleanupJob) processed_at uzerinden siler.
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);

-- Cycle Time = T_done - T_in_progress (PHASE_3 Bolum 1). Gorev basina tek satir.
-- Bilerek FK YOK (tasks/workspaces'e): bu bir READ MODEL'dir (CQRS), yazma modelinden bagimsiz
-- yeniden kurulabilmeli ve ileride ayri bir veritabanina tasinabilmelidir.
CREATE TABLE task_analytics (
    task_id               UUID PRIMARY KEY,
    workspace_id          UUID        NOT NULL,
    project_id            UUID        NOT NULL,
    first_in_progress_at  TIMESTAMPTZ,
    done_at               TIMESTAMPTZ,
    cycle_time_seconds    BIGINT,
    -- Son islenen olayin zamani: eski tarihli (stale) bir olay (DLT replay, sirasi bozulmus
    -- teslim) mevcut durumu geri saramaz.
    last_event_at         TIMESTAMPTZ,
    CONSTRAINT chk_task_analytics_cycle_time CHECK (cycle_time_seconds IS NULL OR cycle_time_seconds >= 0)
);

-- Tenant verisi: tasks/projects/sprints ile ayni fail-closed politika (V6: bos GUC'u NULLIF ile
-- NULL'a cevirir, aksi halde ''::uuid sert hata verirdi).
ALTER TABLE task_analytics ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_analytics FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON task_analytics
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);
