-- Faz 3 / Dilim 3.3: Velocity / Spillover read model'i + Throughput/Cycle Time sorgu indeksi.

-- Sprint basina tek satir (PHASE_3_DETAILED_DESIGN.md Bolum 1.1, madde 4). task_analytics gibi bir
-- READ MODEL'dir: sprints/workspaces'e FK YOK (yazma modelinden bagimsiz yeniden kurulabilir), RLS'li.
-- Degerler SPRINT_COMPLETED anindaki KESITTEN (completed_at) task_events tarihcesinden hesaplanir;
-- sonradan yapilan tasima/statu/story point degisiklikleri bu satiri degistirmez.
CREATE TABLE sprint_analytics (
    sprint_id         UUID PRIMARY KEY,
    workspace_id      UUID         NOT NULL,
    project_id        UUID         NOT NULL,
    -- Sprint adi ve kesit zamani okuma modelinde de tutulur: analitik API sprints tablosuna
    -- baglanmadan (ileride ayri DB'ye tasinsa da) cevap verebilsin.
    sprint_name       VARCHAR(255) NOT NULL,
    completed_at      TIMESTAMPTZ  NOT NULL,
    -- committed = kapanis kesitinde sprint'te olan TUM gorevler (sprint ortasinda eklenenler dahil);
    -- completed = bunlarin kesitte Done olanlari; spillover = committed - completed.
    committed_tasks   INTEGER      NOT NULL,
    completed_tasks   INTEGER      NOT NULL,
    committed_points  BIGINT       NOT NULL,
    completed_points  BIGINT       NOT NULL,
    -- spillover_points / committed_points. committed_points = 0 ise TANIMSIZ (NULL): puansiz
    -- sprint'te "yuzde 0 devir" demek yaniltici olurdu.
    spillover_rate    NUMERIC(6, 4),
    calculated_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_sprint_analytics_tasks CHECK (completed_tasks >= 0 AND completed_tasks <= committed_tasks),
    CONSTRAINT chk_sprint_analytics_points CHECK (completed_points >= 0 AND completed_points <= committed_points),
    CONSTRAINT chk_sprint_analytics_rate CHECK (spillover_rate IS NULL OR (spillover_rate >= 0 AND spillover_rate <= 1))
);

-- Velocity listesi: projenin son kapanan sprint'leri (completed_at DESC).
CREATE INDEX idx_sprint_analytics_project_completed_at ON sprint_analytics (project_id, completed_at DESC);

ALTER TABLE sprint_analytics ENABLE ROW LEVEL SECURITY;
ALTER TABLE sprint_analytics FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sprint_analytics
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

-- Weekly Throughput ve Cycle Time sorgulari (proje + zaman penceresi). Yalniz tamamlanmis gorevler.
CREATE INDEX idx_task_analytics_project_done_at ON task_analytics (project_id, done_at) WHERE done_at IS NOT NULL;
