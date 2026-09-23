# WorkFollowUp — Yerel Kurulum Dokümanı

Bu doküman, Faz 4 (mikroservis dönüşümü) öncesi mevcut **moduler monolit** mimariyi lokalde
ayağa kaldırıp Swagger üzerinden denemek içindir. Üretim/K8s dağıtımı için `docs/
PHASE_0_CICD_AND_DEPLOYMENT.md` ve `k8s/` dizinine bakın — bu doküman yalnız yerel geliştirme
akışını anlatır.

## 1. Ön Koşullar

- **JDK 21** (proje `mvnw` ile geliyor, sistemde ayrıca `mvn` kurulu olmasına gerek yok)
- **Docker Desktop** (Postgres/Redis/Kafka için `docker-compose.yml`)
- Windows'ta: `JAVA_HOME` JDK 21'i göstermeli. Bilinen kurulum yeri:
  `C:\Program Files\Java\jdk-21.0.7` (`~/.jdks` altında farklı bir JDK varsa karıştırmayın).

## 2. ⚠️ Bilinen Ortam Tuzağı — Port 5432 Çakışması

Bu makinede **native bir Windows PostgreSQL servisi** (`postgresql-x64-15`) zaten 5432
portunu dinliyor olabilir. `docker-compose.yml`'deki Postgres container'ı da aynı portu
(`5432:5432`) host'a bağlamaya çalışır; ikisi aynı anda dinlerken host'tan yapılan bağlantı
container'a değil native servise gidebilir ve **"password authentication failed for user
app_migrator/app_runtime"** hatası alırsınız (container'ın kendi içi etkilenmez —
Testcontainers'lı entegrasyon testleri bundan etkilenmiyor, yalnız host'tan elle bağlanan
`./mvnw spring-boot:run` / `flyway:migrate` etkileniyor).

Kontrol edin:

```powershell
netstat -ano | findstr :5432
Get-Service postgresql* 
```

Çözüm (ikisinden birini seçin):

