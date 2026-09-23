import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { addDays, addMonths, format, isSameMonth, isToday, isWeekend, parse, startOfMonth, startOfWeek } from 'date-fns'
import { AnimatePresence, motion } from 'motion/react'
import { CalendarClock, ChevronLeft, ChevronRight, Plus, Video } from 'lucide-react'
import { useCurrentRole, useMeetingOccurrences, useMeetings } from '@/api/queries'
import { cn } from '@/lib/cn'
import { fmt, iso } from '@/lib/dates'
import type { Meeting, MeetingOccurrence } from '@/lib/types'
import { Button } from '@/components/ui/Button'
import { EmptyState, Page } from '@/components/ui/misc'
import { MeetingFormDialog } from './MeetingFormDialog'
import { MeetingDetailDialog } from './MeetingDetailDialog'

const WEEKDAYS = ['Pzt', 'Sal', 'Çar', 'Per', 'Cum', 'Cmt', 'Paz']
const MAX_VISIBLE = 3

const FREQUENCY_LABEL: Record<Meeting['frequency'], string> = {
  ONCE: 'Bir kez',
  DAILY: 'Günlük',
  WEEKLY: 'Haftalık',
  MONTHLY: 'Aylık',
}

/** Workspace geneli toplantı takvimi — proje sınırına bağlı değil (bkz. Mimari.md kararı). */
export function MeetingsPage() {
  const [params, setParams] = useSearchParams()
  const role = useCurrentRole()
  const canManage = role === 'WORKSPACE_ADMIN' || role === 'MANAGER'

  const monthParam = params.get('month')
  const month = useMemo(() => {
    const parsed = monthParam ? parse(monthParam, 'yyyy-MM', new Date()) : new Date()
    return startOfMonth(Number.isNaN(parsed.getTime()) ? new Date() : parsed)
  }, [monthParam])

  const gridStart = startOfWeek(month, { weekStartsOn: 1 })
  const days = Array.from({ length: 42 }, (_, i) => addDays(gridStart, i))
  const from = iso(days[0])
  const to = iso(days[41])

  const { data: occurrences, isLoading } = useMeetingOccurrences(from, to)
  const { data: meetings } = useMeetings()

  const [formOpen, setFormOpen] = useState(false)
  const [editingMeeting, setEditingMeeting] = useState<Meeting | null>(null)
  const [selectedOccurrence, setSelectedOccurrence] = useState<MeetingOccurrence | null>(null)

  const byDay = useMemo(() => {
    const map = new Map<string, MeetingOccurrence[]>()
    for (const o of occurrences ?? []) {
      map.set(o.date, [...(map.get(o.date) ?? []), o])
    }
    return map
  }, [occurrences])

  function goTo(target: Date) {
    const next = new URLSearchParams(params)
    next.set('month', format(target, 'yyyy-MM'))
    setParams(next, { replace: true })
  }

  function openNew() {
    setEditingMeeting(null)
    setFormOpen(true)
  }

  function editFromDetail(meeting: Meeting) {
    setSelectedOccurrence(null)
    setEditingMeeting(meeting)
    setFormOpen(true)
  }

  const monthKey = format(month, 'yyyy-MM')
  const lastWeekVisible = isSameMonth(days[35], month)
  const selectedMeeting = meetings?.find((m) => m.id === selectedOccurrence?.meetingId)

  return (
    <Page className="max-w-none">
      <div className="mb-6 flex flex-wrap items-center gap-3">
        <div className="mr-auto">
          <h1 className="text-2xl font-semibold tracking-tight">Toplantılar</h1>
          <p className="text-sm text-muted">Workspace genelinde tekrarlanan toplantı planı.</p>
        </div>
        {canManage && (
          <Button size="sm" onClick={openNew}>
            <Plus size={15} /> Yeni toplantı
          </Button>
        )}
      </div>

      <div className="grid gap-4 xl:grid-cols-[1fr_280px]">
        <div className="overflow-hidden rounded-2xl border border-border bg-surface">
          <div className="flex items-center gap-2 border-b border-border px-4 py-3">
            <h2 className="min-w-36 text-lg font-semibold capitalize">{fmt(month, 'MMMM yyyy')}</h2>
            {isLoading && <span className="h-3 w-3 animate-spin rounded-full border-2 border-accent border-t-transparent" />}
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
              <div className="grid grid-cols-7" key={monthKey}>
                {days.map((day, i) => {
                  if (i >= 35 && !lastWeekVisible) return null
                  const key = iso(day)
                  return (
                    <DayCell
                      key={key}
                      date={day}
                      inMonth={isSameMonth(day, month)}
                      occurrences={byDay.get(key) ?? []}
                      onOpen={setSelectedOccurrence}
                    />
                  )
                })}
              </div>
            </div>
          </div>
        </div>

        <aside className="flex max-h-[calc(100vh-180px)] flex-col rounded-2xl border border-border bg-surface p-3 xl:sticky xl:top-20">
          <div className="mb-3 flex items-center gap-2 px-1">
            <CalendarClock size={16} className="text-muted" />
            <h3 className="text-sm font-medium">Tanımlı toplantılar</h3>
          </div>
          <div className="-mx-1 flex-1 space-y-1.5 overflow-y-auto px-1">
            {meetings?.map((m) => (
              <button
                key={m.id}
                onClick={() => canManage && editFromDetail(m)}
                disabled={!canManage}
                className="flex w-full cursor-pointer flex-col gap-0.5 rounded-xl border border-border p-2.5 text-left transition-colors hover:bg-surface-2 disabled:cursor-default disabled:hover:bg-transparent"
              >
                <span className="truncate text-sm font-medium">{m.title}</span>
                <span className="text-xs text-muted">
                  {FREQUENCY_LABEL[m.frequency]} · {m.startTime.slice(0, 5)}
                  {m.meetingUrl && <Video size={11} className="ml-1.5 inline" />}
                </span>
              </button>
            ))}
            {meetings?.length === 0 && (
              <EmptyState icon={<CalendarClock size={20} />} title="Henüz toplantı yok" text="Sağ üstten yeni bir toplantı planla." />
            )}
          </div>
        </aside>
      </div>

      <MeetingFormDialog open={formOpen} onOpenChange={setFormOpen} meeting={editingMeeting} />
      <MeetingDetailDialog
        occurrence={selectedOccurrence}
        meeting={selectedMeeting}
        canManage={canManage}
        onOpenChange={(o) => !o && setSelectedOccurrence(null)}
        onEdit={editFromDetail}
      />
    </Page>
  )
}

