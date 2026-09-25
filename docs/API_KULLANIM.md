# WorkFollowUp — API Kullanım Dokümanı

> Kurulum (Docker Compose, migration, env değişkenleri) için `KURULUM.md`'ye bakın.
> Bu doküman uygulama zaten ayakta olduğunu varsayar.

## 1. Swagger / OpenAPI

Uygulama ayaktayken:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

Swagger UI'da sağ üstteki **Authorize** kilidine `Bearer <access_token>` girerek (aşağıdaki
login adımından alınan token) korumalı endpoint'leri doğrudan UI üzerinden deneyebilirsiniz.
`X-Workspace-Id` header'ı da aynı diyalogda ayrıca istenir (workspace'e bağlı endpoint'ler için).

Bu proje şu an **sadece backend REST API**'dir — bir web arayüzü (React) henüz yazılmadı.
Uygulamayı kullanmak = Swagger UI, Postman/Insomnia veya `curl` ile API'yi çağırmak demektir.

## 2. Kimlik Doğrulama Akışı

### 2.1 Kayıt

```
POST /api/v1/auth/register
Content-Type: application/json

{
  "email": "ali@example.com",
  "password": "en-az-12-karakter",
  "fullName": "Ali Veli"
}
```

`201 Created`. Kayıt, `notification.email` üzerinden gerçek bir doğrulama e-postası gönderir
(Dilim 1.3 — bkz. §3.5); e-posta doğrulama akışı (`verify-email`) login'i **engellemiyor** —
e-posta doğrulanmamış kullanıcı da login olabilir (bilinen tasarım, bkz.
`SECURITY_AND_EXCEPTIONS_DESIGN.md`).

### 2.2 Giriş

```
POST /api/v1/auth/login
Content-Type: application/json

{ "email": "ali@example.com", "password": "en-az-12-karakter" }
```

Yanıt gövdesi:

```json
{ "accessToken": "<JWT, RS256, 15dk>", "refreshTokenExpiresIn": 604800 }
```

Refresh token yanıt gövdesinde **DÖNMEZ** — `HttpOnly; Secure; SameSite=Strict` cookie olarak
gelir (`refresh_token`, path `/api/v1/auth`). Tarayıcı dışı istemciler (curl/Postman) cookie
jar kullanmazsa refresh akışını test edemez; Postman/Insomnia'da "cookie'leri sakla" açık
olmalı. `Secure` bayrağı yüzünden **HTTPS olmayan ortamda (düz `http://localhost`) tarayıcı bu
cookie'yi hiç göndermez** — yalnızca curl/Postman gibi HTTPS zorunluluğunu es geçen istemcilerle
test edilebilir; gerçek bir frontend eklenene kadar bu bilinen bir kısıt.

### 2.3 Korumalı isteklerde header

```
Authorization: Bearer <accessToken>
```

`/api/v1/auth/**`, `/api/v1/webhooks/**`, `/actuator/health/**`, `/ws/**`, Swagger yolları
dışındaki HER endpoint bu header'ı ister; yoksa `401`.

### 2.4 Refresh / Logout

```
POST /api/v1/auth/refresh   (cookie'den refresh_token okunur, body yok)
POST /api/v1/auth/logout
```

Refresh **tek kullanımlıktır** (rotation + reuse detection) — aynı refresh token ikinci kez
kullanılırsa tüm oturum ailesi (`family_id`) iptal edilir; çift sekme toleransı için ~30sn
grace period var.

## 3. Workspace / Multi-Tenancy

Her workspace'e bağlı istek **hem** `Authorization` **hem de** `X-Workspace-Id` header'ı
ister:

```
X-Workspace-Id: <workspace UUID>
```

Header'daki UUID `workspace_users` tablosunda kullanıcının üyeliği ile doğrulanır; üye
değilse `403`. Header eksikse tenant context kurulmaz — workspace'e bağlı bir endpoint'i
header'sız çağırmak RLS'in hiçbir satır görmemesine (boş liste) ya da işlem tipine göre
hataya yol açabilir; bu davranış test edilmemiş bir boşluktur (bkz. `Ilerleme.md`), header'ı
**her zaman** gönderin.

