03 — Manuel Test Kılavuzu (Sunucu Ortamı)
Bu kılavuz, 02 kataloğundaki kontrollerin kendi sunucunda elle tekrarlanması içindir. Ayrıntılı test
prosedürleri için her bölümde OWASP WSTG'nin ilgili bölümüne referans verilir. Bu doküman ortamı, araçları,
sırayı ve kanıt toplamayı tanımlar.

Yalnız sahip olduğun sistemlere. VPS/bulut sağlayıcının kabul edilebilir kullanım politikasını oku.
Aktif taramaları (ZAP active scan, DOS-*) gerçek kullanıcı yokken ya da ayrı bir staging kopyasında koş.

1. Hazırlık
1.1 Test makinesi araçları
Araç	Amaç	Kurulum
curl, jq	API çağrıları, yanıt inceleme	Paket yöneticisi
OWASP ZAP (Docker)	DAST: baseline ve API taraması	docker pull ghcr.io/zaproxy/zaproxy:stable
testssl.sh	TLS yapılandırması	docker pull drwetter/testssl.sh
nmap	Dışarıdan açık port doğrulaması (yalnız kendi sunucun)	Paket yöneticisi
trivy	İmaj, dosya sistemi ve yapılandırma taraması	docker pull aquasec/trivy
OSV-Scanner	Maven/npm bağımlılık açıkları	github.com/google/osv-scanner
gitleaks	Sır taraması	Mevcut CI; yerelde Docker imajı
psql	DB katalog denetimi	PostgreSQL istemcisi
Tarayıcı DevTools	Cookie, depolama, başlıklar, WebSocket çerçeveleri	—
Burp Suite Community (opsiyonel)	İstek inceleme/tekrar (WSTG prosedürleri için)	portswigger.net
1.2 Ortam değişkenleri (test makinesinde)
Kopyala
export BASE="https://<alan-adin>"        # nginx arkasındaki genel adres
export API="$BASE/api/v1"
export OUT="./sec-run-$(date +%Y%m%d)"     # kanıt klasörü
mkdir -p "$OUT"
1.3 Kanıt kuralı
Her kontrol için $OUT/<ID>.txt (komut + çıktı) veya ekran görüntüsü. Rapor (05) bu dosyalara atıf yapar.
Token ve parolaları kanıt dosyalarına yazmadan önce maskele (eyJ... → <token>).

2. Uygulama Sırası
Kopyala
§3 Fikstür  →  §4 Altyapı/TLS  →  §5 Yapılandırma/başlıklar  →  §6 DB katalog denetimi
→  §7 Kimlik/oturum  →  §8 Yetki ve kiracı izolasyonu  →  §9 Girdi/XSS  →  §10 WebSocket
→  §11 Dış entegrasyonlar  →  §12 ZAP taramaları  →  §13 Tedarik zinciri  →  §14 Temizlik
Önce pasif ve yapılandırma kontrolleri yapılır. Aktif taramalar en sona bırakılır, böylece bulunan
yapılandırma hataları taramayı gereksiz yere gürültülü yapmaz.

3. Fikstür (iki kiracı)
Normal API kullanımıyla kurulur: kayıt, e-posta doğrulama, login, workspace oluşturma, davet, proje, görev
vb. İş akışı docs/API_KULLANIM.md'deki uçtan uca örneği izler.

sec-test-a-admin@..., sec-test-a-manager@..., sec-test-a-dev@..., sec-test-a-viewer@... ve B için aynı dört kullanıcı + sec-test-nomember@... kaydedilir. Doğrulama e-postaları gerçek posta kutusundan (ya da staging'de Mailpit'ten) onaylanır.
A-admin ve B-admin kendi workspace'ini oluşturur, diğerlerini davet eder (rol başına bir kullanıcı).
Her workspace'te: 1 proje, 1 sprint, ≥3 görev (biri alt görevli, biri bağımlılıklı, biri onaylı), 1 yorum, 1 etiket, 1 toplantı, 1 hedef, 1 kayıtlı görünüm, 1 retro maddesi.
Tüm oluşturulan id'ler $OUT/fixture.json'a kaydedilir ({ "A": {...}, "B": {...} }). §8 bu dosyayı kullanır.
Bu adım bir kez script'e dönüştürülürse (scripts/security/fixture.sh) her koşumda tekrar
kullanılabilir. Script, TEN-01 otomasyon harness'inin de temelidir.

4. Altyapı ve TLS
Referans — OWASP WSTG v4.2: Configuration and Deployment Management Testing
(WSTG-CONF-01, WSTG-CONF-02), Testing for Weak Transport Layer Security (WSTG-CRYP-01).
Container ve host ayrıntıları için 02'deki CIS referansları da kullanılır.

Önce dış ağ görünümünü, sonra sunucudaki yapılandırmayı kontrol et. Kanıtta hedef IP, tarih, araç sürümü
ve kullanılan imaj digest'i bulunsun. CDN varsa genel adresin yanında yalnız sana ait origin IP'lerini
de kontrol et; CDN sonucunu origin sunucunun sonucu olarak raporlama.

Adım	Nasıl	Beklenen	Kontrol ID
4.1 Dış port görünümü	Sunucu ağının dışındaki bir makineden, sana ait IPv4 ve varsa IPv6 adreslerinde düşük hızlı TCP port taraması yap. Cloud firewall, host firewall ve Docker port yayınlarını karşılaştır.	Yalnız 80/443; SSH yalnız yönetim kaynaklarına açık. Postgres, Redis, Kafka, Mailpit ve 8080 dışarıya kapalı.	INF-01
4.2 Proxy zorunluluğu	Dışarıdan backend portunu dene; Compose ports/expose, bind adresi ve firewall kurallarını incele. Yönetim ağından erişim varsa ayrı belgele.	Genel istemci backend'e nginx'i atlayarak erişemez. Docker port yayını firewall politikasını boşa düşürmez.	INF-02
4.3 Parolalar ve iç servisler	Sırların değerlerini rapora almadan Postgres rolleri, Redis ACL/requirepass, SMTP kimliği ve Kafka listener ayarlarını incele. Güvenilir iç ağdan kimliksiz bağlantının davranışını doğrula.	Varsayılan parola yok; Redis kimlikli ve iç ağda. Kafka dışarıya açık değil; SASL/TLS kullanımı veya iç ağ sınırı belgeli.	INF-03, INF-04, INF-05
4.4 Posta ortamı	Prod Compose servislerini, portları ve SMTP hedefini incele. Staging Mailpit erişimini de sınırla.	Prod'da Mailpit yok; gerçek SMTP sağlayıcı kullanılıyor.	INF-06
4.5 Container sertleştirme	Compose/Dockerfile üzerinde trivy config çalıştır. Çalışan container'larda kullanıcı, read_only, cap_drop, privileged, host mount ve Docker socket erişimini incele.	Non-root çalışma; mümkün yerlerde salt-okunur FS; gereksiz capability ve ayrıcalıklı container yok. Yazılabilir dizin istisnaları gerekçeli.	INF-07
4.6 Host sertleştirme	Güncelleme politikası ve son uygulama tarihini, sshd -T etkin ayarlarını, SSH anahtar girişini, parola girişinin kapalılığını ve fail2ban durumunu incele.	04 kontrol listesi tamam; yönetim erişimi sınırlı. Güncelleme ve yeniden başlatma sorumluluğu tanımlı.	INF-08
4.7 K8s uygulanabilirliği	Dağıtım gerçekten K8s kullanıyorsa trivy config/kube-linter, securityContext, NetworkPolicy ve secret erişimlerini incele.	Kritik bulgu yok. Yalnız Compose kullanılıyorsa gerekçesiyle UYGULANAMAZ.	INF-09
4.8 TLS	Genel HTTPS adresini testssl.sh ile tara; HTTP yönlendirmesini, sertifika zincirini, hostname'i, süreyi, protokolleri ve şifre takımlarını incele.	HTTP→HTTPS; geçerli sertifika; yalnız TLS 1.2/1.3 ve güvenli şifre takımları.	CFG-01
4.9 İstemci IP güveni	Proxy'nin Forwarded/X-Forwarded-For üretme veya temizleme davranışını ve backend trusted-proxy ayarını incele. Kendi isteğinde sahte başlık gönder; uygulamanın belirlediği istemci IP'sini maskelenmiş logla karşılaştır.	Saldırganın eklediği keyfî adres istemci IP'si sayılmaz. Güven zinciri yalnız tanımlı proxy'lerden oluşur. Hız sınırı bu başlıklarla aşılamaz.	AUTH-06, INF-02
Örnek komutlar: Aşağıdaki IP yer tutucusunu yalnız kendi sunucunun adresiyle değiştir. Port taraması
yük testi değildir; kullanıcı yokken, sağlayıcı politikasına uygun düşük hızla çalıştır.

Kopyala
umask 077
chmod 700 "$OUT"

# Komutu ve çıktıyı aynı kanıt dosyasına yaz.
{
  printf '%s\n' 'nmap -Pn -sT -p- --max-rate 30 --max-retries 1 <kendi-sunucu-ip>'
  nmap -Pn -sT -p- --max-rate 30 --max-retries 1 "<kendi-sunucu-ip>"
} > "$OUT/INF-01.txt" 2>&1

# IPv6 kullanılıyorsa ayrıca nmap -6 ile aynı denetimi yap.
# Filtrelenmiş/yanıtsız sonucu yerel firewall ve yayın ayarlarıyla destekle.

{
  printf 'curl -sS -I --max-time 15 %s\n' "${BASE/https:/http:}"
  curl -sS -I --max-time 15 "${BASE/https:/http:}"
  printf 'testssl.sh %s\n' "$BASE"
  docker run --rm drwetter/testssl.sh "$BASE"
} > "$OUT/CFG-01.txt" 2>&1
testssl.sh çıktısını otomatik olarak “A derecesi” diye yorumlama; araç bu derecelendirmeyi üretmez.
02'deki derece beklentisi kullanılacaksa derecelendirme yöntemi ayrıca belirtilir. TLS bulgularını
protokol, sertifika ve şifre takımı bazında kaydet.

