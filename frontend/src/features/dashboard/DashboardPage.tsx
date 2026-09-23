import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { motion } from 'motion/react'
import { addDays, differenceInCalendarDays, startOfToday, subDays } from 'date-fns'
import { AlertTriangle, ArrowUpRight, CalendarClock, CheckCircle2, FolderKanban, Plus, Sparkles } from 'lucide-react'
import { useCalendarTasksForProjects, useProjects, useCurrentRole } from '@/api/queries'
import { fmt, fromIso, iso } from '@/lib/dates'
import { useSession } from '@/stores/session'
import type { Project, Task } from '@/lib/types'
import { Aurora } from '@/components/Aurora'
import { Button } from '@/components/ui/Button'
import { EmptyState, Page, Skeleton, StatusDot } from '@/components/ui/misc'
import { STATUS_META, taskKey } from '@/lib/status'
import { CreateProjectDialog } from './CreateProjectDialog'

function greeting() {
  const h = new Date().getHours()
  if (h < 6) return 'İyi geceler'
  if (h < 12) return 'Günaydın'
  if (h < 18) return 'İyi günler'
  return 'İyi akşamlar'
}

const container = { hidden: {}, show: { transition: { staggerChildren: 0.06 } } }
const item = {
  hidden: { opacity: 0, y: 14 },
  show: { opacity: 1, y: 0, transition: { duration: 0.4, ease: [0.22, 1, 0.36, 1] as const } },
}

export function DashboardPage() {
  const { data: projects, isLoading } = useProjects()
  const email = useSession((s) => s.email)
  const role = useCurrentRole()
  const [createOpen, setCreateOpen] = useState(false)

  // Son 30 gun + onumuzdeki 31 gun: gecikenler ve yaklasanlar (takvim endpoint'i en fazla 62 gun).
  const today = startOfToday()
  const from = iso(subDays(today, 30))
  const to = iso(addDays(today, 31))
  const { data: tasks, isLoading: tasksLoading } = useCalendarTasksForProjects(projects ?? [], from, to)

  const byProject = useMemo(() => new Map((projects ?? []).map((p) => [p.id, p])), [projects])
  const open = tasks.filter((t) => t.status !== 'Done')
  const overdue = open.filter((t) => t.dueDate && differenceInCalendarDays(fromIso(t.dueDate), today) < 0)
  const upcoming = open
    .filter((t) => {
      const d = differenceInCalendarDays(fromIso(t.dueDate!), today)
      return d >= 0 && d <= 7
    })
    .sort((a, b) => a.dueDate!.localeCompare(b.dueDate!))
  const doneRecently = tasks.filter((t) => t.status === 'Done').length

  const name = email?.split('@')[0]

  return (
    <Page>
      {/* Hero */}
      <motion.section
        initial={{ opacity: 0, scale: 0.985 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ duration: 0.6, ease: [0.22, 1, 0.36, 1] }}
        className="relative mb-8 overflow-hidden rounded-3xl border border-border bg-surface px-6 py-8 sm:px-10 sm:py-10"
      >
        <Aurora />
        <div className="relative">
          <motion.p
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.1 }}
            className="mb-2 inline-flex items-center gap-1.5 rounded-full border border-border bg-surface/70 px-3 py-1 text-xs text-muted backdrop-blur"
          >
            <Sparkles size={12} className="text-accent" /> {fmt(today, 'd MMMM yyyy, EEEE')}
          </motion.p>
          <motion.h1
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.18, duration: 0.5 }}
            className="text-3xl font-semibold tracking-tight sm:text-4xl"
          >
            {greeting()}
            {name && (
              <>
                ,{' '}
                <span className="bg-gradient-to-r from-accent to-fuchsia-500 bg-clip-text text-transparent">{name}</span>
              </>
            )}
          </motion.h1>
          <motion.p initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.3 }} className="mt-2 text-muted">
            {tasksLoading
              ? 'Görevlerin yükleniyor…'
              : upcoming.length
                ? `Önümüzdeki 7 günde ${upcoming.length} görevin var.`
                : 'Önümüzdeki 7 gün sakin görünüyor.'}
          </motion.p>
        </div>
      </motion.section>

      {/* Istatistik kartlari */}
      <motion.div variants={container} initial="hidden" animate="show" className="mb-10 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat icon={<FolderKanban size={18} />} label="Proje" value={projects?.length} tone="accent" />
        <Stat icon={<CalendarClock size={18} />} label="Bu hafta" value={upcoming.length} tone="progress" />
        <Stat icon={<AlertTriangle size={18} />} label="Geciken" value={overdue.length} tone="danger" />
        <Stat icon={<CheckCircle2 size={18} />} label="Tamamlanan (30 gün)" value={doneRecently} tone="done" />
      </motion.div>

      <div className="grid gap-8 xl:grid-cols-[1fr_380px]">
        {/* Projeler */}
        <section>
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-lg font-semibold">Projeler</h2>
            {(role === 'WORKSPACE_ADMIN' || role === 'MANAGER') && (
              <Button size="sm" variant="outline" onClick={() => setCreateOpen(true)}>
                <Plus size={15} /> Yeni proje
              </Button>
            )}
          </div>
          {isLoading ? (
            <div className="grid gap-3 sm:grid-cols-2">
              {[0, 1, 2, 3].map((i) => (
                <Skeleton key={i} className="h-32" />
              ))}
            </div>
          ) : projects?.length ? (
            <motion.div variants={container} initial="hidden" animate="show" className="grid gap-3 sm:grid-cols-2">
              {projects.map((p) => (
                <ProjectCard key={p.id} project={p} tasks={tasks.filter((t) => t.projectId === p.id)} />
              ))}
            </motion.div>
          ) : (
            <EmptyState
              icon={<FolderKanban size={22} />}
              title="Henüz proje yok"
              text="İlk projeni oluştur; görevlerini Kanban panosunda ya da takvimde yönet."
              action={
                (role === 'WORKSPACE_ADMIN' || role === 'MANAGER') && (
                  <Button onClick={() => setCreateOpen(true)}>
                    <Plus size={16} /> Proje oluştur
                  </Button>
                )
              }
            />
          )}
        </section>

        {/* Yaklasanlar */}
        <section>
          <h2 className="mb-4 text-lg font-semibold">Yaklaşanlar</h2>
          <div className="rounded-2xl border border-border bg-surface p-2">
            {tasksLoading ? (
              <div className="space-y-2 p-2">
                {[0, 1, 2].map((i) => (
                  <Skeleton key={i} className="h-12" />
                ))}
              </div>
            ) : [...overdue, ...upcoming].length === 0 ? (
              <p className="px-4 py-10 text-center text-sm text-muted">Tarihli açık görev yok 🎉</p>
            ) : (
              <motion.ul variants={container} initial="hidden" animate="show">
                {[...overdue, ...upcoming].slice(0, 10).map((t) => (
                  <UpcomingRow key={t.id} task={t} project={byProject.get(t.projectId)} today={today} />
                ))}
              </motion.ul>
            )}
          </div>
        </section>
      </div>
      <CreateProjectDialog open={createOpen} onOpenChange={setCreateOpen} />
    </Page>
  )
}

