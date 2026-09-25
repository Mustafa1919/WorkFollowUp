import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import {
  ArrowLeft,
  ArrowRightLeft,
  CheckCircle2,
  Gauge,
  Lightbulb,
  ListChecks,
  Octagon,
  Sparkles,
  Timer,
  Trash2,
} from 'lucide-react'
import {
  useCurrentRole,
  useProjects,
  useRetroItemActions,
  useRetroItems,
  useSprintRetro,
  useSprints,
} from '@/api/queries'
import { useCurrentUserId } from '@/stores/session'
import { errorMessage } from '@/lib/api'
import { fromNow } from '@/lib/dates'
import { taskKey } from '@/lib/status'
import type { RetroItemKind, RetroItemResponse, RetroTaskRef } from '@/lib/types'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { inputClass } from '@/components/ui/Input'
import { EmptyState, Page, Skeleton } from '@/components/ui/misc'
import { cn } from '@/lib/cn'

function duration(seconds: number | null | undefined) {
  if (seconds == null) return '–'
  const h = seconds / 3600
  if (h < 1) return `${Math.round(seconds / 60)} dk`
  if (h < 48) return `${h.toFixed(1)} sa`
  return `${(h / 24).toFixed(1)} gün`
}

const COLUMNS: { kind: RetroItemKind; label: string; icon: typeof Sparkles; tone: string }[] = [
  { kind: 'went_well', label: 'İyi giden', icon: Sparkles, tone: 'text-st-done' },
  { kind: 'improve', label: 'Geliştirilebilir', icon: Lightbulb, tone: 'text-st-review' },
  { kind: 'action', label: 'Aksiyon', icon: ListChecks, tone: 'text-accent' },
]

/** Dalga 2.4 (ADR-0015) — veriye dayali retro: plan/gerceklesen karsilastirmasi + retro panosu. */
export function RetroPage() {
  const { projectId = '' } = useParams()
  const { data: projects } = useProjects()
  const project = projects?.find((p) => p.id === projectId)
  const { data: sprints } = useSprints(projectId)

  const completedSprints = useMemo(
    () =>
      (sprints ?? [])
        .filter((s) => s.status === 'completed')
        .sort((a, b) => (b.completedAt ?? '').localeCompare(a.completedAt ?? '')),
    [sprints],
  )
  const [sprintId, setSprintId] = useState('')
  const activeSprintId = sprintId || completedSprints[0]?.id || ''
  const activeSprint = completedSprints.find((s) => s.id === activeSprintId)

  const { data: retro, isLoading } = useSprintRetro(activeSprintId, !!activeSprintId)

  return (
    <Page>
      <Link to={`/projects/${projectId}`} className="mb-3 inline-flex items-center gap-1.5 text-sm text-muted hover:text-fg">
        <ArrowLeft size={15} /> Panoya dön
      </Link>

      <div className="mb-6 flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">{project?.name ?? '…'} · Retro</h1>
        {completedSprints.length > 0 && (
          <select
            value={activeSprintId}
            onChange={(e) => setSprintId(e.target.value)}
            className={cn(inputClass, 'ml-auto w-auto cursor-pointer')}
          >
            {completedSprints.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        )}
      </div>

      {completedSprints.length === 0 ? (
        <EmptyState
          icon={<CheckCircle2 size={22} />}
          title="Henüz tamamlanmış sprint yok"
          text="Bir sprint tamamlandığında retrospektifi burada görünür."
        />
      ) : isLoading || !retro ? (
        <div className="grid gap-4 md:grid-cols-3">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-28" />
          ))}
        </div>
      ) : (
        <RetroContent projectKey={project?.key} sprintId={activeSprintId} sprintName={activeSprint?.name} retro={retro} />
      )}
    </Page>
  )
}

