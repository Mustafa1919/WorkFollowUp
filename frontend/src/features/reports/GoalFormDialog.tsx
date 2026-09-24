import { useState, type FormEvent } from 'react'
import { useGoalActions, useProjects } from '@/api/queries'
import type { GoalMetricType, GoalProgress } from '@/lib/types'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { Field, Input, inputClass } from '@/components/ui/Input'

const METRIC_LABEL: Record<GoalMetricType, string> = {
  COMPLETED_TASKS: 'Tamamlanan görev sayısı',
  COMPLETED_POINTS: 'Tamamlanan story point',
  CUSTOM: 'Elle takip edilen',
}

const METRIC_HINT: Record<GoalMetricType, string> = {
  COMPLETED_TASKS: 'İlerleme, dönemin raporuyla aynı kaynaktan otomatik hesaplanır.',
  COMPLETED_POINTS: 'Puansız görevler 0 puan sayılır (velocity ile aynı kural).',
  CUSTOM: 'Otomatik hesaplanamaz; ilerlemeyi kart üzerinden elle güncellersiniz.',
}

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  year: number
  quarter: number | null
  /** Dolu ise düzenleme: dönem ve metrik tipi DEĞİŞTİRİLEMEZ (hedefin kimliği). */
  editing: GoalProgress | null
}

/**
 * Hedef oluşturma/düzenleme. Dialog kapalıyken hiç render edilmez (`key` ile remount), böylece her
 * açılışta form durumu sıfırlanır — `TaskDialog`/`MeetingFormDialog` ile aynı desen.
 */
export function GoalFormDialog({ open, onOpenChange, year, quarter, editing }: Props) {
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={editing ? 'Hedefi düzenle' : 'Yeni hedef'}
      description={
        quarter ? `${year} · ${quarter}. çeyrek dönemi için` : `${year} yılı geneli için`
      }
    >
      {open && (
        <GoalForm
          key={editing?.id ?? 'new'}
          year={year}
          quarter={quarter}
          editing={editing}
          onDone={() => onOpenChange(false)}
        />
      )}
    </Dialog>
  )
}

function GoalForm({
  year,
  quarter,
  editing,
  onDone,
}: {
  year: number
  quarter: number | null
  editing: GoalProgress | null
  onDone: () => void
}) {
  const { data: projects } = useProjects()
  const { create, update } = useGoalActions(year, quarter)

  const [title, setTitle] = useState(editing?.title ?? '')
  const [metricType, setMetricType] = useState<GoalMetricType>(editing?.metricType ?? 'COMPLETED_TASKS')
  const [targetValue, setTargetValue] = useState(String(editing?.targetValue ?? 10))
  const [projectId, setProjectId] = useState(editing?.projectId ?? '')

  const target = Number(targetValue)
  const valid = title.trim().length > 0 && Number.isFinite(target) && target > 0
  const pending = create.isPending || update.isPending

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!valid) return
    const scope = projectId || null
    const done = { onSuccess: onDone }
    if (editing) {
      update.mutate({ id: editing.id, title: title.trim(), targetValue: target, projectId: scope }, done)
    } else {
      create.mutate({ title: title.trim(), metricType, targetValue: target, projectId: scope }, done)
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      <Field label="Başlık" id="goal-title">
        <Input
          id="goal-title"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="Örn. Bu çeyrekte 40 görev tamamla"
          maxLength={200}
          autoFocus
        />
      </Field>

      <Field label="Ölçüm" id="goal-metric">
        <select
          id="goal-metric"
          className={inputClass}
          value={metricType}
          onChange={(e) => setMetricType(e.target.value as GoalMetricType)}
          // Metrik tipi hedefin kimligidir: degistirilmesi gecmis ilerlemeyi baska bir seye ait
          // hale getirirdi (backend de reddeder). Duzenlemede kilitli.
          disabled={!!editing}
        >
          {(Object.keys(METRIC_LABEL) as GoalMetricType[]).map((m) => (
            <option key={m} value={m}>
              {METRIC_LABEL[m]}
            </option>
          ))}
        </select>
        <p className="mt-1.5 text-xs text-muted">{METRIC_HINT[metricType]}</p>
      </Field>

      <Field label="Hedef değer" id="goal-target">
        <Input
          id="goal-target"
          type="number"
          min={1}
          value={targetValue}
          onChange={(e) => setTargetValue(e.target.value)}
        />
      </Field>

      <Field label="Kapsam" id="goal-project">
        <select
          id="goal-project"
          className={inputClass}
          value={projectId}
          onChange={(e) => setProjectId(e.target.value)}
        >
          <option value="">Tüm workspace</option>
          {(projects ?? []).map((p) => (
            <option key={p.id} value={p.id}>
              {p.key} · {p.name}
            </option>
          ))}
        </select>
      </Field>

      <div className="flex justify-end gap-2 pt-1">
        <Button type="button" variant="ghost" onClick={onDone}>
          Vazgeç
        </Button>
        <Button type="submit" loading={pending} disabled={!valid}>
          {editing ? 'Kaydet' : 'Oluştur'}
        </Button>
      </div>
    </form>
  )
}
