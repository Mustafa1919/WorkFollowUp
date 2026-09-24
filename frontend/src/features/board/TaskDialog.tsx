import { useState } from 'react'
import { CheckCircle2, RotateCcw, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { useCurrentRole, useTaskLifecycle, useTaskTagAssignment, useUpdateStoryPoint, useUpdateTask } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { fmt, todayIso } from '@/lib/dates'
import { cn } from '@/lib/cn'
import { TASK_STATUSES, type Sprint, type Task } from '@/lib/types'
import { Dialog } from '@/components/ui/Dialog'
import { Field, Input, inputClass } from '@/components/ui/Input'
import { Button } from '@/components/ui/Button'
import { StatusDot, TagChip } from '@/components/ui/misc'
import { STATUS_META, taskKey } from '@/lib/status'
import { CommentPanel } from './CommentPanel'
import { DependencyPanel } from './DependencyPanel'
import { SubtaskPanel } from './SubtaskPanel'
import { TagPicker } from './TagPicker'
import { AssigneeField, DescriptionField, WatchButton } from './TaskPeoplePanel'

interface Props {
  task: Task | null
  projectKey?: string
  sprints: Sprint[]
  canWrite: boolean
  onOpenChange: (open: boolean) => void
}

/** Gorev detayi: her alan degisikligi aninda kaydedilir (ayri "Kaydet" adimi yok). */
export function TaskDialog({ task, projectKey, sprints, canWrite, onOpenChange }: Props) {
  return (
    <Dialog
      open={!!task}
      onOpenChange={onOpenChange}
      title={task?.title ?? ''}
      description={task ? `${taskKey(projectKey, task.taskNumber)} · oluşturuldu ${fmt(task.createdAt, 'd MMM yyyy')}` : undefined}
    >
      {task && (
        <>
          <TaskFields
            key={task.id}
            task={task}
            sprints={sprints}
            canWrite={canWrite && !task.approvedAt}
            // Yorum yazmak is verisini degistirmez; onayli gorevde de acik kalir (ADR-0009 madde 8).
            canComment={canWrite}
          />
          <TaskLifecycle key={`lc-${task.id}`} task={task} onClosed={() => onOpenChange(false)} />
        </>
      )}
    </Dialog>
  )
}

function TaskFields({
  task,
  sprints,
  canWrite,
  canComment,
}: {
  task: Task
  sprints: Sprint[]
  canWrite: boolean
  canComment: boolean
}) {
  const update = useUpdateTask(task.projectId)
  const storyPoint = useUpdateStoryPoint(task.projectId)
  const { unassign } = useTaskTagAssignment(task.projectId)
  // TaskDialog bu bileseni her gorev icin `key={task.id}` ile yeniden mount eder, bu yuzden lazy
  // init GUVENLI (task degisince state sifirlanir, eski gorevin degeriyle karismaz).
  const [sp, setSp] = useState(() => task.storyPoint?.toString() ?? '')

  const onError = (err: unknown) => toast.error(errorMessage(err))
  const assignable = sprints.filter((s) => s.status !== 'completed' || s.id === task.sprintId)

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-0 flex-1">
          <AssigneeField task={task} canWrite={canWrite} />
        </div>
        <WatchButton task={task} />
      </div>

      <DescriptionField task={task} canWrite={canWrite} />

      <div>
        <div className="mb-2 text-xs font-medium text-muted">Etiketler</div>
        <div className="flex flex-wrap items-center gap-1.5">
          {task.tags.map((tag) => (
            <TagChip key={tag.id} tag={tag} onRemove={canWrite ? () => unassign.mutate({ taskId: task.id, tagId: tag.id }, { onError }) : undefined} />
          ))}
          {canWrite && <TagPicker task={task} />}
        </div>
      </div>

      <div>
        <div className="mb-2 text-xs font-medium text-muted">Durum</div>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {TASK_STATUSES.map((s) => (
            <button
              key={s}
              disabled={!canWrite}
              onClick={() => s !== task.status && update.mutate({ id: task.id, patch: { status: s } }, { onError })}
              className={cn(
                'flex cursor-pointer items-center justify-center gap-1.5 rounded-xl border px-2 py-2 text-xs transition-all disabled:cursor-default',
                s === task.status
                  ? `border-transparent ${STATUS_META[s].soft} ${STATUS_META[s].text} font-medium`
                  : 'border-border text-muted hover:border-accent/40 hover:text-fg',
              )}
            >
              <StatusDot status={s} />
              {STATUS_META[s].label}
            </button>
          ))}
        </div>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Tarih (takvim)" id="t-due">
          <div className="flex gap-2">
            <Input
              id="t-due"
              type="date"
              min={todayIso()}
              disabled={!canWrite}
              value={task.dueDate ?? ''}
              onChange={(e) => update.mutate({ id: task.id, patch: { dueDate: e.target.value || null } }, { onError })}
            />
            {task.dueDate && canWrite && (
              <Button variant="ghost" size="sm" className="h-10" onClick={() => update.mutate({ id: task.id, patch: { dueDate: null } }, { onError })}>
                Kaldır
              </Button>
            )}
          </div>
        </Field>
        <Field label="Sprint" id="t-sprint">
          <select
            id="t-sprint"
            disabled={!canWrite}
            value={task.sprintId ?? ''}
            onChange={(e) => update.mutate({ id: task.id, patch: { sprintId: e.target.value || null } }, { onError })}
            className={cn(inputClass, 'cursor-pointer')}
          >
            <option value="">Backlog</option>
            {assignable.map((s) => (
              <option key={s.id} value={s.id} disabled={s.status === 'completed'}>
                {s.name}
              </option>
            ))}
          </select>
        </Field>
      </div>

      {canWrite && (
        <form
          className="flex items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            storyPoint.mutate(
              { id: task.id, storyPoint: sp === '' ? null : Number(sp) },
              { onError, onSuccess: () => toast.success('Story point kaydedildi') },
            )
          }}
        >
          <div className="flex-1">
            <Field label="Story point (boş = kaldır)" id="t-sp">
              <Input id="t-sp" type="number" min={0} max={100} value={sp} onChange={(e) => setSp(e.target.value)} placeholder="ör. 3" />
            </Field>
          </div>
          <Button type="submit" variant="outline" loading={storyPoint.isPending}>
            Kaydet
          </Button>
        </form>
      )}

      <div className="border-t border-border pt-4">
        <SubtaskPanel task={task} canWrite={canWrite} />
      </div>

      <div className="border-t border-border pt-4">
        <DependencyPanel task={task} canWrite={canWrite} />
      </div>

      <div className="border-t border-border pt-4">
        <CommentPanel task={task} canWrite={canComment} />
      </div>
    </div>
  )
}

