# Faz 3: Analitik Motoru ve Dış Entegrasyonlar Tasarımı (v1.1)

> **v1.1 Değişiklikleri:** Velocity metriği, `DATABASE_SCHEMA.md` v1.1'de eklenen `sprints` tablosuna dayandırılarak doğru tanımına kavuşturuldu (Bölüm 1.1). Sprint bazlı çalışmayan (Kanban) takımlar için alternatif "Throughput" metriği tanımlandı.

## 1. Analitik ve Raporlama Motoru (CQRS Yaklaşımı)
Sistemin okuma (Read) ve yazma (Write) modellerini birbirinden ayırmak için basitleştirilmiş bir CQRS (Command Query Responsibility Segregation) deseni uygulanacaktır.

*   **Read Replica (Okuma Kopyası):** PostgreSQL veritabanımızın anlık bir "Read Replica"sı oluşturulur. Tüm analitik GET istekleri (Raporlar, Dashboard grafikleri) ana veritabanına değil, bu kopyaya yönlendirilir.
*   **Materialized Views (Fizikselleştirilmiş Görünümler):** Ağır hesaplamalar (Örn: Takımların sprint bazlı Velocity puanları) her istekte baştan hesaplanmaz. PostgreSQL üzerinde `CONCURRENTLY` parametresi ile çalışan ve her gece (veya saat başı) yenilenen Materialized View'lar oluşturulur.
*   **Metrik Hesaplama (Cycle Time):** Bir görevin Cycle Time'ı (Döngü Süresi), görevin "In Progress" statüsüne geçmesi ile "Done" statüsüne geçmesi arasındaki süredir.
    
    $$CT = T_{done} - T_{in\_progress}$$
    
    Bu hesaplama Kafka'dan `task.events` dinleyen bir Analitik Worker tarafından arka planda yapılarak `task_analytics` adlı özel bir tabloya yazılır.

### 1.0. `task_events` Partitioning Stratejisi
`task_events`, append-only ve sürekli büyüyen bir tablodur; `DATABASE_SCHEMA.md`'de `created_at` "Partition Key" olarak işaretlenmiştir. Faz 3'te bu strateji somutlaştırılır:

*   **Yöntem:** PostgreSQL **Declarative Range Partitioning**, `created_at` üzerinden **aylık** partition'lar (`task_events_2026_07`, `task_events_2026_08`...). Aylık granülarite, hem partition sayısını yönetilebilir tutar hem de analitik sorguların tipik zaman aralığıyla (son sprint, son çeyrek) örtüşür.
*   **Otomasyon:** Partition'ların önceden oluşturulması ve eskilerin ayrılması **`pg_partman`** eklentisiyle otomatikleştirilir (gelecek 3 ay için partition hazır tutulur). Manuel partition oluşturma "ayın 1'inde INSERT patlaması" riskini taşır ve yasaktır.
*   **Sorgu Faydası (Partition Pruning):** `WHERE created_at >= '2026-06-01'` içeren analitik sorgular yalnızca ilgili partition'ları tarar; tablo 500M satıra ulaştığında bile Cycle Time sorguları yalnızca birkaç aylık veriyi okur.
*   **Arşivleme:** 12 aydan eski partition'lar `DETACH PARTITION` ile ayrılır, Parquet formatında S3'e aktarılır ve tablodan `DROP` edilir (Şema dokümanındaki Event-Driven Archiving yaklaşımıyla uyumlu). `DELETE` asla kullanılmaz — `DROP PARTITION` milisaniyeler sürer ve vacuum/bloat yükü üretmez.
*   **İndeks Notu:** Partitioned tabloda unique constraint'ler partition key'i içermek zorundadır; bu nedenle `task_events.id` PK'sı `(id, created_at)` bileşik anahtarına dönüşür. Bu tablo yalnızca `task_id + created_at` üzerinden sorgulandığı için pratik bir kayıp yoktur.

### 1.1. Velocity Metriği: Sprint Bazlı Tanım — GÜNCELLENDİ
Velocity, tanımı gereği **sprint bazlı** bir metriktir ve "haftalık" gibi takvim bazlı bir pencereyle hesaplanamaz. Doğru tanım şudur:

