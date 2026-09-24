# ADR-0004: Webhook kimlik modeli

- **Durum:** Kabul edildi (Faz 3, Dilim 3.4 — 2026-09-21). **Faz 4'te yeniden açılacak** (aşağıya bakın).
- **İlgili:** ADR-0003 (master key guard), ADR-0005 (aynı master key)

## Bağlam

GitHub webhook istekleri JWT taşımaz; kimlik yalnızca `X-Hub-Signature-256` HMAC imzasıyla
kanıtlanır. Sorun bir tavuk-yumurta döngüsü:

- İmzayı doğrulamak için **secret** bilinmeli.
- Secret'ı bulmak için **tenant** bilinmeli.
- Tenant'ı bulmak için DB okunmalı, ama DB RLS'li ve RLS bağlamı **tenant bilinmeden** kurulamaz.

PHASE_3 §2 tek bir `/webhooks/github` endpoint'i tarif ediyor; bu, döngüyü çözmüyor.

## Karar

Dört parçadan oluşan bir model:

### 1. Tenant'ı path taşır

`POST /api/v1/webhooks/github/{integrationId}`. Path'teki id hem tenant'ı hem secret'ı çözer.
PHASE_3 dokümanından bilinçli sapma.

### 2. Secret saklanmaz, türetilir

```
secret = HMAC-SHA256(masterKey, "webhook-secret:v1:" + integrationId + ":" + secret_version)
```

Rotasyon = `secret_version++`. Secret yalnızca oluşturma ve rotasyon yanıtında bir kez gösterilir.

### 3. Kimlik doğrulama öncesi lookup: FORCE'suz RLS + SECURITY DEFINER

- `webhook_integrations` RLS'li ama **FORCE'suz** — projede tek istisna.
- `resolve_webhook_integration(id)` SECURITY DEFINER fonksiyonu yalnızca 3 kolon döner
  (`workspace_id`, `provider`, `secret_version`); `search_path` sabit (`public, pg_temp`);
  PUBLIC'ten EXECUTE geri alınmış.
- FORCE olsaydı tablo sahibi olan fonksiyon da RLS'e takılır ve hiç satır görmezdi. Bu varsayım
  **superuser olmayan** sahiple test edildi (`WebhookIntegrationsMigrationTest`).

### 4. Ingestion akışı ve idempotency

- Gövde sınırlı okunur (varsayılan 512 KB → 413), **imzadan önce**.
- Bilinmeyen entegrasyon ve yanlış imza **aynı 401**'i döner (id enumeration'ı engellemek için).
- Gövde parse edilmez, envelope'a ham gömülür; topic `webhooks.incoming`, anahtar = entegrasyon id.
- Kafka ack'i beklenir; yazılamazsa **503** (GitHub yeniden dener). Outbox kullanılmaz: ingestion
  iş verisi yazmaz, dual-write yoktur.
- Idempotency **tüketicide**:
  `eventId = UUID.nameUUIDFromBytes(integrationId + ":" + deliveryId)`. `processed_events` global
  ve RLS'siz olduğu için ham `deliveryId` kullanılsaydı bir tenant başka tenant'ın delivery id'sini
  taklit edip olayını "işlendi" saydırabilirdi.

### Resolver cache'i

Yalnız pozitif sonuçlar, 60 sn TTL, 10.000 girdi (aşılırsa tamamı boşaltılır). Rotasyon/silme
commit'ten sonra **bu pod'un** cache'ini temizler.

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| Tek endpoint, imzayı tüm secret'larla denemek | O(tenant) maliyet her istekte; zamanlama farkı bilgi sızdırır; tenant sayısıyla ölçeklenmez. |
| Secret'ı DB'de şifreli saklamak | Ek anahtar yönetimi ve şifreli kolon; türetme aynı güvenliği daha az parçayla sağlıyor. |
| Secret'ı düz/hash saklamak | HMAC doğrulamak için düz secret gerekir; hash işe yaramaz, düz saklamak DB sızıntısında tüm entegrasyonları açar. |
| FORCE RLS + superuser/BYPASSRLS rolü | Uygulamaya RLS'i atlayan bir rol vermek, RLS'in tüm garantisini tek bağlantıya bağlar. |
| Idempotency ingestion'da | "Gördüm" işareti + ardından Kafka hatası = GitHub'ın yeniden teslimi tekrar sanılıp atılırdı (veri kaybı). |

## Sonuçlar

**Olumlu**
- DB sızıntısı tek başına webhook secret'larını açığa çıkarmaz.
- İstek başına tek, indeksli, cache'lenebilir lookup.
- Tenant'lar arası olay taklidi idempotency seviyesinde de kapalı.

**Olumsuz / kabul edilen bedel**
- **Master key değişirse tüm secret'lar değişir** — her entegrasyonun GitHub tarafında yeniden
  girilmesi gerekir. Bkz. `README.md` çapraz risk notu.
- FORCE'suz tablo: tablo sahibi (migrator) RLS'e tabi değil. Güvenlik, fonksiyonun doğru yazılmış
  olmasına bağlı; fonksiyon değişiklikleri güvenlik incelemesi gerektirir.
- Aynı 401 debug'ı zorlaştırır (log'da ayrım yapılır, yanıtta yapılmaz).
- Rotasyondan sonra diğer pod'lar TTL (60 sn) boyunca eski secret'ı kabul edebilir.
- Webhook kaynaklı durum değişiklikleri sabit bir sistem aktörüyle yazılır
  (`IntegrationActor`); kaynak PR/commit bilgisi tarihçede tutulmaz.

## Faz 4 notu — bu ADR yeniden açılacak

Webhook ingestion ayrı servise çıktığında temel soru şudur: **`resolve_webhook_integration`'ı
kim çağıracak?**

- Ayrılan servis çekirdek DB'ye yalnız bu fonksiyon için bağlanmaya devam ederse servis ayrımı
  yarım kalır (paylaşılan DB, paylaşılan bağlantı havuzu).
- Alternatifler: entegrasyon metadata'sını (`id → workspace_id, secret_version`) Kafka
  compacted topic ile webhook servisine replike etmek; ya da çekirdek API'ye iç bir lookup
  endpoint'i açmak (senkron bağımlılık).
- Master key webhook servisine taşınırsa Slack şifrelemesi (ADR-0005) ile paylaşım da ayrılmalı.

Faz 4'te bu kararları veren ADR bu ADR'nin yerini alır.

## Referanslar

- `src/main/resources/db/migration/V13__webhook_integrations.sql`
- `src/main/java/com/app/tracker/integration/service/WebhookSecretService.java`
- `src/main/java/com/app/tracker/integration/service/WebhookIngestionService.java`
- `src/main/java/com/app/tracker/integration/service/WebhookIntegrationResolver.java`
- `src/test/.../WebhookIntegrationsMigrationTest.java`
