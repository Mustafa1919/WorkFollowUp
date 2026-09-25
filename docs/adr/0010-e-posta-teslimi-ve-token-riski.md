# ADR-0010: E-posta teslimi — devre kesici/offset, ham token riski, izin modeli

- **Durum:** Kabul edildi (Dilim 1.3 — 2026-09-25)
- **İlgili:** ADR-0003 (fail-closed guard deseni), ADR-0006 (bildirim teslim semantiği)

## Bağlam

`EmailNotificationPublisher` (Faz 1'den beri) doğrulama/şifirlama/güvenlik-uyarısı olaylarını
`notification.email` topic'ine yazıyordu ama hiçbir tüketen yoktu — kayıt olan kullanıcı asla
doğrulama e-postası almıyordu, "şifremi unuttum" akışı sessizce çöküyordu. Bu dilim tüketiciyi
(`EmailDeliveryConsumer`) ve gorev-kaynaklı (atama/mention) ikinci bir tüketiciyi
(`EmailTaskEventConsumer`) ekliyor. Cevaplanması gerekenler: retry/devre kesici sorumluluğu kime
ait, `APP_PUBLIC_URL` unutulursa ne olur, ham token'ın DB'de/Kafka'da düz metin durmasının riski
nasıl azaltılır, kullanıcı hangi e-postaları kapatabilir.

## Karar

### 1. Devre kesici ve retry — ADR-0006'nın AYNI deseni

SMTP tüm kullanıcılar için PAYLAŞILAN tek bağımlılık: `ResilientEmailSender` `ResilientSlackSender`
ile birebir aynı yapıda tek global Resilience4j devre kesicisi kullanır. Retry'ın tek sahibi yine
Kafka `DefaultErrorHandler`; uygulama katmanında ikinci bir retry katmanı yok.

**Geçici/kalıcı ayrımı basitleştirildi (bilinen sınır):** `JavaMailSender` gerçek SMTP durum kodunu
(4xx geçici / 5xx kalıcı) her zaman ayırt edilebilir şekilde iletmez. Sezgisel kural: alıcı adresi
biçim olarak geçersizse (`AddressException`, mesaj kurulurken fırlar) kalıcı sayılır; SMTP
bağlantısı/kimlik doğrulama/diğer tüm hatalar geçici sayılır. Gerçek bir SMTP sağlayıcısına
geçildiğinde (Mailpit değil) bu sınır yeniden değerlendirilmeli — kalıcı bir "posta kutusu yok"
reddi bugün geçici sayılıp devre kesiciyi gereksiz yere açabilir.

### 2. İki ayrı consumer, ikisi de `auto.offset.reset=latest`

| Consumer | Group | Topic | Neden `latest` |
|---|---|---|---|
| `EmailDeliveryConsumer` | `notification-email` | `notification.email` | Bu consumer YENİ devreye giriyor; topic'te biriken, çoğu süresi dolmuş eski doğrulama/sıfırlama olaylarını bir anda göndermek hem anlamsız hem SMTP sağlayıcısına ani yük. |
| `EmailTaskEventConsumer` | `notification-email-task` | `task.events` | Slack/Inbox ile AYNI gerekçe (ADR-0006 madde 5): geçmiş atama/mention e-postaya boşaltılmaz. |

`email.security_alert` gibi güvenlik-kritik olaylar için bile `latest` seçildi: parola değişikliği
uyarısının gecikmeli/atlanmış olması, spam bir toplu gönderimden daha kabul edilebilir bir bedel
(ADR-0006'daki genel kabul).

### 3. Çoklu alıcılı olaylarda alıcı-başına idempotency

`COMMENT_MENTION` tek bir olaydan BİRDEN FAZLA e-posta üretir (her etiketlenen kullanıcıya bir
tane); `ProcessedEventStore`'un `(consumer, event_id)` anahtarı tek başına yetmez. Çözüm
`WebhookIngestionService.eventId` ile AYNI teknik: alıcı başına türetilmiş bir kimlik
(`UUID.nameUUIDFromBytes(eventId + ":" + recipientId)`) `markProcessed`'e verilir. `TASK_ASSIGNED`
tek alıcılı olsa da AYNI yol kullanılır (tutarlılık, ayrı bir kod yolu gerekmez).

### 4. `APP_PUBLIC_URL` fail-closed guard (ADR-0003 deseni)

`AppPublicUrlGuard`, `JwtKeyProvider`/`WebhookSecretService` ile BİREBİR aynı desen: staging/prod
profilinde `APP_PUBLIC_URL` boşsa veya `localhost` içeriyorsa açılış reddedilir. Aksi halde ortam
değişkeni unutulduğunda e-postadaki doğrulama/sıfırlama/görev linkleri kullanıcının makinesinde
`localhost`'a işaret eder, sessizce kırık bir akış üretirdi (`migrate` profili muaf — mail bean'leri
o profilde hiç kurulmaz).

### 5. Ham token riski: kısa TTL + erken outbox temizliği

Doğrulama/sıfırlama token'ı `outbox_events.payload`'da ve Kafka mesajında DÜZ METİN durur (DB'de
yalnız `verification_tokens.token_hash` saklanır, ama outbox satırı ham token'ı taşımak zorunda —
e-postanın içine hash konamaz). Kabul edilen risk azaltma:

- Sıfırlama TTL'i 30 dakikaya düşürüldü (`AuthService.requestPasswordReset`, önceden yoktu).
- `notification.email` topic'indeki satırlar `OutboxRelay` tarafından BAŞARIYLA Kafka'ya
  gönderildikten HEMEN SONRA silinir (`OutboxEventRepository#delete`), diğer topic'lerin izlediği
  7 günlük `OutboxCleanupJob` bekletme süresini BEKLEMEZ. Bedel: bu satırlar için "gönderildi ama
  henüz relay edilmedi" durumunun debug edilebilirliği azalır (satır kalıcı değil) — kabul edildi,
  çünkü zaten idempotency `processed_events`'te ayrı tutuluyor.
- Kafka topic retention'ı (varsayılan) düşürülmedi — bilinen, kabul edilen kalan risk: mesaj relay
  edilip DB'den silinse de Kafka broker'ında retention süresi boyunca durur.

**Değerlendirilip reddedildi:** token'ı e-postaya link olarak DEĞİL, tek kullanımlık bir kod olarak
göndermek (kullanıcı elle girer) — UX'i karmaşıklaştırır, mevcut `verify-email?token=` /
`reset-password?token=` deseninden sapardı; bu dilimde kapsam dışı bırakıldı.

### 6. İzin modeli: güvenlik e-postaları tercihe BAĞLI DEĞİL

`notification_preferences` (V24) yalnız gorev-kaynaklı e-postaları (`email_on_assign`,
`email_on_mention`) kapsar. Doğrulama/şifre sıfırlama/güvenlik uyarısı e-postaları KOŞULSUZ
gönderilir — bir kullanıcı "parola sıfırlama e-postası almayı kapat" diyemez, bu güvenlik
kontrolünü devre dışı bırakırdı.

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| Tek consumer (`notification.email` + `task.events` aynı sınıfta) | İki farklı topic/offset/idempotency politikası (tekli vs. çoklu alıcı) karıştırılırdı; ADR-0006'daki "her consumer kendi grubu" ilkesiyle tutarsız. |
| `auto.offset.reset=earliest` (ilk kurulumda geçmişi de gönder) | Üretimde aylarca birikmiş, çoğu süresi dolmuş doğrulama/sıfırlama olayını bir anda göndermek SMTP sağlayıcısını tetikleyebilir/spam sayılabilir. |
| Token'ı outbox'ta hash'lemek | E-postanın içine konacak ham değeri relay üretemezdi (hash geri çözülemez); token zaten yalnız `verification_tokens` tablosunda hash'li. |
| Güvenlik e-postalarını da tercihe bağlamak | Kullanıcı kendi hesap güvenliği bildirimini kapatabilirdi — kabul edilemez. |

## Sonuçlar

**Olumlu**
- Kayıt/şifre sıfırlama akışı artık gerçekten çalışıyor (önceden sessizce kırıktı).
- Ham token'ın DB'de kalma süresi minimize edildi.
- Slack ile aynı dayanıklılık deseni: yeni bir kavram öğrenmeye gerek yok.

**Olumsuz / kabul edilen bedel**
- Geçici/kalıcı SMTP hata ayrımı kaba bir sezgi; gerçek sağlayıcıya geçişte yeniden gözden
  geçirilmeli.
- `latest` offset: consumer group'lar ASLA yeniden adlandırılmamalı (ADR-0006'daki aynı kural).
- Kafka broker retention'ı boyunca ham token teorik olarak diskte durur.

## Referanslar

- `src/main/java/com/app/tracker/notification/email/`
- `src/main/java/com/app/tracker/core/outbox/OutboxRelay.java`
- `src/main/java/com/app/tracker/core/outbox/OutboxEventRepository.java#delete`
- `src/main/java/com/app/tracker/notification/preferences/`
- `src/main/resources/db/migration/V24__notification_preferences.sql`