5. Yapılandırma ve Güvenlik Başlıkları
Referans — OWASP WSTG v4.2: WSTG-CONF-02, WSTG-CONF-05, WSTG-CONF-07
(HSTS), WSTG-CONF-12 (CSP), WSTG-INFO-02, WSTG-ERRH-01, WSTG-CLNT-07
(CORS), WSTG-CLNT-09 (Clickjacking).

Başlıkları yalnız ana sayfada değil; SPA rotası, API yanıtı ve hata yanıtlarında da kontrol et.
Kimlikli yanıtların Set-Cookie gibi alanlarını §1.3'e göre maskele.

| Adım | Nasıl | Beklenen | Kontrol ID |
|---|---|---|---|---|
| 5.1 Güvenlik başlıkları | DevTools/curl ile HTML, API ve hata yanıtlarını incele; §12 baseline ile destekle. CSP'nin yalnız Report-Only olmadığını, geniş kaynak izinlerini ve inline/eval kullanımını değerlendir. | Etkin CSP, HSTS, X-Content-Type-Options: nosniff, Referrer-Policy ve frame-ancestors mevcut. Uygulanabilir başlık eksikleri yok. | FE-01 |
| 5.2 Clickjacking | Sana ait farklı origin'deki basit sayfada uygulamayı iframe içinde açmayı dene. | CSP frame-ancestors 'none'; tarayıcı çerçevelemeyi engeller. | FE-02 |
| 5.3 Actuator | Dışarıdan /actuator/health, /actuator/info, /actuator/env, /actuator/heapdump, /actuator/configprops, /actuator/mappings yollarını kontrol et. Etkin Spring ve nginx yapılandırmasını karşılaştır. | Backend'de yalnız health/info açık. Genel ağa yalnız gereken probe sunulur; hassas endpoint'ler kapalı. Sağlık/bilgi yanıtında iç ayrıntı yok. | CFG-02 |
| 5.4 Swagger/OpenAPI | Kimliksiz /v3/api-docs, Swagger UI ve yapılandırılmış doküman rotalarını kontrol et. SPA fallback'in döndürdüğü 200 HTML'yi API dokümanı sanma. | Prod'da 404/401 veya belgeli kimlikli erişim. §12 için dışarıya açmak yerine iç ağdan dışa aktarım yapılır. | CFG-03 |
| 5.5 Hata ve sürüm ifşası | Bulunmayan yol, bozuk JSON, geçersiz parametre ve yetkisiz isteği gönder. Server/X-Powered-By başlıklarını ve ProblemDetail alanlarını incele. | Stack trace, sürüm, SQL ve iç dosya yolu yok. Uygulama hataları ProblemDetail; validasyon 422. Nginx hatalarında da iç bilgi yok. | CFG-04, CFG-05 |
| 5.6 CORS | İzinli ve izinsiz origin için gerçek API rotasında preflight ve normal istek gönder; tarayıcıyla da doğrula. | İzinli origin çalışır; yabancı origin'e CORS izni verilmez. Credentials ile * yok; keyfî Origin yansıtılmaz. | CFG-06 |
| 5.7 Prod guard'ları | JUnit guard testlerini çalıştır. Manuel spot-check'i yalnız izole container'da, prod profiliyle eksik/dev JWT anahtarı, webhook master key ve public URL kullanarak yap. | Uygulama güvenli olmayan varsayılanlarla başlamaz; sır değeri hata mesajına yazılmaz. | CFG-07 |
| 5.8 Sır kaynağı | Secret/env referanslarını, bind mount'ları, image build arg'larını ve CI secret kullanımını incele; §13 gitleaks ve image taramasına bağla. Ham docker inspect veya genişletilmiş Compose çıktısını kanıta koyma. | Sırlar repo/image katmanlarında yok; secret/env üzerinden sağlanıyor ve erişim kısıtlı. | CFG-08 |

CORS'ta preflight'in 200/204 dönmesi tek başına başarısızlık değildir: tarayıcının erişimine izin
veren başlıklar değerlendirilir. CORS, kimlik doğrulama veya CSRF korumasının yerine geçmez.

Kopyala
# OPENAPI'den belirlediğin gerçek bir liste rotası; örnek değer değildir.
CORS_PATH="<gercek-liste-yolu>"

{
  printf '%s\n' \
    'OPTIONS <API>/<liste-yolu>, Origin: https://sec-test-origin.invalid'
  curl -sS -i --max-time 15 -X OPTIONS "$API/$CORS_PATH" \
    -H 'Origin: https://sec-test-origin.invalid' \
    -H 'Access-Control-Request-Method: GET' \
    -H 'Access-Control-Request-Headers: authorization,x-workspace-id'
} > "$OUT/CFG-06.txt" 2>&1
6. DB Katalog Denetimi
Referans — OWASP WSTG v4.2: WSTG-CONF-02, WSTG-ATHZ-02,
WSTG-ATHZ-03, WSTG-CRYP-03. PostgreSQL RLS, rol ve SECURITY DEFINER ayrıntıları
için 02'deki PostgreSQL/mimari referansları esas alınır; WSTG doğrudan bir RLS katalog testi tanımlamaz.

DB portunu internete açma. psql bağlantısını sunucudan veya kısıtlı yönetim tünelinden kur.
Parolayı komut satırına/kanıta koyma; parola istemi veya izinleri 0600 olan .pgpass kullan.
Katalog incelemesi yönetim hesabıyla yapılabilir; davranış testleri gerçekten app_runtime rolüyle
ve yeni bağlantıyla yapılır.

| Adım | Nasıl | Beklenen | Kontrol ID |
|---|---|---|---|---|
| 6.1 RLS envanteri | JUnit katalog testiyle ve aşağıdaki sorguyla workspace_id, RLS/FORCE, sahip ve partition durumlarını çıkar. Tüm uygulama şemalarını kapsa. | workspace_id taşıyan uygulama tablolarında RLS+FORCE; yalnız aşağıdaki açık istisnalar kabul edilir. Yeni istisna CI'da testi kırar. | DB-01 |
| 6.2 Fail-closed ve kiracı davranışı | JUnit ile doğrulanır. Manuelde yeni app_runtime bağlantısında bağlam yokken, sonra boş bağlamla, ardından A ve B bağlamıyla fikstür satırlarını sorgula. Ayrı testte B satırına A bağlamında yazmayı dene; tüm yazma denemelerini rollback et. | Bağlam yok/boşken satır yok; A yalnız A'yı görür. Başka kiracıya yazma politika tarafından engellenir. Hata güvenli olsa bile “0 satır” beklentisinden sapma raporlanır. | DB-02 |
| 6.3 Runtime rolü | pg_roles, tablo sahipliği, rol üyelikleri ve SET ROLE ile erişilebilen roller incelenir. | app_runtime NOSUPERUSER/NOBYPASSRLS; tablo sahibi değil; app_migrator veya eşdeğer ayrıcalıklı role geçemiyor. | DB-03 |
| 6.4 Şema ve append-only yetkileri | Etkin şema yetkilerini ve mimaride yalnız ekleme için ayrılmış tabloların tablo/sütun ACL'lerini incele. PUBLIC ve rol üyeliklerinden gelen hakları da kapsa. | Uygulama şemasında CREATE yok; gerekçesiz UPDATE/DELETE hakkı yok. TEMP ve güvenli search_path ilişkisi ayrıca incelenmiş. | DB-04, DB-05 |
| 6.5 SECURITY DEFINER | ensure_task_events_partitions ve resolve_webhook_integration dahil tüm definer fonksiyonları listele. Sahip, fonksiyon gövdesi, sabit search_path ve EXECUTE ACL'lerini incele. | PUBLIC EXECUTE kaldırılmış; yalnız gerekli roller çalıştırabilir. Yazılabilir şema güven zincirine girmiyor; pg_temp varsa en sonda. Yetki yükseltme yüzeyi gerekçeli. | DB-06 |
| 6.6 Partition erişimi | JUnit ile doğrulanır. Manuelde bir gerçek child partition'a app_runtime ile doğrudan SELECT dene; parent üzerinden normal erişimin sürdüğünü doğrula. | Child partition doğrudan sorgulanamaz. Yeni partition'lar da aynı yetki politikasını alır. | DB-07 |
| 6.7 Hassas saklama | Yalnız fikstür hesaplarında hash/şifreli alanların biçimini ve üretim kodunu incele; tam değerleri dışa aktarma. | Parola bcrypt; rastgele token'lar SHA-256; Slack webhook adresi AES-GCM. Anahtar DB'den ayrı; nonce kullanımı güvenli. | DB-08 |
| 6.8 DB TLS | JDBC/psql etkin ayarını ve uygulama bağlantılarının pg_stat_ssl durumunu incele. | sslmode=verify-full; aksi durumda aynı host/özel ağ istisnası risk kabulüyle belgeli. Yalnız ssl=true hostname doğrulamasının kanıtı sayılmaz. | DB-09 |
| 6.9 Yedek geri yükleme | Şifreleme, anahtar erişimi, saklama ve silme politikasını incele. Bir yedeği izole, dış entegrasyonları kapalı ortama geri yükle; kayıt bütünlüğünü kontrol et. | Şifreli yedek ve başarılı geri yükleme kanıtı; tarih ve sorumlu belli. | DB-10 |
| 6.10 Zaman aşımı ve havuz | Runtime bağlantısında SHOW statement_timeout; bağlantı/transaction override'ları ve Hikari havuz sınırını incele. | Etkin değerler bilinçli ve belgeli; sınırsız pahalı sorgu riski değerlendirilmiş. Yavaş sorgu spot-check'i yalnız staging'de. | DOS-05 |

Beklenen istisna listesi:

RLS'siz: users, workspaces, workspace_users, refresh_tokens, verification_tokens, workspace_invitations, notification_preferences, outbox_events, processed_events, task_counters.
webhook_integrations: RLS açık, FORCE kapalı; bilinçli istisna ve gerekçesi raporda.
Partition'lar ayrıca DB-07 ile değerlendirilir; parent politikasının doğrudan child erişimini kendiliğinden koruduğu varsayılmaz.
İstisna, servis katmanı izolasyonunu kaldırmaz; §8 ve §11 ile doğrulanır.
Katalog sorguları: Örnekte uygulama şeması public kabul edilmiştir. Gerçek şema listesini
migration'larla karşılaştır; birden fazla şema varsa hepsinde çalıştır. Her sorguyu ilgili
$OUT/DB-xx.txt dosyasına, sorgu metniyle birlikte kaydet (psql -X -a -v ON_ERROR_STOP=1).

