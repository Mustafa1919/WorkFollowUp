# Rakip Analizi ve Özellik Yol Haritası (2026-09-23)

> Amaç: WorkFollowUp'ı deneyerek eksik bulmak yerine, benzer iş takip/proje yönetimi
> ürünlerini (Jira, Linear, Shortcut; genel PM tarafında Trello/Asana/ClickUp/Monday)
> analiz edip "ne eklenmeli / rakipler nerede kötü / biz nasıl farklılaşabiliriz"
> sorusuna cevap aramak. Web araştırmasına dayanır (bkz. Kaynaklar).
>
> Bağlam kararı: Faz 4'e (mikroservis dönüşümü) geçmeden önce modüler monolit
> yapıda ürünü olgunlaştırmaya devam ediliyor (bkz. `Hedefler.md` [2026-09-22] ve
> [2026-09-23] girdileri). Bu doküman o ara adımın girdisidir.

## 1. Rakip Analizi Özeti

### Jira
- **Güçlü:** Sınırsız özelleştirilebilirlik (workflow/screen/permission scheme),
  6000+ marketplace eklentisi, enterprise ölçek raporlama.
- **Zayıf:** Kurulumu günler-haftalar sürüyor; 200+ ticket'lı board'da drag-drop
  4-6 saniye donuyor; native time tracking yok (Tempo eklentisi + ekstra ücret);
  sprint/epic UX'i "pahasına göre zayıf"; entegrasyonlar kağıt üstünde iyi,
  pratikte kırılgan/eklenti bağımlı.

### Linear (WorkFollowUp'a en yakın kıyas — geliştirici-merkezli)
- **Hız:** her view <300ms (Jira 2sn+).
- **Keyboard-first + Command Palette (Cmd+K).**
- **Triage Inbox:** yeni bug/feature isteği backlog'a karışmadan önce ayrı kutuda
  süzülüyor.
- **Cycles:** zaman kutulu sprint, bitmeyen iş otomatik sıradaki cycle'a taşınıyor.
- **3 katmanlı planlama:** Initiative (çok-çeyreklik tema) → Project (hedef
  tarihli kapsam) → Issue/Cycle.
- **Bildirim merkezi (Inbox):** kanal bazlı (Desktop/Mobile/Email/Slack), her
  kanal için anlık ya da digest seçimi.
- **2026'da Linear Agent:** AI ajanlar issue'ya atanabiliyor, otomatik
  triage/özet/takip-işi üretiyor.

### Shortcut
- Jira'nın karmaşıklığı ile Linear'in basitliği arası; güçlü GitHub
  entegrasyonu, daha iyi mobil app.

### Trello / Asana / ClickUp / Monday (genel PM tarafı)
- Ortak trend: **subtask + dependency** ("Blocked by" / "Blocking" iki ayrı
  alan), **saved/custom view'lar**, **AI destekli özet/triage** (ClickUp AI
  Stand-ups: toplantısız günlük özet).
- ClickUp'ın kötü yanı: her şeyi tek workspace'e tıkıştırınca performans düşüyor,
  öğrenme eğrisi dikleşiyor — WorkFollowUp'ın modüler monolit kararı bunu mimari
  seviyede zaten önlüyor.

## 2. WorkFollowUp'ın Zaten İyi Yaptığı Yerler (korunmalı, pazarlanmalı)

