-- Urunlestirme Dalga 3.1: Hazir otomasyon sablonlari (ADR-0016).
--
-- Serbest kural editoru YOK (Jira'nin konfigurasyon karmasasindan bilincli kacinma) -- kod icinde
-- sabit, dogrulanan sablonlar (template_key, bkz. AutomationTemplateKey) proje bazinda ac/kapa.
-- Kapsam BILEREK proje-duzeyinde (workspace-geneli kural YOK, Subtask/V19 ile ayni basitlestirme):
-- her (project_id, template_key) icin EN FAZLA bir satir -- "sablonu ac/kapa" zihinsel modeliyle
-- birebir. v1'de sablonlarin parametresi yok; ileride gerekirse `params` kullanilir (JSONB DEGIL
-- TEXT -- saved_views/V27 ile ayni gerekce: Hibernate 7 + Jackson 3 JSONB entity mapping
-- belirsizligi, sunucu bu alani hic sorgulamiyor).
CREATE TABLE automation_rules (
    id           UUID        NOT NULL PRIMARY KEY,
    workspace_id UUID        NOT NULL REFERENCES workspaces(id),
    project_id   UUID        NOT NULL REFERENCES projects(id),
    template_key VARCHAR(64) NOT NULL,
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    params       TEXT        NOT NULL DEFAULT '{}',
    created_by   UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, template_key)
);

CREATE INDEX idx_automation_rules_project ON automation_rules (project_id);

ALTER TABLE automation_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON automation_rules
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

-- OVERDUE_NOTIFY gunluk dedup: is (AutomationOverdueJob) saatlik calisabilir, ayni gun icin ayni
-- goreve tekrar bildirim gitmesin. task_aging_alerts (V28) ile AYNI RLS+FORCE desen; farkli olarak
-- burada anahtar (template_key, task_id, sent_on) uclusu -- birden fazla sablon/gun icin ayri satir.
CREATE TABLE automation_notifications_sent (
    template_key VARCHAR(64) NOT NULL,
    task_id      UUID        NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    workspace_id UUID        NOT NULL REFERENCES workspaces(id),
    sent_on      DATE        NOT NULL,
    PRIMARY KEY (template_key, task_id, sent_on)
);

ALTER TABLE automation_notifications_sent ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_notifications_sent FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON automation_notifications_sent
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

-- Otomasyon aktoru: webhook'un system-integration kullanicisi (V13) ile AYNI desen -- giris
-- yapamaz, hicbir workspace'e uye degil, task_events.actor_id FK'si icin gecerli bir satir. Dongu
-- korumasi bu sabit kimlige dayanir: AutomationEventConsumer, actorId'si bu kullanici olan
-- task.events olaylarini isleMEZ (otomasyonun kendi urettigi olay baska bir kurali tetiklemez).
INSERT INTO users (id, email, password_hash, full_name, email_verified)
VALUES ('00000000-0000-0000-0000-00000000a003',
        'system-automation@tracker.invalid',
        '!disabled',
        'Otomasyon (system)',
        true)
ON CONFLICT (id) DO NOTHING;
