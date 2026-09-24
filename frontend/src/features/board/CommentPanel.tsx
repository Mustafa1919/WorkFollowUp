import { useRef, useState } from 'react'
import * as DM from '@radix-ui/react-dropdown-menu'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { AtSign, Pencil, Send, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { useCommentActions, useComments, useMemberMap, useMembers } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { fmt } from '@/lib/dates'
import { cn } from '@/lib/cn'
import type { Task, WorkspaceMember } from '@/lib/types'
import { useCurrentUserId } from '@/stores/session'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { inputClass } from '@/components/ui/Input'

const MAX_BODY = 10000
const MENTION_TOKEN = /@\[([0-9a-fA-F-]{36})\]/g

/**
 * `@[userId]` token'larini GORUNUM icin isme cevirir (kalin metin) — asil govde (backend'e giden
 * ve duzenlerken geri yuklenen deger) degismez, bu yalniz react-markdown'a verilen ARA metindir.
 * Backend, sunucu tarafinda AYNI regex ile (MentionParser) token'lari ayristirir.
 */
function withMentionNames(body: string, memberMap: Map<string, WorkspaceMember>): string {
  return body.replace(MENTION_TOKEN, (_match, id: string) => `**@${memberMap.get(id)?.fullName ?? 'Eski üye'}**`)
}

/** Gorev detayindaki yorum paneli: liste + Markdown yazma + `@` ile mention (ADR-0009). */
export function CommentPanel({ task, canWrite }: { task: Task; canWrite: boolean }) {
  const { data, fetchNextPage, hasNextPage, isFetchingNextPage, isLoading } = useComments(task.id)
  const { data: members } = useMembers(true)
  const memberMap = useMemberMap()
  const me = useCurrentUserId()
  const { add, edit, remove } = useCommentActions(task.id, task.projectId)
  const [draft, setDraft] = useState('')
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editDraft, setEditDraft] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const onError = (err: unknown) => toast.error(errorMessage(err))

  const comments = data?.pages.flatMap((p) => p.data) ?? []

  function insertMention(userId: string) {
    const token = `@[${userId}] `
    const el = textareaRef.current
    if (!el) {
      setDraft((d) => d + token)
      return
    }
    const start = el.selectionStart ?? draft.length
    const end = el.selectionEnd ?? draft.length
    const next = draft.slice(0, start) + token + draft.slice(end)
    setDraft(next)
    requestAnimationFrame(() => {
      el.focus()
      el.setSelectionRange(start + token.length, start + token.length)
    })
  }

  function submit() {
    const body = draft.trim()
    if (body === '') return
    add.mutate(body, { onError, onSuccess: () => setDraft('') })
  }

  return (
    <div>
      <div className="mb-2 text-xs font-medium text-muted">
        Yorumlar{task.commentCount > 0 && ` (${task.commentCount})`}
      </div>

      {hasNextPage && (
        <button
          onClick={() => fetchNextPage()}
          disabled={isFetchingNextPage}
          className="mb-2 cursor-pointer text-xs text-muted hover:text-fg disabled:cursor-default"
        >
          {isFetchingNextPage ? 'Yükleniyor…' : 'Önceki yorumları yükle'}
        </button>
      )}

      {isLoading ? (
        <div className="h-10 animate-pulse rounded-xl bg-surface-2" />
      ) : comments.length === 0 ? (
        <p className="text-sm text-muted">Henüz yorum yok.</p>
      ) : (
        <ul className="space-y-2">
          {comments.map((c) => {
            const author = memberMap.get(c.authorId)
            const editing = editingId === c.id
            const mine = c.authorId === me
            return (
              <li key={c.id} className="rounded-xl border border-border bg-surface-2/40 px-3 py-2">
                <div className="flex items-center gap-2 text-xs text-muted">
                  <Avatar name={author?.fullName ?? 'Eski üye'} seed={c.authorId} size={18} />
                  <span className="font-medium text-fg">{author?.fullName ?? 'Eski üye'}</span>
                  <span>{fmt(c.createdAt, 'd MMM yyyy HH:mm')}</span>
                  {c.edited && <span>(düzenlendi)</span>}
                  {!c.deleted && mine && !editing && (
                    <button
                      onClick={() => {
                        setEditingId(c.id)
                        setEditDraft(c.body)
                      }}
                      className="ml-auto cursor-pointer text-muted hover:text-fg"
                      aria-label="Yorumu düzenle"
                    >
                      <Pencil size={12} />
                    </button>
                  )}
                  {!c.deleted && mine && (
                    <button
                      onClick={() => remove.mutate(c.id, { onError })}
                      className={cn('cursor-pointer text-muted hover:text-danger', mine ? '' : 'ml-auto')}
                      aria-label="Yorumu sil"
                    >
                      <Trash2 size={12} />
                    </button>
                  )}
                </div>
                {editing ? (
                  <div className="mt-2 space-y-2">
                    <textarea
                      autoFocus
                      value={editDraft}
                      maxLength={MAX_BODY}
                      onChange={(e) => setEditDraft(e.target.value)}
                      rows={3}
                      className={cn(inputClass, 'h-auto resize-y py-2 text-sm')}
                    />
                    <div className="flex justify-end gap-2">
                      <Button variant="ghost" size="sm" onClick={() => setEditingId(null)}>
                        Vazgeç
                      </Button>
                      <Button
                        size="sm"
                        loading={edit.isPending}
                        onClick={() =>
                          edit.mutate(
                            { id: c.id, body: editDraft },
                            { onError, onSuccess: () => setEditingId(null) },
                          )
                        }
                      >
                        Kaydet
                      </Button>
                    </div>
                  </div>
                ) : (
                  <div className="markdown mt-1 text-sm">
                    <Markdown
                      remarkPlugins={[remarkGfm]}
                      components={{
                        a: ({ node: _node, ...props }) => <a {...props} target="_blank" rel="noopener noreferrer" />,
                      }}
                    >
                      {withMentionNames(c.body, memberMap)}
                    </Markdown>
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}

      {canWrite && (
        <div className="mt-3 space-y-2">
          <textarea
            ref={textareaRef}
            value={draft}
            maxLength={MAX_BODY}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) submit()
            }}
            rows={3}
            placeholder="Yorum yaz… (Ctrl+Enter gönderir)"
            className={cn(inputClass, 'h-auto resize-y py-2 text-sm')}
          />
          <div className="flex items-center justify-between">
            <DM.Root>
              <DM.Trigger className="inline-flex cursor-pointer items-center gap-1 rounded-full border border-dashed border-border px-2 py-0.5 text-xs text-muted transition-colors outline-none hover:border-accent/40 hover:text-fg">
                <AtSign size={11} /> Etiketle
              </DM.Trigger>
              <DM.Portal>
                <DM.Content
                  align="start"
                  sideOffset={6}
                  className="z-50 max-h-72 w-56 overflow-y-auto rounded-xl border border-border bg-surface p-1.5 shadow-xl"
                >
                  {(members ?? []).length === 0 ? (
                    <div className="px-2 py-2 text-xs text-muted">Uye yok.</div>
                  ) : (
                    (members ?? []).map((m) => (
                      <DM.Item
                        key={m.userId}
                        onSelect={() => insertMention(m.userId)}
                        className="flex cursor-pointer items-center gap-2 truncate rounded-lg px-2 py-1.5 text-sm outline-none data-[highlighted]:bg-surface-2"
                      >
                        <Avatar name={m.fullName} seed={m.userId} size={18} />
                        <span className="truncate">{m.fullName}</span>
                      </DM.Item>
                    ))
                  )}
                </DM.Content>
              </DM.Portal>
            </DM.Root>
            <Button size="sm" loading={add.isPending} onClick={submit} disabled={draft.trim() === ''}>
              <Send size={13} /> Gönder
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
