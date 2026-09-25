# ADR-0015: Retro'nun "ilk taahhüt" kesiti ve retro panosu

- **Durum:** Kabul edildi (Ürünleştirme Dalga 2.4 — V30, 2026-09-25)
- **İlgili:** ADR-0002 (Velocity'de `committed` = kapanış kesiti; bu ADR onun açık bıraktığı "ilk
  taahhüt" sorusunu kapatır), ADR-0013 (Monte Carlo — retro'nun geriye dönük olasılık hesabı AYNI
  fonksiyonu kullanır)

## Karar

1. **`sprint_analytics`'e `committed_at_start_tasks/points` eklendi** — `SprintSnapshotRepository`
   (zaten herhangi bir `cutoff` anına göre üyelik kuran) `sprint.startedAt` ile de çağrılır.
   Kapanış kesiti (`committedTasks/Points`) DEĞİŞMEDİ; bu iki kolon yalnız "sprint ortasında ne
   eklendi/çıkarıldı" sorusunu cevaplar. **Nullable** — bu migration'dan önce tamamlanmış
   sprint'ler için geri doldurma YAPILMAZ (V17/V19 ile aynı "bilerek dar kapsam" deseni).
2. **Eklenen/çıkarılan görev LİSTELERİ** iki ayrı `membersAt` çağrısının (start/end) küme farkından
   çıkarılır — yalnız sayı değil, gerçek görev referansları.
3. **Spillover listesi görevlerin ANLIK durumunu kullanır**, kesit-katı değildir (aggregate sayılar
   `sprint_analytics`'ten gelir ve HER ZAMAN doğrudur). Retro geçmişe dönük bir özet ekranı olduğu
   için görev durumunun sprint kapandıktan sonra değişmesi pratikte nadir — kabul edilmiş bir
   yaklaşıklık.
4. **Geriye dönük tahmin karşılaştırması**: `MonteCarloForecaster` (ADR-0013) AYNEN yeniden
   kullanılır, örneklem penceresi sprint BAŞLAMADAN ÖNCEki günlerle sınırlanır (`sampleTo =
   startDate - 1`) — geleceği sızdırmamak için.
5. **Retro panosu (`retro_items`)**: serbest metin maddeleri (`went_well/improve/action`).
   Sahiplik `SavedViewService` ile AYNI sıkılıkta (yalnız yazan silebilir, ADMIN istisnası yok).
   `action` maddesi bir göreve dönüştüğünde **backlog'a düşer** (sprint zaten kapalı olduğu için
   V9'un "tamamlanmış sprint'e görev eklenemez" kuralı burada da geçerli).

## Sonuçlar

**Olumlu:** Velocity/Spillover ile ÇELİŞMEYEN, aynı kaynaklardan beslenen bir retro ekranı;
tahmin karşılaştırması ek bir hesaplama motoru gerektirmedi.

**Kabul edilen bedel:** Eski sprint'lerde plan verisi yok; spillover listesi kesit-katı değil;
cycle time aykırı değerleri ve en uzun bekleyen blocker sorguları katılımcı/görev sayısıyla
doğrusal büyür (günde bir kez açılan bir sayfa için kabul edilebilir).

## Referanslar

- `src/main/resources/db/migration/V30__sprint_retro.sql`
- `src/main/java/com/app/tracker/retro/RetroService.java`
- `src/main/java/com/app/tracker/retro/RetroItemService.java`
- `src/test/java/com/app/tracker/retro/RetroIntegrationTest.java`
