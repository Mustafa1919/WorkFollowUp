# 02 — Güvenlik Kontrolleri ve Doğrulama Kataloğu

Her satır bir **güvenlik kontrolünü** ve onun nasıl doğrulanacağını tanımlar. Uygulama ayrıntısı için
standart test prosedürüne başvurulur: **OWASP WSTG v4.2** (`WSTG-xxxx`) ve **ASVS 4.0.3** maddeleri.

**Sütunlar**
- **Yöntem:** `Oto` = JUnit/Testcontainers ile kalıcı test (CI'da yaşar) · `DAST` = OWASP ZAP taraması ·
  `SAST` = statik analiz · `Konf` = yapılandırma/manifest incelemesi · `Manuel` = elle doğrulama (WSTG prosedürü).
- **Öncelik:** P1 = yayından önce zorunlu · P2 = yayından önce tercihen · P3 = yayından sonra periyodik.
- **Hip.:** `docs/security/local/ON-INCELEME-HIPOTEZLERI.md`'deki ilgili hipotez (git dışı).

Sonuç rapora (05) `GEÇTİ / KALDI / UYGULANAMAZ` + kanıt (test adı, tarama çıktısı, ekran görüntüsü) ile yazılır.

**Ortak fikstür:** İki kiracı (A, B), her birinde ADMIN/MANAGER/DEVELOPER/VIEWER rolünde birer kullanıcı ve her kaynak
tipinden en az bir kayıt; ayrıca hiçbir workspace'e üye olmayan bir kullanıcı. Kurulum: 03 §3.
Her kiracıda ek olarak: bir alt görev (atanmış DEVELOPER'ın görevinin altında), açık bloklayanı olan bir görev,
bir yorum içinde mention ve A'nın DEVELOPER'ı için bir kişisel erişim token'ı (PAT).

> **Güncelleme (2026-10-29):** İlk sürüm 2026-09-26'da, Dalga 3.3'ten önce yazıldı. Sonradan gelen yüzeyler
> için eklenen satırlar: AUTH-11, TOK-11..16, AUTHZ-09..14, TEN-09..11, INJ-09, DOS-07..08, CFG-09, LOG-05 ve
> §16 (PRIV). Uygulama sunucuda canlı olduğundan koşum sırası README'deki iş akışında güncellendi.

---

## 1. Kimlik Doğrulama (AUTH)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref | Hip. |
|---|---|---|---|---|---|---|
| AUTH-01 | Parola politikası (min. 12, sızmış parola kontrolü kararı) | Oto + Manuel | 12 altı 422; sızmış parola kararı raporda | P2 | ASVS 2.1, WSTG-ATHN-07 | |
| AUTH-02 | Hesap başına kilitleme ve üstel gecikme | Oto | Eşikten sonra kilit; doğru parola da kilit süresince reddedilir | P1 | ASVS 2.2.1, WSTG-ATHN-03 | |
| AUTH-03 | Kayıt/sıfırlama uçlarında hesap varlığı ifşa edilmez | Manuel | Var olan / olmayan e-posta için ayırt edilemez yanıt (karar raporda) | P2 | WSTG-IDNT-04 | H-09 |
| AUTH-04 | E-posta kimliği tekil ve normalize | Oto | Büyük/küçük harf varyantı ikinci hesap açamaz | P1 | ASVS 2.x | H-09 |
| AUTH-05 | Login yanıt süresi hesap varlığını ifşa etmez | Manuel | Anlamlı süre farkı yok | P3 | WSTG-ATHN-03 | |
| AUTH-06 | İstemci IP'si güvenilir kaynaktan belirlenir | Konf + Manuel | Yalnız güvenilir proxy'nin eklediği değer kullanılır | P1 | ASVS 2.2.1 | H-02 |
| AUTH-07 | Parola sıfırlama token'ı: tek kullanımlık, 30 dk, hash'li saklanır | Oto | İkinci kullanım ve süre aşımı reddedilir; DB'de düz token yok | P1 | ASVS 2.5, WSTG-ATHN-09 | |
| AUTH-08 | Parola değişiminde mevcut oturumlar sonlandırılır | Oto | Sıfırlama sonrası eski refresh ailesi geçersiz | P1 | ASVS 3.3.3 | |
| AUTH-09 | E-posta doğrulama token'ı: tek kullanımlık, 24 saat | Oto | Tekrar kullanım ve süre aşımı reddedilir | P2 | ASVS 2.5 | |
| AUTH-10 | Güvenlik olayı bildirimi (parola değişti) gönderilir | Oto (Mailpit) | E-posta gider | P3 | ASVS 2.5.5 | |
| AUTH-11 | Kayıt, aydınlatma beyanı olmadan sunucu tarafında reddedilir (UI'ya güvenilmez) | Oto | Beyan yok/false 422, eski sürüm 400; kullanıcı oluşmaz | P1 | V35, KVKK m. 10 | |

## 2. Oturum ve Token Yönetimi (TOK)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref | Hip. |
|---|---|---|---|---|---|---|
| TOK-01 | Access token imzası RS256 ile doğrulanır; başlıktaki `alg` dikkate alınmaz | Oto | İmzasız/başka algoritmalı token 401 | P1 | ASVS 3.5.3, WSTG-SESS-10 | |
| TOK-02 | Süresi dolmuş token reddedilir | Oto | 401 | P1 | ASVS 3.5 | |
| TOK-03 | Logout sonrası access token kara listede | Oto | Aynı token 401 | P1 | ASVS 3.3.1 | |
| TOK-04 | Biçimi bozuk token her durumda 401 döner, 500 değil | Oto | Bozuk biçimli her girdi 401, stack trace yok | P1 | ASVS 7.4.1 | H-03 |
| TOK-05 | Refresh rotasyonu + tekrar kullanım tespiti (aile iptali) | Oto | Grace süresi dışındaki tekrar kullanımda tüm aile iptal | P1 | ASVS 3.5 | |
| TOK-06 | Refresh cookie öznitelikleri | Oto + Manuel | `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` | P1 | ASVS 3.4, WSTG-SESS-02 | |
| TOK-07 | Cookie kullanan uçlarda CSRF koruması | Manuel | Yabancı origin'den tetiklenen refresh/logout etkisiz | P2 | WSTG-SESS-05 | H-13 |
| TOK-08 | Token ortamlar arası taşınamaz | Konf | Her ortamın ayrı anahtar çifti; `iss`/`aud` kararı raporda | P2 | ASVS 3.5.3 | H-14 |
| TOK-09 | Access token'da hassas veri yok | Oto | Payload yalnız `sub, roles, jti, iat, exp` | P2 | ASVS 3.5 | |
| TOK-10 | Frontend token'ı kalıcı depolamada tutmaz | Manuel (DevTools) | localStorage/sessionStorage'da access/refresh token yok | P2 | ASVS 3.2.3 | |
| TOK-11 | PAT düz metni DB'de yok, yalnız oluşturma yanıtında bir kez görünür | Oto | `personal_access_tokens`'ta SHA-256 hash; listeleme yanıtında token yok | P1 | ADR-0018, ASVS 2.10 | |
| TOK-12 | PAT ile token yönetimi yapılamaz (oluştur/listele/iptal) | Oto (mevcut `AccessTokenIntegrationTest`) | PAT'li istek 403; JWT'li aynı istek 2xx | P1 | ADR-0018 | |
| TOK-13 | PAT `SYSTEM_ADMIN` yetkisi taşımaz | Oto | Sistem yöneticisinin PAT'i ile admin ucu 403 | P1 | ADR-0018 | |
| TOK-14 | İptal edilen/süresi dolan PAT anında reddedilir | Oto | İptal sonrası ilk istek 401 | P1 | ASVS 3.3.1 | |
| TOK-15 | JWT/PAT ayrıştırması karışmaz: `wf_pat_` önekli bozuk değer ve PAT gövdeli JWT 401 | Oto | 401, 500 değil; çift kimlik doğrulama yok | P2 | ASVS 7.4.1 | H-03 |
| TOK-16 | PAT hız sınırı token başına; aşımda 429 + `Retry-After`, geçersiz denemeler bütçe tüketmez | Oto | Eşik üstü 429 | P2 | API4, ADR-0018 | |

## 3. Yetkilendirme — Dikey (AUTHZ)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref | Hip. |
|---|---|---|---|---|---|---|
| AUTHZ-01 | Rol matrisi tüm uçlarda uygulanıyor | Oto (mevcut `HttpAuthorizationIntegrationTest`) | Yeni uç eklenince matrise girmeyen uç CI'da yakalanır | P1 | API5, WSTG-ATHZ-02 | |
| AUTHZ-02 | Matrisin kapsamı eksiksiz | Oto | OpenAPI'deki uç listesi ile matris karşılaştırılır; fark = test kırılır | P1 | API9 | |
| AUTHZ-03 | Mass assignment yok: istemci rol/workspace/sahip alanlarını set edemez | Oto + DAST | Fazladan alanlar yok sayılır | P1 | API3, ASVS 5.1.2 | |
| AUTHZ-04 | Kişisel kaynaklar yalnız sahibine açık (kayıtlı görünüm, yorum düzenleme, retro maddesi) | Oto | Aynı kiracıdaki başka kullanıcı 403/404 | P1 | API1 | |
| AUTHZ-05 | Onaylı görev kilidi toplu işlemle de korunuyor | Oto | Bulk istek tümüyle reddedilir/geri alınır | P2 | ASVS 4.1 | |
| AUTHZ-06 | SYSTEM_ADMIN uçları yalnız SYSTEM_ADMIN'e açık | Oto | Diğer tüm roller 403 | P1 | API5 | |
| AUTHZ-07 | Yetki geri alındığında etki süresi bilinen sınırda | Manuel | Workspace rolü anında, global rol ≤ token TTL | P3 | ASVS 4.1.x | H-12 |
| AUTHZ-08 | Otomasyon kuralları kullanıcının kendi yetkisini aşan eylem yaptırmaz | Oto | Kural yönetimi ADMIN/MANAGER; sistem aktörü döngüye girmez | P2 | ADR-0016 | |
| AUTHZ-09 | Yetki matrisi ADR-0019 izin tablosuyla birebir (Dalga 5 sonrası) | Oto (mevcut `HttpAuthorizationIntegrationTest`, `RolePermissionsTest`) | Beklenen izinler testte elle yazılı; üretim eşlemesi çağrılmaz | P1 | ADR-0019 | |
| AUTHZ-10 | DEVELOPER görev oluşturamaz ve planlama alanlarını (puan, son tarih, sprint) değiştiremez; tekil, toplu ve genişletilmiş oluşturma yollarının hepsinde | Oto | 403; toplu istekte hiçbir değişiklik uygulanmaz | P1 | ADR-0019 | |
| AUTHZ-11 | `TASK_SELF_ASSIGN` yalnız atanmamış göreve ve yalnız kendine | Oto | Başkasına atama ya da atanmış görevi devralma 403 | P1 | ADR-0019 | |
| AUTHZ-12 | `SUBTASK_CREATE_OWN` yalnız kendisine atanmış görevin altında | Oto | Başkasının görevinin altına alt görev 403; puanlı alt görev reddedilir | P1 | ADR-0019, ADR-0021 | |
| AUTHZ-13 | Açık bloklayanı olan görev hiçbir yoldan Done olamaz (tekil, toplu, sürükle-bırak, GitHub PR merge, otomasyon) | Oto | API 400; GitHub tüketicisi atlar + bildirim yazar, DLT'ye düşmez | P2 | ADR-0020 | |
| AUTHZ-14 | `/me/permissions` başka workspace'in iznini döndürmez | Oto | Üye olunmayan `X-Workspace-Id` 403, başlıksız 400 | P1 | ADR-0019 | |

## 4. Kiracı İzolasyonu — Yatay / BOLA (TEN)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref | Hip. |
|---|---|---|---|---|---|---|
| TEN-01 | Tüm id'li uçlarda kiracılar arası erişim yok | Oto (OpenAPI'den türetilen harness) | A kiracısının kimliğiyle B'nin her kaynağına okuma/yazma/silme = 404 | P1 | API1, WSTG-ATHZ-04 | |
| TEN-02 | `X-Workspace-Id` üyelik doğrulaması | Oto | Üye olunmayan workspace 403 | P1 | API1 | |
| TEN-03 | Liste/arama/rapor/analitik yanıtlarında yalnız kendi kiracının verisi | Oto | B verisi hiçbir koleksiyon yanıtında görünmez | P1 | API1 | |
| TEN-04 | Gövde içindeki id'ler (bulk, bağımlılık, parent, etiket) kiracıya bağlı | Oto | Karışık kiracı id'si içeren istek tümüyle reddedilir | P1 | API1 | |
| TEN-05 | RLS'siz tablolarda izolasyon servis kodunda sağlanıyor (üyeler, davetler, tercihler) | Oto | Başka kiracının üye/davet kaydına erişim yok | P1 | API1 | |
| TEN-06 | Geçersiz workspace başlığı düzgün reddedilir | Oto | 400/403, 500 değil | P2 | ASVS 7.4.1 | H-03 |
| TEN-07 | Arka plan işleri ve Kafka tüketicileri doğru kiracı bağlamında çalışır | Oto (mevcut entegrasyon testleri) | Olay yalnız kendi kiracısının verisini etkiler | P1 | Mimari.md §4 | |
| TEN-08 | Bağlantı havuzunda kiracı bağlamı sızmaz | Oto | Ardışık farklı kiracı istekleri aynı bağlantıda karışmaz (`SET LOCAL`) | P1 | Mimari.md §4 | |
| TEN-09 | Mention yalnız aynı workspace üyesine çözülür | Oto | B üyesinin `@[uuid]`'i yorumda kalır ama bildirim/e-posta üretmez | P1 | ADR-0009 | |
| TEN-10 | Alt görev ve genişletilmiş oluşturmadaki id'ler (parent, atanan, sprint, etiket) kiracıya bağlı | Oto | B'ye ait id içeren istek reddedilir, görev oluşmaz (tek tx) | P1 | API1, ADR-0021 | |
| TEN-11 | `?task=KEY-NO` / UUID derin linki başka kiracının görevini açmaz | Manuel | B'nin anahtarı A oturumunda "bulunamadı" | P2 | API1 | |

## 5. Veritabanı (DB)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref | Hip. |
|---|---|---|---|---|---|---|
| DB-01 | `workspace_id` taşıyan her tabloda RLS + FORCE açık; istisnalar açık listede | Oto (katalog testi) | Listede olmayan RLS'siz tablo = test kırılır | P1 | Mimari.md §4 | |
| DB-02 | RLS politikaları fail-closed (boş GUC → satır yok) | Oto | GUC set edilmeden sorgu 0 satır | P1 | V6 | |
| DB-03 | Uygulama rolü süper kullanıcı değil, `BYPASSRLS` yok, tablo sahibi değil | Oto (katalog testi) | Hepsi false | P1 | ASVS 1.4 | |
| DB-04 | Uygulama rolü şemada nesne oluşturamaz | Oto (katalog testi) | `CREATE` yetkisi yok | P2 | ASVS 1.4.3 | H-04 |
| DB-05 | Yalnız ekleme yapılan tablolarda uygulama rolünün UPDATE/DELETE yetkisi yok (ya da gerekçeli) | Oto (katalog testi) | Gerekçesiz yetki yok | P2 | ASVS 1.4.3 | H-04 |
| DB-06 | SECURITY DEFINER fonksiyonlar: sabit ve güvenli `search_path`, PUBLIC'ten EXECUTE geri alınmış | Oto (katalog testi) | Uygulama rolünün yazabildiği şema `search_path`'te yok | P1 | PostgreSQL docs "Writing SECURITY DEFINER Functions Safely" | H-04 |
| DB-07 | Partition tablolarına doğrudan erişim kapalı | Oto | Uygulama rolü partition'ı doğrudan sorgulayamaz | P2 | V10 | |
| DB-08 | Hassas veriler hash'li/şifreli saklanır | Oto + Konf | Parola bcrypt; token'lar SHA-256; Slack adresi AES-GCM | P1 | ASVS 2.4, 6.2 | |
| DB-09 | DB bağlantısı TLS ile (sunucu kurulumunda) | Konf | `sslmode=verify-full` ya da aynı host/özel ağ gerekçesi | P2 | ASVS 9.2.2 | |
| DB-10 | Yedekler şifreli ve geri yükleme denenmiş | Konf + Manuel | Belgelenmiş ve bir kez denenmiş prosedür | P2 | ASVS 1.x | |

## 6. Enjeksiyon ve Girdi İşleme (INJ)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref | Hip. |
|---|---|---|---|---|---|---|
| INJ-01 | SQL ifadelerinde kullanıcı girdisi yalnız bind parametresiyle | SAST (CodeQL, find-sec-bugs) + kod incelemesi | String birleştirme yalnız yer tutucu/sabit sütun adı üretir | P1 | ASVS 5.3.4, WSTG-INPV-05 | |
| INJ-02 | Dinamik SQL üreten yardımcıların girdisi sabit/sayı | Kod incelemesi | `TaskDependencyRepository`, `PeriodReportRepository` yalnız `?n` ve sabit sütun üretir | P1 | ASVS 5.3.4 | |
| INJ-03 | Uçlarda enjeksiyon taraması temiz | DAST (ZAP active scan, kimlikli) | Yüksek/orta SQLi bulgusu yok | P1 | WSTG-INPV-05 | |
| INJ-04 | Arama sorgusu özel karakterlerde 500 vermez | Oto | Her girdi 200 (boş/sonuçlu) ya da 400 | P2 | ASVS 7.4.1 | |
| INJ-05 | Sayfalama cursor'u ve sayı parametreleri doğrulanır | Oto | Bozuk cursor/limit 400, 500 değil | P2 | ASVS 5.1.3 | |
| INJ-06 | E-posta başlıklarına (konu, alıcı) CRLF enjeksiyonu yok | Oto | Satır sonu içeren ad/başlık konuya taşınmaz | P2 | WSTG-INPV-10 | |
| INJ-07 | Log enjeksiyonu yok (kullanıcı girdisi satır sonuyla loga yazılmaz) | Kod incelemesi | Yapılandırılmış log ya da kaçış | P3 | ASVS 7.3.1 | |
| INJ-08 | İstek gövdesi boyut ve JSON derinlik sınırları | Oto + Konf | Aşırı büyük gövde 413; derin iç içe JSON reddedilir | P2 | ASVS 13.1 | |
| INJ-09 | Tüketicide yazılan alanlar sütun sınırını aşmaz (bildirim gövdesi, telemetri mesajı) | Oto | Uzun girdi kırpılır/özetlenir; kalıcı hata ve DLT yok | P2 | V34 notu | |

## 7. İstemci Tarafı ve XSS (XSS / FE)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref | Hip. |
|---|---|---|---|---|---|---|
| XSS-01 | Kullanıcı metni hiçbir yerde HTML olarak yorumlanmaz | SAST (Semgrep React kuralları) + Manuel | `dangerouslySetInnerHTML` yok; markdown ham HTML render etmez | P1 | ASVS 5.3.3, WSTG-INPV-02 | |
| XSS-02 | Markdown bağlantılarında tehlikeli URL şemaları nötralize | Manuel | Yalnız `http(s)`/`mailto` tıklanabilir | P1 | ASVS 5.3.3 | |
| XSS-03 | Kullanıcı girişli URL alanları (toplantı linki) şema allow-list'iyle doğrulanır | Oto (backend) + Manuel (UI) | Yalnız `https://` kabul | P1 | ASVS 5.1.4 | H-05 |
| XSS-04 | E-posta ve Slack şablonlarında kullanıcı metni kaçışlanır | Oto | HTML/Slack kontrol karakterleri kaçışlı | P1 | ASVS 5.3.1 | |
| FE-01 | Güvenlik başlıkları: CSP, HSTS, X-Content-Type-Options, frame-ancestors, Referrer-Policy | DAST (ZAP baseline) + Konf | ZAP baseline'da başlık uyarısı yok | P1 | ASVS 14.4, WSTG-CONF-12 | H-08 |
| FE-02 | Clickjacking koruması | DAST | `frame-ancestors 'none'` | P2 | WSTG-CLNT-09 | |
| FE-03 | Üretim bundle'ında sır, iç URL, kaynak haritası yok | Konf | `dist` taramasında sır yok; source map yayında değil | P2 | ASVS 14.3 | |
| FE-04 | Açık yönlendirme yok (`state.from`, davet sonrası dönüş) | Manuel | Yalnız uygulama içi göreli yollar | P2 | WSTG-CLNT-04 | |

## 8. WebSocket / STOMP (WS)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref | Hip. |
|---|---|---|---|---|---|---|
| WS-01 | CONNECT kimlik doğrulaması zorunlu | Oto (mevcut) | Token'sız CONNECT reddedilir | P1 | WSTG-CLNT-10 | |
| WS-02 | Başka kiracının kanalına abonelik reddedilir | Oto (mevcut) | SUBSCRIBE hatası | P1 | API1 | |
| WS-03 | Handshake origin kontrolü | Oto + Konf | İzinli liste dışındaki origin reddedilir | P1 | WSTG-CLNT-10 | |
| WS-04 | Üyelikten çıkarılan kullanıcı olay almayı bırakır | Oto | Makul sürede bağlantı kapanır/abonelik düşer (karar raporda) | P2 | ASVS 3.3 | H-11 |
| WS-05 | Token süresi dolunca/kara listeye girince bağlantı sonlanır | Oto | Karar ve sınır raporda | P2 | ASVS 3.3 | H-11 |
| WS-06 | Hata mesajları girdiyi yansıtmaz, iç bilgi ifşa etmez | Manuel | Sabit hata metni | P3 | ASVS 7.4.1 | H-15 |
| WS-07 | Mesaj boyutu ve bağlantı sayısı sınırlı | Konf | Varsayılanlar bilinçli ayarlanmış | P3 | ASVS 13.x | |

## 9. Dış Entegrasyonlar (EXT)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref | Hip. |
|---|---|---|---|---|---|---|
| EXT-01 | Webhook imzası sabit zamanlı doğrulanır; bilinmeyen id ile yanlış imza ayırt edilemez | Oto (mevcut) | İkisi de aynı 401 | P1 | ADR-0004 | |
| EXT-02 | Webhook tekrar gönderimi idempotent | Oto (mevcut) | Aynı delivery ikinci kez etki üretmez | P1 | ADR-0004 | |
| EXT-03 | Webhook gövde sınırı imzadan önce uygulanır | Oto (mevcut) | 413 | P1 | ADR-0004 | |
| EXT-04 | Secret rotasyonu eski secret'ı geçersiz kılar (TTL sınırında) | Oto | Rotasyon sonrası eski imza reddedilir | P2 | ADR-0004 | |
| EXT-05 | Slack giden istek SSRF allow-list'i | Oto (mevcut 32 vaka) | Yalnız `hooks.slack.com/services/...`; yönlendirme takip edilmez | P1 | ASVS 12.6, ADR-0005 | |
| EXT-06 | E-postadaki bağlantılar yalnız `APP_PUBLIC_URL`'den üretilir (Host başlığından değil) | Oto + Konf | Host başlığı değişse de link aynı | P1 | WSTG-INPV-17 | |
| EXT-07 | Giden SMTP TLS ve kimlik doğrulamalı (sunucu kurulumunda) | Konf | STARTTLS/SMTPS açık | P1 | ASVS 9.2 | |

## 10. İş Mantığı (BL)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref | Hip. |
|---|---|---|---|---|---|---|
| BL-01 | Davet token'ı: tek kullanımlık, 7 gün, iptal edilince geçersiz | Oto (mevcut) | Kabul sonrası/iptal sonrası reddedilir | P1 | ASVS 2.5 | |
| BL-02 | Davet yalnız davet edilen e-postaya sahip hesapla kabul edilir | Oto (mevcut) | Farklı hesap 400 | P1 | V25 | |
| BL-03 | Davet kabulü doğrulanmış e-posta şartı | Oto | Doğrulanmamış hesapla kabul kararı raporda | P1 | ASVS 2.x | H-09 |
| BL-04 | Son ADMIN kendini düşüremez/çıkaramaz (workspace sahipsiz kalmaz) | Oto | 400 | P2 | ASVS 4.x | |
| BL-05 | Idempotency anahtarı kullanıcıya ve isteğe bağlı | Oto | Farklı kullanıcı aynı anahtarla önbellekteki yanıtı alamaz | P1 | ASVS 4.x | H-01 |
| BL-06 | Kimlik doğrulama uçları idempotency önbelleğinin dışında | Oto | Login/refresh yanıtı önbelleğe alınmaz | P1 | ASVS 3.x | H-01 |
| BL-07 | Toplu işlem atomik ve sınırlı (≤100) | Oto (mevcut) | Hata = tam rollback | P2 | V27 | |
| BL-08 | Tarih/durum iş kuralları API üzerinden de uygulanıyor (UI'ya güvenilmiyor) | Oto (mevcut) | Geçmiş tarih, onaylı görev kilidi API'de 400 | P2 | ASVS 11.1 | |

## 11. Kaynak Tüketimi / DoS (DOS)

> Yalnız test ortamında veya kullanıcı yokken; hedef davranışın doğrulanmasıdır, yük testi değildir.

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref | Hip. |
|---|---|---|---|---|---|---|
| DOS-01 | Kimlik doğrulama uçlarında hız sınırı (login, kayıt, sıfırlama, davet) | Oto + Konf | Eşik üstü 429 | P1 | API4, ASVS 11.1.4 | H-10 |
| DOS-02 | E-posta gönderen uçlar kurban başına sınırlı | Oto | Aynı alıcıya kısa sürede N'den fazla e-posta gitmez | P1 | API4 | H-10 |
| DOS-03 | Pahalı okuma uçlarında (arama, rapor, tahmin, analitik) hız sınırı ve zaman aşımı | Oto + Konf | 429 / statement_timeout | P2 | API4 | H-10 |
| DOS-04 | Tüm liste uçlarında üst limit | Oto | `limit` üst sınırı zorlanır | P2 | API4 | |
| DOS-05 | DB `statement_timeout` ve havuz boyutu bilinçli ayarlı | Konf | Değerler belgelenmiş | P2 | ASVS 1.x | |
| DOS-06 | Kafka zehirli mesajı tüketiciyi durdurmaz | Oto (mevcut DLT) | Mesaj DLT'ye düşer | P2 | Faz 2 | |
| DOS-07 | Telemetri ve geri bildirim uçları kullanıcı başına sınırlı (bilinen eksik: hız sınırı yok) | Oto | Eşik üstü 429; parti boyutu üst sınırı zorlanır | P2 | API4 | H-10 |
| DOS-08 | Genişletilmiş oluşturmanın ürettiği olay patlaması (~6 realtime olay) istemcide sınırlı refetch üretir | Manuel | Kabul/karar raporda (güvenlikten çok kapasite) | P3 | | |

## 12. Yapılandırma ve Bilgi İfşası (CFG)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref | Hip. |
|---|---|---|---|---|---|---|
| CFG-01 | Yalnız TLS (HTTP→HTTPS yönlendirme, HSTS), güncel protokol/şifre takımları | Araç (testssl.sh) | TLS 1.2+/1.3, derece A | P1 | ASVS 9.1, WSTG-CRYP-01 | H-08 |
| CFG-02 | Actuator yalnız `health`/`info`, dışarıya yalnız gereken probe | Konf + DAST | `env`, `heapdump` vb. yok; dışarıdan gerekmiyorsa kapalı | P1 | WSTG-CONF-05 | H-08 |
| CFG-03 | API dokümanı (Swagger) prod'da kapalı ya da kimlikli | Konf | Prod'da 404/401 | P2 | WSTG-CONF-05 | H-06 |
| CFG-04 | Hata yanıtlarında stack trace/sürüm/iç yol yok | DAST + Oto | ProblemDetail, genel mesaj | P1 | ASVS 7.4.1, WSTG-ERRH-01 | |
| CFG-05 | Sunucu sürüm başlıkları gizli | DAST | `Server` başlığında sürüm yok | P3 | WSTG-INFO-02 | H-08 |
| CFG-06 | CORS yalnız bilinen origin, credentials ile joker yok | Oto + Konf | Yabancı origin preflight reddedilir | P1 | WSTG-CLNT-07 | |
| CFG-07 | Prod profilinde dev anahtarları/varsayılanlarla açılış reddedilir | Oto (mevcut guard testleri) | JWT, webhook master key ve public URL guard'ları çalışır | P1 | ADR-0003 | |
| CFG-08 | Tüm sırlar env/secret'tan, image ve repoda yok | SAST (gitleaks) + Konf | gitleaks temiz; image katmanlarında sır yok | P1 | ASVS 2.10 | |
| CFG-09 | Sunucudaki gerçek reverse proxy yapılandırması repodaki şablonla aynı (`deploy/Caddyfile`: başlıklar, `/actuator/*` 404) | Manuel (sunucuda `curl -I`) | Şablondaki başlıkların hepsi yanıtta; `/actuator/health` dışarıdan 404 | P1 | WSTG-CONF-05 | H-08 |

## 13. Altyapı (INF)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref | Hip. |
|---|---|---|---|---|---|---|
| INF-01 | Dışarıdan yalnız 80/443 (ve yönetim için kısıtlı SSH) erişilebilir | Araç (dış ağdan port taraması) | Postgres/Redis/Kafka/Mailpit/8080 kapalı | P1 | ASVS 1.x | H-07 |
| INF-02 | Backend doğrudan değil, yalnız reverse proxy üzerinden erişilebilir | Araç + Konf | 8080 dışarıya kapalı | P1 | | H-02, H-07 |
| INF-03 | Varsayılan parola yok (Postgres rolleri, Redis, SMTP) | Konf | Tümü güçlü, benzersiz | P1 | ASVS 2.10 | H-07 |
| INF-04 | Redis parola korumalı ve yalnız iç ağda | Konf | `requirepass`/ACL; bind iç ağ | P1 | | H-07 |
| INF-05 | Kafka yalnız iç ağda (ve/veya SASL/TLS) | Konf | Dış listener yok | P1 | ADR-0010 riski | H-07 |
| INF-06 | Mailpit prod'da yok | Konf | Gerçek SMTP sağlayıcı | P1 | | H-07 |
| INF-07 | Container'lar non-root, salt-okunur FS mümkün olduğunca, gereksiz capability yok | Konf + Araç (trivy config) | Kritik yapılandırma bulgusu yok | P2 | CIS Docker | H-16 |
| INF-08 | Host: otomatik güvenlik güncellemesi, SSH anahtar ile, parola girişi kapalı, fail2ban | Konf | Kontrol listesi (04) tamam | P1 | CIS | |
| INF-09 | K8s manifestleri: securityContext, NetworkPolicy, secret izolasyonu | Araç (trivy config / kube-linter) | Kritik bulgu yok | P3 | CIS K8s | |

## 14. Tedarik Zinciri (SC)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref |
|---|---|---|---|---|---|
| SC-01 | Maven bağımlılıklarında bilinen kritik/yüksek açık yok | SCA (OSV-Scanner / trivy fs / Dependency-Check) | Kritik/yüksek yok ya da gerekçeli istisna | P1 | ASVS 14.2 |
| SC-02 | npm bağımlılıklarında bilinen kritik/yüksek açık yok | SCA (`npm audit`, OSV-Scanner) | Aynı | P1 | ASVS 14.2 |
| SC-03 | Container imajları taranmış | trivy image | Kritik yok | P1 | |
| SC-04 | CI action'ları SHA'ya sabit, en az yetki | Konf | `permissions` minimal, action'lar sabit | P2 | |
| SC-05 | Gizli anahtar taraması tüm geçmişte temiz | gitleaks (mevcut) | Temiz | P1 | |
| SC-06 | Kod analizi: CodeQL (Java + JS/TS) CI'da | SAST | Yüksek bulgu yok | P1 | |

## 15. Loglama ve İzleme (LOG)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref |
|---|---|---|---|---|---|
| LOG-01 | Güvenlik olayları loglanır (başarısız login, kilitleme, token reuse, 403 yoğunluğu) | Kod incelemesi + Manuel | Olay, kullanıcı id'si ve zaman damgasıyla loglanır | P2 | ASVS 7.1, 7.2 |
| LOG-02 | Loglarda sır/token/parola yok | Manuel (log taraması) | Yok | P1 | ASVS 7.1.1 |
| LOG-03 | Loglar saklanıyor ve erişim kısıtlı | Konf | Saklama süresi belgelenmiş | P3 | ASVS 7.3 |
| LOG-04 | Temel uyarılar (5xx oranı, login hata oranı) | Konf | En az bir uyarı kanalı | P3 | |
| LOG-05 | Hata telemetrisi (`error_events.message`) kişisel veri ve sır taşımaz (bilinen eksik: `ex.getMessage()` ham saklanıyor) | Kod incelemesi + Oto | E-posta/token içeren istisna mesajı maskelenir ya da yalnız tip saklanır | P1 | ASVS 7.1.1, KVKK m. 4 | |

## 16. Kişisel Veri / KVKK (PRIV)

| ID | Kontrol | Yöntem | Beklenen | Önc. | Ref |
|---|---|---|---|---|---|
| PRIV-01 | Aydınlatma beyanı kanıtlanabilir: sürüm + zaman saklanır, ilk onay anı tekrar onayla ezilmez | Oto | `users.privacy_notice_version/accepted_at`; aynı sürüme ikinci onay zamanı değiştirmez | P1 | KVKK m. 10, V35 |
| PRIV-02 | Aydınlatma onayı PAT ile verilemez | Oto | 403 | P2 | V35 |
| PRIV-03 | Aydınlatma metni gerçek veri akışıyla uyumlu (işlenen veri, alıcılar, saklama süreleri) | Kod incelemesi | Metinde olmayan bir veri/alıcı kodda yok: telemetri, SMTP, Slack, GitHub, loglar, IP (brute-force sayacı) | P1 | KVKK m. 10 |
| PRIV-04 | Saklama süreleri uygulanıyor (refresh 7 gün, outbox 7 gün, processed_events 30 gün; telemetri ve log süresi tanımlı) | Konf + Oto | Tanımsız saklama yok; temizleme işi çalışıyor | P2 | KVKK m. 7 |
| PRIV-05 | İlgili kişi başvurusu yolu çalışıyor (metindeki iletişim adresi; hesap silme şimdilik elle) | Manuel | Başvuru adresi geçerli; elle silme prosedürü belgelenmiş | P2 | KVKK m. 11, 13 |
| PRIV-06 | Kişisel veri istemci depolamasında gereksiz yere tutulmaz | Manuel (DevTools) | localStorage'da yalnız e-posta (oturum) ve tercih bayrakları; görev/yorum içeriği yok | P3 | ASVS 8.2 |

---

## Otomasyona Taşıma Planı

Koşum sonrası `Oto` işaretli ve henüz testi olmayan kontroller şu yeni test sınıflarına toplanır:

| Test sınıfı (öneri) | Kapsadığı ID'ler |
|---|---|
| `security/DatabaseSecurityFitnessTest` | DB-01..07 |
| `security/CrossTenantAccessHarnessTest` | TEN-01, TEN-03, TEN-04, TEN-05 |
| `security/TokenHardeningTest` | TOK-01..05, TOK-09, AUTH-02, AUTH-07..09 |
| `security/InputHandlingTest` | INJ-04..06, INJ-08, TEN-06 |
| `security/IdempotencyIsolationTest` | BL-05, BL-06 |
| `security/RateLimitTest` | DOS-01..04, DOS-07, TOK-16 (rate limit eklendikten sonra) |
| `security/AccessTokenHardeningTest` | TOK-11, TOK-13..15 (TOK-12 mevcut testte) |
| `security/PermissionPathsTest` | AUTHZ-10..14, TEN-09, TEN-10 |
| `user/PrivacyNoticeIntegrationTest` (KVKK dilimiyle gelir) | AUTH-11, PRIV-01, PRIV-02 |
| CI: CodeQL + OSV-Scanner + ZAP baseline (zamanlanmış) | INJ-01, SC-01..03, SC-06, FE-01, CFG-04 |