```
POST /api/v1/workspaces
Authorization: Bearer <token>
Content-Type: application/json

{ "name": "Benim Şirketim" }
```

Workspace'i oluşturan kullanıcı otomatik olarak `WORKSPACE_ADMIN` olur.

Kullanıcının üye olduğu workspace'leri ve oradaki rolünü listelemek için (`X-Workspace-Id`
**gerekmez**; frontend workspace seçimini buradan yapar):

```
GET /api/v1/workspaces
→ [{ "id": "...", "name": "Benim Şirketim", "planType": "free", "role": "WORKSPACE_ADMIN" }]
```

### 3.1 Görev tarihi ve takvim (V15)

Görevlerin opsiyonel bir `dueDate` alanı (`yyyy-MM-dd`, saat dilimsiz takvim günü) var.
Her değişiklik `task_events`'e `due_date_changed` ve outbox'a `TASK_DUE_DATE_CHANGED`
olarak yazılır.

```
POST /api/v1/projects/{projectId}/tasks        { "title": "...", "dueDate": "2026-09-24" }   # dueDate opsiyonel
PUT  /api/v1/tasks/{taskId}/due-date           { "dueDate": "2026-09-26" }                   # null = takvimden kaldır
GET  /api/v1/projects/{projectId}/tasks/calendar?from=2026-08-31&to=2026-10-11
```

Takvim ucu sayfalamasızdır; aralık iki uç dahil en fazla **62 gün** olabilir (aşarsa `400`).
Tarihsiz görevler bu uçta dönmez. Yazma rolleri diğer görev uçlarıyla aynıdır
(ADMIN/MANAGER/DEVELOPER).

**Geçmiş tarih yasağı (V16):** yeni verilen `dueDate` bugünden önce olamaz (`400`). "Bugün"
sunucunun `app.business-time-zone` ayarına göre hesaplanır (varsayılan `Europe/Istanbul`).
Kural yalnız DEĞİŞEN tarihe uygulanır: gecikmiş görevin mevcut tarihi korunur, `null` serbesttir.

### 3.2 Onay, Tamamlananlar ve silme (V16)

```
POST   /api/v1/tasks/{taskId}/approval                  # ADMIN/MANAGER; yalnız "Done" görev
DELETE /api/v1/tasks/{taskId}/approval                  # ADMIN/MANAGER; onayı geri al
GET    /api/v1/projects/{projectId}/tasks/approved      # onaylananlar, onay zamanına göre (keyset)
DELETE /api/v1/tasks/{taskId}                           # yalnız ADMIN; soft delete, 204
```

- Onaylanan görevin durumu `Done` **kalır** (analitik etkilenmez), `approvedAt` dolar ve
  `GET /projects/{id}/tasks` (Kanban listesi) artık onu **döndürmez**; takvim döndürmeye devam eder.
- Onaylı görevin durumu ve tarihi değiştirilemez (`400`); önce onay geri alınmalı.
- Silme soft'tur (`deleted_at`); görev tüm okuma uçlarından düşer, tarihçe (`task_events`) korunur,
  sprint'teyse sprint'ten çıkarılır, `TASK_DELETED` olayı Cycle Time/Throughput read model'ini temizler.

