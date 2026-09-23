import { useEffect, useState } from 'react'
import { Dialog } from '@/components/ui/Dialog'
import { isTypingTarget, onShortcutsOpenRequested } from '@/lib/shortcuts'

interface Shortcut {
  keys: string[]
  description: string
}

interface Group {
  title: string
  shortcuts: Shortcut[]
}

const GROUPS: Group[] = [
  {
    title: 'Genel',
    shortcuts: [
      { keys: ['Ctrl', 'K'], description: 'Komut paletini aç / kapat' },
      { keys: ['?'], description: 'Bu kısayol listesini aç' },
      { keys: ['Esc'], description: 'Açık pencereyi veya paleti kapat' },
    ],
  },
  {
    title: 'Komut paleti açıkken',
    shortcuts: [
      { keys: ['↑'], description: 'Bir önceki sonuca geç' },
      { keys: ['↓'], description: 'Bir sonraki sonuca geç' },
      { keys: ['Enter'], description: 'Seçili sonucu aç' },
    ],
  },
]

/**
 * "?" ile acilan salt-bilgi amacli kisayol listesi (komut paletinden de erisilebilir).
 * Mac'te de fiziksel tus ayni oldugundan (Ctrl), tek bir gosterim yeterli — uygulamanin geri
 * kalaninda da platform ayrimi yapilmiyor (bkz. header'daki "Ctrl K" ipucu).
 */
export function ShortcutsDialog() {
  const [open, setOpen] = useState(false)

  useEffect(() => onShortcutsOpenRequested(() => setOpen(true)), [])

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === '?' && !isTypingTarget(e.target)) {
        e.preventDefault()
        setOpen(true)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  return (
    <Dialog open={open} onOpenChange={setOpen} title="Klavye kısayolları">
      <div className="space-y-5">
        {GROUPS.map((group) => (
          <div key={group.title}>
            <div className="mb-2 text-xs font-medium text-muted">{group.title}</div>
            <div className="space-y-1.5">
              {group.shortcuts.map((s) => (
                <div key={s.description} className="flex items-center justify-between gap-4 text-sm">
                  <span className="text-fg">{s.description}</span>
                  <span className="flex shrink-0 items-center gap-1">
                    {s.keys.map((k) => (
                      <kbd key={k} className="rounded-md border border-border bg-surface-2 px-1.5 py-0.5 font-mono text-xs text-muted">
                        {k}
                      </kbd>
                    ))}
                  </span>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </Dialog>
  )
}
