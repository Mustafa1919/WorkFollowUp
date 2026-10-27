# ADR-0019: İzin tabanlı yetki modeli ve geliştirici rolünün daraltılması

- **Durum:** Kabul edildi (Dalga 5 planı, 2026-10-26 — kod henüz yazılmadı)
- **İlgili:** SECURITY_AND_EXCEPTIONS_DESIGN.md Bölüm 1.2 (rol tanımları), ADR-0018 (PAT — token
  kullanıcının rollerini taşır, bu ADR'nin izinleri PAT'li isteklerde de aynen geçerli),
  ADR-0021 (alt görev oluşturma — geliştiricinin koşullu izni), `docs/DALGA_5_KULLANILABILIRLIK.md`

## Bağlam

Uygulama sunucuya kurulup denendiğinde iki sorun görüldü:

1. **Geliştirici rolü fazla güçlü.** `DEVELOPER`, `ADMIN` ve `MANAGER` ile aynı listede olduğu için
   görev oluşturabiliyor, başkasının görevini kendine/başkasına atayabiliyor, story point'i, son
   tarihi ve sprint üyeliğini değiştirebiliyor. Story point ve sprint üyeliği Velocity (ADR-0002)
   ve Retro'nun (ADR-0015) girdileri olduğu için bu, analitiklerin güvenilirliğini doğrudan bozar.
