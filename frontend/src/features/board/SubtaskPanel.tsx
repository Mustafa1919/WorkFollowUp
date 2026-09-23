import * as DM from '@radix-ui/react-dropdown-menu'
import { Plus, X } from 'lucide-react'
import { toast } from 'sonner'
import { useSubtasks, useTaskParentAssignment, useTasks } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { STATUS_META } from '@/lib/status'
import { StatusDot } from '@/components/ui/misc'
import type { Task } from '@/lib/types'

/**
 * Alt gorevler: V19, tek seviye (subtask'in subtask'i olamaz) + ayni proje ile sinirli — bu yuzden
 * aday listesi projedeki TUM gorevler degil, henuz parent'i/cocugu OLMAYAN gorevlerdir (backend de
 * ayni kurali TaskService#setParent'te uygular, burada sadece UI'da gereksiz 400'u onceden eler).
 */
export function SubtaskPanel({ task, canWrite }: { task: Task; canWrite: boolean }) {
  const { data: children } = useSubtasks(task.id)
  const { data: siblings } = useTasks(task.projectId)
  const { setParent, removeParent } = useTaskParentAssignment(task.projectId)
  const onError = (err: unknown) => toast.error(errorMessage(err))

  if (task.parentTaskId) {
    // Kendisi bir subtask ise (tek seviye) baska bir gorevin alt gorevi olamaz — sadece kendi
    // ust gorevinden ayrilma secenegi gosterilir.
    return (
      <div>
        <div className="mb-2 text-xs font-medium text-muted">Üst Görev</div>
        {canWrite && (
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

  const candidates = (siblings ?? []).filter(
    (t) => t.id !== task.id && !t.parentTaskId && t.subtaskCount === 0,
  )
  const childIds = new Set((children ?? []).map((c) => c.id))

  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs font-medium text-muted">
          Alt Görevler {children && children.length > 0 && `(${children.filter((c) => c.status === 'Done').length}/${children.length})`}
        </span>
        {canWrite && (
          <DM.Root>
            <DM.Trigger className="inline-flex cursor-pointer items-center gap-1 rounded-full border border-dashed border-border px-2 py-0.5 text-xs text-muted transition-colors outline-none hover:border-accent/40 hover:text-fg">
              <Plus size={11} /> Ekle
            </DM.Trigger>
            <DM.Portal>
              <DM.Content align="end" sideOffset={6} className="z-50 max-h-72 w-64 overflow-y-auto rounded-xl border border-border bg-surface p-1.5 shadow-xl">
                {candidates.filter((c) => !childIds.has(c.id)).length === 0 ? (
                  <div className="px-2 py-2 text-xs text-muted">Uygun görev yok.</div>
                ) : (
                  candidates
                    .filter((c) => !childIds.has(c.id))
                    .map((c) => (
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
              {canWrite && (
                <button
                  onClick={() => removeParent.mutate(c.id, { onError })}
                  className="cursor-pointer rounded-full p-0.5 text-muted hover:bg-surface-2 hover:text-danger"
                  aria-label="Alt görevi kaldır"
                >
                  <X size={12} />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
