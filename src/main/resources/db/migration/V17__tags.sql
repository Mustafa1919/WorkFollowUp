-- Faz 4 oncesi ara adim: Tags/Labels (RAKIP_ANALIZI.md Bolum 3 — Yuksek oncelik, Faz 6'nin acik
-- noktasiydi). Workspace duzeyinde tanimlanir (proje duzeyinde degil: kucuk ekiplerde etiketler
-- projeler arasi tutarli olmali, "Bug" her projede ayni renk/anlam tasimali).
CREATE TABLE tags (
    id           UUID PRIMARY KEY,
    workspace_id UUID        NOT NULL REFERENCES workspaces(id),
    name         VARCHAR(40) NOT NULL,
    color        VARCHAR(7)  NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_tags_color_format CHECK (color ~ '^#[0-9A-Fa-f]{6}$')
);

-- LOWER(name): case-insensitive essizlik ("Bug" ve "bug" ayni etiket sayilir) — TagService de ayni
-- kurali (existsByWorkspaceIdAndNameIgnoreCase) ONCE kontrol eder, bu index race condition'a karsi
-- ikinci savunma hattidir (bkz. idempotency/unique-constraint deseni, Backend-Notlar 2026-09-18).
CREATE UNIQUE INDEX uq_tags_workspace_lower_name ON tags (workspace_id, LOWER(name));

-- Diger tenant tablolariyla AYNI fail-closed politika (V6: bos GUC NULLIF ile NULL'a cevrilir).
ALTER TABLE tags ENABLE ROW LEVEL SECURITY;
ALTER TABLE tags FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tags
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

-- Gorev <-> etiket ataması (cok-cok). workspace_id BILEREK denormalize edilir: RLS politikasi
-- diger tum tenant tablolariyla ayni bicimde (dogrudan kolon karsilastirmasi) yazilabilsin diye —
-- task_events'teki EXISTS(...) dolayli deseninin aksine, burada gerek yok cunku hem tasks hem tags
-- zaten workspace_id tasiyor ve ikisi de ayni ataerkil (parent) satirdan turetilebilir.
CREATE TABLE task_tags (
    task_id      UUID        NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    tag_id       UUID        NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    workspace_id UUID        NOT NULL REFERENCES workspaces(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (task_id, tag_id)
);

-- PK (task_id, tag_id) zaten task_id'yi soldan indeksler (bir gorevin etiketlerini bulmak icin
-- yeterli); tag_id icin AYRI index gerekir (bir etiketin tum atamalarini bulmak / ON DELETE CASCADE
-- performansi icin).
CREATE INDEX idx_task_tags_tag_id ON task_tags (tag_id);

ALTER TABLE task_tags ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_tags FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON task_tags
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

-- Not: app_runtime icin ayrica GRANT gerekmiyor — docker/postgres-init/init-roles-and-dbs.sh'deki
-- ALTER DEFAULT PRIVILEGES, app_migrator'in yarattigi HER yeni tabloya SELECT/INSERT/UPDATE/DELETE'i
-- otomatik verir (V9/V14 ile ayni desen).
