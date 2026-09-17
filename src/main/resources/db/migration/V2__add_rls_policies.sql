-- PHASE_1_DETAILED_DESIGN.md Bolum 3 referans alinarak olusturulmustur.
--
-- Not: workspaces/users/workspace_users tabloları burada RLS'e tabi TUTULMAZ.
-- Bunlar tenant "kok" ve uyelik verisidir; erisim workspace_id'ye gore degil
-- kullaniciya gore (uyelik sorgusu ile) kontrol edilir ve tenant context henuz
-- kurulmadan (login/workspace-secimi anında) okunmaları gerekir.
--
-- task_events tablosunda workspace_id kolonu yoktur (DATABASE_SCHEMA.md 2.8);
-- izolasyon, RLS'e tabi olan tasks tablosuna EXISTS ile dolayli kurulur.

ALTER TABLE projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE projects FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON projects
    USING (workspace_id = current_setting('app.current_workspace_id', true)::uuid)
    WITH CHECK (workspace_id = current_setting('app.current_workspace_id', true)::uuid);

ALTER TABLE sprints ENABLE ROW LEVEL SECURITY;
ALTER TABLE sprints FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sprints
    USING (workspace_id = current_setting('app.current_workspace_id', true)::uuid)
    WITH CHECK (workspace_id = current_setting('app.current_workspace_id', true)::uuid);

ALTER TABLE tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tasks
    USING (workspace_id = current_setting('app.current_workspace_id', true)::uuid)
    WITH CHECK (workspace_id = current_setting('app.current_workspace_id', true)::uuid);

ALTER TABLE task_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON task_events
    USING (EXISTS (SELECT 1 FROM tasks t WHERE t.id = task_events.task_id))
    WITH CHECK (EXISTS (SELECT 1 FROM tasks t WHERE t.id = task_events.task_id));
