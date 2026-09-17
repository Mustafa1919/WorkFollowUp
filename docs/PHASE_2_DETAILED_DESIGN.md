# Faz 2: Olay Güdümlü Mimari ve Gerçek Zamanlı İletişim Tasarımı (v1.1)

## 1. Kafka Topic ve Partition Stratejisi
Sistemdeki olaylar, iş alanlarına (domain) göre Kafka topic'lerine ayrılacaktır.
*   **Topic Adlandırma Standardı:** `<domain>.events` (Örn: `task.events`, `project.events`).
*   **Mesaj Formatı:** Tüm Kafka mesajları JSON formatında ve standart bir "Event Envelope" (Olay Zarfı) yapısında olacaktır.
    ```json
    {
      "eventId": "uuid",
      "eventType": "TASK_STATUS_UPDATED",
      "schemaVersion": 1,
      "timestamp": "2026-07-18T17:54:00Z",
      "aggregateId": "task-uuid",
      "workspaceId": "workspace-uuid",
      "payload": { "oldStatus": "TODO", "newStatus": "IN_PROGRESS" }
    }
    ```

### 1.1. Mesaj Şeması Evrimi (Schema Evolution) Kuralları
Olaylar bir kez üretildikten sonra topic'te günlerce yaşar; producer güncellenirken eski consumer'lar hâlâ eski kodla çalışır. Şema değişikliklerinde şu kurallar **zorunludur**:

*   **`schemaVersion` alanı zorunludur:** Her envelope, payload şemasının versiyonunu taşır. Consumer'lar desteklemedikleri versiyonu DLT'ye yönlendirir (sessizce yanlış parse etmek yerine).
*   **Yalnızca eklemeli (Additive) değişiklik:** Yeni alan eklemek serbesttir; var olan alanı silmek, yeniden adlandırmak veya tipini değiştirmek **yasaktır**. Kırıcı değişiklik gerekiyorsa `schemaVersion` artırılır ve consumer'lar her iki versiyonu da işleyebilir hale getirildikten sonra producer geçiş yapar (Faz 0'daki "önce tolere eden consumer, sonra üreten producer" deploy sırası kuralı).
*   **Bilinmeyen alanlar yok sayılır:** Tüm consumer'larda Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false` ayarı standarttır. Böylece producer'ın eklediği yeni alan, güncellenmemiş consumer'ı kırmaz.
*   **Gelecek Notu:** Topic ve olay tipi sayısı arttığında (Faz 4 sonrası) bu manuel disiplin yerini **Confluent Schema Registry + Avro**'ya bırakmalıdır; Registry, uyumsuz şemayı producer daha mesajı gönderemeden reddeder.

## 2. Transactional Outbox Pattern İmplementasyonu
Veri kaybını (Dual-Write problemini) önlemek için Outbox deseni uygulanacaktır.

