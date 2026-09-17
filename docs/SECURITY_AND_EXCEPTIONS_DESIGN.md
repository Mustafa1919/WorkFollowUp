# Güvenlik ve Hata Yönetimi Mimari Tasarımı (v1.1)

> **v1.1 Değişiklikleri:** Refresh Token Rotation + Reuse Detection (1.1.1), SPA için CORS/CSRF stratejisi (1.1.2) ve Login Güvenliği — brute-force koruması, parola sıfırlama, e-posta doğrulama (1.4) eklendi.

## 1. Güvenlik Mimarisi (Security)

Sistem, Spring Security 6.x ve stateless (durumsuz) JWT (JSON Web Token) altyapısı üzerine kuruludur.

### 1.1. Kimlik Doğrulama (Authentication) Akışı
*   **Access Token:** 15 dakika ömürlü, asimetrik şifreleme (RS256) ile imzalanmış token. İstemci bunu `Authorization: Bearer <token>` header'ı ile gönderir.
*   **Refresh Token:** 7 gün ömürlü, sadece veritabanında (veya Redis'te) hash'lenmiş olarak tutulan ve istemci tarafında `HttpOnly Secure Cookie` olarak saklanan token. XSS (Cross-Site Scripting) saldırılarına karşı korunmak için JavaScript ile okunamaz.
*   **Token İçeriği (Payload):** Token içinde sadece `userId`, `roles` ve `jti` (JWT ID) bulunur. Hassas veriler (email, isim) token içine konmaz.

#### 1.1.1. Refresh Token Rotation ve Reuse Detection — YENİ
Refresh token'ı 7 gün boyunca sabit tutmak, çalınması durumunda saldırgana 7 günlük bir pencere açar. Modern standart **Rotation + Reuse Detection**'dır:

*   **Rotation:** `/api/v1/auth/refresh` endpoint'i her çağrıldığında, kullanılan refresh token **anında geçersiz kılınır** ve yanıtla birlikte **yeni bir refresh token** dönülür (yeni access token'ın yanında). Her refresh token tek kullanımlıktır.
*   **Token Ailesi (Family):** Her login, bir `family_id` üretir; rotation ile üretilen tüm token'lar aynı aileye bağlanır (`refresh_tokens` tablosu: `token_hash`, `family_id`, `user_id`, `expires_at`, `used_at`, `revoked`).
*   **Reuse Detection (Kritik Kural):** Daha önce kullanılmış (`used_at` dolu) bir refresh token tekrar gelirse, bu **kesin bir çalınma sinyalidir** — çünkü meşru istemci artık yeni token'ı kullanıyordur; eskisini kullanan ikinci taraf ya saldırgandır ya da saldırgan yenisini almış, kurban eskisini deniyordur. Hangisi olduğu bilinemeyeceği için **ailedeki TÜM token'lar anında iptal edilir** ve kullanıcı tüm cihazlarda yeniden login olmaya zorlanır. Olay, güvenlik logu olarak kaydedilir ve kullanıcıya bilgilendirme e-postası gönderilir.
*   **Race Toleransı:** Meşru istemcinin çift sekmede eşzamanlı refresh yapması false-positive üretebilir; bunun için kullanılmış token'a ~30 saniyelik bir "grace period" tanınır (bu pencerede tekrar kullanım, aynı yeni token'ı döndürür, aileyi iptal etmez).

#### 1.1.2. SPA İçin CORS ve CSRF Stratejisi — YENİ
Refresh token'ın cookie'de taşınması, cookie tabanlı isteklerin klasik zafiyetini (CSRF) beraberinde getirir. Strateji şudur:

*   **İki farklı taşıma, iki farklı koruma:** API istekleri `Authorization: Bearer` header'ı ile kimliklenir — header'lar cross-site formlarla gönderilemediği için bu istekler **doğal olarak CSRF'e kapalıdır**. CSRF riski yalnızca cookie ile kimliklenen `/api/v1/auth/refresh` ve `/api/v1/auth/logout` endpoint'lerindedir.
*   **Cookie Nitelikleri:** Refresh cookie şu bayraklarla set edilir: `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`. `SameSite=Strict`, cookie'nin cross-site isteklerde hiç gönderilmemesini sağlar ve modern tarayıcılarda CSRF'i büyük ölçüde kapatır. `Path` kısıtlaması, cookie'nin yalnızca auth endpoint'lerine gitmesini sağlar.
*   **Derinlemesine Savunma:** `SameSite`'ı desteklemeyen eski istemcilere karşı, refresh endpoint'i ek olarak `Origin`/`Referer` header doğrulaması yapar (izinli origin listesi dışından gelen refresh istekleri reddedilir).
*   **CORS:** İzinli origin listesi (`https://app.example.com`) ortam konfigürasyonundan gelir; `*` wildcard'ı `allowCredentials=true` ile birlikte **asla** kullanılmaz. Preflight yanıtları 1 saat cache'lenir (`Access-Control-Max-Age`).

