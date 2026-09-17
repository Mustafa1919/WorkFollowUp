# Faz 1: Temel Altyapı ve Core API Detaylı Tasarımı (v1.1)

## 1. Proje Dizin Yapısı (Package-by-Feature)
Uygulama katmanlara göre (Controller, Service, Repository) değil, **Domain'lere (İş alanlarına)** göre paketlenecektir. Bu, gelecekte servisi bölmek istediğimizde işimizi inanılmaz kolaylaştırır.

```text
src/main/java/com/app/tracker/
├── core/                   # Tüm modüllerin paylaştığı ortak altyapı
│   ├── exception/          # GlobalExceptionHandler, Custom Exceptions
│   ├── security/           # JWT Filters, SecurityConfig, UserDetails
│   ├── tenancy/            # RLS Context yönetimi, TenantAwareTransaction (YENİ)
│   └── config/             # JPA, Swagger, WebMvc konfigürasyonları
├── workspace/              # Workspace Domain'i
│   ├── controller/         # WorkspaceController
│   ├── service/            # WorkspaceService
│   ├── repository/         # WorkspaceRepository
│   └── model/              # Workspace Entity, DTOs
├── project/                # Project Domain'i
└── task/                   # Task Domain'i
```

## 2. Veritabanı Versiyon Kontrolü (Flyway)
Veritabanı şeması manuel olarak değil, Flyway kullanılarak yönetilecektir. `src/main/resources/db/migration` klasörü altında SQL dosyaları tutulacaktır.

*   `V1__init_schema.sql` -> Tabloların oluşturulması (`DATABASE_SCHEMA.md` referans alınarak).
*   `V2__add_rls_policies.sql` -> Row-Level Security (RLS) kurallarının eklenmesi.
*   `V3__create_app_db_roles.sql` -> Uygulama ve worker'lar için ayrı veritabanı rollerinin oluşturulması (Bkz. Bölüm 3.2).
*   `V4__create_task_counters.sql` -> Proje bazlı atomik görev numarası sayaç tablosu (Bkz. Bölüm 6).

## 3. Güvenlik ve RLS (Row-Level Security) Entegrasyonu
Çoklu kiracı izolasyonunu veritabanı seviyesinde sağlamak için Spring Boot ile PostgreSQL RLS entegrasyonu şu şekilde yapılacaktır:

*   **JWT Filter:** Gelen istekteki JWT çözülür, kullanıcının `userId` ve çalıştığı aktif `workspaceId` bilgisi `SecurityContextHolder`'a konur.
*   **Hibernate Interceptor (AOP):** Spring Data JPA veritabanına sorgu atmadan hemen önce, bir AOP (Aspect-Oriented Programming) veya Hibernate `StatementInspector` devreye girer.
*   **Session Set:** Veritabanı oturumuna şu komut gönderilir: `SET LOCAL app.current_workspace_id = 'ilgili-uuid';`
*   **Sonuç:** Geliştirici `taskRepository.findAll()` çağırsa bile, PostgreSQL sadece o workspace'e ait görevleri döndürür.

### 3.1. RLS ve Connection Pool (HikariCP) Etkileşimi — KRİTİK
`SET LOCAL` komutu ile Connection Pooling bir arada kullanılırken üç temel kurala uyulması **zorunludur**. Bu kurallar ihlal edilirse ya izolasyon sessizce devre dışı kalır ya da bir tenant'ın context'i başka bir tenant'ın isteğine sızar.

**Kural 1: Her sorgu bir Transaction içinde çalışmalıdır.**
`SET LOCAL`, yalnızca içinde bulunduğu transaction'ın ömrü boyunca geçerlidir; transaction biterse (COMMIT/ROLLBACK) ayar otomatik olarak sıfırlanır. Bu bizim için bir avantajdır (Havuza dönen connection temizdir), ancak şu tuzağı doğurur: **Transaction dışında çalışan bir sorguda `SET LOCAL` hiçbir etki yapmaz** ve PostgreSQL yalnızca bir `WARNING` üretir — hata fırlatmaz!

