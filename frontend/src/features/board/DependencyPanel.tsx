import * as DM from '@radix-ui/react-dropdown-menu'
import { Ban, Plus, X } from 'lucide-react'
import { toast } from 'sonner'
import { useAllTasks, useProjects, useTaskDependencyAssignment } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { STATUS_META } from '@/lib/status'
import { StatusDot } from '@/components/ui/misc'
import type { Task, TaskRef } from '@/lib/types'

/**
 * Bagimlilik (Blocked by/Blocking): kullanici karariyla SADECE bilgilendirici — durum gecisini
 * engellemez (bkz. TaskDependencyService javadoc'u), workspace geneli herhangi proje arasinda
 * kurulabilir (Subtask'in aksine proje siniri YOK).
 */
export function DependencyPanel({ task, canWrite }: { task: Task; canWrite: boolean }) {
  const { data: projects } = useProjects()
  const { data: allTasks } = useAllTasks(projects ?? [])
  const { link, unlink } = useTaskDependencyAssignment()
  const onError = (err: unknown) => toast.error(errorMessage(err))

  const linkedIds = new Set([...task.blocking, ...task.blockedBy].map((t) => t.id))
  const candidates = allTasks.filter((t) => t.id !== task.id && !linkedIds.has(t.id))

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-muted">Bağımlılıklar</span>
        {canWrite && (
          <DM.Root>
            <DM.Trigger className="inline-flex cursor-pointer items-center gap-1 rounded-full border border-dashed border-border px-2 py-0.5 text-xs text-muted transition-colors outline-none hover:border-accent/40 hover:text-fg">
              <Plus size={11} /> Bloklayan ekle
            </DM.Trigger>
            <DM.Portal>
              <DM.Content align="end" sideOffset={6} className="z-50 max-h-72 w-64 overflow-y-auto rounded-xl border border-border bg-surface p-1.5 shadow-xl">
                {candidates.length === 0 ? (
                  <div className="px-2 py-2 text-xs text-muted">Uygun görev yok.</div>
                ) : (
                  candidates.map((t) => (
                    <DM.Item
                      key={t.id}
                      onSelect={() => link.mutate({ taskId: task.id, blockingTaskId: t.id }, { onError })}
                      className="flex cursor-pointer items-center gap-2 truncate rounded-lg px-2 py-1.5 text-sm outline-none data-[highlighted]:bg-surface-2"
                    >
                      <StatusDot status={t.status} />
                      <span className="truncate">{t.title}</span>
                    </DM.Item>
                  ))
                )}
              </DM.Content>
            </DM.Portal>
          </DM.Root>
        )}
      </div>

      {task.blockedBy.length > 0 && (
        <RefList
          label="Bekleniyor (Blocked by)"
          refs={task.blockedBy}
          canWrite={canWrite}
          onRemove={(otherId) => unlink.mutate({ taskId: task.id, blockingTaskId: otherId }, { onError })}
        />
      )}
      {task.blocking.length > 0 && (
        <RefList
          label="Blokluyor (Blocking)"
          refs={task.blocking}
          canWrite={canWrite}
          onRemove={(otherId) => unlink.mutate({ taskId: otherId, blockingTaskId: task.id }, { onError })}
        />
      )}
    </div>
  )
}

function RefList({
  label,
  refs,
  canWrite,
  onRemove,
}: {
  label: string
  refs: TaskRef[]
  canWrite: boolean
  onRemove: (id: string) => void
}) {
  return (
    <div>
      <div className="mb-1.5 flex items-center gap-1.5 text-[11px] text-muted">
        <Ban size={11} /> {label}
      </div>
      <ul className="space-y-1">
        {refs.map((r) => (
          <li key={r.id} className="flex items-center gap-2 rounded-lg border border-border px-2.5 py-1.5 text-sm">
            <StatusDot status={r.status} />
            <span className="flex-1 truncate">{r.title}</span>
            <span className={STATUS_META[r.status].text}>#{r.taskNumber}</span>
            {canWrite && (
              <button
                onClick={() => onRemove(r.id)}
                className="cursor-pointer rounded-full p-0.5 text-muted hover:bg-surface-2 hover:text-danger"
                aria-label="Bağımlılığı kaldır"
              >
                <X size={12} />
              </button>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
