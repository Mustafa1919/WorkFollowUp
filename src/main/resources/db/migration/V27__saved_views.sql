-- Dalga 1.7 (V27) — Board verimliligi: kayitli gorunumler. Toplu islem (bulk) icin yeni tablo
-- gerekmiyor, mevcut TaskService/TagService uzerinden calisir.
--
-- `query` alani JSONB DEGIL TEXT: sunucu tarafinda hic sorgulanmiyor (BoardPage.tsx'teki URL
-- filtre durumunun -sprint/tags/assignee- opak bir JSON kopyasi), TaskCustomFieldRepository'nin
-- "custom_fields JSONB type mapping'i (Hibernate 7 + Jackson 3 uyumu belirsiz) yerine native SQL"
-- gerekcesiyle AYNI sebep — burada native SQL'e bile gerek yok, duz TEXT yeterli.
CREATE TABLE saved_views (
    id           UUID PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES workspaces(id),
    project_id   UUID         NOT NULL REFERENCES projects(id),
    user_id      UUID         NOT NULL REFERENCES users(id),
    name         VARCHAR(60)  NOT NULL,
    query        VARCHAR(4000) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Gorunum listesi hep "bu proje + bu kullanici" ile sorgulanir (kisisel, Tags'in aksine paylasilan
-- workspace-geneli bir yapi degil).
CREATE INDEX idx_saved_views_project_user ON saved_views (project_id, user_id);

-- Diger tenant tablolariyla AYNI fail-closed politika (V6: bos GUC NULLIF ile NULL'a cevrilir).
ALTER TABLE saved_views ENABLE ROW LEVEL SECURITY;
ALTER TABLE saved_views FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON saved_views
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

-- Not: app_runtime icin ayrica GRANT gerekmiyor — docker/postgres-init/init-roles-and-dbs.sh'deki
-- ALTER DEFAULT PRIVILEGES, app_migrator'in yarattigi HER yeni tabloya SELECT/INSERT/UPDATE/DELETE'i
-- otomatik verir (V9/V14/V17 ile ayni desen).
