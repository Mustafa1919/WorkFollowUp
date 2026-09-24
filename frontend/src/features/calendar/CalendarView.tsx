import { useMemo, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  addDays,
  addMonths,
  format,
  isSameMonth,
  isToday,
  isWeekend,
  parse,
  startOfMonth,
  startOfWeek,
} from 'date-fns'
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
import { CalendarX2, ChevronLeft, ChevronRight, Plus } from 'lucide-react'
import { toast } from 'sonner'
import { useCalendarTasks, useCreateTask, useUpdateTask } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { cn } from '@/lib/cn'
import { fmt, iso, todayIso } from '@/lib/dates'
import type { Task } from '@/lib/types'
import { Button } from '@/components/ui/Button'
import { STATUS_META, taskKey } from '@/lib/status'
import { TaskCard } from '@/features/board/TaskCard'

const WEEKDAYS = ['Pzt', 'Sal', 'Çar', 'Per', 'Cum', 'Cmt', 'Paz']
const MAX_VISIBLE = 3
const UNDATED = 'undated'

interface Props {
  projectId: string
  projectKey?: string
  sprintFilter: string
  /** Secili etiket id'leri; bos = filtre yok. HERHANGI BIRINI tasiyan gorev eslesir (OR). */
  tagFilter: string[]
  /** Atanan filtresi: undefined = herkes, null = atanmamis, aksi halde kullanici id'si. */
  assigneeTarget?: string | null
  undated: Task[]
  canWrite: boolean
  onOpen: (task: Task) => void
}

/**
 * Aylik takvim: 6 haftalik izgara (Pazartesi baslangicli, 42 gun => backend'in 62 gunluk sinirinin
 * altinda). Gorev cipleri gunler arasinda ya da "Tarihsiz" listesine suruklenir; gune tiklayinca
 * hizli ekleme acilir. Gorunen ay URL'de (`?month=2026-09`).
 */