Jira'da eklentiyle/ekstra ücretle gelen ya da hiç olmayan şeyler WorkFollowUp'ta
native:
- Event sourcing (`task_events`) = hazır audit log.
- Cycle Time / Velocity / Throughput native (Jira'da raporlama zayıf/pahalı).
- GitHub webhook → otomatik durum geçişi + Slack bildirimi native.
- RLS ile tenant izolasyonu (yapısal, "WHERE workspace_id" unutma riski yok).
- Kafka/WebSocket tabanlı gerçek zamanlı güncelleme (Jira'nın yavaş board
  sorununun tersi).

## 3. Öncelik Sıralı Özellik Listesi

| Öncelik | Özellik | Gerekçe / Mevcut mimariye oturuşu |
|---|---|---|
| **Yüksek** | Tags/Labels | Zaten Faz 6 açık noktası; filtreleme için gerekli |
| **Yüksek** | In-app bildirim merkezi (Inbox) | `task.events`'i zaten Slack worker'ı tüketiyor; aynı desenle in-app tablo + mevcut WebSocket/STOMP altyapısına push |
| **Yüksek** | Subtask + Dependency (Blocked by/Blocking) | İki ayrı alan, net gösterim; Jira'nın "clumsy epic handling" şikayetinin tersi |
| **Yüksek** | Command Palette (Cmd+K) | Frontend'de Radix zaten kurulu, düşük efor/yüksek etki (Linear'in en çok övülen özelliği) |
| Orta | Saved/custom filtreler | Kanban+takvimde filtre state'i zaten var, kalıcı hale getirmek yeterli |
| Orta | Global arama (başlık/açıklama/yorum) | Küçük ölçekte Postgres `tsvector` yeterli |
| Orta | Toplu işlem (bulk status/sprint/etiket) | Kanban'da çoklu seçim |
| Orta | Burndown / Cumulative Flow grafiği | `sprint_analytics`/`task_analytics` üzerine ek sorgu |
| Orta | Native time tracking | Jira'nın Tempo bağımlılığının tam tersi, farklılaşma noktası |
| Orta | Activity/audit sekmesi (UI) | `task_events` zaten var, sadece UI'da gösterim |
| Düşük (uzun vade) | Initiative/Roadmap katmanı | Linear'in 3 katmanlı modeli, Faz 6 sonrası |
| Düşük (uzun vade) | AI destekli triage/standup özeti | `GithubEventInterpreter` zaten kural motoru; üstüne LLM katmanı |
| Düşük | Workspace'e üye davet akışı | Bilinen sınır (zaten not düşülmüş) |
| Düşük | Guest/misafir salt-okunur erişim | Öncelik değil |

## 4. Kaçınılması Gereken Kötü Pattern'ler

- **Jira'nın konfigürasyon cehennemi** (workflow/screen/permission scheme
  kombinasyonu) — basit, opinionated kalınmalı.
- **Marketplace bağımlılığı ile temel özelliği ücretlendirme** (time tracking,
  docs) — native ve ücretsiz yapmak farklılaşma noktası.
- **ClickUp'ın "her şeyi tek ekrana tıkıştırma" performans sorunu** — yeni
  özellik eklerken UI'da "her şeyi aynı yerde göster" dürtüsüne direnmek gerekir.

## 5. Kullanıcı Notları (bu oturumda eklenen, ayrı değerlendirilecek)

1. **Bug/eksik — Sprint geçmiş tarihe oluşturulabiliyor:** Dün veya geçen aya
   sprint açılabiliyor, olmamalı. V16'daki `dueDate` geçmiş tarih yasağı deseni
   (`ClockConfig` iş saat dilimi + `TaskService.rejectPastDueDate`) referans
   alınarak `SprintService.createSprint`'e de uygulanmalı.
2. **Yeni özellik — Raporlama sayfası:** Yıllık ve çeyreklik (3/6/9 ay) raporlar;
   hem "yapılanlar" (geçmişe dönük — velocity/throughput/cycle-time özetleri,
   mevcut `sprint_analytics`/`task_analytics` üzerine inşa edilebilir) hem de
   "hedef/plan" (ileriye dönük — quarter hedefleri; henüz veri modeli yok, yeni
   tasarım gerekir).
