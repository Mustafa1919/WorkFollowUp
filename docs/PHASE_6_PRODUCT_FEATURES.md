# Faz 6: Ürün Olgunlaştırma — Domain Özellikleri ve Plan Yönetimi (v1.0)

Bu faz, teknik altyapısı Faz 1-5'te tamamlanan sistemin üzerine, ticari bir görev yönetimi ürününde kaçınılmaz olan domain özelliklerini ekler: **Yorumlar, Dosya Ekleri, Etiketler, Bildirimler ve Plan Bazlı Kotalar**. Tüm özellikler, önceki fazlarda kurulan desenleri (RLS, Outbox, Kafka olayları, idempotent consumer'lar) yeniden kullanır — yeni desen icat edilmez.

> **Şema Notu:** Bu dokümandaki tablolar, implementasyon başlangıcında `DATABASE_SCHEMA.md`'ye (v1.2) işlenecek ve Flyway migration'ları olarak eklenecektir. Tüm yeni tablolar `workspace_id` taşır ve mevcut RLS politikasına dahildir.

## 1. Yorumlar (Comments)

### 1.1. Tablo: `comments`
| Kolon Adı | Veri Tipi | Kısıtlamalar | Açıklama |
| :--- | :--- | :--- | :--- |
| id | UUID | PRIMARY KEY | Yorum kimliği. |
| workspace_id | UUID | FK -> workspaces(id), NOT NULL | RLS izolasyon anahtarı. |
| task_id | UUID | FK -> tasks(id), NOT NULL | Bağlı görev. |
| author_id | UUID | FK -> users(id), NOT NULL | Yazan kullanıcı. |
| parent_comment_id | UUID | FK -> comments(id), NULLABLE | Yanıt zinciri (tek seviye thread). |
| body | TEXT | NOT NULL | Yorum içeriği (Markdown olarak saklanır). |
| edited_at | TIMESTAMPTZ | NULLABLE | Düzenlendiyse zamanı ("düzenlendi" rozeti için). |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Oluşturulma zamanı. |

(İndeks: `task_id + created_at`. Silme: yorum silindiğinde satır silinmez, `body` "[silindi]" ile değiştirilir ve `deleted=true` işaretlenir — thread bütünlüğü korunur.)

### 1.2. Tasarım Kararları
*   **İçerik Formatı:** `body` ham **Markdown** olarak saklanır; HTML'e çevirme işi **istemcide** yapılır (XSS yüzeyi sunucuda oluşturulmaz). İstemci render'ında sanitizasyon (DOMPurify) zorunludur.
*   **Mention (@kullanıcı):** Yorum kaydedilirken `@` ifadeleri sunucuda parse edilir, geçerli workspace üyeleriyle eşleştirilir ve `COMMENT_MENTION` olayı üretilir (Bildirim sisteminin girdisi — Bölüm 4).
*   **Olaylar:** `COMMENT_ADDED`, `COMMENT_MENTION` olayları Outbox üzerinden `task.events` topic'ine yazılır; WebSocket fan-out (Faz 2) sayesinde açık görev detay ekranlarında yorumlar anlık görünür.
*   **Pagination:** Yorum listesi, Faz 1'deki keyset pagination standardını kullanır (eski yorumlara doğru cursor ile).

## 2. Dosya Ekleri (Attachments)

### 2.1. Tablo: `attachments`
| Kolon Adı | Veri Tipi | Kısıtlamalar | Açıklama |
| :--- | :--- | :--- | :--- |
| id | UUID | PRIMARY KEY | Ek kimliği. |
| workspace_id | UUID | FK -> workspaces(id), NOT NULL | RLS izolasyon anahtarı. |
| task_id | UUID | FK -> tasks(id), NOT NULL | Bağlı görev. |
| uploader_id | UUID | FK -> users(id), NOT NULL | Yükleyen kullanıcı. |
| file_name | VARCHAR(255) | NOT NULL | Orijinal dosya adı. |
| content_type | VARCHAR(100) | NOT NULL | MIME tipi. |
| size_bytes | BIGINT | NOT NULL | Boyut (kota hesabında kullanılır). |
| storage_key | VARCHAR(512) | NOT NULL, UNIQUE | S3 obje anahtarı. |
| status | VARCHAR(20) | NOT NULL, DEFAULT 'pending' | pending, available, infected, deleted. |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Yüklenme zamanı. |

### 2.2. Depolama Mimarisi: S3 + Presigned URL
Dosya baytları **asla** Spring Boot üzerinden akmaz (pod belleğini ve bant genişliğini tüketir); obje depolama (AWS S3 / MinIO) ile doğrudan istemci arasında taşınır:

