import { useState } from 'react'
import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import { AnimatePresence, motion } from 'motion/react'
import { toast } from 'sonner'
import { useCurrentRole, useTaskLifecycle, useUpdateTask } from '@/api/queries'
import { CheckCircle2 } from 'lucide-react'
import { errorMessage } from '@/lib/api'
import { cn } from '@/lib/cn'
import { TASK_STATUSES, type Task, type TaskStatus } from '@/lib/types'
import { StatusDot } from '@/components/ui/misc'
import { STATUS_META } from '@/lib/status'
import { TaskCard } from './TaskCard'

interface Props {
  projectId: string
  projectKey?: string
  tasks: Task[]
  canWrite: boolean
  onOpen: (task: Task) => void
}

/** Kolonlar arasi surukle-birak = durum degisikligi (optimistic; hata olursa kart geri doner). */
export function KanbanView({ projectId, projectKey, tasks, canWrite, onOpen }: Props) {
  const update = useUpdateTask(projectId)
  const role = useCurrentRole()
  const canApprove = role === 'WORKSPACE_ADMIN' || role === 'MANAGER'
  const { approve } = useTaskLifecycle(projectId)
  const onApprove = (t: Task) =>
    approve.mutate(t.id, {
      onError: (err) => toast.error(errorMessage(err)),
      onSuccess: () => toast.success(`'${t.title}' onaylandı, Tamamlananlar'a taşındı`),
    })
  const [active, setActive] = useState<Task | null>(null)
  // 6px esigi: kisa tiklama karti acar, surukleme baslatmaz.
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }), useSensor(KeyboardSensor))

  function onDragEnd(e: DragEndEvent) {
    setActive(null)
    const task = tasks.find((t) => t.id === e.active.id)
    const status = e.over?.id as TaskStatus | undefined
    if (!task || !status || task.status === status) return
    update.mutate(
      { id: task.id, patch: { status } },
      { onError: (err) => toast.error(errorMessage(err)) },
    )
  }

  return (
    <DndContext
      sensors={sensors}
      onDragStart={(e) => setActive(tasks.find((t) => t.id === e.active.id) ?? null)}
      onDragEnd={onDragEnd}
      onDragCancel={() => setActive(null)}
    >
      <div className="-mx-4 flex snap-x gap-4 overflow-x-auto px-4 pb-4 sm:mx-0 sm:px-0 md:grid md:grid-cols-4 md:overflow-visible">
        {TASK_STATUSES.map((status) => (
          <Column
            key={status}
            status={status}
            tasks={tasks.filter((t) => t.status === status)}
            projectKey={projectKey}
            canWrite={canWrite}
            onOpen={onOpen}
            onApprove={status === 'Done' && canApprove ? onApprove : undefined}
          />
        ))}
      </div>
      <DragOverlay dropAnimation={{ duration: 220, easing: 'cubic-bezier(.2,.8,.2,1)' }}>
        {active && <TaskCard task={active} projectKey={projectKey} overlay />}
      </DragOverlay>
    </DndContext>
  )
}

function Column({
  status,
  tasks,
  projectKey,
  canWrite,
  onOpen,
  onApprove,
}: {
  status: TaskStatus
  tasks: Task[]
  projectKey?: string
  canWrite: boolean
  onOpen: (t: Task) => void
  /** Yalniz Done kolonunda ve onay yetkisi varsa: onaylanan gorev Kanban'dan kalkar. */
  onApprove?: (t: Task) => void
}) {
  const { setNodeRef, isOver } = useDroppable({ id: status })
  return (
    <section
      ref={setNodeRef}
      className={cn(
        'flex w-[80vw] max-w-80 shrink-0 snap-start flex-col rounded-2xl border border-border bg-surface-2/50 p-2 transition-colors md:w-auto md:max-w-none',
        isOver && 'border-accent/60 bg-accent-soft/60',
      )}
    >
      <header className="flex items-center gap-2 px-2 pt-1 pb-3">
        <StatusDot status={status} />
        <h3 className="text-sm font-medium">{STATUS_META[status].label}</h3>
        <span className="ml-auto rounded-md bg-surface px-1.5 text-xs text-muted tabular-nums">{tasks.length}</span>
      </header>
      <div className="flex min-h-40 flex-1 flex-col gap-2">
        <AnimatePresence initial={false}>
          {tasks.map((t) => (
            <motion.div
              key={t.id}
              layout
              initial={{ opacity: 0, scale: 0.96 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.96 }}
              transition={{ type: 'spring', stiffness: 500, damping: 40 }}
            >
              <DraggableCard task={t} projectKey={projectKey} disabled={!canWrite} onOpen={onOpen} />
              {onApprove && (
                <button
                  onClick={() => onApprove(t)}
                  className="mt-1 flex w-full cursor-pointer items-center justify-center gap-1.5 rounded-lg py-1 text-xs text-st-done transition-colors hover:bg-st-done/15"
                >
                  <CheckCircle2 size={13} /> Onayla
                </button>
              )}
            </motion.div>
          ))}
        </AnimatePresence>
        {tasks.length === 0 && (
          <div className="grid flex-1 place-items-center rounded-xl border border-dashed border-border py-8 text-xs text-muted">
            Buraya sürükle
          </div>
        )}
      </div>
    </section>
  )
}

function DraggableCard({ task, projectKey, disabled, onOpen }: { task: Task; projectKey?: string; disabled: boolean; onOpen: (t: Task) => void }) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: task.id, disabled })
  return (
    <TaskCard
      ref={setNodeRef}
      task={task}
      projectKey={projectKey}
      dragging={isDragging}
      onClick={() => onOpen(task)}
      {...attributes}
      {...listeners}
    />
  )
}
