# WorkFollowUp

*Türkçe | [English](README.en.md)*

Çok kiracılı (multi-tenant) bir iş/görev takip ve analiz uygulaması. Board tabanlı görev
yönetiminin üzerine; toplantısız standup özetleri, veriye dayalı retro, Monte Carlo tahmin,
Aging WIP uyarıları ve hazır otomasyon şablonları gibi ekip pratiklerini doğrudan ürüne
gömer — ayrı bir eklenti/entegrasyon aramadan.

## Neler var

- **Board ve görevler** — sprint/backlog, subtask, bağımlılık (dependency), etiket, kayıtlı görünüm
- **Zaman makinesi** — board'un geçmişteki herhangi bir andaki halini geriye dönük görüntüleme
- **Toplantısız standup** — dün/bugün/bloklanan özetleri otomatik derlenir, toplantı gerekmez
- **Veriye dayalı retro** — sprint başlangıcındaki taahhüt ile kapanışın karşılaştırması
- **Analitik** — Cycle Time, Velocity/Throughput, Aging WIP (projenin kendi p85'ine göre eşik),
  Monte Carlo ile bootstrap tahmin
- **Otomasyon şablonları** — "PR merge → Done", "tüm subtask bitince parent → Review" gibi
  hazır kurallar, proje bazında aç/kapa
- **Toplantı planlama, raporlama, bildirim/inbox, global arama**
- **GitHub webhook + Slack entegrasyonu**, kişisel erişim token'ları (API otomasyonu için)
- **Uygulama içi geri bildirim** ve ürün kullanım/hata telemetrisi

Ürün kararlarının gerekçeleri `docs/adr/` altındaki ADR'lerde; mimarinin genel görünümü
`docs/ARCHITECTURE_AND_PHASES.md` ve fazlara bölünmüş tasarım dokümanlarında (`docs/PHASE_*`).

## Teknoloji

- **Backend:** Java 21, Spring Boot 4 (Security 7), PostgreSQL 16 (Row-Level Security ile
  kiracı izolasyonu), Kafka (Transactional Outbox Pattern), Redis (cache-aside + distributed lock)
- **Frontend:** React 19 + TypeScript, Vite, TanStack Query, Zustand, Tailwind CSS, STOMP
  üzerinden gerçek zamanlı güncellemeler
- **Mimari:** modüler monolit + seçici worker ayrışımı (Analytics Service, Webhook Ingestion
  Service) — tam mikroservise erken geçişten bilinçli olarak kaçınılmış bir ara durak

Detaylı gerekçeler için `docs/ARCHITECTURE_AND_PHASES.md`.

## Hızlı başlangıç

Bu uygulamayı **kendi makinende** denemek için Docker (Postgres/Redis/Kafka), JDK 21 ve
Node.js gerekir. Adım adım kurulum, bilinen ortam tuzakları ve environment variable listesi
için:

**→ [`docs/KURULUM.md`](docs/KURULUM.md)**

Kısaca:

```bash
docker compose up -d                                    # Postgres + Redis + Kafka
./mvnw -Dspring-boot.run.profiles=migrate spring-boot:run   # migration (bir kereye mahsus)
./mvnw spring-boot:run                                   # backend → :8080

cd frontend
cp .env.example .env.local
npm install
npm run dev                                               # frontend → :5173
```

Uygulamaya girince ilk workspace'ini oluşturduğunda kısa bir tanıtım turu seni karşılar
(temel alanları gösterir; Ayarlar → Tanıtım turu'ndan istediğin an tekrar açabilirsin).
API'yi doğrudan denemek istersen `http://localhost:8080/swagger-ui.html` ve
[`docs/API_KULLANIM.md`](docs/API_KULLANIM.md).

## Proje durumu

Bireysel/öğrenme amaçlı geliştirilen, hâlâ aktif geliştirilen bir proje — kısayol veya kapsam
kısaltması yok, her faz gerçek implementasyonuyla tamamlanıyor. Güncel ilerleme ve yol
haritası proje sahibinin kendi notlarında tutuluyor; kod tabanındaki en güvenilir kaynak
`docs/` altındaki tasarım dokümanları ve `docs/adr/` altındaki kararlardır.

Yayına alınmadan önce ayrı bir güvenlik değerlendirmesi planlanıyor —
[`docs/security/README.md`](docs/security/README.md).