function RetroContent({
  projectKey,
  sprintId,
  sprintName,
  retro,
}: {
  projectKey: string | undefined
  sprintId: string
  sprintName: string | undefined
  retro: NonNullable<ReturnType<typeof useSprintRetro>['data']>
}) {
  return (
    <>
      {!retro.planAvailable && (
        <div className="mb-6 rounded-xl border border-dashed border-border bg-surface-2 px-4 py-3 text-sm text-muted">
          Bu sprint, veriye dayalı retro özelliğinden ÖNCE tamamlandığı için "sprint başı" kesiti
          hesaplanmadı — yalnız gerçekleşen sayılar ve retro panosu gösteriliyor.
        </div>
      )}

      <div className="mb-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {retro.planAvailable && (
          <Metric icon={<Gauge size={18} />} label="Sprint başı taahhüt (görev)" value={String(retro.committedAtStartTasks ?? '–')} />
        )}
        <Metric icon={<CheckCircle2 size={18} />} label="Tamamlanan (görev / puan)" value={`${retro.completedTasks} / ${retro.completedPoints}`} />
        <Metric icon={<ArrowRightLeft size={18} />} label="Spillover" value={String(retro.spilloverTasks.length)} />
        {retro.forecastProbabilityAtStart != null && (
          <Metric
            icon={<Timer size={18} />}
            label="Sprint başında zamanında bitme olasılığı"
            value={`%${Math.round(retro.forecastProbabilityAtStart * 100)}`}
          />
        )}
      </div>

      {retro.planAvailable && (retro.addedTasks.length > 0 || retro.removedTasks.length > 0) && (
        <div className="mb-6 grid gap-4 sm:grid-cols-2">
          <TaskRefCard title="Sprint içinde eklenen" tasks={retro.addedTasks} projectKey={projectKey} />
          <TaskRefCard title="Sprint içinde çıkarılan" tasks={retro.removedTasks} projectKey={projectKey} />
        </div>
      )}

      {retro.spilloverTasks.length > 0 && (
        <TaskRefCard title="Spillover (hâlâ Done değil)" tasks={retro.spilloverTasks} projectKey={projectKey} className="mb-6" />
      )}

      {(retro.cycleTimeOutliers.length > 0 || retro.longestOpenBlocker) && (
        <div className="mb-6 grid gap-4 sm:grid-cols-2">
          {retro.cycleTimeOutliers.length > 0 && (
            <div className="rounded-2xl border border-border bg-surface p-4">
              <h2 className="mb-3 text-sm font-semibold">En uzun cycle time</h2>
              <ul className="space-y-1.5 text-sm">
                {retro.cycleTimeOutliers.map((o) => (
                  <li key={o.task.taskId} className="flex items-center justify-between gap-2">
                    <span className="truncate">
                      <span className="font-mono text-xs text-muted">{taskKey(o.task.projectKey ?? projectKey, o.task.taskNumber)}</span> {o.task.title}
                    </span>
                    <span className="shrink-0 tabular-nums text-muted">{duration(o.cycleTimeSeconds)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {retro.longestOpenBlocker && (
            <div className="rounded-2xl border border-border bg-surface p-4">
              <h2 className="mb-3 flex items-center gap-1.5 text-sm font-semibold text-danger">
                <Octagon size={14} /> En uzun süredir bekleyen blocker
              </h2>
              <p className="text-sm">
                <span className="font-mono text-xs text-muted">
                  {taskKey(retro.longestOpenBlocker.blockedTask.projectKey ?? projectKey, retro.longestOpenBlocker.blockedTask.taskNumber)}
                </span>{' '}
                {retro.longestOpenBlocker.blockedTask.title}
              </p>
              <p className="mt-1 text-xs text-muted">
                şunun tarafından bloklanıyor:{' '}
                <span className="font-mono">
                  {taskKey(retro.longestOpenBlocker.blockingTask.projectKey ?? projectKey, retro.longestOpenBlocker.blockingTask.taskNumber)}
                </span>{' '}
                {retro.longestOpenBlocker.blockingTask.title} · {fromNow(retro.longestOpenBlocker.openSince)}
              </p>
            </div>
          )}
        </div>
      )}

      <RetroBoard sprintId={sprintId} sprintName={sprintName} />
    </>
  )
}

function Metric({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="rounded-2xl border border-border bg-surface p-4">
      <div className="mb-3 grid h-9 w-9 place-items-center rounded-xl bg-accent-soft text-accent">{icon}</div>
      <div className="text-2xl font-semibold tabular-nums">{value}</div>
      <div className="text-xs text-muted">{label}</div>
    </div>
  )
}

function TaskRefCard({
  title,
  tasks,
  projectKey,
  className,
}: {
  title: string
  tasks: RetroTaskRef[]
  projectKey: string | undefined
  className?: string
}) {
  return (
    <div className={cn('rounded-2xl border border-border bg-surface p-4', className)}>
      <h2 className="mb-3 text-sm font-semibold">
        {title} <span className="text-muted">· {tasks.length}</span>
      </h2>
      <ul className="space-y-1 text-sm">
        {tasks.map((t) => (
          <li key={t.taskId} className="truncate text-muted">
            <span className="font-mono text-xs">{taskKey(t.projectKey ?? projectKey, t.taskNumber)}</span> {t.title}
          </li>
        ))}
      </ul>
    </div>
  )
}

function RetroBoard({ sprintId, sprintName }: { sprintId: string; sprintName: string | undefined }) {
  const { data: items } = useRetroItems(sprintId, !!sprintId)
  const currentUserId = useCurrentUserId()
  const role = useCurrentRole()
  const canWrite = role !== undefined && role !== 'VIEWER'

  return (
    <div>
      <h2 className="mb-3 text-lg font-semibold">Retro panosu{sprintName ? ` · ${sprintName}` : ''}</h2>
      <div className="grid gap-4 md:grid-cols-3">
        {COLUMNS.map((col) => (
          <RetroColumn
            key={col.kind}
            sprintId={sprintId}
            kind={col.kind}
            label={col.label}
            Icon={col.icon}
            tone={col.tone}
            items={(items ?? []).filter((it) => it.kind === col.kind)}
            currentUserId={currentUserId}
            canWrite={canWrite}
          />
        ))}
      </div>
    </div>
  )
}

function RetroColumn({
  sprintId,
  kind,
  label,
  Icon,
  tone,
  items,
  currentUserId,
  canWrite,
}: {
  sprintId: string
  kind: RetroItemKind
  label: string
  Icon: typeof Sparkles
  tone: string
  items: RetroItemResponse[]
  currentUserId: string | null
  canWrite: boolean
}) {
  const actions = useRetroItemActions(sprintId)
  const [draft, setDraft] = useState('')

  async function submit() {
    const body = draft.trim()
    if (!body) return
    try {
      await actions.create.mutateAsync({ kind, body })
      setDraft('')
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  async function remove(id: string) {
    try {
      await actions.remove.mutateAsync(id)
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  async function convert(id: string) {
    try {
      await actions.convertToTask.mutateAsync(id)
      toast.success('Göreve dönüştürüldü')
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  return (
    <div className="rounded-2xl border border-border bg-surface p-4">
      <div className={cn('mb-3 flex items-center gap-1.5 text-sm font-semibold', tone)}>
        <Icon size={15} /> {label} <span className="text-muted">· {items.length}</span>
      </div>

      <ul className="mb-3 space-y-2">
        {items.map((it) => (
          <li key={it.id} className="rounded-xl border border-border bg-surface-2 p-2.5 text-sm">
            <p className="whitespace-pre-wrap break-words">{it.body}</p>
            <div className="mt-2 flex items-center justify-between gap-2">
              <div className="flex items-center gap-1.5 text-xs text-muted">
                <Avatar name={it.authorName} seed={it.authorId} size={16} />
                {it.authorName}
              </div>
              <div className="flex items-center gap-1">
                {kind === 'action' && canWrite && !it.taskId && (
                  <Button size="sm" variant="outline" className="h-6 px-1.5 text-xs" onClick={() => convert(it.id)} title="Göreve dönüştür">
                    Göreve dönüştür
                  </Button>
                )}
                {kind === 'action' && it.taskId && <span className="text-xs text-muted">Göreve dönüştürüldü</span>}
                {it.authorId === currentUserId && (
                  <Button size="sm" variant="outline" className="h-6 w-6 p-0" onClick={() => remove(it.id)} title="Sil">
                    <Trash2 size={12} />
                  </Button>
                )}
              </div>
            </div>
          </li>
        ))}
        {items.length === 0 && <li className="py-2 text-center text-xs text-muted">Henüz madde yok.</li>}
      </ul>

      {canWrite && (
        <div className="flex gap-2">
          <textarea
            value={draft}
            maxLength={2000}
            rows={2}
            placeholder="Bir madde ekle…"
            onChange={(e) => setDraft(e.target.value)}
            className={cn(inputClass, 'h-auto resize-none py-2 text-sm')}
          />
        </div>
      )}
      {canWrite && (
        <div className="mt-2 flex justify-end">
          <Button size="sm" onClick={submit} loading={actions.create.isPending} disabled={!draft.trim()}>
            Ekle
          </Button>
        </div>
      )}
    </div>
  )
}
