import { useMemo, useState } from 'react'
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
import { CheckCircle2, X } from 'lucide-react'
import { useBulkTaskAction, useCurrentRole, useMembers, useTags, useSprints, useTaskLifecycle, useUpdateTask } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { cn } from '@/lib/cn'
import { TASK_STATUSES, type BulkOperation, type Task, type TaskStatus } from '@/lib/types'
import { Button } from '@/components/ui/Button'
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

  // Dalga 1.7: coklu-secim (Ctrl/Cmd/Shift+tik — ikisi de AYNI basit toggle, gercek "araligi sec"
  // davranisi kolonlar arasi siralama karmasikligini kaldirmaya degmedi).
  const [selected, setSelected] = useState<Set<string>>(new Set())
  function toggleSelect(taskId: string) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(taskId)) next.delete(taskId)
      else next.add(taskId)
      return next
    })
  }
  const selectedTasks = useMemo(() => tasks.filter((t) => selected.has(t.id)), [tasks, selected])

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
            selected={selected}
            onToggleSelect={toggleSelect}
          />
        ))}
      </div>
      <DragOverlay dropAnimation={{ duration: 220, easing: 'cubic-bezier(.2,.8,.2,1)' }}>
        {active && <TaskCard task={active} projectKey={projectKey} overlay />}
      </DragOverlay>
      {canWrite && (
        <BulkActionBar
          selectedTasks={selectedTasks}
          onClear={() => setSelected(new Set())}
        />
      )}
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
  selected,
  onToggleSelect,
}: {
  status: TaskStatus
  tasks: Task[]
  projectKey?: string
  canWrite: boolean
  onOpen: (t: Task) => void
  /** Yalniz Done kolonunda ve onay yetkisi varsa: onaylanan gorev Kanban'dan kalkar. */
  onApprove?: (t: Task) => void
  selected: Set<string>
  onToggleSelect: (taskId: string) => void
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
              <DraggableCard
                task={t}
                projectKey={projectKey}
                disabled={!canWrite}
                onOpen={onOpen}
                selectable={canWrite}
                selected={selected.has(t.id)}
                onToggleSelect={onToggleSelect}
              />
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

function DraggableCard({
  task,
  projectKey,
  disabled,
  onOpen,
  selectable,
  selected,
  onToggleSelect,
}: {
  task: Task
  projectKey?: string
  disabled: boolean
  onOpen: (t: Task) => void
  selectable: boolean
  selected: boolean
  onToggleSelect: (taskId: string) => void
}) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: task.id, disabled })
  return (
    <TaskCard
      ref={setNodeRef}
      task={task}
      projectKey={projectKey}
      dragging={isDragging}
      selectable={selectable}
      selected={selected}
      onToggleSelect={() => onToggleSelect(task.id)}
      onClick={(e) => {
        if (e.ctrlKey || e.metaKey || e.shiftKey) {
          e.preventDefault()
          onToggleSelect(task.id)
          return
        }
        onOpen(task)
      }}
      {...attributes}
      {...listeners}
    />
  )
}

/**
 * Dalga 1.7 — toplu islem alt cubugu. Her dropdown secim aninda ilgili bulk cagrisini tetikler
 * (BoardPage'deki filtre select'leriyle AYNI "aninda uygula" deseni); basari sonrasi secim temizlenir.
 */
function BulkActionBar({ selectedTasks, onClear }: { selectedTasks: Task[]; onClear: () => void }) {
  const { data: members } = useMembers(true)
  const { data: sprints } = useSprints(selectedTasks[0]?.projectId ?? '')
  const { data: tags } = useTags()
  const bulk = useBulkTaskAction()
  const [resetKey, setResetKey] = useState(0)

  function run(body: {
    taskIds: string[]
    operation: BulkOperation
    status?: TaskStatus
    sprintId?: string | null
    assigneeId?: string | null
    tagId?: string
  }) {
    bulk.mutate(body, {
      onSuccess: () => {
        onClear()
        setResetKey((k) => k + 1)
      },
      onError: (err) => toast.error(errorMessage(err)),
    })
  }

  return (
    <AnimatePresence>
      {selectedTasks.length > 0 && (
        <motion.div
          key="bulk-bar"
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: 24 }}
          transition={{ type: 'spring', stiffness: 500, damping: 40 }}
          className="fixed inset-x-0 bottom-4 z-40 mx-auto flex w-fit max-w-[92vw] flex-wrap items-center gap-2 rounded-2xl border border-border bg-surface px-3 py-2 shadow-2xl shadow-black/10"
        >
          <span className="px-1 text-sm font-medium tabular-nums">{selectedTasks.length} seçili</span>
          <select
            key={`status-${resetKey}`}
            defaultValue=""
            onChange={(e) => {
              if (!e.target.value) return
              run({ taskIds: selectedTasks.map((t) => t.id), operation: 'STATUS', status: e.target.value as TaskStatus })
            }}
            className="h-8 cursor-pointer rounded-lg border border-border bg-surface-2 px-2 text-xs outline-none focus:border-accent"
          >
            <option value="">Durum...</option>
            {TASK_STATUSES.map((s) => (
              <option key={s} value={s}>
                {STATUS_META[s].label}
              </option>
            ))}
          </select>
          <select
            key={`sprint-${resetKey}`}
            defaultValue=""
            onChange={(e) => {
              if (e.target.value === '') return
              run({ taskIds: selectedTasks.map((t) => t.id), operation: 'SPRINT', sprintId: e.target.value === 'none' ? null : e.target.value })
            }}
            className="h-8 cursor-pointer rounded-lg border border-border bg-surface-2 px-2 text-xs outline-none focus:border-accent"
          >
            <option value="">Sprint...</option>
            <option value="none">Backlog (sprintsiz)</option>
            {sprints?.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
          <select
            key={`assignee-${resetKey}`}
            defaultValue=""
            onChange={(e) => {
              if (e.target.value === '') return
              run({ taskIds: selectedTasks.map((t) => t.id), operation: 'ASSIGNEE', assigneeId: e.target.value === 'none' ? null : e.target.value })
            }}
            className="h-8 cursor-pointer rounded-lg border border-border bg-surface-2 px-2 text-xs outline-none focus:border-accent"
          >
            <option value="">Atanan...</option>
            <option value="none">Atamayı kaldır</option>
            {members
              ?.filter((m) => m.role !== 'VIEWER')
              .map((m) => (
                <option key={m.userId} value={m.userId}>
                  {m.fullName}
                </option>
              ))}
          </select>
          {tags && tags.length > 0 && (
            <select
              key={`tag-${resetKey}`}
              defaultValue=""
              onChange={(e) => {
                if (!e.target.value) return
                run({ taskIds: selectedTasks.map((t) => t.id), operation: 'ADD_TAG', tagId: e.target.value })
              }}
              className="h-8 cursor-pointer rounded-lg border border-border bg-surface-2 px-2 text-xs outline-none focus:border-accent"
            >
              <option value="">Etiket ekle...</option>
              {tags.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
          )}
          <Button variant="ghost" size="sm" className="h-8 px-2" onClick={onClear} title="Seçimi temizle">
            <X size={14} />
          </Button>
        </motion.div>
      )}
    </AnimatePresence>
  )
}