3. **Toplantı özelliği — KARAR VERİLDİ (2026-09-23): planlama-only.** Teams
   tarzı tekrarlanan toplantı, takvimde görünsün, günlük/haftalık kısa
   toplantılar (standup) için düşünülüyordu. Kullanıcı, uygulama-içi gerçek
   görüşme YAPILMAYACAK şekilde karar verdi — aşağıdaki değerlendirme aynen
   kabul edildi.
   - **Değerlendirme (kabul edildi):** İki ayrı parça olarak ele alınmalı.
     - *Toplantı planlama* (tekrarlanan desen — RRULE tarzı, takvime entegre,
       Slack/in-app hatırlatma): düşük maliyetli, mevcut takvim + bildirim
       altyapısıyla uyumlu → **yapılabilir**.
     - *Uygulama içi gerçek sesli/görüntülü görüşme* (WebRTC/SFU, medya
       sunucusu, NAT/TURN, kodek, bant genişliği yönetimi): tamamen ayrı ve
       büyük bir mühendislik alanı; projenin hedeflediği Distributed Systems
       öğrenme alanıyla (Kafka/CQRS/RLS/caching/observability) örtüşmüyor ve
       "Faz 4 öncesi monoliti oturtma" odağını dağıtır → **önerilmez**, en
       azından bu ara adımda.
   - **Önerilen orta yol:** Toplantı planlama/takvim kısmını ekle; gerçek
     görüşmeyi harici bir araca (Zoom/Google Meet/Teams) otomatik link üretimiyle
     devret — Linear/Asana gibi rakipler de görüşmeyi kendileri
     implemente etmiyor, entegrasyonla çözüyor. Native görüşme gerçekten
     isteniyorsa ileride ayrı, açıkça kapsamlı bir sprint olarak (LiveKit/Jitsi
     gibi açık kaynak SFU değerlendirmesiyle) ele alınmalı, bu listeye
     karıştırılmamalı.

## Kaynaklar

- [Linear vs. Jira: which is best for your team in 2026?](https://monday.com/blog/rnd/linear-or-jira/)
- [Best Project Management Tools for Developers in 2026: Linear vs Jira vs GitHub Projects](https://nexasphere.io/blog/best-project-management-tools-developers-2026)
- [Linear vs Jira: Why Dev Teams Are Ditching Jira in 2026](https://www.buildmvpfast.com/blog/linear-vs-jira-project-management-developer-team-2026)
- [Jira Reviews 2026 — Capterra](https://www.capterra.com/p/19319/JIRA/reviews/)
- [Brutal Honest Jira Review 2026](https://thebusinessdive.com/jira-review)
- [Jira Alternatives in 2026: Rethinking Issue Tracking](https://bridgeapp.ai/resources/blog/jira-alternatives-in-2026-rethinking-issue-tracking-for-modern-teams)
- [Linear – Features](https://linear.app/features)
- [Linear Roadmaps in 2026](https://aitoolpick.org/blog/linear-roadmaps-complete-guide-2026/)
- [Trello vs Asana vs Monday vs ClickUp: Ultimate Guide (2026)](https://softwarefinder.com/resources/trello-vs-asana-vs-monday-vs-clickup)
- [Best Project Management Software: 11 Tools Compared — Asana](https://asana.com/resources/best-project-management-software)
- [Linear vs Jira vs Shortcut: The Best Project Management Tool for Engineering Teams](https://valueaddvc.com/blog/linear-vs-jira-vs-shortcut-the-best-project-management-tool-for-engineering-teams)
- [Choosing Project Software: Jira, Linear, Shortcut & Azure DevOps](https://talentblocks.com/blog/linear-vs-shortcut-vs-jira-vs-azure-devops-which-tool-offers-the-best-flexibility-and)
- [Task Dependencies | Productive Help Center](https://help.productive.io/en/articles/6278091-task-dependencies)
- [How to use task dependencies in Asana](https://help.asana.com/s/article/task-dependencies?language=en_US)
- [Best AI project management tools for 2026 — Wrike](https://www.wrike.com/blog/ai-project-management-tools/)
- [Top 10 AI Project Management Tools (Compared & Ranked) 2026](https://productive.io/blog/ai-project-management-tools/)
- [Notifications – Linear Docs](https://linear.app/docs/notifications)
- [Inbox – Linear Docs](https://linear.app/docs/inbox)
