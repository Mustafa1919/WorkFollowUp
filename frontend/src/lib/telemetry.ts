import { api } from '@/lib/api'
import { useSession } from '@/stores/session'

/**
 * Dalga 4 -- urun kullanim telemetrisi. Sayfa gorusleri/ozellik kullanimlari bellekte biriktirilir,
 * periyodik (10sn) veya kuyruk dolunca (20) toplu gonderilir -- backend'deki best-effort Kafka
 * produce'unun (TelemetryEventPublisher) istek sayisini gereksiz artirmamak icin. Basarisizlik
 * SESSIZCE yutulur: kullanicinin akisini asla bozmaz.
 */
interface QueuedEvent {
  feature: string
  action: string
}

const QUEUE_LIMIT = 20
const FLUSH_INTERVAL_MS = 10_000

let queue: QueuedEvent[] = []
let flushTimer: ReturnType<typeof setInterval> | null = null

function ensureFlushTimer() {
  if (flushTimer) return
  flushTimer = setInterval(flush, FLUSH_INTERVAL_MS)
}

function isAuthenticated() {
  return !!useSession.getState().accessToken
}

export function trackEvent(feature: string, action: string) {
  if (!isAuthenticated()) return
  queue.push({ feature, action })
  ensureFlushTimer()
  if (queue.length >= QUEUE_LIMIT) flush()
}

/** Sayfa gorusleri de ayni kuyruktan gecer, action sabit "viewed". */
export function trackPageView(path: string) {
  trackEvent(pageFeatureFromPath(path), 'viewed')
}

function pageFeatureFromPath(path: string): string {
  const segment = path.split('/').filter(Boolean)[0]
  return segment || 'home'
}

export function flush() {
  if (queue.length === 0) return
  const events = queue
  queue = []
  api.post('/api/v1/telemetry/usage', { events }).catch(() => {
    /* best-effort -- kayip tolere edilir (bkz. TelemetryEventPublisher javadoc'u) */
  })
}

/** Sekme kapanirken kuyrukta kalani gondermeyi dener (garanti degil, best-effort). */
window.addEventListener('beforeunload', flush)

/** Frontend hata raporu -- ayri, batch'lenmeyen bir istek (nadir, kayip yine tolere edilir). */
export function reportError(errorType: string, message: string | undefined, path: string) {
  if (!isAuthenticated()) return
  api.post('/api/v1/telemetry/errors', { errorType, message, path }).catch(() => {
    /* best-effort */
  })
}
