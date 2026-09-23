import { useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { useCreateTask } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { todayIso } from '@/lib/dates'
import { Dialog } from '@/components/ui/Dialog'
import { Field, Input } from '@/components/ui/Input'
import { Button } from '@/components/ui/Button'

export function NewTaskDialog({
  projectId,
  open,
  onOpenChange,
  defaultDate,
}: {
  projectId: string
  open: boolean
  onOpenChange: (o: boolean) => void
  defaultDate?: string
}) {
  const [title, setTitle] = useState('')
  const [dueDate, setDueDate] = useState(defaultDate ?? '')
  const create = useCreateTask(projectId)

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      await create.mutateAsync({ title, dueDate: dueDate || null })
      toast.success('Görev eklendi')
      setTitle('')
      setDueDate(defaultDate ?? '')
      onOpenChange(false)
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="Yeni görev">
      <form onSubmit={submit} className="space-y-4">
        <Field label="Başlık" id="nt-title">
          <Input id="nt-title" autoFocus required maxLength={255} value={title} onChange={(e) => setTitle(e.target.value)} />
        </Field>
        <Field label="Tarih (opsiyonel, takvimde görünür)" id="nt-due">
          <Input id="nt-due" type="date" min={todayIso()} value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
        </Field>
        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
            Vazgeç
          </Button>
          <Button type="submit" loading={create.isPending}>
            Ekle
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
