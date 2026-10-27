# Uzak sunucuya Docker image deposu kullanmadan kurulum

Bu rehber Ubuntu 24.04 LTS üzerinde **backend ve frontend image'larını kaynak koddan sunucuda derler**. GHCR/Docker Hub'a uygulama image'ı yüklemez; `docker login`, PAT veya CI image yayını gerekmez. Docker ve Compose kullanılmaya devam eder. PostgreSQL, Redis, Kafka ve Dockerfile'lardaki Java/Node/nginx temel image'ları genel depolardan indirilir; Maven/npm bağımlılıkları da internetten alınır. Dolayısıyla bu yöntem tamamen çevrimdışı veya Docker'sız kurulum değildir.

Bu akışta yalnız kökteki `docker-compose.server.yml` çalıştırılır. Eski üç katmanlı production komutlarıyla karıştırmayın. Dosya altyapı servislerini `docker-compose.yml` üzerinden devralır; bu dosya da sunucuda bulunmalıdır. Mailpit çalıştırılmaz; gerçek SMTP kullanılır. Uygulama image'larında `pull_policy: never` vardır: image bulunmazsa depodan çekmek yerine kurulum hata verir.

Komutlar aksi belirtilmedikçe **sunucunun Bash terminalinde**, sudo yetkili kullanıcıyla çalıştırılır. `<SUNUCU_IP>`, `<GIT_REPO_URL>` ve `<COMMIT_SHA>` alanlarını gerçek değerlerle değiştirin. DNS örneği `app.example.com` yerine kendi alan adınızı kullanın.

## 1. Sunucu ve DNS hazırlığı

Başlangıç kapasitesi olarak 4 vCPU, 8 GB RAM ve en az 60 GB SSD ayırın; kaynak kod derlemesi çalışma yüküne ek bellek/disk ister. Bu bir kapasite garantisi değildir; logları ve disk kullanımını izleyin. Alan adının A kaydını sunucunun IPv4 adresine yönlendirin. AAAA kaydı varsa IPv6 adresinin de bu sunucuya eriştiğini doğrulayın.

İlk bağlantıyı bilgisayarınızdan yapın:

```bash
ssh <KULLANICI>@<SUNUCU_IP>
```

Sağlayıcı yalnız root veriyorsa root oturumunda `adduser deploy` ve `usermod -aG sudo deploy` çalıştırın; SSH anahtarınızı bu kullanıcıya kurup yeni oturumdan giriş yapabildiğinizi doğrulayın. Aşağıdaki işlemlere o kullanıcıyla devam edin.

```bash
sudo apt update
sudo apt install -y ca-certificates curl git gnupg openssl ufw
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo timedatectl set-ntp true
```

Özel SSH portu kullanıyorsanız güvenlik duvarını etkinleştirmeden o portu ayrıca açın. Sağlayıcının ağ güvenlik duvarında da SSH, 80 ve 443 erişimini ayarlayın. 3000, 8080, 5432, 6379 ve 9092 portlarını açmayın. Compose altyapı/backend portlarını yayınlamaz; frontend yalnız localhost'a bağlanır.

## 2. Docker ve Compose kurulumu

