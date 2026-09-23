import { useState } from 'react'
import { ExternalLink, Pencil, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { useMeetingActions } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { fmt } from '@/lib/dates'
import type { Meeting, MeetingOccurrence } from '@/lib/types'
import { Dialog } from '@/components/ui/Dialog'
import { Button } from '@/components/ui/Button'

interface Props {
  occurrence: MeetingOccurrence | null
  /** Occurrence'ın ait olduğu seri — düzenle/sil bunu hedefler (v1: tek occurrence değiştirilemez). */
  meeting: Meeting | undefined
  canManage: boolean
  onOpenChange: (open: boolean) => void
  onEdit: (meeting: Meeting) => void
}

/** Salt-okunur occurrence detayı + (yetkiliyse) seriyi düzenle/sil. */
export function MeetingDetailDialog({ occurrence, meeting, canManage, onOpenChange, onEdit }: Props) {
  const { remove } = useMeetingActions()
  const [confirmDelete, setConfirmDelete] = useState(false)

  return (
    <Dialog
      open={!!occurrence}
      onOpenChange={(o) => {
        if (!o) setConfirmDelete(false)
        onOpenChange(o)
      }}
      title={occurrence?.title ?? ''}
      description={occurrence ? fmt(occurrence.date, 'd MMMM yyyy, EEEE') : undefined}
    >
      {occurrence && (
        <div className="space-y-4">
          <div className="text-sm text-muted">
            {occurrence.startTime.slice(0, 5)} · {occurrence.durationMinutes} dakika
          </div>

          {occurrence.meetingUrl && (
            <a
              href={occurrence.meetingUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1.5 rounded-xl bg-accent px-3 py-2 text-sm font-medium text-accent-fg transition-all hover:brightness-110"
            >
              <ExternalLink size={15} /> Katıl
            </a>
          )}

          {canManage && meeting && (
            <div className="flex items-center gap-2 border-t border-border pt-4">
              <Button type="button" variant="outline" size="sm" onClick={() => onEdit(meeting)}>
                <Pencil size={14} /> Seriyi düzenle
              </Button>
              {confirmDelete ? (
                <div className="ml-auto flex items-center gap-2 text-xs">
                  <span className="text-danger">Tüm seri silinsin mi?</span>
                  <Button size="sm" variant="ghost" onClick={() => setConfirmDelete(false)}>
                    Vazgeç
                  </Button>
                  <Button
                    size="sm"
                    variant="danger"
                    loading={remove.isPending}
                    onClick={() =>
                      remove.mutate(meeting.id, {
                        onError: (err) => toast.error(errorMessage(err)),
                        onSuccess: () => {
                          toast.success('Toplantı silindi')
                          onOpenChange(false)
                        },
                      })
                    }
                  >
                    Sil
                  </Button>
                </div>
              ) : (
                <Button type="button" size="sm" variant="ghost" className="ml-auto text-danger" onClick={() => setConfirmDelete(true)}>
                  <Trash2 size={14} /> Seriyi sil
                </Button>
              )}
            </div>
          )}
        </div>
      )}
    </Dialog>
  )
}
