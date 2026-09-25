# ADR-0013: Monte Carlo tahmin modeli (olasılıksal bitiş tarihi)

- **Durum:** Kabul edildi (Ürünleştirme Dalga 2.2, 2026-09-25)
- **İlgili:** ADR-0001 (read replica opt-in — bu tahmin BİLEREK read replica'ya bağlanmadı, bkz.
  "Değerlendirilen seçenekler")

## Bağlam

Rakip analizi (Linear/Jira) "ne zaman biter?" sorusuna basit bir ortalama yerine olasılık dağılımı
(p50/p85/p95) ile cevap vermenin farklılaştırıcı olduğunu işaretlemişti. Cevaplanması gerekenler:
hangi yöntem (deterministik ortalama mı, simülasyon mu), veri azken ne olur, hesaplama maliyeti
her istekte tekrar mı ödenir, proje temelli mi yoksa hedef temelli mi kapsam.

## Karar

1. **Yöntem: bootstrap resampling Monte Carlo**, ortalama×gün gibi deterministik bir formül değil.
   Gerçek günlük throughput dağılımı (sıfır-tamamlanma günleri dahil) yerine konarak yeniden
   örneklenip binlerce (10.000) "sanal gelecek" simüle edilir — bu, throughput'un gün gün
   DEĞİŞKENLİĞİNİ (bazı günler 0, bazı günler 5) modele taşır; sabit bir ortalama bunu kaybeder.
2. **En az 10 tamamlanmış iş şartı** (`MonteCarloForecaster.MIN_SAMPLES`), pencerenin GÜN SAYISI
   değil DEĞERLERİN TOPLAMI üzerinden ölçülür. 84 günlük (12 hafta — Throughput'un varsayılan
   penceresiyle aynı) gözlem penceresi HER ZAMAN sabittir; yeni bir projede bu pencere neredeyse
   tamamen sıfırlarla dolu olabilir, o yüzden "yeterli veri" kriteri gün sayısı değil GERÇEK iş
   miktarı olmalı. Şart sağlanmazsa `{"available": false}` döner — hiç tahmin göstermemek, yanlış
   güvenli bir tahmin göstermekten iyidir.
3. **Kapsam BİLEREK dar: yalnız proje (backlog) ve sprint.** Planın üçüncü kullanım yeri olan
   "hedef (goal) dönem sonuna ulaşma olasılığı" bu dilimde YAPILMADI — `Goal.projectId` null
   olabilir (workspace geneli hedef), bu durumda tek bir projenin günlük throughput'u temsilci
   değildir (workspace'teki tüm projelerin birleşik throughput'u gerekir, ayrı bir agregasyon
   ister). Küçük, dar bir kapsamla teslim etmek tercih edildi; goal entegrasyonu ayrı bir dilim.
4. **Yalnız görev SAYISI, story point DEĞİL.** `COMPLETED_POINTS` hedefi için puan bazlı tahmin de
   planlanmıştı ama `tasks.custom_fields->>'story_point'` ile `task_analytics.done_at`'i günlük
   birleştiren bir sorgu ek karmaşıklık ekliyordu; goal entegrasyonuyla BİRLİKTE ertelendi.
5. **Redis Cache-Aside, projedeki İLK gerçek Redis kullanımı.** Anahtar `forecast:{ws}:{projectId}:
   project` / `forecast:{ws}:{projectId}:sprint:{sprintId}` — plandaki `{date}` segmenti BİLEREK
   düşürüldü (TTL 1 saat zaten günlük tazeliği sağlıyor, projeId'yi anahtara gömmek tek bir pattern
   silmeyle hem proje hem o projenin TÜM sprint tahminlerini temizlemeyi mümkün kılıyor). `@Cacheable`
   yerine doğrudan `StringRedisTemplate` kullanıldı çünkü eviction bir ANAHTAR DESENİ silmek zorunda
   (`KEYS forecast:{ws}:{projectId}:*` + `DEL`) — Spring'in cache soyutlaması bunu desteklemiyor.
