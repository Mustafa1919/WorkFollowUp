# İş Takip ve Analiz Sistemi - Mimari Dokümantasyon (v1.0)

## 1. Teknoloji Yığını (Tech Stack)
*   **Frontend:** React.js (SPA, Redux/Zustand for State Management)
*   **Backend:** Java 21+, Spring Boot 3.x
*   **Veritabanı:** PostgreSQL 16+
*   **Asenkron İletişim / Message Broker:** Apache Kafka
*   **Güvenlik:** Spring Security, JWT (JSON Web Token)

---

## 2. Geliştirme Fazları (Phased Approach)

### Faz 1: Temel Altyapı ve Core CRUD (MVP)
Bu fazda sistemin temel iskeleti kurulur. Asenkron işlemler henüz yoktur.
*   **Spring Boot Proje Kurulumu:** Modüler monolit yapıda paketleme (Örn: `com.app.auth`, `com.app.tasks`, `com.app.projects`).
*   **Veritabanı Entegrasyonu:** Flyway veya Liquibase ile DB versiyon kontrolü. `DATABASE_SCHEMA.md`'deki tabloların oluşturulması.
*   **Güvenlik (Security) Katmanı:** JWT tabanlı kimlik doğrulama ve RBAC (Rol Bazlı Erişim) implementasyonu.
*   **Core API'ler:** Proje ve Görev (Task) oluşturma, listeleme, güncelleme REST API'lerinin yazılması.
*   **React Entegrasyonu:** Temel Kanban panosunun ve login ekranlarının yapılması.

### Faz 2: Olay Güdümlü Mimari ve Gerçek Zamanlı İletişim
Sistemin tepkiselliğinin (reactivity) artırıldığı fazdır.
*   **Kafka Entegrasyonu:** Spring Kafka ile `TaskUpdatedEvent`, `TaskCreatedEvent` gibi olayların (events) fırlatılması.
*   **Transactional Outbox Pattern:** Veri kaybını önlemek için Outbox tablosunun devreye alınması ve Kafka'ya olayların güvenli aktarımı.
*   **WebSocket / SSE:** Kafka'dan dinlenen olayların, Spring WebSockets üzerinden React istemcisine anlık iletilmesi (Gerçek zamanlı ekran güncellemeleri).

### Faz 3: Analitik, Raporlama ve Dış Entegrasyonlar
Sistemin zekasının ve dış dünya ile bağlantısının kurulduğu fazdır.
*   **Analitik Servisi (Worker):** Kafka'daki olayları dinleyip `task_events` tablosunu besleyen ve "Cycle Time", "Velocity" hesaplayan asenkron worker'ların yazılması.
*   **Webhook Ingestion:** GitHub/GitLab'den gelen webhook'ları karşılayan ve doğrudan Kafka'ya atan yüksek performanslı endpoint'lerin yazılması.
*   **Gelişmiş React Raporları:** Recharts veya Chart.js ile analitik verilerin dashboard'da gösterimi.

---

## 3. Güvenlik Mimarisi (Security)

Güvenlik, "Defense in Depth" (Derinlemesine Savunma) prensibiyle iki katmanda sağlanır:

1.  **Uygulama Katmanı (Spring Security & JWT):**
    *   Sistem **Stateless** (durumsuz) çalışacaktır. Kullanıcı login olduğunda kısa ömürlü bir Access Token (15 dk) ve uzun ömürlü bir Refresh Token (7 gün) alır.
    *   Her istekte `Authorization: Bearer <token>` header'ı kontrol edilir.
    *   Metot seviyesinde güvenlik için `@PreAuthorize("hasRole('MANAGER')")` veya `@PreAuthorize("@securityService.hasWorkspaceAccess(#workspaceId)")` anotasyonları kullanılır.

2.  **Veri Katmanı (PostgreSQL RLS):**
    *   Spring Boot, veritabanına bağlantı açtığında kullanıcının `workspace_id`'sini veritabanı session'ına set eder. PostgreSQL Row-Level Security (RLS) sayesinde, bir müşteri diğerinin verisini teknik olarak sorgulayamaz.

---

## 4. Hata Yönetimi (Global Exception Handling)

Sistemde oluşacak tüm hatalar, Spring'in `@RestControllerAdvice` mekanizması ile tek bir merkezde yakalanır ve React istemcisine standart bir **RFC 7807 Problem Details** formatında dönülür.

**Örnek Hata Yanıtı (JSON):**
```json
{
  "type": "https://api.app.com/errors/task-not-found",
  "title": "Görev Bulunamadı",
  "status": 404,
  "detail": "ENG-101 numaralı görev bu çalışma alanında bulunmamaktadır.",
  "instance": "/api/v1/tasks/ENG-101",
  "timestamp": "2026-07-18T14:04:00Z"
}
Temel Exception Sınıfları:

ResourceNotFoundException (404)
UnauthorizedException (401)
ForbiddenException (403)
BusinessValidationException (400) - İş kuralları ihlali.
```

### ⚖️ Trade-off (Ödünleşim) Analizi
*   **Spring Boot vs. Hafif Çatılar (Go/Node.js):** Spring Boot, JVM üzerinde çalıştığı için başlangıç süresi (startup time) ve bellek (RAM) tüketimi Node.js veya Go'ya göre daha yüksektir. Ancak sunduğu devasa ekosistem, Spring Data JPA, Spring Security ve Kafka entegrasyonlarının olgunluğu, kurumsal bir projede geliştirme hızınızı (Time-to-Market) inanılmaz artırır.
*   **JWT vs. Session (Oturum):** JWT (Stateless) kullanmak, sunucuda oturum bilgisini tutmadığımız için yatay ölçeklenmeyi (Horizontal Scaling) çok kolaylaştırır. Ancak JWT'nin dezavantajı, token çalındığında süresi bitene kadar iptal edilememesidir (Revocation zorluğu). Bu yüzden Access Token süresini kısa (Örn: 15 dakika) tutup, Refresh Token mekanizması kurmak zorunludur.

### 🚀 Mimari İpucu
**Circuit Breaker (Devre Kesici) Deseni:**
Sisteminiz dış servislere (Örn: GitHub API, Slack API) istek atarken, karşı taraf çökmüş veya çok yavaşlamış olabilir. Bu durumda Spring Boot uygulamanızdaki thread'ler (iş parçacıkları) yanıt beklerken kilitlenir ve tüm sisteminiz çöker (Cascading Failure).

Bunu önlemek için Spring Cloud tabanlı **Resilience4j** kütüphanesini kullanarak dış çağrılara Circuit Breaker ekleyin. Devre kesicinin açılma (hata fırlatma) mantığı basit bir hata oranı formülüne dayanır:
$$E = \frac{F}{T}$$
Burada $$F$$ başarısız istek sayısını, $$T$$ ise toplam istek sayısını belirtir. Eğer hata oranı $$E$$ belirlenen eşiği (örneğin $$0.5$$, yani %50) aşarsa, devre kesici "Açık" (Open) duruma geçer ve dış servise istek atmayı anında keserek hızlıca hata döner (Fail Fast). Belirli bir süre sonra "Yarı Açık" (Half-Open) duruma geçerek servisin düzelip düzelmediğini test eder. Bu, sisteminizin dayanıklılığını (Resiliency) muazzam artırır.
