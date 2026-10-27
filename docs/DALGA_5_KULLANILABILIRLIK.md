# Dalga 5: Sunucu Denemesi Sonrası Kullanılabilirlik ve Yetki Düzeltmeleri (v1.0)

- **Durum:** Planlandı (2026-10-26) — kod yazılmadı
- **Kaynak:** Uygulamanın sunucuya kurulup elle denenmesinde çıkan bulgular (kullanıcı, 2026-10-26)
  ve bunların god mode tartışmasında alınan kararları.
- **İlgili ADR'ler:** [ADR-0019](adr/0019-izin-tabanli-yetki-modeli.md) (izin modeli),
  [ADR-0020](adr/0020-bagimlilik-tamamlanma-kurali.md) (bağımlılık kuralı),
  [ADR-0021](adr/0021-alt-gorev-olusturma-ve-puan-kurali.md) (alt görev ve puan)
- **Yol haritasındaki yeri:** Dalga 4 sonrası. KVKK dilimi ve yayın öncesi güvenlik testiyle göreli
  sırası ayrıca kararlaştırılacak. Dalga 5 yetki matrisini değiştirdiği için güvenlik testlerinin
  (özellikle AUTHZ kontrolleri) Dalga 5'ten SONRA koşulması önerilir.

## 1. Bulgular ve kararlar

| # | Bulgu | Karar | Belge |
|---|---|---|---|
| B1 | Geliştirici görev oluşturabiliyor, puan/son tarih/sprint/atama değiştirebiliyor | Geliştirici görev oluşturamaz; yalnız "işi yapma" alanlarını (durum, açıklama, etiket, bağımlılık, yorum) değiştirir, atanmamış göreve kendini atayabilir. Retro maddesini göreve çevirme yönetmen ve yöneticiye çekilir. Yönetmen görev silemez (değişmedi). | ADR-0019 |
| B2 | Rol kuralları uçlara ve frontend'e dağılmış | Rol listesi yerine izin modeli, tek eşleme, frontend izinleri sunucudan alır | ADR-0019 |
| B3 | Roller workspace bazında; büyük şirketler projeleri ayırmak isteyecek | Şimdilik "proje başına workspace". Proje bazlı rol talep gelince; izin modeli geçişi hazırlar | ADR-0019 |
| B4 | Görev oluştururken yalnız başlık ve tarih var | Opsiyonel alanlarla tek transaction'da oluşturma; formda "Genişlet" | Dilim 5.4 |
| B5 | Alt görev sıfırdan açılamıyor, yalnız mevcut görev bağlanıyor | Yeni `POST /tasks/{id}/subtasks`; geliştirici yalnız kendine atanmış görevin altına açar | ADR-0021 |
| B6 | Alt görev + üst görev puanlanırsa Velocity çift sayar (sorgularda üst/alt ayrımı yok) | Puan yalnız üst görevde (seçenek a) | ADR-0021 |
| B7 | Bloklayan bitmeden bloklanan Done olabiliyor | Bloklayan Done olmadan bloklanan Done olamaz; PR merge'de atla ve bildir | ADR-0020 |
| B8 | Yorum yazarken/düzenlerken mention `@[uuid]` olarak görünüyor; `@` yazınca öneri açılmıyor | Saklama `@[uuid]` kalır (ADR-0009 gerekçesi geçerli); editörde `@` ile öneri, metinde `@Ad Soyad` | Dilim 5.6 |
| B9 | Görev penceresi dar (`Dialog` sabit `max-w-md` ≈ 448px) | `Dialog`'a `size`; görev için ≈1100px iki sütun, mobilde tek sütun; `?task=ANAHTAR` derin linki | Dilim 5.4 |
| B10 | Bildirimler yalnız zilde, gözden kaçıyor | Şimdi Notification API (sekme açıkken). **Faz 6'da Web Push** | Dilim 5.7, PHASE_6 §6 |
| B11 | Anasayfa selamlaması e-postanın `@` öncesini gösteriyor (`DashboardPage.tsx:53`) | `GET /api/v1/me` → tam ad | Dilim 5.2 |

