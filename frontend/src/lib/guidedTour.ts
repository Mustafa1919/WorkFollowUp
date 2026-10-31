const OPEN_EVENT = 'wf:open-tour'
const SEEN_PREFIX = 'wf-tour-seen:'

/** Ayarlar sayfasindaki "Turu tekrar başlat" gibi disaridan tetiklemek icin. */
export function openGuidedTour() {
  window.dispatchEvent(new Event(OPEN_EVENT))
}

export function onGuidedTourOpenRequested(handler: () => void) {
  window.addEventListener(OPEN_EVENT, handler)
  return () => window.removeEventListener(OPEN_EVENT, handler)
}

/**
 * Anahtar kullanici id'sine baglidir; e-posta cihazda cikistan sonra kalmasin (PRIV-06). Eski
 * surumler `wf-tour-seen:<email>` yaziyordu: bulunursa id anahtarina tasinip silinir.
 */
function seenKey(userId: string) {
  return `${SEEN_PREFIX}${userId}`
}

/** localStorage erisimi basarisiz olursa (gizli sekme vb.) tur tekrar tekrar acilmasin diye "gorulmus" sayilir. */
export function hasTourBeenSeen(userId: string, legacyEmail: string | null): boolean {
  try {
    if (localStorage.getItem(seenKey(userId)) === '1') return true
    if (legacyEmail && localStorage.getItem(`${SEEN_PREFIX}${legacyEmail}`) === '1') {
      markTourSeen(userId, legacyEmail)
      return true
    }
    return false
  } catch {
    return true
  }
}

export function markTourSeen(userId: string, legacyEmail: string | null) {
  try {
    localStorage.setItem(seenKey(userId), '1')
    if (legacyEmail) localStorage.removeItem(`${SEEN_PREFIX}${legacyEmail}`)
  } catch {
    /* yoksay */
  }
}
