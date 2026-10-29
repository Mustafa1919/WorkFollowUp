# Güvenlik Testi Dokümantasyonu

> Durum (2026-10-29): **Plan aşaması, katalog Dalga 3.3–5 ve KVKK için güncellendi.** Testler henüz
> koşulmadı. Koşum şartı: tüm yerel commit'ler atılmış ve `origin/main`'e push edilmiş olmalı
> (hareketli hedefe test yapılmaz). Önce **rapor** çıkarılır, düzeltmeler rapor önceliklendirildikten
> sonra ayrı dilimlerde yapılır.
>
> **Sıra değişikliği:** Uygulama 2026-10-28'de sunucuya kuruldu ve kullanımda; aşağıdaki iş akışının
> [5] adımı testlerden önce gerçekleşti. Bu yüzden sunucuya özgü P1 kontroller (INF-01..06, CFG-01..03,
> CFG-09) yerel koşumu beklemeden ilk iş olarak sunucuda yapılır; yerel koşum ve rapor ondan sonra gelir.

## Amaç

Uygulamayı gerçek kullanıcılara açmadan önce (sunucuya kurulum) güvenlik açısından kanıtlanabilir
bir asgari seviyeye getirmek. Ölçüt: **OWASP ASVS 4.0.3 Level 2** + **OWASP API Security Top 10
(2023)**. "Güvenli mi?" sorusunu his ile değil, koşulmuş ve kayıtlı senaryolarla cevaplamak.

## Kapsam

| Katman | Dahil | Not |
|---|---|---|
| Backend API (Spring Boot) | Evet | Tüm `/api/v1/**`, `/ws/**`, `/actuator/**`, Swagger |
| Veritabanı (PostgreSQL, RLS, roller) | Evet | Katalog düzeyi denetim + `app_runtime` ile saldırı |
| Frontend (React SPA + nginx) | Evet | XSS, CSP/güvenlik başlıkları, token saklama |
| Altyapı (Docker/compose, nginx, Redis, Kafka, Mailpit, K8s manifest) | Evet | Ağ yüzeyi, varsayılan parolalar, TLS |
| Tedarik zinciri (Maven, npm, imajlar, CI) | Evet | SCA, imaj taraması, gizli anahtar taraması |
| Üçüncü taraf servisler (GitHub, Slack, SMTP sağlayıcı) | Hayır | Yalnız bizim onlarla konuştuğumuz uç test edilir |
| Hizmet dışı bırakma (volumetrik DDoS) | Hayır | Yalnız uygulama katmanı kaynak tüketimi (DOS-*) |

## Doküman Haritası

| Dosya | İçerik | Ne zaman kullanılır |
|---|---|---|
| [01-TEHDIT-MODELI.md](01-TEHDIT-MODELI.md) | Varlıklar, güven sınırları, STRIDE, **ön inceleme hipotezleri** | Test öncesi okuma; hipotezler ilk test edilecekler |
| [02-TEST-SENARYOLARI.md](02-TEST-SENARYOLARI.md) | ID'li senaryo kataloğu (amaç, adım, beklenen, otomasyon hedefi) | Testin kendisi — her koşumda buradan |
| [03-MANUEL-TEST-KILAVUZU.md](03-MANUEL-TEST-KILAVUZU.md) | Araç kurulumu, yardımcı script'ler, senaryo senaryo komutlar | Sunucuya kurduktan sonra elle tekrar |
| [04-YAYIN-ONCESI-SERTLESTIRME.md](04-YAYIN-ONCESI-SERTLESTIRME.md) | Sunucuya kurulum güvenlik kontrol listesi | Kurulumdan ÖNCE ve SONRA |
| [05-RAPOR-SABLONU.md](05-RAPOR-SABLONU.md) | Bulgu raporu şablonu, önem derecelendirmesi | Test koşumu sırasında/sonunda |

## Yöntem (katmanlar, değer/efor sırasıyla)

1. **DB fitness testleri** — `pg_catalog` üzerinden RLS/FORCE/rol/yetki kurallarını CI'da kalıcı
   doğrulayan testler (tek seferlik değil, mimari kuralın koda gömülmesi).
2. **Kiracılar arası BOLA harness'i** — OpenAPI'den türetilen tüm id'li uçlara ikinci kiracının
   id'leriyle istek; beklenen her yerde 404.
3. **SAST** — SpotBugs + find-sec-bugs, GitHub CodeQL (public repo, ücretsiz), Semgrep (opsiyonel).
4. **DAST** — OWASP ZAP API scan (OpenAPI içe aktarımıyla, kimlikli), hedefli sqlmap.
5. **Manuel pentest** — iş mantığı, token/oturum saldırıları, WebSocket, XSS.
6. **Altyapı/konfigürasyon** — port taraması, TLS (testssl.sh), başlıklar, imaj ve bağımlılık taraması.

Otomatikleştirilebilen senaryolar (katalogda `Oto` işaretli) test koşumu sonrasında JUnit/CI'a
taşınır; manuel kılavuz sunucu ortamında aynı senaryoların elle tekrarı içindir.

## İş Akışı

```
[1] Commit + push (ön koşul)
[2] Yerel prod-benzeri ortam (docker compose, app_runtime) üzerinde 02 kataloğunu koş
[3] Bulguları 05 şablonuna göre raporla  -> docs/security/reports/YYYY-MM-DD-rapor.md
[4] Önceliklendir (Kritik/Yüksek önce), düzelt, regresyon testini koda ekle
[5] 04 kontrol listesiyle sunucuya kur
[6] 03 kılavuzuyla sunucu üzerinde manuel tekrar  -> ikinci rapor
[7] Kullanıma aç; periyodik tekrar (her büyük sürüm / 3 ayda bir)
```

## Kurallar

- Testler YALNIZ bizim sahip olduğumuz ortamlara yapılır. Sunucu bir VPS/bulut sağlayıcısındaysa
  sağlayıcının kabul edilebilir kullanım politikasındaki "penetration testing" maddesi okunur.
- DOS-* senaryoları **canlı kullanıcı varken** prod üzerinde koşulmaz.
- Test verisi `sec-test-*` önekiyle oluşturulur ve koşum sonunda temizlenir.
- Bulunan açık, düzeltilene kadar public repoya (issue/commit mesajı) ayrıntılı yazılmaz;
  rapor dosyası düzeltme commit'iyle birlikte eklenir.
