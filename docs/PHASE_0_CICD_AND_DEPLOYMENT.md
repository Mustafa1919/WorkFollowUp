# Faz 0: CI/CD, Ortam Yönetimi ve Deployment Mimarisi (v1.0)

Bu faz, kod yazılmaya başlanmadan **önce** kurulması gereken operasyonel altyapıyı tanımlar. Faz 1-5'teki tüm mimari kararlar (RLS, Kafka, çoklu pod), bu fazda tanımlanan deployment disiplinine dayanır. "Önce özellik, sonra pipeline" yaklaşımı, teknik borcun en pahalı türünü üretir.

## 1. Ortam Stratejisi (Environments)
Üç izole ortam tanımlanır:

| Ortam | Amaç | Veri | Deploy Tetikleyicisi |
| :--- | :--- | :--- | :--- |
| **dev** | Geliştirici entegrasyon ortamı | Sentetik / anonim veri | `main` branch'ine her merge (otomatik) |
| **staging** | Prod kopyası, son doğrulama | Anonimleştirilmiş prod benzeri veri | Release tag'i (otomatik) |
| **prod** | Canlı sistem | Gerçek müşteri verisi | Staging onayı sonrası **manuel onay** (tek tık) |

**Kurallar:**
*   Ortamlar arasında **hiçbir konfigürasyon kod içinde** taşınmaz; tüm ortam farkları (DB adresi, Kafka broker, log seviyesi) environment variable / Kubernetes ConfigMap üzerinden verilir (12-Factor App prensibi).
*   Staging, prod ile **aynı topolojide** çalışır (aynı pod sayısı oranı, aynı Kafka partition sayısı). Aksi halde Faz 2'deki çoklu-pod bug'ları (WebSocket fan-out gibi) staging'de yakalanamaz.
*   Prod veritabanına geliştirici erişimi varsayılan olarak **kapalıdır**; erişim break-glass prosedürü ile, süreli ve loglanarak açılır.

## 2. Containerization (Docker)
Tüm servisler (Core API, Analytics, Webhook Ingestion) tek standart Dockerfile şablonundan türetilir:

*   **Multi-Stage Build:** İlk aşamada Maven/Gradle ile derleme, ikinci aşamada yalnızca JRE + uygulama JAR'ı içeren minimal imaj (`eclipse-temurin:21-jre-alpine` tabanlı). İmaj boyutu ve saldırı yüzeyi küçültülür.
*   **Layered JAR:** Spring Boot'un layered JAR özelliği kullanılır; bağımlılık katmanı ayrı Docker layer'ında tutulur. Kod değişikliğinde yalnızca ~1MB'lık uygulama katmanı yeniden yüklenir, CI süresi ve registry maliyeti düşer.
*   **Non-Root User:** Container'lar root olmayan bir kullanıcı ile çalışır (Güvenlik taramalarının ilk maddesi).
*   **İmaj Etiketleme:** `latest` etiketi **asla** deploy'da kullanılmaz. Her imaj, git commit SHA'sı ile etiketlenir (`core-api:a3f8b21`). Böylece "prod'da hangi kod koşuyor?" sorusunun cevabı her zaman kesindir ve rollback bir etiket değişikliğinden ibarettir.

## 3. CI Pipeline (Continuous Integration)
Her Pull Request'te otomatik koşan aşamalar (GitHub Actions / GitLab CI):

