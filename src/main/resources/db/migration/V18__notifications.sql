-- Faz 4 oncesi ara adim: In-app bildirim gelen kutusu (RAKIP_ANALIZI.md Bolum 3 — Yuksek oncelik;
-- PHASE_6_PRODUCT_FEATURES.md Bolum 4'un tek-kanal, denormalize "Fan-out on Write" tasarimi).
--
-- assignee_id (V1'den beri tasks'ta duran, hic kullanilmayan bir kolon) BILEREK kullanilmadi: bu
-- dilimin kapsami sadece Inbox, gorev atama ozelligi degil (Hedefler.md sirasi: Tags -> Inbox ->
-- Subtask/Dependency -> Command Palette). Alici bu yuzden "workspace'teki DEVELOPER ve uzeri roller,
-- aktor haric" olarak tanimlandi (NotificationFanoutService). assignee_id ileride koda baglanirsa
-- alici tanimi "sadece atanan kisi + izleyenler"e kaymali; bu migration/tasarim o zaman gozden
-- gecirilmeli.
-- project_id: verilen kolon listesinin (task_id, title, body, ...) DISINDA, bilerek eklendi.
-- Frontend "tikla, gorevin proje board'una git" gereksinimi projectId'yi gerektiriyor; bunu
-- REST cevabinda tasimazsak ya payload'i (write-only, entity'ye maplenmiyor) acmak ya da her
-- tiklamada ekstra bir task sorgusu yapmak gerekirdi. task_id gibi NULLABLE: ileride gorevle
-- ilgisiz bir bildirim turu eklenirse zorunlu olmaz.
CREATE TABLE notifications (
    id           UUID PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES workspaces(id),
    user_id      UUID         NOT NULL REFERENCES users(id),
    type         VARCHAR(50)  NOT NULL,
    task_id      UUID REFERENCES tasks(id),
    project_id   UUID REFERENCES projects(id),
    title        VARCHAR(255) NOT NULL,
    body         VARCHAR(500) NOT NULL,
    -- Insan-okunur ozet title/body'de; payload ham task.events payload'inin bir kopyasidir (ileride
    -- zengin render/hata ayiklama icin). Hibernate 7 + Jackson 3 JSONB entity mapping'i belirsizligi
    -- (bkz. Mimari.md 2026-09-19 "custom_fields" karari) NEDENIYLE entity'ye MAPLENMEZ; yalniz native
    -- SQL (INSERT'te ?N ::jsonb, araya bosluk — Hibernate 7 ordinal-parametre + bitisik cast tuzagi)
    -- ile yazilir, REST'e YANSITILMAZ.
    payload      JSONB,
    read_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- "Bana ait, okunmamislar en yeniden eskiye" sorgusu (bell ikonu + inbox listesi) icin; ayni indeks
-- "tumu" listesini de (read_at filtresi olmadan) created_at DESC ile karsilar.
CREATE INDEX idx_notifications_user_unread ON notifications (user_id, read_at, created_at DESC);

-- Diger tenant tablolariyla AYNI fail-closed politika (V6: bos GUC NULLIF ile NULL'a cevrilir).
-- NOT: RLS yalniz workspace izolasyonunu saglar, kullanicilar-arasi izolasyon (bir uyenin baska
-- uyenin bildirimini gormemesi) uygulama/servis katmaninda user_id = CurrentUser.id() ile
-- ZORUNLU tutulur (workspace_users gibi RLS'in kapsam disinda birakabilecegi bir boyut degil, ama
-- RLS "hangi tenant" sorusunu cevaplar, "hangi kullanici" sorusunu degil).
ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notifications
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

-- app_runtime icin ayrica GRANT gerekmiyor — init-roles-and-dbs.sh'deki ALTER DEFAULT PRIVILEGES
-- app_migrator'in yarattigi HER yeni tabloya SELECT/INSERT/UPDATE/DELETE'i otomatik verir (V9/V14/
-- V17 ile ayni desen).