### 1.2. Yetkilendirme ve RBAC (Role-Based Access Control)
Sistemde iki seviyeli yetkilendirme vardır:
1.  **Global Roller:** `SYSTEM_ADMIN` (Sistemin sahibi).
2.  **Workspace Rolleri:** `WORKSPACE_ADMIN`, `MANAGER`, `DEVELOPER`, `VIEWER`.

Spring Boot içinde metot seviyesi güvenlik uygulanacaktır:
```java
// Sadece ilgili workspace'te MANAGER veya ADMIN olanlar görev silebilir.
@PreAuthorize("@securityGuard.hasWorkspaceRole(#workspaceId, 'MANAGER', 'ADMIN')")
@DeleteMapping("/workspaces/{workspaceId}/tasks/{taskId}")
public ResponseEntity<Void> deleteTask(...) { ... }
```
### 1.3. Veri İzolasyonu (PostgreSQL RLS Entegrasyonu)
API katmanını aşan bir güvenlik açığı olsa bile veritabanı veriyi korumalıdır.

Spring Security Context'teki aktif workspaceId alınır.
Hibernate sorguyu veritabanına göndermeden hemen önce şu SQL çalıştırılır: SET LOCAL app.current_workspace_id = 'ilgili-uuid';
PostgreSQL'deki RLS politikası şu şekildedir: CREATE POLICY tenant_isolation_policy ON tasks USING (workspace_id = current_setting('app.current_workspace_id')::uuid);

(Detaylı implementasyon kuralları — transaction zorunluluğu, rol ayrımı, worker stratejisi — için bkz. `PHASE_1_DETAILED_DESIGN.md`, Bölüm 3.1.)

### 1.4. Login Güvenliği ve Hesap Yaşam Döngüsü — YENİ

#### 1.4.1. Brute-Force Koruması
Login endpoint'i, kimlik bilgisi doldurma (Credential Stuffing) ve kaba kuvvet saldırılarının birincil hedefidir. İki katmanlı sayaç uygulanır (Redis'te, sliding window):

| Katman | Anahtar | Eşik | Aksiyon |
| :--- | :--- | :--- | :--- |
| Hesap bazlı | `login_fail:user:{email}` | 15 dk içinde 5 başarısız | Hesap için artan gecikme (exponential backoff: 1sn -> 2sn -> 4sn...), 10 başarısızlıkta 15 dk kilit + kullanıcıya e-posta |
| IP bazlı | `login_fail:ip:{ip}` | 15 dk içinde 20 başarısız | IP'ye CAPTCHA zorunluluğu, 50'de geçici IP engeli (Gateway seviyesinde) |

*   **Kullanıcı Numaralandırma (Enumeration) Koruması:** Login ve parola sıfırlama yanıtları, e-postanın kayıtlı olup olmadığını **asla belli etmez** ("E-posta veya şifre hatalı" / "Eğer bu e-posta kayıtlıysa bağlantı gönderildi"). Yanıt süreleri de eşitlenir — kayıtlı olmayan e-posta için de bcrypt karşılaştırması sahte bir hash'e karşı çalıştırılır (Timing Attack koruması).
*   **Parola Politikası:** Minimum 12 karakter; bileşim kuralları (büyük harf/sembol zorunluluğu) yerine **sızmış parola kontrolü** uygulanır (HaveIBeenPwned k-Anonymity API'si veya yerel sızıntı listesi). NIST 800-63B ile uyumludur.

#### 1.4.2. Parola Sıfırlama Akışı
1.  Kullanıcı e-postasını girer; sistem **her durumda** aynı yanıtı döner (enumeration koruması).
2.  Kayıtlıysa: 256-bit rastgele, tek kullanımlık, 30 dk ömürlü bir token üretilir; **hash'i** `password_reset_tokens` tablosuna yazılır (düz token asla saklanmaz — DB sızıntısında bile token'lar kullanılamaz), düz hali e-posta bağlantısına konur.
3.  Token doğrulanıp yeni parola set edildiğinde: token yakılır, kullanıcının **tüm refresh token aileleri iptal edilir** (tüm oturumlar düşer), Redis blacklist'e mevcut access token `jti`'ları eklenir ve kullanıcıya "parolanız değiştirildi" bildirimi gönderilir.
4.  Parola sıfırlama endpoint'i de IP bazlı rate limit'e tabidir (saatte 3 istek/e-posta).

