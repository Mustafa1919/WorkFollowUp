# ADR-0020: Bağımlılık artık bilgilendirici değil — bloklayan bitmeden bloklanan Done olamaz

- **Durum:** Kabul edildi (Dalga 5 planı, 2026-10-26 — kod henüz yazılmadı)
- **Değiştirdiği karar:** `TaskDependencyService` sınıf javadoc'undaki "sadece BİLGİLENDİRİCİ (durum
  geçişini engellemez)" kararı (RAKIP_ANALIZI.md Bölüm 3 kapsamında, ADR'siz alınmıştı). Kod
  yazıldığında o javadoc bu ADR'ye referansla güncellenir.
- **İlgili:** ADR-0016 (otomasyon — `PR_MERGE_TO_DONE`, `BLOCKER_DONE_NOTIFY`), ADR-0004 (webhook
  aktörü), `docs/DALGA_5_KULLANILABILIRLIK.md`

## Bağlam

Sunucu denemesinde bir görevi bloklayan görev bitmeden bloklanan görevin Done'a taşınabildiği
görüldü. Bu bir kod hatası değil, eski bir kapsam kararının sonucu: bağımlılık bilerek
"bilgilendirici" tutulmuştu ve `TaskService.updateStatus` hiçbir bağımlılık kontrolü yapmıyor.
Kullanıcı gözlemi bu kararın üründe yanlış olduğunu gösteriyor: "X, Y'yi blokluyor" bilgisi
kapanışı engellemiyorsa anlamı yok.

Kod incelemesi: Done'a giden bütün yollar tek metottan geçiyor — `TaskService.updateStatus`.
Çağıranlar: `TaskController` (UI), `BulkTaskService` (toplu işlem, tek tx), `GithubEventProcessor`
(PR merge, Kafka tüketicisi) ve `AutomationRuleEngine` (yalnız IN_PROGRESS ve REVIEW üretir,
Done üretmez).

## Karar

1. **Kural:** bloklanan görev, silinmemiş ve durumu Done OLMAYAN en az bir bloklayanı varken Done'a
   geçemez. "Tamamlandı" = **Done**; bloklayanın onaylanması (approval) beklenmez — onay bir kalite
   kapısıdır, onu beklemek akışı gereksiz kilitler.
2. **Yalnız Done geçişi kapanır.** Bloklu görev To Do / In Progress / Review arasında serbestçe
   gezebilir (hazırlık işi başlayabilsin). Onay zaten yalnız Done görevde mümkün olduğu için ayrıca
   kontrol gerekmez.
3. **Kontrol `TaskService.updateStatus`'un İÇİNDE**, yalnız `newStatus == DONE` iken tek bir
   `EXISTS` sorgusuyla yapılır (`TaskDependencyRepository.hasOpenBlocker(taskId)`). Böylece UI, toplu
   işlem ve gelecekteki her yol aynı kuraldan geçer. İhlal → `BusinessRuleException` (400); mesaj
   açık bloklayanların görev anahtarlarını listeler.
4. **GitHub PR merge istisnası: reddetme, atla ve bildir.** `GithubEventProcessor` bir Kafka
   tüketicisidir; `updateStatus` exception fırlatırsa `DefaultErrorHandler` olayı yeniden dener ve
   sonunda `task.events-dlt`'ye düşürür — kalıcı bir iş kuralı ihlali geçici hata gibi ele alınmış
   olur. Bu yüzden işlemci Done'a geçirmeden ÖNCE aynı `hasOpenBlocker` kontrolünü yapar; bloklu ise
   durumu değiştirmez, olayı işlenmiş olarak işaretler ve `TASK_TRANSITION_BLOCKED` outbox olayı
   yazar. Inbox bu olaydan atanan + izleyicilere "PR merge edildi ama görev bloklu olduğu için Done'a
   taşınmadı" bildirimi üretir.
5. **Bitmiş göreve bitmemiş bloklayan eklenemez.** `TaskDependencyService.link`, bloklanan Done iken
   bloklayan Done değilse reddeder (aksi halde kural ihlali olan bir durum yaratılmış olur).
   Bloklayan da Done ise bağlantı kurulabilir (geçmişe dönük kayıt).
6. **Zincirleme geri açma YOK.** Bloklayan, bloklanan Done olduktan sonra yeniden açılırsa
   bloklanan Done kalır. Bu sayede eşzamanlılık için kilit de gerekmez: "bloklanan Done'a geçerken
   bloklayan aynı anda yeniden açıldı" yarışının sonucu, kuralın zaten izin verdiği durumla aynıdır.
7. **Silinmiş (soft delete) bloklayan yok sayılır** — sorgu `deleted_at IS NULL` filtresi taşır
   (native SQL'de `@SQLRestriction` uygulanmaz, filtre elle yazılır).
8. **Toplu işlem:** bir görev bloklu ise mevcut tek-tx kuralı gereği tümü geri alınır; hata mesajı
   bloklu görevi adıyla söyler.
9. **Frontend:** board'da bloklu görevin kartında kilit rozeti; Done sütununa sürüklenince istek
   gönderilir, 400 yanıtındaki bloklayan listesi toast'ta gösterilir (istemci tarafı ön kontrol
   yalnız kolaylık, kural backend'de).

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| Bilgilendirici kalsın, yalnız uyarı göstersin | Kullanıcının gözlemi tam olarak bunun yetmediği. |
| "Tamamlandı" = onaylanmış | Onay gecikmesi, bitmiş işe bağlı her görevi gereksiz bekletir. |
| Bloklu görev In Progress'e de geçemesin | Hazırlık işini engeller; birçok araç (Jira, Linear) bunu engellemez. |
| PR merge'de de exception fırlatmak | Kafka retry + DLT'ye kalıcı iş kuralı ihlali düşer; operasyonel gürültü, kullanıcıya görünmez. |
| Bloklayan yeniden açılınca bloklananı da geri açmak | Sürpriz durum değişikliği, otomasyon zinciri ve kilit gerektirir; bilgi değeri düşük. |
| Kontrolü controller'a koymak | Toplu işlem ve GitHub yolu atlar; tek giriş noktası servis metodu. |

## Sonuçlar

**Olumlu**
- Bağımlılık gerçek bir anlam kazanır; `BLOCKER_DONE_NOTIFY` otomasyonu artık "artık kapatabilirsin"
  sinyali olur.
- Kural tek noktada, bütün yolları kapsıyor.

**Olumsuz / kabul edilen bedel**
- Done geçişinde ek bir `EXISTS` sorgusu (indeksli, ihmal edilebilir).
- PR merge'ün Done'a taşımadığı durum kullanıcıya yalnız Inbox ile anlatılır; bildirimi kaçıran
  biri görevi açık görür.
- Mevcut veride "Done ama bloklayanı açık" görevler olabilir; kural geriye dönük uygulanmaz,
  yalnız yeni geçişleri etkiler.
- Bağımlılık ekleme geliştiriciye açık (`TASK_EDIT`, ADR-0019); geliştirici başkasının görevini
  bloklayabilir. Kabul edildi; kötüye kullanım görülürse `TASK_PLAN`'a taşınır.

## Referanslar

- `src/main/java/com/app/tracker/task/service/TaskService.java#updateStatus`
- `src/main/java/com/app/tracker/dependency/service/TaskDependencyService.java#link`
- `src/main/java/com/app/tracker/integration/service/GithubEventProcessor.java`
- `src/main/java/com/app/tracker/task/service/BulkTaskService.java`
- `src/main/java/com/app/tracker/notification/service/NotificationMessageFormatter.java`
