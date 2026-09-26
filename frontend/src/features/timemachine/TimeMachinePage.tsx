import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, History } from 'lucide-react'
import { useBoardSnapshot, useMemberMap, useProjects, useSprints } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { taskKey, STATUS_META } from '@/lib/status'
import type { TaskSnapshotResponse, TaskStatus } from '@/lib/types'
import { Avatar } from '@/components/ui/Avatar'
import { inputClass } from '@/components/ui/Input'
import { EmptyState, Page, Skeleton, StatusDot } from '@/components/ui/misc'
import { cn } from '@/lib/cn'

const STATUSES: TaskStatus[] = ['To Do', 'In Progress', 'Review', 'Done']

/** "yyyy-MM-ddTHH:mm" (yerel saat, datetime-local input formati). */
function toLocalInputValue(d: Date) {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * ADR-0017 — Zaman makinesi: bir projenin gecmisteki bir andaki board anlik goruntusu.
 * Tags/dependency/title bu kesitte YOK (bilinen sinir, TaskSnapshotRepository javadoc'una bkz.).
 */
export function TimeMachinePage() {
  const { projectId = '' } = useParams()
  const { data: projects } = useProjects()
  const project = projects?.find((p) => p.id === projectId)
  const { data: sprints } = useSprints(projectId)
  const memberMap = useMemberMap()

  const [cutoffInput, setCutoffInput] = useState(() => toLocalInputValue(new Date()))
  const cutoffIso = useMemo(() => {
    const parsed = new Date(cutoffInput)
    return Number.isNaN(parsed.getTime()) ? '' : parsed.toISOString()
  }, [cutoffInput])

  // Gelecek bir an secilirse backend BusinessRuleException doner, asagida `error` olarak gosterilir
  // (input'un `max` alani zaten cogu tarayicida gelecegi engeller).
  const { data: snapshot, isLoading, error } = useBoardSnapshot(projectId, cutoffIso, !!cutoffIso)

  const sprintName = (sprintId: string | null) =>
    sprintId ? (sprints ?? []).find((s) => s.id === sprintId)?.name : undefined

  const byStatus = useMemo(() => {
    const map = new Map<TaskStatus, TaskSnapshotResponse[]>(STATUSES.map((s) => [s, []]))
    for (const row of snapshot ?? []) {
      map.get(row.status)?.push(row)
    }
    return map
  }, [snapshot])

  return (
    <Page>
      <Link to={`/projects/${projectId}`} className="mb-3 inline-flex items-center gap-1.5 text-sm text-muted hover:text-fg">
        <ArrowLeft size={15} /> Panoya dön
      </Link>

      <div className="mb-6 flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">{project?.name ?? '…'} · Zaman Makinesi</h1>
        <input
          type="datetime-local"
          value={cutoffInput}
          max={toLocalInputValue(new Date())}
          onChange={(e) => setCutoffInput(e.target.value)}
          className={cn(inputClass, 'ml-auto w-auto')}
        />
      </div>

      <div className="mb-6 rounded-xl border border-dashed border-border bg-surface-2 px-4 py-3 text-sm text-muted">
        Seçilen andaki durum, sprint, story point, atanan kişi ve bitiş tarihi görev tarihçesinden yeniden
        kurulur. Etiketler, bağımlılıklar ve görev başlığı bu görünümde <b>güncel</b> değerleriyle gösterilir
        (geçmiş tarihçeleri henüz tutulmuyor).
      </div>

      {error ? (
        <EmptyState icon={<History size={22} />} title="Kesit yüklenemedi" text={errorMessage(error)} />
      ) : isLoading || !snapshot ? (
        <div className="grid gap-4 md:grid-cols-4">
          {STATUSES.map((s) => (
            <Skeleton key={s} className="h-40" />
          ))}
        </div>
      ) : snapshot.length === 0 ? (
        <EmptyState icon={<History size={22} />} title="Bu anda hiç görev yok" text="Seçilen tarihten önce oluşturulmuş görev bulunamadı." />
      ) : (
        <div className="grid gap-4 md:grid-cols-4">
          {STATUSES.map((status) => {
            const rows = byStatus.get(status) ?? []
            const meta = STATUS_META[status]
            return (
              <div key={status} className="rounded-2xl border border-border bg-surface p-3">
                <div className={cn('mb-3 flex items-center gap-1.5 text-sm font-semibold', meta.text)}>
                  <StatusDot status={status} /> {meta.label} <span className="text-muted">· {rows.length}</span>
                </div>
                <ul className="space-y-2">
                  {rows.map((row) => {
                    const assignee = row.assigneeId ? memberMap.get(row.assigneeId) : undefined
                    return (
                      <li key={row.id} className="rounded-xl border border-border bg-surface-2 p-2.5 text-sm">
                        <p className="truncate">
                          <span className="font-mono text-xs text-muted">{taskKey(project?.key, row.taskNumber)}</span> {row.title}
                        </p>
                        <div className="mt-1.5 flex items-center justify-between gap-2 text-xs text-muted">
                          <span className="truncate">
                            {sprintName(row.sprintId) ?? 'Backlog'}
                            {row.storyPoint != null && ` · ${row.storyPoint} sp`}
                          </span>
                          {assignee && <Avatar name={assignee.fullName} seed={assignee.userId} size={16} className="shrink-0" />}
                        </div>
                      </li>
                    )
                  })}
                  {rows.length === 0 && <li className="py-2 text-center text-xs text-muted">Boş.</li>}
                </ul>
              </div>
            )
          })}
        </div>
      )}
    </Page>
  )
}
