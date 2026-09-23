import { useState, type FormEvent } from 'react'
import { addDays } from 'date-fns'
import { Play, Flag } from 'lucide-react'
import { toast } from 'sonner'
import { useSprintActions, useSprints } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { fmt, iso } from '@/lib/dates'
import { cn } from '@/lib/cn'
import { Dialog } from '@/components/ui/Dialog'
import { Field, Input } from '@/components/ui/Input'
import { Button } from '@/components/ui/Button'

const BADGE = {
  planned: 'bg-surface-2 text-muted',
  active: 'bg-st-progress/15 text-st-progress',
  completed: 'bg-st-done/15 text-st-done',
}
const LABEL = { planned: 'Planlandı', active: 'Aktif', completed: 'Tamamlandı' }

export function SprintsDialog({
  projectId,
  open,
  onOpenChange,
  canManage,
}: {
  projectId: string
  open: boolean
  onOpenChange: (o: boolean) => void
  canManage: boolean
}) {
  const { data: sprints } = useSprints(projectId)
  const actions = useSprintActions(projectId)
  const today = new Date()
  const [name, setName] = useState('')
  const [startDate, setStartDate] = useState(iso(today))
  const [endDate, setEndDate] = useState(iso(addDays(today, 13)))
  const onError = (err: unknown) => toast.error(errorMessage(err))

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      await actions.create.mutateAsync({ name, startDate, endDate })
      setName('')
      toast.success('Sprint oluşturuldu')
    } catch (err) {
      onError(err)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="Sprintler" description="Tamamlanan sprintler velocity grafiğine girer.">
      <ul className="mb-6 max-h-64 space-y-2 overflow-y-auto">
        {sprints?.length === 0 && <li className="py-4 text-center text-sm text-muted">Henüz sprint yok.</li>}
        {sprints?.map((s) => (
          <li key={s.id} className="flex items-center gap-3 rounded-xl border border-border p-3">
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-medium">{s.name}</div>
              <div className="text-xs text-muted">
                {fmt(s.startDate, 'd MMM')} – {fmt(s.endDate, 'd MMM yyyy')}
              </div>
            </div>
            <span className={cn('rounded-md px-2 py-0.5 text-xs', BADGE[s.status])}>{LABEL[s.status]}</span>
            {canManage && s.status === 'planned' && (
              <Button size="sm" variant="outline" onClick={() => actions.start.mutate(s.id, { onError })} title="Başlat">
                <Play size={13} />
              </Button>
            )}
            {canManage && s.status === 'active' && (
              <Button size="sm" variant="outline" onClick={() => actions.complete.mutate(s.id, { onError })} title="Tamamla">
                <Flag size={13} />
              </Button>
            )}
          </li>
        ))}
      </ul>
      {canManage && (
        <form onSubmit={submit} className="space-y-3 border-t border-border pt-5">
          <Field label="Yeni sprint adı" id="s-name">
            <Input id="s-name" required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} placeholder="ör. Sprint 12" />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Başlangıç" id="s-start">
              <Input id="s-start" type="date" required value={startDate} onChange={(e) => setStartDate(e.target.value)} />
            </Field>
            <Field label="Bitiş" id="s-end">
              <Input id="s-end" type="date" required value={endDate} onChange={(e) => setEndDate(e.target.value)} />
            </Field>
          </div>
          <Button type="submit" loading={actions.create.isPending} className="w-full justify-center">
            Sprint oluştur
          </Button>
        </form>
      )}
    </Dialog>
  )
}