1.  **Upload:** İstemci `POST /api/v1/tasks/{id}/attachments` çağırır (dosya adı, tip, boyut ile). API; kota kontrolü yapar (Bölüm 5), `attachments` kaydını `pending` statüsüyle oluşturur ve 15 dk ömürlü bir **Presigned PUT URL** döner. İstemci dosyayı bu URL ile doğrudan S3'e yükler.
2.  **Doğrulama:** S3 event notification (veya istemcinin `complete` çağrısı) ile API, objenin varlığını ve boyutunu doğrular; kayıt `available` yapılır ve `ATTACHMENT_ADDED` olayı üretilir.
3.  **Download:** İndirme istekleri de kısa ömürlü (5 dk) **Presigned GET URL** ile karşılanır. S3 bucket'ı tamamen private'tır; kalıcı public URL **yoktur** — erişim her seferinde API'nin (dolayısıyla RLS + RBAC'ın) onayından geçer.
4.  **Obje Anahtarı Düzeni:** `{workspace_id}/{task_id}/{attachment_id}` — workspace bazlı prefix, ileride müşteri bazlı arşivleme/silme işlemlerini tek prefix operasyonuna indirger.

### 2.3. Güvenlik Kontrolleri
*   **Boyut ve Tip Limiti:** Maksimum dosya boyutu plana bağlıdır (Bölüm 5); izinli MIME tipleri allowlist ile sınırlıdır (çalıştırılabilir dosyalar reddedilir). Presigned URL, `content-length-range` koşuluyla üretilir — istemci beyan ettiğinden büyük dosya yükleyemez.
*   **Virüs Taraması:** `pending` -> `available` geçişi arasında asenkron bir tarama worker'ı (ClamAV) objeyi tarar; şüpheli dosya `infected` işaretlenir ve indirilemez. Tarama tamamlanana kadar dosya diğer kullanıcılara "işleniyor" görünür.
*   **Content-Disposition:** İndirme URL'leri `Content-Disposition: attachment` ile üretilir — tarayıcının HTML/SVG içeriği aynı origin'de render edip XSS üretmesi engellenir.

## 3. Etiketler (Labels)

### 3.1. Tablolar
**`labels`** — workspace bazlı etiket tanımları:
| Kolon Adı | Veri Tipi | Kısıtlamalar | Açıklama |
| :--- | :--- | :--- | :--- |
| id | UUID | PRIMARY KEY | Etiket kimliği. |
| workspace_id | UUID | FK -> workspaces(id), NOT NULL | RLS izolasyon anahtarı. |
| name | VARCHAR(50) | NOT NULL | Etiket adı (Örn: "bug", "backend"). |
| color | VARCHAR(7) | NOT NULL | Hex renk kodu. |

