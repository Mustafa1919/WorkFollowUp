import { useState, type FormEvent } from 'react'
import { useLocation } from 'react-router-dom'
import { MessageSquarePlus } from 'lucide-react'
import { toast } from 'sonner'
import { useSubmitFeedback } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { inputClass } from '@/components/ui/Input'
import { Dialog } from '@/components/ui/Dialog'
import { Button } from '@/components/ui/Button'

/**
 * Dalga 4 -- her sayfada erisilebilir, sabit konumlu geri bildirim girisi. Senkron/dogrudan DB
 * yazimi (FeedbackService): kullanicinin bilincli doldurdugu bir formun kaybi tolere edilemez, bu
 * yuzden telemetri (usage/error) icin secilen best-effort Kafka yolunun AKSINE burada normal bir
 * mutation + hata toast'i kullanilir.
 */
export function FeedbackWidget() {
  const [open, setOpen] = useState(false)
  const [message, setMessage] = useState('')
  const location = useLocation()
  const submit = useSubmitFeedback()

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    try {
      await submit.mutateAsync({ message, pagePath: location.pathname })
      setMessage('')
      setOpen(false)
      toast.success('Geri bildirimin için teşekkürler')
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="fixed right-5 bottom-5 z-30 flex cursor-pointer items-center gap-2 rounded-full border border-border bg-surface px-4 py-2.5 text-sm font-medium shadow-xl backdrop-blur-xl transition-colors hover:bg-surface-2"
        aria-label="Geri bildirim gönder"
      >
        <MessageSquarePlus size={16} /> Geri bildirim
      </button>
      <Dialog open={open} onOpenChange={setOpen} title="Geri bildirim gönder" description="Ne iyi gidiyor, ne bozuk — direkt bana ulaşır.">
        <form onSubmit={handleSubmit} className="space-y-4">
          <textarea
            autoFocus
            required
            maxLength={4000}
            rows={5}
            placeholder="Düşüncelerini yaz…"
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            className={`${inputClass} h-auto resize-none py-2.5`}
          />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
              Vazgeç
            </Button>
            <Button type="submit" loading={submit.isPending}>
              Gönder
            </Button>
          </div>
        </form>
      </Dialog>
    </>
  )
}
