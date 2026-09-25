# ADR-0012: Aging WIP eşik modeli (takılan iş uyarısı)

- **Durum:** Kabul edildi (Ürünleştirme Dalga 2.1 — V28, 2026-09-25)
- **İlgili:** ADR-0002 (Cycle Time/percentile tanımları burada da temel alınır)

## Bağlam

Rakip analizi (Jira/Linear) "takılan iş" (stale/aging work item) uyarısını farklılaştırıcı bir
özellik olarak işaretlemişti. Sorulması gereken: bir görev ne zaman "normalden uzun sürüyor"
sayılır, bu eşik projeden projeye nasıl değişir, aynı uyarı tekrar tekrar mı gönderilir?

## Karar

1. **Eşik projenin kendi p85 cycle time'ıdır**, sabit bir gün sayısı (örn. "5 günden fazla açık")
   DEĞİL. `ProjectMetricsRepository.cycleTimeStats` (ADR-0002'nin percentile sorgusu) yeniden
   kullanılır — her proje kendi tempoısına göre ölçülür, yeni kurulan bir proje ile olgun bir
   proje aynı sabit sayıyla karşılaştırılmaz.
2. **En az 10 tamamlanmış görev şartı** (`AgingWipLevels.MIN_SAMPLE_SIZE`). Azlık örneklemde p85
   istatistiksel olarak anlamsızdır; şart sağlanmazsa rozet/bildirim HİÇ üretilmez (sessizce
   yanlış eşik göstermek, hiç göstermemekten kötüdür).
3. **İki seviye: p85 ve 2×p85.** Tek eşik "az önce geçti" ile "aylardır unutuldu" görevini
   ayıramaz; ikinci seviye ciddiyet farkını UI'da (rozet rengi) ve bildirim metninde taşır.
4. **Eşik hesabı `AgingWipLevels`'ta paylaşılan SAF bir fonksiyondur** — hem okuma API'sinin
   (`GET /projects/{id}/flow/aging`, rozet) hem bildirim job'inin (`AgingWipJob`, saatlik) AYNI
   tanıma dayanması zorunlu; ikisi ayrı yerlerde hesaplansaydı zamanla sürüklenip rozet ile
   bildirim çelişebilirdi.
5. **Bildirim yalnız seviye YÜKSELDİĞİNDE gider** (`task_aging_alerts`, V28). Aynı seviyede
   tekrar tekrar bildirim gitmez; seviye düşünce (görev normale döndü, Done oldu, reopen sonrası
   henüz genç) kayıt temizlenir ki ileride yeniden eşiği aşarsa bildirim TEKRAR gidebilsin.
6. **"Açık gorev" tanımı `task_analytics`'ten:** `done_at IS NULL AND first_in_progress_at IS NOT
   NULL` — Cycle Time'ın "reopen sonrası tamamlanmış sayılmaz" kuralıyla (bkz. `TaskAnalytics`)
   BİREBİR aynı veri kaynağı; ayrı bir "açık görev" tanımı icat edilmedi.
7. **Alıcı = atanan + izleyiciler** (ADR-0008 ile aynı işleyiş), workspace üyeliği filtrelenir.
   Aktör kavramı yoktur (insan tetiklemez, job tetikler), bu yüzden "aktör hariç" filtresi
   uygulanmaz.

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| Sabit eşik (örn. "3 günden fazla In Progress") | Proje temposunu yok sayar; hızlı bir Kanban takımı için 3 gün çok uzun, yavaş bir takım için çok kısa olabilir. |
| Tek seviye (yalnız p85) | "Az önce geçti" ile "aylardır unutuldu" aynı rozeti alır, önceliklendirme sinyali kaybolur. |
| Her çalışmada bildirim (idempotency yok) | Saatlik job'la birleşince aynı görev için günde 24 bildirim — Inbox gürültüye döner. |
| Ayrı bir tablo yerine `task_analytics`'e `alert_level` kolonu eklemek | `task_analytics` bir read model'dir (olay güdümlü projector yazar); job'un yazdığı bir "yan durum" onunla karışırsa hangi yazarın otorite olduğu belirsizleşir. Ayrı, küçük bir tablo (`task_tags`/`task_watchers` ile aynı desen) daha temiz. |

## Sonuçlar

**Olumlu**
- Rozet (okuma) ve bildirim (job) her zaman tutarlı: aynı fonksiyon, aynı p85.
- 10 örnek şartı yeni/küçük projelerde yanlış alarmı önler.

**Olumsuz / kabul edilen bedel**
- p85 zamanla değişir (yeni tamamlanan görevler istatistiği kaydırır); bir görev bir çalıştırmada
  seviye 1 iken p85 düşünce (proje genel olarak yavaşlarsa) aynı görev sonraki çalıştırmada
  seviye 0'a dönebilir — bu KABUL EDİLMİŞ bir davranış, eşik dinamik olduğu için beklenir.
- Silinmiş (soft delete) bir görev `task_analytics`'te hâlâ "açık" görünebilir (Task'ın
  `deleted_at`'i `task_analytics`'e yansımaz) — V19/webhook kimlik modeliyle aynı "bilerek dar
  kapsam" deseni; bu ADR'de ayrıca ele alınmadı, bilinen bir sınır olarak bırakıldı.
- Job saatlik çalıştığı için bildirim eşik aşımından en fazla ~1 saat gecikebilir; okuma API'si
  (rozet) ise anlık hesaplanır — ikisi arasında kısa bir "rozet var ama bildirim henüz gitmedi"
  penceresi olabilir, zararsız kabul edildi.

## Faz 4 notu

Job "Tenant-Iterating" deseniyle (`SprintAnalyticsReconciliationJob`/`MeetingReminderJob` ile
aynı) çalışıyor; analitik ayrı bir servise çıkarsa bu job da onunla birlikte taşınmalı (zaten
`ProjectMetricsRepository`'ye bağımlı).

## Referanslar

- `src/main/resources/db/migration/V28__task_aging_alerts.sql`
- `src/main/java/com/app/tracker/flow/AgingWipLevels.java`
- `src/main/java/com/app/tracker/flow/service/AgingWipService.java`
- `src/main/java/com/app/tracker/flow/service/AgingWipAlertService.java`
- `src/main/java/com/app/tracker/flow/service/AgingWipJob.java`
- `src/test/java/com/app/tracker/flow/AgingWipIntegrationTest.java`
