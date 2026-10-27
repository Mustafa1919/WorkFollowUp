# ADR-0021: Alt görev sıfırdan oluşturulur; story point yalnız üst görevde

- **Durum:** Kabul edildi (Dalga 5 planı, 2026-10-26 — kod henüz yazılmadı)
- **İlgili:** V19 (subtask: tek seviye, aynı proje — `TaskService#setParent` javadoc'u), ADR-0002
  (Velocity), ADR-0015 (Retro ilk taahhüt), ADR-0019 (`SUBTASK_CREATE_OWN` izni),
  `docs/DALGA_5_KULLANILABILIRLIK.md`

## Bağlam

Bugün "Alt görev ekle" yeni görev oluşturmuyor; var olan bir görevi `PUT /tasks/{id}/parent/{parentId}`
ile alt görev olarak bağlıyor. Kullanıcı, bir işi parçalamak için önce ayrı bir görev açıp sonra
bağlamak zorunda kalıyor.

İkinci sorun puanla ilgili: analitik ve rapor sorgularında (`SprintSnapshotRepository`,
`PeriodReportRepository`) üst/alt görev ayrımı yok. Hem üst görev hem alt görevleri puanlanırsa
Velocity, dönem raporu ve `COMPLETED_POINTS` hedefleri aynı işi iki kez sayar.

ADR-0019 ile geliştirici görev oluşturamaz; ama kendi işini parçalayabilmesi gerekiyor.

## Karar

1. **Yeni uç: `POST /api/v1/tasks/{parentTaskId}/subtasks`.** Gövde, genişletilmiş görev oluşturma
   isteğinin (Dalga 5 plan dokümanı, dilim 5.4) alt kümesidir: başlık zorunlu; açıklama, son tarih,
   atanan, etiketler opsiyonel. Proje ve tek seviye kuralları V19'daki gibi: üst görev başka bir
   görevin alt görevi olamaz, onaylı ya da silinmiş olamaz. Oluşturma ve bağlama tek transaction'da
   yapılır; `TASK_CREATED` ve `TASK_PARENT_CHANGED` olayları aynen yazılır (tüketiciler değişmez).
2. **Varsayılanlar üst görevden gelir:** sprint = üst görevin sprinti, atanan = üst görevin atananı.
   `TASK_PLAN` yetkisi olan bunları değiştirebilir; yalnız `SUBTASK_CREATE_OWN` ile gelen
   (geliştirici) değiştiremez, alanlar üst görevden türetilir.
3. **Yetki:** `TASK_CREATE` VEYA `SUBTASK_CREATE_OWN`. İkincisi koşulludur: üst görevin atananı
   çağıranın kendisi olmalı (`TaskAccessPolicy`, ADR-0019 madde 3). Mevcut görevi bağlama/çözme
   (`setParent`) `TASK_PLAN`'a bağlanır.
4. **Story point yalnız üst görevde (ve alt görevi olmayan bağımsız görevde) bulunur.**
   - Alt göreve puan girilemez: `updateStoryPoint`, `parentTaskId != null` ise reddeder; alt görev
     oluşturma isteğinde puan alanı yoktur.
   - Puanı olan bir görev alt görev olarak bağlanamaz: `setParent` "önce puanı kaldırın" mesajıyla
     reddeder. Puanı sessizce silmek Velocity geçmişini fark ettirmeden değiştirirdi.
   - Üst görevin puanı bağımsızdır; alt görevlerden türetilmez.
5. **Mevcut veri geriye dönük düzeltilmez, ölçülür.** Yayından önce sunucuda
   `SELECT count(*) FROM tasks WHERE parent_task_id IS NOT NULL AND custom_fields ? 'story_point'
   AND deleted_at IS NULL` çalıştırılır. Sonuç sıfır değilse kayıtlar listelenip elle temizlenir;
   otomatik migration yazılmaz (az sayıda, demo verisi).

## Değerlendirilen seçenekler

| Seçenek | Neden seçilmedi |
|---|---|
| (b) Puan alt görevlerde, üst görev toplamı türetir | Daha doğru ama türetilmiş alanın tutarlılığı (alt görev silme, taşıma, sprint farkı) ve Velocity'nin hangi seviyeyi sayacağı ayrı bir tasarım gerektirir. |
| (c) Puan serbest, sorgular alt görevi hariç tutar | Kullanıcı alt göreve puan girip hiçbir yerde göremez; sessiz veri. Ayrıca her yeni sorguda filtreyi hatırlamak gerekir. |
| Oluşturmayı `POST /projects/{id}/tasks`'a `parentTaskId` alanıyla eklemek | Tek uçta iki farklı yetki kuralı (TASK_CREATE vs koşullu SUBTASK_CREATE_OWN) olur; ayrı uç izni anotasyonda net ifade eder. |
| Geliştirici hiç alt görev açamasın | Kendi işini parçalayamaz; her parça için yönetmene bağımlı olur. |

## Sonuçlar

**Olumlu**
- Puan analitiklerde tek kez sayılır; Velocity/Retro tanımları (ADR-0002, ADR-0015) değişmez.
- Geliştirici kendi işini backlog'a yeni iş eklemeden parçalayabilir.

**Olumsuz / kabul edilen bedel**
- Puanlı bir görevi alt göreve çevirmek iki adım ister (puanı kaldır, sonra bağla).
- **Açık nokta (bu ADR'nin kapsamı dışında):** görev SAYISINA dayanan metrikler (throughput,
  Monte Carlo — ADR-0013, Aging WIP örneklemi — ADR-0012) alt görevleri ayrı görev olarak sayıyor.
  Alt görev kullanımı arttıkça throughput şişebilir. Kullanım verisi toplanınca ayrı ADR ile ele
  alınacak.

## Referanslar

- `src/main/java/com/app/tracker/task/service/TaskService.java#setParent`, `#updateStoryPoint`
- `src/main/java/com/app/tracker/task/controller/TaskController.java` (`/story-point`, `/parent`)
- `src/main/java/com/app/tracker/analytics/repository/SprintSnapshotRepository.java`
- `src/main/java/com/app/tracker/report/repository/PeriodReportRepository.java`
