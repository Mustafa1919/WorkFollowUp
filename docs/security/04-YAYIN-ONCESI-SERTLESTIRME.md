# 04 — Yayın Öncesi Sertleştirme Kontrol Listesi

Sunucuya kurulumdan **önce** hazırlanır, kurulumdan **sonra** tek tek işaretlenir. Her madde 02 kataloğundaki
ilgili kontrol ID'sine bağlıdır. Referans: CIS Benchmarks (Ubuntu Linux, Docker), OWASP ASVS L2.

Durum işaretleri: `[ ]` yapılmadı · `[x]` yapıldı · `[-]` bilinçli olarak uygulanmadı (gerekçe yaz).

## 1. Sunucu (Host)

- [ ] İşletim sistemi güncel; otomatik güvenlik güncellemeleri açık (`unattended-upgrades`) — INF-08
- [ ] SSH: yalnız anahtar ile giriş, `PasswordAuthentication no`, `PermitRootLogin no` — INF-08
- [ ] SSH erişimi mümkünse yalnız belirli IP'lerden; fail2ban ya da eşdeğeri etkin — INF-08
- [ ] Uygulama için ayrı, sudo yetkisiz bir sistem kullanıcısı
- [ ] Güvenlik duvarı: gelen trafikte yalnız 80, 443 ve (kısıtlı) SSH açık — INF-01
- [ ] **Docker ve güvenlik duvarı etkileşimi anlaşıldı:** Docker'ın yayınladığı portlar ufw kurallarını
      atlayabilir. Bu yüzden altyapı servisleri için `ports:` **hiç tanımlanmaz** ya da yalnız
      `127.0.0.1:` adresine bağlanır. Güvenlik duvarına güvenilmez — INF-01, INF-02
- [ ] Saat senkronizasyonu (NTP) açık (token süreleri ve loglar için)
- [ ] Disk şifreleme / sağlayıcı tarafı şifreleme durumu biliniyor

## 2. Ağ ve Reverse Proxy

- [ ] Alan adı + geçerli TLS sertifikası (ör. Let's Encrypt, otomatik yenileme) — CFG-01
- [ ] HTTP → HTTPS kalıcı yönlendirme — CFG-01
- [ ] TLS 1.2 ve 1.3 dışındaki protokoller kapalı — CFG-01
- [ ] Güvenlik başlıkları eklendi — FE-01, FE-02:
  - [ ] `Strict-Transport-Security: max-age=31536000; includeSubDomains`
  - [ ] `Content-Security-Policy` (en azından `default-src 'self'`; `connect-src` API ve `wss:` için;
        `frame-ancestors 'none'`; `object-src 'none'`; `base-uri 'self'`)
  - [ ] `X-Content-Type-Options: nosniff`
  - [ ] `Referrer-Policy: strict-origin-when-cross-origin`
  - [ ] `Permissions-Policy` (kullanılmayan özellikler kapalı)
- [ ] `server_tokens off` — CFG-05
- [ ] `client_max_body_size` bilinçli ayarlı (webhook sınırıyla uyumlu) — INJ-08
- [ ] Proxy, istemci IP başlığını **sıfırdan** set ediyor (istemcinin gönderdiği değer korunmuyor);
      backend yalnız güvenilir proxy'den gelen değeri kullanacak şekilde yapılandırıldı — AUTH-06
- [ ] `/actuator/` dışarıya proxy'lenmiyor (probe'lar iç ağdan) — CFG-02
- [ ] Backend (8080) yalnız proxy'nin erişebildiği iç ağda — INF-02
- [ ] WebSocket yolu (`/ws/`) TLS üzerinden (`wss://`)

## 3. Uygulama Yapılandırması

