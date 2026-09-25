import { useMemo, useState } from 'react'
import { toast } from 'sonner'
import { CalendarClock, CircleDot, Flame, GitBranch, ListTodo, Octagon } from 'lucide-react'
import { useMeetings, useStandups, useUpdateStandupNote } from '@/api/queries'
import { useCurrentUserId } from '@/stores/session'
import { errorMessage } from '@/lib/api'
import { taskKey } from '@/lib/status'
import { todayIso } from '@/lib/dates'
import type { StandupDigest, StandupTaskRef } from '@/lib/types'
import { Avatar } from '@/components/ui/Avatar'
import { EmptyState, Page } from '@/components/ui/misc'
import { Button } from '@/components/ui/Button'
import { Field, Input, inputClass } from '@/components/ui/Input'
import { cn } from '@/lib/cn'

const SECTIONS: {
  key: keyof StandupDigest['facts']
  label: string
  icon: typeof ListTodo
  tone: string
}[] = [
  { key: 'completedYesterday', label: 'Dün tamamlanan', icon: CircleDot, tone: 'text-st-done' },
  { key: 'progressedYesterday', label: 'Dün ilerleyen', icon: ListTodo, tone: 'text-st-progress' },
  { key: 'inProgress', label: 'Şu an üzerinde çalışılan', icon: ListTodo, tone: 'text-st-progress' },
  { key: 'blocked', label: 'Bloklanan', icon: Octagon, tone: 'text-danger' },
  { key: 'aging', label: 'Takılan (Aging WIP)', icon: Flame, tone: 'text-st-review' },
  { key: 'githubActivity', label: 'GitHub aktivitesi', icon: GitBranch, tone: 'text-muted' },
]

/** Dalga 2.3 — toplantısız standup: tarih + toplantı seçici, her katılımcının günlük özeti. */
export function StandupsPage() {
  const { data: meetings } = useMeetings()
  const standupMeetings = useMemo(() => meetings?.filter((m) => m.standupEnabled) ?? [], [meetings])
  const [meetingId, setMeetingId] = useState('')
  const [date, setDate] = useState(todayIso())
  const activeMeetingId = meetingId || standupMeetings[0]?.id || ''

  const { data: digests, isLoading } = useStandups(activeMeetingId, date, !!activeMeetingId)
  const currentUserId = useCurrentUserId()

  if (standupMeetings.length === 0) {
    return (
      <Page>
        <EmptyState
          icon={<CalendarClock size={22} />}
          title="Standup özeti açık bir toplantı yok"
          text="Toplantılar sayfasında bir toplantı için 'Toplantısız standup özeti' seçeneğini açın."
        />
      </Page>
    )
  }

  return (
    <Page>
      <div className="mb-6 flex flex-wrap items-center gap-3">
        <h1 className="text-lg font-semibold">Standup</h1>
        <select
          value={activeMeetingId}
          onChange={(e) => setMeetingId(e.target.value)}
          className={cn(inputClass, 'w-auto cursor-pointer')}
        >
          {standupMeetings.map((m) => (
            <option key={m.id} value={m.id}>
              {m.title}
            </option>
          ))}
        </select>
        <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} className="w-auto" />
      </div>

      {isLoading && <div className="text-sm text-muted">Yükleniyor…</div>}
      {!isLoading && digests?.length === 0 && (
        <EmptyState icon={<CalendarClock size={22} />} title="Bu tarih için özet yok" text="Özet, occurrence başlamadan 30 dakika önce otomatik üretilir." />
      )}

      <div className="space-y-4">
        {digests?.map((digest) => (
          <DigestCard
            key={digest.userId}
            digest={digest}
            editable={digest.userId === currentUserId}
            meetingId={activeMeetingId}
            date={date}
          />
        ))}
      </div>
    </Page>
  )
}

function DigestCard({
  digest,
  editable,
  meetingId,
  date,
}: {
  digest: StandupDigest
  editable: boolean
  meetingId: string
  date: string
}) {
  const [note, setNote] = useState(digest.note ?? '')
  const updateNote = useUpdateStandupNote(meetingId, date)

  async function saveNote() {
    try {
      await updateNote.mutateAsync(note)
      toast.success('Not kaydedildi')
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  const hasAnyFact = SECTIONS.some((s) => digest.facts[s.key].length > 0)

  return (
    <div className="rounded-2xl border border-border bg-surface p-4">
      <div className="mb-3 flex items-center gap-2">
        <Avatar name={digest.userName} seed={digest.userId} size={22} />
        <span className="text-sm font-medium">{digest.userName}</span>
      </div>

      {!hasAnyFact && <div className="text-sm text-muted">Bu kullanıcı için kayıtlı bir aktivite yok.</div>}

      <div className="grid gap-3 sm:grid-cols-2">
        {SECTIONS.filter((s) => digest.facts[s.key].length > 0).map((section) => (
          <div key={section.key}>
            <div className={cn('mb-1 flex items-center gap-1.5 text-xs font-medium', section.tone)}>
              <section.icon size={13} /> {section.label}
            </div>
            <ul className="space-y-0.5 text-sm">
              {digest.facts[section.key].map((ref: StandupTaskRef) => (
                <li key={ref.taskId} className="truncate text-muted">
                  <span className="font-mono text-xs">{taskKey(ref.projectKey, ref.taskNumber)}</span> {ref.title}
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

      {editable && (
        <div className="mt-4 border-t border-border pt-3">
          <Field label="Bugün planım / engelim" id={`note-${digest.userId}`}>
            <textarea
              id={`note-${digest.userId}`}
              maxLength={2000}
              rows={2}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              className={cn(inputClass, 'h-auto resize-none py-2')}
            />
          </Field>
          <div className="mt-2 flex justify-end">
            <Button size="sm" onClick={saveNote} loading={updateNote.isPending}>
              Kaydet
            </Button>
          </div>
        </div>
      )}
      {!editable && digest.note && <p className="mt-3 border-t border-border pt-3 text-sm">{digest.note}</p>}
    </div>
  )
}
