# ADR-0001: Read replica yönlendirmesi opt-in (`@ReadReplica`)

- **Durum:** Kabul edildi (Faz 3, Dilim 3.3 — 2026-09-20)
- **İlgili:** ADR-0007 (rapor servisi de replica'dan okur)

## Bağlam

CQRS gereği ağır analitik okumalar yazma veritabanını yormamalı, bir read replica'dan
okunmalıdır. Spring'de yaygın desen, `@Transactional(readOnly = true)` olan her şeyi replica'ya
göndermektir.

Ancak çekirdek API'nin okumaları (görev listesi, detay) **kendi yazdığını okuma** bekler:
kullanıcı görev oluşturur, liste hemen yenilenir. Asenkron streaming replica'da gecikme
(replication lag) varsa, `readOnly` her şeyi replica'ya gönderdiğinde yeni görev listede
görünmez. Bu hata exception üretmez, sessizdir ve yalnızca yük altında ortaya çıkar.

## Karar

Bir sorgu replica'ya **yalnızca iki koşul birlikte** sağlandığında gider:

1. Metot veya sınıf `@ReadReplica` ile işaretlidir, **ve**
2. aktif transaction `readOnly`'dir.

Biri eksikse yazma havuzu kullanılır. Transaction dışı çağrılar (başlangıç, health check)
daima yazma havuzuna gider.

Uygulama:

- `ReadReplicaAspect`, transaction advisor'ından **önce** (`TransactionManagementConfig.ORDER - 1`)
  bir ThreadLocal bayrak kurar, sonra geri alır; iç içe çağrılarda dış değer korunur.
- `AbstractRoutingDataSource` (`ReadWriteRouter`) bayrağa ve `readOnly`'ye bakar.
- `LazyConnectionDataSourceProxy`: bağlantı transaction başlarken değil **ilk SQL'de** alınır;
  böylece karar anında `readOnly` kesinleşmiştir ve `TenancyGuardAspect`'in `set_config`
  sorgusu da doğru havuza gider.
- `app.datasource.read.url` boşsa **ayrı havuz açılmaz**; yönlendirici her iki anahtar için aynı
  yazma havuzunu kullanır. Bugünkü durum budur (gerçek replica Faz 4/5 HA sprintine ertelendi).
- Havuzlar bilerek Spring `DataSource` **bean'i değildir**; kapanışta `PooledRoutingDataSource`
  kapatır.
- `migrate` profilinde devre dışı: Flyway Boot'un standart tek havuzunu kullanır.

Bugün işaretli olanlar: `AnalyticsQueryService`, `PeriodReportService`.

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| `readOnly` ⇒ replica (Spring'in yaygın deseni) | Read-your-writes'ı sessizce bozar; çekirdek API okumalarının tamamı riske girer. |
| Replica'yı hiç kurmamak, analitiği ana DB'den okumak | Faz 3'ün CQRS öğrenme hedefini karşılamaz; Faz 4'te servis ayrılırken zaten gerekecek. |
| Ayrı `@Bean DataSource` olarak read havuzu | Actuator `db` health'i onu readiness'e katar; replica arızası **tüm** pod'ları trafikten çıkarır. |
| İşareti repository seviyesine koymak | Transaction servis katmanında açılıyor (ADR dışı kural, bkz. `TenancyGuardAspect` tuzağı); karar aynı seviyede olmalı. |

## Sonuçlar

**Olumlu**
- Eski veri görebilecek sorgular kodda **açıkça** işaretlidir; yeni bir sorgu varsayılan olarak
  güvenli (yazma) tarafa düşer.
- Replica tanımlı değilken davranış birebir aynıdır; özellik bayraksız devreye alınabilir.

**Olumsuz / kabul edilen bedel**
- Read havuzu health'e dahil değil: replica çökerse analitik uçları 500 döner ama pod "sağlıklı"
  görünür. Bu yüzden replica için **ayrı bir metrik/alarm** gerekir (Faz 5 observability).
- Replica gecikmesinde yeni oluşturulmuş bir proje analitikte kısa süre 404 verebilir
  (`AnalyticsQueryService` proje varlığını da replica'dan kontrol ediyor). Boş analitik zaten
  anlamsız olduğu için kabul edildi.
- ThreadLocal tabanlıdır: `@Async`, reaktif akış veya elle açılan thread'e bayrak **taşınmaz**;
  o kod yazma havuzuna düşer (güvenli taraf).

## Faz 4 notu

Analytics ayrı servise çıktığında o servisin **tüm** okumaları replica'dan yapılabilir; işaret
o serviste gereksizleşir. Çekirdek API'de kalan tek kullanıcı rapor olursa işaret yaşamaya
devam eder. Karar Faz 4 servis ayrım ADR'sinde yeniden değerlendirilmeli.

## Referanslar

- `src/main/java/com/app/tracker/core/datasource/RoutingDataSourceConfig.java`
- `src/main/java/com/app/tracker/core/datasource/ReadReplicaAspect.java`
- `src/main/java/com/app/tracker/core/datasource/ReadReplicaContext.java`
