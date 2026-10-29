import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { keys, useMe } from '@/api/queries'
import { api, errorMessage } from '@/lib/api'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { PrivacyNoticeContent } from './PrivacyNoticeContent'
import { PRIVACY_NOTICE_VERSION } from './privacyNotice'

export function PrivacyNoticeGate() {
  const me = useMe()
  const queryClient = useQueryClient()
  const [accepted, setAccepted] = useState(false)
  const [loading, setLoading] = useState(false)
  const open = me.isSuccess && me.data.privacyNoticeVersion !== PRIVACY_NOTICE_VERSION

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!accepted || loading) return
    setLoading(true)
    try {
      await api.post('/api/v1/me/privacy-notice-acceptance', { version: PRIVACY_NOTICE_VERSION })
      await queryClient.invalidateQueries({ queryKey: keys.me })
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setLoading(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={() => {}} dismissible={false}
      title="Kişisel Verilerin Korunması Aydınlatma Metni"
      description="Devam etmek için güncel aydınlatma metnini okuyup anladığınızı onaylayın.">
      <form onSubmit={submit} className="space-y-5">
        <PrivacyNoticeContent />
        <label className="flex items-start gap-2 text-sm">
          <input type="checkbox" required checked={accepted} onChange={(event) => setAccepted(event.target.checked)} className="mt-1 accent-accent" />
          <span>
            <a href="/kvkk" target="_blank" rel="noopener noreferrer" className="text-accent hover:underline">Aydınlatma metnini</a>{' '}
            okudum ve anladım.
          </span>
        </label>
        <Button type="submit" disabled={!accepted} loading={loading}>Onayla</Button>
      </form>
    </Dialog>
  )
}
