import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { motion } from 'motion/react'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { ArrowDown, ArrowUp, CheckCircle2, Gauge, Minus, Pencil, Plus, Target, Timer, Trash2 } from 'lucide-react'
import { useCurrentRole, useGoalActions, usePeriodReport, useProjects } from '@/api/queries'
import { cn } from '@/lib/cn'
import { fmt } from '@/lib/dates'
import type { GoalProgress } from '@/lib/types'
import { Button } from '@/components/ui/Button'
import { EmptyState, Page, Skeleton } from '@/components/ui/misc'
import { GoalFormDialog } from './GoalFormDialog'

const QUARTERS = [1, 2, 3, 4] as const
const YEAR_SPAN = 5

const axis = { stroke: 'var(--muted)', fontSize: 12, tickLine: false, axisLine: false }
const tooltip = {
  contentStyle: { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 12, color: 'var(--fg)' },
  cursor: { fill: 'var(--surface-2)' },
}

function duration(seconds: number | null | undefined) {
  if (seconds == null) return '–'
  const h = seconds / 3600
  if (h < 1) return `${Math.round(seconds / 60)} dk`
  if (h < 48) return `${h.toFixed(1)} sa`
  return `${(h / 24).toFixed(1)} gün`
}

/**
 * Dönemsel rapor — TAKVIM dönemi (yıl / çeyrek) üzerinden, workspace geneli + proje kırılımı.
 * Dönem ve proje süzgeci URL'de tutulur (`?year=&quarter=&projects=`): rapor paylaşılabilir ve
 * tarayıcı geri tuşu beklendiği gibi çalışır.
 */
