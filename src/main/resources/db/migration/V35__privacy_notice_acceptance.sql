-- Aydinlatma metni surumu ve ilk onay zamani; mevcut kullanicilar onaysiz baslar.
ALTER TABLE users ADD COLUMN privacy_notice_version VARCHAR(20);
ALTER TABLE users ADD COLUMN privacy_notice_accepted_at TIMESTAMPTZ;
