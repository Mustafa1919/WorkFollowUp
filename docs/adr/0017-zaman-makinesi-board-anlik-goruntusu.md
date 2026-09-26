# ADR-0017: Zaman makinesi — board'un geçmişteki bir andaki anlık görüntüsü

- **Durum:** Kabul edildi (Dalga 3.2, 2026-09-26)
- **İlgili:** ADR-0015 (`SprintSnapshotRepository`'nin "her alan için `created_at <= cutoff` olan
  son olay geçerlidir" deseni; bu ADR aynı deseni tek sprint'ten TÜM projeye genelleştirir)

## Bağlam

Kullanıcı bir projenin geçmişteki herhangi bir tarihte board'un nasıl göründüğünü ("3 hafta önce
hangi görevler Done'dı, kimdeydi") sorgulayabilmeli. Veri zaten `task_events`'te (append-only) var;
yeni bir tablo veya event yeniden oynatma altyapısı (event sourcing projection) kurmaya gerek yok —
`SprintSnapshotRepository`'nin retro için kullandığı DISTINCT ON deseni doğrudan uygulanabilir.

## Karar

1. **Yeni tablo/migration YOK.** `TaskSnapshotRepository` (`com.app.tracker.timemachine`) salt
   `tasks` + `task_events` üzerinden native SQL ile kesit kurar — `SprintSnapshotRepository` ile
   AYNI CTE deseni (`DISTINCT ON (task_id) ... created_at <= cutoff ORDER BY created_at DESC, id
   DESC`), ama tek sprint üyeliğiyle sınırlı değil, projedeki TÜM görevler için batch halinde.
2. **Silinme durumu `task_events`'teki `deleted` olayından DEĞİL, doğrudan `tasks.deleted_at`
   kolonundan okunur** — soft delete zaten gerçek bir zaman damgası, `deleted_at IS NULL OR
   deleted_at > cutoff` tek koşulu event tarihçesini yeniden yorumlamaktan daha basit ve tek
   doğruluk kaynağı.
3. **Kapsam BİLEREK dar: `status`, `sprintId`, `storyPoint`, `assigneeId`, `dueDate`.** Tags ve
   dependency kesite DAHİL EDİLMEDİ — `tags_changed`/`dependency_changed` olayları tek bir alanın
   son değerini değil bir KÜMENİN (birden fazla etiket/bağımlılık) ekle/çıkar geçmişini tutuyor;
   kümeyi yeniden kurmak DISTINCT-ON-son-değer deseninden farklı bir replay mantığı gerektirir. V1
   kapsamı dışında bırakıldı (Subtask/V19'un "tek seviye, sınırlı kapsam" deseniyle aynı bilinçli
   daraltma).
4. **`title`/`taskNumber` ANLIK (güncel) değerleriyle döner** — bu alanlar için `*_changed` olay
   tipi hiç yok (başlık değişikliği tarihçeye yazılmıyor). Geçmiş kesitte görevin o anki başlığı
   değil güncel başlığı görünür — description içeriğinin tarihçeye yazılmamasıyla AYNI türden
   bilinçli bir sınırlama, okuyucuya ADR'de ve repository javadoc'unda açıkça belirtildi.
5. **Okuma rol sınırı YOK** — `RetroController`/Activity sekmesiyle AYNI ilke: geçmiş kesit
   görüntüleme analitik okuma sayılır, tüm workspace üyeleri erişebilir.
6. **Cache YOK (v1).** Sık kullanılan cutoff'lar (örn. "her sprint başlangıcı") ileride
   `ForecastCacheService` deseniyle (sonsuz TTL, cutoff anahtara gömülü) cache'lenebilir — kesitler
   deterministik ve değişmez olduğu için bu güvenli bir gelecek optimizasyonu, v1'de gerek yok.

## Sonuçlar

**Olumlu:** Yeni tablo/event/worker sıfır — mevcut event log'un salt-okunur bir projeksiyonu.
Sorgu maliyeti proje büyüklüğüyle (görev sayısı × değişen alan sayısı) doğrusal, günde birkaç kez
açılan bir görünüm için kabul edilebilir.

**Kabul edilen bedel:** Tags/dependency/title geçmişi bu kesitte YOK (bilinen sınır, madde 3-4).
Çok büyük projelerde (binlerce görev, yoğun event geçmişi) sorgu maliyeti izlenmeli; gerekirse
madde 6'daki cache devreye alınır.

## Referanslar

- `src/main/java/com/app/tracker/timemachine/TaskSnapshotRepository.java`
- `src/main/java/com/app/tracker/timemachine/TimeMachineService.java`
- `src/main/java/com/app/tracker/timemachine/TimeMachineController.java`
- `src/test/java/com/app/tracker/timemachine/TimeMachineIntegrationTest.java`
