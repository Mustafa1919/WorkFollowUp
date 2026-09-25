import { useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { useSavedViewActions } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { Dialog } from '@/components/ui/Dialog'
import { Field, Input } from '@/components/ui/Input'
import { Button } from '@/components/ui/Button'

/** Dalga 1.7 — mevcut Kanban filtresini (sprint/etiket/atanan) kisisel bir gorunum olarak kaydeder. */
export function SaveViewDialog({
  projectId,
  query,
  open,
  onOpenChange,
}: {
  projectId: string
  query: string
  open: boolean
  onOpenChange: (o: boolean) => void
}) {
  const [name, setName] = useState('')
  const { create } = useSavedViewActions(projectId)

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      await create.mutateAsync({ name, query })
      toast.success('Görünüm kaydedildi')
      setName('')
      onOpenChange(false)
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="Görünümü kaydet">
      <form onSubmit={submit} className="space-y-4">
        <Field label="İsim" id="sv-name">
          <Input id="sv-name" autoFocus required maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <p className="text-xs text-muted">Aktif sprint, etiket ve atanan filtresi bu isimle kaydedilir.</p>
        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
            Vazgeç
          </Button>
          <Button type="submit" loading={create.isPending}>
            Kaydet
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
