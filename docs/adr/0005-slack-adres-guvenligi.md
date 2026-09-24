# ADR-0005: Slack adres güvenliği — allow-list + şifreli saklama

- **Durum:** Kabul edildi (Faz 3, Dilim 3.5 — 2026-09-21)
- **İlgili:** ADR-0003 (guard), ADR-0004 (aynı master key), ADR-0006 (teslim semantiği)

## Bağlam

Workspace ADMIN'i bir Slack incoming webhook adresi girer; sunucu olay oldukça bu adrese HTTP
isteği atar. Bu klasik bir **SSRF** yüzeyidir: adres `http://169.254.169.254/...` (cloud
metadata) veya iç bir servis olabilir. Ayrıca adresin kendisi bir sırdır: bilen herkes o kanala
mesaj yazabilir.

PHASE_3 §3 "Slack/Teams" diyor.

## Karar

### Adres politikası: allow-list

- Kabul edilen **tek** biçim: `https://hooks.slack.com/services/T.../B.../...`.
- Doğrulama tek bir regex ile `matches()` (tam eşleşme); `$` kullanılmaz (sondaki satır sonunu
  tolere eder). URL ayrıştırıcısına **güvenilmez**: `hooks.slack.com@evil.com` (userinfo),
  `hooks.slack.com.evil.com` gibi ayrıştırıcı farklılıkları regex'te kapanır.
- Politika **iki kez** uygulanır: kayıt anında ve her gönderimden önce (DB'den çözülen adrese).
- HTTP istemcisi yönlendirme takip etmez (`Redirect.NEVER`); 3xx kalıcı hata sayılır. Aksi halde
  allow-list'ten geçen bir adres başka yere yönlendirebilirdi.

### Saklama: şifreli

- AES-256-GCM, kayıt başına rastgele 96 bit IV, format `v1.<iv>.<ct>`.
- **AAD = workspace id:** satır başka bir tenant'a kopyalanırsa şifre çözülemez.
- Anahtar: `WebhookSecretService.deriveKey("slack-url-encryption")` =
  `HMAC-SHA256(masterKey, "key:v1:slack-url-encryption")` — mevcut master key'den amaç etiketiyle
  ayrılmış (domain separation). Yeni K8s secret'ı ve yeni guard gerekmez.
- Çözme hatası sessiz "bildirim yok" değil, `IllegalStateException`'dır.
- Adres hiçbir API yanıtında dönmez; `GET` yalnızca durum döner.

### Kapsam

- Yalnız Slack; Teams yok. Workspace başına **tek** entegrasyon (PK = `workspace_id`),
  `PUT` ile oluştur/değiştir. Yönetim yalnız workspace ADMIN.
- Kullanıcı kontrolündeki metin (başlık, durum, proje anahtarı) Slack için `&`, `<`, `>`
  kaçışlanır (`&` önce), başlık 200 karakterde kesilir — `<!channel>` ve aldatıcı bağlantı
  enjeksiyonunu engeller.

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| **Deny-list** (özel IP aralıklarını engelle) | DNS rebinding, IPv6, ondalık/sekizlik IP, yönlendirme ile atlatılabilir; doğru yapmak çözümleme sonrası IP'yi sabitlemeyi gerektirir. Slack'in adres biçimi sabit olduğu için allow-list hem daha basit hem daha güçlü. |
| URL'i `java.net.URI` ile ayrıştırıp host kontrolü | Ayrıştırıcılar arası farklar (userinfo, kaçış) klasik SSRF atlatma kaynağı. |
| Adresi düz saklamak | DB sızıntısı/yedek = tüm müşterilerin kanallarına yazma yetkisi. |
| Ayrı şifreleme anahtarı | Yeni secret, yeni guard, yeni rotasyon süreci; hobi projesi için maliyet > fayda. |
| Slack + Teams birlikte | Teams'in adres biçimi farklı; her sağlayıcı yeni bir allow-list kuralı ve test seti demek. |

## Sonuçlar

**Olumlu**
- SSRF yüzeyi tek, test edilmiş bir regex'e indirgendi (`SlackWebhookUrlPolicyTest`, 32 vaka).
- DB tek başına sızsa adresler okunamaz; tenant'lar arası satır kopyalama işe yaramaz.

**Olumsuz / kabul edilen bedel**
- **Master key değişirse tüm Slack adresleri çözülemez**; her ADMIN adresi yeniden girer. Bkz.
  `README.md` çapraz risk notu. `v1.` öneki şifreleme sürümü içindir, anahtar sürümü değil;
  anahtar rotasyonu için ayrıca sürümleme gerekir.
- Slack adres biçimini değiştirirse (ör. yeni alt alan adı) entegrasyonlar kod değişikliği
  olmadan eklenemez.
- Workspace başına tek kanal: proje bazlı veya kullanıcı bazlı yönlendirme yok. Faz 6
  bildirim tercihleri Slack'i kullanıcı kanalı yaparsa bu modelle ilişkisi yeniden kurulmalı.

## Referanslar

- `src/main/java/com/app/tracker/notification/service/SlackWebhookUrlPolicy.java`
- `src/main/java/com/app/tracker/notification/service/SlackUrlCipher.java`
- `src/main/java/com/app/tracker/notification/service/SlackMessageFormatter.java`
- `src/main/java/com/app/tracker/notification/controller/SlackIntegrationController.java`
- `src/main/resources/db/migration/V14__slack_integrations.sql`
