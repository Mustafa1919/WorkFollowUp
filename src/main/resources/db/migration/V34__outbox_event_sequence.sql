-- Outbox sirasi: created_at = NOW() transaction BASLANGICIDIR; ayni tx'te yazilan olaylar ayni
-- damgayi alir ve relay'in ORDER BY created_at taramasi aralarindaki sirayi koruyamaz. seq
-- (INSERT sirasiyla dolan BIGSERIAL) tx ici yazim sirasini belirler.
ALTER TABLE outbox_events ADD COLUMN seq BIGSERIAL;

DROP INDEX IF EXISTS idx_outbox_events_unprocessed;
CREATE INDEX idx_outbox_events_unprocessed ON outbox_events (seq)
    WHERE processed_at IS NULL;

DO $$
BEGIN
    -- CI'nin migration-check job'inda app_runtime rolu yoktur; kosullu verilir.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime') THEN
        GRANT USAGE, SELECT ON SEQUENCE outbox_events_seq_seq TO app_runtime;
    END IF;
END
$$;
