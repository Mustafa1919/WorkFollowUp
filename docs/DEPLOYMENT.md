# WorkFollowUp — Bulut Sunucusuna Yayına Alma (Production Deployment)

Bu doküman, uygulamayı tek bir Linux sunucusuna (herhangi bir bulut sağlayıcısı — Hetzner,
DigitalOcean, AWS EC2, GCP Compute Engine, Azure VM, vb.) Docker Compose ile kurup canlıya
almak içindir. `docs/KURULUM.md` yerel geliştirme akışını anlatır, bu doküman onun **üretim
karşılığıdır**.

## 0. Kapsam ve Ön Koşul

**Bu doküman altyapıyı hazırlar; "canlıya aç" kararı ayrı bir adımdır.** Projenin kendi yol
haritasına göre (`Hedefler.md`) gerçek kullanıcı trafiğine açmadan önce iki şey daha
tamamlanmalı: (1) KVKK aydınlatma metni + rıza checkbox'ı, (2) `docs/security/`'deki güvenlik
testleri + bulgu düzeltmeleri. Bu doküman ve `docker-compose.prod.yml` o testlerin **hedef
ortamı** olacak şekilde tasarlandı (04 sertleştirme kontrol listesindeki maddeleri
operasyonelleştirir) — yani önce burayı kurup demo/test ortamı olarak kullanabilir, gerçek
kullanıcı verisiyle açılışı ayrı bir karar olarak sonraya bırakabilirsiniz.

**Neden `k8s/overlays/prod` değil:** Repoda zaten bir k8s prod overlay'i var, ama
`DB_HOST=host.docker.internal` kullanıyor — bu, **kind** (yerel Kubernetes-in-Docker) için
yazılmış bir kalıp; `Mimari.md`'nin kendi ifadesiyle "infra kalıcı olarak kind dışında" ve
gerçek HA altyapısı (Strimzi + CloudNativePG) bilinçli olarak Faz 4/5'e ertelenmiş durumda.
Tek sunuculuk bir "bulut sunucuya at" senaryosu için bu k8s kurulumu olduğu gibi kullanılamaz
(managed DB/Redis/Kafka ya da gerçek bir cluster gerekir). Bu yüzden burada zaten var olan
`docker-compose.app.yml` (app katmanı) + `docker-compose.yml` (infra) ikilisini üretime
sertleştirilmiş bir üçüncü katmanla (`docker-compose.prod.yml`) genişletiyoruz — mimarinin
zaten seçtiği "modüler monolit, tek makine" kararıyla tutarlı.

## 1. Sunucu Seçimi

- **Minimum:** 2 vCPU / 4 GB RAM / 40 GB SSD, Ubuntu 22.04 veya 24.04 LTS. Aynı makinede
  Postgres + Redis + Kafka + backend (JVM) + frontend (nginx) çalışacak — Kafka + JVM ikisi
  birlikte RAM'e aç, 2 GB'ın altına inmeyin.
- **DNS:** Kuruluma başlamadan önce alan adınızın A kaydını sunucu IP'sine yönlendirin (TLS
  sertifikası almak DNS'in çözülmesini bekler).

## 2. Sunucu İlk Kurulumu

```bash
# Root ile SSH'lanıp sudo yetkili, kendi kullanıcınızı oluşturun
adduser deploy
usermod -aG sudo deploy
# SSH anahtarınızı deploy kullanıcısına kopyalayın, ANAHTARLA giriş yapabildiğinizi
# doğrulayın, ANCAK bundan sonra parola girişini kapatın:
#   /etc/ssh/sshd_config → PasswordAuthentication no, PermitRootLogin no
systemctl restart sshd

apt update && apt install -y ufw fail2ban unattended-upgrades
dpkg-reconfigure -plow unattended-upgrades

ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable

timedatectl set-ntp true
```