1.  **Outbox Tablosu:** Veritabanında `outbox_events` adında bir tablo oluşturulur (Flyway ile).
2.  **Aynı Transaction:** Core API'de bir görev güncellendiğinde, `@Transactional` anotasyonu altındaki aynı metotta hem `tasks` tablosu güncellenir hem de `outbox_events` tablosuna olay yazılır.
3.  **Message Relay (İletici):** Spring Boot içinde çalışan bir `@Scheduled` worker (veya ileride Debezium), `outbox_events` tablosundaki işlenmemiş kayıtları okur, Kafka'ya gönderir ve başarılı olursa kaydı "işlendi" olarak işaretler (veya siler).
4.  **Outbox Temizliği (Cleanup) — İhmal Edilirse Tablo Şişer:** İşlenen kayıtlar anında silinmez ("işlendi" işaretlenir — hata ayıklama ve replay için değerlidir), ancak süresiz de tutulmaz:
    *   Gecelik bir temizlik job'ı, `processed_at < NOW() - INTERVAL '7 days'` olan kayıtları **batch'ler halinde** siler (tek dev `DELETE` yerine 10.000'lik parçalarla — uzun süreli kilit ve WAL şişmesi önlenir).
    *   Yüksek hacimde bu tablo da `created_at` üzerinden günlük/haftalık **partition'lanmalı** ve temizlik `DELETE` yerine `DROP PARTITION` ile yapılmalıdır (milisaniyeler sürer, vacuum yükü sıfırdır).
    *   `outbox_events` tablo boyutu ve işlenmemiş kayıt yaşı (`oldest unprocessed age`) Faz 5'te Prometheus metriği olarak izlenir; işlenmemiş kayıt yaşı büyüyorsa relay durmuş demektir — alarm üretilir.

## 3. Kafka Consumer Hata Yönetimi: Retry ve Dead Letter Queue (DLQ)
Producer tarafı Outbox ile güvence altındadır; ancak **consumer tarafı** da aynı disiplinle tasarlanmalıdır. Bozuk veya işlenemeyen tek bir mesaj ("Poison Pill"), varsayılan davranışta sonsuz döngüde tekrar denenerek ait olduğu partition'daki tüm mesajların işlenmesini bloke eder. Bu, event-driven mimarilerin en klasik prod felaketidir.

### 3.1. Hata Sınıflandırması
Consumer'daki hatalar iki sınıfa ayrılır ve farklı muamele görür:

| Hata Tipi | Örnek | Strateji |
| :--- | :--- | :--- |
| **Geçici (Transient / Retryable)** | DB bağlantı kopması, timeout, kilit çakışması | Exponential Backoff ile yeniden dene |
| **Kalıcı (Permanent / Non-Retryable)** | Bozuk JSON, şemaya uymayan payload, `NullPointerException`, iş kuralı ihlali | **Tekrar deneme YAPMA**, doğrudan DLT'ye gönder |

Kalıcı hataları tekrar denemek hem anlamsızdır (aynı input aynı hatayı üretir) hem de partition'ı gereksiz yere bloke eder.

### 3.2. Spring Kafka İmplementasyonu
Spring Kafka'nın `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` ikilisi standart olarak kullanılacaktır:

```java
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
    // Başarısız mesajı "<orijinal-topic>.DLT" adlı topic'e, AYNI partition numarasıyla gönderir
    var recoverer = new DeadLetterPublishingRecoverer(template);

    // Exponential Backoff: 1sn -> 2sn -> 4sn -> 8sn (maks. 4 deneme), sonra DLT
    var backOff = new ExponentialBackOff(1000L, 2.0);
    backOff.setMaxAttempts(4);

    var handler = new DefaultErrorHandler(recoverer, backOff);

    // Kalıcı hatalar için retry'ı atla, doğrudan DLT'ye gönder
    handler.addNotRetryableExceptions(
        DeserializationException.class,
        MessageConversionException.class,
        IllegalArgumentException.class,
        BusinessRuleException.class
    );
    return handler;
}
```

*   **DLT Adlandırma Standardı:** `<orijinal-topic>.DLT` (Örn: `task.events.DLT`). Spring Kafka'nın varsayılanıdır, değiştirilmez.
*   **Hata Bağlamı (Context):** `DeadLetterPublishingRecoverer`, orijinal topic/partition/offset bilgisini ve exception stack trace'ini mesaj header'larına (`kafka_dlt-exception-message`, `kafka_dlt-original-topic` vb.) otomatik ekler. DLT'deki bir mesajın "neden" ve "nereden" geldiği her zaman izlenebilirdir.
*   **Deserialization Koruması:** Consumer'larda `ErrorHandlingDeserializer` kullanılacaktır; aksi halde bozuk JSON, deserializer seviyesinde patlayıp error handler'a hiç ulaşmadan sonsuz döngü yaratır.

### 3.3. DLT Operasyonu (Mesajlar DLT'ye Düştükten Sonra Ne Olacak?)
DLT, mesajların ölmeye gittiği bir çöplük değil, **operasyonel bir kuyruk** olarak ele alınır:

1.  **Alarm:** `task.events.DLT` topic'indeki mesaj sayısı (veya consumer lag) bir Prometheus metriği olarak izlenir. **DLT'ye düşen her mesaj bir alarm üretir** — çünkü DLT'deki mesaj, "kaybolmuş bir iş" demektir (Örn: hesaplanmamış bir analitik metriği, iletilmemiş bir bildirim).
2.  **İnceleme:** Nöbetçi mühendis, header'lardaki exception bilgisiyle kök nedeni analiz eder.
3.  **Yeniden İşleme (Replay):** Hata koddan kaynaklanıyorsa (bug), düzeltme deploy edildikten sonra DLT'deki mesajları orijinal topic'e geri pompalayan basit bir "Replay" endpoint'i/aracı kullanılır. Bu aracın Faz 2 kapsamında basit bir versiyonu (belirli offset aralığını yeniden yayınlayan bir yönetici endpoint'i) yazılacaktır.
4.  **Idempotency Ön Şartı:** Replay yapılabilmesi için tüm consumer'ların **idempotent** olması zorunludur (aynı `eventId` iki kez işlenirse sonuç değişmemelidir). Consumer'lar işledikleri `eventId`'leri kontrol eder (Faz 3'teki webhook idempotency deseni ile aynı mekanizma).

