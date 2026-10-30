-- Mevcut kurulumlarda runtime'in sema ve yalniz-ekleme olay tablosu yetkilerini daralt.
-- Diger tablolarin varsayilan DML yetkileri ve definer fonksiyonlarin EXECUTE yetkileri korunur.
DO $$
BEGIN
    -- CI'nin migration-check job'inda app_runtime rolu yoktur; kosullu geri alinir.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime') THEN
        REVOKE CREATE ON SCHEMA public FROM app_runtime;
        REVOKE UPDATE, DELETE ON TABLE public.task_events FROM app_runtime;
        REVOKE ALL ON TABLE public.flyway_schema_history FROM app_runtime;
    END IF;
END
$$;