2. **Yetki kuralları dağınık.** Kural her uçta `@PreAuthorize("@securityGuard.hasCurrentWorkspaceRole(
   'WORKSPACE_ADMIN', 'MANAGER', 'DEVELOPER')")` biçiminde rol listesi olarak elle yazılmış
   (yalnız `TaskController`'da 55 rol referansı); frontend'de de 16 dosyada `role === 'MANAGER'`
   gibi literal karşılaştırmalar var. "Geliştirici puanı değiştiremesin" gibi bir değişiklik onlarca
   yere dokunmayı gerektiriyor ve bir yer unutulursa backend ile frontend sessizce ayrışıyor.

Roller workspace bazında kalacak (proje bazlı rol bu ADR'nin kapsamı DEĞİL, bkz. "Değerlendirilen
seçenekler"). Büyük ekipler için önerilen kullanım şimdilik "proje başına workspace"tır.

## Karar

1. **Uçlar rol değil izin (permission) kontrol eder.** Yeni `WorkspacePermission` enum'u ve rol →
   izin eşlemesinin TEK tanımı (`RolePermissions`, kodda sabit) eklenir. `SecurityGuard`'a
   `can(String permission)` eklenir; uçlar `@PreAuthorize("@securityGuard.can('TASK_PLAN')")`
   biçimine geçer. Eski `hasCurrentWorkspaceRole` yalnız geçiş süresince kalır, geçiş bitince
   silinir.
2. **İzin kataloğu ve eşleme** (✅ = izinli):

   | İzin | ADMIN | MANAGER | DEVELOPER | VIEWER |
   |---|:-:|:-:|:-:|:-:|
   | `TASK_CREATE` — görev oluşturma, retro maddesini göreve çevirme | ✅ | ✅ | | |
   | `TASK_EDIT` — durum, açıklama, etiket, bağımlılık | ✅ | ✅ | ✅ | |
   | `TASK_PLAN` — story point, son tarih, sprint, başkasını atama, mevcut görevi alt görev olarak bağlama/çözme | ✅ | ✅ | | |
   | `TASK_SELF_ASSIGN` — atanmamış göreve kendini atama, kendi atamasını bırakma (koşullu, madde 3) | ✅ | ✅ | ✅ | |
   | `SUBTASK_CREATE_OWN` — kendisine atanmış görevin altına alt görev (koşullu, madde 3, ADR-0021) | ✅ | ✅ | ✅ | |
   | `TASK_APPROVE` — onay ve onayı geri alma | ✅ | ✅ | | |
   | `TASK_DELETE` | ✅ | | | |
   | `COMMENT_WRITE` | ✅ | ✅ | ✅ | |
   | `COMMENT_MODERATE` — başkasının yorumunu silme | ✅ | | | |
   | `RETRO_ITEM_WRITE` | ✅ | ✅ | ✅ | |
   | `PROJECT_CREATE`, `SPRINT_MANAGE`, `TAG_MANAGE`, `MEETING_MANAGE`, `GOAL_MANAGE`, `AUTOMATION_MANAGE`, `FEEDBACK_READ` | ✅ | ✅ | | |
   | `MEMBER_MANAGE`, `INTEGRATION_MANAGE` | ✅ | | | |

   Üyelik yeterli olan işlemler (okuma, izleme, kayıtlı görünüm, geri bildirim gönderme) izin
   gerektirmez, bugünkü gibi `WorkspaceContextFilter`'ın üyelik doğrulamasıyla korunur.
   **Davranış değişiklikleri:** geliştiriciden `TASK_CREATE` ve `TASK_PLAN` alınır; retro maddesini
   göreve çevirme `TASK_CREATE`'e bağlanır (bugün geliştiriciye açık, `RetroController` `WRITE`).
   Yönetmenin görev silememesi BİLEREK korunur.
3. **Kaynak durumuna bağlı (koşullu) izinler iki katmanda denetlenir.** `TASK_SELF_ASSIGN` ve
   `SUBTASK_CREATE_OWN` yalnız anotasyonla ifade edilemez ("görev atanmamış mı", "üst görev bana mı
   atanmış" bilgisi gerekir). Anotasyon kaba izni kontrol eder; ince kural servis katmanındaki tek
   bir `TaskAccessPolicy` içinde, kaynak yüklendikten sonra uygulanır. Örnek: `assign` ucu
   `TASK_PLAN` VEYA `TASK_SELF_ASSIGN` ister; servis, çağıranda `TASK_PLAN` yoksa
   `assigneeId == currentUser` ve (mevcut atanan `null` ya da çağıranın kendisi) şartını arar,
   aksi halde `AccessDeniedException` (403) fırlatır — yorum silmedeki (ADR-0009 madde 7) yazan/
   ADMIN denetimiyle aynı yer.
4. **Toplu işlem (`POST /tasks/bulk`) işlem tipine göre denetlenir.** `STATUS`, `ADD_TAG`,
   `REMOVE_TAG` → `TASK_EDIT`; `SPRINT`, `ASSIGNEE` → `TASK_PLAN`. Kontrol `BulkTaskService`'te,
   döngüden ÖNCE yapılır (kısmi uygulama zaten yok; yetkisizse hiçbir görev değişmez).
5. **Frontend izinleri sunucudan alır, rol literali karşılaştırmaz.** `GET /api/v1/me/permissions`
   (`X-Workspace-Id` zorunlu) → `{ role, permissions[] }`. Frontend tek bir `useCan(permission)`
   kancası kullanır; 16 dosyadaki `role === ...` karşılaştırmaları buna taşınır. Böylece eşleme
   değişince frontend'de kod değişmez. Butonları gizlemek yalnız UI kolaylığıdır; asıl kontrol
   her zaman backend'dedir.
6. **Eşleme kodda sabittir, DB'de değil.** Rol tanımı kiracı bazında özelleştirilemez (bkz.
   seçenekler).

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| Rol listelerini olduğu gibi bırakıp yalnız ilgili uçlardan `DEVELOPER`'ı silmek | En az iş, ama sorun 2 kalır: bir sonraki rol değişikliği yine onlarca yere dokunur, frontend ayrışması sürer. |
| Proje bazlı rol (`project_members`, etkin rol = proje kaydı ?? workspace rolü) | Gerçek bir ihtiyaç ama henüz talep yok. Maliyeti: `/tasks/{id}` gibi uçlarda her kontrolde görev → proje çözümü, özel proje görünürlüğünün RLS dışında (uygulama katmanında) kalması. Bu ADR'nin izin katmanı ileride yalnız `can()`'in içini değiştirerek buna geçişi mümkün kılar. |
| "Proje başına workspace"ı kalıcı çözüm saymak | Kısa vadede yeterli; bedeli projeler arası görünümün (Benim işlerim, Inbox, arama, rapor) workspace sınırında kesilmesi. Proje bazlı rol talebi gelirse yeniden açılır. |
| DB'de yapılandırılabilir rol/izin (kiracıya özel roller) | Jira tipi konfigürasyon karmaşası; ADR-0016'daki "sabit şablon" tercihiyle aynı gerekçe. |
| ReBAC (Zanzibar, OpenFGA) | Ölçeğe göre fazla; yeni bir altyapı bileşeni. Proje bazlı rol bile yetmediğinde düşünülür. |
| Koşullu izinleri (self-assign) SpEL içinde çözmek | SpEL'de kaynağı yüklemek aynı görevi iki kez okumak demek ve kural dağılır; servis katmanındaki tek politika sınıfı test edilebilir. |

## Sonuçlar

**Olumlu**
- Rol değişikliği tek dosyada (`RolePermissions`) yapılır; backend ve frontend aynı kaynaktan
  beslenir.
- Velocity/Retro girdileri (puan, sprint üyeliği) yalnız planlama yetkisi olan rollerde kalır.
- Proje bazlı role geçiş yolu açık: yalnız `can()`'in "etkin rolü nereden okuduğu" değişir.

**Olumsuz / kabul edilen bedel**
- Koşullu izinler iki katmanda (anotasyon + `TaskAccessPolicy`) — bir uçta servis kontrolü
  unutulursa anotasyon tek başına fazla izin verir. Bu yüzden her koşullu izin için HTTP seviyesinde
  ret testi zorunlu (`HttpAuthorizationIntegrationTest`).
- Geliştirici artık backlog'a görev ekleyemez; küçük ekiplerde yönetmen darboğaz olabilir. Kullanıcı
  geri bildirimiyle yeniden değerlendirilecek.
- Mevcut `HttpAuthorizationIntegrationTest` matrisi güncellenir; geçişin ilk dilimi davranış
  DEĞİŞTİRMEMELİ (aynı matris yeşil kalmalı), davranış değişikliği ayrı dilimde gelir.

## Faz 4 notu

Gateway'e geçişte izin kontrolü gateway'e TAŞINMAZ: izinler workspace üyeliğine ve kaynak durumuna
bağlı, gateway'de bu bilgi yok. Gateway yalnız kimlik doğrulamayı (JWT/PAT) yapar.

## Referanslar

- `src/main/java/com/app/tracker/core/security/SecurityGuard.java`
- `src/main/java/com/app/tracker/workspace/model/WorkspaceRole.java`
- `src/main/java/com/app/tracker/task/controller/TaskController.java`
- `src/main/java/com/app/tracker/task/service/BulkTaskService.java`
- `src/main/java/com/app/tracker/retro/RetroController.java`
- `frontend/src/lib/types.ts` (`WorkspaceRole`), `frontend/src/features/board/BoardPage.tsx`,
  `frontend/src/features/board/TaskDialog.tsx`