## 4. Gerçek Zamanlı İletişim (WebSocket & STOMP)
React istemcilerine anlık güncellemeleri iletmek için Spring WebSocket ve STOMP protokolü kullanılacaktır.

*   **Bağlantı ve Güvenlik:** İstemci `/ws/connect` endpoint'ine bağlanırken JWT token'ını iletir. Spring Security bu token'ı doğrular.
*   **Kanal Aboneliği (Subscription):** React istemcisi, sadece aktif olarak görüntülediği projenin kanalına abone olur. Örn: `/topic/workspace.{workspaceId}.project.{projectId}`.
*   **Akış:** Kafka'dan `task.events` topic'ini dinleyen bir Spring Kafka Consumer, olayı alır ve `SimpMessagingTemplate` kullanarak ilgili WebSocket kanalına (topic) fırlatır. React arayüzü bu mesajı anında yakalayıp Redux/Zustand state'ini günceller.

## 5. WebSocket'in Yatay Ölçeklenmesi (Multi-Instance Fan-Out) — KRİTİK
Yukarıdaki akış **tek instance'ta** kusursuz çalışır, ancak birden fazla Spring Boot pod'u çalıştığında sessizce bozulur. Sorunun kökü şudur:

> Kafka consumer group semantiği gereği, `task.events` topic'indeki bir olay grup içinde **yalnızca tek bir pod** tarafından tüketilir. Ancak o olayı bekleyen kullanıcının WebSocket bağlantısı **başka bir pod'da** açık olabilir. Olayı tüketen pod, bağlantısı kendisinde olmayan kullanıcıya mesaj basamaz — kullanıcı güncellemeyi hiç almaz.

Bu problem, "1 pod'da çalışıyor, prod'da 3 pod'a çıkınca ekranlar rastgele güncellenmiyor" şeklinde, teşhisi çok zor bir bug olarak ortaya çıkar. Faz 2'de tasarım kararı **baştan** verilmektedir.

### 5.1. Değerlendirilen Çözümler

**Seçenek A — Broadcast Consumer (Pod başına benzersiz Consumer Group) → SEÇİLEN ÇÖZÜM**
Her pod, WebSocket fan-out amacıyla `task.events` topic'ini **kendine özgü, rastgele bir `groupId`** ile dinler (Örn: `ws-fanout-${random-uuid}`). Böylece Kafka, her olayı **tüm pod'lara** teslim eder (Broadcast / Pub-Sub davranışı). Her pod olayı alır, **yalnızca kendi üzerinde açık olan** WebSocket oturumlarına dağıtır; ilgili abonesi olmayan pod olayı sessizce yok sayar.

```java
@KafkaListener(
    topics = "task.events",
    // Pod başına benzersiz groupId -> her pod tüm olayları alır (Broadcast)
    groupId = "ws-fanout-#{T(java.util.UUID).randomUUID().toString()}",
    properties = {
        // Fan-out consumer'ı geçmişi umursamaz; pod açıldığı andan itibaren dinler
        "auto.offset.reset=latest",
        // Offset commit'e gerek yok; kaçan olay zaten anlamsızdır (istemci yeniden bağlanınca REST'ten güncel state'i çeker)
        "enable.auto.commit=false"
    }
)
public void fanOutToWebSocket(TaskEvent event) {
    String destination = "/topic/workspace.%s.project.%s"
        .formatted(event.workspaceId(), event.projectId());
    simpMessagingTemplate.convertAndSend(destination, event);
}
```