export function CalendarView({ projectId, projectKey, sprintFilter, tagFilter, assigneeTarget, undated, canWrite, onOpen }: Props) {
  const [params, setParams] = useSearchParams()
  const monthParam = params.get('month')
  const month = useMemo(() => {
    const parsed = monthParam ? parse(monthParam, 'yyyy-MM', new Date()) : new Date()
    return startOfMonth(Number.isNaN(parsed.getTime()) ? new Date() : parsed)
  }, [monthParam])
  const [direction, setDirection] = useState(0)

  const gridStart = startOfWeek(month, { weekStartsOn: 1 })
  const days = Array.from({ length: 42 }, (_, i) => addDays(gridStart, i))
  const from = iso(days[0])
  const to = iso(days[41])

  const { data: raw, isFetching } = useCalendarTasks(projectId, from, to)
  const update = useUpdateTask(projectId)
  const [active, setActive] = useState<Task | null>(null)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [adding, setAdding] = useState<string | null>(null)
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }), useSensor(KeyboardSensor))

  const byDay = useMemo(() => {
    const map = new Map<string, Task[]>()
    for (const t of raw ?? []) {
      if (!t.dueDate) continue
      if (sprintFilter === 'backlog' && t.sprintId) continue
      if (sprintFilter !== 'all' && sprintFilter !== 'backlog' && t.sprintId !== sprintFilter) continue
      if (tagFilter.length > 0 && !t.tags.some((tag) => tagFilter.includes(tag.id))) continue
      if (assigneeTarget !== undefined && t.assigneeId !== assigneeTarget) continue
      map.set(t.dueDate, [...(map.get(t.dueDate) ?? []), t])
    }
    return map
  }, [raw, sprintFilter, tagFilter, assigneeTarget])

  function goTo(target: Date) {
    setDirection(target > month ? 1 : -1)
    const next = new URLSearchParams(params)
    next.set('month', format(target, 'yyyy-MM'))
    setParams(next, { replace: true })
  }

  function onDragEnd(e: DragEndEvent) {
    setActive(null)
    const task = (e.active.data.current as { task: Task } | undefined)?.task
    const over = e.over?.id as string | undefined
    if (!task || !over) return
    const dueDate = over === UNDATED ? null : over
    if (task.dueDate === dueDate) return
    if (dueDate && dueDate < todayIso()) {
      toast.error('Geçmiş bir güne görev taşınamaz.')
      return
    }
    update.mutate({ id: task.id, patch: { dueDate } }, { onError: (err) => toast.error(errorMessage(err)) })
  }

  const monthKey = format(month, 'yyyy-MM')
  // 6. hafta yalniz ay oraya tasiyorsa gosterilir (veri yine 42 gun icin cekilir).
  const lastWeekVisible = isSameMonth(days[35], month)

  return (
    <DndContext
      sensors={sensors}
      onDragStart={(e) => setActive((e.active.data.current as { task: Task }).task)}
      onDragEnd={onDragEnd}
      onDragCancel={() => setActive(null)}
    >
      <div className="grid gap-4 xl:grid-cols-[1fr_260px]">
        <div className="overflow-hidden rounded-2xl border border-border bg-surface">
          {/* Baslik */}
          <div className="flex items-center gap-2 border-b border-border px-4 py-3">
            <div className="relative h-7 min-w-44 overflow-hidden">
              <AnimatePresence mode="popLayout" initial={false} custom={direction}>
                <motion.h2
                  key={monthKey}
                  custom={direction}
                  variants={{
                    enter: (d: number) => ({ y: d > 0 ? 20 : -20, opacity: 0 }),
                    center: { y: 0, opacity: 1 },
                    exit: (d: number) => ({ y: d > 0 ? -20 : 20, opacity: 0 }),
                  }}
                  initial="enter"
                  animate="center"
                  exit="exit"
                  transition={{ duration: 0.25 }}
                  className="absolute text-lg font-semibold capitalize"
                >
                  {fmt(month, 'MMMM yyyy')}
                </motion.h2>
              </AnimatePresence>
            </div>
            {isFetching && <span className="h-3 w-3 animate-spin rounded-full border-2 border-accent border-t-transparent" />}
            <div className="ml-auto flex items-center gap-1">
              <Button variant="outline" size="sm" onClick={() => goTo(startOfMonth(new Date()))}>
                Bugün
              </Button>
              <Button variant="ghost" size="icon" onClick={() => goTo(addMonths(month, -1))} aria-label="Önceki ay">
                <ChevronLeft size={18} />
              </Button>
              <Button variant="ghost" size="icon" onClick={() => goTo(addMonths(month, 1))} aria-label="Sonraki ay">
                <ChevronRight size={18} />
              </Button>
            </div>
          </div>

          <div className="overflow-x-auto">
            <div className="min-w-[720px]">
              <div className="grid grid-cols-7 border-b border-border">
                {WEEKDAYS.map((d, i) => (
                  <div key={d} className={cn('px-3 py-2 text-xs font-medium text-muted', i >= 5 && 'text-muted/70')}>
                    {d}
                  </div>
                ))}
              </div>
              <AnimatePresence mode="wait" initial={false} custom={direction}>
                <motion.div
                  key={monthKey}
                  custom={direction}
                  variants={{
                    enter: (d: number) => ({ x: d > 0 ? 40 : -40, opacity: 0 }),
                    center: { x: 0, opacity: 1 },
                    exit: (d: number) => ({ x: d > 0 ? -40 : 40, opacity: 0 }),
                  }}
                  initial="enter"
                  animate="center"
                  exit="exit"
                  transition={{ duration: 0.22, ease: [0.22, 1, 0.36, 1] }}
                  className="grid grid-cols-7"
                >
                  {days.map((day, i) => {
                    if (i >= 35 && !lastWeekVisible) return null
                    const key = iso(day)
                    return (
                      <DayCell
                        key={key}
                        date={day}
                        inMonth={isSameMonth(day, month)}
                        tasks={byDay.get(key) ?? []}
                        projectId={projectId}
                        projectKey={projectKey}
                        canWrite={canWrite}
                        expanded={expanded === key}
                        onExpand={() => setExpanded(expanded === key ? null : key)}
                        adding={adding === key}
                        onAdding={(on) => setAdding(on ? key : null)}
                        onOpen={onOpen}
                      />
                    )
                  })}
                </motion.div>
              </AnimatePresence>
            </div>
          </div>
        </div>

        <UndatedPanel tasks={undated} projectKey={projectKey} canWrite={canWrite} onOpen={onOpen} />
      </div>

      <DragOverlay dropAnimation={{ duration: 200, easing: 'cubic-bezier(.2,.8,.2,1)' }}>
        {active && <Chip task={active} projectKey={projectKey} overlay />}
      </DragOverlay>
    </DndContext>
  )
}

