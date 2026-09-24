-- Urunlestirme Dalga 1.2: yorumlar + @mention.
--
-- task_watchers (V22) ile AYNI desen: RLS+FORCE, V6'daki fail-closed NULLIF politikasi. Tek
-- seviye (parent yok) — Faz 6 Bolum 1'deki thread hiyerarsisi bilerek v1 kapsaminda degil (plan
-- dokumaninin "1.2 Yorumlar + @mention" boluumu).
CREATE TABLE comments (
    id           UUID        NOT NULL PRIMARY KEY,
    workspace_id UUID        NOT NULL REFERENCES workspaces(id),
    task_id      UUID        NOT NULL REFERENCES tasks(id),
    author_id    UUID        NOT NULL REFERENCES users(id),
    body         TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Soft delete: silinen yorum satiri KALIR (govdesi DB'de korunur), API govdeyi "[silindi]"
    -- olarak maskeler (CommentResponse#from). Boylece thread'in sirasi/sayisi bozulmaz.
    deleted_at   TIMESTAMPTZ
);

ALTER TABLE comments ADD CONSTRAINT chk_comments_body_length
    CHECK (char_length(body) <= 10000);

-- Bir gorevin yorumlarini zaman sirasiyla okumak icin; (task_id, created_at) V22'nin V19/V17
-- deseniyle ayni (denormalize erisim yolu).
CREATE INDEX idx_comments_task_id_created_at ON comments (task_id, created_at);

ALTER TABLE comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE comments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON comments
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);
