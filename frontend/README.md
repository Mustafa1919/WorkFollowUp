# WorkFollowUp — Frontend

React 19 + TypeScript + Vite. Kurulum ve çalıştırma: `../docs/KURULUM.md` (Bölüm 6.1).

```bash
npm run dev     # geliştirme (5173, /api ve /actuator -> 8080 proxy)
npm run build   # tsc -b + vite build
npm run lint    # oxlint
```

## Yığın

| Konu | Seçim | Not |
|---|---|---|
| Stil | Tailwind CSS v4 | Renkler `src/index.css`'te token; tema `<html data-theme>` |
| Bileşen | Radix (Dialog, DropdownMenu) + kendi `components/ui` | shadcn tarzı, bağımlılık kilidi yok |
| Animasyon | Motion | Sayfa geçişi, shared layout (`layoutId`), stagger, tema için View Transitions |
| Sürükle-bırak | dnd-kit | Kanban (durum) ve takvim (tarih) aynı altyapı |
| Sunucu verisi | TanStack Query | Optimistic update: `api/queries.ts#useUpdateTask` |
| İstemci durumu | Zustand | Yalnız oturum (token bellekte), workspace seçimi, tema |
| Grafik | Recharts | Yalnız analitik sayfasında, lazy chunk |

## Dizinler

```
src/
  api/queries.ts        tüm sorgu/mutasyon hook'ları; sorgu anahtarları workspace'e bağlı
  lib/api.ts            axios: token + X-Workspace-Id ekler, 401'de tek seferlik refresh
  stores/               session (token YALNIZ bellekte), theme
  layouts/AppLayout     sidebar, workspace seçici, sayfa geçişleri, onboarding
  features/
    auth/               giriş/kayıt, açılışta sessiz refresh (AuthGate)
    dashboard/          karşılama, istatistik, projeler, yaklaşan görevler
    board/              Kanban, görev detayı, sprintler
    calendar/           aylık takvim, günlere sürükle-bırak, hızlı ekleme, tarihsiz liste
    analytics/          velocity, throughput, cycle time
    settings/           tema, Slack ve GitHub webhook (yalnız WORKSPACE_ADMIN)
```

## Bilinen sınırlar

- Gerçek zamanlı güncelleme (STOMP/WebSocket) henüz bağlı değil. Başka bir kullanıcının
  değişikliği pencere odaklanınca ya da 30 sn'lik `staleTime` dolunca görünür.
- Story point backend yanıtında dönmediği için görev detayında mevcut değer gösterilmiyor,
  yalnızca yazılabiliyor.
- Workspace'e üye davet etme ucu olmadığı için bu özellik UI'da da yok.
