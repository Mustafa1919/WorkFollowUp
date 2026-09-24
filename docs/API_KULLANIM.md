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

`201 Created`. E-posta doğrulama akışı (`verify-email`) mevcut ama login'i **engellemiyor**
— e-posta doğrulanmamış kullanıcı da login olabilir (bilinen tasarım, bkz.
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

> **Bilinen kısıt:** Şu an başka bir kullanıcıyı workspace'e davet eden/ekleyen bir API
> endpoint'i YOK. Her kullanıcı sadece kendi oluşturduğu workspace'in admin'i olabilir;
> çok kullanıcılı bir workspace test etmek istiyorsanız (rol matrisini denemek için)
> DB'ye elle `workspace_users` satırı eklemeniz gerekir. Bu, tek kişilik yerel kullanım
> için engel değil ama not edilmeli.

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

## 4. Roller

`workspace_users.role`: `WORKSPACE_ADMIN`, `MANAGER`, `DEVELOPER`, `VIEWER`.

| Endpoint | İzinli roller |
|---|---|
| `POST /api/v1/projects` | ADMIN, MANAGER |
| `POST /api/v1/projects/{id}/tasks`, task status/sprint/story-point güncelleme | ADMIN, MANAGER, DEVELOPER |
| `POST/…/sprints/**` (oluştur/başlat/tamamla) | ADMIN, MANAGER |
| `GET` uçları (liste, analitik, rapor) | tüm roller (VIEWER dahil) |
| Dönem hedefi yönetimi (`/api/v1/goals`) | ADMIN, MANAGER |
| Webhook/Slack entegrasyon yönetimi | yalnız ADMIN |
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
