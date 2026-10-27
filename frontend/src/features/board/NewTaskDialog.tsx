import { useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { useCan, useCreateTask, useMembers, useSprints, useTags } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { todayIso } from '@/lib/dates'
import { Dialog } from '@/components/ui/Dialog'
import { Field, Input, inputClass } from '@/components/ui/Input'
import { Button } from '@/components/ui/Button'
import { cn } from '@/lib/cn'
import { taskCreateBody } from './taskForm'

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
  const [expanded, setExpanded] = useState(false)
  const [description, setDescription] = useState('')
  const [assigneeId, setAssigneeId] = useState('')
  const [storyPoint, setStoryPoint] = useState('')
  const [sprintId, setSprintId] = useState('')
  const [tagIds, setTagIds] = useState<string[]>([])
  const canPlan = useCan('TASK_PLAN')
  const { data: members } = useMembers(open && expanded && canPlan)
  const { data: sprints } = useSprints(projectId)
  const { data: tags } = useTags()
  const create = useCreateTask(projectId)

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      await create.mutateAsync(taskCreateBody({ title, dueDate, description, assigneeId, storyPoint, sprintId, tagIds }, canPlan))
      toast.success('Görev eklendi')
      setTitle('')
      setDueDate(defaultDate ?? '')
      setDescription('')
      setAssigneeId('')
      setStoryPoint('')
      setSprintId('')
      setTagIds([])
      setExpanded(false)
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
        <Button type="button" variant="ghost" aria-expanded={expanded} aria-controls="nt-extra" onClick={() => setExpanded(!expanded)}>
          {expanded ? 'Daralt' : 'Genişlet'}
        </Button>
        {expanded && (
          <div id="nt-extra" className="space-y-4">
            <Field label="Açıklama" id="nt-description">
              <textarea id="nt-description" className={cn(inputClass, 'h-28 py-2')} value={description} onChange={(e) => setDescription(e.target.value)} />
            </Field>
            {canPlan && (
              <>
                <Field label="Atanan" id="nt-assignee">
                  <select id="nt-assignee" className={inputClass} value={assigneeId} onChange={(e) => setAssigneeId(e.target.value)}>
                    <option value="">Atanmamış</option>
                    {members?.filter((m) => m.role !== 'VIEWER').map((m) => <option key={m.userId} value={m.userId}>{m.fullName}</option>)}
                  </select>
                </Field>
                <Field label="Story point" id="nt-sp">
                  <Input id="nt-sp" type="number" min={0} max={100} step={1} value={storyPoint} onChange={(e) => setStoryPoint(e.target.value)} onInvalid={() => toast.error('Story point 0-100 arasında tam sayı olmalı')} />
                </Field>
                <Field label="Sprint" id="nt-sprint">
                  <select id="nt-sprint" className={inputClass} value={sprintId} onChange={(e) => setSprintId(e.target.value)}>
                    <option value="">Backlog</option>
                    {sprints?.filter((s) => s.status === 'planned' || s.status === 'active').map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                  </select>
                </Field>
              </>
            )}
            <fieldset className="space-y-2">
              <legend className="mb-1.5 text-xs font-medium text-muted">Etiketler</legend>
              {tags?.map((tag) => (
                <label key={tag.id} className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={tagIds.includes(tag.id)} disabled={!tagIds.includes(tag.id) && tagIds.length >= 20} onChange={(e) => setTagIds(e.target.checked ? [...tagIds, tag.id] : tagIds.filter((id) => id !== tag.id))} />
                  {tag.name}
                </label>
              ))}
            </fieldset>
          </div>
        )}
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