1.  **Build & Unit Test:** Derleme ve birim testler.
2.  **Statik Analiz:** Checkstyle/Spotless (format), SonarQube veya SpotBugs (kod kalitesi), OWASP Dependency-Check (bilinen zafiyetli bağımlılıklar).
3.  **Entegrasyon Testleri:** Testcontainers ile gerçek PostgreSQL + Kafka ayağa kaldırılarak koşulur. (Kapsam ve derinlik ekibin insiyatifindedir; ancak pipeline'da bu aşamanın **yeri** baştan açılır.)
4.  **İmaj Build & Scan:** Docker imajı build edilir, Trivy ile imaj zafiyet taraması yapılır.
5.  **Migration Doğrulaması:** Flyway migration'ları boş bir PostgreSQL'e ve bir önceki sürümün şemasına karşı uygulanarak "temiz kurulum" ve "upgrade" senaryolarının ikisi de doğrulanır.

`main` branch her zaman deploy edilebilir durumda tutulur (Trunk-Based Development). Uzun ömürlü feature branch'lerden kaçınılır; tamamlanmamış özellikler **feature flag** arkasına alınır.

## 4. Deployment (Kubernetes) ve Zero-Downtime
Servisler Kubernetes üzerinde koşar. Sıfır kesintili deployment için üç mekanizma birlikte çalışır:

### 4.1. Rolling Update + Sağlık Kontrolleri
*   **Deployment Stratejisi:** `RollingUpdate` (`maxSurge: 1`, `maxUnavailable: 0`) — yeni pod sağlıklı olmadan eski pod öldürülmez.
*   **Probe'lar (Spring Boot Actuator ile):**
    *   `readinessProbe` -> `/actuator/health/readiness`: Pod, DB ve Kafka bağlantıları hazır olmadan trafiğe **alınmaz**.
    *   `livenessProbe` -> `/actuator/health/liveness`: Kilitlenen pod otomatik yeniden başlatılır.
*   **Graceful Shutdown:** `server.shutdown=graceful` + `terminationGracePeriodSeconds: 30`. Pod kapanma sinyali (SIGTERM) aldığında yeni istek kabul etmeyi bırakır, eldeki istekleri ve **Kafka consumer'ın işlemekte olduğu mesajı** bitirir, offset'i commit eder, sonra kapanır. Bu olmadan her deploy, yarım kalmış transaction ve tekrar işlenen Kafka mesajı üretir.

### 4.2. Veritabanı Migration'larının Deploy ile İlişkisi — KRİTİK
Rolling deployment sırasında **eski ve yeni kod aynı anda, aynı veritabanına karşı çalışır.** Bu nedenle demir kural şudur:

> **Her migration, bir önceki uygulama sürümüyle geriye uyumlu (Backward-Compatible) olmak ZORUNDADIR.**

Kırıcı değişiklikler (kolon silme, yeniden adlandırma, NOT NULL ekleme) tek adımda yapılmaz; **Expand → Migrate → Contract** deseniyle en az iki release'e yayılır:

| Adım | Release | Örnek: `title` kolonunu `summary` olarak yeniden adlandırma |
| :--- | :--- | :--- |
| **Expand** | N | Yeni `summary` kolonu eklenir (NULLABLE). Kod her iki kolona da yazar, `summary`'den okur (yoksa `title`'a düşer). |
| **Migrate** | N (arka plan) | Batch job eski verileri `title` -> `summary` kopyalar. |
| **Contract** | N+1 | Kod artık yalnızca `summary` kullanır; `title` kolonu **bir sonraki** release'de silinir. |

**Uygulama Kararları:**
*   Flyway migration'ları uygulama startup'ında değil, deployment öncesi ayrı bir **Kubernetes Job** (init adımı) olarak koşar. Böylece 10 pod'un aynı anda migration lock'u için yarışması engellenir ve migration hatası, trafik alan pod'ları hiç etkilemeden deploy'u durdurur.
*   Migration Job'ı `app_migrator` rolüyle, uygulama pod'ları `app_runtime` rolüyle bağlanır (Faz 1, Bölüm 3.1'deki rol ayrımı burada operasyonelleşir).
*   Uzun sürecek indeks oluşturma işlemleri **her zaman** `CREATE INDEX CONCURRENTLY` ile yapılır (tabloyu kilitlemez); bu komut transaction içinde çalışamadığı için ilgili migration dosyaları Flyway'de `executeInTransaction=false` olarak işaretlenir.

### 4.3. Rollback Stratejisi
*   **Uygulama Rollback:** Commit SHA etiketli imaj sayesinde tek komutla bir önceki imaja dönülür (`kubectl rollout undo`).
*   **Veritabanı Rollback:** Migration'lar **geri alınmaz** (down script yazılmaz). Backward-compatible migration kuralı sayesinde, eski uygulama sürümü yeni şemayla sorunsuz çalışır — rollback yalnızca uygulama katmanında yapılır. Bu, "down migration'ın prod verisini bozması" riskini kökten ortadan kaldırır.

## 5. Secrets Yönetimi (Gizli Bilgiler)
Sistemdeki hassas değerler — PostgreSQL şifreleri (`app_runtime`, `app_migrator`, `app_worker` rolleri için ayrı ayrı), JWT imzalama private key'i (RS256), webhook HMAC secret'ları, Slack API token'ları, Redis şifresi — için kurallar:

*   **Asla kod deposunda tutulmaz:** Secrets, git'e giren hiçbir dosyada (application.yml dahil) bulunamaz. CI, bunu her PR'da tarayan bir secret-scanning adımı (gitleaks/trufflehog) ile zorlar.
*   **Kaynak:** Başlangıç için **Kubernetes Secrets** + **External Secrets Operator** kullanılır; secret'ların asıl kaynağı bulut sağlayıcının kasasıdır (AWS Secrets Manager / GCP Secret Manager). Uygulama secret'ları yalnızca environment variable veya mounted file olarak görür. Ölçek ve ekip büyüdüğünde dinamik secret üretimi (kısa ömürlü DB şifreleri) için HashiCorp Vault'a geçiş değerlendirilir.
*   **Rotasyon:** Her secret'ın bir rotasyon politikası vardır (DB şifreleri: 90 gün; JWT private key: yıllık, `kid` header'ı ile çoklu-anahtar desteği sayesinde kesintisiz). JWT key rotasyonunda eski public key, mevcut token'ların ömrü (15 dk) boyunca doğrulama setinde tutulur.
*   **Erişim İzolasyonu:** Faz 1'deki rol ayrımının secrets karşılığı: `app_migrator` şifresi yalnızca migration Job'ının namespace secret'ında bulunur; uygulama pod'larının bu secret'a **erişimi yoktur** (Kubernetes RBAC ile zorlanır).