Kopyala
-- DB-01: public yerine gerçek uygulama şeması.
SELECT n.nspname AS schema_name, c.relname, c.relkind,
       c.relispartition, c.relrowsecurity, c.relforcerowsecurity,
       pg_get_userbyid(c.relowner) AS owner,
       EXISTS (
         SELECT 1 FROM pg_attribute a
         WHERE a.attrelid = c.oid AND a.attname = 'workspace_id'
           AND a.attnum > 0 AND NOT a.attisdropped
       ) AS has_workspace_id
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
ORDER BY c.relname;

-- DB-02: USING ve WITH CHECK ifadelerini birlikte incele.
SELECT schemaname, tablename, policyname, roles, cmd, qual, with_check
FROM pg_policies
WHERE schemaname = 'public'
ORDER BY tablename, policyname;

-- DB-03: etkin rol nitelikleri ve doğrudan üyelikler.
SELECT rolname, rolsuper, rolbypassrls, rolcreaterole, rolcreatedb, rolinherit
FROM pg_roles
WHERE rolname IN ('app_runtime', 'app_migrator');

SELECT member_role.rolname AS member,
       granted_role.rolname AS granted_role,
       m.admin_option, m.inherit_option, m.set_option
FROM pg_auth_members m
JOIN pg_roles member_role ON member_role.oid = m.member
JOIN pg_roles granted_role ON granted_role.oid = m.roleid
ORDER BY member, granted_role;
-- Üyelik zincirlerini de takip et; yalnız doğrudan üyelikle yetinme.

-- DB-04
SELECT nspname,
       has_schema_privilege('app_runtime', oid, 'USAGE') AS can_use,
       has_schema_privilege('app_runtime', oid, 'CREATE') AS can_create
FROM pg_namespace
WHERE nspname = 'public';

-- DB-05 / DB-07: append-only listesi ve partition envanteriyle karşılaştır.
SELECT n.nspname, c.relname, c.relispartition,
       has_table_privilege('app_runtime', c.oid, 'SELECT') AS can_select,
       has_table_privilege('app_runtime', c.oid, 'INSERT') AS can_insert,
       has_table_privilege('app_runtime', c.oid, 'UPDATE') AS can_update,
       has_any_column_privilege('app_runtime', c.oid, 'UPDATE') AS can_update_column,
       has_table_privilege('app_runtime', c.oid, 'DELETE') AS can_delete,
       has_table_privilege('app_runtime', c.oid, 'TRUNCATE') AS can_truncate
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
ORDER BY c.relname;

-- DB-06: NULL proacl, varsayılan PUBLIC EXECUTE hakkını gizlememeli.
SELECT n.nspname, p.oid::regprocedure AS function_signature,
       pg_get_userbyid(p.proowner) AS owner, p.proconfig,
       EXISTS (
         SELECT 1
         FROM aclexplode(
           COALESCE(p.proacl, acldefault('f', p.proowner))
         ) a
         WHERE a.grantee = 0 AND a.privilege_type = 'EXECUTE'
       ) AS public_can_execute,
       has_function_privilege('app_runtime', p.oid, 'EXECUTE')
         AS runtime_can_execute
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE p.prosecdef
  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
ORDER BY n.nspname, function_signature;
Bağlam GUC'sinin adını migration/politika kodundan al; tahmin etme. set_config kullanılıyorsa üçüncü
argüman true ile transaction-local davranışı doğrula. Başarısız SQL transaction'ından sonra
ROLLBACK yap; yönetici bağlantısındaki sonucu runtime kanıtı olarak kullanma.

7. Kimlik Doğrulama ve Oturum
Referans — OWASP WSTG v4.2: Identity Management (WSTG-IDNT-02, WSTG-IDNT-04),
Authentication (WSTG-ATHN-03, WSTG-ATHN-07, WSTG-ATHN-09),
Session Management (WSTG-SESS-02, WSTG-SESS-05, WSTG-SESS-06,
WSTG-SESS-07, WSTG-SESS-10).

Gerçek uç adlarını OpenAPI ve docs/API_KULLANIM.md üzerinden al. Token'ları çevrimiçi JWT
çözücülerine gönderme. Süre, yarış durumu ve çok sayıda bozuk girdi için JUnit esastır;
manuel adımlar fikstür hesaplarında spot-check'tir.

Aktif denemeler ve DOS kontrolleri yalnız kullanıcı yokken / staging'de. Kilitleme ve hız
sınırı eşiklerini önce yapılandırmadan öğren. Paralel yük üretme; eşik ve bir ek istek yeterlidir.
Genel 5xx artışı, kuyruk büyümesi veya beklenmeyen e-posta hacminde dur.

| Adım | Nasıl | Beklenen | Kontrol ID |
|---|---|---|---|---|
| 7.1 Parola politikası | JUnit ile doğrulanır. Manuelde 11 ve 12 karakterli, diğer kuralları sağlayan iki parola dene; sızmış parola kontrolü kararını incele. | 12 altı 422; kabul sınırı çalışır. Sızmış parola kontrolü/istisnası raporda. | AUTH-01 |
| 7.2 Kilitleme | Test hesabında yapılandırılmış eşik kadar yanlış giriş, sonra doğru parola dene. Kilit sonrasında kontrollü tekrar yap; üstel gecikme testini JUnit'ten bağla. | Hesap bazlı kilit ve gecikme; doğru parola kilit süresince reddedilir. Süre bitiminde normal giriş döner. | AUTH-02 |
| 7.3 Hesap varlığı | Kayıt/sıfırlamada var olan ve olmayan fikstür e-postasının durum, gövde, başlık ve yönlendirmelerini karşılaştır. Login sürelerini düşük hızda, sıralamayı karıştırarak ölç. | Ayırt edilebilir hesap ifşası yok; süre ölçüm yöntemi, örnek sayısı ve dağılımı raporda. Az sayıda ölçüm kesin kanıt sayılmaz. | AUTH-03, AUTH-05 |
| 7.4 E-posta tekilliği | JUnit ile doğrulanır. Aynı e-postanın büyük/küçük harf varyantıyla kayıt ve davet akışını dene. Yarış durumu DB unique kısıtı/JUnit ile kapsanır. | İkinci hesap açılamaz; normalize kimlik tutarlı. Sağlayıcıya özgü nokta/+ dönüşümleri varsayılmaz. | AUTH-04 |
| 7.5 Sıfırlama token'ı | JUnit'te süreyi kontrollü ilerlet; manuelde token'ı bir kez kullanıp tekrar dene. DB'de yalnız hash saklandığını §6 ile bağla. | Tek kullanımlık; 30 dk sonrası ret; düz token DB'de yok. | AUTH-07 |
| 7.6 Parola değişimi ve bildirim | Önceden iki oturum aç; parola değiştir/sıfırla. Eski refresh aileleriyle yenileme dene; staging Mailpit/posta kutusunu kontrol et. | Eski refresh aileleri geçersiz; güvenlik bildirimi gelir. Eski access token'ın kalan ömrü/revokasyon davranışı ayrıca kaydedilir. | AUTH-08, AUTH-10 |
| 7.7 E-posta doğrulaması | JUnit ile 24 saat sınırı ve yarış durumu; manuelde aynı doğrulama token'ını iki kez kullan. | İkinci kullanım ve süre aşımı reddedilir. | AUTH-09 |
| 7.8 JWT doğrulama | JUnit ile imzasız, alg=none, yanlış anahtar/algoritma, değiştirilmiş payload, süresi dolmuş ve bozuk biçimli token'ları doğrula. Manuelde birkaç örneği korumalı bir GET'e gönder. | Hepsi 401; 500 ve stack trace yok. Kabul edilen algoritma sunucuda RS256'ya sabit; uyumsuz alg reddedilir. | TOK-01, TOK-02, TOK-04 |
| 7.9 Logout | Geçerli access token ile korumalı GET yap; logout sonrası aynı token'ı yeniden kullan. | Aynı access token 401; kara liste kaydı ömür politikasına uygun. | TOK-03 |
| 7.10 Refresh rotasyonu | İlk refresh ile yeni cookie al; eski değeri grace süresi dışında tekrar kullan, sonra ailenin en yeni token'ını dene. Eşzamanlı/grace sınırlarını JUnit ile doğrula. | Eski token'ın tekrar kullanımı aileyi iptal eder; yeni token da artık kullanılamaz. Grace davranışı ve sınırı belgeli. | TOK-05 |
| 7.11 Cookie ve tarayıcı saklama | HTTPS altında refresh Set-Cookie özniteliklerini ve logout'taki silme cookie'sinin path/domain eşleşmesini incele. Login, refresh ve reload sonrasında DevTools depolamaya bak. | HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; access/refresh token localStorage/sessionStorage'da yok. | TOK-06, TOK-10 |
| 7.12 CSRF | Sana ait yabancı origin'deki sayfadan gerçek tarayıcıyla refresh/logout tetiklemeyi dene. Desteklenen yöntem/content-type kombinasyonlarını ve varsa aynı-site farklı-origin yüzeyini incele. | Kullanıcının oturumu yabancı origin'den yenilenemez/sonlandırılamaz. Yalnız CORS yanıtını okuyamamak yeterli kanıt değildir. | TOK-07 |
| 7.13 Ortam ve payload | Anahtarların ortam bazında ayrıldığını parmak iziyle doğrula. Staging token'ını yalnız sana ait diğer ortamın korumalı ucunda dene. Payload'ı yerelde çöz. | Ortamlar arası token 401. Payload yalnız sub, roles, jti, iat, exp; iss/aud kararı ve whitelist ile olası uyumsuzluk raporda. | TOK-08, TOK-09 |
| 7.14 Hız sınırı ve posta kotası | Login, kayıt, sıfırlama ve davet için eşik+1 isteği kontrollü gönder. Aynı alıcıya farklı test kaynaklarıyla gönderimleri say; uygulama günlükleriyle doğrula. | Eşik üstü 429; alıcı başına kota var. IP başlığı değiştirmek limiti aşmaz. E-posta teslim sayısı sınırı geçmez. | DOS-01, DOS-02 |
| 7.15 Güvenlik olayları | Başarısız login, kilitleme, refresh reuse ve test 403 dizisinin loglarını bul. Test penceresi ve korelasyon bilgilerini kaydet. | Olay türü, zaman ve biliniyorsa kullanıcı kimliği var; bilinmeyen hesap için sahte kullanıcı ID'si üretilmez. Parola/token/cookie yok. | LOG-01, LOG-02 |

