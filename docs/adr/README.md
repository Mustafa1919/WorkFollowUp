# Mimari Karar Kayıtları (ADR)

Bu klasör, kodda "neden böyle?" sorusunun cevabı olan kararları tutar. Kod davranışı anlatır;
ADR, reddedilen seçenekleri ve kabul edilen bedeli anlatır.

## Kurallar

- Dosya adı: `NNNN-kisa-baslik.md`, numara bir kez verilir ve değişmez.
- Kabul edilmiş bir ADR düzenlenmez; karar değişirse yeni bir ADR yazılır, eskisinin durumu
  `Yerini aldı: ADR-XXXX` olur. Yazım hatası/referans düzeltmesi serbesttir.
- Şablon: Durum · Bağlam · Karar · Değerlendirilen seçenekler · Sonuçlar · Faz 4 notu · Referanslar.
- Her ADR ilgili kodun yolunu verir; kod taşınırsa referans güncellenir.

## Dizin

| No | Başlık | Durum |
|---|---|---|
| [0001](0001-read-replica-opt-in-routing.md) | Read replica yönlendirmesi opt-in (`@ReadReplica`) | Kabul edildi |
| [0002](0002-velocity-committed-tanimi.md) | Velocity'de `committed` = kapanış kesiti | Kabul edildi |
| [0003](0003-ortam-sirlari-fail-closed-guard.md) | Ortam sırları için fail-closed guard (deny-list) | Kabul edildi |
| [0004](0004-webhook-kimlik-modeli.md) | Webhook kimlik modeli | Kabul edildi (Faz 4'te yeniden açılacak) |
| [0005](0005-slack-adres-guvenligi.md) | Slack adres güvenliği: allow-list + şifreleme | Kabul edildi |
| [0006](0006-bildirim-teslim-semantigi.md) | Bildirim teslim semantiği: retry, devre kesici, offset | Kabul edildi |
| [0007](0007-rapor-ve-hedef-modeli.md) | Rapor ve hedef modeli | Kabul edildi |
| [0008](0008-izleyici-modeli-ve-inbox-alicilari.md) | İzleyici modeli ve Inbox alıcı tanımı | Kabul edildi |
| [0009](0009-yorum-ve-mention-modeli.md) | Yorum ve mention modeli | Kabul edildi |
| [0010](0010-e-posta-teslimi-ve-token-riski.md) | E-posta teslimi: devre kesici/offset, ham token riski, izin modeli | Kabul edildi |
| [0011](0011-global-arama-modeli.md) | Global arama modeli: Postgres tsvector, simple+unaccent, dogrudan anahtar eslesme | Kabul edildi |

## Çapraz risk: tek master key

ADR-0004 (webhook secret'ları) ve ADR-0005 (Slack adres şifrelemesi) aynı
`WEBHOOK_SECRET_MASTER_KEY`'den türetilir. Bu anahtarın değişmesi **tüm** GitHub webhook
secret'larını geçersiz kılar ve **tüm** Slack adreslerini çözülemez hale getirir. Anahtar
rotasyonu bugün bir toplu kesinti operasyonudur; anahtar sürümleme (`key:v2:`) eklenene kadar
bir runbook olmadan döndürülmemelidir.