#### 1.4.3. E-posta Doğrulama ve Transactional E-posta Altyapısı
*   **Kayıt Akışı:** Yeni kullanıcı `email_verified=false` ile oluşturulur; doğrulama bağlantısı (24 saat ömürlü, hash'lenmiş token — parola sıfırlamayla aynı mekanizma) gönderilir. Doğrulanmamış hesap login olabilir ancak **workspace oluşturamaz ve davet gönderemez** (spam/abuse önlemi).
*   **Transactional E-posta Servisi:** Doğrulama, parola sıfırlama, güvenlik uyarıları ve workspace davetleri için bir e-posta sağlayıcısı (AWS SES / Resend / Postmark) entegre edilir. Gönderim **senkron yapılmaz**: e-posta ihtiyacı bir `notification.email` Kafka olayı olarak üretilir; Notification Worker (Faz 3) gönderimi Resilience4j koruması altında yapar. Sağlayıcı çökse bile kayıt/sıfırlama akışları çalışmaya devam eder, e-postalar kuyruğun arkasından gönderilir.
*   **Domain İtibarı:** SPF, DKIM ve DMARC kayıtları Faz 0 kapsamında (DNS kurulumu) yapılandırılır; aksi halde doğrulama e-postaları spam'e düşer ve onboarding hunisi sessizce kırılır.

## 2. Global Hata Yönetimi (Exception Handling)
   Sistemdeki tüm hatalar, Spring Boot 3'ün yerleşik ProblemDetail sınıfı kullanılarak RFC 7807 standardında dışarı aktarılır.

### 2.1. Merkezi Hata Yakalayıcı (@RestControllerAdvice)
Tüm controller'lardan fırlatılan exception'lar GlobalExceptionHandler sınıfında toplanır.
```java
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // 1. İş Kuralları Hataları (400 Bad Request)
    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail handleBusinessRuleException(BusinessRuleException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create("https://api.app.com/errors/business-rule-violation"));
        problem.setTitle("İş Kuralı İhlali");
        return problem;
    }

    // 2. Yetki Hataları (403 Forbidden)
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Bu işlemi yapmak için yetkiniz yok.");
        problem.setType(URI.create("https://api.app.com/errors/forbidden"));
        problem.setTitle("Erişim Reddedildi");
        return problem;
    }

    // 3. Beklenmeyen Sistem Hataları (500 Internal Server Error)
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAllUncaughtException(Exception ex) {
        // TODO: Hatayı Logla (Sentry, ELK veya Datadog'a gönder)
        log.error("Beklenmeyen Hata: ", ex);
        
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Sistemde beklenmeyen bir hata oluştu. Lütfen daha sonra tekrar deneyin.");
        problem.setType(URI.create("https://api.app.com/errors/internal-error"));
        problem.setTitle("Sunucu Hatası");
        // Güvenlik: Stack Trace KESİNLİKLE dışarı dönülmez!
        return problem;
    }
}
```
### 2.2. Validasyon Hataları (422 Unprocessable Entity)
Kullanıcı formdan eksik veri gönderdiğinde (Örn: Görev başlığı boş), hatalar gruplanarak dönülür.
```json
{
  "type": "https://api.app.com/errors/validation-failed",
  "title": "Doğrulama Hatası",
  "status": 422,
  "detail": "Gönderilen verilerde 2 adet hata bulundu.",
  "invalid_params": [
    { "field": "title", "reason": "Görev başlığı boş bırakılamaz." },
    { "field": "status", "reason": "Geçersiz statü değeri." }
  ]
}
```

### ⚖️ Trade-off (Ödünleşim) Analizi
*   **JWT (Stateless) vs. Opaque Token (Stateful):** JWT kullanmanın en büyük avantajı, sunucunun veritabanına gitmeden token'ı doğrulayabilmesidir (Yüksek performans). Ancak en büyük dezavantajı: Bir kullanıcının yetkilerini aniden alırsanız veya kullanıcı şifresini değiştirirse, elindeki mevcut JWT süresi dolana kadar (15 dk) sistemde işlem yapmaya devam edebilir. Opaque Token (Sadece rastgele bir string) kullansaydık, her istekte veritabanına/Redis'e soracağımız için anında iptal edebilirdik ama veritabanı yükümüz inanılmaz artardı. Biz performansı seçip JWT kullandık.
*   **Detaylı Hata Mesajları vs. Güvenlik:** Geliştiriciler (Frontend ekibi) API'den dönen hataların çok detaylı olmasını ister (Örn: "Veritabanında tasks_project_id_fkey kısıtlaması ihlal edildi"). Ancak bu durum, kötü niyetli kişilere veritabanı şemanız hakkında bilgi verir. Bu ödünleşimi çözmek için; dışarıya her zaman jenerik ve güvenli mesajlar (RFC 7807) dönüyoruz, ancak hatanın tüm detayını (Stack Trace) benzersiz bir `traceId` (İzleme Kimliği) ile iç sistemlerimize (örn. Elasticsearch/Kibana) logluyoruz.
*   **Rotation'ın Maliyeti — UX vs. Güvenlik:** Refresh token rotation, her token yenilemesinde bir veritabanı/Redis yazması ekler ve çoklu sekme senaryolarında race yönetimi (grace period) gerektirir. Rotation'sız sabit refresh token çok daha basittir; ancak çalınan bir token'ın 7 gün boyunca fark edilmeden kullanılabilmesi, B2B bir üründe kabul edilemez bir risktir. Reuse detection'ın sağladığı "çalınmayı aktif tespit etme" yeteneği, eklenen karmaşıklığın asıl karşılığıdır — sadece koruma değil, **tespit** mekanizmasıdır.
*   **Hesap Kilitleme vs. Artan Gecikme:** Başarısız login'lerde hesabı sert kilitlemek (lockout), saldırgana bir DoS silahı verir: kurbanın e-postasıyla bilerek 10 yanlış deneme yapıp hesabını sürekli kilitleyebilir. Bu yüzden birincil savunma **artan gecikme + CAPTCHA**'dır; sert kilit yalnızca yüksek eşikte, kısa süreli ve e-posta bildirimli olarak devreye girer.

### 🚀 Mimari İpucu
**JWT İptali İçin Redis Blacklist (Kara Liste) Stratejisi:**
Yukarıda bahsettiğim "JWT'nin iptal edilememesi" sorununu çözmek için mimari bir hile yapabiliriz. Bir kullanıcı "Çıkış Yap" (Logout) dediğinde veya hesabı askıya alındığında, elindeki Access Token'ın içindeki benzersiz `jti` (JWT ID) değerini alır ve **Redis** üzerine yazarız.

Redis'e yazarken, bu kaydın yaşam süresini (TTL - Time To Live) token'ın kalan ömrü kadar ayarlarız.
Örneğin token'ın bitmesine 10 dakika kalmışsa, Redis'teki kaydın TTL'i 10 dakika olur.

Spring Security filtre katmanında, gelen her JWT için Redis'e $$O(1)$$ zaman karmaşıklığıyla (milisaniyenin altında) şu soruyu sorarız: *"Bu jti kara listede mi?"*
Eğer kara listedeyse istek reddedilir. 10 dakika sonra Redis bu kaydı otomatik siler (çünkü token zaten doğal yollarla ölmüştür) ve Redis belleği şişmez. Bu yöntem, Stateless (durumsuz) mimarinin hızını korurken, Stateful (durumlu) mimarinin kontrol gücünü size verir.

**Reuse Detection ile Blacklist'i Birleştirin:** Bölüm 1.1.1'deki token ailesi iptali tetiklendiğinde (çalınma şüphesi), yalnızca refresh token'ları iptal etmek yetmez — saldırganın elindeki **access token hâlâ 15 dakika geçerlidir**. Aile iptali, kullanıcının aktif tüm access token `jti`'larını da Redis blacklist'ine yazmalıdır. Bunun için her refresh işleminde üretilen access token'ın `jti`'sı, refresh token kaydının yanına not edilir; iptal anında toplu olarak karalisteye taşınır. Böylece çalınma tespiti, saldırganı **saniyeler içinde** tamamen dışarı atar.
