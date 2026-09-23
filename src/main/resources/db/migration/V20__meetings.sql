-- Faz 4 oncesi ara adim: Toplanti planlama (RRULE-tarzi tekrar + takvim, RAKIP_ANALIZI.md sonrasi
-- Hedefler.md karari — planlama-only, gercek gorusme YOK, harici link ile devredilir).
--
-- Recurrence kasitli olarak RFC5545 RRULE string DEGIL, yapilandirilmis alanlar (frequency/
-- interval_count/by_weekday/until_date/occurrence_count) olarak tutulur — yeni bir kutuphane
-- bagimliligi gerektirmeden java.time ile hesaplanabilecek pratik bir alt kume (MeetingOccurrence-
-- Calculator). Occurrence'lar DB'de satir olarak SAKLANMAZ, kurallardan anlik hesaplanir (V19
-- dependency'deki "bilerek dar kapsam" deseniyle tutarli): v1'de tek bir tekrari atlama/tasima/iptal
-- YOK, duzenleme/silme HER ZAMAN seri genelini etkiler.
CREATE TABLE meetings (
    id                       UUID PRIMARY KEY,
    workspace_id             UUID         NOT NULL REFERENCES workspaces(id),
    title                    VARCHAR(200) NOT NULL,
    description              VARCHAR(1000),
    -- Harici toplanti linki (Zoom/Google Meet/Teams); kullanici disaridan olusturup yapistirir,
    -- API entegrasyonu ile OTOMATIK URETILMEZ (bkz. Hedefler.md karari).
    meeting_url              VARCHAR(500),
    start_date               DATE         NOT NULL,
    start_time               TIME         NOT NULL,
    duration_minutes         INTEGER      NOT NULL,
    frequency                VARCHAR(10)  NOT NULL,
    interval_count           INTEGER      NOT NULL DEFAULT 1,
    -- 'MON,WED,FRI' formatinda CSV; yalniz frequency='WEEKLY' icin anlamlidir (servis katmaninda
    -- dogrulanir). custom_fields/payload'daki ayni gerekceyle (Hibernate 7 + Jackson koleksiyon
    -- mapping belirsizligi) entity'ye Set<DayOfWeek> olarak DEGIL duz String olarak maplenir.
    by_weekday               VARCHAR(30),
    until_date               DATE,
    occurrence_count         INTEGER,
    -- NULL = hatirlatma yok; doluysa MeetingReminderJob bu toplantinin occurrence'lari icin
    -- baslangictan N dakika once Inbox + Slack'e hatirlatma gonderir.
    reminder_minutes_before  INTEGER,
    created_by               UUID         NOT NULL REFERENCES users(id),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_meetings_frequency CHECK (frequency IN ('ONCE', 'DAILY', 'WEEKLY', 'MONTHLY')),
    -- until_date ve occurrence_count BIRLIKTE kullanilamaz (ikisi de "serinin ne zaman bitecegi"
    -- sorusunu cevaplar, ayni aliskanlik Sprint'teki tek "status" alaniyla tutarli basitlik).
    CONSTRAINT chk_meetings_until_xor_count
        CHECK (until_date IS NULL OR occurrence_count IS NULL)
);

-- MeetingReminderJob her dakika calisir; hatirlatmasi olan toplantilari hizlica filtreler.
CREATE INDEX idx_meetings_workspace_reminder
    ON meetings (workspace_id) WHERE reminder_minutes_before IS NOT NULL;

-- Diger tenant tablolariyla AYNI fail-closed politika (V6: bos GUC NULLIF ile NULL'a cevrilir).
ALTER TABLE meetings ENABLE ROW LEVEL SECURITY;
ALTER TABLE meetings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meetings
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

-- Not: app_runtime icin ayrica GRANT gerekmiyor (init-roles-and-dbs.sh'deki
-- ALTER DEFAULT PRIVILEGES, V9/V14/V17 ile ayni desen).