*   **Uygulama Kararı:** Tüm Service metotları (salt okunanlar dahil) `@Transactional` (okumalar için `@Transactional(readOnly = true)`) ile işaretlenecektir. Bunu unutmayı imkânsız kılmak için, `core/tenancy` paketinde repository çağrılarının aktif bir transaction ve set edilmiş bir tenant context olmadan çalışmasını engelleyen bir guard (Aspect) yazılacaktır: `TransactionSynchronizationManager.isActualTransactionActive()` kontrolü false dönerse istek `IllegalStateException` ile reddedilir.
*   **Neden `SET` değil `SET LOCAL`?** Transaction'dan bağımsız `SET` (session-level) kullanılsaydı, connection havuza döndüğünde eski tenant'ın `workspace_id`'si üzerinde kalır ve havuzdan aynı connection'ı alan **başka bir tenant'ın isteği önceki tenant'ın verisini görürdü.** Bu, multi-tenant sistemdeki en tehlikeli sızıntı senaryosudur. `SET LOCAL` + zorunlu transaction bu riski yapısal olarak ortadan kaldırır.

**Kural 2: Uygulama DB kullanıcısı tablo sahibi OLMAMALIDIR.**
PostgreSQL'de tablo sahipleri (owner) ve `BYPASSRLS` yetkisine sahip roller, `FORCE ROW LEVEL SECURITY` açıkça belirtilmedikçe RLS politikalarından **muaftır**. Geliştirme ortamlarında sıklıkla yapılan hata, uygulamanın migration'ları çalıştıran süper yetkili kullanıcıyla bağlanmasıdır — bu durumda tüm RLS politikaları yazılmış olsa bile hiçbiri çalışmaz.

*   **Uygulama Kararı:** İki ayrı veritabanı rolü tanımlanacaktır:
    *   `app_migrator`: Flyway'in kullandığı, DDL yetkili, tablo sahibi rol. Uygulama runtime'ında **asla** kullanılmaz.
    *   `app_runtime`: Spring Boot'un (HikariCP'nin) bağlandığı rol. Yalnızca DML (SELECT/INSERT/UPDATE/DELETE) yetkisi vardır, tablo sahibi değildir, `BYPASSRLS` ve `SUPERUSER` yetkileri yoktur.
*   Ek güvence olarak tüm tenant tablolarında `ALTER TABLE tasks FORCE ROW LEVEL SECURITY;` uygulanacaktır. Böylece yanlışlıkla owner ile bağlanılsa bile politika devrede kalır.
*   RLS politikasında `current_setting` çağrısı `missing_ok` parametresi ile yapılacaktır: `current_setting('app.current_workspace_id', true)`. Context set edilmemişse politika `NULL` karşılaştırması nedeniyle **hiçbir satır döndürmez** (Fail-Closed / Güvenli Varsayılan). Context'i unutan bir kod hatası, veri sızdırmak yerine boş sonuç döner.

