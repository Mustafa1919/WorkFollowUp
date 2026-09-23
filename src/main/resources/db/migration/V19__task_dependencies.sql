-- Faz 4 oncesi ara adim: Subtask + Dependency (RAKIP_ANALIZI.md Bolum 3 — Yuksek oncelik).
--
-- Subtask: tasks.parent_task_id kolonu V1'den beri semada duruyor ama hic kullanilmiyordu (entity'ye
-- mapli degildi, bkz. Task.java javadoc'u). Bu artimda mapleniyor; tek eksik parent_task_id icin
-- indexti (bir gorevin alt gorevlerini bulmak icin), burada eklenir.
CREATE INDEX idx_tasks_parent_task_id ON tasks (parent_task_id) WHERE parent_task_id IS NOT NULL;

-- Dependency (Blocked by / Blocking): task_tags ile AYNI desen (cok-cok iliski, workspace_id
-- BILEREK denormalize edilir ki RLS diger tum tenant tablolariyla ayni sekilde dogrudan kolon
-- karsilastirmasi yapabilsin). Yonlu: blocking_task_id, blocked_task_id'yi bloklar.
--
-- Bilinen sinir (bilerek, servis katmaninda cozulur): ters-cift (A, B'yi blokluyorken B'nin de A'yi
-- bloklamasi) ve transitive dongu (A->B->C->A) DB seviyesinde CHECK/UNIQUE ile yakalanamaz (satirlar
-- arasi kisit); TaskDependencyService sadece dogrudan ters-cifti reddeder, derin dongu tespiti
-- YAPILMAZ (V13'teki webhook kimlik modeli sapmasiyla ayni "bilerek dar kapsam" deseni).
CREATE TABLE task_dependencies (
    blocking_task_id UUID        NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    blocked_task_id  UUID        NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    workspace_id     UUID        NOT NULL REFERENCES workspaces(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (blocking_task_id, blocked_task_id),
    CONSTRAINT chk_task_dependencies_not_self CHECK (blocking_task_id <> blocked_task_id)
);

-- PK (blocking_task_id, blocked_task_id) zaten blocking_task_id'yi soldan indeksler ("bu gorev neyi
-- bloklar" sorgusu); "bu gorev neyle bloklu" sorgusu (blocked_task_id) icin AYRI index gerekir.
CREATE INDEX idx_task_dependencies_blocked ON task_dependencies (blocked_task_id);

ALTER TABLE task_dependencies ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_dependencies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON task_dependencies
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

-- Not: app_runtime icin ayrica GRANT gerekmiyor — ALTER DEFAULT PRIVILEGES (docker/postgres-init/
-- init-roles-and-dbs.sh) app_migrator'in yarattigi her yeni tabloya otomatik SELECT/INSERT/UPDATE/
-- DELETE verir (V9/V14/V17 ile ayni desen).
