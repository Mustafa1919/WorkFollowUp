# ADR-0016: Hazır otomasyon şablonları modeli

- **Durum:** Kabul edildi (Ürünleştirme Dalga 3.1 — V31, 2026-09-25)
- **İlgili:** ADR-0004 (webhook kimlik modeli — aktör kimliği kararı buradan devralındı), ADR-0012
  (Aging WIP — "Tenant-Iterating" saatlik job deseni burada da kullanıldı)

## Bağlam

Rakip analizi ve plan, "PR merge → Done" gibi mevcut webhook davranışının yanına birkaç basit,
hazır otomasyon kuralı istiyordu (tüm alt görevler Done → üst görev Review, blocker kapandı →
bildirim, teslim tarihi geçti → bildirim, atama yapıldı → In Progress). Cevaplanması gereken üç
soru: (1) kural motoru serbest bir editör mü olacak yoksa sabit şablonlar mı, (2) otomasyonun
tetiklediği değişiklikler kimin adına yazılacak, (3) otomasyonun kendi ürettiği olay yeni bir
otomasyonu tetikleyip sonsuz döngü oluşturabilir mi.

## Karar

1. **Serbest kural editörü YOK, sabit şablonlar (`AutomationTemplateKey`) VAR.** Jira'nın
   konfigürasyon karmaşasından bilinçli kaçınma (RAKIP_ANALİZİ.md'nin kendi tavsiyesi): her
   şablonun tetikleyicisi ve etkisi kodda sabittir, kullanıcı yalnız proje bazında açar/kapatır.
   Kapsam bilerek proje-düzeyinde (workspace-geneli kural yok) — Subtask/V19'daki "tek seviye,
   proje sınırlı" basitleştirmesiyle aynı desen. Bir proje bir şablonu en fazla bir kez
   yapılandırır (`UNIQUE (project_id, template_key)`).
2. **Otomasyon aktörü: dedike sistem kullanıcısı (`AutomationActor.SYSTEM_USER_ID`), webhook'un
   `IntegrationActor`'ı ile AYNI desen** (ADR-0004) — kural sahibinin kişisel kimliği DEĞİL: bir
   kural birden çok admin tarafından açılıp kapatılabilir, "sahip" kavramı anlamsız; ayrıca sabit
   bir kimlik döngü korumasının (madde 3) temelini oluşturuyor.
3. **Döngü koruması: `causedByRule`/derinlik alanı DEĞİL, aktör eşitliği.** Plan taslağı
   envelope'a bir `causedByRule` bayrağı ekleyip derinlik 1 ile sınırlamayı öneriyordu; bunun
   yerine daha basit bir kural seçildi: `AutomationEventConsumer`, `actorId`'si
   `AutomationActor.SYSTEM_USER_ID`'ye eşit olan `task.events` olaylarını hiç işlemez. Otomasyonun
   ürettiği HER değişiklik bu aktör adına yazıldığından, bu tek kontrol otomasyonun kendi
   zincirini (tek adımda VEYA çok adımda) tamamen keser — ayrı bir "derinlik sayacı" taşımaya
   gerek kalmaz. Webhook'un aktörü (`IntegrationActor`) FARKLI bir kimlik olduğu için bu korumadan
   etkilenmez: bir PR merge'inin tetiklediği Done geçişi, `SUBTASK_ALL_DONE_PARENT_TO_REVIEW` gibi
   kuralları normal şekilde tetikleyebilir — bu istenen davranıştır.
4. **Varsayılan değer şablona göre değişir:** `PR_MERGE_TO_DONE` varsayılan AÇIKTIR (opt-out) —
   `GithubEventProcessor`'ın V13'ten beri var olan davranışını korur; satırı hiç olmayan (bu
   dilimden önceki) projelerde davranış sessizce değişmesin diye. Diğer dört şablon varsayılan
   KAPALIDIR (opt-in) — yeni davranış kullanıcı açıkça açmadan çalışmamalı.
5. **Dört şablonun üçü (`SUBTASK_ALL_DONE_PARENT_TO_REVIEW`, `BLOCKER_DONE_NOTIFY`,
   `ASSIGNED_TODO_TO_IN_PROGRESS`) `task.events` tüketicisi (`AutomationRuleEngine`), beşincisi
   (`PR_MERGE_TO_DONE`) `GithubEventProcessor` içine gömülü bir kapı.** `OVERDUE_NOTIFY` olay
   güdümlü değil — saatlik "Tenant-Iterating" job (`AutomationOverdueJob`, ADR-0012'deki
   `AgingWipJob` ile birebir aynı iskelet), günlük tekrar bildirimini `automation_notifications_
   sent` dedup tablosuyla (V31) engeller.
6. **Otomasyonun tetiklediği durum değişiklikleri her zaman `TaskService` üzerinden** —
   `task_events`/outbox/WebSocket/Cycle Time zinciri elle değişiklikle AYNI yoldan akar, ayrı bir
   "otomasyon" event tipi veya yazım yolu icat edilmedi.
7. **Params kolonu JSONB DEĞİL TEXT** — `saved_views` (V27) ile aynı gerekçe (Hibernate 7 +
   Jackson 3 JSONB entity mapping belirsizliği); v1'de hiçbir şablonun parametresi yok, kolon
   yalnız ileriye dönük yer tutucu.

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| Serbest kural editörü (tetikleyici + koşul + eylem kullanıcı tanımlı) | Jira'nın kendi kaçınılması gereken karmaşası; hobi projesinde bakım/test yükü şablonlardan kat kat fazla olurdu. |
| Workspace-geneli otomasyon kuralı | Subtask/dependency'nin aksine burada proje sınırını aşmayı gerektiren bir kullanım senaryosu yoktu; proje-düzeyi zaten "aç/kapa" zihinsel modeliyle birebir örtüşüyor. |
| Kural sahibi = açan kullanıcının `userId`'si | Bir kuralı birden fazla admin açıp kapatabilir; hangi admin'in "sahip" sayılacağı belirsiz, ayrıca döngü korumasını da karmaşıklaştırırdı (her yazan kullanıcı için ayrı kontrol gerekirdi). |
| Envelope'a `causedByRule` alanı + derinlik sayacı | Aktör eşitliği kontrolü aynı korumayı, envelope şemasını değiştirmeden ve TÜM tüketicilerde (CycleTimeConsumer, InboxFanoutService, vs.) ekstra alan ayrıştırmaya gerek kalmadan sağlıyor. |
| `PR_MERGE_TO_DONE` varsayılan KAPALI (diğer 4 şablonla tutarlı) | Var olan projelerde ilk kez otomasyon sayfası açılana kadar webhook'un mevcut davranışı sessizce dururdu — geriye dönük uyumluluğu bozardı. |

## Sonuçlar

**Olumlu**
- Otomasyonun tetiklediği HER değişiklik mevcut `TaskService` iş kurallarından (onaylı görev
  kilidi, geçerli durum kümesi, vb.) geçer — ayrı bir doğrulama yolu yok, tutarsızlık riski yok.
- Döngü koruması tek bir eşitlik kontrolüyle sağlanıyor; yeni bir şablon eklendiğinde ekstra bir
  "derinlik" muhasebesi gerekmez.

**Olumsuz / kabul edilen bedel**
- Aktör-eşitliği koruması çok-adımlı (A kuralı → event → B kuralı → event → C kuralı) zincirleri de
  TAMAMEN keser, yalnız "kendini tetikleme"yi değil — bu bilinçli bir kapsam daraltmasıdır (plan
  taslağının "derinlik 1" fikrinden daha katı), v1 için daha güvenli varsayılan kabul edildi.
- `SUBTASK_ALL_DONE_PARENT_TO_REVIEW` yalnız üst görevin durumu `To Do`/`In Progress` iken çalışır;
  üst görev zaten `Review`/`Done`/onaylıysa hiçbir şey yapmaz (sessiz no-op) — kullanıcı arayüzünde
  bunu açıklayan bir geri bildirim yok, bilinen küçük bir sınır.
- `OVERDUE_NOTIFY` job'ı saatlik çalışıp dedup tablosuyla günde bire indirgiyor; "günün hangi
  saatinde" bildirim gideceği job'ın ilk çalıştığı saate bağlı, yapılandırılamaz (v1 kapsamı dışı).

## Faz 4 notu

`AutomationRuleEngine`, `TaskService`/`TaskDependencyRepository`/`TaskWatcherRepository`'ye
(çekirdek görev modülü) doğrudan bağımlı; bildirim veya otomasyon ayrı bir servise çıkarsa bu
okuma bağımlılıkları senkron API çağrısına ya da ayrı bir read model'e dönüşmeli (ADR-0008'in Faz 4
notuyla aynı ödünç). `PR_MERGE_TO_DONE`'ın `GithubEventProcessor` içine gömülü olması, webhook
ayrı bir servise çıkarsa (ADR-0004'ün kendi Faz 4 notu) otomasyon kapısının da o servise taşınması
veya senkron bir sorguya dönüşmesi gerektiği anlamına gelir.

## Referanslar

- `src/main/resources/db/migration/V31__automation_rules.sql`
- `src/main/java/com/app/tracker/automation/AutomationTemplateKey.java`
- `src/main/java/com/app/tracker/automation/AutomationRuleEngine.java`
- `src/main/java/com/app/tracker/automation/AutomationEventConsumer.java`
- `src/main/java/com/app/tracker/automation/AutomationOverdueJob.java`
- `src/main/java/com/app/tracker/integration/service/GithubEventProcessor.java`
- `src/test/java/com/app/tracker/automation/AutomationRuleIntegrationTest.java`
