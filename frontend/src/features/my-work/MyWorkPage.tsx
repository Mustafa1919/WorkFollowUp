import { useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'motion/react'
import { CalendarDays, CircleUserRound } from 'lucide-react'
import { differenceInCalendarDays, startOfToday } from 'date-fns'
import { useMyTasks, useProjects } from '@/api/queries'
import { cn } from '@/lib/cn'
import { fmt, fromIso } from '@/lib/dates'
import { STATUS_META, taskKey } from '@/lib/status'
import { TASK_STATUSES, type Task } from '@/lib/types'
import { EmptyState, Page, Skeleton, StatusDot, TagChip } from '@/components/ui/misc'

/** Bitis tarihi yakin olan once; tarihsizler en sonda (ayni gunde gorev numarasina gore). */
function byUrgency(a: Task, b: Task) {
  if (a.dueDate && b.dueDate) return a.dueDate.localeCompare(b.dueDate) || a.taskNumber - b.taskNumber
  if (a.dueDate) return -1
  if (b.dueDate) return 1
  return b.createdAt.localeCompare(a.createdAt)
}

/**
 * "Benim islerim": aktif workspace'te bana atanmis, onaylanmamis gorevler — tum projelerden, duruma
 * gore gruplu. Satira tiklamak gorevin board'unu `?task=` derin baglantisiyla acar.
 */
export function MyWorkPage() {
  const { data: tasks, isLoading } = useMyTasks()
  const { data: projects } = useProjects()
  const navigate = useNavigate()
  const projectKey = (projectId: string) => projects?.find((p) => p.id === projectId)?.key

  const groups = useMemo(
    () =>
      TASK_STATUSES.map((status) => ({
        status,
        tasks: (tasks ?? []).filter((t) => t.status === status).sort(byUrgency),
      })).filter((g) => g.tasks.length > 0),
    [tasks],
  )
  const overdue = (tasks ?? []).filter(
    (t) => t.dueDate && t.status !== 'Done' && differenceInCalendarDays(fromIso(t.dueDate), startOfToday()) < 0,
  ).length

  return (
    <Page className="max-w-4xl">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold tracking-tight">Benim işlerim</h1>
        <p className="text-sm text-muted">
          Tüm projelerde sana atanmış açık görevler
          {tasks && tasks.length > 0 && ` · ${tasks.length} görev`}
          {overdue > 0 && <span className="text-danger"> · {overdue} gecikmiş</span>}
        </p>
      </div>

      {isLoading ? (
        <div className="space-y-2">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-14" />
          ))}
        </div>
      ) : groups.length === 0 ? (
        <EmptyState
          icon={<CircleUserRound size={22} />}
          title="Sana atanmış açık görev yok"
          text="Bir görevi açıp “Bana ata” dediğinde ya da biri sana görev atadığında burada görünür."
        />
      ) : (
        <div className="space-y-6">
          {groups.map((group) => (
            <section key={group.status}>
              <h2 className="mb-2 flex items-center gap-2 text-xs font-medium tracking-wide text-muted uppercase">
                <StatusDot status={group.status} />
                {STATUS_META[group.status].label}
                <span className="tabular-nums">{group.tasks.length}</span>
              </h2>
              <ul className="space-y-1.5">
                {group.tasks.map((task, i) => (
                  <motion.li
                    key={task.id}
                    initial={{ opacity: 0, y: 4 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ delay: Math.min(i, 10) * 0.02 }}
                  >
                    <button
                      onClick={() => navigate(`/projects/${task.projectId}?task=${task.id}`)}
                      className="flex w-full cursor-pointer items-center gap-3 rounded-xl border border-border bg-surface px-3 py-2.5 text-left transition-colors hover:border-accent/40"
                    >
                      <span className="w-20 shrink-0 font-mono text-xs text-muted">
                        {taskKey(projectKey(task.projectId), task.taskNumber)}
                      </span>
                      <span className="min-w-0 flex-1 truncate text-sm">{task.title}</span>
                      <span className="hidden gap-1 sm:flex">
                        {task.tags.slice(0, 2).map((tag) => (
                          <TagChip key={tag.id} tag={tag} />
                        ))}
                      </span>
                      <DueBadge task={task} />
                    </button>
                  </motion.li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
    </Page>
  )
}

function DueBadge({ task }: { task: Task }) {
  if (!task.dueDate) return null
  const diff = differenceInCalendarDays(fromIso(task.dueDate), startOfToday())
  const late = diff < 0 && task.status !== 'Done'
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1 rounded-md px-1.5 py-0.5 text-xs',
        late ? 'bg-danger/15 text-danger' : diff === 0 ? 'bg-accent-soft text-accent' : 'bg-surface-2 text-muted',
      )}
    >
      <CalendarDays size={11} />
      {diff === 0 ? 'Bugün' : fmt(task.dueDate, 'd MMM')}
    </span>
  )
}
