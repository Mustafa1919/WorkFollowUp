import * as DM from '@radix-ui/react-dropdown-menu'
import { Check, Plus, Tag as TagIcon } from 'lucide-react'
import { toast } from 'sonner'
import { useTags, useTaskTagAssignment } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import type { Task } from '@/lib/types'

/**
 * Goreve etiket ekleme dropdown'i: workspace'in tum etiketleri checkbox olarak listelenir, secili
 * olanlar goreve atanmis demektir. Toggle her tiklamada TEK bir assign/unassign cagrisi yapar
 * (idempotent backend sayesinde art arda tiklama guvenli).
 */
export function TagPicker({ task }: { task: Task }) {
  const { data: tags } = useTags()
  const { assign, unassign } = useTaskTagAssignment(task.projectId)
  const assignedIds = new Set(task.tags.map((t) => t.id))
  const onError = (err: unknown) => toast.error(errorMessage(err))

  function toggle(tagId: string) {
    if (assignedIds.has(tagId)) unassign.mutate({ taskId: task.id, tagId }, { onError })
    else assign.mutate({ taskId: task.id, tagId }, { onError })
  }

  return (
    <DM.Root>
      <DM.Trigger className="inline-flex cursor-pointer items-center gap-1 rounded-full border border-dashed border-border px-2 py-0.5 text-xs text-muted transition-colors outline-none hover:border-accent/40 hover:text-fg">
        <Plus size={11} /> Etiket
      </DM.Trigger>
      <DM.Portal>
        <DM.Content align="start" sideOffset={6} className="z-50 max-h-72 w-56 overflow-y-auto rounded-xl border border-border bg-surface p-1.5 shadow-xl">
          {!tags || tags.length === 0 ? (
            <div className="flex items-center gap-2 px-2 py-2 text-xs text-muted">
              <TagIcon size={13} /> Henüz etiket yok — Ayarlar'dan ekleyebilirsin.
            </div>
          ) : (
            tags.map((tag) => (
              <DM.CheckboxItem
                key={tag.id}
                checked={assignedIds.has(tag.id)}
                onSelect={(e) => {
                  e.preventDefault() // dropdown'i kapatma, art arda secime izin ver
                  toggle(tag.id)
                }}
                className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-sm outline-none data-[highlighted]:bg-surface-2"
              >
                <span className="h-2 w-2 shrink-0 rounded-full" style={{ backgroundColor: tag.color }} />
                <span className="flex-1 truncate">{tag.name}</span>
                <DM.ItemIndicator>
                  <Check size={13} className="text-accent" />
                </DM.ItemIndicator>
              </DM.CheckboxItem>
            ))
          )}
        </DM.Content>
      </DM.Portal>
    </DM.Root>
  )
}
