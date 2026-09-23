import { Client, ReconnectionTimeMode, type IMessage, type StompSubscription } from '@stomp/stompjs'

/**
 * Backend `core/realtime/WebSocketConfig` — native WebSocket (SockJS YOK), tek endpoint
 * `/ws/connect`, CONNECT frame'inde STOMP native `Authorization: Bearer <token>` header'i ister
 * (WebSocketAuthInterceptor). Abonelik hedefi `/topic/workspace.{workspaceId}.project.{projectId}`
 * — TaskEventBroadcastListener'in Kafka'dan fan-out ettigi HAM envelope JSON'ini tasir
 * (eventId/eventType/workspaceId/payload). Vite dev proxy'si `/ws`'i `ws: true` ile yonlendirir.
 */
function brokerUrl(): string {
  const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${protocol}://${location.host}/ws/connect`
}

let client: Client | null = null
let currentToken: string | null = null
let subscription: StompSubscription | null = null
let desired: { workspaceId: string; projectId: string; onEvent: (envelope: unknown) => void } | null = null

// Bildirim kanalı proje kanalından BAĞIMSIZ, İKİNCİ bir abonelik: aynı `disconnectRealtime`
// yaşam döngüsünü paylaşır (bağlantı tek), ama niyeti (`desiredNotifications`) ayrı tutulur —
// aksi halde ikisini TEK `desired` değişkeninde tutmak birini kaydedince diğerini sessizce
// kaybettirirdi (bkz. `disconnectRealtime` yorumu: niyeti yalnız sahibi temizlemeli).
let notificationSubscription: StompSubscription | null = null
let desiredNotifications: { onEvent: (notification: unknown) => void } | null = null

function resubscribe() {
  subscription?.unsubscribe()
  subscription = null
  if (!client?.connected || !desired) return
  const destination = `/topic/workspace.${desired.workspaceId}.project.${desired.projectId}`
  subscription = client.subscribe(destination, (message: IMessage) => {
    try {
      desired?.onEvent(JSON.parse(message.body))
    } catch {
      console.debug('[realtime] ayrıştırılamayan çerçeve:', message.body)
    }
  })
}

function resubscribeNotifications() {
  notificationSubscription?.unsubscribe()
  notificationSubscription = null
  if (!client?.connected || !desiredNotifications) return
  // `/user/queue/notifications`: backend'in UserDestinationMessageHandler'i bunu CONNECT'teki
  // kimlige gore session'a ozel gercek bir kuyruga cevirir; istemci hicbir kullanici id'si
  // GONDERMEZ (bkz. WebSocketAuthInterceptor) — bu yuzden baska bir kullanicinin kanalina
  // abone olmak yapisal olarak imkansizdir.
  notificationSubscription = client.subscribe('/user/queue/notifications', (message: IMessage) => {
    try {
      desiredNotifications?.onEvent(JSON.parse(message.body))
    } catch {
      console.debug('[realtime] ayrıştırılamayan bildirim çerçevesi:', message.body)
    }
  })
}

/**
 * Oturum boyunca TEK paylaşımlı STOMP bağlantısı. `token` değişmemişse (aynı erişim jetonuyla
 * tekrar çağrılırsa) no-op — reconnect'i sadece gerçek jeton değişiminde (refresh sonrası) veya
 * `disconnectRealtime()` sonrasında tetikler.
 */
export function ensureRealtimeConnected(token: string) {
  if (client && currentToken === token) return
  disconnectRealtime()
  currentToken = token
  client = new Client({
    brokerURL: brokerUrl(),
    connectHeaders: { Authorization: `Bearer ${token}` },
    // Baglanti koptugunda (ag, sunucu restart) otomatik yeniden dene; ustel geri cekilme (stompjs
    // 7'nin yerlesik ozelligi) art arda basarisiz denemelerde sunucuyu bogmaz.
    reconnectDelay: 1000,
    reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    maxReconnectDelay: 30000,
    onConnect: () => {
      resubscribe()
      resubscribeNotifications()
    },
    onStompError: (frame) => console.debug('[realtime] STOMP hatası:', frame.headers.message),
    onWebSocketError: (event) => console.debug('[realtime] WebSocket hatası:', event),
  })
  client.activate()
}

/**
 * Çıkış / workspace değişimi / jeton geçersizleşmesinde çağrılır: soketi tamamen kapatır.
 * `desired` BİLEREK korunur: React'te child (BoardPage) effect'i parent (AppLayout) effect'inden
 * ÖNCE çalışır ve jeton yenilemede yalnız AppLayout effect'i yeniden koşar; burada silinseydi
 * abonelik hem ilk yüklemede hem her refresh'te sessizce kaybolurdu. Abonelik isteğini yalnız
 * `subscribeToProject`'in döndürdüğü temizleyici siler.
 */
export function disconnectRealtime() {
  subscription?.unsubscribe()
  subscription = null
  notificationSubscription?.unsubscribe()
  notificationSubscription = null
  client?.deactivate()
  client = null
  currentToken = null
}

/**
 * Açık proje kanalına abone olur. Bağlantı henüz kurulmamışsa (ör. sayfa daha yeni açıldı) istek
 * saklanır ve `onConnect`'te (ilk bağlantı VE her reconnect sonrası) gerçek abonelik kurulur —
 * stompjs bağlantısız bir `subscribe()` çağrısını sessizce yutar, bu yüzden durumu kendimiz tutarız.
 */
export function subscribeToProject(
  workspaceId: string,
  projectId: string,
  onEvent: (envelope: unknown) => void,
): () => void {
  desired = { workspaceId, projectId, onEvent }
  resubscribe()
  return () => {
    if (desired?.workspaceId === workspaceId && desired.projectId === projectId) {
      desired = null
      subscription?.unsubscribe()
      subscription = null
    }
  }
}

/**
 * Kullanicinin kendi Inbox kanalina abone olur (workspace/proje'den BAGIMSIZ — AppLayout gibi
 * her zaman monte bir yerden tek sefer cagrilir). `subscribeToProject` ile AYNI desen: baglanti
 * henuz yoksa istek saklanir, `onConnect`'te (ilk baglanti VE her reconnect sonrasi) gercek
 * abonelik kurulur.
 */
export function subscribeToNotifications(onEvent: (notification: unknown) => void): () => void {
  desiredNotifications = { onEvent }
  resubscribeNotifications()
  return () => {
    if (desiredNotifications?.onEvent === onEvent) {
      desiredNotifications = null
      notificationSubscription?.unsubscribe()
      notificationSubscription = null
    }
  }
}
