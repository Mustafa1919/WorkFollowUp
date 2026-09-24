# ADR-0007: Rapor ve hedef modeli

- **Durum:** Kabul edildi (V21 — 2026-09-24)
- **İlgili:** ADR-0001 (replica'dan okuma), ADR-0002 (sprint özetinin tanımı)

## Bağlam

Yıllık ve çeyreklik rapor istendi: geçmişe dönük "ne yapıldı" ve ileriye dönük "hedefe ne kadar
yaklaşıldı". Açık sorular: dönem nasıl tanımlanır, sayılar nereden gelir, hedef nasıl modellenir,
kullanıcı raporu daralttığında hedef ne gösterir?

## Karar

### Dönem: takvim dönemi, kayan pencere değil

- Yıl veya yıl + Q1-Q4. "Son 3 ay" gibi kayan pencere **yok**.
- Sınırlar **iş saat diliminde** (`app.business-time-zone`, varsayılan `Europe/Istanbul`):
  `2026-Q3` İstanbul'da 1 Temmuz 00:00'da başlar, UTC'de değil.

### Kaynak: mevcut read model'ler

- Toplamlar, aylık ve proje kırılımı `task_analytics`, sprint özeti `sprint_analytics`'ten.
  `task_events` yeniden taranmaz.
- Sonuç: raporun "tamamlandı" tanımı Throughput/Cycle Time ile **aynı** (son Done geçişi,
  reopen sayılmaz); rapor ile proje analitiği çelişemez.
- İkinci savunma: `JOIN tasks ... deleted_at IS NULL` (DLT replay'i silinmiş görevin analitik
  satırını geri getirebilir).

### Tek endpoint, tek kesit

`GET /api/v1/reports/period` sayfanın tamamını (KPI, aylık, proje tablosu, hedefler) tek istekte
döner; servis `@ReadReplica` + `readOnly`. Parçalar farklı anlarda okunup kendi içinde çelişen
bir rapor üretemez.

### Süzgeç: URL durumu, kalıcı tablo değil

`projectIds` query parametresi (üst sınır 100) + frontend'de URL (`?year=&quarter=&projects=`).
Rapor paylaşılabilir kalır, kalıcı "rapor tanımı" şeması oluşmaz.

### Hedef modeli (V21 `goals`)

- Tek seviye; OKR Objective/Key Result hiyerarşisi, ağırlık, check-in geçmişi **yok**.
- `metric_type`: `COMPLETED_TASKS`, `COMPLETED_POINTS` (read model'den otomatik) veya `CUSTOM`
  (elle `manual_value`; DB CHECK ile yalnız CUSTOM'da dolu olabilir).
- **Dönem ve metrik tipi oluşturulduktan sonra değiştirilemez** — hedefin kimliğidir.
  Değiştirilebilir olan: başlık, hedef değer, kapsam.
- Yıllık (`period_quarter IS NULL`) ve çeyreklik hedefler ayrı listelerdir; repository'de iki
  ayrı sorgu (JPQL `= :quarter` null ile hiçbir satırı eşlemez, sessizce boş liste dönerdi).
- **Hedef ilerlemesi proje süzgecinden etkilenmez**; yanıttaki `projectFilterApplied` bayrağı
  arayüze bunu bildirir. Süzgeç varken hedef sayıları süzgeçsiz olarak ayrıca sorgulanır (hedef
  sayısından bağımsız en fazla 2 ek sorgu, N+1 yok).
- Yetki: rapor okuma tüm üyeler; hedef yönetimi ve elle ilerleme ADMIN/MANAGER.

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| Kayan pencere ("son 90 gün") | Dönemin kendisi her gün kayar; karşılaştırılamaz. |
| UTC dönem sınırları | Türkiye'deki kullanıcı için 31 Aralık 21:00 sonrası tamamlanan iş yanlış yıla düşer. |
| `task_events`'ten yeniden hesaplamak | Analitikle farklı tanım riski; ağır tarama. |
| Parça parça endpoint'ler | Parçalar farklı kesitlerde okunur; toplam ≠ kırılım toplamı olabilir. |
| Tam OKR modeli | Ürün ihtiyacı yok; şema ve arayüz karmaşıklığı ×3. Tek seviye model, ileride `parent_goal_id` ile genişletilebilir. |
| Hedefin süzgece göre daralması | Raporu daraltmak hedefi olduğundan "yakın" gösterir; hedef mutlak taahhüttür. |
| Dönem/metrik düzenlenebilir | Geçmiş ilerleme sessizce başka bir hedefe ait olur. |

## Sonuçlar

**Olumlu**
- Dönem sınırları sabit; rapor, analitik sayfasıyla tutarlı.

**Olumsuz / kabul edilen bedel**
- **Kapanmış dönem tam olarak donmuş değil** (dönem sabit, sayılar değil):
  - Story point `tasks.custom_fields`'in **güncel** değerinden okunur, tamamlanma anındaki
    değerden değil; geçmiş bir görevin puanı değişirse geçmiş rapor da değişir.
  - `task_analytics.done_at` **son** Done geçişidir; geçen çeyrekte bitip bu çeyrekte yeniden
    açılan görev geçen çeyrekten düşer.
  - Tam donmuş rapor için tamamlanma anındaki puanın read model'e yazılması (veya dönem sonu
    snapshot tablosu) gerekir — ayrı iş, bugün bilerek yapılmadı.
- **Tenant bazlı saat dilimi yok:** tek global iş saat dilimi. Farklı saat dilimindeki bir
  müşteri dönem sınırlarını birkaç saat kaymış görür. Çözüm `workspaces.time_zone` kolonu —
  ayrı iş.
- Rapor read model'e bağlı: consumer geride kalırsa rapor da geride kalır; replica gecikmesi
  eklenir.
- `CUSTOM` hedefte yalnız son değer tutulur; ilerlemenin zaman içindeki seyri yok.
- Export (CSV/PDF) bilerek yok.

## Referanslar

- `src/main/java/com/app/tracker/report/model/ReportPeriod.java`, `ReportPeriodTest.java`
- `src/main/java/com/app/tracker/report/repository/PeriodReportRepository.java`
- `src/main/java/com/app/tracker/report/service/PeriodReportService.java`
- `src/main/java/com/app/tracker/report/dto/PeriodReportResponse.java`
- `src/main/java/com/app/tracker/goal/` , `src/main/resources/db/migration/V21__goals.sql`