Token gereken komutlarda set -x, curl -v ve ham --trace kullanma. Çalışan isteklerin gövde ve
başlıkları sır içerebilir; önce erişimi kısıtlı geçici alanda incele, sonra maskelenmiş kanıtı yaz.
Kanıtta komutun Authorization: Bearer <token> biçimindeki temsili bulunur.

8. Yetkilendirme ve Kiracı İzolasyonu
Referans — OWASP WSTG v4.2: Authorization Testing (WSTG-ATHZ-02,
WSTG-ATHZ-03, WSTG-ATHZ-04), Business Logic Testing
(WSTG-BUSL-01, WSTG-BUSL-02, WSTG-BUSL-05, WSTG-BUSL-06).

$OUT/fixture.json içindeki gerçek kimlikleri kullan. Her negatif denemeden önce aynı kaynağın
sahibiyle başarılı pozitif kontrol yap; bulunmayan kaynağa gelen 404 izolasyon kanıtı değildir.
Test sonunda yalnız HTTP durumunu değil, DB/olay/yan etki durumunu da kontrol et.

| Adım | Nasıl | Beklenen | Kontrol ID |
|---|---|---|---|---|
| 8.1 Rol matrisi ve kapsamı | HttpAuthorizationIntegrationTest ve OpenAPI–matris karşılaştırma testini çalıştır. Manuelde her rol için temsilî okuma/yazma/yönetim ucu dene. Listeyi HTTP yöntem + normalize rota olarak karşılaştır; public uçlar açık sınıflandırılır. | Rol matrisi uygulanır; yeni veya matrissiz uç CI'ı kırar. OpenAPI dışında kalan controller'lar ayrıca envantere alınır. | AUTHZ-01, AUTHZ-02 |
| 8.2 Mass assignment | JUnit ile doğrulanır. Fikstür oluşturma/güncelleme isteğine API alanlarına uygun role, workspaceId, ownerId gibi sunucunun yönetmesi gereken alanları ekle. Son durumu oku. | Fazladan alanlar yok sayılır; rol/sahip/kiracı değişmez. Sıkı şema nedeniyle 4xx varsa 02'deki beklentiden sapma not edilir. | AUTHZ-03 |
| 8.3 Kişisel kaynaklar | Aynı kiracıdaki başka kullanıcının kayıtlı görünümünü, yorumunu ve retro maddesini oku/değiştir. Kaynak türünün gerçek sahiplik politikasını esas al. | Sahipliğe özel işlemler 403/404; yan etki yok. | AUTHZ-04 |
| 8.4 Onaylı görev ve API kuralları | Onaylı görevi tekil ve bulk uçla değiştirmeyi, geçersiz tarih/durum geçişini doğrudan API'den dene. | UI atlanınca da kural geçerli; bulk tümüyle reddedilir/rollback olur. Katalogdaki 400 beklentisi ile validasyon 422 ayrımı belgeli. | AUTHZ-05, BL-08 |
| 8.5 Sistem yönetimi | Fikstüre yalnız bu test için geçici SYSTEM_ADMIN ekle; ADMIN dahil diğer rollerle sistem yönetimi uçlarını dene. | Yalnız SYSTEM_ADMIN erişir; diğer roller 403. SYSTEM_ADMIN pozitif kontrolü başarılı. | AUTHZ-06 |
| 8.6 Yetki geri alma | Oturumu açık kullanıcının workspace rolünü düşür/üyeliğini kaldır; aynı token ile yeniden istekte bulun. Global rol değişimini ayrı ölç. | Workspace değişikliği anında etkili; global rol en geç 15 dk access TTL içinde etkili. Ölçülen süre raporda. | AUTHZ-07 |
| 8.7 Otomasyon yetkisi | JUnit ile kural yönetimi rol matrisini ve çalıştırılan eylemin yetkisini doğrula. Manuelde düşük rolle kural yönetimini ve kapsam dışı kaynak hedeflemeyi dene. | Kural yönetimi ADMIN/MANAGER; kullanıcı kendi yetkisini aşan işlem yaptıramaz. Sistem aktörü tekrar tetikleme döngüsüne girmez. | AUTHZ-08 |
| 8.8 ID üzerinden BOLA | Harness/JUnit tüm ID'li uçlarda çalışır. Manuelde A token + A workspace ile B'nin her kaynak türünden temsilî ID'sini GET/PATCH/DELETE gibi desteklenen yöntemlerle dene; A/B yönlerini ters çevir. | Çapraz kiracı kaynak erişimi 404; bilgi veya yan etki yok. Önce sahip kullanıcının erişebildiği doğrulanmış. | TEN-01 |
| 8.9 Workspace seçimi | A kullanıcısı + B workspace başlığı; üyeliği olmayan kullanıcı + A workspace; eksik, boş, bozuk ve birden fazla başlık senaryolarını test et. | Üye olunmayan workspace 403; geçersiz başlık 400/403, 500 değil. Eksik/çoklu başlık politikası açık ve güvenli. | TEN-02, TEN-06 |
| 8.10 Koleksiyon izolasyonu | Liste, arama, rapor, tahmin ve analitik uçlarında B'ye özgü işaretli metinlerle sorgu yap; pagination'ın tüm sayfalarını ve aggregate değerlerini karşılaştır. | A yanıtında B kaydı, sayısı veya türetilmiş verisi yok. | TEN-03 |
| 8.11 Gövde içindeki ilişkiler | A kaynağını B'nin parent, dependency, label, project veya bulk ID'sine bağlamayı dene. Karışık A/B bulk listesi gönder. | İstek tümüyle reddedilir; A tarafında bile kısmi değişiklik/olay yok. | TEN-04 |
| 8.12 RLS istisnaları | Üye, davet, tercih ve diğer RLS'siz kaynakları servis testleriyle doğrula. Manuelde B'nin üye/davet kaydına A bağlamından erişimi dene. Global users kimliğini tenant kaynağı gibi varsayma; ilgili görünürlük politikasını test et. | RLS yokluğu izolasyon açığı oluşturmuyor; tenant'a bağlı veri servis katmanında korunuyor. | TEN-05 |
| 8.13 Arka plan ve bağlantı havuzu | JUnit/Testcontainers ile A/B olaylarını sırayla ve eşzamanlı işlet; aynı fiziksel DB bağlantısının yeniden kullanımını kanıtla. Exception/rollback ve bağlam temizliğini de kapsa. Manuelde A/B isteklerini sırayla gönderip olay sonuçlarını kontrol et. | Kafka/arka plan etkisi doğru kiracıda; SET LOCAL transaction sonrasında sızmıyor. Yalnız farklı istek göndermek aynı bağlantı testinin yerine geçmez. | TEN-07, TEN-08 |
| 8.14 Davet yaşam döngüsü | JUnit ile 7 gün sınırı ve eşzamanlı kabul; manuelde kullanılmış/iptal edilmiş token'ı yeniden dene. Farklı e-posta ve doğrulanmamış hesapla kabulü test et. | Tek kullanımlık, 7 gün; iptal/kullanım sonrası ret; farklı hesap 400. Doğrulanmış e-posta şartı kararı açık; kontrol başlığıyla çelişki risk olarak raporlanır. | BL-01, BL-02, BL-03 |
| 8.15 Son yönetici | Son ADMIN'in kendini düşürmesini/çıkarmasını dene; eşzamanlı iki yönetici değişikliğini JUnit ile doğrula. | 400; workspace sahipsiz kalmaz. | BL-04 |
| 8.16 Idempotency izolasyonu | Aynı anahtarı aynı kullanıcı/aynı istekle, farklı gövdeyle, başka kullanıcıyla ve başka workspace'te dene. Login/refresh yanıtlarının önbelleğe alınmadığını incele. | Yanıt başka kullanıcı/kiracıya verilmez; farklı istek eski sonuçla karışmaz. Login/refresh cache dışında; token'lı yanıt sızmaz. | BL-05, BL-06 |
| 8.17 Bulk atomiklik ve limit | JUnit ile 100/101 sınırı ve ortadaki hatada rollback doğrulanır. Manuelde küçük, bir öğesi geçersiz bulk isteğinin önce/sonra durumunu karşılaştır. | En fazla 100; hata tüm işlemi geri alır. Kalıcı event/outbox yan etkisi de yok. | BL-07 |

Fikstür kullanımı: Dosyanın alan adları §3'te üretilen şemaya bağlıdır; bilinmeyen bir JSON
şeması varsayarak yanlış ID ile test yapma.

Kopyala
# Token/parola içermeyen fikstür envanterini kontrol et.
jq 'keys' "$OUT/fixture.json"

# Gerçek fixture şemasına göre A workspace ve B kaynak ID'sini seç.
# Her kanıtta şu bağlamı yaz:
# actor=A-viewer, workspace=A, resource=B/<tip>/<id>,
# owner-positive-control=<başarılı durum>, result=<durum>, side-effect=<yok/var>
9. Girdi İşleme ve XSS
Referans — OWASP WSTG v4.2: Input Validation Testing
(WSTG-INPV-01, WSTG-INPV-02, WSTG-INPV-05, WSTG-INPV-10),
WSTG-CLNT-01, WSTG-CLNT-04, Business Logic Testing
(WSTG-BUSL-01, WSTG-BUSL-05).

