import { Link, useParams } from 'react-router-dom'
import { motion } from 'motion/react'
import { ArrowLeft, Gauge, Timer, TrendingUp } from 'lucide-react'
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { useAnalytics, useProjects } from '@/api/queries'
import { fmt } from '@/lib/dates'
import { EmptyState, Page, Skeleton } from '@/components/ui/misc'

function duration(seconds: number | null | undefined) {
  if (seconds == null) return '–'
  const h = seconds / 3600
  if (h < 1) return `${Math.round(seconds / 60)} dk`
  if (h < 48) return `${h.toFixed(1)} sa`
  return `${(h / 24).toFixed(1)} gün`
}

const axis = { stroke: 'var(--muted)', fontSize: 12, tickLine: false, axisLine: false }
const tooltip = {
  contentStyle: { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 12, color: 'var(--fg)' },
  cursor: { fill: 'var(--surface-2)' },
}

export function AnalyticsPage() {
  const { projectId = '' } = useParams()
  const { data: projects } = useProjects()
  const project = projects?.find((p) => p.id === projectId)
  const { velocity, throughput, cycleTime } = useAnalytics(projectId)

  const velocityData = [...(velocity.data?.sprints ?? [])].reverse().map((s) => ({
    name: s.name,
    Taahhüt: s.committedPoints,
    Tamamlanan: s.completedPoints,
  }))
  const throughputData = (throughput.data?.weeks ?? []).map((w) => ({ week: fmt(w.weekStart, 'd MMM'), Görev: w.completedTasks }))

  return (
    <Page>
      <Link to={`/projects/${projectId}`} className="mb-3 inline-flex items-center gap-1.5 text-sm text-muted hover:text-fg">
        <ArrowLeft size={15} /> Panoya dön
      </Link>
      <h1 className="mb-8 text-2xl font-semibold tracking-tight">{project?.name ?? '…'} · Analitik</h1>

      <motion.div
        initial="hidden"
        animate="show"
        variants={{ hidden: {}, show: { transition: { staggerChildren: 0.07 } } }}
        className="mb-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-4"
      >
        <Metric icon={<Gauge size={18} />} label="Ort. velocity (puan)" value={velocity.data?.averageVelocity?.toFixed(1) ?? '–'} loading={velocity.isLoading} />
        <Metric icon={<Timer size={18} />} label="Cycle time medyan (30 gün)" value={duration(cycleTime.data?.medianSeconds)} loading={cycleTime.isLoading} />
        <Metric icon={<Timer size={18} />} label="Cycle time p85" value={duration(cycleTime.data?.p85Seconds)} loading={cycleTime.isLoading} />
        <Metric
          icon={<TrendingUp size={18} />}
          label="Örneklem (tamamlanan görev)"
          value={String(cycleTime.data?.sampleSize ?? '–')}
          loading={cycleTime.isLoading}
        />
      </motion.div>

      <div className="grid gap-6 lg:grid-cols-2">
        <ChartCard title="Velocity" subtitle="Tamamlanan sprintlerde taahhüt edilen ve biten story point">
          {velocity.isLoading ? (
            <Skeleton className="h-64" />
          ) : velocityData.length === 0 ? (
            <EmptyState icon={<Gauge size={20} />} title="Veri yok" text="Bir sprint tamamlandığında burada görünür." />
          ) : (
            <ResponsiveContainer width="100%" height={260}>
              <BarChart data={velocityData} barGap={4}>
                <CartesianGrid vertical={false} stroke="var(--border)" />
                <XAxis dataKey="name" {...axis} />
                <YAxis {...axis} width={32} />
                <Tooltip {...tooltip} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar dataKey="Taahhüt" fill="var(--border)" radius={[6, 6, 0, 0]} />
                <Bar dataKey="Tamamlanan" fill="var(--accent)" radius={[6, 6, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </ChartCard>

        <ChartCard title="Throughput" subtitle="Haftalık tamamlanan görev sayısı (son 12 hafta)">
          {throughput.isLoading ? (
            <Skeleton className="h-64" />
          ) : (
            <ResponsiveContainer width="100%" height={260}>
              <LineChart data={throughputData}>
                <CartesianGrid vertical={false} stroke="var(--border)" />
                <XAxis dataKey="week" {...axis} />
                <YAxis {...axis} width={32} allowDecimals={false} />
                <Tooltip {...tooltip} cursor={{ stroke: 'var(--border)' }} />
                <Line type="monotone" dataKey="Görev" stroke="var(--accent)" strokeWidth={2.5} dot={{ r: 3, fill: 'var(--accent)' }} activeDot={{ r: 5 }} />
              </LineChart>
            </ResponsiveContainer>
          )}
        </ChartCard>
      </div>
      <p className="mt-4 text-xs text-muted">
        Analitik, olay akışından (Kafka) asenkron hesaplanır; yeni bir değişikliğin yansıması birkaç saniye sürebilir.
      </p>
    </Page>
  )
}

function Metric({ icon, label, value, loading }: { icon: React.ReactNode; label: string; value: string; loading: boolean }) {
  return (
    <motion.div
      variants={{ hidden: { opacity: 0, y: 12 }, show: { opacity: 1, y: 0 } }}
      className="rounded-2xl border border-border bg-surface p-4"
    >
      <div className="mb-3 grid h-9 w-9 place-items-center rounded-xl bg-accent-soft text-accent">{icon}</div>
      {loading ? <Skeleton className="h-7 w-16" /> : <div className="text-2xl font-semibold tabular-nums">{value}</div>}
      <div className="text-xs text-muted">{label}</div>
    </motion.div>
  )
}

function ChartCard({ title, subtitle, children }: { title: string; subtitle: string; children: React.ReactNode }) {
  return (
    <motion.section
      initial={{ opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: 0.15, duration: 0.4 }}
      className="rounded-2xl border border-border bg-surface p-5"
    >
      <h2 className="font-semibold">{title}</h2>
      <p className="mb-4 text-xs text-muted">{subtitle}</p>
      {children}
    </motion.section>
  )
}
