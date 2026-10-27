# 05 — Güvenlik Test Raporu Şablonu

Her koşum için bu şablon kopyalanır: `docs/security/reports/YYYY-MM-DD-<ortam>-rapor.md`.
Düzeltilmemiş bulgular içeren rapor önce `docs/security/local/` altında (git dışı) tutulur; ilgili düzeltmeler
yapılınca ayrıntılar gözden geçirilip `reports/` altına taşınır.

## Önem Derecelendirmesi

Her bulguya CVSS v3.1 taban skoru (calculator: first.org/cvss/calculator/3.1) ve aşağıdaki sınıf verilir.
Çok kiracılı bir üründe **kiracılar arası veri erişimi, CVSS skorundan bağımsız olarak en az Yüksek** sayılır.

| Sınıf | CVSS | Tanım | Aksiyon süresi |
|---|---|---|---|
| Kritik | 9.0–10.0 | Kimlik doğrulamasız veri/sistem erişimi, kiracılar arası toplu sızıntı | Yayını durdur, hemen düzelt |
| Yüksek | 7.0–8.9 | Kimlikli kullanıcı ile kiracılar arası erişim, hesap ele geçirme | Yayından önce |
| Orta | 4.0–6.9 | Sınırlı etki, ek koşul gerektiren zafiyet, eksik sertleştirme | Yayından önce tercihen, en geç 30 gün |
| Düşük | 0.1–3.9 | Bilgi ifşası, derinlemesine savunma eksiği | Planlı |
| Bilgi | — | Öneri, en iyi uygulama farkı | İsteğe bağlı |

---

# Güvenlik Test Raporu — <YYYY-MM-DD> — <ortam>

## 1. Künye

| Alan | Değer |
|---|---|
| Test tarihi / süresi | |
| Test eden | |
| Ortam | yerel docker / staging / prod sunucu |
| Uygulama sürümü | commit SHA: `` (backend + frontend) |
| DB migration sürümü | V__ |
| Kapsam | README.md "Kapsam" tablosu / farklıysa belirt |
| Kapsam dışı bırakılanlar | |
| Kullanılan araçlar ve sürümleri | ZAP __, testssl __, trivy __, OSV-Scanner __, CodeQL __ |

## 2. Yönetici Özeti

- Genel değerlendirme (2–4 cümle):
- Bulgu sayıları: Kritik _ · Yüksek _ · Orta _ · Düşük _ · Bilgi _
- Yayın kararı: **UYGUN / KOŞULLU UYGUN (şu bulgular kapanınca) / UYGUN DEĞİL**
- En önemli 3 aksiyon:
  1.
  2.
  3.

## 3. Kontrol Sonuçları (02 kataloğu)

| ID | Sonuç | Kanıt | Bulgu no | Not |
|---|---|---|---|---|
| AUTH-01 | GEÇTİ / KALDI / UYGULANAMAZ / ATLANDI | `$OUT/AUTH-01.txt` | | |
| ... | | | | |

Özet: Toplam __ kontrol · Geçti __ · Kaldı __ · Uygulanamaz __ · Atlandı __ (atlananlar için gerekçe zorunlu)

## 4. Hipotez Çözümü (01 §5)

| Hipotez | Durum | İlgili kontrol | Bulgu no |
|---|---|---|---|
| H-01 | DOĞRULANDI / ÇÜRÜTÜLDÜ / KISMEN | BL-05 | F-__ |
| ... | | | |

## 5. Bulgular

Her bulgu için bu blok tekrarlanır. Bulgular önem sırasına göre dizilir.

### F-01 — <kısa başlık>

| Alan | Değer |
|---|---|
| Önem | Kritik / Yüksek / Orta / Düşük / Bilgi |
| CVSS v3.1 | skor + vektör (`AV:N/AC:L/...`) |
| Kontrol ID | |
| Referans | ASVS __ / OWASP API Top 10 __ / CWE-__ |
| Etkilenen bileşen | dosya:satır, uç nokta, yapılandırma |
| Durum | Açık / Düzeltiliyor / Düzeltildi / Kabul edildi (risk kabulü) |

**Açıklama:** Kontrolün neden başarısız olduğu, kök neden.

**Etki:** Hangi aktör (01 §2), hangi varlığa (01 §1), ne yapabilir.

**Kanıt:** Kanıt dosyası/ekran görüntüsü referansı (token/parola maskelenmiş).

**Öneri:** Düzeltme yaklaşımı; varsa alternatifler ve trade-off.

**Regresyon testi:** Düzeltmeyle birlikte eklenecek otomatik test (sınıf/metot adı).

**Yeniden test:** Tarih, commit SHA, sonuç.

## 6. Risk Kabulleri

| Bulgu | Gerekçe | Telafi edici kontrol | Kabul eden | Gözden geçirme tarihi |
|---|---|---|---|---|
| | | | | |

## 7. Sonraki Adımlar

- [ ] Kritik/Yüksek bulguların düzeltilmesi (dilim planı)
- [ ] Regresyon testlerinin CI'a eklenmesi (02 "Otomasyona Taşıma Planı")
- [ ] Bulgu kapanışı sonrası yeniden test
- [ ] 04 kontrol listesinin tamamlanması
- [ ] Sonraki periyodik test tarihi:

## Ek A — Araç Çıktıları

ZAP HTML/JSON raporu, testssl çıktısı, trivy/OSV-Scanner çıktıları, CodeQL SARIF: dosya adlarıyla listelenir
(ham çıktılar `docs/security/local/` altında, git dışı).