- **A) Native servisi durdurun** (yerel Postgres'i kullanmıyorsanız): `Stop-Service
  postgresql-x64-15` (kalıcı olarak devre dışı bırakmak isterseniz `Set-Service
  postgresql-x64-15 -StartupType Manual`).
- **B) Compose container'ını farklı bir host portuna taşıyın** — repo kökünde
  `docker-compose.override.yml` (git'e girmez, sadece sizin makinenizde etkili):

  ```yaml
  services:
    postgres:
      ports:
        - "15432:5432"
  ```

  Bu durumda aşağıdaki tüm komutlarda `DB_PORT=15432` kullanın.

## 3. Altyapıyı Ayağa Kaldırma

```bash
docker compose up -d
docker compose ps   # postgres/redis/kafka "healthy" olmalı (~10-15sn)
```

`docker/postgres-init/init-roles-and-dbs.sh` ilk açılışta otomatik çalışır: `app_migrator`
(bootstrap/owner, container'ın `POSTGRES_USER`'ı) ve `app_runtime` (DML-only, RLS'e tabi
non-superuser) rollerini + `tracker` veritabanını kurar.

## 4. Migration'ları Uygulama

Migration'lar **uygulama startup'ında OTOMATİK çalışmaz** (`spring.flyway.enabled=false`,
bilinçli tasarım — bkz. `Mimari.md`). Ayrı bir "migrate" profili ile, `app_migrator`
kimliğiyle bir kereye mahsus çalıştırılır:

```bash
export JAVA_HOME="C:\Program Files\Java\jdk-21.0.7"
export PATH="$JAVA_HOME/bin:$PATH"

DB_HOST=localhost DB_PORT=5432 DB_NAME=tracker \
DB_USER=app_migrator DB_PASSWORD=app_migrator \
./mvnw -Dspring-boot.run.profiles=migrate spring-boot:run
```

Başarılı çıktı `Successfully applied N migrations ... now at version vX` ile biter ve JVM
`exit 0` ile kapanır (Job deseni — bkz. `MigrationRunner`). Yeni bir migration eklediğinizde
bu komutu tekrar çalıştırmanız yeterli (Flyway sadece uygulanmamışları işler).

## 5. Uygulamayı Başlatma

```bash
DB_PORT=5432 ./mvnw spring-boot:run
```

Varsayılan (`application.yml`) profil, hiçbir env değişkeni vermeseniz de local'de çalışacak
şekilde ayarlı:

| Env | Varsayılan | Not |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `tracker` | |
| `DB_USER` / `DB_PASSWORD` | `app_runtime` / `app_runtime` | migration'ı **app_migrator** ile, uygulamayı **app_runtime** ile çalıştırın |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `JWT_PRIVATE_KEY_LOCATION` / `JWT_PUBLIC_KEY_LOCATION` | repo içindeki dev anahtarları | yalnız yerel/dev'de geçerli — staging/prod'da bu varsayılanla açılış REDDEDİLİR |
| `WEBHOOK_SECRET_MASTER_KEY` | dev placeholder | staging/prod'da 32+ karakter ve `dev-webhook-` önekli olmayan bir değer zorunlu |
| `SYSTEM_ADMIN_EMAILS` | boş | virgülle ayrılmış e-postalar ilk girişte SYSTEM_ADMIN işaretlenir (Kafka DLT replay için gerekir) |
| `CORS_ALLOWED_ORIGINS` | boş | bir frontend'den çağıracaksanız origin'i buraya ekleyin |

Uygulama `http://localhost:8080` üzerinde ayağa kalkar. Log'un sonunda `Started
TrackerApplication in Ns` satırını görmelisiniz.

## 6. Doğrulama

```bash
curl http://localhost:8080/actuator/health/readiness
curl http://localhost:8080/v3/api-docs | head -c 200
```

Tarayıcıda: **http://localhost:8080/swagger-ui.html**

Buradan `POST /api/v1/auth/register` → `POST /api/v1/auth/login` ile bir access token alıp
Swagger'ın sağ üstündeki **Authorize** ile girin; artık tüm korumalı endpoint'leri UI'dan
deneyebilirsiniz. Ayrıntılı akış için `docs/API_KULLANIM.md`.

## 6.1 Frontend (React)

```bash
cd frontend
cp .env.example .env.local   # VITE_API_BASE_URL BOŞ kalmalı (Vite proxy kullanılır)
npm install
npm run dev                  # http://localhost:5173
```

Backend **`CORS_ALLOWED_ORIGINS=http://localhost:5173`** ile başlatılmalı: istekler Vite
proxy'si üzerinden aynı origin'den gitse de refresh/logout uçları `Origin` header'ını bu
listeye göre doğruluyor.

Neden proxy: refresh token `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` bir
cookie. Tarayıcı `http://localhost`'u güvenli bağlam saydığı için `Secure` cookie düz HTTP'de
de kabul edilir, ama yalnızca **aynı site** isteklerinde gönderilir. `VITE_API_BASE_URL`'i
`http://localhost:8080` yaparsanız sayfa yenilendiğinde oturum düşer. Backend 8080'de
değilse `VITE_API_PROXY_TARGET` ayarlayın.

## 7. Durdurma / Temizlik

```bash
docker compose down          # container'ları durdurur, veriyi KORUR (named volume)
docker compose down -v       # veriyi de siler (sıfırdan migration gerekir)
```

## 8. Testleri Çalıştırma (opsiyonel)

Entegrasyon testleri kendi Testcontainers Postgres/Kafka'sını ayağa kaldırır — yukarıdaki
`docker-compose` altyapısından bağımsızdır, port 5432 çakışmasından etkilenmez. Docker
Desktop'ın açık olması yeterli:

```bash
./mvnw clean verify -DargLine=-Dapi.version=1.44
./mvnw spotless:check
./mvnw spotbugs:check
```

`spotless`/`spotbugs` `verify`'a bağlı DEĞİL, ayrıca çalıştırılmalı (bkz. `Ilerleme.md`).

## 9. Sınır / Kapsam Notu

Bu kurulum yalnız **backend REST API**'yi ayağa kaldırır. Proje şu an bir frontend (React)
içermiyor — "kullanmak" Swagger UI veya Postman üzerinden API çağırmak anlamına geliyor.
Detay ve bilinen davranış kısıtları için `docs/API_KULLANIM.md`.