Zararsız işaretleyiciler ve küçük örnekler kullan. Saklanan girdileri UI, e-posta, Slack ve log gibi
tüm çıkışlarda değerlendir; yalnız API yanıtına bakma.

Aktif enjeksiyon taraması ve DOS kontrolleri yalnız kullanıcı yokken / staging'de.
Büyük gövde testini yapılandırılmış sınırın hemen üstünde tut; sınırsız boyut/derinlik veya
paralel istek üretme. Otomatik aktif tarama §12'de yapılır.

| Adım | Nasıl | Beklenen | Kontrol ID |
|---|---|---|---|---|
| 9.1 SQL parametreleme | CodeQL/find-sec-bugs çıktısını ve native SQL/JDBC çağrılarını incele. Kullanıcıdan gelen sıralama/sütun/filtre seçimlerinin allow-list eşlemesini kontrol et. | Değerler bind parametresi; identifier'lar yalnız sabit allow-list'ten. Kullanıcı girdisi SQL metnine birleştirilmiyor. | INJ-01 |
| 9.2 Dinamik yardımcılar | TaskDependencyRepository ve PeriodReportRepository sorgu üretimini gözden geçir; testlerde üretilen SQL biçimini doğrula. | Yalnız ?n yer tutucuları ve sabit sütunlar üretiliyor; keyfî SQL parçası kabul edilmiyor. | INJ-02 |
| 9.3 Kimlikli enjeksiyon taraması | §12 OpenAPI API taramasını uygula; orta/yüksek SQLi uyarılarını gerçek kimlikli istekle doğrula. | Doğrulanmış orta/yüksek SQLi bulgusu yok. Uçlara erişmeyen tarama “temiz” sayılmaz. | INJ-03 |
| 9.4 Arama ve sayfalama | JUnit ile özel karakter, Unicode, boş sorgu, bozuk cursor, negatif/aşırı limit ve sayı taşması doğrulanır. Manuelde az sayıda temsilî örnek gönder. | Arama 200/400; bozuk cursor/limit 400; 500 yok. DTO validasyonu 422 ise sözleşme farkı ayrıca yazılır. | INJ-04, INJ-05 |
| 9.5 CRLF ve log girdisi | Konu/alıcı/ad alanlarında satır sonu içeren fikstür girdisini dene; e-posta ham başlıklarını ve log çıktısını incele. JUnit/kod incelemesiyle destekle. | Ek e-posta başlığı veya sahte log satırı üretilemiyor; yapılandırılmış log/kaçış güvenli. | INJ-06, INJ-07 |
| 9.6 Gövde ve JSON derinliği | Nginx/backend sınırlarını belirle; sınır altı/üstü gövdeyi ve derinlik sınırının hemen üstündeki JSON'u kontrollü gönder. | Büyük gövde 413; aşırı derin JSON 4xx ile reddedilir, 500/OOM yok. Normal istek sonrasında çalışır. | INJ-08 |
| 9.7 Saklanan/yansıtılan XSS | React Semgrep kurallarını çalıştır; dangerouslySetInnerHTML, DOM sink'leri ve markdown renderer ayarını incele. Fikstür metninde zararsız HTML/olay işaretleyicisini liste, detay ve bildirimlerde aç. | Kullanıcı girdisi çalıştırılmaz; ham HTML render edilmez; ilgili tehlikeli sink kullanımı yok. | XSS-01 |
| 9.8 Markdown URL'leri | Markdown bağlantısında javascript:, data: ve kodlanmış/büyük-küçük harf varyantlarını dene; DOM'daki son href değerini incele. | Yalnız http(s)/mailto tıklanabilir; tehlikeli şema nötralize. | XSS-02 |
| 9.9 URL alanları | Toplantı linki gibi alanlarda HTTPS pozitif kontrolü, HTTP ve tehlikeli şema negatif kontrolü yap. API'yi doğrudan çağırarak UI doğrulamasını atla. | Backend ve UI yalnız https:// kabul eder; normal validasyon hatası 422. | XSS-03 |
| 9.10 Mesaj şablonları | JUnit ile HTML ve Slack özel karakterlerini doğrula; manuelde staging posta kutusu ve test Slack kanalındaki çıktıyı incele. | Kullanıcı metni bağlama uygun kaçışlanmış; HTML/Slack kontrol yapıları enjekte edilemiyor. | XSS-04 |
| 9.11 Frontend dağıtım içeriği | Gerçek prod dist ve sunulan dosyaları tara. .map dosyalarını, sourceMappingURL, gömülü sırları ve hassas iç URL'leri incele. | Sır/iç URL yok; source map yayında değil. Build-time değişkenler yanlışlıkla sır taşımıyor. | FE-03 |
| 9.12 Açık yönlendirme | state.from, davet dönüşü ve ilgili query/state alanlarında dış URL, //host, ters slash ve kodlanmış varyantları dene. | Yalnız uygulama içi göreli yollar; tarayıcı dış origin'e yönlenmez. | FE-04 |
| 9.13 Pahalı okuma ve liste sınırı | JUnit ile tüm liste uçlarının üst sınırını doğrula. Manuelde arama/rapor/tahmin/analitik için limit üstü ve yapılandırılmış hız eşiği+1 dene; DB timeout davranışını izle. | Üst limit zorlanır; pahalı uçlarda hız sınırı/zaman aşımı var. Gereken yerde 429; timeout kontrollü hata, genel çökme yok. | DOS-03, DOS-04 |
| 9.14 Zehirli Kafka mesajı | JUnit/Testcontainers ile veya yalnız staging test topic'inde, işlenemeyen bir fikstür mesajını gönder. Ardından geçerli test mesajı gönder. | Sınırlı retry sonrası DLT; tüketici durmaz, sonraki geçerli mesaj işlenir. | DOS-06 |

10. WebSocket / STOMP
Referans — OWASP WSTG v4.2: Testing WebSockets (WSTG-CLNT-10),
Session Management (WSTG-SESS-06, WSTG-SESS-07) ve
Authorization Testing (WSTG-ATHZ-02, WSTG-ATHZ-04).

/ws/connect için uygulamanın kullandığı gerçek STOMP istemcisini kullan; yalnız ham WebSocket
açmak CONNECT kimlik doğrulamasını test etmez. Tarayıcı DevTools ve mevcut JUnit WebSocket
entegrasyon testleri temel kanıttır. Authorization HTTP handshake'te değil, STOMP CONNECT
frame'inde gönderilir; frame kanıtında token maskelenir.

| Adım | Nasıl | Beklenen | Kontrol ID |
|---|---|---|---|---|
| 10.1 CONNECT kimliği | Token'sız, bozuk/süresi dolmuş token'lı ve geçerli token'lı CONNECT gönder. | İlkleri reddedilir; yalnız geçerli kimlikle STOMP oturumu kurulur. HTTP 101 tek başına kimlik doğrulama başarısı değildir. | WS-01 |
| 10.2 Abonelik izolasyonu | A token ile B'nin /topic/workspace.{wsId}.project.{projectId} kanalına SUBSCRIBE gönder; geçerli A workspace + B project karışımını da dene. A ve B kullanıcı bildirimlerini karşılaştır. | Yetkisiz abonelik reddedilir; B olayı A'ya ulaşmaz. /user/queue/notifications yalnız ilgili kullanıcıya ait. | WS-02 |
| 10.3 Origin | İzinli origin pozitif kontrolü; sana ait yabancı origin'deki tarayıcıdan handshake; JUnit ile eksik/null Origin politikasını test et. | İzinli liste dışındaki tarayıcı origin'i reddedilir. Origin kontrolü token doğrulamasının yerine geçmez. | WS-03 |
| 10.4 Üyelik kaldırma | Aktif aboneyi workspace'ten çıkar; zaman damgalı yeni fikstür olayları üret. Yeniden abonelik denemesini de kontrol et. | Belirlenen üst süre içinde bağlantı kapanır/abonelik düşer; bu sınırdan sonra olay alınmaz. “Makul süre” sayısallaştırılır. | WS-04 |
| 10.5 Token yaşam döngüsü | Bağlantı açıkken token'ın 15 dk süresini doldur; ayrı senaryoda logout/blacklist yap. Yeni olay gönder. | Bağlantının sonlandırılma/yeniden doğrulama politikası ve üst süre raporda; süre sonunda yetkisiz olay akışı yok. | WS-05 |
| 10.6 Hata içeriği | Küçük bozuk frame veya geçersiz destination gönder; ERROR frame ve uygulama loglarını incele. | Sabit/genel hata; token, girdi, stack trace veya iç sınıf bilgisi yansıtılmaz. | WS-06 |
| 10.7 Limitler | Frame/mesaj boyutu, send-buffer, idle timeout ve kullanıcı/IP bağlantı sınırlarını yapılandırmadan çıkar. Sınır testi gerekiyorsa yalnız staging'de küçük kontrollü deneme yap. | Değerler bilinçli ayarlı ve belgeli; aşım kontrollü reddedilir. Sınırsız bağlantı testi yapılmaz. | WS-07 |

Abonelikte ERROR alınmaması tek başına veri sızıntısı değildir; olayın ulaşıp ulaşmadığını da kaydet.
Tersine, sessizce engellenen bir abonelik katalogdaki “SUBSCRIBE hatası” beklentisinden farklıysa bunu
sözleşme sapması olarak belirt.

11. Dış Entegrasyonlar
Referans — OWASP WSTG v4.2: WSTG-INPV-17 (Host Header Injection),
WSTG-INPV-19 (SSRF), WSTG-CRYP-03, Business Logic Testing
(WSTG-BUSL-02, WSTG-BUSL-03, WSTG-BUSL-05).

Yalnız fikstür entegrasyonlarını, test Slack kanalını ve sana ait posta kutularını kullan.
Slack/GitHub/SMTP sağlayıcılarını aktif tarama hedefi yapma. SSRF'nin ağ düzeyindeki olumsuz
senaryoları JUnit/mock sunucularıyla doğrulanır.

