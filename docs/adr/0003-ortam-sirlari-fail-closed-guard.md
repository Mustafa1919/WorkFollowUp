# ADR-0003: Ortam sırları için fail-closed guard (deny-list)

- **Durum:** Kabul edildi (JWT: 2026-09-20; webhook master key: Faz 3 Dilim 3.4 — 2026-09-21)
- **İlgili:** ADR-0004, ADR-0005 (master key'in kullanıcıları)

## Bağlam

Repo public ve yerel çalışma/testler için **bilerek** dev sırları içeriyor:

- `src/main/resources/keys/dev-jwt-*.pem` (JWT RS256 anahtar çifti)
- `dev-webhook-` önekli `WEBHOOK_SECRET_MASTER_KEY` (`.env.secret.example` şablonları)

`application.yml` varsayılanı dev değerleri gösteriyor. Staging/prod'da ortam değişkeni
unutulursa uygulama **hata vermeden** herkesin bildiği anahtarla açılır: herkes geçerli JWT
imzalayabilir, herkes webhook secret'larını türetebilir. Bu, en kötü türden hatadır — sessiz ve
sonradan fark edilmez.

## Karar

Korunan profillerde (`staging`, `prod`) uygulama zayıf sırla **açılmayı reddeder**
(`IllegalStateException`, pod CrashLoop'a düşer):

| Sır | Reddedilen durum | Yer |
|---|---|---|
| JWT anahtarları | Private **veya** public anahtar konumu `null` ya da `dev-jwt-` içeriyor | `JwtKeyProvider#rejectDevKeysInProtectedProfiles` |
| Webhook master key | `dev-webhook-` ile başlıyor **veya** 32 karakterden kısa (boşsa tüm profillerde red) | `WebhookSecretService#rejectWeakKeyInProtectedProfiles` |

Dağıtım kuralları:

- `JWT_*_KEY_LOCATION` **yalnız Deployment patch'inde** (`k8s/overlays/{staging,prod}`), ortak
  ConfigMap'te değil. Migration Job'ı aynı ConfigMap'i `envFrom` ile alır ama anahtar volume'u
  yoktur; ConfigMap'te olsaydı Job dosyayı bulamayıp patlardı.
- Anahtarlar `core-api-jwt-keys` secret'ından `/etc/tracker/jwt`'ye mount edilir (0444).
- `WEBHOOK_SECRET_MASTER_KEY` yalnız `core-api-secret`'te (Deployment).
- Slack adres şifreleme anahtarı master key'den türetildiği için (ADR-0005) **ayrı guard
  gerekmez**; guard tek yerde, `WebhookSecretService` kurucusunda durur.

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| **Allow-list**: dev sırrı yalnız `dev`/`test` profilinde çalışır | `migrate` profili (migration Job'ı) JWT kullanmıyor ve anahtar mount'u yok; allow-list Job'ı kırardı. Profil verilmeden çalışan yerel çalıştırma ve testler de kırılırdı. |
| Dev anahtarlarını repodan kaldırmak | Her geliştirici/CI koşusu anahtar üretmek zorunda kalır; testlerin sıfır kurulumla çalışması kaybolur. gitleaks allowlist ile dar kapsamda tutuldu. |
| Yalnız dokümantasyon/checklist | İnsan hatasına karşı koruma sağlamaz; tam da önlenmek istenen senaryo. |
| Anahtarın entropisini ölçmek | Karmaşık, yanlış pozitif üretir; önek + uzunluk kontrolü bilinen tehdidi (repodaki değer) tam kapatıyor. |

## Sonuçlar

**Olumlu**
- "Prod'da bilinen anahtar" hatası yapısal olarak imkânsız; hata deploy anında, açıkça görülür.
- Aynı desen tek satırlık bir kontrolle yeni sırlara uygulanabilir.

**Olumsuz / kabul edilen bedel**
- **Deny-list açığı:** korunan profil listesi sabit (`staging`, `prod`). Yeni bir ortam profili
  (ör. `perf`, `demo`) eklenirse guard onu **kapsamaz**. Yeni profil eklemek bu listeyi
  güncellemeyi gerektirir.
- Profil hiç verilmezse dev sırları çalışır. Bu bilerek böyle (yerel çalışma), ama bir üretim
  ortamı profil vermeyi unutursa korunmaz.
- Guard yalnızca repodaki bilinen değeri yakalar; sızmış ama repoda olmayan bir anahtarı
  yakalamaz (bu, sır yönetiminin işidir).

## Faz 4 notu

API Gateway JWT doğrulamasını üstlenirse gateway yalnızca **public** key'e ihtiyaç duyar; bu
guard'ın eşdeğeri gateway'e de konmalıdır. Webhook servisi ayrılırsa master key o servise
taşınır ve guard onunla birlikte gider.

## Referanslar

- `src/main/java/com/app/tracker/core/security/JwtKeyProvider.java`, `JwtKeyProviderTest.java`
- `src/main/java/com/app/tracker/integration/service/WebhookSecretService.java`
- `k8s/overlays/{staging,prod}/kustomization.yaml`, `k8s/overlays/*/.env.secret.example`
- `.gitleaks.toml`