**B8 notu (güvenlik değerlendirmesi):** Kullanıcı ID'sinin görünmesi bir güvenlik açığı olarak
raporlandı. Değerlendirme: kullanılabilirlik hatası. ID gizli bir değer değil (üye listesi API'si
her üyeye döndürüyor), yetki ID'nin bilinmemesine dayanmıyor; her istek RLS + izin kontrolünden
geçiyor. Yine de güvenlik testinde "ID ile başka kiracının kaynağına erişim (BOLA/IDOR)" kontrolü
zaten kapsamda (`docs/security/02-TEST-SENARYOLARI.md`, AUTHZ/TEN).

## 2. Dilimler

Her dilim tek commit'tir ve şu kapıdan geçer: `mvnw clean verify` yeşil, `spotless:check` +
`spotbugs:check` ayrıca temiz, frontend `npm run build` + `npm run lint` temiz, sunucuda tarayıcıyla
elle deneme.

### 5.1 İzin katmanı — davranış DEĞİŞTİRMEYEN geçiş

- `WorkspacePermission` enum'u, `RolePermissions` eşlemesi, `SecurityGuard.can(...)`.
- Eşleme bu dilimde BUGÜNKÜ davranışı birebir üretir (geliştirici hâlâ görev oluşturabilir).
  Bütün `hasCurrentWorkspaceRole(...)` anotasyonları `can(...)`'e çevrilir.
- **Kabul:** `HttpAuthorizationIntegrationTest` hiç değiştirilmeden yeşil. Eşleme için birim testi.
  Bu dilimin amacı, davranış değişikliğini (5.2) refactor'dan ayırmak: 5.2'de kırılan bir test
  gerçekten kural değişikliğini gösterir.

### 5.2 Yeni rol matrisi + kimlik uçları

- Backend:
  - Eşleme ADR-0019 tablosuna çekilir: geliştiriciden `TASK_CREATE`, `TASK_PLAN` alınır; retro
    çevirme `TASK_CREATE`'e bağlanır.
  - `TaskAccessPolicy`: `TASK_SELF_ASSIGN` koşulu (yalnız kendini, yalnız atanmamış göreve; kendi
    atamasını bırakabilir).
  - `BulkTaskService`: işlem tipine göre izin, döngüden önce.
  - `GET /api/v1/me` → `{id, email, fullName}`; `GET /api/v1/me/permissions` → `{role, permissions[]}`.
- Frontend:
  - `useCan(permission)` kancası; 16 dosyadaki `role === ...` karşılaştırmaları buna taşınır.
  - Selamlama `fullName` gösterir; yüklenirken iskelet, e-posta hiçbir durumda görünmez.
  - Header avatarı da `/me`'den beslenir.
- **Kabul:**
  - Yetki matrisi testi yeni tabloya göre güncellenir.
  - Her koşullu izin için HTTP seviyesinde ret testi: geliştirici başkasını atayamaz; atanmış
    göreve kendini atayamaz; toplu işlemde SPRINT/ASSIGNEE 403.
  - Geliştirici hesabıyla tarayıcıda "Yeni görev" ve planlama alanları görünmez.

### 5.3 Bağımlılık kuralı (ADR-0020)

- `TaskDependencyRepository.hasOpenBlocker` (native SQL, `deleted_at IS NULL` elle).
- Kontrol `TaskService.updateStatus` içinde, yalnız `newStatus == DONE` iken.
- `GithubEventProcessor`: ön kontrol, bloklu ise durumu değiştirme, işlenmiş işaretle,
  `TASK_TRANSITION_BLOCKED` olayı yaz. `NotificationMessageFormatter` + `InboxFanoutService` bu
  olayı tanır.
- `TaskDependencyService.link`: Done olan göreve açık bloklayan eklenemez.
- Frontend: kartta kilit rozeti, Done'a sürüklemede 400 mesajındaki bloklayan listesi toast'ta.
- **Kabul (entegrasyon testleri):**
  - UI yolu 400 döner.
  - Toplu işlemde bir bloklu görev → hiçbir görev değişmez.
  - PR merge bloklu görevi Done yapmaz, DLT'ye olay düşmez, Inbox bildirimi oluşur.
  - Silinmiş bloklayan yok sayılır.
  - Farklı projedeki bloklayan da kuralı tetikler.
  - Bloklu görev In Progress'e geçebilir.
- Sıralama gerekçesi: kullanıcının "mantık hatası" olarak gördüğü tek bulgu; 5.2'den sonra çünkü
  ikisi de `TaskService`'e dokunuyor (aynı dosyada sıralı çalışma).