$$V_{sprint} = \sum_{t \in D_{sprint}} SP_t$$

Burada $D_{sprint}$, sprint kapandığı anda **hem sprint'e dahil hem de "Done" statüsünde olan** görevler kümesini, $SP_t$ ise görevin Story Point değerini (`custom_fields ->> 'story_point'`) ifade eder. Takımın "ortalama velocity"si, son 3-5 kapanan sprint'in hareketli ortalamasıdır (tek sprint yanıltıcıdır).

**Hesaplama Kuralları (Analitik Worker):**
1.  **Tetikleyici:** Hesaplama, `sprint.events` topic'inden gelen `SPRINT_COMPLETED` olayı ile tetiklenir (Sprint kapatma işlemi Core API'de bir olay üretir). Zamanlanmış (cron) bir yeniden hesaplama job'ı da güvence olarak gece koşar.
2.  **Kaynak Veri:** Görevin sprint üyeliği, `tasks.sprint_id`'nin anlık değerinden değil, `task_events` tablosundaki `sprint_changed` tarihçesinden türetilir. Böylece sprint kapandıktan **sonra** yapılan taşımalar (görevi bir sonraki sprint'e aktarmak) geçmiş sprint'in velocity'sini değiştiremez — metrikler **değişmez (immutable)** kalır.
3.  **Yarım Kalan İşler:** Sprint kapanırken "Done" olmayan görevlerin puanı o sprint'in velocity'sine **dahil edilmez** (Scrum standardı). Bu görevler ayrıca "Spillover Rate" (Devir Oranı) metriği olarak raporlanır: $Spillover = \frac{SP_{tamamlanmayan}}{SP_{taahhüt}}$
4.  **Sonuç Tablosu:** Hesaplanan değerler `sprint_analytics` tablosuna yazılır (`sprint_id`, `completed_points`, `committed_points`, `spillover_rate`, `calculated_at`).

**Kanban Takımları İçin: Throughput**
Sprint kullanmayan takımlar için Velocity yerine **Weekly Throughput** metriği sunulur: Haftalık tamamlanan görev sayısı (veya toplam story point). Bu metrik sprint tablosuna ihtiyaç duymaz, doğrudan `task_events`'teki `status_changed -> Done` olaylarından hesaplanır. Dashboard, projenin çalışma tarzına göre (sprint'li / sprint'siz) doğru metriği otomatik gösterir.

## 2. Webhook Ingestion API (Dış Entegrasyonlar)
GitHub, GitLab veya Jenkins'ten gelen webhook'ları karşılamak için ana API'den bağımsız, son derece hafif bir "Ingestion" katmanı tasarlanmıştır.

*   **Endpoint:** `POST /api/v1/webhooks/github`
*   **Akış:**
    1. İstek gelir gelmez, GitHub'ın gönderdiği HMAC imzası (Secret Token) doğrulanır.
    2. İstek gövdesi (Payload) hiçbir işleme sokulmadan (Parse edilmeden) doğrudan Kafka'nın `webhooks.incoming` topic'ine fırlatılır.
    3. Karşı tarafa anında `202 Accepted` HTTP kodu dönülür (İşlem süresi < 50ms).
