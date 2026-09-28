-- Dalga 4 -- uygulama ici geri bildirim + urun kullanim/hata telemetrisi.

-- feedback: gercek tenant verisi (workspace uyesi kendi gonderdigini gorebilmeli), diger tenant
-- tablolariyla (V17/tags) AYNI RLS+FORCE deseni. Senkron/dogrudan yazilir (Core API, normal
-- @Transactional servis metodu) -- telemetri olaylarinin aksine kaybi tolere edilemez, kullanicinin
-- bilincli olarak doldurdugu bir form.
CREATE TABLE feedback (
    id           UUID PRIMARY KEY,
    workspace_id UUID        NOT NULL REFERENCES workspaces(id),
    user_id      UUID        NOT NULL REFERENCES users(id),
    message      TEXT        NOT NULL,
    page_path    VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_feedback_workspace_created_at ON feedback (workspace_id, created_at DESC);

ALTER TABLE feedback ENABLE ROW LEVEL SECURITY;
ALTER TABLE feedback FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON feedback
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

-- usage_events / error_events: outbox_events/task_counters ile AYNI gerekce -- altyapi/operatore
-- ait telemetri, hicbir tenant-facing API'den okunmaz, bu yuzden RLS UYGULANMAZ. workspace_id/
-- user_id NULLable: hata olaylari workspace secilmeden veya (ileride) kimliksiz sayfalarda da
-- olusabilir. id, Kafka producer'in urettigi eventId'dir -- consumer'in DefaultErrorHandler yeniden
-- denemesinde ayni olay ikinci kez gelirse ON CONFLICT DO NOTHING dogal dedupe saglar (ayri bir
-- ProcessedEventStore kaydina gerek yok, tek satirlik idempotency).
CREATE TABLE usage_events (
    id           UUID PRIMARY KEY,
    workspace_id UUID,
    user_id      UUID,
    feature      VARCHAR(60) NOT NULL,
    action       VARCHAR(60) NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_usage_events_feature_occurred_at ON usage_events (feature, occurred_at DESC);

CREATE TABLE error_events (
    id           UUID PRIMARY KEY,
    workspace_id UUID,
    user_id      UUID,
    source       VARCHAR(20)  NOT NULL,
    error_type   VARCHAR(200) NOT NULL,
    message      TEXT,
    path         VARCHAR(255),
    occurred_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_error_events_occurred_at ON error_events (occurred_at DESC);