| Adım | Nasıl | Beklenen | Kontrol ID |
|---|---|---|---|---|
| 11.1 GitHub imzası | POST /api/v1/webhooks/github/{integrationId} için tam ham gövde baytları üzerinden HMAC-SHA256 üret. Geçerli imza, değiştirilmiş gövde, yanlış imza ve bilinmeyen ID dene. Kodda sabit zamanlı karşılaştırmayı incele. | Geçerli fikstür olayı kabul edilir; bilinmeyen ID ve yanlış imza aynı 401/gövde. Aynı HTTP durumu sabit zamanlı karşılaştırmanın tek başına kanıtı değildir. | EXT-01 |
| 11.2 Delivery tekrar kullanımı | Aynı X-GitHub-Delivery ve gövdeyi iki kez gönder; DB/outbox ve iş etkisini kontrol et. | İkinci gönderim yeni etki üretmez; yalnız HTTP yanıtını karşılaştırmak yeterli değil. | EXT-02 |
| 11.3 Gövde sınırı | Yapılandırılmış sınırın hemen üstünde gövdeyi yanlış/eksik imzayla gönder. İşlem sırasını JUnit/kod incelemesiyle destekle. | 413; sınırlama imza hesaplama/işleme öncesinde uygulanır. | EXT-03 |
| 11.4 Secret rotasyonu | Fikstür secret'ını döndür; yeni imzayı, eski imzayı geçiş süresinde ve TTL sonrasında dene. | Yeni secret çalışır; eski secret belgeli TTL sonrasında reddedilir. | EXT-04 |
| 11.5 Slack SSRF | Mevcut 32 vakalık JUnit testini çalıştır. Manuelde geçerli test webhook'u ve URL doğrulamasınca reddedilmesi gereken birkaç değer kullan; ağ çıkışını gözle. | Yalnız HTTPS hooks.slack.com/services/...; başka host/port/şema ve yönlendirme takibi yok. Benzer hostname, userinfo ve encoding varyantları geçemez. | EXT-05 |
| 11.6 E-posta link kaynağı | Sıfırlama/davet gibi fikstür akışında Host, Forwarded ve X-Forwarded-Host varyantlarını kontrollü dene. Gelen e-postadaki link origin'ini incele. | Link yalnız APP_PUBLIC_URL'den gelir. Proxy isteği reddederse uygulama davranışı JUnit ile ayrıca kanıtlanır. | EXT-06 |
| 11.7 SMTP güvenliği | Sunucudaki etkin SMTP ayarı ve sağlayıcı bağlantısını incele; sertifika doğrulaması ve STARTTLS zorunluluğunu kontrol et. JUnit/Mailpit teslim testini bağla. | Prod SMTP kimlikli ve STARTTLS/SMTPS; TLS düşürme/sertifika doğrulamayı kapatma yok. Staging Mailpit istisnası açık. | EXT-07 |

GitHub imzası JSON yeniden biçimlendirildikten sonra değil, gönderilecek dosyanın tam baytlarıyla
hesaplanır. Secret'ı komut argümanına veya shell geçmişine yazma; yerel script'te gizli istemle al.
Secret, X-Hub-Signature-256, delivery içeriğindeki hassas alanlar ve Slack webhook URL'si kanıtta
maskelenir. Boyut testi de yalnız kullanıcı yokken / staging'de yapılır.

12. ZAP Taramaları
Referans — OWASP WSTG v4.2: WSTG-CONF-12, WSTG-ERRH-01,
WSTG-INPV-05, WSTG-INPV-01, WSTG-INPV-02.
Araç prosedürü: ZAP Docker Baseline Scan, API Scan ve Handling Authentication Yourself.

Baseline, gezilen sayfalarda pasif kuralları uygular; hiç istek göndermeyen bir işlem değildir.
OpenAPI API taraması ise aktif taramadır. İkisi de rol matrisi, BOLA, RLS ve iş mantığı
testlerinin yerine geçmez.

ZAP active scan yalnız kullanıcı yokken / staging'de. Önce snapshot/geri yükleme yolunu
doğrula; test e-posta/Slack hedeflerini kullan. Tarama veri oluşturabilir, değiştirebilir ve silebilir.
Kapsam dışında kalan gerçek verileri, yönetim uçlarını ve üçüncü taraf hedefleri dahil etme.

| Adım | Nasıl | Beklenen | Kontrol ID |
|---|---|---|---|---|
| 12.1 Kapsam ve ön kontrol | İmaj sürümü/digest, hedef origin, endpoint listesi, test rolü ve workspace'i kaydet. API envanterini rol matrisiyle karşılaştır; dış servisleri ve riskli işlemleri açıkça kapsam dışı bırak. | Yalnız yetkili hedef ve fikstür verisi. Hariç tutulan uçlar raporda, başka testle kapsanmış. | AUTHZ-02, INJ-03 |
| 12.2 Baseline | Aşağıdaki komutla SPA'yı tara. Kimlikli ekranlar ve erişilemeyen SPA rotalarını DevTools/manuel gezinme veya ayrı ZAP context ile tamamla. | Uygulanabilir güvenlik başlığı uyarısı yok; hata/bilgi ifşası bulguları incelenmiş. | FE-01, FE-02, CFG-04, CFG-05 |
| 12.3 OpenAPI hazırlama | /v3/api-docs çıktısını yetkili iç erişimle al; prod dokümanını dışarıya açma. servers, path/operation override'ları ve haricî $ref adreslerini incele; yerel, kendine yeterli ve kapsamı daraltılmış kopya oluştur. | Import yalnız $BASE hedefini üretir; gerçek HTTP yöntemleri ve istek şemaları kullanılır. | CFG-03, AUTHZ-02 |
| 12.4 Kimlikli API taraması | Taze access token ve A workspace başlığıyla aşağıdaki API taramasını çalıştır. Önce gerçek korumalı liste/detay isteğinin 2xx döndüğünü ve A verisi içerdiğini doğrula. | Trafikte hem Authorization hem X-Workspace-Id var; doğrulanmış orta/yüksek SQLi ve iç hata ifşası yok. | INJ-03, CFG-04 |
| 12.5 Kimlik sürekliliği | 15 dk TTL nedeniyle taramayı küçük endpoint gruplarına böl; her grupta taze token kullan. Uzun koşumda test edilmiş refresh/auth script'i kullan; aksi halde TTL dolmadan durdur. | Tarama boyunca kimlik geçerli. Yaygın 401/403/429 veya yalnız validasyon hataları “temiz tarama” sayılmaz. | TOK-02, INJ-03 |
| 12.6 Bulgular ve sınırlar | Her uyarıyı hedef istek, rol, yanıt ve yeniden üretimle incele. Mass assignment için §8'deki elle ek alan denemelerini ZAP/Burp üzerinden tekrarla. | False-positive gerekçeli; açık hata gizlenmez. API tarayıcısının tek başına mass assignment/XSS tespit ettiği varsayılmaz. | AUTHZ-03, INJ-03, XSS-01 |
| 12.7 Kanıt çıkarma | Ham raporları kapalı geçici dizinde tut; token/cookie/secret/kişisel veriyi maskele. Her kontrol için ilgili istek/uyarı özetini ayrı dosyaya aktar. | $OUT/<ID>.txt ve maskelenmiş raporlar; araç çıkış kodu, kapsam ve çalışmayan uçlar kaydedilmiş. | FE-01, CFG-04, INJ-03 |

12.1 Baseline komutu
Rapor dizini erişimi kısıtlı geçici alandır; ham raporu doğrudan ortak kanıt klasörüne yazma.
Docker kullanıcısını/volume izinlerini ortama göre doğrula; çözüm olarak chmod 777 kullanma.

Kopyala
umask 077
ZAP_TMP="$(mktemp -d)"
chmod 700 "$ZAP_TMP"

docker run --rm \
  --user "$(id -u):$(id -g)" \
  -v "$ZAP_TMP:/zap/wrk:rw" \
  ghcr.io/zaproxy/zaproxy:stable \
  zap-baseline.py \
    -t "$BASE" \
    -m 1 \
    -r baseline.html \
    -J baseline.json \
  > "$ZAP_TMP/baseline-console.txt" 2>&1
ZAP_BASELINE_RC=$?

printf 'ZAP baseline çıkış kodu: %s\n' "$ZAP_BASELINE_RC"
Baseline crawler kapsamını önceden kontrol et. Logout veya değişiklik yapan GET uçları varsa bunları
dışla; GET'in yan etkisiz olduğu varsayımına güvenme.

12.2 OpenAPI ve kimlikli API taraması
$ZAP_TMP/openapi.scan.json, /v3/api-docs üzerinden elde edilmiş incelenmiş tarama kopyasıdır.
Hazırlarken:

Üst servers hedefini $BASE yap; path/operation düzeyindeki server override'larını da düzelt. Şemadaki path'ler /api/v1 içeriyorsa hedefe yeniden /api/v1 ekleme.
Haricî $ref'leri yerelleştir; import sırasında üçüncü tarafa istek gitmemesini sağla.
Yalnız bu koşumun endpoint grubunu bırak. Auth, kullanıcı/workspace silme, secret rotasyonu, gerçek entegrasyon yönetimi gibi yan etkili uçları otomatik taramadan çıkarıp ayrı kontrollü test et.
Pozitif isteklere gereken A fikstür ID'lerini/örnek değerlerini sağla; rastgele ID'lerle yalnız 404 alan taramayı iş mantığı kapsamı olarak sayma.
Tarayıcı ağı/çıkış politikasıyla hedefi sınırla. Header eşleme, tarama kapsam sınırı değildir.
Aşağıdaki hook, başlıkları yalnız tam hedef origin ve /api/v1 yolu için ekler. Bu yöntem dışarıda
alınmış kısa ömürlü access token kullanır; refresh cookie'sini ZAP'a vermeyi gerektirmez.

Kopyala
# $ZAP_TMP/headers.py
import os
import re
from urllib.parse import urlsplit

