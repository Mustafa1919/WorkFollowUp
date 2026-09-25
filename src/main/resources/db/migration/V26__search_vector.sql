-- Dalga 1.6 — Global arama. Basliklarin (agirlik A) aciklamadan (agirlik B) daha onemli sayildigi
-- tek bir tsvector kolonu tasks'a, gorev govdesi icin ayni bicimde comments'a eklenir.
CREATE EXTENSION IF NOT EXISTS unaccent;

-- unaccent() STABLE'dir (sozluk arama yoluna bagli oldugu icin), bu yuzden GENERATED ALWAYS
-- kolonda dogrudan kullanilamaz ("generation expression is not immutable"). Yaygin cozum: sabit
-- 'unaccent' sozlugunu adiyla cagiran, IMMUTABLE olarak ISARETLENMIS bir sarmalayici — sozluk
-- calisma zamaninda degismeyecegi varsayilir, bu varsayim gecerli kaldigi surece guvenlidir
-- (yonetilen DB'de unaccent uzantisinin izinli olmasi gerekir; risk ADR-0011'de kayitli).
CREATE OR REPLACE FUNCTION immutable_unaccent(text) RETURNS text AS
$$
SELECT unaccent('unaccent', $1)
$$ LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT;

-- 'simple' konfigurasyonu (turkish stemmer degil): kisa basliklarda ('Auth', 'API') stemmer
-- tutarsiz kok buluyor; proje anahtari (PRJ-12) tam metin aramada zaten ayri bir yoldan (dogrudan
-- eslesme) bulunuyor, stemming'e ihtiyac yok.
ALTER TABLE tasks
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', immutable_unaccent(coalesce(title, ''))), 'A') ||
        setweight(to_tsvector('simple', immutable_unaccent(coalesce(description, ''))), 'B')
        ) STORED;

CREATE INDEX idx_tasks_search_vector ON tasks USING GIN (search_vector);

ALTER TABLE comments
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('simple', immutable_unaccent(coalesce(body, '')))
        ) STORED;

CREATE INDEX idx_comments_search_vector ON comments USING GIN (search_vector);