> ADMIN, **kayıtlı** bir kullanıcıyı `POST /api/v1/workspaces/members` (`{"email","role"}`) ile
> anında ekleyebilir (kayıtlı olmayan e-posta `404` döner). Kayıtsız birini davet etmek için bkz.
> §3.7 (token'li davet, Dalga 1.4).

### 3.3 Dönemsel rapor ve hedefler (V21)

```
GET    /api/v1/reports/period?year=2026[&quarter=3][&projectIds=<uuid>,<uuid>]
GET    /api/v1/goals?year=2026[&quarter=3]
POST   /api/v1/goals                       # ADMIN/MANAGER
PUT    /api/v1/goals/{goalId}              # ADMIN/MANAGER; başlık/hedef/kapsam
PUT    /api/v1/goals/{goalId}/progress     # ADMIN/MANAGER; yalnız metricType=CUSTOM
DELETE /api/v1/goals/{goalId}              # ADMIN/MANAGER, 204
```

- Dönem **takvim** dönemidir: `quarter` verilmezse tüm yıl. Sınırlar iş saat diliminde
  (`app.business-time-zone`) hesaplanır, yani `2026-Q3` Istanbul'da 1 Temmuz 00:00'da başlar.
- Rapor **workspace geneli**dir; `projectIds` (virgülle ayrılmış, en fazla 100) yalnızca toplamları,
  proje kırılımını, aylık trendi ve önceki dönem kıyaslamasını daraltır.
  **Hedef ilerlemeleri süzgeçten etkilenmez** (`projectFilterApplied` bunu bildirir).
- Tek istek sayfanın tamamını döndürür (KPI'lar, aylık kırılım, proje tablosu, hedefler): parçalar
  aynı dönem kesitinden gelir.
- Tüm sayılar mevcut read model'lerden (`task_analytics`, `sprint_analytics`) okunur; "tamamlanma"
  tanımı Throughput/Cycle Time ile birebir aynıdır (görevin **son** `Done` geçişi; yeniden açılıp
  kapanan görev yalnız son kapanışında sayılır). Puansız görev 0 puandır.
- `metricType`: `COMPLETED_TASKS` | `COMPLETED_POINTS` (ilerleme otomatik) | `CUSTOM` (elle).
  Hedefin dönemi ve metrik tipi oluşturulduktan sonra **değiştirilemez**; yeni hedef açılmalı.
- Yıllık ve çeyreklik hedefler ayrı listelerdir: yıllık rapor çeyreklik hedefleri göstermez.

### 3.4 Atanan, açıklama, izleyiciler ve "Benim işlerim" (V22)

```
PUT    /api/v1/tasks/{taskId}/assignee      # yazma rolleri; {"assigneeId": "<uuid>" | null}
PUT    /api/v1/tasks/{taskId}/description   # yazma rolleri; {"description": "<markdown>" | null}
GET    /api/v1/tasks/{taskId}/detail        # her üye; açıklama + izleyiciler + watching
PUT    /api/v1/tasks/{taskId}/watch         # her üye (VIEWER dahil); idempotent
DELETE /api/v1/tasks/{taskId}/watch         # her üye; idempotent
GET    /api/v1/me/tasks?limit=&cursor=      # bana atanmış, onaylanmamış görevler (tüm projeler)
```

- Görevin **tek** atananı vardır; atanan, workspace'in VIEWER dışı bir üyesi olmalıdır (`400`).
  Onaylı görevin atananı ve açıklaması değiştirilemez.
- Açıklama Markdown'dır (en fazla 20.000 karakter); boş metin açıklamayı kaldırır. Liste
  yanıtları (`TaskResponse`) açıklamayı **taşımaz**, yalnız `assigneeId` taşır; açıklama için
  `/detail` çağrılır.
- **Inbox alıcıları = görevin izleyicileri − işlemi yapan kişi.** Görevi oluşturan ve atanan
  otomatik izleyici olur. `TASK_ASSIGNED` yeni atanana izlemese bile gider (ADR-0008).

### 3.5 Yorumlar ve @mention (V23)

```
GET    /api/v1/tasks/{taskId}/comments?limit=&cursor=   # her üye; eskiden yeniye (keyset)
POST   /api/v1/tasks/{taskId}/comments                  # yazma rolleri; {"body": "<markdown>"}
PATCH  /api/v1/comments/{commentId}                     # yalnız yazan; {"body": "<markdown>"}
DELETE /api/v1/comments/{commentId}                     # yazan veya workspace ADMIN
```

- Yorum gövdesi Markdown'dır (en fazla 10.000 karakter). Mention sözdizimi `@[<userId>]` —
  frontend autocomplete ile ekler, sunucu üye listesine karşı doğrular; geçersiz/üye olmayan
  token'lar sessizce yok sayılır (serbest isim eşleştirme YOK). Bir yorumda en fazla 20 mention
  işlenir.
- Silinen bir yorum listede **kalır**, gövdesi `"[silindi]"` olarak döner (`deleted: true`);
  gövde asıl olarak DB'de korunur, yalnız API'de maskelenir.
- `TaskResponse.commentCount`: silinenler dahil toplam yorum sayısı (liste/kanban rozeti için).
- **Bildirim (ADR-0009):** yorum yazan ve geçerli mention edilenler otomatik izleyici olur.
  `COMMENT_ADDED` izleyicilere (aktör ve o yorumda mention edilenler hariç) gider; mention
  edilenler AYRICA (izliyor olsun olmasın, "her zaman") `COMMENT_MENTION` ile bildirilir — aynı
  yoruma iki bildirim gitmez.
- Yazan/ADMIN olmayanın düzenleme/silme denemesi **403** döner (silinmiş yorumu düzenlemek 400).
  Düzenleme `COMMENT_UPDATED`, silme `COMMENT_DELETED` yayınlar — yalnız canlı yenileme için,
  bildirim üretmezler.

### 3.6 E-posta gönderimi ve bildirim tercihleri (V24, ADR-0010)

```
GET /api/v1/me/notification-preferences   # X-Workspace-Id GEREKMEZ, kullaniciya ait
PUT /api/v1/me/notification-preferences   # {"emailOnAssign": bool, "emailOnMention": bool}
```

- Aşağıdaki olaylar gerçek bir e-posta gönderir (yerelde Mailpit, `http://localhost:8025`):
  e-posta doğrulama (`/verify-email?token=`), parola sıfırlama (`/reset-password?token=`, 30dk
  geçerli), güvenlik uyarısı (parola değişti / oturum ailesi iptal edildi), görev ataması
  ("size atandı"), yorum @mention'ı. Şablonlar Türkçe, düz metin + basit HTML.
- `emailOnAssign`/`emailOnMention` yalnız **atama/mention** e-postalarını kapsar. Doğrulama,
  parola sıfırlama ve güvenlik uyarısı e-postaları koşulsuz gönderilir, kapatılamaz.
- Frontend: `POST /api/v1/auth/password-reset/request` → `/forgot-password` sayfası,
  `/reset-password?token=`, `/verify-email?token=` sayfaları; Ayarlar'da "Bildirim tercihleri".

### 3.7 Token'li workspace daveti (V25, Dalga 1.4)

```
POST   /api/v1/workspaces/members/invitations           # ADMIN; {"email","role"}, 7 gün geçerli
GET    /api/v1/workspaces/members/invitations            # ADMIN; bekleyen (PENDING) davetler
DELETE /api/v1/workspaces/members/invitations/{id}       # ADMIN; iptal

GET    /api/v1/invitations/{token}                        # public, kimlik/header GEREKMEZ
POST   /api/v1/invitations/{token}/accept                 # kimlik ister, X-Workspace-Id GEREKMEZ
```

- `POST .../invitations` kayıtlı olsun olmasın herhangi bir e-postaya davet e-postası gönderir
  (§3.6'daki `email.workspace_invite` şablonu; link `/invitations/{token}`). Aynı workspace+e-posta
  için bekleyen bir davet varsa önce iptal edilir, sonra yenisi üretilir ("yeniden gönder" ayrı bir
  uç değildir).
- `GET /invitations/{token}` **public**: workspace adı, rol, e-posta ve durumu döner
  (`PENDING`/`ACCEPTED`/`REVOKED`/`EXPIRED`) — henüz giriş yapmamış biri de davet sayfasını
  görebilsin diye.
- `POST /invitations/{token}/accept`: çağıran kullanıcının hesap e-postası davetteki e-postayla
  (büyük/küçük harf duyarsız) **eşleşmezse `400`**. Kayıtsız biri önce normal `/register` ile hesap
  açıp giriş yapmalı, sonra bu uca gelmelidir — ayrı bir "davetle kayıt" ucu yok, mevcut register
  akışı yeniden kullanılır.
- Ham token API yanıtlarının HİÇBİRİNDE dönmez (yalnız e-postadaki linkte); DB'de yalnız SHA-256
  hash'i tutulur (`verification_tokens` ile aynı desen).

### 3.8 Global arama (V26, Dalga 1.6, ADR-0011)

```
GET /api/v1/search?q=<metin>&limit=<1-50, varsayılan 10>
```

- Rol sınırı yok (workspace üyeliği yeterli). `q` parametresi zorunludur, hiç verilmezse `400`;
  boş/yalnız boşluk bir `q` ise hata değil, boş sonuç (`{"tasks":[],"comments":[]}`) döner.
- Yanıt: `{ "tasks": [...], "comments": [...] }`.
  - Görev satırı: `id, projectId, projectKey, taskNumber, title, status, snippet`. `snippet`
    açıklamadan çıkarılan kısa bağlam (eşleşme yoksa `null`).
  - Yorum satırı: `id, taskId, projectId, projectKey, taskNumber, taskTitle, snippet`.
- Arama başlık (ağırlık A) + açıklama (ağırlık B) üzerinden `tsvector`/`ts_rank` ile yapılır;
  `simple` + `unaccent` konfigürasyonu kullanılır (Türkçe ek/çekim eşleşmesi yok, bilinen sınır).
- `PRJ-12` gibi proje anahtarı + görev numarası biçimindeki sorgular (büyük/küçük harf duyarsız)
  doğrudan eşleşerek sonucun başına konur.
- Silinmiş görev/yorumlar sonuçlarda görünmez; onaylı görevler görünür (onay yalnız Kanban'dan
  kaldırır, aramayı etkilemez).
- `snippet` içindeki eşleşen kelime U+0001/U+0002 kontrol karakterleriyle işaretlenir — HTML
  DEĞİLDİR, istemci bunu kendi vurgu elemanına çevirir (frontend `CommandPalette.tsx#highlightSnippet`).

### 3.9 Kayıtlı görünümler + toplu işlem (V27, Dalga 1.7)

```
POST   /api/v1/projects/{projectId}/saved-views   {"name": "...", "query": "<opak JSON metni>"}
GET    /api/v1/projects/{projectId}/saved-views
DELETE /api/v1/saved-views/{viewId}
POST   /api/v1/tasks/bulk
```

- Kayıtlı görünümler **kişiseldir** (Tags/Meetings'in aksine workspace geneli paylaşılan bir yapı
  DEĞİL): rol sınırı yok, ama liste yalnız kendi görünümlerini döner, silme yalnız sahibine açıktır
  (başkasının görünümünü silme denemesi `403`). `query` sunucuda hiç yorumlanmaz — Kanban'ın URL
  filtre durumunun (`sprint`/`tags`/`assignee`) opak bir JSON kopyasıdır, istemci kaydeder ve geri
  uygular.
- Toplu işlem (`POST /tasks/bulk`) yazma rolleriyle AYNI (ADMIN/MANAGER/DEVELOPER). Gövde:
  `{"taskIds": [...en fazla 100...], "operation": "STATUS|SPRINT|ASSIGNEE|ADD_TAG|REMOVE_TAG", ...}`.
  Operasyona göre ek alan: `STATUS` → `status`, `SPRINT` → `sprintId` (`null` backlog'a alır),
  `ASSIGNEE` → `assigneeId` (`null` atamayı kaldırır), `ADD_TAG`/`REMOVE_TAG` → `tagId`. Her görev
  `TaskService`/`TagService` üzerinden **tek transaction** içinde işlenir: bir görevde hata olursa
  (ör. bulunamayan `taskId`, onaylı görev) TÜMÜ geri alınır — kısmi uygulama yok. Yanıt güncellenmiş
  görevlerin listesidir.

### 3.10 Aging WIP — takılan iş uyarısı (V28, Dalga 2.1, ADR-0012)

```
GET /api/v1/projects/{projectId}/flow/aging
```

- Rol sınırı yok (analitik okuma ile aynı ilke). Projenin en az 10 tamamlanmış görevi yoksa
  `{"thresholdAvailable": false, "p85Seconds": null, "items": []}` döner — az örneklemde eşik
  anlamsız kabul edilir, rozet/bildirim hiç üretilmez.
- Eşik projenin kendi p85 cycle time'ıdır (sabit bir gün sayısı değil). Yanıttaki `items` şu an
  "açık" (en az bir kez In Progress'e girmiş, henüz Done olmamış) görevleri en yaşlıdan gence
  sıralar: `taskId, taskNumber, title, ageSeconds, level`. `level`: `0` normal, `1` p85'i aşmış,
  `2` 2×p85'i aşmış.
- Ayrı bir `AgingWipJob` saatlik çalışır ve seviye YÜKSELDİĞİNDE (0→1, 1→2) atanana ve
  izleyicilere Inbox bildirimi gönderir; aynı seviyede tekrar tekrar bildirim gitmez
  (`task_aging_alerts`). Bu uç yalnız OKUMA yapar, bildirim tetiklemez.

### 3.11 Monte Carlo tahmin (Dalga 2.2, ADR-0013)

```
GET /api/v1/projects/{projectId}/forecast/backlog
GET /api/v1/sprints/{sprintId}/forecast
```

- Rol sınırı yok. Projenin 12 haftalık (84 gün) penceresinde toplam en az 10 tamamlanmış iş yoksa
  `{"available": false}` döner — az veriyle yanlış güvenli bir tarih göstermek yerine hiç
  gösterilmez.
- Yanıt: `available, sampleSize, remainingItems, p50CompletionDate, p85CompletionDate,
  p95CompletionDate, targetDate, probabilityByTargetDate`. Proje (backlog) ucunda `targetDate`/
  `probabilityByTargetDate` `null`dır; sprint ucunda `targetDate` sprint bitiş tarihi,
  `probabilityByTargetDate` kalan işin o tarihe kadar bitme olasılığıdır (0.0-1.0).
- Yöntem bootstrap resampling Monte Carlo'dur (deterministik ortalama değil); sonuç sunucuda 1 saat
  Redis'te önbelleklenir, projede o günün içinde herhangi bir görev durumu değişince/silinince
  önbellek temizlenir.
- Yalnız görev SAYISINA dayanır (story point tabanlı tahmin ve hedef/goal bazlı olasılık bu
  dilimde YOK, bkz. ADR-0013).

### 3.12 Toplantısız standup (Dalga 2.3, V29, ADR-0014)

```
GET /api/v1/standups?meetingId=&date=
PUT /api/v1/standups/{meetingId}/{date}/note   {"note": "..."}
```

- `meetings.standupEnabled` açıksa `StandupDigestJob` occurrence başlamadan 30 dakika önce
  ADMIN/MANAGER/DEVELOPER rolündeki her üye için bir özet üretir (Inbox + varsa Slack).
- `GET`: rol sınırı yok. Yanıt her katılımcı için `userId, userName, facts, note, createdAt`;
  `facts` altı kategori taşır: `completedYesterday, progressedYesterday, inProgress, blocked,
  aging, githubActivity` (her biri `taskId, projectKey, taskNumber, title` listesi).
- `PUT .../note`: yalnız KENDİ notunu güncelleyebilirsin (`WHERE user_id = giriş yapan kullanıcı`);
  o tarih için özetin yoksa `404`.
- "Dün" = önceki İŞ GÜNÜ (hafta sonu atlanır), iş saat diliminde hesaplanır.

## 4. Roller

`workspace_users.role`: `WORKSPACE_ADMIN`, `MANAGER`, `DEVELOPER`, `VIEWER`.

| Endpoint | İzinli roller |
|---|---|
| `POST /api/v1/projects` | ADMIN, MANAGER |
| `POST /api/v1/projects/{id}/tasks`, task status/sprint/story-point güncelleme, yorum ekleme | ADMIN, MANAGER, DEVELOPER |
| `POST/…/sprints/**` (oluştur/başlat/tamamla) | ADMIN, MANAGER |
| `GET` uçları (liste, analitik, rapor) | tüm roller (VIEWER dahil) |
| Dönem hedefi yönetimi (`/api/v1/goals`) | ADMIN, MANAGER |
| Webhook/Slack entegrasyon yönetimi | yalnız ADMIN |
| Üye ekleme/davet gönderme/davet iptali | yalnız ADMIN |
| Toplu görev işlemi (`POST /tasks/bulk`) | ADMIN, MANAGER, DEVELOPER |
| Kayıtlı görünüm oluştur/listele/sil | tüm roller (kişisel, VIEWER dahil) |
| Kafka DLT replay | yalnız SYSTEM_ADMIN (global rol, `SYSTEM_ADMIN_EMAILS` env'i ile atanır) |

## 5. Uçtan Uca Örnek Akış

```
1. POST /api/v1/auth/register           -> kullanıcı oluştur
2. POST /api/v1/auth/login              -> accessToken al
3. POST /api/v1/workspaces              -> workspace oluştur (sen ADMIN olursun), workspaceId'yi not et
4. POST /api/v1/projects                -> { "key": "APP", "name": "Ana Proje" }
   (X-Workspace-Id header'ı ile)
5. POST /api/v1/projects/{projectId}/tasks
   -> { "title": "İlk görev" }
6. PATCH /api/v1/tasks/{taskId}
   -> { "status": "In Progress" }   (geçerli değerler: "To Do" | "In Progress" | "Review" | "Done")
7. POST /api/v1/projects/{projectId}/sprints -> sprint oluştur
8. PUT /api/v1/tasks/{taskId}/sprint         -> { "sprintId": "<uuid>" }
9. PUT /api/v1/tasks/{taskId}/story-point    -> { "storyPoint": 5 }
10. POST /api/v1/sprints/{sprintId}/start
11. POST /api/v1/sprints/{sprintId}/complete
12. GET /api/v1/projects/{projectId}/analytics/velocity
    GET /api/v1/projects/{projectId}/analytics/throughput
    GET /api/v1/projects/{projectId}/analytics/cycle-time
```

Task durum geçişleri **yalnız ileri yönde** zorlanır (`To Do < In Progress < Review < Done`);
geri alma yoktur (bilinçli tasarım, webhook'tan gelen sırasız event'lerin tamamlanmış görevi
geri saramaması için).

## 6. Sayfalama

`GET /api/v1/projects/{projectId}/tasks?limit=20&cursor=<opaque>` — keyset (cursor) pagination.
`limit` 1-200 arası kırpılır. Yanıt: `{ "data": [...], "nextCursor": "...", "hasMore": true }`.

## 7. Entegrasyonlar (opsiyonel, workspace ADMIN)

- **GitHub webhook:** `POST /api/v1/integrations/webhooks` ile bir entegrasyon kaydı
  oluşturulur, dönen secret ile GitHub repo ayarlarında webhook kurulur
  (`POST /api/v1/webhooks/github/{integrationId}`, HMAC-SHA256 imzalı, JWT taşımaz).
  Commit/PR mesajında `[A-Z][A-Z0-9]{1,9}-\d{1,9}` deseni (örn. `APP-12`) task'ı bulur ve
  durumunu günceller.
- **Slack bildirimi:** `PUT /api/v1/integrations/slack` ile tek bir Slack Incoming Webhook
  URL'i (`https://hooks.slack.com/services/...`) kaydedilir; task oluşturma/durum değişikliği
  bu kanala bildirilir.

Yerel/tek-kişilik testte ikisi de opsiyoneldir — atlanabilir.

## 8. Bilinen Davranış Kısıtları (yerel test sırasında şaşırtabilir)

- `Task.updatedAt` durum değişikliğinde güncellenmiyor (görsel/sıralama amaçlı kullanılmamalı).
- Analitik uçları (velocity/throughput/cycle-time) `@ReadReplica` opt-in routing kullanıyor;
  yerelde ayrı bir read replica yoksa (varsayılan) aynı DB'den okur, fark hissedilmez.
- Cycle Time yalnız `task_events` tablosundaki event geçmişinden hesaplanır — bir görevi
  event akışı dışında (elle DB'de) değiştirirseniz metrikler tutarsız kalır.
- Atama/mention e-postaları `auto.offset.reset=latest` ile tüketilir: uygulama ilk kez ayağa
  kalktığında (yeni consumer group) o ana kadar oluşmuş atama/mention olayları e-postaya
  DÖKÜLMEZ, yalnız bundan sonraki olaylar gönderilir (ADR-0010).
