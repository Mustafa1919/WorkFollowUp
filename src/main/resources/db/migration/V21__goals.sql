-- Faz 4 oncesi ara adim: Raporlama sayfasi (RAKIP_ANALIZI.md Bolum 3, Hedefler.md'deki "yillik/
-- ceyreklik rapor" maddesi). Raporun GECMISE DONUK tarafi tamamen mevcut read model'lerden
-- (task_analytics, sprint_analytics) hesaplanir ve YENI TABLO GEREKTIRMEZ; bu migration yalnizca
-- ILERIYE DONUK tarafi, yani donem hedeflerini tanimlar.
--
-- Bilerek DAR kapsam (V19 Subtask/Dependency karariyla ayni desen): tek seviye hedef. OKR'deki
-- Objective -> Key Result hiyerarsisi, agirlik, check-in gecmisi YOK. Hedef = "bu donemde su
-- metrikte su sayiya ulas"; ilerleme metrik tipine gore ya read model'den OTOMATIK hesaplanir
-- (COMPLETED_TASKS / COMPLETED_POINTS) ya da elle girilir (CUSTOM).
CREATE TABLE goals (
    id             UUID PRIMARY KEY,
    workspace_id   UUID         NOT NULL REFERENCES workspaces(id),
    -- NULL = workspace geneli hedef; dolu ise yalniz o projenin tamamlananlari sayilir.
    -- ON DELETE CASCADE: proje silinince hedefi de anlamini yitirir (projects'te soft delete yok).
    project_id     UUID         REFERENCES projects(id) ON DELETE CASCADE,
    period_year    SMALLINT     NOT NULL,
    -- NULL = YILLIK hedef, 1-4 = ceyreklik. Ceyrek sinirlari is saat diliminde (app.business-time-
    -- zone) hesaplanir; burada saklanan yalnizca takvim etiketidir (bkz. ReportPeriod).
    period_quarter SMALLINT,
    title          VARCHAR(200) NOT NULL,
    -- COMPLETED_TASKS | COMPLETED_POINTS | CUSTOM (enum DEGIL, VARCHAR: yeni metrik tipi eklemek
    -- migration gerektirmesin — projedeki diger tip kolonlariyla ayni tercih).
    metric_type    VARCHAR(30)  NOT NULL,
    target_value   BIGINT       NOT NULL,
    -- Yalniz CUSTOM icin anlamli: otomatik hesaplanamayan hedefin elle girilen ilerlemesi.
    manual_value   BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_goals_quarter CHECK (period_quarter IS NULL OR period_quarter BETWEEN 1 AND 4),
    CONSTRAINT chk_goals_year CHECK (period_year BETWEEN 2000 AND 2999),
    CONSTRAINT chk_goals_target CHECK (target_value > 0),
    CONSTRAINT chk_goals_metric
        CHECK (metric_type IN ('COMPLETED_TASKS', 'COMPLETED_POINTS', 'CUSTOM')),
    -- manual_value yalnizca CUSTOM hedefte tasinabilir; otomatik metrikte elle girilen bir deger
    -- sessizce yok sayilir ve "neden ilerleme farkli" sorusunu dogururdu.
    CONSTRAINT chk_goals_manual_only_custom
        CHECK (manual_value IS NULL OR metric_type = 'CUSTOM'),
    CONSTRAINT chk_goals_manual_non_negative CHECK (manual_value IS NULL OR manual_value >= 0)
);

-- Rapor sorgusu hedefleri HER ZAMAN donem uzerinden ceker.
CREATE INDEX idx_goals_workspace_period ON goals (workspace_id, period_year, period_quarter);

-- Diger tenant tablolariyla AYNI fail-closed politika (V6: bos GUC NULLIF ile NULL'a cevrilir).
ALTER TABLE goals ENABLE ROW LEVEL SECURITY;
ALTER TABLE goals FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON goals
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

-- Not: app_runtime icin ayrica GRANT gerekmiyor — docker/postgres-init/init-roles-and-dbs.sh'deki
-- ALTER DEFAULT PRIVILEGES yeni tablolara yetkiyi otomatik verir (V9/V14/V17 ile ayni desen).
