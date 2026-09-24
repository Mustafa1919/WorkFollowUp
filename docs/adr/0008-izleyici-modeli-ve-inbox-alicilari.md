# ADR-0008: İzleyici modeli ve Inbox alıcı tanımı

- **Durum:** Kabul edildi (Ürünleştirme Dalga 1.1 — V22, 2026-09-24)
- **İlgili:** ADR-0006 (teslim semantiği değişmedi; yalnız alıcı kümesi değişti)

## Bağlam

V18'de Inbox alıcıları rol bazlıydı: bir görevdeki her durum/onay/tarih değişikliği, workspace'in
**tüm** ADMIN/MANAGER/DEVELOPER üyelerine gidiyordu. Görevin sahibi yoktu (`tasks.assignee_id`
kolonu V1'den beri duruyordu ama hiçbir kod yolu kullanmıyordu). İki kişilik bir takımda bu
kabul edilebilirdi; ürün başka ekiplere açılınca her üye her görevin bildirimini alır ve Inbox
gürültüye döner.

Cevaplanması gerekenler: görevin kaç sahibi olur, bildirimi kim alır, yıllardır açık duran eski
görevler ne olur, alıcı listesi hangi anda belirlenir?

## Karar

1. **Tek atanan.** `tasks.assignee_id` entity'ye bağlandı. Atanan kişi, görevin workspace'inde
   VIEWER dışı bir üye olmalı (`workspace_users` RLS'siz olduğu için kontrol serviste, workspace
   id açıkça verilerek yapılır).
2. **İzleyiciler (`task_watchers`).** Görevi oluşturan kişi ve her yeni atanan, aynı transaction
   içinde otomatik izleyici olur. Her üye (VIEWER dahil) elle izleyebilir veya izlemeyi
   bırakabilir. İzlemek görevi değiştirmediği için rol sınırı yoktur.
3. **Alıcı = izleyiciler − aktör**, yalnız hâlâ workspace üyesi olanlar.
   `TASK_ASSIGNED` olayı yeni atanana izleyici listesinden bağımsız olarak **her zaman** ve
   kişisel metinle ("Görev size atandı.") gider; diğer izleyiciler genel metni alır.
4. **Liste tüketim anında okunur**, olay anında değil. Olay ile tüketim arasında izlemeyi bırakan
   kişi bildirimi almaz, yeni izlemeye başlayan alır.
5. **Geçiş (backfill):** V22 öncesi açılmış görevlerin oluşturanı bilinmiyor. İzleyicisiz kalsalar
   Inbox bu görevler için tamamen susardı. Bu yüzden eski davranış, izleyici satırı olarak
   korundu: her eski görev için workspace'in ADMIN/MANAGER/DEVELOPER üyeleri izleyici yazıldı.
   Kullanıcılar istemediklerini "izlemeyi bırak" ile çıkarır.
6. **Açıklama olay yüküne konmaz.** `TASK_DESCRIPTION_CHANGED` yalnız kimlik taşır; `task_events`'e
   içerik değil eski/yeni uzunluk yazılır. Liste yanıtları da açıklamayı taşımaz; ayrı bir
   `GET /tasks/{id}/detail` ucu vardır.

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| Çoklu atanan (join tablosu) | Sorumluluk bulanıklaşır; "Benim işlerim" ve standup özeti tek sahibe dayanıyor. |
| İzleyicisiz: yalnız atanan + mention | Oluşturan kişi kendi açtığı görevin ilerleyişini kaçırır; VIEWER bir görevi takip edemez. |
| Rol bazlı yayını korumak | Kullanıcı sayısı arttıkça gürültü doğrusal büyür; Inbox'ın değeri kaybolur. |
| Alıcıları olay anında payload'a yazmak | Olay boyutu izleyici sayısıyla büyür; ayrıca "izlemeyi bıraktım ama hâlâ bildirim geliyor" durumu uzar. |
| Backfill yapmamak | Eski görevlerde bildirim sessizce durur — kullanıcı bunu hata olarak algılar. |
| Açıklamayı `task_events`'e tam yazmak | Append-only, partition'lı tabloda 20.000 karakterlik tekrarlar; Activity sekmesi için uzunluk yeterli. |

## Sonuçlar

**Olumlu**
- Bildirim hacmi takım büyüklüğüyle değil, görevle ilgili kişi sayısıyla ölçeklenir.
- "Benim işlerim", standup özeti ve takılan iş uyarısı (Dalga 2) için sahiplik verisi hazır.

**Olumsuz / kabul edilen bedel**
- Backfill yüzünden eski görevler hâlâ geniş bir izleyici kümesine sahip; zamanla kullanıcılar
  temizler.
- Workspace'ten çıkarılan kişinin izleyici satırları ve atamaları silinmez. Bildirimler
  üyelik filtresiyle kesilir; atanan alanı "Eski üye" olarak görünür.
- Backfill, migration kullanıcısının RLS'i bypass ettiğini varsayar (bootstrap superuser).
  Yönetilen bir DB'de migrator'a `BYPASSRLS` verilmezse backfill 0 satır yazar.

## Faz 4 notu

İzleyici çözümlemesi Inbox consumer'ı içinde, çekirdek tablodan (`task_watchers`) okunuyor.
Bildirim servisi ayrıştırılırsa ya izleyici değişiklikleri olay olarak yayınlanıp bildirim
servisinde bir read model kurulmalı ya da bu okuma senkron bir API çağrısına dönüşür.
İlki tercih edilmeli (çekirdek API'ye çalışma zamanı bağımlılığı eklemez).

## Referanslar

- `src/main/resources/db/migration/V22__task_description_assignee_watchers.sql`
- `src/main/java/com/app/tracker/task/service/TaskService.java` (`assign`, `updateDescription`, `watch`)
- `src/main/java/com/app/tracker/task/repository/TaskWatcherRepository.java`
- `src/main/java/com/app/tracker/notification/service/InboxFanoutService.java`
- `src/test/java/com/app/tracker/task/TaskAssignmentAndWatchIntegrationTest.java`
