# ADR-0009: Yorum ve mention modeli

- **Durum:** Kabul edildi (Ürünleştirme Dalga 1.2 — V23, 2026-09-24)
- **İlgili:** ADR-0008 (izleyici modeli ve Inbox alıcı tanımı buraya genişletildi)

## Bağlam

Faz 6 §1 yorumları tasarlamıştı ama kodlanmamıştı (bkz. Hedefler.md açık nokta). Ürünleştirme
planının 1.2 dilimi: tek seviye yorumlar (thread yok), `@[userId]` mention sözdizimi, düzenleme/
silme ve bunların Inbox'a bağlanması. V22'nin izleyici modeli (ADR-0008) zaten "izleyiciler −
aktör" alıcı tanımını kurmuştu; buradaki asıl soru bunun yorum/mention'a nasıl genişleyeceği ve
aynı yoruma iki kez bildirim gitmesinin nasıl önleneceğiydi.

## Karar

1. **Tek seviye, soft delete.** `comments` tablosu `task_watchers`/`tags` ile AYNI desen: RLS+
   FORCE, V6 fail-closed `NULLIF` politikası, workspace_id denormalize. Silinen yorumun **gövdesi
   DB'de korunur** (`deletedAt` dolar); "[silindi]" maskesi yalnız API katmanında
   (`CommentResponse#from`) uygulanır — TaskService'in soft delete'i (V16) ile aynı ilke: geçmiş
   veri kaybolmaz, yalnız görünürlüğü kısılır.
2. **Mention sözdizimi `@[<uuid>]`, serbest isim eşleştirme YOK.** `MentionParser` saf bir regex
   ayrıştırıcıdır (I/O yok); bulduğu ham UUID'ler `CommentService#resolveMentions` içinde
   workspace üye listesine karşı doğrulanır, üye olmayanlar ve aktörün kendisi (self-mention)
   elenir, en fazla 20 mention işlenir (`MentionParser.MAX_MENTIONS`).
3. **İki ayrı outbox olayı: `COMMENT_ADDED` ve `COMMENT_MENTION`.** İkisi de aynı sözleşmeyi taşır:
   `{taskId, projectId, actorId, commentId, mentionedUserIds}`. `COMMENT_ADDED` her yorumda yazılır
   (mention olsun olmasın); `COMMENT_MENTION` YALNIZ geçerli mention varsa, yorum başına TEK olay
   olarak yazılır. Bu sözleşme SABİTTİR — paralel ilerleyen e-posta dilimi (1.3) aynı alanlara
   bağımlı. Düzenleme `COMMENT_UPDATED`, silme `COMMENT_DELETED` yazar (aynı zarf,
   `mentionedUserIds` boş): yalnız diğer sekmelerde canlı yenileme içindir, bildirim tüketicileri
   bu tipleri yok sayar.
4. **Çift bildirim, olay bazlı dışlama ile önlenir — sıraya bağımlı DEĞİL.**
   `InboxFanoutService`, `COMMENT_ADDED`'in alıcılarını hesaplarken (izleyiciler − aktör) kendi
   payload'ındaki `mentionedUserIds`'i ayrıca çıkarır; `COMMENT_MENTION`'ın alıcıları ise SADECE
   `mentionedUserIds`'tir (izliyor olsun olmasın, `TASK_ASSIGNED`'in yeni atananı gibi "her
   zaman"). Her iki olay da KENDİ payload'ından karar verdiği için, Kafka'da hangisinin önce
   tüketildiği sonucu etkilemez — ayrı bir "bu yorum için zaten bildirdim mi" tablosuna gerek
   yoktur.
5. **Otomatik izleme yorumda da geçerli.** Yorum yazan ve geçerli mention edilenler aynı
   transaction içinde izleyici olur (`TaskWatcherRepository.watch`) — V22'nin izleyici modeli
   birebir yeniden kullanılır, yeni bir tablo/desen icat edilmedi.
6. **Düzenlemede yalnız YENİ mention'lar bildirilir.** Eski gövde yeniden ayrıştırılıp yeni
   gövdeyle karşılaştırılır (`oldMentions`/`newMentions` fark kümesi); zaten var olan bir mention'ı
   tekrar bildirmek gürültüdür. Mention listesi ayrı bir kolonda SAKLANMAZ, her ihtiyaçta gövdeden
   yeniden türetilir (append-only `task_events`'in aksine, `comments.body` zaten güncel halin tek
   kaynağıdır).