*   **İşleme (Processing):** Arka planda çalışan bir "Integration Worker", Kafka'dan bu webhook'u kendi hızında okur, ilgili görevi (Örn: Commit mesajındaki `ENG-101` tag'ini) bulur ve durumunu günceller.

## 3. Outbound Notifications (Slack/Teams Bildirimleri)
Sistemimizde olan biteni dışarıya aktarmak için ayrı bir servis/modül devreye girer.

*   Kafka'daki `task.events` topic'ini dinleyen bir "Notification Worker" oluşturulur.
*   Eğer kullanıcının/takımın Slack entegrasyonu aktifse, olay formatlanıp Slack API'sine gönderilir. Dış API çökmelerine karşı Resilience4j (Circuit Breaker) kullanılır.

### ⚖️ Trade-off (Ödünleşim) Analizi
*   **PostgreSQL Read Replica vs. ClickHouse / Elasticsearch:** Analitik veriler için PostgreSQL'in Read Replica'sını ve Materialized View'ları kullanmak Faz 3 başlangıcında en mantıklı yoldur; çünkü yeni bir teknoloji öğrenme ve bakım maliyeti (DevOps overhead) yaratmaz. Ancak veriniz 100 Milyon satırı aştığında PostgreSQL analitik sorgularda zorlanacaktır. Bu noktada (belki Faz 4'te) analitik verileri Kafka üzerinden sütun odaklı (Columnar) bir veritabanı olan ClickHouse'a akıtmak gerekecektir.
*   **Senkron Webhook İşleme vs. Asenkron (Kafka) İşleme:** Webhook'ları doğrudan REST API içinde senkron işleyip veritabanına yazmak kodlama açısından çok daha kolaydır. Ancak GitHub'ın webhook timeout süresi genellikle 10 saniyedir. Eğer veritabanınız o an yoğunsa ve yanıt veremezseniz, GitHub isteği başarısız sayar ve tekrar gönderir. Asenkron (Kafka) yaklaşım sistemin dayanıklılığını (Resiliency) artırır ama mimariyi karmaşıklaştırır. Kurumsal bir ürün için asenkron şarttır.
*   **Olay Tarihçesinden Hesaplama vs. Anlık State'ten Hesaplama:** Velocity'yi `tasks` tablosunun anlık durumundan (`WHERE sprint_id = ? AND status = 'Done'`) hesaplamak tek satırlık bir sorgudur ve çok caziptir. Ancak anlık state **değişkendir**: kapanmış sprint'teki bir görev sonradan taşınır veya statüsü değişirse, geçmiş raporlar sessizce değişir ve yönetime sunulan rakamlar tutarsızlaşır. `task_events` tarihçesinden hesaplamak daha fazla kod ve işlem gücü ister, ancak metriklerin **denetlenebilir ve değişmez** olmasını garanti eder. Analitik bir üründe rapor tutarlılığı, hesaplama basitliğinden önce gelir.

### 🚀 Mimari İpucu
**Idempotency (Eş Etkililik) ve Tekrar Eden Webhook'lar:**
Dağıtık sistemlerin altın kuralı şudur: Ağ güvenilmezdir ve aynı mesaj size birden çok kez gelebilir (At-least-once delivery). GitHub bir webhook gönderdiğinde, ağda bir dalgalanma olur ve sizin 202 Accepted yanıtınızı alamazsa, aynı webhook'u 5 dakika sonra tekrar gönderir. Eğer sisteminiz buna hazırlıklı değilse, aynı commit için göreve iki kez yorum atarsınız veya metrikleri iki kez hesaplarsınız.

Bunu önlemek için API'nizi Idempotent (Eş Etkili) tasarlamalısınız. Matematiksel olarak bir fonksiyonun idempotent olması, onu bir kez çalıştırmakla $N$ kez çalıştırmanın aynı sonucu vermesidir:

$$f(f(x)) = f(x)$$

**Nasıl Uygulanır?**
GitHub webhook'larının header'ında benzersiz bir ID gelir (Örn: `X-GitHub-Delivery`). Bu ID'yi işleme başlamadan önce Redis'e veya veritabanındaki bir `processed_webhooks` tablosuna kaydedin. Eğer aynı ID tekrar gelirse, sistemi yormadan doğrudan 200 OK dönüp isteği çöpe atın. Bu basit "Idempotency Key" kontrolü, sisteminizi veri tekrarlarından (Data Duplication) ve mantıksal hatalardan kurtaracaktır.

**Not:** Bu idempotency mekanizması, Faz 2'de tanımlanan DLT Replay senaryosundaki idempotent consumer gereksinimiyle **aynı altyapıyı** paylaşır (`core` paketindeki ortak "Processed Event Store"). İki yerde ayrı ayrı yazılmamalıdır.
