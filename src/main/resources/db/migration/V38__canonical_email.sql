-- SEC-14: e-posta tek kanonik bicimde saklanir (bas/son bosluk kirpilmis, yalniz ASCII A-Z
-- kucultulmus). Java tarafi: com.app.tracker.user.model.EmailAddresses#canonical.
--
-- lower() KULLANILMAZ: ASCII disi harfleri de kucultur, Java tarafiyla uyusmaz. [A-Z] regex
-- araligi da kullanilmaz: Postgres'te aralik harmanlamaya baglidir. translate() harf harf,
-- harmanlamadan bagimsiz calisir.

-- 1) users icinde kanonik bicimi cakisan satirlar varsa HICBIR satiri degistirmeden dur.
DO $$
DECLARE
    conflicts TEXT;
BEGIN
    SELECT string_agg(grp.canonical_email || ' -> [' || grp.ids || ']', '; ' ORDER BY grp.canonical_email)
      INTO conflicts
      FROM (
          SELECT btrim(translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'))
                     AS canonical_email,
                 string_agg(id::text, ', ' ORDER BY id::text) AS ids
            FROM users
           GROUP BY 1
          HAVING count(*) > 1
      ) grp;

    IF conflicts IS NOT NULL THEN
        RAISE EXCEPTION 'V38: users tablosunda kanonik e-postasi cakisan kullanicilar var, elle birlestirin: %',
            conflicts;
    END IF;
END
$$;

-- 2) Mevcut veriyi kanoniklestir (yalniz degisecek satirlar).
UPDATE users
   SET email = btrim(translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'))
 WHERE email <> btrim(translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'));

-- Davet tablosunda cakisma kontrolu YOK: workspace basina ayni e-postaya birden cok davet satiri
-- (REVOKED/ACCEPTED gecmisi) olabilir.
UPDATE workspace_invitations
   SET email = btrim(translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'))
 WHERE email <> btrim(translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'));

-- 3) Bundan sonra ASCII buyuk harf iceren e-posta yazilamaz. (Bosluk kirpma icin ayri CHECK yok.)
ALTER TABLE users
    ADD CONSTRAINT users_email_canonical
        CHECK (email = translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'));

ALTER TABLE workspace_invitations
    ADD CONSTRAINT workspace_invitations_email_canonical
        CHECK (email = translate(email, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'));
