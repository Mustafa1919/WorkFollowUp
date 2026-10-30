-- error_events satirlari maskeleme (SEC-09) oncesi yazildigi icin ham e-posta, erisim token'i veya
-- davet token'i icerebilir; maskelenmemis gecmis veri tutulmaz. Tablo RLS'siz altyapi tablosudur (V33).
-- usage_events etkilenmez. Bos tabloda ve tekrar kosulmada hata vermez.
DELETE FROM error_events;