function DayCell({
  date,
  inMonth,
  tasks,
  projectId,
  projectKey,
  canWrite,
  expanded,
  onExpand,
  adding,
  onAdding,
  onOpen,
}: {
  date: Date
  inMonth: boolean
  tasks: Task[]
  projectId: string
  projectKey?: string
  canWrite: boolean
  expanded: boolean
  onExpand: () => void
  adding: boolean
  onAdding: (on: boolean) => void
  onOpen: (t: Task) => void
}) {
  const key = iso(date)
  const past = key < todayIso()
  const { setNodeRef, isOver } = useDroppable({ id: key, disabled: past })
  const today = isToday(date)
  const visible = expanded ? tasks : tasks.slice(0, MAX_VISIBLE)
  const hidden = tasks.length - visible.length

  return (
    <div
      ref={setNodeRef}
      className={cn(
        'group relative min-h-32 border-r border-b border-border p-1.5 transition-colors [&:nth-child(7n)]:border-r-0',
        !inMonth && 'bg-surface-2/40',
        isWeekend(date) && inMonth && 'bg-surface-2/20',
        isOver && 'bg-accent-soft',
      )}
    >
      <div className="mb-1 flex items-center justify-between px-1">
        <span
          className={cn(
            'grid h-6 min-w-6 place-items-center rounded-full px-1 text-xs tabular-nums',
            today ? 'bg-accent font-semibold text-accent-fg shadow-md shadow-accent/40' : inMonth ? 'text-fg' : 'text-muted/60',
          )}
        >
          {format(date, 'd')}
        </span>
        {canWrite && !adding && !past && (
          <button
            onClick={() => onAdding(true)}
            className="cursor-pointer rounded-md p-0.5 text-muted opacity-0 transition-opacity group-hover:opacity-100 hover:bg-surface-2 hover:text-accent focus:opacity-100"
            aria-label={`${fmt(date, 'd MMMM')} için görev ekle`}
          >
            <Plus size={14} />
          </button>
        )}
      </div>
      <div className="space-y-1">
        <AnimatePresence initial={false}>
          {visible.map((t) => (
            <motion.div
              key={t.id}
              layout
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.9 }}
              transition={{ type: 'spring', stiffness: 500, damping: 38 }}
            >
              <DraggableChip task={t} projectKey={projectKey} disabled={!canWrite || !!t.approvedAt} onOpen={onOpen} />
            </motion.div>
          ))}
        </AnimatePresence>
        {(hidden > 0 || (expanded && tasks.length > MAX_VISIBLE)) && (
          <button onClick={onExpand} className="w-full cursor-pointer rounded-md px-1.5 py-0.5 text-left text-[11px] text-muted hover:bg-surface-2 hover:text-fg">
            {expanded ? 'Daha az' : `+${hidden} daha`}
          </button>
        )}
        {adding && <QuickAdd projectId={projectId} date={key} onDone={() => onAdding(false)} />}
      </div>
    </div>
  )
}