### 5.4 Geniş pencere + genişletilmiş görev oluşturma

- Backend:
  - `CreateTaskRequest`'e opsiyonel alanlar: `description`, `assigneeId`, `storyPoint`,
    `sprintId`, `tagIds`.
  - **Opsiyonel alanlar boxed tiptir** (`Integer`, `UUID`, `List`): primitif eksik alan 400
    verip `@PreAuthorize` 403'ünü maskeler (Dalga 2 `MeetingRequest` dersi).
  - Servis tek `@Transactional` metotta görevi oluşturur ve mevcut iç metotları (atama, puan,
    sprint, etiket) çağırır; her biri bugün yazdığı olayı aynen yazar. Böylece Inbox, otomasyon
    (`ASSIGNED_TODO_TO_IN_PROGRESS`) ve analitik tüketicileri değişmez.
- Frontend:
  - `Dialog`'a `size` (`md` varsayılan, `xl` ≈1100px).
  - `TaskDialog` iki sütun: solda başlık, açıklama, alt görevler, yorum, aktivite; sağda durum,
    atanan, puan, sprint, son tarih, etiket, bağımlılık. `lg` altında tek sütun.
  - `NewTaskDialog` kısa formla açılır, "Genişlet" diğer alanları açar. Takvimden hızlı ekleme
    yalnız başlık ve tarih olarak kalır.
  - Görev açıkken URL `?task=<PROJE>-<NO>`. Link paylaşılabilir; Inbox, e-posta ve masaüstü
    bildirimi bu linke gider.
- **Kabul:**
  - Geçersiz bir alan (başka workspace'in etiketi gibi) gelince görev OLUŞMAZ (tek tx rollback).
  - Tüm alanlarla oluşturmada yazılan olaylar, aynı işlemlerin sırayla yapılmasıyla aynı.
  - Tarayıcıda 1366px ve mobil genişlikte görsel kontrol.

### 5.5 Alt görev oluşturma + puan kuralı (ADR-0021)

- **Ön adım (sunucuda, koddan önce):** mevcut verideki puanlı alt görev sayısı ölçülür (sorgu
  ADR-0021 madde 5). Sıfır değilse elle temizlenir.
- `POST /api/v1/tasks/{parentTaskId}/subtasks`. Sprint ve atanan üst görevden. `SUBTASK_CREATE_OWN`
  koşulu `TaskAccessPolicy`'de.
- `updateStoryPoint` alt görevde reddeder; `setParent` puanlı görevi reddeder ve `TASK_PLAN` ister.
- Frontend: "Alt görev ekle" → "Yeni oluştur" (satır içi başlık, Enter) | "Mevcut görevi bağla".
  Alt görevde puan alanı gösterilmez.
- **Kabul:**
  - Geliştirici kendine atanmış görevin altına açar (201).
  - Geliştirici başkasınınkinin altına açamaz (403).
  - Alt göreve puan 400.
  - Puanlı görev bağlama 400.
  - Alt görevin altına alt görev 400.
- Bağımlılık: 5.2 (izinler) ve 5.4 (oluşturma isteği).

### 5.6 Mention editörü (yalnız frontend)

- Yorum textarea'sında `@` yazınca üye öneri listesi açılır (yazdıkça süzülür, ok tuşları + Enter,
  Esc kapatır). Mevcut "Etiketle" butonu kalır, aynı mekanizmayı kullanır.
- Seçilen kişi metne `@Ad Soyad` olarak girer. Taslak boyunca bir görünür metin → `userId` eşlemesi
  tutulur; gönderirken eşlenen görünür metinler `@[uuid]`'ye çevrilir. Düzenleme açılırken
  token'lar isme geri çevrilir ve eşleme yeniden kurulur.
- Workspace'te aynı adı taşıyan birden fazla üye varsa öneri listesinde e-posta da gösterilir;
  metne giren görünür ad, çakışmayı önlemek için ayırt edici biçimde yazılır (ör.
  `@Ad Soyad (eposta-öneki)`).
- `withMentionNames` paylaşılan bir yardımcıya taşınır ve global arama sonucu parçasında da
  kullanılır. `SearchRepository` `ts_headline` ile ham gövdeden parça üretiyor; parçada ham token
  görünmesi hipotezdir, uygularken doğrulanacak.
