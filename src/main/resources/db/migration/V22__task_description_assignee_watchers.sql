-- Urunlestirme Dalga 1.1: gorev aciklamasi + atanan kisi + izleyiciler.
--
-- assignee_id V1'den beri tasks'ta duruyordu (idx_tasks_assignee_id dahil) ama hicbir kod yolu
-- kullanmiyordu; bu migration yalniz eksik parcalari ekler.

-- Markdown olarak saklanir, HTML'e cevirme istemcide yapilir (PHASE_6 Bolum 1.2 ile ayni ilke).
-- Ust sinir DB'de de zorlanir: DTO dogrulamasi atlanirsa (ileride yeni bir yazma yolu) tablo
-- sinirsiz buyumesin.
ALTER TABLE tasks ADD COLUMN description TEXT;
ALTER TABLE tasks ADD CONSTRAINT chk_tasks_description_length
    CHECK (description IS NULL OR char_length(description) <= 20000);

-- Olusturan kullanici. Eski satirlar icin bilinmiyor (NULL) — tarihcede olusturma olayi yoktu.
ALTER TABLE tasks ADD COLUMN created_by UUID REFERENCES users(id);

-- "Benim islerim": atanmis, onaylanmamis, silinmemis gorevler; keyset (created_at, id) DESC.
CREATE INDEX idx_tasks_assignee_open ON tasks (assignee_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL AND approved_at IS NULL;

-- Izleyiciler: Inbox bildiriminin alicilari (rol bazli yayin yerine). task_tags (V17) ile AYNI
-- desen: workspace_id denormalize, RLS+FORCE, native SQL repository.
CREATE TABLE task_watchers (
    task_id      UUID        NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users(id),
    workspace_id UUID        NOT NULL REFERENCES workspaces(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (task_id, user_id)
);

-- PK task_id'yi soldan indeksler; kullanicinin izledikleri icin ayri index.
CREATE INDEX idx_task_watchers_user_id ON task_watchers (user_id);

ALTER TABLE task_watchers ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_watchers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON task_watchers
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

-- Gecis: bu migration'dan ONCE acilmis gorevlerin olusturani/atanani bilinmiyor. Hic izleyicisi
-- olmazsa eski gorevler icin Inbox tamamen susardi; bu yuzden eski davranis (workspace'in
-- ADMIN/MANAGER/DEVELOPER uyelerine yayin) izleyici satiri olarak korunur. Kullanicilar
-- istemedikleri gorevi "izlemeyi birak" ile cikarabilir. VIEWER bilerek dahil degil (V18 kurali).
--
-- Varsayim: migration kullanicisi RLS'i bypass eder (docker/postgres-init: app_migrator bootstrap
-- superuser). Yonetilen bir DB'de migrator superuser DEGILSE bu INSERT FORCE RLS yuzunden hic satir
-- goremez ve sessizce 0 satir yazar — o ortamda BYPASSRLS verilmeli.
INSERT INTO task_watchers (task_id, user_id, workspace_id, created_at)
SELECT t.id, wu.user_id, t.workspace_id, NOW()
FROM tasks t
JOIN workspace_users wu ON wu.workspace_id = t.workspace_id
WHERE t.deleted_at IS NULL
  AND wu.role IN ('WORKSPACE_ADMIN', 'MANAGER', 'DEVELOPER')
ON CONFLICT DO NOTHING;
