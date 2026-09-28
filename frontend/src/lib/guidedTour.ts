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

function seenKey(email: string | null) {
  return `${SEEN_PREFIX}${email ?? 'anon'}`
}

/** localStorage erisimi basarisiz olursa (gizli sekme vb.) tur tekrar tekrar acilmasin diye "gorulmus" sayilir. */
export function hasTourBeenSeen(email: string | null): boolean {
  try {
    return localStorage.getItem(seenKey(email)) === '1'
  } catch {
    return true
  }
}

export function markTourSeen(email: string | null) {
  try {
    localStorage.setItem(seenKey(email), '1')
  } catch {
    /* yoksay */
  }
}