def zap_started(zap, target):
    base = os.environ["SCAN_BASE"].rstrip("/")
    parsed = urlsplit(base)
    if (
        parsed.scheme != "https"
        or not parsed.netloc
        or parsed.username
        or parsed.password
        or parsed.path not in ("", "/")
        or parsed.query
        or parsed.fragment
    ):
        raise RuntimeError("SCAN_BASE yalnız HTTPS origin olmalı")

    scope = r"^" + re.escape(base) + r"/api/v1(?:/|\?|$)"
    headers = {
        "Authorization": "Bearer " + os.environ["SCAN_TOKEN"],
        "X-Workspace-Id": os.environ["SCAN_WORKSPACE"],
    }
    for name, value in headers.items():
        if not value or "\r" in value or "\n" in value:
            raise RuntimeError("Geçersiz tarama başlığı")
        zap.replacer.add_rule(
            description="sec-test-" + name,
            enabled="true",
            matchtype="REQ_HEADER",
            matchregex="false",
            matchstring=name,
            replacement=value,
            initiators="",
            url=scope,
        )
Hook'u seçilen ZAP sürümünde önce küçük bir grupta dene; History üzerinden başlıkların gönderildiğini
doğrula. Başarılı hook yüklenmesi, başarılı kimlik doğrulaması demek değildir.

Kopyala
# $ZAP_TMP önceki adımda oluşturulmuş olmalı.
# headers.py ve incelenmiş openapi.scan.json bu dizine konmuş olmalı.
# Komutlar Bash içindir. set -x kapalı tutulur.
set +x

export SCAN_BASE="$BASE"

# ID'yi fixture.json'daki gerçek alanından al; burada etkileşimli giriş kullanılıyor.
read -r -p "A workspace ID (fixture.json): " SCAN_WORKSPACE
export SCAN_WORKSPACE

# Normal login ile yeni alınmış token; kanıta/shell geçmişine yazılmaz.
read -r -s -p "Taze A test kullanıcısı access token: " SCAN_TOKEN
printf '\n'
export SCAN_TOKEN

SCAN_NAME="sec-zap-api-$(date +%s)"

# Linux timeout: 10 dk sonunda koşumu durdurur.
# -T seçeneği aktif taramanın genel süre sınırı değildir; bunun yerine dış timeout kullanılır.
timeout --signal=TERM --kill-after=15s 600s \
  docker run --rm --name "$SCAN_NAME" \
    --user "$(id -u):$(id -g)" \
    -e SCAN_BASE -e SCAN_WORKSPACE -e SCAN_TOKEN \
    -v "$ZAP_TMP:/zap/wrk:rw" \
    ghcr.io/zaproxy/zaproxy:stable \
    zap-api-scan.py \
      -t /zap/wrk/openapi.scan.json \
      -f openapi \
      --hook /zap/wrk/headers.py \
      -r api.html \
      -J api.json \
  > "$ZAP_TMP/api-console.txt" 2>&1
ZAP_API_RC=$?

# timeout Docker istemcisini durdurmuş olsa da container'ın kaldığını varsayma.
docker rm -f "$SCAN_NAME" >/dev/null 2>&1 || true

unset SCAN_TOKEN SCAN_WORKSPACE SCAN_BASE
printf 'ZAP API çıkış kodu: %s\n' "$ZAP_API_RC"
Bu blok yeni token ile ve önceden doğrulanmış küçük kapsamla çalıştırılır. Süre sınırına ulaşırsa
tarama tamamlanmamıştır; kalan uçları daha küçük gruplara böl. timeout yoksa eşdeğer dış
süre kontrolü kur; token TTL'sini uzatmayı veya güvenlik kontrollerini kapatmayı çözüm sayma.

ZAP'ın token'ı environment/config içinde görmesi normaldir; Docker daemon ve test makinesine
erişim yetkisini sınırla. Hook/API hata çıktıları da sır içerebilir; ham console dosyaları maskelenmeden
paylaşılmaz. Tarama sonrasında kullanılan oturumu iptal et.

12.3 Sonuçların değerlendirilmesi
ZAP paketli tarama çıkış kodlarını doğru yorumla:

0: Yapılandırılmış uyarı/başarısızlık yok.
1: En az bir FAIL.
2: FAIL yok, en az bir WARN.
3: Araç/çalıştırma hatası.
124 veya dış timeout'un ürettiği kod: Koşum süre sınırında kesildi; tamamlanmış tarama değil.
Varsayılan uyarı seviyesi, zafiyetin risk seviyesiyle aynı değildir. Çıkış kodu 0 olsa bile endpoint
kapsamı, başarılı kimlikli istekler ve test edilen alanlar doğrulanmadan GEÇTİ verilmez.

Maskelenmiş rapora ek olarak $OUT/INJ-03.txt, $OUT/FE-01.txt, $OUT/CFG-04.txt gibi dosyalarda
komut temsili, çıkış kodu, kapsam, bulgular ve ilgili rapor yolu bulunur. Secret maskelemesi yalnız
JWT kalıbına indirgenmez; cookie, bearer olmayan refresh token, imza, webhook URL'si ve response
body alanları da kontrol edilir.

13. Tedarik Zinciri
Referans — OWASP WSTG v4.2: WSTG-CONF-01, WSTG-CONF-02,
WSTG-CONF-04, WSTG-INFO-05.
Bağımlılık ve CI kontrollerinde ayrıca 02'deki ASVS 14.2 referansları kullanılır.

Repo commit SHA'sı, lockfile'lar, Maven bağımlılık çözümü, kullanılan scanner sürümü ve gerçek
dağıtım image digest'lerini kaydet. Geliştirici makinesindeki başka bir build'in taraması prod
artifact'ının kanıtı değildir.

| Adım | Nasıl | Beklenen | Kontrol ID |
|---|---|---|---|---|
| 13.1 Maven SCA | OSV-Scanner, trivy fs veya Dependency-Check ile doğrudan/transitif bağımlılıkları tara. Maven çözüm ağacı/SBOM ile scanner kapsamını karşılaştır. | Kritik/yüksek açık yok veya süreli, sorumlusu belirli, gerekçeli istisna var. Yalnız pom.xml dosyasının görülmesi tam çözüm kanıtı değildir. | SC-01 |
| 13.2 npm SCA | Lockfile ile temiz/reprodüksiyonlu kurulumun bağımlılıklarını npm audit ve/veya OSV-Scanner ile tara; build/dev bağımlılıklarını ayrı sınıflandır. | Kritik/yüksek açık yok veya belgeli istisna. Ağ/registry hatası temiz sonuç sayılmaz. | SC-02 |
| 13.3 Image taraması | Çalışan backend/frontend ve dağıtıma dahil diğer image'ları gerçek digest üzerinden trivy image ile tara. Katmanlardaki sırları da kontrol et. | Kritik açık yok; yüksek bulgular değerlendirilmiş. Silinmiş olsa bile eski katmanda sır yok. | SC-03, CFG-08 |
| 13.4 CI yetkileri | Workflow/action referanslarını ve permissions bloklarını incele. PR/fork çalıştırmalarında secret erişimi ve güvenilmeyen kodun ayrıcalıklı tetikleyicilerde çalışmasını kontrol et. | Action'lar tam commit SHA'ya sabit; minimal token yetkisi; gereksiz secret erişimi yok. | SC-04 |
| 13.5 Sır ve geçmiş | Tam Git geçmişini gitleaks ile tara; sığ clone kullanma. Çalışma ağacı, tracked/untracked dağıtım dosyaları ve image bulgularını birlikte değerlendir. | Sır taraması temiz. Gerçek sır bulunduysa dosyayı silmek yetmez; iptal/rotasyon ve geçmiş etkisi ele alınır. | SC-05, CFG-08 |
| 13.6 Kod analizi | Java ve JS/TS için CodeQL CI sonucunu ilgili commit'te kontrol et. Java build/çıkarım ve JS/TS kapsamının başarılı olduğunu doğrula; find-sec-bugs/Semgrep sonuçlarını ekle. | Yüksek bulgu yok; analizin çalışmaması veya sıfır dosya taraması başarı sayılmaz. | SC-06, INJ-01, XSS-01 |
| 13.7 Dağıtım yapılandırması | Compose/Dockerfile için trivy config sonucunu §4 ile bağla; frontend artifact taramasını ekle. | Kritik yapılandırma bulgusu, bundle sırrı veya yayınlanmış source map yok. | INF-07, FE-03 |

Örnek komutlar: Repo kökünde çalıştır; modül dizinlerini gerçek yapıya göre ayarla. Araçların
kurulu sürümündeki --help çıktısını kontrol et. Ham JSON raporları da hassas bilgi içerebilir.

Kopyala
umask 077
SC_TMP="$(mktemp -d)"
chmod 700 "$SC_TMP"

# Dosya sistemi ve yapılandırma; SCA kapsamı ayrıca doğrulanır.
trivy fs --scanners vuln,secret --format json \
  --output "$SC_TMP/trivy-fs.json" .
TRIVY_FS_RC=$?

trivy config --format json \
  --output "$SC_TMP/trivy-config.json" .
TRIVY_CONFIG_RC=$?

# Değeri çalışan dağıtımın digest'iyle değiştir.
trivy image --scanners vuln,secret --format json \
  --output "$SC_TMP/trivy-image.json" \
  "<registry/image@sha256:digest>"
TRIVY_IMAGE_RC=$?

# Frontend paket dizininde çalıştır.
npm audit --json > "$SC_TMP/npm-audit.json"
NPM_AUDIT_RC=$?

# Önce tam geçmiş bulunduğunu doğrula.
git rev-parse --is-shallow-repository
gitleaks git . --log-opts="--all" --redact \
  --report-format json --report-path "$SC_TMP/gitleaks.json"
GITLEAKS_RC=$?
Her rapordan ilgili $OUT/SC-xx.txt dosyasına komut, scanner sürümü, çıkış kodu, kapsam ve
maskelenmiş bulgu özeti aktarılır. Güncel advisory veritabanına erişilememesi veya desteklenmeyen
bağımlılık formatı ayrıca yazılır; boş rapor otomatik GEÇTİ değildir.

14. Temizlik ve Raporlama
Referans — OWASP WSTG v4.2: Reporting (WSTG §5) ve Security Test Data
Analysis and Reporting (§2.11); log ve hata kanıtları için WSTG-ERRH-01.
Operasyonel saklama/izleme ayrıntıları 02'deki ASVS 7.x referanslarıyla değerlendirilir.

