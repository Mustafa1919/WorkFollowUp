-- Faz 3 / Dilim 3.4: Webhook ingestion (PHASE_3_DETAILED_DESIGN.md Bolum 2).
--
-- Tasarim sapmasi (bilerek): dokuman tek bir `POST /api/v1/webhooks/github` endpoint'i tarif ediyor,
-- ama GitHub JWT tasimaz ve istekte tenant bilgisi yoktur. Hangi workspace'e ait oldugunu (ve HMAC
-- secret'ini) bulmanin tek olceklenebilir yolu endpoint'e integration id'sini koymaktir:
-- `POST /api/v1/webhooks/github/{integrationId}`. Aksi halde gelen imza TUM tenant secret'lariyla
-- denenmek zorunda kalirdi (O(tenant) HMAC + tenant karisikligi riski).

-- Secret BILEREK saklanmaz: HMAC secret'i uygulama anahtarindan turetilir
-- (HMAC(master_key, integration_id || secret_version)). DB sizsa bile imza secret'lari sizmaz;
-- rotasyon secret_version'i artirmaktir.
CREATE TABLE webhook_integrations (
    id              UUID PRIMARY KEY,
    workspace_id    UUID         NOT NULL REFERENCES workspaces(id),
    provider        VARCHAR(20)  NOT NULL,
    secret_version  INTEGER      NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_webhook_integrations_provider CHECK (provider IN ('github'))
);

CREATE INDEX idx_webhook_integrations_workspace_id ON webhook_integrations (workspace_id);

-- Tenant verisi: yonetim endpoint'leri (olustur/listele/rotate/sil) diger tablolarla ayni fail-closed
-- politikaya tabidir (V6: bos GUC NULLIF ile NULL'a cevrilir).
--
-- FORCE ROW LEVEL SECURITY BILEREK YOK (projedeki diger tenant tablolarindan farkli): asagidaki
-- SECURITY DEFINER lookup fonksiyonu, kimlik dogrulamadan ONCE (tenant henuz bilinmiyorken) satiri
-- bulabilmeli. FORCE olsaydi tablo sahibi olan fonksiyon da RLS'e takilir ve hic satir gormezdi.
-- Sahip (migrator) calisma zamaninda hic kullanilmaz; app_runtime sahip DEGILDIR ve RLS'e tabidir.
ALTER TABLE webhook_integrations ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON webhook_integrations
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

-- Ingestion yolunda tenant baglami YOKTUR (GitHub'in JWT'si yok): integration id'si ile TEK satir,
-- TEK amac icin (workspace + secret surumu) cozulur. Fonksiyon baska hicbir kolon/satir dondurmez,
-- yani RLS'i delme yuzeyi id'yi bilen kisinin zaten bilmesi gereken uc alanla sinirlidir.
CREATE FUNCTION resolve_webhook_integration(p_id UUID)
RETURNS TABLE (workspace_id UUID, provider TEXT, secret_version INTEGER)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT wi.workspace_id, wi.provider::text, wi.secret_version
    FROM webhook_integrations wi
    WHERE wi.id = p_id
$$;

REVOKE ALL ON FUNCTION resolve_webhook_integration(UUID) FROM PUBLIC;
DO $$
BEGIN
    -- CI'nin migration-check job'inda app_runtime rolu yoktur; kosullu verilir.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime') THEN
        GRANT EXECUTE ON FUNCTION resolve_webhook_integration(UUID) TO app_runtime;
    END IF;
END;
$$;

-- Webhook kaynakli durum degisikliklerinin aktoru. task_events.actor_id NOT NULL + FK oldugu icin
-- (ve gecmis tabloyu gevsetmek analitik okuyucularini etkiler) sabit, GIRIS YAPILAMAZ bir sistem
-- kullanicisi tohumlanir: password_hash gecerli bir BCrypt degeri degildir (hicbir parola eslesmez),
-- e-posta `.invalid` TLD'sindedir (RFC 2606: teslim edilemez => parola sifirlama ile ele gecirilemez,
-- ayni e-postayla kayit da UNIQUE ile engellenir). Hicbir workspace'e uye DEGILDIR.
INSERT INTO users (id, email, password_hash, full_name, email_verified)
VALUES ('00000000-0000-0000-0000-00000000a001',
        'system-integration@tracker.invalid',
        '!disabled',
        'Integration (system)',
        true)
ON CONFLICT (id) DO NOTHING;
