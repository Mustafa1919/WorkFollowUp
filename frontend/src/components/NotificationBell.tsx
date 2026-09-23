import * as DM from '@radix-ui/react-dropdown-menu'
import { useNavigate } from 'react-router-dom'
import { Bell, CheckCheck, Inbox } from 'lucide-react'
import { useNotificationActions, useNotifications, useUnreadCount } from '@/api/queries'
import { fromNow } from '@/lib/dates'
import { cn } from '@/lib/cn'
import type { Notification } from '@/lib/types'

/**
 * Çan ikonu + okunmamış rozeti + Radix dropdown liste. `@radix-ui/react-popover` yerine BİLEREK
 * zaten kurulu `@radix-ui/react-dropdown-menu` kullanıldı (WorkspaceSwitcher/UserMenu/TagPicker ile
 * AYNI birincil öge — "aç, bir öğe seç, kapan" listesi için): yeni bir bağımlılık eklemeden aynı
 * erişilebilirlik/konumlandırma davranışını verir. Satıra tıklamak TEK eylemdir (okundu işaretle +
 * varsa proje board'una git) — ayrı bir "okundu işaretle" düğmesi DM.Item içine iç içe interaktif
 * öğe koymayı gerektirirdi (tıklama/klavye davranışı belirsizleşir).
 */
export function NotificationBell() {
  const { data: unreadCount } = useUnreadCount()
  const { data, fetchNextPage, hasNextPage, isFetchingNextPage } = useNotifications(false)
  const { markRead, markAllRead } = useNotificationActions()
  const navigate = useNavigate()

  const notifications = data?.pages.flatMap((p) => p.data) ?? []
  const badge = unreadCount && unreadCount > 0 ? (unreadCount > 99 ? '99+' : String(unreadCount)) : null

  function openNotification(n: Notification) {
    if (!n.read) markRead.mutate(n.id)
    if (n.projectId) navigate(`/projects/${n.projectId}`)
  }

  return (
    <DM.Root>
      <DM.Trigger
        className="relative grid h-9 w-9 cursor-pointer place-items-center rounded-xl text-muted transition-colors outline-none hover:bg-surface-2 hover:text-fg"
        aria-label="Bildirimler"
      >
        <Bell size={18} />
        {badge && (
          <span className="absolute top-1 right-1 grid h-4 min-w-4 place-items-center rounded-full bg-accent px-1 text-[10px] font-semibold text-accent-fg">
            {badge}
          </span>
        )}
      </DM.Trigger>
      <DM.Portal>
        <DM.Content
          align="end"
          sideOffset={8}
          className="z-50 flex max-h-[28rem] w-80 flex-col overflow-hidden rounded-xl border border-border bg-surface shadow-xl"
        >
          <div className="flex items-center justify-between border-b border-border px-3 py-2">
            <span className="text-sm font-medium">Bildirimler</span>
            {badge && (
              <button
                onClick={(e) => {
                  e.preventDefault()
                  markAllRead.mutate()
                }}
                className="flex cursor-pointer items-center gap-1 rounded-md px-1.5 py-1 text-xs text-muted outline-none hover:bg-surface-2 hover:text-fg"
              >
                <CheckCheck size={13} /> Tümünü okundu işaretle
              </button>
            )}
          </div>

          <div className="flex-1 overflow-y-auto">
            {notifications.length === 0 ? (
              <div className="flex flex-col items-center gap-2 px-4 py-10 text-center text-muted">
                <Inbox size={22} />
                <p className="text-xs">Henüz bildirim yok.</p>
              </div>
            ) : (
              notifications.map((n) => (
                <DM.Item
                  key={n.id}
                  onSelect={() => openNotification(n)}
                  className={cn(
                    'flex cursor-pointer flex-col gap-0.5 border-b border-border px-3 py-2.5 text-sm outline-none last:border-b-0 data-[highlighted]:bg-surface-2',
                    !n.read && 'bg-accent-soft/40',
                  )}
                >
                  <div className="flex items-start gap-2">
                    {!n.read && <span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-accent" />}
                    <span className={cn('flex-1 truncate font-medium', n.read && 'ml-3.5 text-muted')}>{n.title}</span>
                  </div>
                  <p className="ml-3.5 truncate text-xs text-muted">{n.body}</p>
                  <span className="ml-3.5 text-[11px] text-muted">{fromNow(n.createdAt)}</span>
                </DM.Item>
              ))
            )}
            {hasNextPage && (
              <button
                onClick={(e) => {
                  e.preventDefault()
                  fetchNextPage()
                }}
                disabled={isFetchingNextPage}
                className="w-full cursor-pointer py-2 text-center text-xs text-muted outline-none hover:bg-surface-2 hover:text-fg disabled:cursor-default"
              >
                {isFetchingNextPage ? 'Yükleniyor…' : 'Daha fazla yükle'}
              </button>
            )}
          </div>
        </DM.Content>
      </DM.Portal>
    </DM.Root>
  )
}