Temizliği kanıtlar güvenli biçimde alındıktan sonra yap. Önce otomasyon/olay üretimini durdur,
sonra fikstür kaynaklarını sil; böylece tüketiciler silinen test verisini yeniden oluşturmaz.
Güvenlik/audit kayıtları sır içermiyorsa sırf testi gizlemek için silinmez; saklama politikasına tabi tutulur.

| Adım | Nasıl | Beklenen | Kontrol ID |
|---|---|---|---|---|
| 14.1 Çalışan testleri durdur | ZAP container'larını, WS istemcilerini, fixture script'lerini ve test otomasyonlarını kapat. Geçici SYSTEM_ADMIN hesabını/rolünü geri al. | Arka planda tarama, abonelik veya ayrıcalıklı test hesabı kalmaz. | AUTHZ-06, WS-04, WS-05 |
| 14.2 Entegrasyonları etkisizleştir | Fikstür otomasyonlarını, webhook entegrasyonlarını ve bekleyen davetleri iptal et. Test secret'larını iptal/döndür; test SMTP/Slack yönlendirmesini gözden geçir. | Yeni dış etki üretilmez; eski test secret'ları kullanılamaz. | EXT-04, BL-01 |
| 14.3 Fikstür verisini sil | $OUT/fixture.json içindeki kesin ID listesiyle sahiplik doğrulaması yap. Önce bağlı kaynakları veya belgeli workspace cascade akışını, ardından A/B workspace'lerini ve sec-test-* hesaplarını normal yönetim API'siyle sil. | Test kaynakları ve ek SYSTEM_ADMIN hesabı yok. Sadece ad önekiyle geniş DELETE çalıştırılmaz; gerçek veri etkilenmez. | TEN-01, TEN-03 |
| 14.4 Oturum ve geçici kayıtlar | Test hesaplarının tüm refresh ailelerini iptal et; access token kara listesini/TTL'yi kontrol et. Teste ait Redis rate-limit/idempotency kayıtları ve DB token kayıtlarını yalnız kesin anahtar/ID listesiyle temizle veya TTL'ye bırak. | Kullanılabilir test oturumu ve hassas cache yanıtı yok. FLUSHALL veya genel tablo temizliği yapılmaz. | TOK-03, TOK-05, BL-05, BL-06 |
| 14.5 Olay ve posta artıkları | Test outbox/event/DLT kayıtlarını uygulama saklama politikasına göre ele al; ortak Kafka topic'lerini silme. Test inbox/Mailpit verilerini ve tarama ile üretilen ek kaynakları envanterle karşılaştır. | Test olayları tekrar işlenip veri oluşturmaz; ortak servis/olay geçmişi bozulmaz. | TEN-07, DOS-06 |
| 14.6 Log taraması | Test penceresindeki uygulama, nginx, CI, ZAP, SMTP ve hata loglarını incele. Bilinen test sırlarını yerelde arat; eşleşen değeri rapora kopyalama. | Parola, access/refresh token, cookie, webhook secret ve hassas URL yok. Bulunursa erişimi sınırla, sırları iptal et ve düzeltme aç. | LOG-02, CFG-08 |
| 14.7 Saklama ve erişim | Merkezi/yerel log izinlerini, saklama süresini ve rotasyonu incele. Kanıt klasörünün erişim/retention politikasını kaydet; git dışı olduğunu doğrula. | Loglar saklanıyor; erişim en az yetkili; süreler belgeli. | LOG-03 |
| 14.8 Uyarı kanalı | Staging'de kontrollü başarısız login/5xx test olayı veya monitör test bildirimi üret. Kanal teslimini ve sorumluya ulaşmasını doğrula. | En az bir çalışan uyarı kanalı; 5xx/login hata oranı koşulları tanımlı. Genel sisteme hata yükü bindirilmez. | LOG-01, LOG-04 |
| 14.9 Geçici erişimleri kapat | Tarama için açılan iç doküman erişimi, SSH tüneli, firewall istisnası ve geçici debug ayarını kaldır. Test ortamı ayarlarını normal dağıtım profiliyle karşılaştır. | Prod dokümanı/servis portu kazara açık kalmaz; dev ayarı yok. | CFG-03, CFG-07, INF-01, INF-02 |
| 14.10 Kanıtları sonlandır | Ham raporları maskele; $OUT/<ID>.txt dosyalarını ve referans verilen ekran görüntülerini kontrol et. Geçici credential dosyalarını, cookie jar'larını, shell değişkenlerini ve kapalı ham rapor dizinlerini sil. | Paylaşılabilir kanıtta sır yok; ham kopyalar kalmamış. Dosya silmenin snapshot/backup kopyalarını otomatik silmediği dikkate alınmış. | LOG-02, SC-05 |
| 14.11 Son doğrulama | A/B workspace ve hesap ID'leriyle yokluk kontrolü yap; test öncesi/sonrası sayaçları karşılaştır. TLS, genel health ve normal smoke test'i tekrarla. | Test verisi yok; normal servis davranışı sağlıklı; artık yetki/konfigürasyon değişikliği yok. | TEN-03, CFG-01, CFG-02 |
| 14.12 Rapor (05) | Aşağıdaki karar kurallarıyla her ID için sonuç, kanıt ve varsa bulgu yaz. JUnit sınıfı/metodu ve CI run bağlantısını ilgili ID kanıtına ekle. | 02'deki her kontrol izlenebilir; P1 başarısızlıkları ve koşulamayan kontroller açıkça görünür. | Bu dosyanın sonundaki tüm kontrol ID'leri |

14.1 Kanıt ve sonuç kuralları
Her $OUT/<ID>.txt dosyası aşağıdaki bilgileri içerir:

Kopyala
Kontrol: TEN-01
Zaman / ortam:
Commit SHA / image digest:
Araç sürümü:
Aktör / rol / workspace:
Komut veya manuel işlem: Authorization: Bearer <token>
Pozitif kontrol:
Negatif kontrol ve çıktı:
Kalıcı yan etki kontrolü:
JUnit testi / CI run / ek rapor:
Sonuç: GEÇTİ / KALDI / UYGULANAMAZ
Bulgu / kapsam sınırı / istisna:
GEÇTİ: Beklenti kanıtla sağlandı. Oto kontrol için başarılı ilgili JUnit sonucu ve manuel spot-check birlikte ilişkilendirilir; spot-check tam test kapsamı gibi sunulmaz.
KALDI: Beklenti sağlanmadı. Kanıtlanamadı/koşulamadı durumunda mevcut üç durumlu şemada KALDI — doğrulama eksik yazılır; doğrulanmış zafiyet olmadığı ayrıca belirtilir.
UYGULANAMAZ: Özellik veya altyapı gerçekten yok; teknik gerekçe yazılır. Araç eksikliği, zaman yetersizliği, expired token veya tarama hatası bu sınıfa girmez.
Risk kabulü: Teknik başarısızlığı otomatik GEÇTİ yapmaz. Sorumlu, gerekçe, telafi kontrolü ve son tarih ayrı kaydedilir. Katalog açıkça gerekçeli istisna kabul ediyorsa bu dayanak belirtilir.
P1: Başarısız veya doğrulaması eksik kontrol yayını engeller. P2/P3 bulguları için düzeltme planı, sorumlu ve yeniden test tarihi belirlenir.
Sözleşme farkı: 02'deki 400 ile gerçek DTO validasyonu 422; iss/aud kararı ile TOK-09 whitelist'i; “makul süre” gibi belirsiz beklentiler sessizce değiştirilmez. Karar ve katalog düzeltmesi rapora yazılır.
14.2 ID → bölüm kapsama tablosu
Aşağıdaki tablo 02 kataloğundaki 125 kontrolün tamamını ilgili doğrulama bölümlerine bağlar.
Birden fazla ID yazılan satırda belirtilen bölüm her ID için geçerlidir; her ID yine kendi
$OUT/<ID>.txt kanıt dosyasına sahiptir.

ID	Bölüm
AUTH-01, AUTH-02, AUTH-03, AUTH-04, AUTH-05	§7
AUTH-06	§4
AUTH-07, AUTH-08, AUTH-09, AUTH-10	§7
TOK-01, TOK-02, TOK-03, TOK-04, TOK-05	§7
TOK-06, TOK-07, TOK-08, TOK-09, TOK-10	§7
AUTHZ-01, AUTHZ-02, AUTHZ-03, AUTHZ-04	§8
AUTHZ-05, AUTHZ-06, AUTHZ-07, AUTHZ-08	§8
TEN-01, TEN-02, TEN-03, TEN-04	§8
TEN-05, TEN-06, TEN-07, TEN-08	§8
DB-01, DB-02, DB-03, DB-04, DB-05	§6
DB-06, DB-07, DB-08, DB-09, DB-10	§6
INJ-01, INJ-02	§9; INJ-01 ayrıca §13
INJ-03	§9, §12
INJ-04, INJ-05, INJ-06, INJ-07, INJ-08	§9
XSS-01, XSS-02, XSS-03, XSS-04	§9
FE-01, FE-02	§5, §12
FE-03	§9, §13
FE-04	§9
WS-01, WS-02, WS-03, WS-04, WS-05, WS-06, WS-07	§10
EXT-01, EXT-02, EXT-03, EXT-04, EXT-05, EXT-06, EXT-07	§11
BL-01, BL-02, BL-03, BL-04, BL-05, BL-06, BL-07, BL-08	§8
DOS-01, DOS-02	§7
DOS-03, DOS-04	§9
DOS-05	§6
DOS-06	§9, §14
CFG-01	§4
CFG-02, CFG-03, CFG-04, CFG-05, CFG-06, CFG-07, CFG-08	§5
INF-01, INF-02, INF-03, INF-04, INF-05, INF-06, INF-07, INF-08, INF-09	§4
SC-01, SC-02, SC-03, SC-04, SC-05, SC-06	§13
LOG-01, LOG-02	§7, §14
LOG-03, LOG-04	§14
Kapsama tablosu dokümantasyon kapsamını gösterir, testlerin geçtiğini göstermez. Nihai güvence,
her ID'nin kanıtı ve 05 raporundaki sonucuyla sağlanır.