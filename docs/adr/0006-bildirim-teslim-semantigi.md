# ADR-0006: Bildirim teslim semantiği — retry sorumluluğu, devre kesici, offset

- **Durum:** Kabul edildi (Slack: Faz 3 Dilim 3.5 — 2026-09-21; Inbox: V18 — 2026-09-23)
- **İlgili:** ADR-0005

## Bağlam

`task.events` topic'ini tüketen iki bildirim consumer'ı var: Slack (dış HTTP) ve in-app Inbox
(DB + STOMP push). Analitik consumer'lardan farklı olarak bunlar:

- Dış bir bağımlılığa (Slack) istek atar; bağımlılık yavaş/çökük olabilir.
- Geçmişi değil **anı** temsil eder: bir hafta önceki "görev Done oldu" bildirimi değersizdir.

Cevaplanması gerekenler: kim retry eder, dış bağımlılık çöktüğünde ne olur, tek bir bozuk tenant
diğerlerini etkiler mi, yeni bir consumer group geçmişi ne yapar?

## Karar

### 1. Retry'ın tek sahibi Kafka error handler'ı

- Global `DefaultErrorHandler`: üstel geri çekilme (1 sn başlangıç, ×2, `maxAttempts=4`), sonra
  `<topic>-dlt`. Deserialization, `IllegalArgumentException`, `BusinessRuleException` retry
  edilmez.
- Uygulama katmanında (Resilience4j) **retry yok**. İki katman üst üste binerse deneme sayısı
  çarpılır (ör. 3 × 5 = 15 istek) ve toplam gecikme öngörülemez olur.

### 2. Tek global devre kesici, yalnız geçici hatalar sayılır

- Slack tüm tenant'lar için aynı bağımlılık → **tek** devre kesici (`slack`), count-based pencere.
- `recordExceptions(SlackTransientException)`: ağ hatası, zaman aşımı, 429, 5xx.
- `ignoreExceptions(SlackPermanentException)`: 4xx — tek bir tenant'ın silinmiş/ölü adresi.
  Sayılsaydı bir tenant'ın ölü adresi **herkesin** bildirimini keserdi.
- Kalıcı red serviste yutulur (log), DLT'ye gitmez: DLT ölü adreslerle dolmasın.
- Devre açıkken (`CallNotPermittedException`) çağrı geçici hata gibi ele alınır → Kafka retry'ı.

### 3. Bulkhead yok

Tek consumer thread'i eşzamanlı Slack çağrısını zaten 1 ile sınırlıyor.

### 4. Dış çağrı transaction'ların **arasında**

`alreadyDelivered` (kısa tx) → Slack HTTP çağrısı → `markDelivered` (kısa tx). Semantik
**at-least-once**. Çağrı transaction içinde olsaydı, Slack'in yavaşlığı paylaşılan Hikari
havuzundan bağlantı tutardı (çekirdek API'yi etkiler). Bu nedenle `ProcessedEventStore`'a
salt-okur `isProcessed` eklendi; `markProcessed` (`Propagation.MANDATORY`) değişmedi.

Inbox'ta dış çağrı yok: bildirim satırları ve `markProcessed` aynı transaction'da; STOMP push
commit'ten **sonra** yapılır.

### 5. Bildirim consumer'ları `auto.offset.reset=latest`

| Consumer | Group | Offset | Neden |
|---|---|---|---|
| Cycle Time / Velocity | `analytics-cycle-time`, `analytics-velocity` | `earliest` | Read model geçmişten yeniden kurulabilmeli. |
| Integration (GitHub) | `integration-github` | `earliest` | Her webhook olayı işlenmeli. |
| Slack | `notification-slack` | **`latest`** | Geçmiş Slack'e boşaltılmaz. |
| Inbox | `notification-inbox` | **`latest`** | Geçmiş gelen kutusuna boşaltılmaz. |

`latest` yalnızca **commit edilmiş offset yokken** (yeni group, süresi dolmuş offset) devreye
girer; normal çalışmada kaldığı yerden devam eder.

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| Resilience4j retry + Kafka retry | Çarpan etkisi; iki yerde ayarlanan politika. |
| Tenant başına devre kesici | Slack tek bağımlılık; tenant başına durum = bellek ve karmaşıklık, fayda yok (tenant hatası zaten 4xx, ignore ediliyor). |
| Kalıcı hataları DLT'ye göndermek | DLT operasyonel inceleme içindir; ölü adresler gürültü üretir ve replay ile düzelmez. |
| Exactly-once (çağrı tx içinde) | Dış HTTP transaction'a katılamaz; gerçek exactly-once mümkün değil, sadece bağlantı tutulur. |
| Bildirimlerde `earliest` | Yeni kurulumda/ group değişiminde yüzlerce eski bildirim spam'i. |

## Sonuçlar

**Olumlu**
- Retry politikası tek yerde; tek bir tenant'ın hatası diğerlerini etkilemez.
- Slack'in yavaşlığı DB havuzunu tüketmez.

**Olumsuz / kabul edilen bedel**
- At-least-once: Slack çağrısı ile `markDelivered` arasında çökme = nadiren çift mesaj.
- `latest`: offset'in olmadığı anlarda (yeni group, retention'dan uzun kesinti) aradaki olaylar
  için **bildirim hiç gitmez**. **Kural: consumer group adları değiştirilmez**; değişirse o
  andaki boşluk kabul edilmiş sayılır.
- "Bulkhead gereksiz" varsayımı consumer `concurrency = 1`'e bağlıdır; concurrency artırılırsa bu
  ADR yeniden değerlendirilmeli.
- Inbox STOMP push'u SimpleBroker ile yalnızca consumer'ın koştuğu pod'daki bağlantılara ulaşır;
  çok pod'da diğer kullanıcılar REST/yenilemede görür (Faz 5 broker relay).

## Referanslar

- `src/main/java/com/app/tracker/core/kafka/KafkaConsumerConfig.java`
- `src/main/java/com/app/tracker/notification/service/ResilientSlackSender.java`
- `src/main/java/com/app/tracker/notification/service/SlackNotificationService.java`
- `src/main/java/com/app/tracker/notification/consumer/SlackNotificationConsumer.java`
- `src/main/java/com/app/tracker/notification/consumer/InboxNotificationConsumer.java`
- `src/main/java/com/app/tracker/core/idempotency/ProcessedEventStore.java`
