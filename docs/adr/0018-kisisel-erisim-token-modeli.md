# ADR-0018: Kişisel erişim token'ları (PAT) + açık API hız sınırı

- **Durum:** Kabul edildi (Dalga 3.3, 2026-09-26)
- **İlgili:** ADR-0004 (webhook kimlik modeli — dış sistemler için secret tabanlı kimlik; PAT
  bunun tersi yönü kapatır: kullanıcının kendi otomasyonunun API'ye erişimi), Backend-Notlar.md
  "Repository'yi @Transactional servis sınırı dışında çağırma" dersi (authenticate() akışında
  aynı tuzağa düşülmedi, bkz. Karar 4).

## Bağlam

Kullanıcı kendi script/otomasyonlarından (CLI, cron, entegrasyon) uygulamanın REST API'sine JWT
login akışı olmadan erişebilmeli — GitHub/Stripe PAT modeliyle aynı ihtiyaç. Açık API'nin kötüye
kullanımını (yanlışlıkla veya kötü niyetle) sınırlamak için token bazlı hız sınırı gerekli;
`docs/security/local/ON-INCELEME-HIPOTEZLERI.md`'de kayıtlı "genel rate limit yok" bulgusunun
PAT/açık API özelinde kapatılmış hali budur (genel JWT/tarayıcı trafiği kapsam dışı, Gateway
seviyesi rate limit hâlâ Faz 4'e ait).

## Karar

1. **Tek token formatı, prefix ile ayrıştırma: `wf_pat_<256-bit rastgele>`.** JWT (üç noktalı
   base64url segment) ile aynı `Authorization: Bearer` header'ını paylaşır;
   `JwtAuthenticationFilter` ve yeni `PatAuthenticationFilter` aynı header'ı okur, prefix
   eşleşmeyince diğerine es geçer — çift işleme veya çift DB sorgusu yok. Duz metin token
   `TokenHasher.sha256Hex` ile hash'lenip DB'ye yazılır (refresh_tokens/verification_tokens ile
   AYNI ilke), sadece oluşturma yanıtında bir defa görünür.
2. **V1'de scope/kapsam sistemi YOK — token, oluşturan kullanıcının global rollerini (`USER`,
   `SYSTEM_ADMIN` HARİÇ) taşır.** `SYSTEM_ADMIN` bilerek PAT'e taşınmaz: çalınan bir PAT'in blast
   radius'u kullanıcının normal iş erişimiyle sınırlı kalır, admin yetkisi asla token üzerinden
   sızmaz. Endpoint-bazlı ince taneli scope (yalnız oku, yalnız belirli proje) gelecek sürüme
   bırakıldı — Subtask/V19 ve Otomasyon/V31'deki "v1 kapsamı bilerek dar" deseniyle aynı tercih.
3. **Token yönetimi (oluştur/listele/iptal) PAT ile YAPILAMAZ.** `PatAuthenticationFilter`
   kimlik doğrulanan PAT isteklerine sabit `AUTH_PAT` marker yetkisi ekler;
   `AccessTokenController` sınıf düzeyinde `@PreAuthorize("!hasAuthority('AUTH_PAT')")` ile bunu
   reddeder. Gerekçe: aksi halde çalınan bir PAT kendi kendine yeni token üretip orijinal token
   iptal edilse de erişimi sürdürebilirdi — token yönetimi yalnız gerçek bir JWT oturumundan
   yapılabilir.
4. **Kimlik doğrulama DB okuma+yazma (last_used_at) içerdiği için
   `PersonalAccessTokenService.authenticate()` gerçek bir `@Transactional` servis metodu.**
   `JwtAuthenticationFilter`in aksine (imza doğrulama + Redis, DB'siz) burada RLS'siz de olsa bir
   repository çağrısı var; Mimari.md'deki tekrar eden derse (repository'yi tx sınırı dışında
   çağırmak okumada sessiz boş/yazmada `TransactionRequiredException`) bilerek referans verildi.
5. **Hız sınırı token ID'sine göre, Redis'te sabit pencere (fixed window) — `BruteForceGuard` ile
   AYNI INCR+EXPIRE deseni, `PatRateLimiter`.** Kullanıcıya göre DEĞİL token'a göre: aynı
   kullanıcının birden fazla token'ı varsa her biri kendi bütçesini taşır. Yalnız BAŞARILI kimlik
   doğrulama sonrası sayaç artırılır — token uzayı 256-bit olduğu için brute-force riski yok
   (BruteForceGuard'ın parola senaryosuyla karıştırılmamalı), geçersiz token denemeleri bütçe
   tüketmez. Aşım `429 Too Many Requests` + `Retry-After` header, `WorkspaceContextFilter`'ın
   `writeForbidden` deseniyle AYNI doğrudan yazım (`sendError` KULLANILMAZ, aynı `/error` dispatch
   gerekçesi).
6. **`personal_access_tokens` tablosu RLS'e tabi DEĞİL** — `refresh_tokens`/`verification_tokens`
   (V5) ile AYNI gerekçe: kimlik doğrulama verisi workspace'e göre değil kullanıcıya göre izole
   edilir. Workspace erişimi PAT'li isteklerde de AYNI `X-Workspace-Id` + `WorkspaceContextFilter`
   akışından geçer — PAT sadece "kimsin" sorusunu cevaplar, "hangi workspace" sorusu değişmez.

## Sonuçlar

**Olumlu:** JWT akışına dokunmadan paralel bir kimlik doğrulama yolu; mevcut yetkilendirme
(`WorkspaceContextFilter`, `@PreAuthorize`, RLS) hiç değişmeden PAT'li isteklere de uygulanır
(principal aynı şekilde `UUID` userId).

**Kabul edilen bedel:** Scope sistemi yok (madde 2) — bir token oluşturulduğunda kullanıcının tüm
(admin hariç) erişimini taşır, ince taneli kısıtlama sonraki sürüm. Hız sınırı basit sabit pencere,
gerçek sliding window değil (madde 5, BruteForceGuard'daki bilinen basitleştirmeyle aynı sınır).

## Referanslar

- `src/main/java/com/app/tracker/accesstoken/`
- `src/main/java/com/app/tracker/core/security/PatAuthenticationFilter.java`
- `src/main/java/com/app/tracker/core/security/PatRateLimiter.java`
- `src/main/java/com/app/tracker/core/security/PatTokenFormat.java`
- `src/main/resources/db/migration/V32__personal_access_tokens.sql`
- `src/test/java/com/app/tracker/accesstoken/AccessTokenIntegrationTest.java`
