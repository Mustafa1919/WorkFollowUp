-- Faz 3 / Dilim 3.5: Outbound notification (PHASE_3_DETAILED_DESIGN.md Bolum 3) — Slack.
--
-- Workspace basina TEK Slack incoming-webhook adresi (PRIMARY KEY = workspace_id). Adres bir
-- KIMLIK BILGISIDIR (URL'yi bilen herkes kanala mesaj yazabilir) ve HMAC secret'inin aksine
-- turetilemez: disaridan verilen bir deger oldugu icin saklanmak zorundadir. Bu yuzden DUZ METIN
-- degil, uygulama anahtarindan turetilen anahtarla AES-256-GCM sifrelenmis halde tutulur (bkz.
-- SlackUrlCipher; AAD = workspace_id, yani baska tenant'in satirina kopyalanan sifreli metin
-- cozulemez). DB yedegi/sizintisi tek basina adresleri aciga cikarmaz.
CREATE TABLE slack_integrations (
    workspace_id          UUID PRIMARY KEY REFERENCES workspaces(id),
    webhook_url_encrypted TEXT        NOT NULL,
    enabled               BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Tenant verisi: diger tenant tablolariyla ayni fail-closed politika (V6: bos GUC NULLIF ile
-- NULL'a cevrilir). V13'un aksine FORCE VAR: tenant baglami olmadan okunmasi gereken bir yol yok
-- (Notification Worker, TenantExecutor ile olayin workspace_id'sinden baglam kurar).
ALTER TABLE slack_integrations ENABLE ROW LEVEL SECURITY;
ALTER TABLE slack_integrations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON slack_integrations
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);