6. **Eviction, `CycleTimeConsumer`'a eklendi** (Done'a ÖZEL değil, TÜM durum değişikliklerine ve
   silmeye): kalan iş sayısı da tahmine giren bir girdi olduğu için herhangi bir durum geçişi
   sonucu değiştirebilir. TTL zaten kısa (1 saat) olduğundan bu "aşırı temizleme" ihmal edilebilir.
7. **Tohumlanabilir RNG** (`seed = Objects.hash(id, today)`): aynı gün içinde aynı proje/sprint için
   deterministik sonuç (test edilebilir), gün değişince yeniden örneklenir.
8. **MAX_DAYS güvenlik supabı (3650 gün):** throughput çok düşük/sıfıra yakınsa simülasyon
   sonsuz döngüye girmez, "çok uzak bir tarih" olarak üst sınırda kesilir.

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| Deterministik `kalan / ortalama_throughput` | Throughput'un gün gün değişkenliğini (varyansı) yok sayar; "p85" gibi bir güven aralığı üretemez. |
| `@Cacheable`/`@CacheEvict` (Spring Cache soyutlaması) | Bir projenin TÜM sprint tahminlerini tek seferde temizlemek için desen eşleme gerekiyor, soyutlama bunu desteklemiyor. |
| Read replica'dan okumak (`@ReadReplica`, ADR-0001 ile tutarlı olurdu) | Sonuç zaten Redis'te önbelleklendiği için (TTL 1 saat) replica gecikmesinin getirisi yok; üstelik `remainingItems` "kendi yazdığını oku" beklentisi taşıyabilir (bir görevi az önce sprint'e ekleyen kullanıcı hemen güncel sayıyı görmeli). |
| Anahtara `{date}` segmenti eklemek (plandaki bicim) | TTL + eviction zaten günlük tazeliği sağlıyor; ekstra segment yalnızca anahtar sayısını gün başına çoğaltır, kazanç yok. |
| Goal (hedef) tahminini de bu dilimde yapmak | `projectId` null olabilen hedefler için throughput kaynağı belirsiz; kapsamı dar tutup ayrı bir dilime bırakmak daha temiz. |

## Sonuçlar

**Olumlu**
- p50/p85/p95 gibi güven aralıkları gerçek throughput dağılımını yansıtır.
- Cache-aside sayesinde aynı proje/sprint için tekrar tekrar 10.000 simülasyon koşulmaz.
- Aynı `MonteCarloForecaster` hem proje hem sprint kapsamında kullanılıyor — iki ayrı tahmin
  motoru yok.

**Olumsuz / kabul edilen bedel**
- Goal (hedef) bazlı tahmin bu dilimde YOK — plan'ın üçüncü kullanım yeri açık kaldı.
- Story point bazlı tahmin YOK, yalnız görev sayısı.
- `KEYS` komutu büyük Redis'lerde bloklayıcı olabilir; bu ölçekte (proje başına birkaç anahtar)
  kabul edilebilir, cache stampede koruması planın kendisi gibi Faz 5'e bırakıldı.
- Soft-delete edilmiş bir görevin `task_analytics` satırı silinmiyor olsaydı (bkz. `CycleTimeConsumer`
  `TASK_DELETED` işleyişi zaten satırı temizliyor) throughput'u yanlış şişirebilirdi — bu ADR
  kapsamında ayrıca ele alınmadı, mevcut silme akışına güvenildi.

## Faz 4 notu

`ForecastService` `ProjectMetricsRepository`'ye (analitik) bağımlı; analitik ayrı bir servise
çıkarsa tahmin de onunla birlikte taşınmalı. Redis Cache-Aside deseni ileride goal/story-point
tahminleri eklenirken aynen genişletilebilir (yeni anahtar segmenti, aynı `evictProject` deseni).

## Referanslar

- `src/main/java/com/app/tracker/forecast/MonteCarloForecaster.java`
- `src/main/java/com/app/tracker/forecast/ForecastCacheService.java`
- `src/main/java/com/app/tracker/forecast/ForecastService.java`
- `src/main/java/com/app/tracker/analytics/consumer/CycleTimeConsumer.java` (eviction)
- `src/test/java/com/app/tracker/forecast/MonteCarloForecasterTest.java`
- `src/test/java/com/app/tracker/forecast/ForecastServiceIntegrationTest.java`
