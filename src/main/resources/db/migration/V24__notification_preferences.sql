-- Urunlestirme Dalga 1.3: e-posta gonderim altyapisi — kullanici basina bildirim tercihi.
--
-- V23 diger bir ajanin dilimine (yorumlar + @mention) ayrildi, burada KULLANILMADI.
--
-- Bu tablo WORKSPACE'e degil KULLANICIYA aittir (bir kullanicinin tum workspace'lerdeki e-posta
-- tercihi tek satirda) — auth tablolariyla (V5: refresh_tokens, verification_tokens) AYNI mantik:
-- RLS uygulanmaz, izolasyon zaten user_id = CurrentUser.id() ile servis katmaninda saglanir (bkz.
-- notifications tablosunun kullanicilar-arasi izolasyon notu, V18).
CREATE TABLE notification_preferences (
    user_id          UUID PRIMARY KEY REFERENCES users(id),
    email_on_assign  BOOLEAN     NOT NULL DEFAULT true,
    email_on_mention BOOLEAN     NOT NULL DEFAULT true,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Satir yoksa varsayilan (ikisi de acik) uygulama katmaninda kabul edilir (bkz.
-- NotificationPreferencesService); bu tablo yalniz VARSAYILANDAN SAPAN kullanicilar icin satir
-- tutar, her kayitta otomatik satir olusturulmaz.