**Kural 3: Arka plan işleri (Worker'lar) için tenant stratejisi baştan tanımlanmalıdır.**
HTTP isteği dışında çalışan kodların (Faz 2'deki Outbox Relay, Faz 3'teki Analitik Worker, arşivleme job'ları) elinde bir JWT ve dolayısıyla tenant context'i yoktur. Bu worker'lar RLS'e takılmamalı ama izolasyonu da delmemelidir.

*   **Uygulama Kararı (İki desen):**
    1.  **Tenant-Iterating Worker (Varsayılan):** Worker, işlem yapacağı kayıtların `workspace_id`'sini bilir (Örn: Outbox kaydının içinde `workspace_id` vardır). İşlemeden önce programatik olarak ilgili tenant context'ini set eder ve `app_runtime` rolüyle, RLS'e tabi şekilde çalışır. Bunun için `core/tenancy` içinde bir yardımcı sınıf sunulur:
        ```java
        tenantExecutor.runAs(workspaceId, () -> {
            // Bu blok içindeki tüm sorgular ilgili workspace context'i ile çalışır
        });
        ```
    2.  **Cross-Tenant Worker (İstisnai):** Tüm tenant'lar üzerinde toplu çalışması gereken işler (Örn: gece arşivleme) için `app_worker` adında, ilgili tablolarda RLS'ten muaf tutulmuş **ayrı ve kısıtlı** bir rol tanımlanır. Bu rolün bağlantı bilgileri ana uygulamaya verilmez; yalnızca ilgili worker deployment'ında bulunur.

### 3.2. HikariCP Konfigürasyon Notları
*   `maximum-pool-size`: Başlangıç için 10 (PostgreSQL'in `max_connections` değeri ve instance sayısı ile birlikte hesaplanmalıdır).
*   `auto-commit: false` — Transaction yönetimi tamamen Spring'e bırakılır; bu, "transaction dışı sorgu" ihtimalini azaltır.
*   Hikari'nin `connection-init-sql` özelliği tenant set etmek için **kullanılmayacaktır** (connection bazlıdır, istek bazlı değildir); tenant context yalnızca transaction başına `SET LOCAL` ile verilir.

## 4. Core REST API Tasarımı
API'ler Richardson Olgunluk Modeli Seviye 2'ye uygun, kaynak odaklı (Resource-oriented) tasarlanacaktır.

| HTTP Metodu | Endpoint | Açıklama |
| :--- | :--- | :--- |
| POST | `/api/v1/auth/login` | JWT Access ve Refresh token döner. |
| POST | `/api/v1/workspaces` | Yeni bir çalışma alanı oluşturur. |
| GET | `/api/v1/projects` | Aktif workspace'teki projeleri listeler. |
| POST | `/api/v1/projects/{projectId}/tasks` | Belirli bir projeye yeni görev ekler. |
| PATCH | `/api/v1/tasks/{taskId}` | Görevin sadece belirli alanlarını günceller (Örn: status). |

**Not:** Güncellemeler için PUT (tüm kaynağı ezme) yerine PATCH (kısmi güncelleme) kullanılacaktır.

### 4.1. API Sözleşme Standartları (Pagination ve Idempotency)

**Pagination — Keyset (Cursor) Bazlı:**
Liste endpoint'lerinde `?page=5&size=20` (Offset) yaklaşımı **kullanılmayacaktır**. Offset pagination'ın iki temel sorunu vardır: (1) `OFFSET 100000` sorgusu, atlanacak tüm satırları yine de okur — derin sayfalarda maliyet $O(N)$'e çıkar; (2) sayfalar arasında yeni görev eklenirse kayıtlar kayar, kullanıcı aynı görevi iki kez görür veya hiç görmez.

Bunun yerine **Keyset (Cursor) Pagination** standardı uygulanır:
```
GET /api/v1/projects/{id}/tasks?limit=20&cursor=eyJjcmVhdGVkX2F0Ijo...
```
*   Cursor, son kaydın sıralama anahtarının (`created_at` + `id`) Base64 kodlanmış halidir. Sorgu `WHERE (created_at, id) < (:cursor_created_at, :cursor_id) ORDER BY created_at DESC, id DESC LIMIT 20` şeklinde çalışır — indeks üzerinden $O(\log N)$, sayfa derinliğinden bağımsız sabit hız.
*   Yanıt zarfı standarttır: `{ "data": [...], "next_cursor": "...", "has_more": true }`. `next_cursor` null ise liste bitmiştir.
*   Kanban panosu gibi tüm listeyi çeken ekranlar için kolon başına makul bir üst sınır (Örn: ilk 200 görev + "daha fazla yükle") uygulanır.

**Idempotency-Key Header (Client-Side Mutations):**
Kullanıcı "Görev Oluştur" butonuna çift tıklarsa veya mobil ağda timeout sonrası istemci isteği yinelerse, aynı görev iki kez oluşturulmamalıdır. Tüm `POST` (kaynak yaratan) endpoint'ler opsiyonel `Idempotency-Key` header'ını destekler:

*   İstemci (React), her mutation için bir UUID üretir ve retry'larda **aynı** anahtarı gönderir.
*   Sunucu, anahtarı `idempotency_keys` tablosunda (veya Faz 5 sonrası Redis'te, 24 saat TTL ile) saklar. Aynı anahtar ikinci kez gelirse istek **yeniden işlenmez**; ilk isteğin kaydedilmiş yanıtı (status code + body) aynen döndürülür.
*   Bu mekanizma, Faz 2 (DLT Replay) ve Faz 3'te (Webhook) tanımlanan idempotent tüketici altyapısıyla aynı `core` bileşenini ("Processed Event Store") paylaşır — üç kez ayrı ayrı yazılmaz.

## 5. Global Exception Handling (Hata Yönetimi)
`@RestControllerAdvice` kullanılarak merkezi bir hata yakalama mekanizması kurulacaktır.

## 6. `task_number` Üretimi: Atomik Sayaç Stratejisi — KRİTİK
Görev numaraları (Örn: `ENG-101`) proje içinde artan ve benzersiz olmalıdır. En sık yapılan hata, numarayı şu şekilde üretmektir:

```sql
-- ❌ YANLIŞ: Race Condition üretir!
SELECT MAX(task_number) + 1 FROM tasks WHERE project_id = :projectId;
```

İki kullanıcı aynı anda görev oluşturduğunda, her iki transaction da aynı `MAX` değerini okur, aynı numarayı üretir ve ikinci `INSERT`, `(project_id, task_number)` unique index'ine çarparak `500` hatası fırlatır. Yük altında bu hata sıklaşır ve kullanıcı deneyimini bozar.

### 6.1. Çözüm: Sayaç Tablosu + Atomik `UPDATE ... RETURNING`
`DATABASE_SCHEMA.md`'de tanımlanan `task_counters` tablosu kullanılarak numara üretimi tek bir atomik SQL komutuna indirgenir:

```sql
UPDATE task_counters
SET last_number = last_number + 1
WHERE project_id = :projectId
RETURNING last_number;
```

*   **Neden çalışır?** PostgreSQL, `UPDATE` sırasında ilgili satıra **satır kilidi (Row-Level Lock)** koyar. Aynı projeye eşzamanlı gelen ikinci istek, ilk transaction bitene kadar bu satırda bekler ve ardından bir sonraki numarayı alır. Duplicate üretimi **fiziksel olarak imkânsızdır**.
*   **Aynı Transaction:** Bu `UPDATE`, görevin `INSERT` edildiği transaction'ın **içinde** çalıştırılır. Transaction rollback olursa sayaç artışı da geri alınır... gibi görünse de dikkat: rollback durumunda numara "yanmış" olabilir mi? Hayır — `UPDATE` de aynı transaction'da olduğu için rollback ile birlikte sayaç da eski değerine döner. (Bu, `SEQUENCE` kullanmaya göre önemli bir farktır; sequence'lar rollback'te geri sarılmaz ve numaralarda boşluk oluşurdu.)
*   **Sayaç Kaydının Oluşturulması:** Yeni bir proje oluşturulduğunda, aynı transaction içinde `task_counters` tablosuna `(project_id, 0)` kaydı eklenir.

```java
@Transactional
public Task createTask(UUID projectId, CreateTaskRequest request) {
    int taskNumber = taskCounterRepository.getNextNumber(projectId); // UPDATE...RETURNING
    Task task = Task.of(request, projectId, taskNumber);
    return taskRepository.save(task); // Aynı transaction: ya ikisi de olur ya hiçbiri
}
```

### 6.2. Değerlendirilen ve Reddedilen Alternatifler
| Yöntem | Neden Reddedildi |
| :--- | :--- |
| `SELECT MAX()+1` | Race condition (yukarıda açıklandı). |
| Proje başına PostgreSQL `SEQUENCE` | Binlerce proje = binlerce sequence objesi (yönetim kabusu); rollback'te numara boşlukları oluşur. |
| PostgreSQL Advisory Lock | Çalışır, ancak kilit yönetimi uygulama koduna sızar; sayaç tablosu daha basit ve kendini belgeleyen bir çözümdür. |
| Uygulama içi `AtomicInteger` | Birden fazla pod'da anında bozulur; restart'ta değer kaybolur. |

**Performans Notu:** Satır kilidi yalnızca **aynı projeye** eşzamanlı görev eklenirken devreye girer ve mikrosaniyeler sürer. Farklı projeler farklı satırları kilitlediği için birbirini hiç etkilemez. Bu darboğaz teorik olarak var, pratikte ölçülemeyecek kadar küçüktür.

### ⚖️ Trade-off (Ödünleşim) Analizi
*   **Package-by-Feature vs. Package-by-Layer:** Geleneksel Spring Boot projeleri katmanlara göre (tüm controller'lar bir pakette, tüm servisler diğerinde) klasörlenir. Bu, yeni başlayanlar için kolaydır ancak proje büyüdüğünde "Task" ile ilgili bir değişiklik yapmak için 5 farklı pakette gezinmeniz gerekir. "Package-by-Feature" (Domain bazlı) yaklaşımı, kodun kohezyonunu (cohesion) artırır. Dezavantajı ise ortak kullanılan DTO'ların veya yardımcı sınıfların nereye konulacağı konusunda bazen kafa karışıklığı yaratmasıdır (Bunun için `core` paketini kullanıyoruz).
*   **JPA/Hibernate vs. JdbcTemplate/jOOQ:** Faz 1'de hızlı CRUD işlemleri için Spring Data JPA (Hibernate) kullanmak geliştirme hızını artırır. Ancak JPA, karmaşık SQL sorgularında kontrolü kaybetmenize neden olabilir. Faz 1 için JPA mükemmeldir, ancak Faz 3'te (Analitik) ağır raporlama sorguları için doğrudan `JdbcTemplate` veya `jOOQ` kullanmak gerekebileceğini şimdiden kabul etmeliyiz.
*   **RLS (DB Seviyesi) vs. Uygulama Seviyesi Filtreleme (Hibernate @Filter):** Tenant izolasyonu yalnızca Hibernate `@Filter` ile de yapılabilirdi; bu, veritabanı rol yönetimi ve `SET LOCAL` karmaşıklığını ortadan kaldırırdı. Ancak native SQL sorguları, `JdbcTemplate` çağrıları ve ileride eklenecek raporlama araçları Hibernate filtrelerini **görmez** — tek bir unutulan filtre veri sızıntısı demektir. RLS'in getirdiği operasyonel karmaşıklık (rol ayrımı, transaction zorunluluğu), "savunmanın veritabanında olması" güvencesinin yanında kabul edilebilir bir maliyettir. İki mekanizmayı birlikte kullanmak (Defense in Depth) ise en güvenli yaklaşımdır: Hibernate filtresi performans için erken eleme yapar, RLS son kale olarak durur.

### 🚀 Mimari İpucu
**N+1 Sorgu Problemi (N+1 Query Problem):**
Spring Data JPA kullanırken ekibinizin yaşayacağı en büyük performans darboğazı N+1 problemidir. Örneğin, bir projedeki 50 görevi ve her göreve atanan kullanıcıyı (Assignee) çekmek istediğinizde, JPA önce görevleri çekmek için 1 sorgu atar, sonra her bir görevdeki kullanıcıyı çekmek için 50 ayrı sorgu daha atar.

Zaman karmaşıklığı (Time Complexity) açısından bu durum veritabanına $$O(N)$$ adet sorgu gitmesi demektir. İdeal olanı bunu $$O(1)$$ (tek bir JOIN sorgusu) seviyesine indirmektir.

Bunu önlemek için, Repository katmanında `@EntityGraph` veya `JOIN FETCH` kullanmayı ekibinize standart bir kural olarak koyun:
```java
@EntityGraph(attributePaths = {"assignee", "project"})
List<Task> findByWorkspaceId(UUID workspaceId);
```

**RLS'i Test Etmeden Prod'a Çıkmayın:**
RLS politikaları H2 gibi in-memory veritabanlarında test **edilemez** (H2, RLS desteklemez). Faz 1'in "Definition of Done" kriterine şu entegrasyon testi eklenmelidir (Testcontainers ile gerçek PostgreSQL üzerinde): "Workspace A context'i ile Workspace B'ye ait bir task ID'si sorgulandığında sonuç boş dönmelidir; context hiç set edilmediğinde hiçbir satır dönmemelidir." Bu iki test, mimarinin en kritik güvenlik varsayımını her CI koşusunda doğrular.
