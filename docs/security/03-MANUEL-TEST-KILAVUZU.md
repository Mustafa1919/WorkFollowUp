# 03 — Manuel Test Kılavuzu (Sunucu Ortamı)

Bu kılavuz, 02 kataloğundaki kontrollerin **kendi sunucunda** elle tekrarlanması içindir. Ayrıntılı test
prosedürleri için her bölümde OWASP WSTG'nin ilgili bölümüne referans verilir. Bu doküman ortamı, araçları,
sırayı ve kanıt toplamayı tanımlar.

> **Yalnız sahip olduğun sistemlere.** VPS/bulut sağlayıcının kabul edilebilir kullanım politikasını oku.
> Aktif taramaları (ZAP active scan, DOS-*) gerçek kullanıcı yokken ya da ayrı bir staging kopyasında koş.

## 1. Hazırlık

### 1.1 Test makinesi araçları

| Araç | Amaç | Kurulum |
|---|---|---|
| `curl`, `jq` | API çağrıları, yanıt inceleme | Paket yöneticisi |
| OWASP ZAP (Docker) | DAST: baseline ve API taraması | `docker pull ghcr.io/zaproxy/zaproxy:stable` |
| testssl.sh | TLS yapılandırması | `docker pull drwetter/testssl.sh` |
| nmap | Dışarıdan açık port doğrulaması (yalnız kendi sunucun) | Paket yöneticisi |
| trivy | İmaj, dosya sistemi ve yapılandırma taraması | `docker pull aquasec/trivy` |
| OSV-Scanner | Maven/npm bağımlılık açıkları | github.com/google/osv-scanner |
| gitleaks | Sır taraması | Mevcut CI; yerelde Docker imajı |
| `psql` | DB katalog denetimi | PostgreSQL istemcisi |
| Tarayıcı DevTools | Cookie, depolama, başlıklar, WebSocket çerçeveleri | — |
| Burp Suite Community (opsiyonel) | İstek inceleme/tekrar (WSTG prosedürleri için) | portswigger.net |

### 1.2 Ortam değişkenleri (test makinesinde)

```bash
export BASE="https://<alan-adin>"        # nginx arkasındaki genel adres
export API="$BASE/api/v1"
export OUT="./sec-run-$(date +%Y%m%d)"     # kanıt klasörü
mkdir -p "$OUT"
```

### 1.3 Kanıt kuralı

Her kontrol için `$OUT/<ID>.txt` (komut + çıktı) veya ekran görüntüsü. Rapor (05) bu dosyalara atıf yapar.
Token ve parolaları kanıt dosyalarına yazmadan önce maskele (`eyJ...` → `<token>`).

## 2. Uygulama Sırası

```
§3 Fikstür  →  §4 Altyapı/TLS  →  §5 Yapılandırma/başlıklar  →  §6 DB katalog denetimi
→  §7 Kimlik/oturum  →  §8 Yetki ve kiracı izolasyonu  →  §9 Girdi/XSS  →  §10 WebSocket
→  §11 Dış entegrasyonlar  →  §12 ZAP taramaları  →  §13 Tedarik zinciri  →  §14 Temizlik
```

Önce pasif ve yapılandırma kontrolleri yapılır. Aktif taramalar en sona bırakılır, böylece bulunan
yapılandırma hataları taramayı gereksiz yere gürültülü yapmaz.

## 3. Fikstür (iki kiracı)

Normal API kullanımıyla kurulur: kayıt, e-posta doğrulama, login, workspace oluşturma, davet, proje, görev
vb. İş akışı `docs/API_KULLANIM.md`'deki uçtan uca örneği izler.

1. `sec-test-a-admin@...`, `sec-test-a-manager@...`, `sec-test-a-dev@...`, `sec-test-a-viewer@...` ve
   B için aynı dört kullanıcı + `sec-test-nomember@...` kaydedilir. Doğrulama e-postaları gerçek
   posta kutusundan (ya da staging'de Mailpit'ten) onaylanır.
2. A-admin ve B-admin kendi workspace'ini oluşturur, diğerlerini davet eder (rol başına bir kullanıcı).
3. Her workspace'te: 1 proje, 1 sprint, ≥3 görev (biri alt görevli, biri bağımlılıklı, biri onaylı),
   1 yorum, 1 etiket, 1 toplantı, 1 hedef, 1 kayıtlı görünüm, 1 retro maddesi.
4. Tüm oluşturulan id'ler `$OUT/fixture.json`'a kaydedilir (`{ "A": {...}, "B": {...} }`). §8 bu
   dosyayı kullanır.

> Bu adım bir kez script'e dönüştürülürse (`scripts/security/fixture.sh`) her koşumda tekrar
> kullanılabilir. Script, TEN-01 otomasyon harness'inin de temelidir.

## 4. Altyapı ve TLS (INF-*, CFG-01)

| Adım | Nasıl | Beklenen |
|---|---|---|
| 4.1 Dış port görünümü | **Sunucu ağının DIŞINDAKİ** bir makineden kendi sunucuna TCP port taraması (`nmap -Pn -p