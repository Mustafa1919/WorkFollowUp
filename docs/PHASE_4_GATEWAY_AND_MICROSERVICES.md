# Faz 4: API Gateway, Servis Ayrışımı ve gRPC İletişimi (v1.1)

> **v1.1 Değişiklikleri:** Dağıtık izleme (Distributed Tracing), manuel Trace ID taşıma yaklaşımından **OpenTelemetry standardına** taşındı (Mimari İpucu bölümü güncellendi).

## 1. Servis Topolojisi (Modüler Monolit + Worker'lar)
Sistem tamamen mikroservislere bölünmeyecek, "Core" işlemleri modüler monolit içinde kalırken, asimetrik yük yaratan alanlar dışarı çıkarılacaktır:
*   **Core API (Modüler Monolit):** Auth, Workspace, Project, Task domainlerini barındırır. Sistemin ana yazma (Write) merkezidir.
*   **Analytics Service:** Sadece okuma (Read Replica) ve Kafka olaylarını dinleyerek metrik hesaplama işlerini yapar.
*   **Webhook Ingestion Service:** Dış dünyadan (GitHub, GitLab) gelen anlık trafik patlamalarını (Burst) karşılar.

## 2. API Gateway (Sistemin Tek Giriş Kapısı)
Tüm dış trafik (React Client, Mobil Uygulama, Dış Webhook'lar) doğrudan servislere değil, **Spring Cloud Gateway** (veya Kong) üzerinden sisteme girer.

**API Gateway'in Temel Görevleri:**
1.  **Dinamik Yönlendirme (Routing):**
    *   `/api/v1/webhooks/**` -> Webhook Service'e yönlendirilir.
    *   `/api/v1/analytics/**` -> Analytics Service'e yönlendirilir.
    *   `/ws/**` -> WebSocket (Gerçek Zamanlı) sunucusuna yönlendirilir.
    *   `/**` (Geri kalan her şey) -> Core API'ye yönlendirilir.
2.  **Rate Limiting (Hız Sınırlandırma):** Redis tabanlı "Token Bucket" algoritması kullanılarak, bir IP'nin saniyede en fazla 50 istek atabilmesi (DDoS koruması) Gateway seviyesinde sağlanır. (Not: IP bazlı limit altyapı korumasıdır; tenant/plan bazlı iş kotaları için bkz. `PHASE_6_PRODUCT_FEATURES.md`, Bölüm 5.)
3.  **SSL Sonlandırma (SSL Termination):** HTTPS sertifikaları Gateway'de çözülür, iç ağdaki servisler birbirleriyle şifresiz (daha hızlı) HTTP/gRPC üzerinden konuşur.
4.  **Token Doğrulama (Offloading):** Gateway, gelen JWT'nin geçerli olup olmadığını (imzasını) kontrol eder. Geçersizse isteği Core servise hiç yollamadan `401 Unauthorized` dönerek iç ağı korur.

## 3. İç İletişim: gRPC (Google Remote Procedure Call)
Servisler arası senkron veri alışverişi gerektiren durumlarda REST (JSON) yerine **gRPC (Protobuf - Protocol Buffers)** kullanılacaktır.

*   **Neden gRPC?** HTTP/2 üzerinde çalışır, veriyi metin (JSON) olarak değil binary (ikili) formatta sıkıştırarak iletir. REST'e göre ağda ortalama 7-10 kat daha hızlıdır.
*   **Kullanım Senaryosu:** Webhook Service, GitHub'dan bir olay aldığında, bu objenin sistemde yetkili olup olmadığını öğrenmek için Core API'ye bir gRPC çağrısı yapar.
*   **Sözleşme (Contract):** `core-service.proto` adında bir dosya oluşturulur.
    ```protobuf
    syntax = "proto3";
    
    service WorkspaceService {
      rpc VerifyRepository (VerifyRepoRequest) returns (VerifyRepoResponse);
    }
    
    message VerifyRepoRequest {
      string github_repo_url = 1;
    }
    
    message VerifyRepoResponse {
      bool is_valid = 1;
      string workspace_id = 2;
    }
    ```
    Bu dosya sayesinde hem Core ekibi hem de Webhook ekibi, iletişim formatını kesin bir kuralla (Strongly Typed) belirlemiş olur.

### ⚖️ Trade-off (Ödünleşim) Analizi
*   **API Gateway vs. Doğrudan İletişim:** API Gateway kullanmak sisteme ekstra bir ağ atlaması (Network Hop) ekler, bu da isteklere ortalama 5-10ms gecikme (Latency) katar. Ayrıca Gateway çökerse tüm sistem çöker (Single Point of Failure). Ancak Gateway kullanmamak; React istemcisinin 3 farklı servisin IP adresini bilmesini, her servisin kendi Rate Limiting ve JWT doğrulama mantığını tekrar tekrar yazmasını gerektirir. Kurumsal bir mimaride Gateway'in getirdiği yönetim kolaylığı, 10ms'lik gecikme maliyetine fazlasıyla değer.
*   **gRPC vs. İç REST API:** İç iletişimde REST kullanmak (Örn: `RestTemplate` veya `FeignClient`) geliştiriciler için çok kolaydır; hataları Postman ile kolayca debug edebilirsiniz. gRPC ise binary olduğu için okunamaz, özel araçlar (BloomRPC) gerektirir ve öğrenme eğrisi vardır. Ancak mikroservisler arası iletişimde JSON parse etme maliyeti CPU'yu en çok yoran şeydir. Yüksek trafikli bir sistemde iç iletişimi gRPC ile yapmak, sunucu maliyetlerinizi (CPU kullanımı) ciddi oranda düşürür.
*   **Manuel Trace Taşıma vs. OpenTelemetry Otomatik Enstrümantasyon:** Trace ID'yi elle üretip her HTTP header'ına, gRPC metadata'sına ve Kafka payload'una kod yazarak taşımak ilk bakışta "basit ve bağımlılıksız" görünür. Ancak pratikte her yeni iletişim kanalında (yeni bir servis, yeni bir topic, bir `@Async` thread geçişi) taşımayı **unutmak** garantidir ve trace zinciri sessizce kopar — en çok ihtiyaç duyduğunuz anda (prod incident) izin yarısı kayıptır. OpenTelemetry, küçük bir bağımlılık ve ajan yapılandırması karşılığında bu taşımayı tüm kanallarda otomatik ve standart (W3C Trace Context) yapar. Manuel yaklaşımın tek gerçek avantajı olan "sıfır bağımlılık", kopan trace'lerin maliyeti yanında anlamsızdır.

### 🚀 Mimari İpucu
**Dağıtık İzleme: OpenTelemetry Standardı (Manuel Trace ID Taşımayın) — GÜNCELLENDİ**
Sistemi Gateway ve servislere böldüğünüz an yaşayacağınız en büyük kabus şudur: Kullanıcı arayüzde bir butona basar, Gateway isteği alır, Webhook servisine iletir, o gRPC ile Core servise sorar, Core Kafka'ya mesaj atar, Analytics servisi patlar. Hata nerede oldu?

Logları incelerken milyonlarca satır arasında bu spesifik isteği bulmanın zaman karmaşıklığı $O(N)$ olacaktır. Bunu $O(1)$ seviyesine indirmek için Trace ID gereklidir — ancak bu ID'yi **elle taşımayın**. Endüstri standardı **OpenTelemetry (OTel)** bunu sizin için yapar:

*   **Uygulama Tarafı:** Spring Boot 3'te **Micrometer Tracing** (OTel bridge ile) eklenir. Bu kadar. HTTP istekleri (gelen ve giden), gRPC çağrıları ve Spring Kafka producer/consumer'ları **otomatik olarak** enstrümante edilir; `traceId` ve `spanId`, W3C Trace Context standardındaki `traceparent` header'ı ile (Kafka'da mesaj header'ı olarak) kanallar arasında otomatik taşınır. Kafka payload'una elle ID koymak gerekmez ve **yapılmamalıdır** (payload iş verisidir, telemetri altyapı katmanına aittir).
*   **Loglarla Otomatik Korelasyon:** Micrometer, aktif `traceId`/`spanId`'yi MDC'ye otomatik koyar; Faz 5'teki JSON log formatı bu alanları her satıra basar. Böylece bir trace'ten loglara, bir log satırından trace'e tek tıkla geçilir.
*   **Toplama:** Her Kubernetes node'unda bir **OTel Collector** (agent) çalışır; servisler span'leri OTLP protokolüyle Collector'a gönderir, Collector bunları backend'e (Grafana **Tempo** — Faz 5'teki Grafana yığınıyla bütünleşiktir — veya Jaeger) iletir. Örnekleme (sampling) kararı uygulamada değil Collector'da yönetilir: başlangıç için "hatalı istekleri her zaman, başarılıları %10 örnekle" (Tail-Based Sampling) kuralı hem maliyeti düşürür hem hiçbir hatayı kaçırmaz.
*   **Dikkat — Async Sınırları:** Otomatik enstrümantasyonun kör noktası elle açılan thread'lerdir (`CompletableFuture.runAsync`, özel `ExecutorService`). Bu noktalarda context taşınması için Spring'in `ContextPropagatingTaskDecorator`'ı standart olarak tüm executor tanımlarına eklenir — ekip kuralıdır.

Sonuç aynıdır ama garantilidir: Grafana'ya girip `traceId="5f9a3b..."` yazdığınızda, isteğin Gateway'den başlayıp Analytics servisinde patlayana kadar geçtiği tüm servislerdeki span'lerini ve loglarını tek ekranda, kronolojik sırayla görürsünüz — ve hiçbir geliştiricinin "header'ı taşımayı unutması" bu zinciri koparamaz.
