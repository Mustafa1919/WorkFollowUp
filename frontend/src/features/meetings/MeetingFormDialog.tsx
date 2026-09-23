import { useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { useMeetingActions, type MeetingPayload } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { todayIso } from '@/lib/dates'
import { cn } from '@/lib/cn'
import type { Meeting, MeetingFrequency, Weekday } from '@/lib/types'
import { Dialog } from '@/components/ui/Dialog'
import { Field, Input, inputClass } from '@/components/ui/Input'
import { Button } from '@/components/ui/Button'

const FREQUENCIES: { value: MeetingFrequency; label: string }[] = [
  { value: 'ONCE', label: 'Bir kez' },
  { value: 'DAILY', label: 'Günlük' },
  { value: 'WEEKLY', label: 'Haftalık' },
  { value: 'MONTHLY', label: 'Aylık' },
]

const WEEKDAYS: { value: Weekday; label: string }[] = [
  { value: 'MON', label: 'Pzt' },
  { value: 'TUE', label: 'Sal' },
  { value: 'WED', label: 'Çar' },
  { value: 'THU', label: 'Per' },
  { value: 'FRI', label: 'Cum' },
  { value: 'SAT', label: 'Cmt' },
  { value: 'SUN', label: 'Paz' },
]

const REMINDER_OPTIONS = [
  { value: '', label: 'Yok' },
  { value: '5', label: '5 dakika önce' },
  { value: '15', label: '15 dakika önce' },
  { value: '30', label: '30 dakika önce' },
  { value: '60', label: '1 saat önce' },
]

type EndMode = 'never' | 'until' | 'count'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Dolu ise düzenleme modu. */
  meeting?: Meeting | null
}

/**
 * Toplantı serisi oluştur/düzenle. v1'de tek occurrence düzenlenemez — form her zaman SERİYİ
 * değiştirir. `TaskDialog`'daki `key={task.id}` deseniyle AYNI: Dialog kapaliyken `Fields` hic
 * render edilmez (Dialog.tsx `{open && ...}`), her acilista fresh bir instance state'i `meeting`
 * prop'undan lazy init eder — ayri bir "reset" effect'ine gerek kalmaz.
 */
export function MeetingFormDialog({ open, onOpenChange, meeting }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={meeting ? 'Toplantıyı düzenle' : 'Yeni toplantı'}>
      {open && <Fields key={meeting?.id ?? 'new'} meeting={meeting} onOpenChange={onOpenChange} />}
    </Dialog>
  )
}