const TONES = {
  accent: 'bg-accent-soft text-accent',
  progress: 'bg-st-progress/15 text-st-progress',
  danger: 'bg-danger/15 text-danger',
  done: 'bg-st-done/15 text-st-done',
}

function Stat({ icon, label, value, tone }: { icon: React.ReactNode; label: string; value?: number; tone: keyof typeof TONES }) {
  return (
    <motion.div
      variants={item}
      whileHover={{ y: -3 }}
      className="rounded-2xl border border-border bg-surface p-4 transition-shadow hover:shadow-lg hover:shadow-black/5"
    >
      <div className={`mb-3 grid h-9 w-9 place-items-center rounded-xl ${TONES[tone]}`}>{icon}</div>
      <div className="text-2xl font-semibold tabular-nums">{value ?? '–'}</div>
      <div className="text-xs text-muted">{label}</div>
    </motion.div>
  )
}

function ProjectCard({ project, tasks }: { project: Project; tasks: Task[] }) {
  const done = tasks.filter((t) => t.status === 'Done').length
  const pct = tasks.length ? Math.round((done / tasks.length) * 100) : 0
  return (
    <motion.div variants={item} whileHover={{ y: -4 }} transition={{ type: 'spring', stiffness: 400, damping: 25 }}>
      <Link
        to={`/projects/${project.id}`}
        className="group relative block overflow-hidden rounded-2xl border border-border bg-surface p-5 transition-all hover:border-accent/50 hover:shadow-xl hover:shadow-accent/10"
      >
        <div className="absolute inset-0 bg-gradient-to-br from-accent/0 to-fuchsia-500/0 transition-all duration-500 group-hover:from-accent/5 group-hover:to-fuchsia-500/5" />
        <div className="relative flex items-start justify-between">
          <div className="flex items-center gap-3">
            <span className="grid h-10 w-10 place-items-center rounded-xl bg-accent-soft font-mono text-sm font-bold text-accent">
              {project.key.slice(0, 3)}
            </span>
            <div>
              <div className="font-medium">{project.name}</div>
              <div className="font-mono text-xs text-muted">{project.key}</div>
            </div>
          </div>
          <ArrowUpRight
            size={18}
            className="text-muted transition-all duration-300 group-hover:translate-x-0.5 group-hover:-translate-y-0.5 group-hover:text-accent"
          />
        </div>
        <div className="relative mt-5">
          <div className="mb-1.5 flex justify-between text-xs text-muted">
            <span>Planlı görevler (±30 gün)</span>
            <span className="tabular-nums">
              {done}/{tasks.length}
            </span>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-surface-2">
            <motion.div
              className="h-full rounded-full bg-gradient-to-r from-accent to-fuchsia-500"
              initial={{ width: 0 }}
              animate={{ width: `${pct}%` }}
              transition={{ duration: 0.9, ease: [0.22, 1, 0.36, 1], delay: 0.2 }}
            />
          </div>
        </div>
      </Link>
    </motion.div>
  )
}

function UpcomingRow({ task, project, today }: { task: Task; project?: Project; today: Date }) {
  const diff = differenceInCalendarDays(fromIso(task.dueDate!), today)
  const when = diff < 0 ? `${-diff} gün gecikti` : diff === 0 ? 'Bugün' : diff === 1 ? 'Yarın' : fmt(task.dueDate!, 'd MMM EEE')
  return (
    <motion.li variants={item}>
      <Link
        to={`/projects/${task.projectId}?view=calendar&month=${task.dueDate!.slice(0, 7)}`}
        className="flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors hover:bg-surface-2"
      >
        <StatusDot status={task.status} />
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm">{task.title}</div>
          <div className="text-xs text-muted">
            <span className="font-mono">{taskKey(project?.key, task.taskNumber)}</span> · {STATUS_META[task.status].label}
          </div>
        </div>
        <span className={`shrink-0 text-xs ${diff < 0 ? 'font-medium text-danger' : 'text-muted'}`}>{when}</span>
      </Link>
    </motion.li>
  )
}
