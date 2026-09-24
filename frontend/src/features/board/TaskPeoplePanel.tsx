import { useState } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Bell, BellOff, Pencil, UserPlus } from 'lucide-react'
import { toast } from 'sonner'
import { useMemberMap, useMembers, useTaskCollaboration, useTaskDetail } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { cn } from '@/lib/cn'
import type { Task } from '@/lib/types'
import { useCurrentUserId } from '@/stores/session'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { inputClass } from '@/components/ui/Input'

const MAX_DESCRIPTION = 20000

const onError = (err: unknown) => toast.error(errorMessage(err))

/** Atanan kisi: VIEWER atanamaz (backend kurali), bu yuzden listede gosterilmez. */
export function AssigneeField({ task, canWrite }: { task: Task; canWrite: boolean }) {
  const { data: members } = useMembers(true)
  const memberMap = useMemberMap()
  const me = useCurrentUserId()
  const { assign } = useTaskCollaboration(task.projectId)
  const assignable = (members ?? []).filter((m) => m.role !== 'VIEWER')
  const current = task.assigneeId ? memberMap.get(task.assigneeId) : undefined

  const change = (assigneeId: string | null) => assign.mutate({ taskId: task.id, assigneeId }, { onError })

  return (
    <div>
      <div className="mb-2 text-xs font-medium text-muted">Atanan</div>
      <div className="flex items-center gap-2">
        {current && <Avatar name={current.fullName} seed={current.userId} size={28} />}
        <select
          aria-label="Atanan kişi"
          disabled={!canWrite || assign.isPending}
          value={task.assigneeId ?? ''}
          onChange={(e) => change(e.target.value || null)}
          className={cn(inputClass, 'cursor-pointer')}
        >
          <option value="">Atanmamış</option>
          {assignable.map((m) => (
            <option key={m.userId} value={m.userId}>
              {m.fullName}
              {m.userId === me ? ' (ben)' : ''}
            </option>
          ))}
          {/* Uyeligi kalkmis bir atanan secenekte kaybolmasin. */}
          {task.assigneeId && !current && <option value={task.assigneeId}>Eski üye</option>}
        </select>
        {canWrite && me && task.assigneeId !== me && assignable.some((m) => m.userId === me) && (
          <Button variant="outline" size="sm" className="h-10 shrink-0" onClick={() => change(me)} loading={assign.isPending}>
            <UserPlus size={15} /> Bana ata
          </Button>
        )}
      </div>
    </div>
  )
}

/** Izle / izlemeyi birak — her uye (VIEWER dahil) kullanabilir; Inbox alicilari izleyicilerdir. */
export function WatchButton({ task }: { task: Task }) {
  const { data: detail } = useTaskDetail(task.id)
  const memberMap = useMemberMap()
  const { watch, unwatch } = useTaskCollaboration(task.projectId)
  if (!detail) return null
  const pending = watch.isPending || unwatch.isPending
  const names = detail.watcherIds.map((id) => memberMap.get(id)?.fullName ?? 'Eski üye').join(', ')
  return (
    <Button
      variant="outline"
      size="sm"
      loading={pending}
      title={names ? `İzleyenler: ${names}` : 'Henüz izleyen yok'}
      onClick={() =>
        detail.watching ? unwatch.mutate(task.id, { onError }) : watch.mutate(task.id, { onError })
      }
    >
      {detail.watching ? <BellOff size={15} /> : <Bell size={15} />}
      {detail.watching ? 'İzlemeyi bırak' : 'İzle'}
      <span className="rounded-full bg-surface-2 px-1.5 text-[10px] tabular-nums">{detail.watcherIds.length}</span>
    </Button>
  )
}

/**
 * Markdown aciklama. Gorunum modunda react-markdown ham HTML'i RENDER ETMEZ (varsayilan), bu yuzden
 * kullanici metni XSS yuzeyi olusturmaz; linkler yeni sekmede ve `noopener` ile acilir.
 */
export function DescriptionField({ task, canWrite }: { task: Task; canWrite: boolean }) {
  const { data: detail, isLoading } = useTaskDetail(task.id)
  const { updateDescription } = useTaskCollaboration(task.projectId)
  const [draft, setDraft] = useState<string | null>(null)
  const editing = draft !== null
  const description = detail?.description ?? ''

  function save() {
    if (draft === null) return
    updateDescription.mutate(
      { taskId: task.id, description: draft.trim() === '' ? null : draft },
      { onError, onSuccess: () => setDraft(null) },
    )
  }

  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs font-medium text-muted">Açıklama</span>
        {canWrite && !editing && (
          <button
            onClick={() => setDraft(description)}
            className="inline-flex cursor-pointer items-center gap-1 text-xs text-muted hover:text-fg"
          >
            <Pencil size={12} /> Düzenle
          </button>
        )}
      </div>
      {editing ? (
        <div className="space-y-2">
          <textarea
            autoFocus
            value={draft}
            maxLength={MAX_DESCRIPTION}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) save()
            }}
            rows={8}
            placeholder="Markdown desteklenir: **kalın**, - liste, [bağlantı](https://…), `kod`"
            className={cn(inputClass, 'h-auto min-h-40 resize-y py-2 font-mono text-xs leading-relaxed')}
          />
          <div className="flex items-center gap-2">
            <span className="mr-auto text-[11px] text-muted tabular-nums">
              {draft.length.toLocaleString('tr-TR')} / {MAX_DESCRIPTION.toLocaleString('tr-TR')} · Ctrl+Enter kaydeder
            </span>
            <Button variant="ghost" size="sm" onClick={() => setDraft(null)}>
              Vazgeç
            </Button>
            <Button size="sm" onClick={save} loading={updateDescription.isPending}>
              Kaydet
            </Button>
          </div>
        </div>
      ) : isLoading ? (
        <div className="h-10 animate-pulse rounded-xl bg-surface-2" />
      ) : description ? (
        <div className="markdown rounded-xl border border-border bg-surface-2/40 px-3 py-2 text-sm">
          <Markdown
            remarkPlugins={[remarkGfm]}
            components={{
              a: ({ node: _node, ...props }) => <a {...props} target="_blank" rel="noopener noreferrer" />,
            }}
          >
            {description}
          </Markdown>
        </div>
      ) : (
        <p className="text-sm text-muted">{canWrite ? 'Açıklama yok — "Düzenle" ile ekleyin.' : 'Açıklama yok.'}</p>
      )}
    </div>
  )
}
