import { useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { motion, AnimatePresence } from 'motion/react'
import { BarChart3, CalendarDays, CheckCircle2, KanbanSquare, Plus } from 'lucide-react'
import { useProjects, useSprints, useTasks, useCurrentRole } from '@/api/queries'
import { cn } from '@/lib/cn'
import type { Task } from '@/lib/types'
import { Button } from '@/components/ui/Button'
import { Page, Skeleton } from '@/components/ui/misc'
import { KanbanView } from './KanbanView'
import { CalendarView } from '@/features/calendar/CalendarView'
import { TaskDialog } from './TaskDialog'
import { NewTaskDialog } from './NewTaskDialog'
import { SprintsDialog } from './SprintsDialog'

type View = 'kanban' | 'calendar'

/** Gorunum ve sprint filtresi URL'de: yenilemede/paylasimda korunur. */
export function BoardPage() {
  const { projectId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const view: View = params.get('view') === 'calendar' ? 'calendar' : 'kanban'
  const sprintFilter = params.get('sprint') ?? 'all'

  const { data: projects } = useProjects()
  const project = projects?.find((p) => p.id === projectId)
  const { data: tasks, isLoading } = useTasks(projectId)
  const { data: sprints } = useSprints(projectId)
  const role = useCurrentRole()
  const canWrite = role !== undefined && role !== 'VIEWER'

  const [selected, setSelected] = useState<Task | null>(null)
  const [newTaskOpen, setNewTaskOpen] = useState(false)
  const [sprintsOpen, setSprintsOpen] = useState(false)

  const filtered = useMemo(() => {
    if (!tasks) return []
    if (sprintFilter === 'all') return tasks
    if (sprintFilter === 'backlog') return tasks.filter((t) => !t.sprintId)
    return tasks.filter((t) => t.sprintId === sprintFilter)
  }, [tasks, sprintFilter])

  function set(key: string, value: string | null) {
    const next = new URLSearchParams(params)
    if (value === null) next.delete(key)
    else next.set(key, value)
    setParams(next, { replace: true })
  }

  // Dialog acikken guncel veriyi goster (optimistic guncelleme sonrasi).
  const selectedLive = selected ? (tasks?.find((t) => t.id === selected.id) ?? selected) : null

  return (
    <Page className="max-w-none">
      <div className="mb-6 flex flex-wrap items-center gap-3">
        <div className="mr-auto min-w-0">
          <div className="flex items-center gap-2 text-xs text-muted">
            <Link to="/" className="hover:text-fg">
              Projeler
            </Link>
            <span>/</span>
            <span className="font-mono">{project?.key}</span>
          </div>
          <h1 className="truncate text-2xl font-semibold tracking-tight">{project?.name ?? <Skeleton className="h-7 w-48" />}</h1>
        </div>

        <ViewSwitch view={view} onChange={(v) => set('view', v === 'kanban' ? null : v)} />

        <select
          value={sprintFilter}
          onChange={(e) => set('sprint', e.target.value === 'all' ? null : e.target.value)}
          className="h-9 cursor-pointer rounded-xl border border-border bg-surface px-3 text-sm outline-none focus:border-accent"
          aria-label="Sprint filtresi"
        >
          <option value="all">Tüm görevler</option>
          <option value="backlog">Backlog (sprintsiz)</option>
          {sprints?.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
              {s.status === 'active' ? ' • aktif' : s.status === 'completed' ? ' • bitti' : ''}
            </option>
          ))}
        </select>
        <Button variant="outline" size="sm" className="h-9" onClick={() => setSprintsOpen(true)}>
          Sprintler
        </Button>
        <Link to={`/projects/${projectId}/completed`}>
          <Button variant="outline" size="sm" className="h-9">
            <CheckCircle2 size={15} /> Tamamlananlar
          </Button>
        </Link>
        <Link to={`/projects/${projectId}/analytics`}>
          <Button variant="outline" size="sm" className="h-9">
            <BarChart3 size={15} /> Analitik
          </Button>
        </Link>
        {canWrite && (
          <Button size="sm" className="h-9" onClick={() => setNewTaskOpen(true)}>
            <Plus size={15} /> Görev
          </Button>
        )}
      </div>

      {isLoading ? (
        <div className="grid gap-4 md:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-72" />
          ))}
        </div>
      ) : (
        <AnimatePresence mode="wait" initial={false}>
          <motion.div
            key={view}
            initial={{ opacity: 0, x: view === 'calendar' ? 24 : -24 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: view === 'calendar' ? -24 : 24 }}
            transition={{ duration: 0.25, ease: [0.22, 1, 0.36, 1] }}
          >
            {view === 'kanban' ? (
              <KanbanView projectId={projectId} projectKey={project?.key} tasks={filtered} canWrite={canWrite} onOpen={setSelected} />
            ) : (
              <CalendarView
                projectId={projectId}
                projectKey={project?.key}
                sprintFilter={sprintFilter}
                undated={filtered.filter((t) => !t.dueDate)}
                canWrite={canWrite}
                onOpen={setSelected}
              />
            )}
          </motion.div>
        </AnimatePresence>
      )}

      <TaskDialog
        task={selectedLive}
        projectKey={project?.key}
        sprints={sprints ?? []}
        canWrite={canWrite}
        onOpenChange={(o) => !o && setSelected(null)}
      />
      <NewTaskDialog projectId={projectId} open={newTaskOpen} onOpenChange={setNewTaskOpen} />
      <SprintsDialog projectId={projectId} open={sprintsOpen} onOpenChange={setSprintsOpen} canManage={role === 'WORKSPACE_ADMIN' || role === 'MANAGER'} />
    </Page>
  )
}

function ViewSwitch({ view, onChange }: { view: View; onChange: (v: View) => void }) {
  const items: { id: View; label: string; icon: typeof KanbanSquare }[] = [
    { id: 'kanban', label: 'Kanban', icon: KanbanSquare },
    { id: 'calendar', label: 'Takvim', icon: CalendarDays },
  ]
  return (
    <div role="tablist" className="flex rounded-xl border border-border bg-surface p-1">
      {items.map((i) => (
        <button
          key={i.id}
          role="tab"
          aria-selected={view === i.id}
          onClick={() => onChange(i.id)}
          className={cn(
            'relative flex cursor-pointer items-center gap-1.5 rounded-lg px-3 py-1 text-sm transition-colors',
            view === i.id ? 'text-accent-fg' : 'text-muted hover:text-fg',
          )}
        >
          {view === i.id && (
            <motion.span
              layoutId="view-pill"
              className="absolute inset-0 rounded-lg bg-accent"
              transition={{ type: 'spring', stiffness: 500, damping: 36 }}
            />
          )}
          <i.icon size={15} className="relative" />
          <span className="relative">{i.label}</span>
        </button>
      ))}
    </div>
  )
}