Temiz Ubuntu sunucusunda resmi Docker paket deposunu ekleyin:

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
. /etc/os-release
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $VERSION_CODENAME stable" | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
```

SSH oturumunu kapatıp yeniden bağlanın. Docker grubu sunucuda yönetici seviyesinde yetki sağlar; yalnız kurulum kullanıcısını ekleyin.

```bash
docker version
docker compose version
```

`!reset` desteği olan güncel Compose kullanın (en az 2.24.4). Sunucuda önceden farklı bir Docker kurulumu varsa paketleri üzerine kurmadan resmi [Ubuntu kurulum rehberindeki](https://docs.docker.com/engine/install/ubuntu/) çakışan paketler bölümünü izleyin.

## 3. Kaynak kodu sunucuya alın

```bash
sudo mkdir -p /opt/workfollowup
sudo chown "$USER:$(id -gn)" /opt/workfollowup
cd /opt/workfollowup
git clone <GIT_REPO_URL> repo
cd repo
git checkout <COMMIT_SHA>
chmod +x mvnw
```

Private Git reposu için SSH deploy anahtarı veya Git erişiminizi kullanabilirsiniz; bu yalnız kaynak kod erişimidir, image deposu erişimi değildir. Seçtiğiniz commit bu rehberi ve `docker-compose.server.yml` dosyasını içermelidir. Henüz Git'e alınmadıysa dosyaları aynı dizin yapısıyla ayrıca kopyalayın. Windows'tan dosya taşırsanız `mvnw` ve `docker/postgres-init/*.sh` satır sonları LF olmalıdır.

## 4. Ortam değerlerini hazırlayın

```bash
umask 077
cp deploy/.env.prod.example deploy/.env.prod
chmod 600 deploy/.env.prod
nano deploy/.env.prod
```

Şablondaki `GHCR_OWNER` satırını kaldırabilirsiniz; kullanılmaz. `IMAGE_TAG` için `git rev-parse HEAD` çıktısını yazın. Diğer alanlar:

| Alan | Değer |
| --- | --- |
| `DB_MIGRATOR_PASSWORD` | Ayrı güçlü veritabanı yönetim parolası |
| `DB_RUNTIME_PASSWORD` | Ayrı güçlü uygulama veritabanı parolası |
| `REDIS_PASSWORD` | Ayrı güçlü Redis parolası |
| `WEBHOOK_SECRET_MASTER_KEY` | En az 32 karakterlik rastgele anahtar |
| `APP_PUBLIC_URL` | `https://app.example.com` (sonunda `/` olmadan) |
| `SYSTEM_ADMIN_EMAILS` | Yönetici olacak e-posta; birden fazlası virgülle ayrılır |
| `SMTP_HOST`, `SMTP_USER`, `SMTP_PASSWORD` | Gerçek SMTP sağlayıcınızın değerleri |
| `SMTP_PORT` | STARTTLS için `587` |
| `SMTP_FROM` | Sağlayıcınızda doğrulanmış gönderici adresi |

İlk dört sır için **her seferinde yeni çıktı alarak** `openssl rand -hex 32` çalıştırın. Değerleri dosyaya yazın; çıktıları Git'e veya destek mesajlarına koymayın. SMTP parolasında `$`, `#` gibi karakterler varsa Compose env dosyasında tek tırnak kullanın. Bu kurulum SMTP AUTH + STARTTLS içindir; yalnız 465 implicit TLS sunan sağlayıcı için ayrıca mail yapılandırması gerekir.

## 5. JWT anahtarlarını oluşturun

```bash
mkdir -p deploy/jwt
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out deploy/jwt/private.pem
openssl pkey -in deploy/jwt/private.pem -pubout -out deploy/jwt/public.pem
```

Backend Dockerfile'ı root olmayan `app` kullanıcısıyla çalışır. Bind mount üzerindeki anahtarın bu kullanıcı tarafından okunabilmesi gerekir. Build'den sonra aşağıda UID/GID değerini image'dan okuyup izinleri ayarlayacağız. Anahtarları sonraki güncellemelerde yeniden üretmeyin.

## 6. Yapılandırmayı kontrol edin ve image'ları derleyin

Repo kökünde aşağıdaki Bash fonksiyonunu tanımlayın; yeni SSH oturumunda yeniden tanımlayın:

```bash
cd /opt/workfollowup/repo
dc() { docker compose --env-file deploy/.env.prod -f docker-compose.server.yml "$@"; }
dc config --quiet
dc build migrate frontend
```

`migrate` build'i backend image'ını da üretir; iki servis aynı etiketi kullanır. Java 21 ve Node 22 Dockerfile içinde sağlanır, sunucuya ayrıca kurulmaz. Backend Dockerfile'ı testleri atlar; bu komut başarılı olması testlerin geçtiği anlamına gelmez. Yayına alınacak commit'in proje testlerinin önceden geçmiş olması gerekir.

Anahtar izinlerini image'daki kullanıcıya göre ayarlayın:

```bash
APP_IMAGE="workfollowup-backend:$(git rev-parse HEAD)"
# IMAGE_TAG dosyada bu commit'in SHA değeri olmalıdır.
APP_UID=$(docker run --rm --entrypoint id "$APP_IMAGE" -u)
APP_GID=$(docker run --rm --entrypoint id "$APP_IMAGE" -g)
sudo chown "$APP_UID:$APP_GID" deploy/jwt deploy/jwt/*.pem
sudo chmod 700 deploy/jwt
sudo chmod 600 deploy/jwt/*.pem
docker run --rm --entrypoint sh -v "$PWD/deploy/jwt:/etc/tracker/jwt:ro" "$APP_IMAGE" -c 'test -r /etc/tracker/jwt/private.pem && test -r /etc/tracker/jwt/public.pem'
```

Son komutun çıkış kodu 0 olmalıdır. Hata varsa uygulamayı başlatmadan izinleri düzeltin.

## 7. Altyapıyı başlatın

```bash
dc up -d postgres redis kafka
dc ps
dc logs --tail=80 postgres redis kafka
```

Üç servisin de `healthy` olmasını bekleyin. PostgreSQL, Redis ve Kafka verileri kalıcı volume'lardadır. Bu Compose proje adı mevcut kurulumdan ayrıdır; eski bir kurulumun verilerini otomatik devralmaz. Rehber **boş sunucu/ilk kurulum** içindir. Var olan veritabanınız varsa önce yedekleyip kontrollü veri aktarımı yapın.

## 8. Veritabanı uygulama parolasını değiştirin

İlk PostgreSQL açılışında mevcut init script'i `app_runtime` rolünü geliştirme parolasıyla oluşturur. Backend'i başlatmadan bunu değiştirin:

```bash
dc exec postgres psql -U app_migrator -d tracker
```

psql içinde:

```text
\password app_runtime
```

İki kez **`.env.prod` dosyasındaki `DB_RUNTIME_PASSWORD` değerini** girin. Ardından:

```sql
REVOKE CREATE ON SCHEMA public FROM app_runtime;
DROP DATABASE IF EXISTS tracker_dev;
DROP DATABASE IF EXISTS tracker_staging;
DROP DATABASE IF EXISTS tracker_prod;
\q
```

DROP komutları yalnız bu yeni kurulumda init script'inin oluşturduğu boş geliştirme veritabanları içindir. Gerçek veri içeren mevcut sunucuda çalıştırmayın. `app_migrator` bootstrap süper kullanıcısıdır; backend bu hesapla çalıştırılmaz. Env dosyasında migrator parolasını değiştirmek mevcut PostgreSQL rol parolasını değiştirmez; init yalnız boş volume üzerinde çalışır.

## 9. Migration ve uygulamayı başlatın

```bash
dc run --rm --no-deps migrate
```

Migration'ın başarıyla tamamlanıp **çıkış kodu 0** verdiğini doğrulayın. Hata varsa devam etmeyin.

```bash
dc up -d --no-build backend frontend
dc logs --tail=100 backend frontend
curl --fail http://127.0.0.1:3000/actuator/health/readiness
```

Backend'in başlaması biraz sürebilir; readiness yanıtında `UP` bekleyin. Bu probe DB/Redis'i kapsar; ayrıca Kafka'nın `healthy` olduğunu kontrol edin. Uygulama image'ı bulunamadı hatası varsa `IMAGE_TAG` ve build adımını kontrol edin. Bu akış migration'ı her başlangıçta otomatik tekrarlamaz; her sürümde önce migration komutunu siz çalıştırırsınız.

## 10. Alan adı ve HTTPS

Host üzerinde Caddy kurun:

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo chmod o+r /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list
sudo apt update
sudo apt install -y caddy
sudo cp deploy/Caddyfile /etc/caddy/Caddyfile
sudo nano /etc/caddy/Caddyfile
```

Dosyadaki **iki** `app.example.com` değerini değiştirin: site adresi ve CSP içindeki `wss://` adresi. `APP_PUBLIC_URL` ile aynı domain'i kullanın.

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl enable --now caddy
sudo systemctl reload caddy
curl --fail -I https://app.example.com/
curl -I https://app.example.com/actuator/health/readiness
```

Ana sayfa 200, dışarıdan actuator isteği 404 vermelidir. TLS hatasında DNS A/AAAA kayıtlarını, sağlayıcı firewall'unu, 80/443 portlarını ve `sudo journalctl -u caddy -n 100 --no-pager` çıktısını kontrol edin.

Tarayıcıda kayıt, giriş, görev oluşturma, e-posta gönderimi ve WebSocket bildirimlerini deneyin. Gerçek kullanıcı verisiyle açılıştan önce mevcut [yayın öncesi kontrol listesini](security/04-YAYIN-ONCESI-SERTLESTIRME.md), [güvenlik testlerini](security/02-TEST-SENARYOLARI.md) ve `DEPLOYMENT.md` kapsamındaki KVKK hazırlıklarını tamamlayın.

## 11. Yedek ve bakım

Repo kökünde, `dc` fonksiyonu tanımlıyken:

```bash
umask 077
mkdir -p /opt/workfollowup/backups
set -o pipefail
dc exec -T postgres pg_dump -U app_migrator -d tracker -Fc > "/opt/workfollowup/backups/tracker-$(date +%Y%m%d-%H%M%S).dump"
```

Komutun çıkış kodunu kontrol edin; başarısız çıktıyı yedek kabul etmeyin. Günlük yedeklemeyi zamanlayın ve yedekleri sunucu dışına kopyalayın. `.env.prod`, JWT anahtarları ve Caddy ayarlarını ayrıca şifreli ve erişimi kısıtlı olarak saklayın. Bu dump Kafka/Redis verilerini kapsamaz. Kafka volume'u kalıcıdır ama tek broker kurulumu yüksek erişilebilirlik sağlamaz.

Yedeği üretim verisini ezmeden ayrı bir test veritabanında doğrulama örneği:

```bash
dc exec -T postgres createdb -U app_migrator tracker_restore_check
dc exec -T postgres pg_restore -U app_migrator -d tracker_restore_check --exit-on-error < /opt/workfollowup/backups/<YEDEK_DOSYASI>.dump
dc exec -T postgres psql -U app_migrator -d tracker_restore_check -c '\dt'
```

Test veritabanı adı zaten kullanılıyorsa başka bir ad seçin. Tablo listesini ve kritik kayıtları doğrulayın. Gerçek geri yükleme bakım penceresi ve uygulamanın durdurulmasını gerektirir; dump'ı doğrudan çalışan üretim DB'sine aktarmayın.

Loglar Compose dosyasında döndürülür. Rutin kontroller: `dc ps`, `dc logs --tail=100 backend`, `docker system df`, `df -h`. Normal durdurma için `dc stop` kullanın. **`dc down -v` kalıcı verileri siler; güncelleme veya yeniden başlatmada kullanmayın.**

## 12. Yeni sürüme geçiş ve geri dönüş

Önce 11. adımdaki veritabanı yedeğini alın, mevcut commit SHA'sını ve `IMAGE_TAG` değerini kaydedin. Eski image'ları geri dönüş süresi bitmeden silmeyin.

```bash
git fetch origin
git checkout <YENI_COMMIT_SHA>
chmod +x mvnw
nano deploy/.env.prod
# IMAGE_TAG değerini yeni commit'in tam SHA değeriyle değiştirin.
dc config --quiet
dc build migrate frontend
dc stop frontend backend
dc run --rm --no-deps migrate
# Yalnız migration başarıyla tamamlandıysa:
dc up -d --no-build backend frontend
curl --fail http://127.0.0.1:3000/actuator/health/readiness
```

Bu yöntem kısa bakım kesintisi içerir. Migration hatasında uygulamayı körlemesine yeniden açmayın; logları inceleyin. Geri dönüşte eski commit'i checkout edin, `IMAGE_TAG` değerini eski etikete döndürün ve `dc up -d --no-build backend frontend` çalıştırın. Bu yalnız eski uygulama mevcut şemayla uyumluysa güvenlidir. Flyway migration'ları otomatik geri alınmaz; uyumsuz şema değişiminde ileri düzeltme veya kontrollü DB geri yüklemesi gerekir.

## 13. Alan adı ve SMTP olmadan, yalnız VPN üzerinden kullanım

Bu senaryoda 1. adımdaki public DNS ve 10. adımdaki public domain/TLS ayarlarının yerine aşağıdaki ayarları kullanın. Diğer parola, JWT, build ve migration adımları geçerlidir. Örnek `10.8.0.1` adresini **sunucunun VPN'den erişilen sabit IP adresiyle** değiştirin. APP_PUBLIC_URL adı internete açık adres zorunluluğu getirmez; tarayıcının kullandığı temel adrestir.

`deploy/.env.prod` içine:

```dotenv
APP_PUBLIC_URL=https://10.8.0.1
SYSTEM_ADMIN_EMAILS=yonetici@example.com
SMTP_HOST=mailpit
SMTP_PORT=1025
SMTP_USER=unused
SMTP_PASSWORD=unused
SMTP_FROM=no-reply@tracker.local
```

`SYSTEM_ADMIN_EMAILS` için kendi kayıt olacağınız e-postayı yazın; yönetici istemiyorsanız boş bırakabilirsiniz. `unused` gerçek bir SMTP kimliği değildir: ana Compose dosyasının zorunlu değişken kontrolünü karşılar, VPN ek dosyası bunları boş değerlerle değiştirip SMTP kimlik doğrulamasını/TLS'ini kapatır. DB/Redis parolaları, webhook anahtarı ve JWT anahtarları yine gerçek ve güçlü olmalıdır.

**Bu bölümde ve sonraki tüm komutlarda** dc fonksiyonunu iki dosyayla tanımlayın (güncel Compose gerekir; `!override` için en az 2.24.4):

```bash
dc() { docker compose --env-file deploy/.env.prod -f docker-compose.server.yml -f docker-compose.vpn.yml "$@"; }
dc config --quiet
```

İlk kurulumda 6–9. adımları bu fonksiyonla uygulayın. Daha önce başlattıysanız:

```bash
dc up -d --no-build backend frontend mailpit
```

Mailpit gerçek kişilere e-posta göndermez; uygulamanın gönderdiği mesajları yakalar. Kayıt doğrulama, parola sıfırlama ve davet e-postaları burada görünür. Mevcut kodda giriş için e-posta doğrulaması zorunlu değildir. Mailpit arayüzünü arkadaş grubuna ortak açmak yerine yönetici kendi bilgisayarından SSH tüneli açabilir:

```bash
ssh -L 8025:127.0.0.1:8025 <KULLANICI>@<SUNUCU_VPN_IP>
```

Ardından bilgisayarınızda `http://localhost:8025` açın. Bu kutu tüm kullanıcıların doğrulama/sıfırlama bağlantılarını içerir; yalnız yönetici erişsin. Mesajlar kalıcı olarak saklanacak şekilde yapılandırılmamıştır; konteyner yeniden oluşturulunca kaybolabilir.

### VPN IP'si için HTTPS

Uygulama refresh cookie'sini `Secure` olarak üretir; düz HTTP kullanırsanız oturum yenileme çalışmaz. Caddy'yi 10. adımdaki gibi kurun fakat `/etc/caddy/Caddyfile` içeriğini aşağıdakiyle değiştirin:

```caddyfile
{
    auto_https disable_redirects
}

https://10.8.0.1 {
    bind 10.8.0.1
    tls internal
    encode gzip
    @actuator path /actuator /actuator/*
    respond @actuator 404
    reverse_proxy 127.0.0.1:3000
}
```

`bind` adresi sunucunun kendi ağ arayüzünde bulunmalıdır. VPN bir ağ geçidi üzerinden sunucunun LAN IP'sine yönlendiriyorsa burada ve APP_PUBLIC_URL'de o erişilebilir LAN IP'sini kullanın. Caddy başlarken VPN arayüzü hazır olmalıdır. VPN'siz/public IP üzerinden erişim açmayın; önceki genel rehberdeki herkese açık 80/443 firewall izinlerini bu kurulumda uygulamayın. Bunun yerine kullandığınız VPN arayüzü veya VPN kaynak ağı için 443 erişimine izin verin; SSH ve VPN'in kendi erişimini koruyun.

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl restart caddy
```

Caddy kendi yerel sertifika otoritesiyle IP'ye HTTPS sertifikası üretir. Her arkadaşınızın cihazına bu otoritenin **yalnız public kök sertifikası** güvenilir olarak kurulmalıdır; aksi halde tarayıcı sertifika hatası verir. Resmi systemd paketiyle varsayılan konum:

```text
/var/lib/caddy/.local/share/caddy/pki/authorities/local/root.crt
```

`root.crt` dosyasını güvenli şekilde kopyalayıp cihazın güvenilir kök sertifika deposuna ekleyin (örneğin Windows'ta geçerli kullanıcı için Güvenilen Kök Sertifika Yetkilileri). Firefox ayrı sertifika deposu kullanıyorsa ona da ekleyin. **`root.key` dahil hiçbir özel anahtarı paylaşmayın.** Tarayıcıyı yeniden açıp `https://10.8.0.1` üzerinden giriş ve sayfa yenilemesini test edin. VPN IP'si değişirse APP_PUBLIC_URL/Caddy ayarını birlikte güncelleyin.

Bu yaklaşımın sertifika davranışı [Caddy yerel HTTPS belgesinde](https://caddyserver.com/docs/automatic-https#local-https) açıklanır. VPN ek dosyası public üretim yerine küçük grup test kullanımına yöneliktir.

## Teknik kaynaklar ve doğrulama sınırı

Compose davranışları için [resmi servis referansı](https://docs.docker.com/reference/compose-file/services/), Caddy paket kurulumu için [resmi kurulum belgesi](https://caddyserver.com/docs/install#debian-ubuntu-raspbian) esas alınmıştır. Bu rehberdeki proje ayarları depodaki Dockerfile, uygulama YAML'ları ve init script'inden türetilmiştir. Yerel yapılandırma doğrulaması uzak sunucuda build, migration, SMTP veya uçtan uca çalışma testinin yerini tutmaz.