export function ReportsPage() {
  const [params, setParams] = useSearchParams()
  const role = useCurrentRole()
  const canManage = role === 'WORKSPACE_ADMIN' || role === 'MANAGER'
  const { data: projects } = useProjects()

  const now = new Date()
  const year = Number(params.get('year')) || now.getFullYear()
  const quarterParam = Number(params.get('quarter'))
  const quarter = QUARTERS.includes(quarterParam as (typeof QUARTERS)[number]) ? quarterParam : null
  const selected = useMemo(() => (params.get('projects') ?? '').split(',').filter(Boolean), [params])

  const { data: report, isLoading } = usePeriodReport(year, quarter, selected)
  const { progress, remove } = useGoalActions(year, quarter)

  const [goalDialogOpen, setGoalDialogOpen] = useState(false)
  const [editingGoal, setEditingGoal] = useState<GoalProgress | null>(null)

  function setParam(key: string, value: string | null) {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    setParams(next, { replace: true })
  }

  function toggleProject(id: string) {
    const next = selected.includes(id) ? selected.filter((p) => p !== id) : [...selected, id]
    setParam('projects', next.join(','))
  }

  const months = (report?.months ?? []).map((m) => ({
    ay: fmt(m.month, quarter ? 'LLLL' : 'LLL'),
    Görev: m.completedTasks,
    Puan: m.completedPoints,
  }))

  return (
    <Page>
      <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Raporlar</h1>
          <p className="mt-1 text-sm text-muted">
            {report
              ? `${report.period.label} · ${fmt(report.period.startDate, 'd MMM yyyy')} – ${fmt(report.period.endDate, 'd MMM yyyy')}`
              : 'Dönem yükleniyor…'}
          </p>
        </div>
        {canManage && (
          <Button
            onClick={() => {
              setEditingGoal(null)
              setGoalDialogOpen(true)
            }}
          >
            <Plus size={16} /> Hedef ekle
          </Button>
        )}
      </div>

      {/* Dönem seçici */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <select
          value={year}
          onChange={(e) => setParam('year', e.target.value)}
          className="h-9 cursor-pointer rounded-xl border border-border bg-surface px-3 text-sm outline-none focus:border-accent"
          aria-label="Yıl"
        >
          {Array.from({ length: YEAR_SPAN }, (_, i) => now.getFullYear() - i).map((y) => (
            <option key={y} value={y}>
              {y}
            </option>
          ))}
        </select>
        <div className="flex gap-1 rounded-xl border border-border bg-surface p-1">
          <PeriodTab active={quarter === null} onClick={() => setParam('quarter', null)}>
            Tüm yıl
          </PeriodTab>
          {QUARTERS.map((q) => (
            <PeriodTab key={q} active={quarter === q} onClick={() => setParam('quarter', String(q))}>
              Q{q}
            </PeriodTab>
          ))}
        </div>
      </div>

      {/* Proje süzgeci */}
      {(projects?.length ?? 0) > 0 && (
        <div className="mb-6 flex flex-wrap items-center gap-1.5">
          <span className="mr-1 text-xs font-medium text-muted">Projeler:</span>
          <FilterChip active={selected.length === 0} onClick={() => setParam('projects', null)}>
            Tümü
          </FilterChip>
          {(projects ?? []).map((p) => (
            <FilterChip key={p.id} active={selected.includes(p.id)} onClick={() => toggleProject(p.id)}>
              {p.key}
            </FilterChip>
          ))}
        </div>
      )}

      {isLoading && !report ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }, (_, i) => (
            <Skeleton key={i} className="h-24" />
          ))}
        </div>
      ) : !report ? (
        <EmptyState icon={<Target size={22} />} title="Rapor yüklenemedi" text="Lütfen tekrar deneyin." />
      ) : (
        <>
          <motion.div
            initial="hidden"
            animate="show"
            variants={{ hidden: {}, show: { transition: { staggerChildren: 0.06 } } }}
            className="mb-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-4"
          >
            <Metric
              icon={<CheckCircle2 size={18} />}
              label="Tamamlanan görev"
              value={String(report.totals.completedTasks)}
              delta={delta(report.totals.completedTasks, report.previousTotals.completedTasks)}
              note={`önceki dönem: ${report.previousTotals.completedTasks}`}
            />
            <Metric
              icon={<Gauge size={18} />}
              label="Tamamlanan puan"
              value={String(report.totals.completedPoints)}
              delta={delta(report.totals.completedPoints, report.previousTotals.completedPoints)}
              note={`önceki dönem: ${report.previousTotals.completedPoints}`}
            />
            <Metric
              icon={<Timer size={18} />}
              label="Cycle time medyan"
              value={duration(report.totals.cycleTimeMedianSeconds)}
              note={
                report.totals.cycleTimeSample > 0
                  ? `${report.totals.cycleTimeSample} görev · p85 ${duration(report.totals.cycleTimeP85Seconds)}`
                  : 'ölçülebilir görev yok'
              }
            />
            <Metric
              icon={<Target size={18} />}
              label="Kapanan sprint"
              value={String(report.totals.sprintCount)}
              note={
                report.totals.sprintCount > 0
                  ? `ort. velocity ${report.totals.averageVelocity?.toFixed(1) ?? '–'} puan`
                  : 'dönemde kapanan sprint yok'
              }
            />
          </motion.div>

          <section className="mb-6 rounded-2xl border border-border bg-surface p-5">
            <h2 className="mb-4 text-sm font-semibold">Aylık tamamlama</h2>
            <div className="h-72">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={months}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
                  <XAxis dataKey="ay" {...axis} />
                  <YAxis {...axis} allowDecimals={false} />
                  <Tooltip {...tooltip} />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  <Bar dataKey="Görev" fill="var(--accent)" radius={[6, 6, 0, 0]} />
                  <Bar dataKey="Puan" fill="var(--muted)" radius={[6, 6, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </section>

          <section className="mb-6 rounded-2xl border border-border bg-surface p-5">
            <h2 className="mb-4 text-sm font-semibold">Proje kırılımı</h2>
            {report.projects.length === 0 ? (
              <p className="py-6 text-center text-sm text-muted">Bu dönemde tamamlanan görev yok.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border text-left text-xs text-muted">
                      <th className="pb-2 font-medium">Proje</th>
                      <th className="pb-2 text-right font-medium">Görev</th>
                      <th className="pb-2 text-right font-medium">Puan</th>
                      <th className="pb-2 text-right font-medium">Cycle time medyan</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.projects.map((p) => (
                      <tr key={p.projectId} className="border-b border-border/60 last:border-0">
                        <td className="py-2.5">
                          <span className="rounded-md bg-surface-2 px-1.5 py-0.5 text-xs text-muted">{p.projectKey}</span>{' '}
                          {p.projectName}
                        </td>
                        <td className="py-2.5 text-right tabular-nums">{p.completedTasks}</td>
                        <td className="py-2.5 text-right tabular-nums">{p.completedPoints}</td>
                        <td className="py-2.5 text-right tabular-nums text-muted">
                          {duration(p.cycleTimeMedianSeconds)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="rounded-2xl border border-border bg-surface p-5">
            <div className="mb-1 flex items-center justify-between gap-3">
              <h2 className="text-sm font-semibold">Dönem hedefleri</h2>
              <span className="text-xs text-muted">{report.period.label}</span>
            </div>
            <p className="mb-4 text-xs text-muted">
              Hedef ilerlemesi proje süzgecinden etkilenmez; hedefin kendi kapsamı üzerinden hesaplanır.
            </p>
            {report.goals.length === 0 ? (
              <EmptyState
                icon={<Target size={22} />}
                title="Bu dönem için hedef yok"
                text={
                  canManage
                    ? 'Dönem hedefi ekleyin; ilerleme tamamlanan görev/puan üzerinden otomatik hesaplanır.'
                    : 'Yönetici bir hedef tanımladığında burada görünür.'
                }
              />
            ) : (
              <ul className="space-y-3">
                {report.goals.map((goal) => (
                  <GoalCard
                    key={goal.id}
                    goal={goal}
                    canManage={canManage}
                    onEdit={() => {
                      setEditingGoal(goal)
                      setGoalDialogOpen(true)
                    }}
                    onDelete={() => remove.mutate(goal.id)}
                    onProgress={(value) => progress.mutate({ id: goal.id, value })}
                  />
                ))}
              </ul>
            )}
          </section>
        </>
      )}

      <GoalFormDialog
        open={goalDialogOpen}
        onOpenChange={setGoalDialogOpen}
        year={year}
        quarter={quarter}
        editing={editingGoal}
      />
    </Page>
  )
}

/** Yuzde degisim; onceki donem 0 ise oran tanimsizdir (null) — "%∞ artis" gostermeyiz. */
function delta(current: number, previous: number): number | null {
  if (previous === 0) return null
  return Math.round(((current - previous) / previous) * 100)
}

function PeriodTab({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'cursor-pointer rounded-lg px-3 py-1.5 text-sm transition-colors',
        active ? 'bg-accent text-accent-fg' : 'text-muted hover:bg-surface-2 hover:text-fg',
      )}
    >
      {children}
    </button>
  )
}

function FilterChip({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'cursor-pointer rounded-full border px-2.5 py-1 text-xs transition-colors',
        active ? 'border-accent bg-accent-soft text-accent' : 'border-border text-muted hover:text-fg',
      )}
    >
      {children}
    </button>
  )
}

