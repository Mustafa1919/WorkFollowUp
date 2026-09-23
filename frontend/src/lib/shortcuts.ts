const OPEN_EVENT = 'wf:open-shortcuts'

/** Komut paletindeki "Klavye kısayolları" eylemi gibi disaridan tetiklemek icin. */
export function openShortcuts() {
  window.dispatchEvent(new Event(OPEN_EVENT))
}

export function onShortcutsOpenRequested(handler: () => void) {
  window.addEventListener(OPEN_EVENT, handler)
  return () => window.removeEventListener(OPEN_EVENT, handler)
}

/** Input/textarea/select/contentEditable icinde yaziyorken global tek-tus kisayollari tetiklenmemeli. */
export function isTypingTarget(target: EventTarget | null) {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  return ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)
}