function QuickAdd({ projectId, date, onDone }: { projectId: string; date: string; onDone: () => void }) {
  const [title, setTitle] = useState('')
  const create = useCreateTask(projectId)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!title.trim()) return onDone()
    try {
      await create.mutateAsync({ title: title.trim(), dueDate: date })
      setTitle('')
      onDone()
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  return (
    <motion.form initial={{ opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }} onSubmit={submit}>
      <input
        autoFocus
        value={title}
        maxLength={255}
        disabled={create.isPending}
        onChange={(e) => setTitle(e.target.value)}
        onBlur={() => !title.trim() && onDone()}
        onKeyDown={(e) => e.key === 'Escape' && onDone()}
        placeholder="Görev adı, Enter"
        className="w-full rounded-md border border-accent bg-surface px-1.5 py-1 text-xs ring-2 ring-accent/20 outline-none"
      />
    </motion.form>
  )
}

function DraggableChip({ task, projectKey, disabled, onOpen }: { task: Task; projectKey?: string; disabled: boolean; onOpen: (t: Task) => void }) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: `chip:${task.id}`, data: { task }, disabled })
  return (
    <div ref={setNodeRef} {...attributes} {...listeners} onClick={() => onOpen(task)} className={cn(isDragging && 'opacity-30')}>
      <Chip task={task} projectKey={projectKey} />
    </div>
  )
}

function Chip({ task, projectKey, overlay }: { task: Task; projectKey?: string; overlay?: boolean }) {
  const meta = STATUS_META[task.status]
  return (
    <div
      title={`${taskKey(projectKey, task.taskNumber)} · ${task.title}`}
      className={cn(
        'flex cursor-grab touch-none items-center gap-1.5 truncate rounded-md border-l-2 px-1.5 py-1 text-[11px] leading-tight select-none active:cursor-grabbing',
        meta.soft,
        task.status === 'Done' && 'text-muted line-through',
        overlay && 'w-40 rotate-2 bg-surface shadow-xl shadow-accent/20',
      )}
      style={{ borderLeftColor: `var(--st-${task.status === 'To Do' ? 'todo' : task.status === 'In Progress' ? 'progress' : task.status === 'Review' ? 'review' : 'done'})` }}
    >
      <span className="truncate">{task.title}</span>
    </div>
  )
}

function UndatedPanel({ tasks, projectKey, canWrite, onOpen }: { tasks: Task[]; projectKey?: string; canWrite: boolean; onOpen: (t: Task) => void }) {
  const { setNodeRef, isOver } = useDroppable({ id: UNDATED })
  const open = tasks.filter((t) => t.status !== 'Done')
  return (
    <aside
      ref={setNodeRef}
      className={cn(
        'flex max-h-[calc(100vh-180px)] flex-col rounded-2xl border border-border bg-surface p-3 transition-colors xl:sticky xl:top-20',
        isOver && 'border-accent/60 bg-accent-soft',
      )}
    >
      <div className="mb-3 flex items-center gap-2 px-1">
        <CalendarX2 size={16} className="text-muted" />
        <h3 className="text-sm font-medium">Tarihsiz</h3>
        <span className="ml-auto rounded-md bg-surface-2 px-1.5 text-xs text-muted tabular-nums">{open.length}</span>
      </div>
      <p className="mb-3 px-1 text-xs text-muted">Takvime planlamak için bir güne sürükle. Takvimden buraya bırakırsan tarihi kaldırılır.</p>
      <div className="-mx-1 flex-1 space-y-2 overflow-y-auto px-1">
        {open.map((t) => (
          <DraggableUndated key={t.id} task={t} projectKey={projectKey} disabled={!canWrite} onOpen={onOpen} />
        ))}
        {open.length === 0 && <p className="py-6 text-center text-xs text-muted">Tüm açık görevler planlanmış ✨</p>}
      </div>
    </aside>
  )
}

function DraggableUndated({ task, projectKey, disabled, onOpen }: { task: Task; projectKey?: string; disabled: boolean; onOpen: (t: Task) => void }) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: `undated:${task.id}`, data: { task }, disabled })
  return <TaskCard ref={setNodeRef} task={task} projectKey={projectKey} dragging={isDragging} onClick={() => onOpen(task)} {...attributes} {...listeners} />
}
