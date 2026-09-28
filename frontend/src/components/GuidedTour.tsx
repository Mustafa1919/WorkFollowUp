import { useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { useSession } from '@/stores/session'
import { hasTourBeenSeen, markTourSeen, onGuidedTourOpenRequested } from '@/lib/guidedTour'

interface Step {
  /** Hedeflenen elemanin data-tour degeri; yoksa kart ekran ortasinda gosterilir (giris/kapanis adimlari). */
  target?: string
  title: string
  body: string
}

const STEPS: Step[] = [
  {
    title: "WorkFollowUp'a hoş geldin",
    body: 'İş takibin ve ekibinin çalışması burada yaşayacak. 5 adımda nerede ne olduğunu göstereyim — istediğin an "Atla" diyebilirsin.',
  },
  {
    target: 'tour-nav',
    title: 'Gezinme',
    body: "Ana sayfa, sana atanan işler, toplantılar, standup'lar ve raporlar buradan bir tık uzakta.",
  },
  {
    target: 'tour-projects',
    title: 'Projeler',
    body: "Workspace'indeki tüm projeler burada listelenir. Yetkin varsa yanındaki + ile yenisini açarsın.",
  },
  {
    target: 'tour-search',
    title: 'Hızlı arama',
    body: "Ctrl+K (veya buraya tıkla) — görev, proje ve komutlara her sayfadan aynı yerden ulaşırsın.",
  },
  {
    target: 'tour-notifications',
    title: 'Bildirimler',
    body: 'Sana atanan işler, yorumlarda etiketlenmen ve gecikmiş görev uyarıları buradan gelir.',
  },
  {
    target: 'tour-settings',
    title: 'Ayarlar',
    body: "Üyeler, entegrasyonlar ve kişisel tercihler burada. Bu turu istediğin zaman Ayarlar'dan tekrar açabilirsin.",
  },
]

const CARD_WIDTH = 300
const MARGIN = 12

/**
 * Ilk workspace'ine giren kullaniciya bir kereligine gosterilen spotlight tur — `data-tour`
 * ile isaretli elemanlarin etrafini karartip sirayla aciklar. Hedef bulunamazsa (mobilde
 * sidebar/arama gizli) kart ortalanmis halde, spotlight'siz gosterilir; akis bozulmaz.
 */
export function GuidedTour() {
  const email = useSession((s) => s.email)
  const [open, setOpen] = useState(false)
  const [step, setStep] = useState(0)
  const [rect, setRect] = useState<DOMRect | null>(null)

  useEffect(
    () =>
      onGuidedTourOpenRequested(() => {
        setStep(0)
        setOpen(true)
      }),
    [],
  )

  // Sayfa/nav/proje listesi yerlesene kadar kisa bir gecikmeyle, kullanici basina bir kere.
  useEffect(() => {
    if (hasTourBeenSeen(email)) return
    const timer = setTimeout(() => {
      setStep(0)
      setOpen(true)
    }, 800)
    return () => clearTimeout(timer)
  }, [email])

  useEffect(() => {
    if (!open) return
    function measure() {
      const target = STEPS[step].target
      const el = target ? document.querySelector<HTMLElement>(`[data-tour="${target}"]`) : null
      setRect(el && el.offsetParent !== null ? el.getBoundingClientRect() : null)
    }
    measure()
    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [open, step])

  function close() {
    setOpen(false)
    markTourSeen(email)
  }

  function next() {
    if (step >= STEPS.length - 1) {
      close()
      return
    }
    setStep((s) => s + 1)
  }

  if (!open) return null
  const current = STEPS[step]
  const cardPos = rect ? placeCard(rect) : null

  return (
    <div className="fixed inset-0 z-[70]">
      {rect ? (
        <div
          className="pointer-events-none fixed rounded-xl transition-all duration-300"
          style={{
            top: rect.top - 6,
            left: rect.left - 6,
            width: rect.width + 12,
            height: rect.height + 12,
            boxShadow: '0 0 0 9999px rgba(0,0,0,0.6)',
          }}
        />
      ) : (
        <div className="fixed inset-0 bg-black/60" />
      )}
      <AnimatePresence mode="wait">
        <motion.div
          key={step}
          initial={{ opacity: 0, y: 8, scale: 0.98 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.2 }}
          className="fixed z-10 rounded-2xl border border-border bg-surface p-5 shadow-2xl"
          style={{ width: CARD_WIDTH, ...(cardPos ?? { top: '50%', left: '50%', transform: 'translate(-50%, -50%)' }) }}
        >
          <button
            onClick={close}
            className="absolute top-3 right-3 cursor-pointer rounded-lg p-1 text-muted hover:bg-surface-2"
            aria-label="Turu kapat"
          >
            <X size={14} />
          </button>
          <p className="mb-1 text-xs font-medium text-accent">
            {step + 1}/{STEPS.length}
          </p>
          <h3 className="mb-1.5 pr-4 font-semibold">{current.title}</h3>
          <p className="mb-4 text-sm text-muted">{current.body}</p>
          <div className="flex items-center justify-between">
            <button onClick={close} className="cursor-pointer text-xs text-muted hover:text-fg">
              Atla
            </button>
            <Button size="sm" onClick={next}>
              {step >= STEPS.length - 1 ? 'Bitir' : 'İleri'}
            </Button>
          </div>
        </motion.div>
      </AnimatePresence>
    </div>
  )
}

function placeCard(rect: DOMRect): { top: number; left: number } {
  const spaceRight = window.innerWidth - rect.right
  const fitsRight = spaceRight > CARD_WIDTH + MARGIN * 2
  const left = fitsRight
    ? rect.right + MARGIN
    : Math.max(MARGIN, Math.min(rect.left, window.innerWidth - CARD_WIDTH - MARGIN))
  const top = fitsRight ? Math.min(rect.top, window.innerHeight - 220) : Math.min(rect.bottom + MARGIN, window.innerHeight - 220)
  return { top, left }
}
