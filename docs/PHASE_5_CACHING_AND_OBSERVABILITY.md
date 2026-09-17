# Faz 5: Dağıtık Önbellekleme ve Gözlemlenebilirlik Tasarımı (v1.1)

> **v1.1 Değişiklikleri:** Merkezi log toplama mimarisi (Bölüm 4) ve Alerting + SLO tanımları (Bölüm 5) eklendi. Gözlemlenebilirlik artık üç ayak üzerinde tamamlanmıştır: Metrikler (Prometheus), Loglar (Loki), Trace'ler (Tempo — Faz 4'te tanımlanan OpenTelemetry altyapısı).

## 1. Dağıtık Önbellekleme (Distributed Caching)
Sık okunan ancak nadir değişen veriler (Workspace ayarları, Kullanıcı Rolleri, Proje Metadata'ları) PostgreSQL yerine **Redis** üzerinden sunulacaktır.

*   **Önbellek Stratejisi:** `Cache-Aside` (Lazy Loading) stratejisi kullanılacaktır. Uygulama önce Redis'e bakar, veri yoksa veritabanına gider, veriyi alır ve Redis'e yazar.
*   **Cache Invalidation (Geçersiz Kılma):** Veritabanında bir güncelleme olduğunda (Örn: Workspace adı değiştiğinde), ilgili Redis anahtarı (Key) anında silinir (`@CacheEvict`).

## 2. Cache Stampede Koruması (Distributed Lock)
Önbellek süresi dolduğunda (TTL bittiğinde) binlerce isteğin aynı anda veritabanına hücum etmesini engellemek için **Redisson** kütüphanesi ile Dağıtık Kilit (Distributed Mutex) uygulanacaktır.

**Örnek Uygulama Akışı:**
```java
public WorkspaceDTO getWorkspaceDetails(UUID workspaceId) {
    String cacheKey = "workspace:" + workspaceId;
    
    // 1. Önce Redis'e bak
    WorkspaceDTO cachedData = redisTemplate.opsForValue().get(cacheKey);
    if (cachedData != null) return cachedData;

    // 2. Veri yoksa kilit al (Sadece 1 thread bu kilidi alabilir)
    RLock lock = redissonClient.getLock("lock:" + cacheKey);
    try {
        // Kilit için en fazla 2 saniye bekle, kilidi alırsan 10 saniye tut
        if (lock.tryLock(2, 10, TimeUnit.SECONDS)) {
            
            // 3. Kilidi aldıktan sonra Redis'i tekrar kontrol et (Double-checked locking)
            cachedData = redisTemplate.opsForValue().get(cacheKey);
            if (cachedData != null) return cachedData;

            // 4. Veritabanına GİT (Sadece 1 kişi buraya ulaşır)
            WorkspaceDTO dbData = workspaceRepository.findById(workspaceId);
            redisTemplate.opsForValue().set(cacheKey, dbData, Duration.ofHours(1));
            return dbData;
        } else {
            // Kilidi alamayanlar kısa bir süre bekleyip Redis'ten tekrar okumayı dener
            Thread.sleep(50);
            return getWorkspaceDetails(workspaceId); // Retry
        }
    } catch (InterruptedException e) {
        throw new SystemException("Önbellek kilidi alınamadı");
    } finally {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
```

## 3. Gözlemlenebilirlik ve Metrikler (Observability)
Sistemin sağlığını izlemek için **Spring Boot Actuator**, **Prometheus** ve **Grafana** üçlüsü entegre edilecektir.

*   **Spring Boot Actuator:** Uygulama içindeki metrikleri `/actuator/prometheus` endpoint'i üzerinden dışarı açar.
*   **Prometheus:** Her 15 saniyede bir (Scrape Interval) servislerin Actuator endpoint'lerine giderek metrikleri toplar ve zaman serisi (Time-Series) veritabanında saklar.
*   **Grafana:** Prometheus'taki verileri okuyarak canlı dashboard'lar oluşturur.

**İzlenecek Kritik Metrikler (Golden Signals):**

*   **HikariCP (Veritabanı Havuzu):** `hikaricp.connections.active` ve `hikaricp.connections.pending`. Eğer pending artıyorsa veritabanı darboğazdadır.
*   **JVM Bellek:** `jvm.memory.used`. Memory Leak (Bellek Sızıntısı) tespiti için.
*   **HTTP Gecikmesi:** `http.server.requests`. API'lerin p95 ve p99 yanıt süreleri.
*   **Kafka Lag:** Tüketicilerin (Workers) olayları okumada ne kadar geride kaldığı.
*   **Outbox Sağlığı (Faz 2 referansı):** `outbox.oldest.unprocessed.age` — en eski işlenmemiş outbox kaydının yaşı. Büyüyorsa relay durmuştur.
*   **DLT Derinliği (Faz 2 referansı):** `*.DLT` topic'lerindeki mesaj sayısı. Sıfırdan büyük her değer incelenmesi gereken bir iştir.

## 4. Merkezi Log Toplama (Centralized Logging) — YENİ
Çoklu pod ve çoklu servis dünyasında `kubectl logs` ile hata aramak sürdürülemez. Tüm loglar tek merkezde toplanır ve trace'lerle ilişkilendirilir.

*   **Yığın Seçimi: Grafana Loki + Promtail.** ELK (Elasticsearch) yerine Loki tercih edilmiştir: Loki, log içeriğini full-text indekslemek yerine yalnızca etiketleri (service, pod, level) indeksler — bu, Elasticsearch'e kıyasla kat kat düşük RAM/disk maliyeti demektir ve mevcut Grafana ekosistemiyle (Prometheus, Tempo) tek arayüzde bütünleşir. Full-text log araması gereksinimleri LogQL'in satır filtrelemesiyle karşılanır; bu ölçekte Elasticsearch'ün gücüne ihtiyaç yoktur.
*   **Log Formatı — Yapılandırılmış JSON (Zorunlu Standart):** Tüm servisler stdout'a **JSON formatında** log basar (Logback + `logstash-logback-encoder`). Her satırda zorunlu alanlar: `timestamp`, `level`, `service`, `traceId`, `spanId`, `workspaceId` (varsa), `message`. Serbest metin log **yasaktır** — parse edilemeyen log, aranamayan logdur.
    ```json
    {"timestamp":"2026-07-18T14:04:00.123Z","level":"ERROR","service":"core-api","traceId":"5f9a3b...","spanId":"c81d...","workspaceId":"a3f8...","logger":"c.a.t.task.TaskService","message":"Görev güncellenemedi: optimistic lock çakışması"}
    ```
*   **Üç Sinyalin Korelasyonu:** `traceId` her log satırında bulunduğu için (Faz 4'teki Micrometer MDC entegrasyonu sayesinde) Grafana'da akış şudur: Dashboard'da p99 sıçraması görülür (Prometheus) -> ilgili zaman aralığındaki ERROR logları açılır (Loki) -> log satırındaki `traceId`'ye tıklanarak isteğin tüm servislerdeki yolculuğu görülür (Tempo). Üç sistem, tek tıkla birbirine bağlıdır.
*   **Retention:** Loglar Loki'de 30 gün tutulur; daha eskiler S3'e (düşük maliyetli obje depolama) taşınır. `workspaceId` etiketi sayesinde bir müşteriye ait loglar destek taleplerinde hızla filtrelenebilir.
*   **Log Hijyeni:** Parola, token, `Authorization` header'ı ve kişisel veriler asla loglanmaz; Logback seviyesinde maskeleme pattern'leri (Örn: `Bearer ***`) uygulanır ve bu kural CI'daki statik analizle denetlenir.

## 5. Alerting ve SLO Tanımları — YENİ
Metrik toplamak yeterli değildir; kimsenin bakmadığı dashboard, olmayan dashboard'dur. Alarm üretimi **Prometheus Alertmanager** ile yapılır.

### 5.1. Alarm Felsefesi: Semptoma Alarm, Nedene Dashboard
Alarmlar kullanıcının hissettiği **semptomlara** kurulur (hata oranı, gecikme); iç nedenlere (CPU %80, GC süresi) alarm kurulmaz — nedenler, semptom alarmı çaldığında bakılan dashboard'lardır. Bu kural, gece 03:00'te çalan ama kimsenin aksiyon alamadığı "gürültü alarmlarını" (Alert Fatigue) engeller. Her alarmın tanımında şu üç alan zorunludur: **ne bozuldu, kullanıcı etkisi ne, ilk bakılacak yer neresi** (runbook linki).

### 5.2. Alarm Kuralları (Başlangıç Seti)

| Alarm | Koşul | Öncelik | Kanal |
| :--- | :--- | :--- | :--- |
| Yüksek Hata Oranı | 5xx oranı > %1 (5 dk pencere) | **Page** (uyandır) | PagerDuty/Opsgenie |
| Yüksek Gecikme | p99 > 2sn (10 dk pencere) | Page | PagerDuty/Opsgenie |
| Kafka Consumer Lag | Lag > 10.000 mesaj VE artıyor | Page | PagerDuty/Opsgenie |
| DLT'ye Mesaj Düştü | DLT mesaj sayısı artışı > 0 | Ticket (mesai saatinde) | Slack #alerts |
| Outbox Relay Durdu | En eski işlenmemiş kayıt > 5 dk | Page | PagerDuty/Opsgenie |
| HikariCP Doygunluk | `pending` > 0 (5 dk boyunca sürekli) | Ticket | Slack #alerts |
| Sertifika/Disk | TLS bitimine < 14 gün; disk > %80 | Ticket | Slack #alerts |

("Page" = nöbetçiyi uyandırır; "Ticket" = mesai saatinde incelenir. Bir alarmın hangi sınıfa girdiği, "gece 3'te müdahale edilmezse müşteri zarar görür mü?" sorusuyla belirlenir.)

### 5.3. SLO (Service Level Objective) Tanımları
Alarm eşikleri keyfi değil, taahhüt edilen hedeflerden türetilir. Başlangıç SLO'ları:

*   **Erişilebilirlik:** Core API isteklerinin %99.9'u başarılı (5xx olmayan) yanıt alır (aylık pencere -> ayda ~43 dk hata bütçesi).
*   **Gecikme:** API isteklerinin %95'i < 500ms, %99'u < 2sn içinde yanıtlanır.
*   **Gerçek Zamanlılık:** Bir görev güncellemesinin diğer kullanıcıların ekranına yansıması p95 < 3sn (Outbox -> Kafka -> WebSocket uçtan uca).
*   **Hata Bütçesi (Error Budget) Kuralı:** Aylık hata bütçesinin %50'si tükendiğinde ekip uyarılır; bütçe biterse yeni özellik deploy'ları durdurulur ve kapasite/stabilite işine öncelik verilir. Bu, "hız mı güvenilirlik mi" tartışmasını sübjektif olmaktan çıkarıp veriye bağlar.

### ⚖️ Trade-off (Ödünleşim) Analizi
*   **Cache Invalidation Zorluğu:** Bilgisayar bilimlerindeki en zor iki şeyden biri önbellek geçersiz kılmadır (Cache Invalidation). Veriyi **Redis**'e koymak sistemi inanılmaz hızlandırır ama "Eski Veri" (Stale Data) gösterme riskini doğurur. Eğer bir verinin 1-2 dakika eski görünmesi iş kuralları gereği kabul edilemezse, o veri KESİNLİKLE önbelleklenmemelidir.
*   **Metrik Saklama Maliyeti:** **Prometheus** her saniye binlerce metrik toplar. Bu verileri sonsuza kadar saklamak devasa bir disk maliyeti yaratır. Bu yüzden Prometheus'ta "Retention Policy" (Veri Tutma Süresi) 15 gün olarak ayarlanmalı, daha eski veriler ya silinmeli ya da daha düşük çözünürlükle (Downsampling) soğuk depolamaya aktarılmalıdır.
*   **Loki vs. Elasticsearch (ELK):** Elasticsearch, log içinde tam metin arama ve karmaşık aggregation'larda rakipsizdir; ancak bu güç, ciddi bir RAM iştahı ve cluster yönetim yüküyle gelir. Loki, "loglar zaten traceId ve etiketlerle bulunur, tam metin arama nadiren gerekir" varsayımıyla maliyeti kat kat düşürür. Bu projede loglar her zaman `traceId`/`workspaceId` üzerinden aranacağı için Loki'nin varsayımı geçerlidir. İleride log üzerinde analitik ihtiyacı doğarsa (Örn: güvenlik olay analizi), o spesifik akış için ayrıca değerlendirme yapılır.
*   **Az Alarm vs. Çok Alarm:** Her metriğe alarm kurmak "hiçbir şeyi kaçırmama" hissi verir ama pratikte tersine çalışır: sürekli çalan alarmlar duyarsızlaşma (Alert Fatigue) yaratır ve gerçek felaket, 50 gürültü alarmının arasında kaybolur. Az sayıda, yüksek sinyalli, semptom bazlı alarm; nöbetçinin her çalan alarmı ciddiye almasını sağlar. Eksik kalan görünürlük, alarmla değil dashboard ve haftalık metrik gözden geçirmeleriyle kapatılır.

### 🚀 Mimari İpucu
**SRE Standardı: RED Metodolojisi**
Grafana dashboard'larınızı tasarlarken ekibinizin rastgele grafikler koymasını engelleyin. Endüstri standardı olan **RED Metodolojisini** kullanın. Her mikroservis/modül için dashboard'un en üstünde şu 3 grafik yan yana durmalıdır:

1.  **R (Rate):** Saniyede gelen istek sayısı (RPS - Requests Per Second).
2.  **E (Errors):** Başarısız isteklerin (5xx hataları) oranı.
3.  **D (Duration):** İsteklerin işlenme süresi (Özellikle 95. yüzdelik dilim - p95 Latency).

Eğer bir gece saat 03:00'te sistem alarm verirse, nöbetçi mühendis sadece bu 3 grafiğe bakarak sorunun trafik patlamasından mı (Rate), kod hatasından mı (Errors) yoksa veritabanı yavaşlamasından mı (Duration) kaynaklandığını saniyeler içinde anlayabilir.

**Alarm Çaldığında İlk 5 Dakika — Runbook Zorunluluğu:** Her alarm tanımına bir runbook (müdahale kılavuzu) linki eklemeyi zorunlu kılın. Runbook şu üç soruyu yanıtlar: (1) Bu alarm ne anlama gelir? (2) İlk bakılacak dashboard/sorgu hangisi? (3) Bilinen geçici çözüm var mı (Örn: "feature flag X'i kapat", "pod'u restart et")? Runbook'suz alarm, gece 03:00'te nöbetçiyi çıplak bırakmaktır — ve Faz 0'daki feature flag altyapısı, runbook'lardaki en hızlı "geçici çözüm" aracınızdır.
