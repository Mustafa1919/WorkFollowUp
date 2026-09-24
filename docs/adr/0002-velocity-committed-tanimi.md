# ADR-0002: Velocity'de `committed` = sprint kapanış kesiti

- **Durum:** Kabul edildi (Faz 3, Dilim 3.3 — 2026-09-20)
- **İlgili:** ADR-0007 (rapor, sprint özetini bu read model'den alır)

## Bağlam

PHASE_3 Bölüm 1.1 Velocity ve Spillover ister ama "taahhüt edilen iş"i tanımlamaz. İki makul
tanım var:

- **Başlangıç kesiti:** sprint başladığı anda sprint'te olan işler (Scrum'daki klasik
  "commitment", Jira'nın sprint raporu).
- **Kapanış kesiti:** sprint kapandığı anda sprint'te olan işler.

Ayrıca değerler **hangi kaynaktan** kurulacak: `tasks` tablosunun anlık hali mi, `task_events`
tarihçesi mi?

## Karar

| Alan | Tanım |
|---|---|
| `committed_tasks/points` | Sprint **kapandığı anda** sprint üyesi olan tüm görevler — sprint ortasında eklenenler **dahil**. |
| `completed_tasks/points` | Bunların kesitte `Done` olanları. Velocity = `completed_points`. |
| Spillover | `committed − completed`. |
| `spillover_rate` | `(committed_points − completed_points) / committed_points`; **puan** bazlı; `committed_points = 0` ise `NULL`. |
| Puansız görev | 0 puan; bu yüzden görev sayıları ayrıca tutulur. |

Kesit kuralları:

- Kaynak `task_events` tarihçesidir, `tasks`'ın anlık değeri **değil**. Sprint üyeliği, durum ve
  story point için `created_at <= kesit` olan **son** olay geçerlidir; olay yoksa `To Do` / 0 puan.
- Kesit zamanı `SPRINT_COMPLETED` payload'ındaki `completedAt`'tir, envelope `timestamp`'i değil.
- Bu yüzden `sprint_changed`, `status_changed`, `story_point_changed` olaylarının tarihçeye
  yazılması **zorunludur**; yazılmayan değişiklik velocity'ye yansımaz.
- Sonuç `sprint_analytics` (V12) read model'ine yazılır; FK yok, RLS+FORCE var.
- Gece 03:45'te uzlaştırma job'ı, satırı olmayan tamamlanmış sprint'leri hesaplar. Hesap
  deterministik olduğundan olay yolu ile çakışması zararsızdır (upsert).

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| Başlangıç kesiti | Scope creep'i ölçmek için daha doğru; ama "başlangıçta vardı, sonra çıkarıldı" işleri ve iki ayrı kesit gerektirir. Dilim 3.3 kapsamı dışında bırakıldı; **ileride ek kolonla** (`initial_committed_*`) eklenebilir, bu karar onu engellemez. |
| `tasks` tablosunun anlık hali | Sprint kapandıktan sonra yapılan taşıma/puan değişikliği geçmiş velocity'yi değiştirirdi; rapor tekrar üretilemez olurdu. |
| Envelope `timestamp`'i kesit olarak | Outbox relay gecikmesi kesiti kaydırır; iş anlamı taşıyan zaman payload'dadır. |

## Sonuçlar

**Olumlu**
- Aynı girdiyle aynı sonuç: yeniden hesaplama, DLT replay ve uzlaştırma güvenli.
- Sprint kapandıktan sonraki düzenlemeler geçmişi bozmaz.

**Olumsuz / kabul edilen bedel**
- Sprint ortasında eklenen iş "taahhüt" sayılır; velocity ve spillover, Jira'nın
  commitment-bazlı raporundan **farklı sayı** verir. Kullanıcıya gösterilen arayüzde bu tanım
  açıkça yazılmalı.
- **Saat kaynağı farkı:** `completedAt` JVM saatinden (`Instant.now()`,
  `SprintService#completeSprint`), `task_events.created_at` DB `NOW()`'ından gelir. Pod ile DB
  arasındaki saat kayması, kesite çok yakın bir olayı yanlış tarafa düşürebilir.
- Kesitten önce başlayıp kesitten sonra commit eden bir transaction'ın olayı kaçabilir
  (`NOW()` = transaction başlangıcı).

## Faz 4 notu

Read model FK'sız ve kendi kendine yeterli (`sprint_name`, `completed_at` tutuluyor); analytics
ayrı DB'ye taşındığında şema değişmeden taşınabilir. Saat kaynağı farkı, `completedAt`'in de DB
`NOW()`'ından alınmasıyla kapatılabilir — ayrı iş.

## Referanslar

- `src/main/java/com/app/tracker/analytics/model/SprintAnalytics.java` (javadoc tanımları)
- `src/main/java/com/app/tracker/analytics/repository/SprintSnapshotRepository.java`
- `src/main/java/com/app/tracker/analytics/consumer/VelocityConsumer.java`
- `src/main/java/com/app/tracker/analytics/service/SprintAnalyticsReconciliationJob.java`
- `src/main/resources/db/migration/V12__sprint_analytics.sql`