**Önemli ayrım:** Bu "broadcast" consumer **yalnızca WebSocket fan-out'u** içindir. Olayları kalıcı olarak işleyen consumer'lar (Faz 3 Analitik Worker, Notification Worker) normal **paylaşımlı** consumer group mantığıyla çalışmaya devam eder (her olay bir kez işlenir). Aynı topic, iki farklı tüketim semantiğiyle iki farklı amaca hizmet eder.

**Seçenek B — Harici STOMP Broker Relay (RabbitMQ):** Spring'in in-memory Simple Broker'ı yerine RabbitMQ (STOMP plugin) tam teşekküllü broker olarak kullanılır; tüm pod'lar mesajı relay'e basar, relay tüm pod'lardaki abonelere dağıtır.
**Seçenek C — Redis Pub/Sub:** Olayı tüketen pod, Redis kanalına yayınlar; tüm pod'lar Redis'i dinleyip kendi oturumlarına dağıtır.

### 5.2. Karar ve Gerekçe
**Faz 2'de Seçenek A** uygulanacaktır: Sıfır yeni altyapı bileşeni gerektirir (Kafka zaten var), Spring Kafka ile ~20 satır kodla çözülür ve fan-out semantiği açıkça görünürdür. Seçenek B/C, sisteme yeni bir stateful bileşen (RabbitMQ/Redis) ve onun operasyon yükünü ekler — Redis Faz 5'te zaten mimariye gireceği için, o noktada gerekirse Seçenek C'ye geçiş değerlendirilebilir.

### 5.3. Sticky Session ve İstemci Yeniden Bağlanma Kuralları
*   **Load Balancer:** WebSocket el sıkışması (upgrade) sırasında bağlantının tek bir pod'a sabitlenmesi doğaldır; ancak SockJS fallback (HTTP long-polling) kullanılacaksa Load Balancer'da **sticky session** (cookie bazlı afinite) zorunludur. Native WebSocket + STOMP kullanıldığı sürece sticky session gerekmez.
*   **Reconnect + State Senkronizasyonu:** Pod restart'ı veya ağ kopmasında istemci otomatik yeniden bağlanır (exponential backoff ile). Yeniden bağlanma sırasında kaçırılmış olabilecek olaylar için istemci, abone olduğu projenin güncel state'ini **REST API'den yeniden çeker** (Snapshot-then-Stream deseni). Bu kural sayesinde WebSocket katmanının "en fazla bir kez" (at-most-once) teslimatı yeterlidir; kesin tutarlılık her zaman REST + veritabanından gelir.