function Metric({
  icon,
  label,
  value,
  note,
  delta: change,
}: {
  icon: React.ReactNode
  label: string
  value: string
  note?: string
  delta?: number | null
}) {
  return (
    <motion.div
      variants={{ hidden: { opacity: 0, y: 10 }, show: { opacity: 1, y: 0 } }}
      className="rounded-2xl border border-border bg-surface p-4"
    >
      <div className="mb-2 flex items-center justify-between">
        <span className="grid h-8 w-8 place-items-center rounded-xl bg-accent-soft text-accent">{icon}</span>
        {change != null && (
          <span
            className={cn(
              'inline-flex items-center gap-0.5 rounded-full px-1.5 py-0.5 text-xs',
              change > 0 ? 'text-success' : change < 0 ? 'text-danger' : 'text-muted',
            )}
          >
            {change > 0 ? <ArrowUp size={12} /> : change < 0 ? <ArrowDown size={12} /> : <Minus size={12} />}
            {Math.abs(change)}%
          </span>
        )}
      </div>
      <div className="text-2xl font-semibold tabular-nums">{value}</div>
      <div className="mt-0.5 text-xs text-muted">{label}</div>
      {note && <div className="mt-1 text-[11px] text-muted/80">{note}</div>}
    </motion.div>
  )
}

function GoalCard({
  goal,
  canManage,
  onEdit,
  onDelete,
  onProgress,
}: {
  goal: GoalProgress
  canManage: boolean
  onEdit: () => void
  onDelete: () => void
  onProgress: (value: number) => void
}) {
  const [draft, setDraft] = useState(String(goal.currentValue))
  const custom = goal.metricType === 'CUSTOM'

  return (
    <li className="rounded-xl border border-border bg-surface-2/40 p-4">
      <div className="mb-2 flex flex-wrap items-start justify-between gap-2">
        <div>
          <div className="font-medium">{goal.title}</div>
          <div className="mt-0.5 text-xs text-muted">
            {goal.projectName ?? 'Tüm workspace'} ·{' '}
            {goal.metricType === 'COMPLETED_TASKS'
              ? 'tamamlanan görev'
              : goal.metricType === 'COMPLETED_POINTS'
                ? 'tamamlanan puan'
                : 'elle takip'}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-sm tabular-nums">
            <strong>{goal.currentValue}</strong>
            <span className="text-muted"> / {goal.targetValue}</span>
          </span>
          {canManage && (
            <>
              <Button variant="ghost" size="icon" onClick={onEdit} aria-label="Hedefi düzenle">
                <Pencil size={15} />
              </Button>
              <Button variant="ghost" size="icon" onClick={onDelete} aria-label="Hedefi sil">
                <Trash2 size={15} className="text-danger" />
              </Button>
            </>
          )}
        </div>
      </div>

      <div className="h-2 overflow-hidden rounded-full bg-surface-2">
        <motion.div
          className="h-full rounded-full bg-accent"
          initial={{ width: 0 }}
          animate={{ width: `${goal.progressPercent}%` }}
          transition={{ duration: 0.6, ease: [0.22, 1, 0.36, 1] }}
        />
      </div>
      <div className="mt-1.5 flex items-center justify-between gap-3">
        <span className="text-xs text-muted">%{goal.progressPercent}</span>
        {custom && canManage && (
          <form
            onSubmit={(e) => {
              e.preventDefault()
              const value = Number(draft)
              if (Number.isFinite(value) && value >= 0) onProgress(value)
            }}
            className="flex items-center gap-1.5"
          >
            <input
              type="number"
              min={0}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              className="h-7 w-20 rounded-lg border border-border bg-surface px-2 text-xs tabular-nums outline-none focus:border-accent"
              aria-label="İlerleme değeri"
            />
            <Button type="submit" variant="outline" size="sm">
              Güncelle
            </Button>
          </form>
        )}
      </div>
    </li>
  )
}