function DayCell({
  date,
  inMonth,
  occurrences,
  onOpen,
}: {
  date: Date
  inMonth: boolean
  occurrences: MeetingOccurrence[]
  onOpen: (o: MeetingOccurrence) => void
}) {
  const [expanded, setExpanded] = useState(false)
  const today = isToday(date)
  const visible = expanded ? occurrences : occurrences.slice(0, MAX_VISIBLE)
  const hidden = occurrences.length - visible.length

  return (
    <div
      className={cn(
        'relative min-h-28 border-r border-b border-border p-1.5 [&:nth-child(7n)]:border-r-0',
        !inMonth && 'bg-surface-2/40',
        isWeekend(date) && inMonth && 'bg-surface-2/20',
      )}
    >
      <div className="mb-1 px-1">
        <span
          className={cn(
            'grid h-6 min-w-6 place-items-center rounded-full px-1 text-xs tabular-nums',
            today ? 'bg-accent font-semibold text-accent-fg shadow-md shadow-accent/40' : inMonth ? 'text-fg' : 'text-muted/60',
          )}
        >
          {format(date, 'd')}
        </span>
      </div>
      <div className="space-y-1">
        <AnimatePresence initial={false}>
          {visible.map((o) => (
            <motion.button
              key={`${o.meetingId}-${o.date}`}
              layout
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.9 }}
              transition={{ type: 'spring', stiffness: 500, damping: 38 }}
              onClick={() => onOpen(o)}
              title={`${o.startTime.slice(0, 5)} · ${o.title}`}
              className="flex w-full cursor-pointer items-center gap-1.5 truncate rounded-md border-l-2 border-accent bg-accent-soft px-1.5 py-1 text-left text-[11px] leading-tight text-accent"
            >
              <span className="shrink-0 font-mono">{o.startTime.slice(0, 5)}</span>
              <span className="truncate">{o.title}</span>
            </motion.button>
          ))}
        </AnimatePresence>
        {(hidden > 0 || (expanded && occurrences.length > MAX_VISIBLE)) && (
          <button
            onClick={() => setExpanded((e) => !e)}
            className="w-full cursor-pointer rounded-md px-1.5 py-0.5 text-left text-[11px] text-muted hover:bg-surface-2 hover:text-fg"
          >
            {expanded ? 'Daha az' : `+${hidden} daha`}
          </button>
        )}
      </div>
    </div>
  )
}

