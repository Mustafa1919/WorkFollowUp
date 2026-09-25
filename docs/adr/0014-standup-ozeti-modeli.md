# ADR-0014: Toplantısız standup (async check-in) modeli

- **Durum:** Kabul edildi (Ürünleştirme Dalga 2.3 — V29, 2026-09-25)
- **İlgili:** ADR-0006 (bildirim teslim semantiği — Inbox+Slack burada AYNEN uygulanır), ADR-0012
  (Aging WIP — "takılan işler" burada `task_aging_alerts`'ten okunur)

## Bağlam

Plan, günlük standup toplantılarının yerini alacak bir async özet istiyordu: her katılımcı için
"dün ne yaptım / şimdi ne yapıyorum / neyle blokluyum" sorularına otomatik cevap. Cevaplanması
gereken: idempotency nasıl sağlanır (ProcessedEventStore mı, yoksa kendi durumu mu), "olgular"
(facts) nereden okunur, "dün" hangi zaman dilimi/hangi gün, LLM katmanı olacak mı.

## Karar

1. **Idempotency `standup_digests`'in kendi PRIMARY KEY'i ile** (`meeting_id, occurrence_date,
   user_id`) + `ON CONFLICT DO NOTHING` — `MeetingReminderService`'in ayrı bir
   `ProcessedEventStore` kaydı tutmasından FARKLI: burada "olay işlendi" değil "durum zaten var"
   sorusu sorulur, tablo kendisi zaten bu bilgiyi taşıyor. Ekstra bir idempotency tablosuna gerek
   yok.
2. **Altı "olgu" kategorisi, hepsi kullanıcıya ATANMIŞ görevler üzerinden:**
   - *Dün tamamlanan*: `task_analytics.done_at` önceki iş gününde.
   - *Dün ilerleyen*: `task_events` içinde önceki iş günü `status_changed`, yeni durum Done
     DEĞİL (Done olanlar zaten ilk kategoride).
   - *Şu an devam eden*: `tasks.status = 'In Progress'` (zaman dilimine bağlı değil, ANLIK).
   - *Bloklanan*: `task_dependencies`'te açık (Done olmayan) bir blocker'ı olan görevler.
   - *Takılan (Aging WIP)*: `task_aging_alerts`'te (ADR-0012) zaten işaretli görevler — YENİDEN
     p85 hesaplamaz, mevcut sonucu okur.
   - *GitHub aktivitesi*: `task_events`'te aktörü webhook sistem kullanıcısı (`IntegrationActor`)
     olan, önceki iş günündeki değişiklikler.
3. **"Dün" = ÖNCEKİ İŞ GÜNÜ** (hafta sonu atlanır), iş saat dilimindeki (`Clock` business zone)
   gün sınırlarıyla hesaplanır — Pazartesi günü bir standup için "dün" Cuma'dır.
4. **Alıcı kuralı `MeetingReminderService` ile AYNI** (ADMIN/MANAGER/DEVELOPER, VIEWER hariç) —
   toplantı katılımcısı kavramı v1'de yok, tüm standart üyeler alır.
5. **Tetikleme occurrence'tan TAM 30 dakika önce, `reminderMinutesBefore`'dan BAĞIMSIZ** — bir
   toplantının hatırlatması kapalıyken bile standup özeti çalışabilir (ikisi farklı anahtarlar:
   `standupEnabled` ayrı bir bayrak, `reminderMinutesBefore` ile karıştırılmaz).
6. **LLM katmanı v1'de YOK.** `facts` yapılandırılmış (JSONB) tutulur ki ileride isteğe bağlı bir
   özetleyici bunu girdi olarak kullanabilsin; insan-okunur sunum (bölüm başlıkları, ikonlar)
   Activity sekmesiyle AYNI ilkeyle FRONTEND'de yapılır.
7. **Okuma (`GET /standups`) rol sınırsız, kendi notunu güncelleme (`PUT .../note`) SAHİPLİK
   servis katmanında** — `WHERE user_id = :callerId` (SavedView'ın "ADMIN istisnası bile yok"
   deseniyle aynı sıkılıkta): başka birinin satırı asla güncellenemez, hedef satır yoksa 404.

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| Ayrı `ProcessedEventStore` kaydı ile idempotency | `standup_digests`'in kendi PK'si zaten aynı garantiyi veriyor, ekstra tablo gereksiz. |
| "Dün" = takvim günü (hafta sonu dahil) | Pazartesi standup'ı Cumartesi/Pazar'ı "dün" sayardı — bu günlerde iş genelde yok, boş/yanıltıcı bir bölüm üretirdi. |
| Aging WIP'i standup içinde YENİDEN hesaplamak | `task_aging_alerts` zaten güncel (saatlik job); aynı p85 hesabını iki yerde tekrarlamak tutarsızlık riski taşır (ADR-0012'nin "paylaşılan fonksiyon" ilkesiyle çelişirdi). |
| Toplantı katılımcı listesi (invite) eklemek | v1 kapsamı dışında (Meeting'in kendisi de katılımcı listesi tutmuyor); mevcut rol tabanlı alıcı kuralı yeterli. |
| Facts'i insan-okunur metin olarak saklamak | Çok dilli/format değişikliği ihtiyacı çıkarsa geriye dönük veri bozulurdu; yapılandırılmış veri hem LLM hem UI için daha esnek (Activity sekmesiyle aynı karar). |

## Sonuçlar

**Olumlu**
- Bildirim gürültüsü yok: aynı occurrence için ikinci bir çalıştırma sessizce hiçbir şey
  üretmez.
- Aging WIP ve Cycle Time ile aynı veri kaynaklarını kullanır — standup, analitikle ÇELİŞMEZ.

**Olumsuz / kabul edilen bedel**
- "GitHub aktivitesi" ve "dün ilerleyen" pencereleri {@code task_events.created_at} DB
  `NOW()`'una dayanır; JVM/DB saat farkı sınırdaki bir olayı yanlış güne atabilir (ADR-0002'nin
  aynı bilinen sınırı, Sprint kesitleri için de geçerliydi).
- Toplantı katılımcı listesi olmadığından, workspace'e yeni katılan ama o meeting'le hiç ilgisi
  olmayan bir DEVELOPER de standup alır — gürültü, ama kabul edilebilir (V18'in ilk halinin aynı
  ödünüydü).
- Facts sorguları (6 ayrı native SQL) her katılımcı için TEK TEK çalışır (N+1 tarzı, katılımcı
  sayısı x 6 sorgu); günde bir kez çalışan bir job için kabul edilebilir, saatlik/dakikalık bir
  işlem olsaydı toplu sorguya çevrilmesi gerekirdi.

## Faz 4 notu

Standup, `task_aging_alerts` ve `task_dependencies`'e (çekirdek görev modülü) bağımlı; bildirim
ayrı bir servise çıkarsa bu okuma bağımlılıkları ya senkron API çağrısına ya da ayrı bir read
model'e dönüşmeli (ADR-0008'in Faz 4 notuyla aynı ödünç).

## Referanslar

- `src/main/resources/db/migration/V29__standup_digests.sql`
- `src/main/java/com/app/tracker/standup/StandupFactsRepository.java`
- `src/main/java/com/app/tracker/standup/StandupDigestService.java`
- `src/main/java/com/app/tracker/standup/StandupDigestJob.java`
- `src/test/java/com/app/tracker/standup/StandupDigestServiceIntegrationTest.java`