- [ ] `SPRING_PROFILES_ACTIVE=prod` (fail-closed guard'lar devrede) — CFG-07
- [ ] JWT anahtar çifti **bu ortam için yeni üretildi**; dev anahtarı kullanılmıyor; private key dosya
      izinleri kısıtlı — TOK-08, CFG-07
- [ ] `WEBHOOK_SECRET_MASTER_KEY` ≥ 32 karakter, rastgele üretildi, yedeği güvenli yerde
      (kaybı = tüm webhook secret'ları ve Slack adresleri geçersiz) — CFG-07
- [ ] `APP_PUBLIC_URL` gerçek `https://` alan adı — EXT-06
- [ ] `CORS_ALLOWED_ORIGINS` yalnız gerçek frontend origin'i — CFG-06
- [ ] `SYSTEM_ADMIN_EMAILS` yalnız senin adresin
- [ ] Swagger/OpenAPI prod'da kapalı ya da kimlik doğrulamalı — CFG-03
- [ ] Actuator exposure yalnız `health,info` — CFG-02
- [ ] Log seviyesi prod için uygun; token/parola loglanmıyor — LOG-02
- [ ] Tüm sırlar env dosyasında/secret'ta; dosya izinleri `600`, sahibi uygulama kullanıcısı;
      repoda ve imajda sır yok — CFG-08

## 4. Veritabanı (PostgreSQL)

- [ ] Port dışarıya kapalı (yalnız iç Docker ağı) — INF-01
- [ ] `app_migrator` ve `app_runtime` için güçlü, benzersiz parolalar; varsayılanlar değiştirildi — INF-03
- [ ] `app_runtime`: NOSUPERUSER, BYPASSRLS yok, tablo sahibi değil — DB-03
- [ ] `app_runtime`'ın şemada CREATE yetkisi kaldırıldı (ya da gerekçesi yazıldı) — DB-04
- [ ] Yalnız ekleme yapılan tablolarda gereksiz UPDATE/DELETE yetkisi yok — DB-05
- [ ] Kullanılmayan veritabanları (`tracker_dev`, `tracker_staging`) prod sunucusunda yok
- [ ] `statement_timeout` ve bağlantı limiti ayarlı — DOS-05
- [ ] Otomatik, şifreli ve sunucu dışında saklanan yedek; **geri yükleme bir kez denendi** — DB-10
- [ ] Migration yalnız `migrate` profiliyle ve `app_migrator` kimliğiyle çalışıyor

## 5. Redis

- [ ] Port dışarıya kapalı — INF-04
- [ ] Parola (`requirepass`) ya da ACL tanımlı; uygulama `REDIS_PASSWORD` ile bağlanıyor — INF-04
- [ ] Kalıcılık kararı verildi (kara liste ve kilit sayaçları yeniden başlatmada kaybolabilir — kabul mü?)

## 6. Kafka

- [ ] Dış listener (`EXTERNAL://...:9092`) prod'da yok; yalnız iç ağ — INF-05
- [ ] Topic retention değerleri, processed_events retention'ından kısa — (Mimari.md)
- [ ] E-posta token'ı taşıyan olaylar nedeniyle Kafka erişimi yalnız uygulamada (ADR-0010) — INF-05

## 7. E-posta

- [ ] Mailpit prod'da yok — INF-06
- [ ] Gerçek SMTP sağlayıcısı, `SMTP_AUTH=true`, `SMTP_STARTTLS=true` — EXT-07
- [ ] Gönderen alan adı için SPF, DKIM, DMARC kayıtları
- [ ] Sağlayıcı tarafında gönderim kotası/uyarısı (kötüye kullanım fark edilsin) — DOS-02

## 8. Container'lar

- [ ] Tüm imajlar sabit sürüm/digest ile; `latest` yok
- [ ] Backend non-root (mevcut); frontend/nginx için non-root imaj (ör. `nginxinc/nginx-unprivileged`) — INF-07
- [ ] `restart: unless-stopped`; kaynak limitleri (`mem_limit`, `cpus`)
- [ ] İmajlar yayından önce trivy ile tarandı, kritik açık yok — SC-03
- [ ] Docker socket hiçbir container'a mount edilmedi

## 9. Uygulama Seviyesi Sınırlar

- [ ] Kimlik doğrulama ve e-posta gönderen uçlarda hız sınırı (uygulamada ya da proxy'de) — DOS-01, DOS-02
- [ ] Pahalı okuma uçlarında hız sınırı — DOS-03
- [ ] Proxy seviyesinde genel istek hızı ve bağlantı limiti (`limit_req`, `limit_conn`) — DOS-01

## 10. İzleme ve Operasyon

- [ ] Uygulama ve proxy logları saklanıyor, rotasyon ayarlı — LOG-03
- [ ] En az bir uyarı: servis ayakta değil, 5xx oranı, disk doluluğu — LOG-04
- [ ] Güvenlik olayı durumunda yapılacaklar kısa not olarak yazıldı: anahtar rotasyonu (JWT, master key,
      DB parolaları), tüm oturumları iptal etme, kullanıcı bilgilendirme (KVKK 72 saat bildirimi)
- [ ] Bağımlılık ve imaj güncellemeleri için periyodik takvim (ör. ayda bir)

## 11. Kurulum Sonrası Doğrulama

Kurulum tamamlanınca 02 kataloğundaki **P1** kontrollerinin sunucu üzerinde tekrar doğrulanması ve sonucun
05 şablonuyla raporlanması. Özellikle: INF-01..06, CFG-01..08, FE-01, TOK-06, CORS (CFG-06), TEN-01.

Tüm P1 maddeleri `[x]` ya da gerekçeli `[-]` olmadan kullanıma açılmaz.