function Fields({ meeting, onOpenChange }: { meeting?: Meeting | null; onOpenChange: (open: boolean) => void }) {
  const { create, update } = useMeetingActions()
  const editing = !!meeting

  const [title, setTitle] = useState(meeting?.title ?? '')
  const [description, setDescription] = useState(meeting?.description ?? '')
  const [meetingUrl, setMeetingUrl] = useState(meeting?.meetingUrl ?? '')
  const [startDate, setStartDate] = useState(meeting?.startDate ?? todayIso())
  const [startTime, setStartTime] = useState(meeting?.startTime.slice(0, 5) ?? '10:00')
  const [durationMinutes, setDurationMinutes] = useState(meeting?.durationMinutes ?? 30)
  const [frequency, setFrequency] = useState<MeetingFrequency>(meeting?.frequency ?? 'ONCE')
  const [intervalCount, setIntervalCount] = useState(meeting?.intervalCount ?? 1)
  const [byWeekday, setByWeekday] = useState<Weekday[]>(meeting?.byWeekday ?? [])
  const [endMode, setEndMode] = useState<EndMode>(meeting?.untilDate ? 'until' : meeting?.occurrenceCount ? 'count' : 'never')
  const [untilDate, setUntilDate] = useState(meeting?.untilDate ?? '')
  const [occurrenceCount, setOccurrenceCount] = useState(meeting?.occurrenceCount ?? 10)
  const [reminder, setReminder] = useState(meeting?.reminderMinutesBefore?.toString() ?? '')

  function toggleWeekday(day: Weekday) {
    setByWeekday((prev) => (prev.includes(day) ? prev.filter((d) => d !== day) : [...prev, day]))
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    const payload: MeetingPayload = {
      title,
      description: description || null,
      meetingUrl: meetingUrl || null,
      startDate,
      startTime: `${startTime}:00`,
      durationMinutes,
      frequency,
      intervalCount,
      byWeekday: frequency === 'WEEKLY' ? byWeekday : [],
      untilDate: endMode === 'until' ? untilDate : null,
      occurrenceCount: endMode === 'count' ? occurrenceCount : null,
      reminderMinutesBefore: reminder ? Number(reminder) : null,
    }
    try {
      if (editing) await update.mutateAsync({ id: meeting.id, ...payload })
      else await create.mutateAsync(payload)
      toast.success(editing ? 'Toplantı güncellendi' : 'Toplantı oluşturuldu')
      onOpenChange(false)
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  const pending = create.isPending || update.isPending

  return (
    <form onSubmit={submit} className="space-y-4">
      <Field label="Başlık" id="mt-title">
        <Input id="mt-title" autoFocus required maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} />
      </Field>

      <Field label="Açıklama (opsiyonel)" id="mt-desc">
        <textarea
          id="mt-desc"
          maxLength={1000}
          rows={2}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          className={cn(inputClass, 'h-auto resize-none py-2')}
        />
      </Field>

      <Field label="Görüşme linki (Zoom/Meet/Teams, opsiyonel)" id="mt-url">
        <Input
          id="mt-url"
          type="url"
          maxLength={500}
          placeholder="https://…"
          value={meetingUrl}
          onChange={(e) => setMeetingUrl(e.target.value)}
        />
      </Field>

      <div className="grid grid-cols-3 gap-3">
        <Field label="Tarih" id="mt-date">
          <Input id="mt-date" type="date" required min={todayIso()} value={startDate} onChange={(e) => setStartDate(e.target.value)} />
        </Field>
        <Field label="Saat" id="mt-time">
          <Input id="mt-time" type="time" required value={startTime} onChange={(e) => setStartTime(e.target.value)} />
        </Field>
        <Field label="Süre (dk)" id="mt-duration">
          <Input
            id="mt-duration"
            type="number"
            required
            min={5}
            max={480}
            step={5}
            value={durationMinutes}
            onChange={(e) => setDurationMinutes(Number(e.target.value))}
          />
        </Field>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <Field label="Tekrar" id="mt-freq">
          <select
            id="mt-freq"
            value={frequency}
            onChange={(e) => setFrequency(e.target.value as MeetingFrequency)}
            className={cn(inputClass, 'cursor-pointer')}
          >
            {FREQUENCIES.map((f) => (
              <option key={f.value} value={f.value}>
                {f.label}
              </option>
            ))}
          </select>
        </Field>
        {frequency !== 'ONCE' && (
          <Field
            label={frequency === 'DAILY' ? 'Kaç günde bir' : frequency === 'WEEKLY' ? 'Kaç haftada bir' : 'Kaç ayda bir'}
            id="mt-interval"
          >
            <Input
              id="mt-interval"
              type="number"
              min={1}
              max={52}
              value={intervalCount}
              onChange={(e) => setIntervalCount(Number(e.target.value))}
            />
          </Field>
        )}
      </div>

      {frequency === 'WEEKLY' && (
        <div>
          <div className="mb-1.5 text-xs font-medium text-muted">Günler</div>
          <div className="flex flex-wrap gap-1.5">
            {WEEKDAYS.map((d) => (
              <button
                key={d.value}
                type="button"
                onClick={() => toggleWeekday(d.value)}
                className={cn(
                  'h-9 min-w-9 cursor-pointer rounded-lg border px-2 text-sm transition-colors',
                  byWeekday.includes(d.value)
                    ? 'border-accent bg-accent text-accent-fg'
                    : 'border-border bg-surface text-muted hover:text-fg',
                )}
              >
                {d.label}
              </button>
            ))}
          </div>
        </div>
      )}

      {frequency !== 'ONCE' && (
        <div>
          <div className="mb-1.5 text-xs font-medium text-muted">Bitiş</div>
          <div className="flex flex-wrap items-center gap-3 text-sm">
            {(['never', 'until', 'count'] as EndMode[]).map((mode) => (
              <label key={mode} className="flex cursor-pointer items-center gap-1.5">
                <input type="radio" name="end-mode" checked={endMode === mode} onChange={() => setEndMode(mode)} />
                {mode === 'never' ? 'Süresiz' : mode === 'until' ? 'Bitiş tarihi' : 'Tekrar sayısı'}
              </label>
            ))}
          </div>
          {endMode === 'until' && (
            <Input type="date" required min={startDate} value={untilDate} onChange={(e) => setUntilDate(e.target.value)} className="mt-2" />
          )}
          {endMode === 'count' && (
            <Input
              type="number"
              required
              min={1}
              max={366}
              value={occurrenceCount}
              onChange={(e) => setOccurrenceCount(Number(e.target.value))}
              className="mt-2 w-28"
            />
          )}
        </div>
      )}

      <Field label="Hatırlatma" id="mt-reminder">
        <select id="mt-reminder" value={reminder} onChange={(e) => setReminder(e.target.value)} className={cn(inputClass, 'cursor-pointer')}>
          {REMINDER_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </Field>

      <div className="flex justify-end gap-2 pt-2">
        <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
          Vazgeç
        </Button>
        <Button type="submit" loading={pending}>
          {editing ? 'Kaydet' : 'Oluştur'}
        </Button>
      </div>
    </form>
  )
}