(Unique: `workspace_id + lower(name)` — aynı workspace'te büyük/küçük harf farkıyla mükerrer etiket oluşamaz.)

**`task_labels`** — çoka-çok pivot: `task_id` (FK) + `label_id` (FK), Composite PK.

### 3.2. Tasarım Kararı: Neden `custom_fields` (JSONB) Değil?
Etiketler `custom_fields` içine dizi olarak gömülebilirdi; ancak etiketler üzerinde **referans bütünlüğü** gereken işlemler vardır: etiket silindiğinde tüm görevlerden kalkmalı, yeniden adlandırıldığında her yerde değişmeli, "bu etiketli görev sayısı" hızla sorgulanabilmelidir. Bunlar ilişkisel modelin doğal işleridir; JSONB'de her biri tam tablo taraması ve manuel tutarlılık yönetimi gerektirir. `custom_fields`, kural taşımayan serbest veriler için kalır (Şema dokümanındaki sprint kararıyla aynı ilke).

Filtreleme sorgusu ("hem 'bug' hem 'urgent' etiketli görevler") pivot tablo üzerinden `GROUP BY ... HAVING COUNT` deseniyle, `task_labels(label_id, task_id)` indeksi sayesinde verimli çalışır.

## 4. Bildirimler (Notifications)

### 4.1. Tablo: `notifications`
| Kolon Adı | Veri Tipi | Kısıtlamalar | Açıklama |
| :--- | :--- | :--- | :--- |
| id | UUID | PRIMARY KEY | Bildirim kimliği. |
| workspace_id | UUID | FK -> workspaces(id), NOT NULL | RLS izolasyon anahtarı. |
| recipient_id | UUID | FK -> users(id), NOT NULL | Alıcı. |
| type | VARCHAR(50) | NOT NULL | TASK_ASSIGNED, COMMENT_MENTION, STATUS_CHANGED... |
| payload | JSONB | NOT NULL | Render için gereken bağlam (task başlığı, aktör adı vb. — denormalize). |
| read_at | TIMESTAMPTZ | NULLABLE | Okundu bilgisi. |
| created_at | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Oluşturulma zamanı. |

(İndeks: `recipient_id + read_at + created_at` — "okunmamışlar" sorgusu için partial index: `WHERE read_at IS NULL`. Bu tablo da `task_events` gibi zamanla büyür; aynı aylık partitioning stratejisi (Faz 3, Bölüm 1.0) uygulanır ve 90 günden eski okunmuş bildirimler partition drop ile temizlenir.)

### 4.2. Üretim Akışı (Mevcut Altyapının Yeniden Kullanımı)
Bildirim üretimi, Faz 3'teki **Notification Worker**'ın genişletilmesidir — yeni servis kurulmaz:

1.  Worker, `task.events` topic'indeki olayları dinler (idempotent — Processed Event Store ile).
2.  **Alıcı Çözümleme (Fan-out on Write):** Olay tipine göre alıcılar belirlenir (TASK_ASSIGNED -> atanan kişi; COMMENT_MENTION -> bahsedilenler; STATUS_CHANGED -> görevi izleyenler). Aktörün kendisi kendi eylemi için bildirim **almaz**.
3.  Her alıcı için `notifications` satırı yazılır ve kullanıcının kişisel WebSocket kanalına (`/user/queue/notifications`) anlık iletilir (çan ikonu sayacı canlı artar).
4.  **Kanal Tercihi:** Kullanıcı bazlı tercih tablosu (`notification_preferences`), her bildirim tipi için kanalları (in-app / e-posta / Slack) belirler. E-posta kanalı seçiliyse worker, güvenlik dokümanında (v1.1, Bölüm 1.4.3) tanımlanan transactional e-posta altyapısına `notification.email` olayı üretir.
5.  **Digest (Özet) Modu:** "Her olayda e-posta" spam üretir; e-posta kanalının varsayılanı **saatlik özet**tir (worker, alıcı bazında biriktirir ve tek e-postada gruplar). Anlık e-posta yalnızca mention ve atama gibi doğrudan-kişisel olaylarda gönderilir.

## 5. Plan Bazlı Kotalar (Plan Enforcement)
`workspaces.plan_type` kolonu şemada Faz 1'den beri vardır ancak hiçbir yerde uygulanmamaktadır. Bu bölüm kotanın **tanımını, sayımını ve uygulama noktasını** belirler.

### 5.1. Tablo: `plan_limits` (Konfigürasyon)
Limitler koda gömülmez; veritabanında tutulur ve deploy'suz değiştirilebilir:

| plan_type | max_projects | max_users | max_tasks | storage_gb | max_file_mb | api_rpm |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| free | 3 | 5 | 500 | 1 | 10 | 60 |
| premium | 25 | 50 | 50.000 | 100 | 100 | 600 |
| enterprise | sınırsız | sınırsız | sınırsız | 1.000 | 500 | 6.000 |

### 5.2. Kullanım Sayacı: `workspace_usage`
"Mevcut proje sayısı kaç?" sorusunu her istekte `COUNT(*)` ile cevaplamak büyük tablolarda pahalıdır. Bunun yerine `task_counters` deseninin genişletilmişi kullanılır: `workspace_usage` tablosu (`workspace_id` PK; `project_count`, `user_count`, `task_count`, `storage_bytes` kolonları), kaynak oluşturma/silme transaction'ının **içinde** atomik olarak artırılıp azaltılır (`UPDATE ... SET task_count = task_count + 1`). Gecelik bir mutabakat (reconciliation) job'ı, sayaçları gerçek `COUNT` değerleriyle karşılaştırıp sapmaları düzeltir ve loglar.

### 5.3. Uygulama Noktaları (Enforcement)
*   **Kaynak Kotaları (proje/görev/üye/depolama):** **Service katmanında**, kaynak oluşturma transaction'ının başında kontrol edilir: `if (usage.taskCount >= limit.maxTasks) throw new PlanLimitExceededException(...)`. Gateway bu kontrolü **yapamaz** — kota, iş verisi gerektirir ve Gateway'in iş verisine inmesi katman ihlalidir. Hata, RFC 7807 formatında `402`/`403` + `type: .../plan-limit-exceeded` olarak döner; React bu hata tipini yakalayıp "Planınızı yükseltin" ekranına yönlendirir.
*   **API Hız Kotası (api_rpm):** Bu tek kota **Gateway'de** uygulanır (Faz 4'teki Redis Token Bucket'ın anahtarı IP yerine `workspace_id` olacak şekilde ikinci bir limiter eklenir). Gateway, JWT'den workspace'i zaten çözdüğü için ek veri gerektirmez — katman ihlali yoktur.
*   **Düşürme (Downgrade) Senaryosu:** Premium'dan free'ye düşen ve 25 projesi olan workspace'in verisi **silinmez**; mevcut kaynaklar salt-okunur erişilebilir kalır, yalnızca **yeni kaynak oluşturma** engellenir (limit aşılmış durumda olduğu için). Veri silen downgrade, hem müşteri güvenini hem de geri dönüş (win-back) ihtimalini yok eder.

### ⚖️ Trade-off (Ödünleşim) Analizi
*   **Presigned URL vs. API Üzerinden Proxy:** Dosyaları API üzerinden akıtmak, her indirmede yetki kontrolünü ve loglama/izlemeyi tek noktada tutar; ancak 100MB'lık bir dosya indiren 50 kullanıcı, pod'ların tüm bant genişliğini ve worker thread'lerini tüketir. Presigned URL, veri düzlemini (data plane) kontrol düzleminden (control plane) ayırır: yetki kararı API'de kalır, baytlar S3'ten akar. Bedeli, URL'in ömrü boyunca (5 dk) paylaşılabilir olmasıdır — kısa TTL bu riski pratikte anlamsızlaştırır.
*   **Fan-out on Write vs. Fan-out on Read (Bildirimler):** Her alıcı için satır yazmak (on Write), okuma tarafını trivial yapar ("benim bildirimlerim" = tek indeksli sorgu) ama yazma hacmini büyütür (100 izleyicili görev = 100 satır). Alternatif olan on Read (olayları okuma anında alıcıya göre filtrele) yazmayı azaltır ama her çan ikonu açılışını pahalı bir sorguya çevirir. Bildirimler "az yazılır, çok okunur" olduğu için on Write doğru tercihtir; ekstrem izleyici sayıları (500+ kişilik duyuru) zaten digest'e yönlendirilir.
*   **Sayaç Tablosu vs. Her İstekte COUNT:** `workspace_usage` sayaçları $O(1)$ kota kontrolü sağlar ama "çift defter" riski taşır: bir kod yolu sayacı güncellemeyi unutursa kota yanlış uygulanır. `COUNT(*)` her zaman doğrudur ama milyonluk tabloda her POST'a indeks taraması ekler. Çözüm ikisinin birleşimidir: hızlı yol sayaçtır, gecelik mutabakat job'ı doğruluk sigortasıdır — sapma metriği (Faz 5) sıfırdan uzaklaşırsa alarm üretir.
*   **Limitleri DB'de Tutmak vs. Koda Gömmek:** Kodda `if (plan == FREE) max = 3` yazmak basittir ama her fiyatlandırma değişikliği deploy gerektirir ve satışın "bu müşteriye özel 10 proje" istisnası imkânsızlaşır. DB tabanlı `plan_limits` (+ workspace bazlı override kolonu) esneklik verir; bedeli, limitlerin de önbelleklenmesi gereken bir veri olmasıdır (Faz 5 Cache-Aside deseni birebir uygulanır — nadir değişir, sık okunur).

### 🚀 Mimari İpucu
**Kota Kontrolünde TOCTOU (Time-of-Check to Time-of-Use) Tuzağı:**
Kota kontrolünün klasik hatası şudur: `SELECT` ile sayacı oku (499 < 500, uygun), sonra görevi `INSERT` et. İki eşzamanlı istek aynı anda 499 okursa, ikisi de geçer ve limit 501 olur. Düşük hacimde masum görünen bu açık, limiti agresif kullanan müşterilerde sistematik kota aşımına dönüşür.

Çözüm, Faz 1'deki `task_counters` deseninin aynısıdır — kontrol ve artırımı **tek atomik komutta** birleştirin:

```sql
UPDATE workspace_usage
SET task_count = task_count + 1
WHERE workspace_id = :id
  AND task_count < :max_tasks   -- Kontrol, UPDATE'in koşuludur
RETURNING task_count;
```

Sıfır satır dönerse kota dolmuştur ve transaction, hiçbir kaynak oluşturmadan `PlanLimitExceededException` ile geri alınır. Satır kilidi sayesinde iki eşzamanlı istekten yalnızca biri son slotu alabilir — race condition **fiziksel olarak** imkânsızdır. Aynı mimari problemin (atomik sayaç) sistemde üçüncü kez karşınıza çıktığına dikkat edin (task_number, idempotency, kota): iyi bir mimaride desen sayısı azalır, kullanım sayısı artar.