- Uygularken kontrol edilecek (doğrulanmadı): mention e-postası (`EmailTemplates.mentionEmail`) ve
  Inbox metni yorum gövdesini içeriyor mu? İçeriyorsa token sunucu tarafında isme çevrilmeli.
- Backend saklama biçimi ve `MentionParser` değişmez; ADR-0009 kararı geçerli, yeni ADR gerekmez.
- **Kabul:**
  - Yazma, düzenleme, görüntüleme, arama ve e-postada hiçbir yerde ham `@[uuid]` görünmez.
  - Mention bildirimi bugünkü gibi gider (`COMMENT_MENTION`).

### 5.7 Masaüstü bildirimi — Notification API (yalnız frontend)

- `lib/realtime.ts` içindeki bildirim olayı geldiğinde, şu koşulların hepsi sağlanıyorsa
  `new Notification(...)` gösterilir:
  - `document.visibilityState !== 'visible'`
  - izin `granted`
  - cihaz ayarı açık
- Bildirim davranışı:
  - Seçenekler `{ body, tag: notificationId }`. Aynı `tag` birden fazla açık sekmede tekrar
    göstermeyi önler.
  - Tıklanınca pencereye odaklanır ve `?task=` linkine gider.
- İzin sayfa açılışında İSTENMEZ (Chrome sık reddedilen sitelerde istemi sessizleştirir):
  - Ayarlar → "Masaüstü bildirimleri" anahtarı, tıklamayla `Notification.requestPermission()`.
  - Zil menüsünde tek seferlik "masaüstü bildirimlerini aç" önerisi.
  - `denied` durumunda tarayıcı ayarından nasıl açılacağı anlatılır.
- Cihaz ayarı `localStorage`'da tutulur, çünkü cihaza özeldir (try/catch korumalı, `session.ts`
  deseni). Hangi olayların geldiği Inbox ile aynıdır.
- **Ön koşul ve risk:** Notification API yalnız güvenli bağlamda çalışır. Sunucuya HTTPS ile IP
  üzerinden erişiliyor. Sertifika tarayıcıya güvenilir değilse (kendinden imzalı, uyarı geçilerek
  açılıyor) davranış tarayıcıya göre değişebilir. İlk iş olarak sunucuda konsoldan
  `window.isSecureContext` ve `Notification.permission` kontrol edilir, izin istemi denenir.
  Faz 6'daki Web Push için tarayıcının GÜVENDİĞİ bir sertifika şarttır (Service Worker kaydı
  sertifika hatasında reddedilir). Alan adı + Let's Encrypt (`deploy/Caddyfile` hazır) veya cihazlara
  kurulu bir iç CA gerekir.
- **Kabul:** sunucuda iki sekme açıkken tek bildirim; odaktaki sekmede masaüstü bildirimi yok;
  tıklama doğru göreve götürür; izin reddedilince uygulama hatasız çalışır.

## 3. Sıra

5.1 → 5.2 → 5.3 → 5.4 → 5.5 → 5.6 → 5.7

- 5.1 ve 5.2 temeldir: sonraki dilimlerin yetki kuralları `can()` ve `TaskAccessPolicy`'ye dayanır.
- 5.3, 5.2 ve 5.4 `TaskService`'e dokunduğu için sıralıdır; aralarında başka bağımlılık yoktur.
- 5.6 ve 5.7 yalnız frontend'dir ve birbirinden bağımsızdır; 5.4'ün `?task=` linkini kullandıkları
  için ondan sonra gelirler.

## 4. Kapsam dışı (bilinçli olarak ertelenenler)

| Konu | Nereye |
|---|---|
| Web Push (sekme/tarayıcı kapalıyken bildirim) | Faz 6 — `PHASE_6_PRODUCT_FEATURES.md` §6 |
| Proje bazlı rol, özel proje | Talep gelince yeni ADR (ADR-0019 "Değerlendirilen seçenekler") |
| Alt görevlerin throughput/Monte Carlo/Aging WIP'te ayrı görev sayılması | Kullanım verisi sonrası ayrı ADR (ADR-0021 açık nokta) |
| Yönetmen için "İptal" durumu (silme yerine kapanış) | Fikir olarak not edildi, karar yok |
| Arama indeksinde mention'ın isimle bulunması (`tsvector` bugün token'ı indeksliyor) | Ayrı iş; 5.6 yalnız görüntülemeyi düzeltir |
