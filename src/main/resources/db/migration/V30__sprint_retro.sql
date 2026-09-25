-- Urunlestirme Dalga 2.4: Veriye dayali retrospektif.
--
-- ADR-0002'nin acik biraktigi "ilk taahhut" kesiti kapatilir: sprint BASLADIGI (started_at)
-- andaki uyelik de ayrica saklanir. committed (mevcut kolonlar) hala sprint KAPANDIGI andaki
-- uyeligi tasir (Velocity/Spillover tanimi DEGISMEDI); bu iki kolon yalniz retro'nun "sprint
-- ortasinda ne eklendi/cikarildi" sorusunu cevaplamak icin eklenir.
--
-- NULLABLE: bu migration'dan ONCE tamamlanmis sprint'lerin analitigi bu alanlari HIC hesaplamadi,
-- geri doldurma YAPILMAZ (V17/V19/webhook kimlik modeliyle AYNI "bilerek dar kapsam" deseni) --
-- retro sayfasi bu sprint'ler icin "plan verisi yok" gosterir.
ALTER TABLE sprint_analytics ADD COLUMN committed_at_start_tasks INTEGER;
ALTER TABLE sprint_analytics ADD COLUMN committed_at_start_points BIGINT;

-- Retro panosu: sprint basina serbest metin maddeleri. RLS+FORCE, task_watchers/task_tags ile AYNI
-- desen (workspace_id denormalize). "action" turundeki bir madde tek tikla gorece donusturulebilir
-- (task_id o zaman doldurulur, geriye donuk degistirilemez degil ama uygulama bunu bir kez yapar).
CREATE TABLE retro_items (
    id           UUID        NOT NULL PRIMARY KEY,
    workspace_id UUID        NOT NULL REFERENCES workspaces(id),
    sprint_id    UUID        NOT NULL REFERENCES sprints(id) ON DELETE CASCADE,
    kind         VARCHAR(20) NOT NULL CHECK (kind IN ('went_well', 'improve', 'action')),
    body         TEXT        NOT NULL CHECK (char_length(body) BETWEEN 1 AND 2000),
    author_id    UUID        NOT NULL REFERENCES users(id),
    task_id      UUID        REFERENCES tasks(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_retro_items_sprint ON retro_items (sprint_id, created_at);

ALTER TABLE retro_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE retro_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON retro_items
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);