### ⚖️ Trade-off (Ödünleşim) Analizi
*   **WebSockets vs. Server-Sent Events (SSE):** React istemcisine anlık veri göndermek için iki ana teknoloji vardır. WebSocket çift yönlü (bi-directional) bir iletişim sağlar, ancak load balancer'lar (Yük Dengeleyiciler) üzerinde bağlantıyı açık tutmak maliyetlidir. SSE (Server-Sent Events) ise tek yönlüdür (sadece sunucudan istemciye) ve standart HTTP protokolü üzerinden çalışır. Bizim senaryomuzda istemci zaten değişiklikleri REST API (POST/PATCH) ile yapıyor, sadece sunucudan "güncelleme" dinlemesi gerekiyor. Bu nedenle SSE kullanmak altyapı maliyetini ve karmaşıklığını ciddi oranda düşürebilir. Ancak STOMP/WebSocket ekosistemi Spring'de çok daha olgundur. (Not: Bölüm 5'teki fan-out problemi ve çözümü, SSE tercih edilse bile **aynen geçerlidir** — hangi pod'un hangi istemciyi beslediği sorunu taşıma katmanından bağımsızdır.)
*   **Outbox Relay: Spring Scheduler vs. Debezium:** Outbox tablosunu okuyup Kafka'ya atmak için Spring içinde basit bir zamanlayıcı (Scheduler) yazmak başlangıçta çok kolaydır. Ancak sistem büyüdüğünde ve birden fazla Spring Boot instance'ı (pod) çalıştığında, aynı outbox kaydını iki sunucunun birden okumasını engellemek için veritabanı kilitleri (Pessimistic Locking) kullanmanız gerekir, bu da veritabanını yorar. İlerleyen aşamalarda bu işi PostgreSQL'in loglarını (WAL) doğrudan okuyan Debezium aracına devretmek mimari açıdan en doğrusudur.
*   **Broadcast Consumer'ın Maliyeti:** Seçenek A'nın gizli maliyeti, pod sayısı arttıkça her olayın ağda N kez taşınmasıdır (N = pod sayısı). 5-10 pod ve saniyede yüzlerce olay ölçeğinde bu ihmal edilebilirdir; ancak yüzlerce pod'a ölçeklenen bir gelecekte Redis Pub/Sub veya dedike bir realtime servisi (Örn: Centrifugo) daha ekonomik olur. Ayrıca rastgele `groupId`'ler Kafka broker'ında zamanla "hayalet" consumer group kaydı biriktirir; `offsets.retention.minutes` süresi sonunda otomatik temizlenirler, operasyonel bir sorun yaratmazlar.
*   **Retry Sayısı: Az mı Çok mu?** Retry sayısını yüksek tutmak (Örn: 10+ deneme) geçici hatalarda mesaj kaybını azaltır ama partition'ı uzun süre bloke eder ve gecikmeyi (Lag) büyütür. 4 deneme + exponential backoff (~15sn toplam), "geçici DB sıçraması" senaryolarını karşılamak için yeterlidir; daha uzun süren kesintiler zaten sistemik bir sorundur ve DLT + alarm ile insana eskale edilmesi daha doğrudur.

### 🚀 Mimari İpucu
**Kafka Partition Ordering (Sıralama Garantisi):**
Asenkron mimarilerdeki en sinsi hata, olayların sırasının karışmasıdır (Race Condition). Bir kullanıcı görevi önce "In Progress" sonra "Done" statüsüne çekerse, Kafka'ya iki olay gider. Eğer bu olaylar Kafka'da farklı partition'lara (bölümlere) düşerse, tüketici (consumer) "Done" olayını "In Progress" olayından daha önce okuyabilir ve arayüzde görev yanlış statüde kalır.

Bunu çözmek için Kafka'nın mesaj yönlendirme (routing) matematiğini kullanmalıyız. Kafka, mesajın hangi partition'a gideceğini şu formülle hesaplar:

$$P = \text{hash}(K) \pmod N$$

Burada:
*   **P:** Hedef partition,
*   **K:** Mesajın anahtarı (Key),
*   **N:** Toplam partition sayısıdır.

Eğer Kafka'ya mesaj gönderirken Key değerini boş (`null`) bırakırsanız, Kafka mesajları partition'lara rastgele (Round-Robin) dağıtır ve sıra bozulur. Çözüm olarak, Kafka'ya mesaj gönderirken Key olarak her zaman `task_id` (Görev ID'si) kullanmalısınız. Bu sayede aynı göreve ait tüm olaylar matematiksel olarak her zaman aynı partition'a ($P$) düşer ve Kafka o partition içindeki mesajları kesin bir sırayla (Strict Ordering) işleyeceğinin garantisini verir.

**DLT'ye Gönderirken de Sıralamayı Koruyun:**
Bir mesaj DLT'ye düştüğünde, aynı `task_id`'ye ait **sonraki** mesajlar ana topic'te işlenmeye devam eder — yani o görev için olay sırası fiilen bozulmuştur. Replay yapılırken bu durum göz önünde bulundurulmalı; idempotent consumer'lar olayın `timestamp`/`version` bilgisine bakarak "eski bir olayla güncel state'i ezme" (Out-of-Order Update) hatasına karşı korunmalıdır (Örn: `UPDATE ... WHERE updated_at < :eventTimestamp` koşulu).
