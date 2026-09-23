const OPEN_EVENT = 'wf:open-command-palette'

/** Header'daki arama butonu gibi disaridan tetiklemek icin (state AppLayout'a tasinmadan). */
export function openCommandPalette() {
  window.dispatchEvent(new Event(OPEN_EVENT))
}

export function onCommandPaletteOpenRequested(handler: () => void) {
  window.addEventListener(OPEN_EVENT, handler)
  return () => window.removeEventListener(OPEN_EVENT, handler)
}