7. **Silme yetkisi: yazan VEYA workspace ADMIN.** Kontrol `CommentService` içinde yapılır (ADMIN
   rolü `workspace_users`'tan okunur, RLS'siz — `TaskService#assign` ile aynı gerekçe); controller
   seviyesinde ek bir `@PreAuthorize` YOK, çünkü rastgele bir `commentId` zaten servis katmanında
   404 ile kısa devre yapar (yetkisiz erişim ile "yorum yok" ayrımı dışarıya sızdırılmaz).
8. **Onaylı/silinmiş görev.** Onaylı (Done, kilitli) bir göreve yorum yazılabilir — yorum iş
   verisini (durum/tarih/atanan) DEĞİŞTİRMEZ, TaskService'teki "onaylı görev kilitli" kuralının
   kapsamı dışındadır. Silinmiş bir göreve yorum yazılamaz/okunamaz (`TaskService#requireTask` ile
   AYNI filtre: `@SQLRestriction` + ikinci savunma).
9. **`task_events`'e yalnız kimlik yazılır.** `comment_added` tipi yalnız `commentId` taşır,
   gövde değil — `description_changed`'in "yalnız uzunluk" ilkesiyle aynı gerekçe (append-only,
   partition'lı tabloyu şişirmemek). Düzenleme/silme `task_events`'e YAZILMAZ (yorumun kendisi
   zaten kalıcı bir kayıt; Activity sekmesi (Dalga 1.5) gerekirse `comments.updated_at`/
   `deleted_at`'ten okuyabilir).

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| Serbest metinde `@Ad Soyad` eşleştirme | İsim değişince veya iki kullanıcı aynı adı taşıyınca sessizce bozulur. |
| Tek olay (`COMMENT_ADDED` içinde mention bilgisiyle) | Alıcı hesaplama mantığı (izleyici vs. "her zaman") ayrışıyor; tek olayda iki farklı alıcı kuralını uygulamak `format()`'ı ve fanOut'u karmaşıklaştırırdı. |
| "Bu yorum için zaten bildirdim mi" ara tablosu | Gereksiz durum: olay bazlı dışlama (madde 4) aynı sonucu sıraya bağımlı olmadan, ek tabloya gerek kalmadan verir. |
| Mention listesini `comments`'e ayrı kolon olarak yazmak | Düzenlemede fark kümesi zaten gövdeden çıkarılabiliyor; ayrı kolon senkron tutma yükü ekler, tek okuyucusu (fark hesabı) için gereksiz. |
| Silinen yorumun gövdesini DB'den de silmek | V16'nın soft delete ilkesiyle tutarsız olurdu; ADMIN'in "neden silindi" denetimi imkansızlaşırdı. |
| Onaylı göreve yorum yazımını kilitlemek | Yorum iş verisini değiştirmiyor; TaskService'in kilit kapsamı (durum/tarih/atanan/açıklama) yorum için anlamsız bir kısıtlama olurdu. |

## Sonuçlar

**Olumlu**
- Mention alt yapısı e-posta dilimi (1.3) ve Activity sekmesi (1.5) için hazır bir sözleşme
  bırakıyor; her ikisi de `COMMENT_MENTION`'ın `mentionedUserIds` alanını doğrudan tüketebilir.
- Çift bildirim riski, ek durum tutmadan (sıraya bağımlı olmayan, olay-yerel bir kural ile)
  kapatıldı.

**Olumsuz / kabul edilen bedel**
- Düzenlemede mention farkını hesaplamak için eski gövde yeniden ayrıştırılır — yorum çok sık
  düzenlenirse (beklenmiyor) hafif bir CPU maliyeti.
- Silinen yorumların gövdesi DB'de kalıcı olarak durur; bir "gerçek silme" (GDPR/KVKK
  anlamında) ihtiyacı çıkarsa ayrı bir mekanizma (Dalga 4.3) gerekir.

## Faz 4 notu

Mention çözümlemesi (`workspace_users` okuma) ve izleyici otomasyonu çekirdek tablolardan
senkron okunuyor — webhook kimlik modeli (ADR-0004) ve izleyici modeli (ADR-0008) ile aynı
sınır: bildirim servisi ayrıştırılırsa bu okuma ya bir read model'e ya da senkron bir API
çağrısına dönüşmeli; ilki tercih edilmeli.

## Referanslar

- `src/main/resources/db/migration/V23__comments.sql`
- `src/main/java/com/app/tracker/comment/service/CommentService.java`
- `src/main/java/com/app/tracker/comment/service/MentionParser.java`
- `src/main/java/com/app/tracker/notification/service/InboxFanoutService.java`
- `src/main/java/com/app/tracker/notification/service/NotificationMessageFormatter.java`
- `src/test/java/com/app/tracker/comment/CommentIntegrationTest.java`
