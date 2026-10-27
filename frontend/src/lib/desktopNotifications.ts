import type { Notification as AppNotification } from '@/lib/types'

export type DesktopPermission = 'unsupported' | 'insecure' | NotificationPermission

const ENABLED_KEY = 'wf-desktop-notifications'
const HINT_KEY = 'wf-desktop-hint-dismissed'

/** 'Notification' in window degilse 'unsupported'; guvenli baglam degilse 'insecure'. */
export function desktopPermission(): DesktopPermission {
  if (typeof window === 'undefined' || !('Notification' in window)) return 'unsupported'
  if (!window.isSecureContext) return 'insecure'
  return window.Notification.permission
}

/** Cihaz ayari; varsayilan KAPALI. */
export function isDesktopEnabled(): boolean {
  try {
    return localStorage.getItem(ENABLED_KEY) === '1'
  } catch {
    return false
  }
}

export function setDesktopEnabled(on: boolean): void {
  try {
    if (on) localStorage.setItem(ENABLED_KEY, '1')
    else localStorage.removeItem(ENABLED_KEY)
  } catch {
    /* yoksay */
  }
}

/** Zil menusundeki tek seferlik oneri kapatildi mi. Okuma basarisizsa "kapatilmis" sayilir. */
export function isDesktopHintDismissed(): boolean {
  try {
    return localStorage.getItem(HINT_KEY) === '1'
  } catch {
    return true
  }
}

export function dismissDesktopHint(): void {
  try {
    localStorage.setItem(HINT_KEY, '1')
  } catch {
    /* yoksay */
  }
}

/** Yalniz kullanici tiklamasindan cagrilir. 'granted' ise cihaz ayarini acar. */
export async function requestDesktopPermission(): Promise<DesktopPermission> {
  const current = desktopPermission()
  if (current === 'unsupported' || current === 'insecure') return current
  try {
    const result = await window.Notification.requestPermission()
    if (result === 'granted') setDesktopEnabled(true)
    return result
  } catch {
    return desktopPermission()
  }
}

export function desktopTargetPath(n: AppNotification): string {
  if (n.projectId && n.taskId) return `/projects/${n.projectId}?task=${n.taskId}`
  if (n.projectId) return `/projects/${n.projectId}`
  return '/'
}

/** Push gövdesi (unknown) en az id + title string ise AppNotification sayilir. */
export function isAppNotification(v: unknown): v is AppNotification {
  if (!v || typeof v !== 'object') return false
  const o = v as Record<string, unknown>
  return typeof o.id === 'string' && typeof o.title === 'string'
}

/** Sekme gorunur degilken, izin + cihaz ayari aciksa masaustu bildirimi gosterir. Hatalar yutulur. */
export function maybeShowDesktopNotification(n: AppNotification, onNavigate: (n: AppNotification) => void): void {
  try {
    if (document.visibilityState === 'visible') return
    if (desktopPermission() !== 'granted' || !isDesktopEnabled()) return
    const shown = new window.Notification(n.title, { body: n.body, tag: n.id })
    shown.onclick = () => {
      try {
        window.focus()
        onNavigate(n)
        shown.close()
      } catch {
        /* yoksay */
      }
    }
  } catch {
    /* uygulamayi bozma */
  }
}
