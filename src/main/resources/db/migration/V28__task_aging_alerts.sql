-- Urunlestirme Dalga 2.1: Takilan is uyarisi (Aging WIP).
--
-- Bir gorevin "yasi" = now - task_analytics.first_in_progress_at (proje henuz Done olmamis, yani
-- task_analytics.done_at IS NULL). Esik projenin p85 cycle time'idir (ProjectMetricsRepository).
-- Bu tablo yalniz "bu gorev icin en son hangi seviyede (1=p85, 2=2xp85) uyari gonderildi" bilgisini
-- tutar -- ayni seviye icin tekrar tekrar bildirim gitmesin diye (AgingWipJob saatlik calisir).
CREATE TABLE task_aging_alerts (
    task_id      UUID        NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    workspace_id UUID        NOT NULL REFERENCES workspaces(id),
    level        SMALLINT    NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (task_id)
);

-- task_watchers (V22) ile AYNI desen: workspace_id denormalize, RLS+FORCE, native SQL repository.
ALTER TABLE task_aging_alerts ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_aging_alerts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON task_aging_alerts
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);