## 6. Deployment Sıralaması (Servisler Arası Bağımlılık)
Faz 4'te sistem birden fazla servise ayrıldığında deploy sırası önem kazanır:
1.  Önce **sözleşmeyi genişleten** taraf deploy edilir (Örn: gRPC servisine yeni alan ekleyen Core API).
2.  Sonra bu yeni alanı **kullanan** taraf deploy edilir (Örn: Webhook Service).
3.  Kafka Event Envelope değişiklikleri de aynı kurala tabidir: Önce yeni alanı **tolere eden** consumer'lar, sonra yeni alanı **üreten** producer deploy edilir. (Consumer'lar bilinmeyen JSON alanlarını her zaman yok saymalıdır — `FAIL_ON_UNKNOWN_PROPERTIES=false`.)

### ⚖️ Trade-off (Ödünleşim) Analizi
*   **Kubernetes vs. Basit VM/Docker-Compose:** Faz 1'de tek bir monolit için Kubernetes "fazla mühendislik" gibi görünebilir; Docker Compose ile bir VM'de başlamak çok daha hızlıdır. Ancak mimarinin tamamı (Faz 2 WebSocket fan-out, Faz 4 servis ayrışımı, Faz 5 observability) **çoklu pod varsayımı** üzerine kuruludur. Compose ile başlayıp sonra K8s'e geçmek, tüm deployment disiplininin (probe'lar, graceful shutdown, migration job'ları) iki kez kurulması demektir. Yönetilen bir Kubernetes servisi (EKS/GKE/AKS) kullanarak operasyon yükü makul seviyeye çekilir ve baştan hedef mimariye uygun zemin kurulur.
*   **Startup'ta Flyway vs. Ayrı Migration Job:** Flyway'i uygulama startup'ında koşturmak (Spring Boot varsayılanı) sıfır ek konfigürasyonla çalışır ve küçük ekipler için caziptir. Ancak çoklu pod'da migration lock yarışı, yavaş migration'ın readiness probe'unu timeout'a düşürmesi ve migration hatasının "yarısı yeni yarısı eski pod" durumu yaratması gibi riskler taşır. Ayrı Job yaklaşımı bir miktar pipeline karmaşıklığı ekler ama migration'ı deploy'un **açıkça görünür ve tek başına geri alınabilir** bir adımı yapar. Kurumsal bir sistemde bu görünürlük, konfigürasyon kolaylığından değerlidir.
*   **Manuel Prod Onayı vs. Tam Otomatik (Continuous Deployment):** Staging'den prod'a tam otomatik akış (her merge prod'a gider) en hızlı geri bildirim döngüsünü verir; ancak bunu güvenle yapabilmek çok güçlü otomatik test ve canary altyapısı gerektirir. Test stratejisinin henüz olgunlaşmadığı bu projede, prod öncesi **tek tıklık manuel onay** düşük maliyetli bir sigortadır. Otomatik test kapsamı büyüdükçe bu onay kaldırılarak tam CD'ye geçilebilir.

### 🚀 Mimari İpucu
**Feature Flag'ler: Deploy'u Release'ten Ayırın**
Zero-downtime altyapısının asıl süper gücü, **deploy** (kodun sunucuya gitmesi) ile **release** (özelliğin kullanıcıya açılması) kavramlarını ayırabilmenizdir. Yeni ve riskli bir özelliği (Örn: Faz 2'nin gerçek zamanlı Kanban güncellemeleri) bir feature flag arkasında prod'a deploy edin — kod prod'dadır ama kapalıdır.

Ardından flag'i kademeli açın: önce kendi iç workspace'iniz, sonra trafiğin %5'i, sonra %100. Bir sorun çıkarsa **deploy geri almazsınız** (dakikalar sürer, risklidir); **flag'i kapatırsınız** (saniyeler sürer, risksizdir). Multi-tenant mimariniz burada size bedava bir hediye verir: `workspace_id`, mükemmel bir flag hedefleme anahtarıdır — "bu özelliği şimdilik sadece X ve Y müşterisine aç" demek tek satır konfigürasyondur. Basit bir başlangıç için veritabanı tablosu + Redis cache yeterlidir; ölçek büyüyünce Unleash veya Flagsmith gibi açık kaynak araçlara geçilebilir.
