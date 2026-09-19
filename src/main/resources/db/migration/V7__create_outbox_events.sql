-- PHASE_2_DETAILED_DESIGN.md Bolum 2 — Transactional Outbox Pattern.
--
-- Not: Bu tabloya RLS UYGULANMAZ (task_counters ile ayni gerekce, bkz.
-- V4__create_task_counters.sql). OutboxRelay, workspace'ler ARASI calisan bir
-- altyapi bilesenidir; tenant_isolation policy eklenirse current_setting(...)
-- her zaman NULL donecegi icin relay HICBIR satiri goremez (V6'daki bosdegil-
-- ama-yanlis hatanin bir varyasyonu). Tenant izolasyonu zaten yazma anında
-- saglanir: satirlar sadece zaten RLS'e tabi olan bir servis metodunun AYNI
-- transaction'i icinde yazilir, bu tablo hicbir tenant-facing API'den okunmaz.

CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    topic          VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    aggregate_id   UUID NOT NULL,
    workspace_id   UUID,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at   TIMESTAMPTZ
);

-- Relay'in "islenmemis, en eski once" taramasi icin: kismi index sadece
-- islenmemis satirlari kapsar, tablo buyudukce (islenmisler birikince) bu
-- index kucuk kalir.
CREATE INDEX idx_outbox_events_unprocessed ON outbox_events (created_at)
    WHERE processed_at IS NULL;

-- Gecelik cleanup job'inin "7 gunden eski islenmisleri batch'le sil" taramasi
-- icin.
CREATE INDEX idx_outbox_events_processed_at ON outbox_events (processed_at)
    WHERE processed_at IS NOT NULL;