/**
 * Onay (ADMIN/MANAGER, yalniz Done) ve silme (yalniz ADMIN). Butonlar yalniz UI kolayligi; asil
 * yetki kontrolu backend'de.
 */
function TaskLifecycle({ task, onClosed }: { task: Task; onClosed: () => void }) {
  const role = useCurrentRole()
  const { approve, revoke, remove } = useTaskLifecycle(task.projectId)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const canApprove = role === 'WORKSPACE_ADMIN' || role === 'MANAGER'
  const canDelete = role === 'WORKSPACE_ADMIN'
  const onError = (err: unknown) => toast.error(errorMessage(err))

  if (!canApprove && !canDelete && !task.approvedAt) return null

  return (
    <div className="mt-5 space-y-3 border-t border-border pt-4">
      {task.approvedAt && (
        <div className="flex items-center gap-2 rounded-xl bg-st-done/15 px-3 py-2 text-xs text-st-done">
          <CheckCircle2 size={14} />
          {fmt(task.approvedAt, 'd MMM yyyy HH:mm')} tarihinde onaylandı — Kanban'da gösterilmez.
        </div>
      )}
      <div className="flex flex-wrap items-center gap-2">
        {canApprove && task.status === 'Done' && !task.approvedAt && (
          <Button
            size="sm"
            loading={approve.isPending}
            onClick={() =>
              approve.mutate(task.id, {
                onError,
                onSuccess: () => {
                  toast.success('Görev onaylandı, Tamamlananlar\'a taşındı')
                  onClosed()
                },
              })
            }
          >
            <CheckCircle2 size={15} /> Onayla
          </Button>
        )}
        {canApprove && task.approvedAt && (
          <Button
            size="sm"
            variant="outline"
            loading={revoke.isPending}
            onClick={() => revoke.mutate(task.id, { onError, onSuccess: () => toast.success('Onay geri alındı') })}
          >
            <RotateCcw size={15} /> Onayı geri al
          </Button>
        )}
        {canDelete &&
          (confirmDelete ? (
            <div className="ml-auto flex items-center gap-2 text-xs">
              <span className="text-danger">Görev silinsin mi?</span>
              <Button size="sm" variant="ghost" onClick={() => setConfirmDelete(false)}>
                Vazgeç
              </Button>
              <Button
                size="sm"
                variant="danger"
                loading={remove.isPending}
                onClick={() =>
                  remove.mutate(task.id, {
                    onError,
                    onSuccess: () => {
                      toast.success('Görev silindi')
                      onClosed()
                    },
                  })
                }
              >
                Sil
              </Button>
            </div>
          ) : (
            <Button size="sm" variant="ghost" className="ml-auto text-danger" onClick={() => setConfirmDelete(true)}>
              <Trash2 size={15} /> Sil
            </Button>
          ))}
      </div>
    </div>
  )
}
