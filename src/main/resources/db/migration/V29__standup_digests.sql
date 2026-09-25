-- Urunlestirme Dalga 2.3: Toplantisiz standup (async check-in).
--
-- Bir toplanti serisinde standup ozetleri acilabilir (standup_enabled). StandupDigestJob
-- occurrence'tan 30 dakika once her katilimci icin BIR ozet uretir; ayni (meeting, occurrence,
-- kullanici) icin ikinci kez calismaz -- idempotency ProcessedEventStore ile DEGIL, bu tablonun
-- kendi PRIMARY KEY'i + ON CONFLICT DO NOTHING ile saglanir (MeetingReminderService'teki ayri
-- idempotency tablosu ihtiyacini ortadan kaldirir, Notification'in aksine burada "olay" degil
-- "durum" saklaniyor).
ALTER TABLE meetings ADD COLUMN standup_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE standup_digests (
    workspace_id     UUID        NOT NULL REFERENCES workspaces(id),
    meeting_id       UUID        NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    occurrence_date  DATE        NOT NULL,
    user_id          UUID        NOT NULL REFERENCES users(id),
    -- Yapilandirilmis "olgular" (tamamlanan/ilerleyen/devam eden/bloklanan/takilan/GitHub
    -- aktivitesi) -- LLM katmani v1'de YOK, ileride isteğe bagli bir ozetleyici bu alani girdi
    -- olarak kullanabilir (Hedefler.md karari).
    facts            JSONB       NOT NULL,
    -- Kullanicinin ekledigi "bugun planim / engelim" notu.
    note             TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (meeting_id, occurrence_date, user_id)
);

ALTER TABLE standup_digests ADD CONSTRAINT chk_standup_digests_note_length
    CHECK (note IS NULL OR char_length(note) <= 2000);

-- /standups sayfasi: tarih + toplanti secici, sonra workspace icindeki tum ozetler tek sorguda.
CREATE INDEX idx_standup_digests_workspace_date ON standup_digests (workspace_id, occurrence_date);

-- task_watchers (V22) ile AYNI desen: workspace_id denormalize, RLS+FORCE, native SQL repository.
ALTER TABLE standup_digests ENABLE ROW LEVEL SECURITY;
ALTER TABLE standup_digests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON standup_digests
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);
