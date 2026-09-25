# ADR-0011: Global arama modeli

- **Durum:** Kabul edildi (Ürünleştirme Dalga 1.6 — V26, 2026-09-25)
- **İlgili:** yok

## Bağlam

Command Palette (2026-09-23) görev aramasını istemci tarafında yapıyordu: palet açıldığında
`useAllTasks` workspace'teki HER projenin HER görevini çekiyor, filtre `Array.filter` ile
yapılıyordu. Workspace büyüdükçe bu hem gürültülü (kullanılmayan onlarca istek) hem de yanlış
kapsamlı — görev *açıklaması* veya *yorumlar* aranamıyordu, çünkü onlar hiç istemciye
gelmiyordu. Ürünleştirme planının 1.6 dilimi bunu sunucu taraflı tam metin aramaya taşıyor.

## Karar

1. **PostgreSQL `tsvector` + GIN index, ayrı bir arama motoru (Elasticsearch/Meilisearch) DEĞİL.**
   Workspace başına veri hacmi (görev + yorum) bir arama kümesi kurmayı haklı çıkaracak
   büyüklükte değil; Postgres zaten tek doğruluk kaynağı, ayrı bir indeksleme pipeline'ı
   (senkronizasyon gecikmesi, ikinci bir altyapı bileşeni) gereksiz karmaşıklık olurdu.
2. **`simple` metin konfigürasyonu, `turkish` stemmer DEĞİL.** Kısa başlıklar ("Auth", "API",
   "PRJ-12") stemmer altında tutarsız kök buluyor; ayrıca proje anahtarı gibi tam eşleşmesi
   gereken tokenlar stemmer'ın kelime köküne indirgemesinden zarar görür. Türkçe eklerin
   (görev/görevi/görevler) eşleşmemesi kabul edilen bir bedel — kullanıcı deneyiminde ölçülmedi,
   gerekirse `turkish` konfigürasyonuna geçiş tek migration'lık bir değişikliktir.
3. **`unaccent` için IMMUTABLE sarmalayıcı fonksiyon.** Postgres'in kendi `unaccent()`'i STABLE
   olduğundan `GENERATED ALWAYS AS ... STORED` kolonunda kullanılamıyor
   ("generation expression is not immutable" hatası). Yaygın çözüm uygulandı:
   `immutable_unaccent(text)` IMMUTABLE olarak işaretlenmiş bir SQL sarmalayıcı — sözlük çalışma
   zamanında değişmeyeceği varsayımı kabul edildi (bu proje ölçeğinde gerçekçi bir varsayım).
   **Bilinen risk:** yönetilen bir DB'de (RDS gibi) `CREATE EXTENSION unaccent` için gerekli
   izin her zaman verilmeyebilir; Faz 4'te gerçek bulut DB'sine geçilirken doğrulanmalı.
4. **Generated column (STORED), trigger DEĞİL.** `tasks.search_vector`/`comments.search_vector`
   `title`/`description`/`body` her INSERT/UPDATE'te Postgres tarafından otomatik yeniden
   hesaplanır; ayrı bir trigger fonksiyonu yazıp bakımını üstlenmeye gerek yok, ve entity'ye
   MAPLENMEZ (`TaskCustomFieldRepository`/`task_events` ile aynı "native SQL'in yönettiği kolon"
   deseni).
5. **Proje anahtarı + görev numarası ("PRJ-12") ayrı bir dogrudan-eslesme yolu.** Tam metin arama
   `PRJ-12` gibi bir sorguda `PRJ` ve `12` token'larını ayrı ayrı arar, tam görev numarasını
   önceliklendirmez. `GithubEventInterpreter`'daki AYNI regex (`[A-Z][A-Z0-9]{1,9}-\d{1,9}`,
   büyük/küçük harf duyarsız) `SearchService`'te bağımsız bir kopya olarak kullanılıyor; ortak bir
   sabite çıkarılmadı çünkü iki modül arasında gerçek bir bağımlılık kurulması istenmedi
   (entegrasyon modülü commit/PR metni ayrıştırıyor, arama kullanıcı sorgusu ayrıştırıyor — farklı
   girdi güven seviyeleri).
6. **Silinmiş görev/yorum sonuçlardan HARİÇ tutulur** (native SQL `deleted_at IS NULL`,
   `@SQLRestriction` native sorguda uygulanmaz, elle eklenmesi gerekiyordu). Onaylı görevler
   ARAMADA görünür (Kanban'dan kalkmaları farklı bir kavram — onay listelemeyi etkiler, aramayı
   etkilemez).
7. **Vurgu (highlight) isaretleyicileri HTML DEĞİL.** `ts_headline` varsayılan `<b>`/`</b>` yerine
   `\u0001`/`\u0002` (kontrol karakteri) StartSel/StopSel kullanır; frontend bunları React text
   node'larına böler (`highlightSnippet`, `CommandPalette.tsx`) — böylece görev açıklaması veya
   yorum gövdesindeki kullanıcı metni asla `dangerouslySetInnerHTML`'e gitmez, XSS yüzeyi açılmaz.

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| Elasticsearch/Meilisearch | Bu ölçekte gereksiz altyapı; ikinci bir senkronize edilmesi gereken veri kopyası. |
| `turkish` stemmer | Kısa başlık/proje anahtarı eşleşmesinde tutarsız; ölçülmeden erken optimizasyon olurdu. |
| Trigger tabanlı `search_vector` | Generated column aynı sonucu bakım yükü olmadan veriyor. |
| İstemci tarafı arama (mevcut hal) | Ölçeklenmiyor, açıklama/yorum içeriğine hiç erişemiyor. |
| `ts_headline` varsayılan `<b>` etiketleri | Frontend'in ham HTML'i güvenli parse etmesi (ya sanitizer kütüphanesi ya XSS riski) gerekirdi. |

## Sonuçlar

**Olumlu**
- Görev başlığı/açıklaması VE yorum gövdesi aynı uçtan aranabiliyor, işaretli sıralama (`ts_rank`,
  başlık ağırlığı A > açıklama B).
- `PRJ-12` gibi doğrudan referanslar tek sonuçla (isabetli) dönüyor.
- Command Palette artık workspace büyüklüğünden bağımsız: sabit sayıda (≤8+8) sonuç, debounce'lu
  tek istek.

**Olumsuz / kabul edilen bedel**
- `unaccent` uzantısının yönetilen DB'de izinli olacağı garanti değil (Faz 4 riski, madde 3).
- Türkçe ek/çekim eşleşmesi yok (`simple` konfigürasyonu).
- Eski (V26 öncesi) satırlar generated column sayesinde otomatik dolduruldu (migration sırasında
  Postgres tüm mevcut satırları yeniden hesaplar); ancak bu, büyük tablolarda migration süresini
  uzatabilecek bir maliyet — bu projede veri hacmi küçük olduğu için hissedilmedi.

## Referanslar

- `src/main/resources/db/migration/V26__search_vector.sql`
- `src/main/java/com/app/tracker/search/`
- `frontend/src/components/CommandPalette.tsx#highlightSnippet`
- `src/main/java/com/app/tracker/integration/service/GithubEventInterpreter.java` (aynı regex'in bağımsız kopyası)