**Docker ile ufw çelişkisi (04 kontrol listesi, madde 1):** Docker, `ports:` ile yayınladığı
portlar için kendi iptables kurallarını ekler ve bu kurallar **ufw'nin önüne geçer** — yani
ufw'de kapalı görünen bir port, Docker onu yayınladıysa yine de dışarıdan erişilebilir olabilir.
Bu yüzden aşağıdaki `docker-compose.prod.yml`'de Postgres/Redis/Kafka için **hiç host portu
yayınlanmıyor** (yalnız Docker'ın iç ağı üzerinden konuşuyorlar) — ufw kuralına güvenmek yerine
yapısal olarak kapatılıyor.

## 3. Docker Kurulumu

```bash
curl -fsSL https://get.docker.com | sh
usermod -aG docker deploy
# yeniden login olun (grup üyeliği yeni oturumda etkinleşir)
docker compose version   # v2 plugin geldiğini doğrulayın
```

## 4. Repo ve İmaj

CI, `main`'e her merge'de backend imajını `ghcr.io/<owner-küçük-harf>/tracker:<commit-sha>`
olarak GHCR'a SHA etiketiyle push'luyor (`latest` YOK — bkz. `.github/workflows/ci.yml`).
Frontend imajı şu an CI'da push'lanmıyor, sunucuda repodan build edilir.

```bash
mkdir -p /opt/workfollowup && cd /opt/workfollowup
git clone https://github.com/<owner>/<repo>.git repo
cd repo
git checkout <main'deki-doğrulanmış-commit-sha>

# Repo private ise GHCR'dan çekmek için bir PAT gerekir (scope: read:packages)
echo "<PAT>" | docker login ghcr.io -u <github-kullanici-adi> --password-stdin
docker pull ghcr.io/<owner-küçük-harf>/tracker:<commit-sha>
```

## 5. Sırların Üretimi

Tüm değerler `deploy/.env.prod.example` şablonundaki adlarla `deploy/.env.prod` dosyasına
yazılır (bu dosya `.gitignore`'da, asla commit edilmez).

```bash
cp deploy/.env.prod.example deploy/.env.prod
chmod 600 deploy/.env.prod
```

**JWT anahtar çifti** (RS256, `JwtKeyProvider` staging/prod'da repodaki dev anahtarını
reddeder — bkz. `Mimari.md` Bölüm 9). `k8s/overlays/prod/kustomization.yaml`'daki yorum
satırlarıyla aynı komutlar:

```bash
mkdir -p deploy/jwt && cd deploy/jwt
openssl genrsa -out raw.pem 3072
openssl pkcs8 -topk8 -nocrypt -in raw.pem -out private.pem
openssl rsa -in raw.pem -pubout -out public.pem
rm raw.pem
chmod 600 private.pem && chmod 644 public.pem
cd /opt/workfollowup/repo
```

**Diğer sırlar:**

```bash
# WEBHOOK_SECRET_MASTER_KEY — WebhookSecretService MIN 32 karakter ve "dev-webhook-" ön eki
# OLMAYAN bir değer bekliyor (staging/prod fail-closed guard):
openssl rand -base64 32

# Postgres app_migrator parolası (POSTGRES_PASSWORD ile aynı, container ilk açılışta bunu okur)
openssl rand -base64 24

# Redis parolası
openssl rand -base64 24
```

Bu üç değeri `deploy/.env.prod` içine yazın. `app_runtime` parolası ayrı — bkz. Bölüm 7
(init script'te sabit kodlu, ilk açılıştan SONRA elle değiştirilmesi gerekiyor).

SMTP için gerçek bir sağlayıcının (SendGrid, AWS SES, Postmark vb.) SMTP kimlik bilgilerini
girin — Mailpit yalnız dev/CI içindir, prod compose'da hiç yok (bkz. Bölüm 6).

## 6. `docker-compose.prod.yml`

Repo köküne eklenen bu dosya, mevcut `docker-compose.yml` + `docker-compose.app.yml`
ikilisinin üzerine **üçüncü bir katman** olarak biner (aynı override deseni, `KURULUM.md`'deki
`docker-compose.override.yml` fikrinin üretim eşdeğeri). Yaptıkları — her biri 04 kontrol
listesindeki bir maddeyi karşılar:

- Postgres/Redis/Kafka için `ports:` yok → dışarıya kapalı (INF-01, INF-04, INF-05)
- Redis `requirepass` ile parola zorunlu (INF-04)
- Mailpit servisi tanımlı değil (INF-06) — SMTP prod ortamda gerçek sağlayıcıya gider
- JWT anahtarları host'tan salt-okunur bind mount (`deploy/jwt/`) — imaja gömülü değil
- Backend'e `SPRING_PROFILES_ACTIVE=prod` → fail-closed guard'lar devreye girer (CFG-07)
- Tüm servislerde `restart: unless-stopped` ve temel kaynak limitleri
- Postgres/Redis/Kafka imajları zaten `docker-compose.yml`'de sabit sürümle (`latest` yok)

```bash
docker compose \
  -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.prod.yml \
  --env-file deploy/.env.prod \
  up -d postgres redis kafka
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.prod.yml \
  ps   # üçü de "healthy" olana kadar bekleyin (~15sn)
```

## 7. Veritabanı Sertleştirme (Bir Kereye Mahsus, İlk Açılıştan Sonra)

`docker/postgres-init/init-roles-and-dbs.sh` iki şey yapar ki üretimde **istemezsiniz**: (1)
`app_runtime` parolasını `'app_runtime'` olarak sabit kodlar (env değişkeniyle
parametrelenmemiş), (2) kullanılmayan `tracker_dev` / `tracker_staging` / `tracker_prod`
veritabanlarını oluşturur (uygulama gerçekte tek bir `tracker` veritabanını kullanıyor — bkz.
`docker-compose.yml` `POSTGRES_DB: tracker`). 04 kontrol listesi madde 60 ve 64 tam olarak bu
ikisini işaret ediyor:

```bash
docker compose ... exec postgres psql -U app_migrator -d tracker -c "
  ALTER ROLE app_runtime WITH PASSWORD '<Bölüm 5'te ürettiğiniz güçlü parola>';
  REVOKE CREATE ON SCHEMA public FROM app_runtime;
"
docker compose ... exec postgres psql -U app_migrator -c "
  DROP DATABASE IF EXISTS tracker_dev;
  DROP DATABASE IF EXISTS tracker_staging;
  DROP DATABASE IF EXISTS tracker_prod;
"
```

`REVOKE CREATE`'in `ALTER DEFAULT PRIVILEGES`'ı etkilemediğini not edin — `app_runtime` yeni
migration'larla eklenen tablolarda hâlâ `SELECT/INSERT/UPDATE/DELETE` alır (bu init script'in
zaten kurduğu default privilege), yalnız yeni **şema nesnesi** (tablo/tip) oluşturma yetkisi
kaldırılıyor; migration'lar zaten `app_migrator` kimliğiyle çalışıyor (Bölüm 8).

`deploy/.env.prod`'daki `DB_PASSWORD` değerini de bu yeni parolayla güncelleyin.

## 8. Migration

```bash
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.prod.yml \
  --env-file deploy/.env.prod run --rm migrate
```

`Successfully applied N migrations ... now at vX` ile biter, konteyner `exit 0` verip kapanır
(Job deseni, `MigrationRunner`).

## 9. Backend + Frontend

```bash
# Frontend imajı GHCR'da yok, sunucuda build edilir:
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.prod.yml \
  --env-file deploy/.env.prod build frontend

docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.prod.yml \
  --env-file deploy/.env.prod up -d backend frontend
```

## 10. Reverse Proxy — TLS ve Güvenlik Başlıkları

**Caddy** öneriliyor (nginx+certbot değil): otomatik TLS alma/yenileme tek satırlık config ile
gelir, tek kişilik bir hobi projesinde nginx+certbot'un cron/renewal bakım yükünü taşımaya
değmez. Trade-off: nginx daha yaygın/tanıdık ve ince ayar seçeneği daha geniş — ekip
büyür/ikinci bir operatör girerse nginx'e geçmek makul, ama tek başınıza işlettiğiniz sürece
Caddy daha az operasyonel yük demek.

`deploy/Caddyfile` şablonunu kullanın (alan adınızı doldurup sunucuya kopyalayın). Öne çıkan
noktalar:

- `/actuator/*` **dışarıdan reddediliyor** (04 madde CFG-02) — `frontend/nginx.conf` bu yolu
  backend'e proxy'liyor (bkz. o dosya), tek kapatma noktası dış Caddy katmanı; uygulama
  kodunda değişiklik gerekmiyor. `/swagger-ui/*` ve `/v3/api-docs/*` zaten `frontend/nginx.conf`
  tarafından proxy'lenmiyor (yalnız `/api/`, `/actuator/`, `/ws/` tanımlı) — backend portu da
  dışarı açık olmadığından (Bölüm 6) bu ikisi zaten erişilemez durumda, ek bir engelleme
  gerekmiyor (04 madde CFG-03 bu yolla otomatik sağlanmış oluyor).
- HSTS, CSP, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy` başlıkları.
- `/ws/*` için `Connection`/`Upgrade` geçişi (STOMP; `frontend/nginx.conf` zaten 3600s
  `proxy_read_timeout` ile heartbeat'siz bağlantıyı destekliyor).

```bash
apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list
apt update && apt install -y caddy

cp deploy/Caddyfile /etc/caddy/Caddyfile
# domain'i doldurduktan sonra:
systemctl reload caddy
```

## 11. Doğrulama

```bash
curl -sI https://<domain>/actuator/health/readiness   # Caddy 404 vermeli (dışarı kapalı)
curl -s https://<domain>/                                     # frontend index.html
```

Tarayıcıda kayıt ol → giriş yap → bir görev oluştur akışını bir kez elle deneyin. Ardından
`docs/security/04-YAYIN-ONCESI-SERTLESTIRME.md` kontrol listesindeki tüm maddeleri tek tek
işaretleyin (bu doküman onları uyguladı, ama listeyi imzalamak ayrı bir adım) ve
`docs/security/02-TEST-SENARYOLARI.md`'deki testler koşulmadan gerçek kullanıcı trafiğine
açmayın (bkz. Bölüm 0).

## 12. Yedekleme

```bash
cat >/opt/workfollowup/backup.sh <<'EOF'
#!/bin/bash
set -euo pipefail
cd /opt/workfollowup/repo
STAMP=$(date +%Y%m%d-%H%M%S)
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.prod.yml \
  exec -T postgres pg_dump -U app_migrator tracker | gzip > /opt/workfollowup/backups/tracker-$STAMP.sql.gz
find /opt/workfollowup/backups -name '*.sql.gz' -mtime +14 -delete
EOF
chmod +x /opt/workfollowup/backup.sh
mkdir -p /opt/workfollowup/backups
# crontab -e (deploy kullanıcısı): 0 3 * * * /opt/workfollowup/backup.sh
```

Yedekleri sunucu dışına (sağlayıcının object storage'ı, `rclone` vb.) düzenli kopyalayın —
yukarıdaki script yalnız yerel diske yazar. **Geri yüklemeyi bir kez elle deneyin** (04 madde
DB-10) — hiç test edilmemiş bir yedek, yedek değildir.

## 13. Log Rotasyonu

`docker-compose.prod.yml`, tüm servislere `json-file` log driver için `max-size`/`max-file`
tanımlar (Docker varsayılanı sınırsız büyür, diski doldurabilir).

## 14. Güncelleme

```bash
cd /opt/workfollowup/repo
git fetch && git checkout <yeni-sha>
docker login ghcr.io ...
docker pull ghcr.io/<owner>/tracker:<yeni-sha>
docker compose ... build frontend
docker compose ... --env-file deploy/.env.prod run --rm migrate
docker compose ... --env-file deploy/.env.prod up -d backend frontend
```

Geri alma (rollback): bir önceki SHA'ya `git checkout` + `docker pull`/`up -d` ile dönün.
Flyway migration'ları **geri almaz** — geriye dönük uyumsuz bir migration'dan sonra rollback
gerekiyorsa bu elle ele alınmalı (proje şu ana kadar hep additive migration yazdı, bkz.
`Mimari.md` Bölüm 6).
