# 01 — Tehdit Modeli

## 1. Korunan Varlıklar (önem sırasıyla)

| # | Varlık | Neden kritik |
|---|---|---|
| V1 | **Kiracı verisi** (görev, yorum, açıklama, toplantı, rapor, hedef) | Çok kiracılı SaaS'ta kiracılar arası sızıntı = ürünün sonu |
| V2 | **Kimlik bilgileri** (parola hash, refresh token, doğrulama/sıfırlama/davet token'ları) | Hesap ele geçirme |
| V3 | **İmza anahtarları ve sırlar** (JWT private key, `WEBHOOK_SECRET_MASTER_KEY`, DB/SMTP parolaları) | Tüm kimlik modelinin çökmesi; master key hem webhook secret'larını hem Slack adres şifrelemesini türetir |
| V4 | **Dış entegrasyon yetkileri** (GitHub webhook → görev durumu, Slack kanalına yazma, e-posta gönderimi) | Sistem üzerinden üçüncü taraflara saldırı / spam |
| V5 | **Hizmet sürekliliği** (DB havuzu, Kafka tüketicileri, Redis) | Paylaşılan Hikari havuzu: bir uçtaki tüketim tüm kiracıları etkiler |
| V6 | **Kişisel veri** (e-posta, ad) — KVKK kapsamı | Yasal yükümlülük |

## 2. Aktörler

| Aktör | Yetenek |
|---|---|
| A0 Anonim internet kullanıcısı | Public uçlar: auth, davet önizleme, webhook, health, Swagger, `/ws` handshake |
| A1 Kayıtlı ama hiçbir workspace'e üye olmayan kullanıcı | Geçerli JWT, `X-Workspace-Id` ile oynama |
| A2 Başka kiracının üyesi (en olası saldırgan) | Geçerli JWT + kendi workspace'i; id tahmini/çalma ile BOLA |
| A3 Aynı kiracıda düşük rollü üye (VIEWER/DEVELOPER) | Dikey yetki yükseltme, başkasının kişisel verisi (kayıtlı görünüm, yorum) |
| A4 Kötü niyetli/ele geçirilmiş dış sistem | Sahte webhook, tekrar gönderim, büyük/bozuk gövde |
| A5 Ağ üzerindeki saldırgan (aynı sunucu ağı / internet) | Açıkta kalmış Postgres/Redis/Kafka/Mailpit portları, TLS'siz trafik |
| A6 İçeriden / tedarik zinciri | Bağımlılık açığı, sızmış secret, kötü niyetli paket |
| A7 Çalınmış PAT sahibi | Kullanıcının workspace rolleri (SYSTEM_ADMIN hariç), `Authorization: Bearer wf_pat_...`; token yönetimi ve aydınlatma onayı kapalı |

## 3. Güven Sınırları ve Veri Akışı

```
 İnternet
   │
   ▼
 Caddy (host, TLS sonlandırma + güvenlik başlıkları, /actuator/* → 404; şablon: deploy/Caddyfile)
   │ localhost:3000
   ▼
 nginx (frontend container, 127.0.0.1:3000→80)  ──── statik SPA
   │ /api/  /ws/  /actuator/   (X-Forwarded-For eklenir)
   ▼
 [SINIR-1] Spring Security zinciri
   JwtAuthenticationFilter → PatAuthenticationFilter → WorkspaceContextFilter → IdempotencyFilter → Controller
   │  @PreAuthorize (rol)            │ TenantContext
   ▼                                 ▼
 [SINIR-2] Servis katmanı (@Transactional) → TenancyGuardAspect → SET LOCAL app.current_workspace_id
   ▼
 [SINIR-3] PostgreSQL RLS (app_runtime, NOSUPERUSER)   ← RLS'siz tablolar: servis koduna güvenir
   │
 Outbox → Kafka (PLAINTEXT) → Tüketiciler (TenantExecutor.runAs) → Slack / SMTP / WebSocket
   ▲
 [SINIR-4] Webhook girişi: /api/v1/webhooks/github/{id} (HMAC, JWT yok)
 [SINIR-5] WebSocket: CONNECT (JWT) / SUBSCRIBE (üyelik)
 [SINIR-6] Redis: brute-force sayaçları, JWT kara listesi, idempotency, tahmin cache'i
```

**RLS'e tabi OLMAYAN tablolar** (izolasyon tamamen uygulama kodunda — test önceliği yüksek):
`users`, `workspaces`, `workspace_users`, `refresh_tokens`, `verification_tokens`,
`workspace_invitations`, `notification_preferences`, `outbox_events`, `processed_events`,
`task_counters`, `personal_access_tokens` (V32), `usage_events`, `error_events` (V33).
**FORCE'suz RLS:** `webhook_integrations` (bilinçli, ADR-0004).

> 2026-10-29 notu: Diyagram sunucu kurulumuna (2026-10-28) göre güncellendi. Sunucudaki gerçek Caddy
> yapılandırması repodaki şablondan farklı olabilir (alan adı yerine IP); CFG-09 bunu doğrular.

## 4. STRIDE Özeti

| Tehdit | Sınır | Örnek senaryo | Mevcut kontrol | Katalog |
|---|---|---|---|---|
| **S**poofing | S1, S5 | Sahte/manipüle JWT, çalınan refresh token | RS256 imza, rotasyon + reuse detection, kara liste | AUTH-*, TOK-* |
| **S**poofing | S4 | Sahte GitHub olayı | HMAC-SHA256, sabit zamanlı karşılaştırma | EXT-* |
| **T**ampering | S2, S3 | Başka kiracının görevini güncelleme | RLS + FORCE, servis kontrolleri | TEN-*, DB-* |
| **T**ampering | S1 | SQL injection, mass assignment | Parametreli sorgu, DTO record'ları | INJ-*, AUTHZ-* |
| **R**epudiation | tümü | "Ben yapmadım" | `task_events` (aktör), log | LOG-* |
| **I**nformation disclosure | S1–S3 | BOLA, hata mesajı sızıntısı, Swagger | ProblemDetail (stack trace yok) | TEN-*, CFG-* |
| **I**nformation disclosure | S5, S6 | WebSocket kanalına izinsiz abonelik, cache replay | SUBSCRIBE üyelik kontrolü | WS-*, BL-* |
| **D**enial of service | S1, S6 | Arama/rapor/tahmin ile havuz tüketimi, e-posta bombardımanı | Sınırlı sayfa/limit, **genel rate limit YOK** | DOS-* |
| **E**levation of privilege | S1, S2 | VIEWER → ADMIN işlemi, SYSTEM_ADMIN ucu | `@PreAuthorize`, HTTP rol matrisi testi | AUTHZ-* |
| **E**levation of privilege | S3 | `app_runtime` ile RLS atlatma, SECURITY DEFINER istismarı | NOSUPERUSER, sabit `search_path` | DB-* |

## 5. Ön İnceleme Hipotezleri

Kod okuması sırasında çıkan, **henüz doğrulanmamış** şüpheler ayrı bir dosyada tutulur:
`docs/security/local/ON-INCELEME-HIPOTEZLERI.md` (git'e **girmez**, `.gitignore`'da).

Gerekçe: repo public. Düzeltilmemiş açık ipuçlarını test ve düzeltme öncesinde yayınlamak
saldırgana yol haritası verir. Her hipotez, katalogdaki (02) bir senaryo ID'sine bağlıdır.
Doğrulanan hipotez rapora bulgu olarak, doğrulanmayan "yanlış pozitif" olarak geçer.

## 6. Bilinçli Olarak Kabul Edilmiş Riskler (mevcut ADR'lerden)

| Risk | Kaynak | Test tavrı |
|---|---|---|
| Tek master key'den webhook secret + Slack şifreleme anahtarı türetimi | `docs/adr/README.md#capraz-risk-tek-master-key` | Rotasyon prosedürü dokümante mi, kontrol et |
| E-posta token'ının outbox/Kafka'da düz metin taşınması | ADR-0010 | Kafka erişimi ağda kapalı mı (INF-*) |
| Access token 15 dk geçerli (rol düşürme/çıkarma gecikmesi) | SECURITY_AND_EXCEPTIONS_DESIGN 1.1 | Workspace rolü DB'den okunuyor mu doğrula (AUTHZ) |
| Bağımlılık derin döngüsü tespit edilmez | V19 | Kapsam dışı (güvenlik değil) |
| Davet/üye eklemede e-posta enumeration (yalnız ADMIN'e) | V17 notu | Kabul; ADMIN dışına sızmıyor mu doğrula |
