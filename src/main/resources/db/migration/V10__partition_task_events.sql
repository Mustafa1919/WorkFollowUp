-- Faz 3 / Dilim 3.1: task_events -> aylik RANGE partitioned tablo (PHASE_3_DETAILED_DESIGN.md 1.0).
--
-- Uc gizli tuzak (dokumanda yok), bu migration bilerek her birini ele alir:
--
-- 1) FORCE ROW LEVEL SECURITY tablo SAHIBINE de uygulanir. Migration kullanicisi superuser
--    degilse (prod'da olmamali) eski tablodan "INSERT ... SELECT" RLS'e takilip SESSIZCE 0 satir
--    tasir. Bu yuzden kopyadan once eski tabloda RLS kapatilir.
-- 2) Parent'a konan RLS politikasi, partition'a DOGRUDAN yapilan sorguya UYGULANMAZ. Default
--    privileges (docker init / AbstractIntegrationTest) app_runtime'a her yeni tabloda DML verir;
--    partition'lardan bu yetki geri alinir, boylece uygulama YALNIZCA parent uzerinden erisebilir.
-- 3) Partition'i otomatik acan kod app_runtime olarak DDL calistirmamali (tablo sahibi
--    app_runtime olurdu). Bu yuzden acma isi SECURITY DEFINER bir fonksiyondadir; sahibi migration
--    kullanicisidir. Uygulama yalnizca fonksiyonu cagirir.

ALTER TABLE task_events DISABLE ROW LEVEL SECURITY;
ALTER TABLE task_events RENAME TO task_events_old;
ALTER TABLE task_events_old RENAME CONSTRAINT task_events_pkey TO task_events_old_pkey;
ALTER INDEX idx_task_events_task_id RENAME TO idx_task_events_old_task_id;
ALTER INDEX idx_task_events_created_at RENAME TO idx_task_events_old_created_at;

-- Partitioned tabloda unique/PK constraint partition key'i icermek zorundadir: PK (id, created_at).
-- task_events yalnizca task_id + created_at uzerinden sorgulandigi icin pratik bir kayip yoktur.
CREATE TABLE task_events (
    id          UUID NOT NULL,
    task_id     UUID NOT NULL REFERENCES tasks(id),
    actor_id    UUID NOT NULL REFERENCES users(id),
    event_type  VARCHAR(50) NOT NULL,
    old_value   JSONB,
    new_value   JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Eski iki tekil indeksin yerini kapsar: (task_id) onek olarak, created_at ise partition pruning.
CREATE INDEX idx_task_events_task_id_created_at ON task_events (task_id, created_at);

-- Partition sinirlari UTC ay basidir (oturum saat diliminden bagimsiz).
CREATE FUNCTION ensure_task_events_partitions(from_month DATE, to_month DATE)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    m        DATE := date_trunc('month', from_month)::date;
    last_m   DATE := date_trunc('month', to_month)::date;
    part     TEXT;
    created  INTEGER := 0;
BEGIN
    -- Birden fazla pod ayni anda cagirabilir; kilit islem sonunda otomatik birakilir.
    PERFORM pg_advisory_xact_lock(hashtext('ensure_task_events_partitions'));
    WHILE m <= last_m LOOP
        part := format('task_events_%s', to_char(m, 'YYYY_MM'));
        IF to_regclass(format('public.%I', part)) IS NULL THEN
            -- CREATE TABLE ... PARTITION OF parent'ta uzun kilit alir; bagimsiz tablo + ATTACH
            -- yalnizca SHARE UPDATE EXCLUSIVE alir (yazmalari bloklamaz).
            EXECUTE format('CREATE TABLE public.%I (LIKE public.task_events INCLUDING DEFAULTS)', part);
            EXECUTE format(
                'ALTER TABLE public.task_events ATTACH PARTITION public.%I '
                'FOR VALUES FROM (%L) TO (%L)',
                part,
                to_char(m, 'YYYY-MM-DD') || ' 00:00:00+00',
                to_char((m + INTERVAL '1 month')::date, 'YYYY-MM-DD') || ' 00:00:00+00');
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime') THEN
                EXECUTE format('REVOKE ALL ON TABLE public.%I FROM app_runtime', part);
            END IF;
            created := created + 1;
        END IF;
        m := (m + INTERVAL '1 month')::date;
    END LOOP;
    RETURN created;
END;
$$;

REVOKE ALL ON FUNCTION ensure_task_events_partitions(DATE, DATE) FROM PUBLIC;
DO $$
BEGIN
    -- CI'nin migration-check job'inda app_runtime rolu yoktur; kosullu verilir.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime') THEN
        GRANT EXECUTE ON FUNCTION ensure_task_events_partitions(DATE, DATE) TO app_runtime;
    END IF;
END;
$$;

-- Mevcut verinin tum aylarini (en eskiden bugunden 3 ay sonrasina) kapsayan partition'lar.
SELECT ensure_task_events_partitions(
    (SELECT (COALESCE(MIN(created_at), NOW()) AT TIME ZONE 'UTC')::date FROM task_events_old),
    ((NOW() AT TIME ZONE 'UTC') + INTERVAL '3 months')::date);

INSERT INTO task_events (id, task_id, actor_id, event_type, old_value, new_value, created_at)
SELECT id, task_id, actor_id, event_type, old_value, new_value, created_at FROM task_events_old;

DROP TABLE task_events_old;

-- RLS, kopyadan SONRA acilir (bkz. tuzak 1). task_events'te workspace_id kolonu yoktur; izolasyon
-- RLS'e tabi tasks tablosuna EXISTS ile dolayli kurulur (V2 ile ayni politika).
ALTER TABLE task_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON task_events
    USING (EXISTS (SELECT 1 FROM tasks t WHERE t.id = task_events.task_id))
    WITH CHECK (EXISTS (SELECT 1 FROM tasks t WHERE t.id = task_events.task_id));
