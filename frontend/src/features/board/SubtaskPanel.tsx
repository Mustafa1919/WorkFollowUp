import { useRef, useState } from 'react'
import * as DM from '@radix-ui/react-dropdown-menu'
import { Plus, X } from 'lucide-react'
import { toast } from 'sonner'
import { useCan, useCreateSubtask, useSubtasks, useTaskParentAssignment, useTasks } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { STATUS_META } from '@/lib/status'
import { StatusDot } from '@/components/ui/misc'
import { inputClass } from '@/components/ui/Input'
import { useCurrentUserId } from '@/stores/session'
import type { Task } from '@/lib/types'

/**
 * Alt gorevler: V19, tek seviye (subtask'in subtask'i olamaz) + ayni proje ile sinirli — bu yuzden
 * aday listesi projedeki TUM gorevler degil, henuz parent'i/cocugu OLMAYAN ve PUANSIZ gorevlerdir
 * (backend de ayni kurali TaskService#setParent'te uygular, burada sadece UI'da gereksiz 400'u onceden eler).
 * `canPlan`: mevcut gorevi baglama/ayirma (TASK_PLAN). Sifirdan olusturma ayri izinle (TASK_CREATE ya da
 * SUBTASK_CREATE_OWN + ust gorev bana atanmis).
 */
export function SubtaskPanel({ task, canPlan }: { task: Task; canPlan: boolean }) {
  const { data: children } = useSubtasks(task.id)
  const { data: siblings } = useTasks(task.projectId)
  const { setParent, removeParent } = useTaskParentAssignment(task.projectId)
  const createSubtask = useCreateSubtask(task.id, task.projectId)
  const canCreateAny = useCan('TASK_CREATE')
  const canCreateOwn = useCan('SUBTASK_CREATE_OWN')
  const me = useCurrentUserId()
  const [adding, setAdding] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const wantFocus = useRef(false)
  const [title, setTitle] = useState('')
  const onError = (err: unknown) => toast.error(errorMessage(err))

  if (task.parentTaskId) {
    // Kendisi bir subtask ise (tek seviye) baska bir gorevin alt gorevi olamaz — sadece kendi
    // ust gorevinden ayrilma secenegi gosterilir.
    return (
      <div>
        <div className="mb-2 text-xs font-medium text-muted">Üst Görev</div>
        {canPlan && (
          <button
            onClick={() => removeParent.mutate(task.id, { onError })}
            className="inline-flex cursor-pointer items-center gap-1.5 rounded-full border border-border bg-surface-2 py-0.5 pr-1.5 pl-2 text-xs hover:border-danger/40"
          >
            Üst görevden ayrıl
            <X size={10} />
          </button>
        )}
      </div>
    )
  }

  const canCreate = canCreateAny || (canCreateOwn && me != null && task.assigneeId === me)
  const childIds = new Set((children ?? []).map((c) => c.id))
  const candidates = (siblings ?? []).filter(
    (t) => t.id !== task.id && !t.parentTaskId && t.subtaskCount === 0 && t.storyPoint == null && !childIds.has(t.id),
  )

  function submit() {
    const value = title.trim()
    if (value === '' || createSubtask.isPending) return
    createSubtask.mutate(value, {
      onError,
      onSuccess: () => {
        setTitle('')
        requestAnimationFrame(() => inputRef.current?.focus())
      },
    })
  }

  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs font-medium text-muted">
          Alt Görevler {children && children.length > 0 && `(${children.filter((c) => c.status === 'Done').length}/${children.length})`}
        </span>
        {(canCreate || canPlan) && (
          <DM.Root>
            <DM.Trigger className="inline-flex cursor-pointer items-center gap-1 rounded-full border border-dashed border-border px-2 py-0.5 text-xs text-muted transition-colors outline-none hover:border-accent/40 hover:text-fg">
              <Plus size={11} /> Ekle
            </DM.Trigger>
            <DM.Portal>
              <DM.Content
                align="end"
                sideOffset={6}
                onCloseAutoFocus={(e) => {
                  if (wantFocus.current) {
                    wantFocus.current = false
                    e.preventDefault()
                    inputRef.current?.focus()
                  }
                }}
                className="z-50 max-h-72 w-64 overflow-y-auto rounded-xl border border-border bg-surface p-1.5 shadow-xl">
                {canCreate && (
                  <DM.Item
                    onSelect={() => {
                      wantFocus.current = true
                      setAdding(true)
                    }}
                    className="cursor-pointer rounded-lg px-2 py-1.5 text-sm outline-none data-[highlighted]:bg-surface-2"
                  >
                    Yeni alt görev
                  </DM.Item>
                )}
                {canPlan && (
                  <DM.Sub>
                    <DM.SubTrigger className="cursor-pointer rounded-lg px-2 py-1.5 text-sm outline-none data-[highlighted]:bg-surface-2">
                      Mevcut görevi bağla
                    </DM.SubTrigger>
                    <DM.Portal>
                      <DM.SubContent
                        sideOffset={6}
                        className="z-50 max-h-72 w-64 overflow-y-auto rounded-xl border border-border bg-surface p-1.5 shadow-xl"
                      >
                        {candidates.length === 0 ? (
                          <div className="px-2 py-2 text-xs text-muted">Uygun görev yok.</div>
                        ) : (
                          candidates.map((c) => (
                            <DM.Item
                              key={c.id}
                              onSelect={() => setParent.mutate({ taskId: c.id, parentTaskId: task.id }, { onError })}
                              className="flex cursor-pointer items-center gap-2 truncate rounded-lg px-2 py-1.5 text-sm outline-none data-[highlighted]:bg-surface-2"
                            >
                              <StatusDot status={c.status} />
                              <span className="truncate">{c.title}</span>
                            </DM.Item>
                          ))
                        )}
                      </DM.SubContent>
                    </DM.Portal>
                  </DM.Sub>
                )}
              </DM.Content>
            </DM.Portal>
          </DM.Root>
        )}
      </div>
      {children && children.length > 0 && (
        <ul className="space-y-1">
          {children.map((c) => (
            <li key={c.id} className="flex items-center gap-2 rounded-lg border border-border px-2.5 py-1.5 text-sm">
              <StatusDot status={c.status} />
              <span className="flex-1 truncate">{c.title}</span>
              <span className={STATUS_META[c.status].text}>#{c.taskNumber}</span>
              {canPlan && (
                <button
                  onClick={() => removeParent.mutate(c.id, { onError })}
                  className="cursor-pointer rounded-full p-0.5 text-muted hover:bg-surface-2 hover:text-danger"
                  aria-label="Üst görevden ayır"
                >
                  <X size={12} />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {adding && canCreate && (
        <input
          ref={inputRef}
          autoFocus
          value={title}
          maxLength={200}
          disabled={createSubtask.isPending}
          onChange={(e) => setTitle(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              submit()
            } else if (e.key === 'Escape') {
              e.preventDefault()
              e.stopPropagation()
              setAdding(false)
              setTitle('')
            }
          }}
          placeholder="Alt görev başlığı… (Enter ekler, Esc iptal)"
          className={`${inputClass} mt-2 text-sm`}
        />
      )}
    </div>
  )
}
