import { History } from 'lucide-react'
import { useActivity, useMemberMap } from '@/api/queries'
import { fmt, fromNow } from '@/lib/dates'
import { STATUS_META } from '@/lib/status'
import type { Sprint, Task, TaskActivity, TaskStatus } from '@/lib/types'
import { Avatar } from '@/components/ui/Avatar'

/** `null`/`undefined` deger icin kisa gosterim — "atamayi kaldirdi" gibi cumlelerde kullanilir. */
function str(v: unknown): string | null {
  return v === null || v === undefined ? null : String(v)
}

function statusLabel(v: unknown): string {
  const s = str(v)
  return s && s in STATUS_META ? STATUS_META[s as TaskStatus].label : (s ?? '—')
}

function memberName(memberMap: Map<string, { fullName: string }>, id: unknown): string {
  const s = str(id)
  return s ? (memberMap.get(s)?.fullName ?? 'eski üye') : '—'
}

function sprintName(sprints: Sprint[], id: unknown): string {
  const s = str(id)
  return s ? (sprints.find((sp) => sp.id === s)?.name ?? 'silinmiş sprint') : 'backlog'
}

/** `dependency_changed` icin diger gorevi CURRENT `task.blocking`/`blockedBy`'dan cozmeye calisir. */
function otherTaskRef(task: Task, id: unknown): string {
  const s = str(id)
  if (!s) return '—'
  const ref = [...task.blocking, ...task.blockedBy].find((r) => r.id === s)
  return ref ? `"${ref.title}"` : 'bir görev'
}

/** Backend'in event tipi + alan sozlugunu insan-okunur Turkce cumleye cevirir (Dalga 1.5). */
function activityLine(
  entry: TaskActivity,
  actorName: string,
  memberMap: Map<string, { fullName: string }>,
  sprints: Sprint[],
  task: Task,
): string {
  const oldV = str(entry.oldValue)
  const newV = str(entry.newValue)
  switch (entry.eventType) {
    case 'status_changed':
      return `${actorName} durumu ${statusLabel(entry.oldValue)} → ${statusLabel(entry.newValue)} yaptı.`
    case 'assignee_changed':
      if (!oldV && newV) return `${actorName} görevi ${memberName(memberMap, newV)} kişisine atadı.`
      if (oldV && !newV) return `${actorName} atamayı kaldırdı (${memberName(memberMap, oldV)}).`
      return `${actorName} atamayı ${memberName(memberMap, oldV)} → ${memberName(memberMap, newV)} olarak değiştirdi.`
    case 'description_changed':
      return `${actorName} açıklamayı güncelledi.`
    case 'due_date_changed':
      if (!oldV && newV) return `${actorName} tarihi ${fmt(newV, 'd MMM yyyy')} olarak belirledi.`
      if (oldV && !newV) return `${actorName} tarihi kaldırdı.`
      return `${actorName} tarihi ${fmt(oldV!, 'd MMM yyyy')} → ${fmt(newV!, 'd MMM yyyy')} olarak değiştirdi.`
    case 'story_point_changed':
      return `${actorName} story point'i ${oldV ?? '—'} → ${newV ?? '—'} yaptı.`
    case 'sprint_changed':
      if (!oldV && newV) return `${actorName} görevi ${sprintName(sprints, newV)} sprintine ekledi.`
      if (oldV && !newV) return `${actorName} görevi backlog'a aldı.`
      return `${actorName} sprinti ${sprintName(sprints, oldV)} → ${sprintName(sprints, newV)} olarak değiştirdi.`
    case 'approval_changed':
      return newV === 'true' ? `${actorName} görevi onayladı.` : `${actorName} onayı geri aldı.`
    case 'deleted':
      return `${actorName} görevi sildi.`
    case 'comment_added':
      return `${actorName} bir yorum ekledi.`
    case 'tags_changed':
      return newV
        ? `${actorName} "${newV}" etiketini ekledi.`
        : `${actorName} "${oldV}" etiketini kaldırdı.`
    case 'dependency_changed':
      if (entry.field === 'blockedBy') {
        return newV
          ? `${actorName} bu görevi ${otherTaskRef(task, newV)} tarafından bloklanacak şekilde işaretledi.`
          : `${actorName} ${otherTaskRef(task, oldV)} blokunu kaldırdı.`
      }
      return newV
        ? `${actorName} bu görevin ${otherTaskRef(task, newV)} görevini bloklamasını sağladı.`
        : `${actorName} ${otherTaskRef(task, oldV)} üzerindeki blok bağlantısını kaldırdı.`
    default:
      return `${actorName} ${entry.field} alanını değiştirdi.`
  }
}

/** Gorev detayindaki Aktivite sekmesi: task_events'in insan-okunur akisi (Dalga 1.5). */
export function TaskActivityPanel({ task, sprints }: { task: Task; sprints: Sprint[] }) {
  const { data, fetchNextPage, hasNextPage, isFetchingNextPage, isLoading } = useActivity(task.id)
  const memberMap = useMemberMap()
  const entries = data?.pages.flatMap((p) => p.data) ?? []

  if (isLoading) {
    return <div className="h-10 animate-pulse rounded-xl bg-surface-2" />
  }
  if (entries.length === 0) {
    return <p className="text-sm text-muted">Henüz aktivite yok.</p>
  }

  return (
    <div>
      <ul className="space-y-2.5">
        {entries.map((e) => (
          <li key={e.id} className="flex items-start gap-2.5 text-sm">
            <Avatar name={e.actorName} seed={e.actorId} size={22} />
            <div className="min-w-0 flex-1">
              <p className="text-fg">{activityLine(e, e.actorName, memberMap, sprints, task)}</p>
              <p className="mt-0.5 flex items-center gap-1 text-xs text-muted">
                <History size={11} /> {fromNow(e.createdAt)}
              </p>
            </div>
          </li>
        ))}
      </ul>
      {hasNextPage && (
        <button
          onClick={() => fetchNextPage()}
          disabled={isFetchingNextPage}
          className="mt-2 cursor-pointer text-xs text-muted hover:text-fg disabled:cursor-default"
        >
          {isFetchingNextPage ? 'Yükleniyor…' : 'Daha eskileri yükle'}
        </button>
      )}
    </div>
  )
}
