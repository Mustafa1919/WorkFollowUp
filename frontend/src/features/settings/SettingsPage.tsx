import { useState, type FormEvent, type ReactNode } from 'react'
import { motion } from 'motion/react'
import { Bell, Copy, GitBranch, Lock, Mail, Monitor, Moon, Pencil, RefreshCw, Sun, Tag as TagIcon, Trash2, Users, X } from 'lucide-react'
import { toast } from 'sonner'
import {
  useSlack,
  useSlackActions,
  useWebhookActions,
  useWebhooks,
  useCurrentRole,
  useTags,
  useTagActions,
  useMembers,
  useMemberActions,
  useNotificationPreferences,
  useNotificationPreferencesActions,
} from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { cn } from '@/lib/cn'
import { fmt } from '@/lib/dates'
import type { Tag, WorkspaceMember, WorkspaceRole } from '@/lib/types'
import { useTheme } from '@/stores/theme'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Page } from '@/components/ui/misc'

const ROLE_LABEL: Record<WorkspaceRole, string> = {
  WORKSPACE_ADMIN: 'Yönetici',
  MANAGER: 'Yönetmen',
  DEVELOPER: 'Geliştirici',
  VIEWER: 'İzleyici',
}

export function SettingsPage() {
  const role = useCurrentRole()
  const isAdmin = role === 'WORKSPACE_ADMIN'
  const canManageTags = isAdmin || role === 'MANAGER'
  return (
    <Page className="max-w-3xl">
      <h1 className="mb-8 text-2xl font-semibold tracking-tight">Ayarlar</h1>
      <div className="space-y-6">
        <Section title="Görünüm" text="Tercihin bu tarayıcıda saklanır.">
          <ThemePicker />
        </Section>
        <Section title="Üyeler" text="Workspace'e önceden kayıtlı bir kullanıcıyı e-posta ile ekle." icon={<Users size={18} />}>
          <MemberSettings isAdmin={isAdmin} />
        </Section>
        <Section title="Bildirim tercihleri" text="Hangi olaylar için e-posta almak istediğini seç." icon={<Mail size={18} />}>
          <NotificationPreferencesSettings />
        </Section>
        {canManageTags && (
          <Section title="Etiketler" text="Görevleri sınıflandırmak için workspace genelinde etiketler." icon={<TagIcon size={18} />}>
            <TagSettings />
          </Section>
        )}
        {isAdmin ? (
          <>
            <Section title="Slack bildirimleri" text="Görev oluşturma ve durum değişiklikleri bir Slack kanalına gönderilir." icon={<Bell size={18} />}>
              <SlackSettings />
            </Section>
            <Section
              title="GitHub webhook"
              text="Commit ve PR'lardaki görev anahtarları (ör. WEB-12) görevin durumunu ileri taşır."
              icon={<GitBranch size={18} />}
            >
              <WebhookSettings />
            </Section>
          </>
        ) : (
          <div className="flex items-center gap-3 rounded-2xl border border-dashed border-border p-5 text-sm text-muted">
            <Lock size={16} /> Entegrasyon ayarları yalnızca workspace yöneticilerine (ADMIN) açıktır.
          </div>
        )}
      </div>
    </Page>
  )
}

function Section({ title, text, icon, children }: { title: string; text: string; icon?: ReactNode; children: ReactNode }) {
  return (
    <motion.section
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      className="rounded-2xl border border-border bg-surface p-6"
    >
      <div className="mb-5 flex items-start gap-3">
        {icon && <div className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-accent-soft text-accent">{icon}</div>}
        <div>
          <h2 className="font-semibold">{title}</h2>
          <p className="text-sm text-muted">{text}</p>
        </div>
      </div>
      {children}
    </motion.section>
  )
}

function ThemePicker() {
  const { theme, setTheme } = useTheme()
  const options = [
    { id: 'light' as const, label: 'Açık', icon: Sun },
    { id: 'dark' as const, label: 'Koyu', icon: Moon },
  ]
  const system = () => setTheme(matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
  return (
    <div className="grid grid-cols-3 gap-3">
      {options.map((o) => (
        <button
          key={o.id}
          onClick={() => setTheme(o.id)}
          className={cn(
            'flex cursor-pointer flex-col items-center gap-2 rounded-xl border p-4 text-sm transition-all',
            theme === o.id ? 'border-accent bg-accent-soft text-accent' : 'border-border text-muted hover:border-accent/40 hover:text-fg',
          )}
        >
          <o.icon size={20} /> {o.label}
        </button>
      ))}
      <button
        onClick={system}
        className="flex cursor-pointer flex-col items-center gap-2 rounded-xl border border-border p-4 text-sm text-muted transition-all hover:border-accent/40 hover:text-fg"
      >
        <Monitor size={20} /> Sistem
      </button>
    </div>
  )
}

const ROLE_OPTIONS: WorkspaceRole[] = ['WORKSPACE_ADMIN', 'MANAGER', 'DEVELOPER', 'VIEWER']

function MemberSettings({ isAdmin }: { isAdmin: boolean }) {
  const { data: members } = useMembers(true)
  const { add, changeRole, remove } = useMemberActions()
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<WorkspaceRole>('DEVELOPER')
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null)
  const onError = (err: unknown) => toast.error(errorMessage(err))

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      await add.mutateAsync({ email, role })
      setEmail('')
      setRole('DEVELOPER')
      toast.success('Üye eklendi')
    } catch (err) {
      onError(err)
    }
  }

  return (
    <div className="space-y-3">
      {members?.map((m: WorkspaceMember) => (
        <div key={m.userId} className="flex items-center gap-3 rounded-xl bg-surface-2 p-3 text-sm">
          <div className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-accent-soft text-xs font-semibold text-accent uppercase">
            {m.fullName.charAt(0) || m.email.charAt(0)}
          </div>
          <div className="min-w-0 flex-1">
            <div className="truncate font-medium">{m.fullName || m.email}</div>
            <div className="truncate text-xs text-muted">{m.email}</div>
          </div>
          {isAdmin ? (
            <>
              <select
                value={m.role}
                onChange={(e) => changeRole.mutate({ userId: m.userId, role: e.target.value }, { onError })}
                className="h-8 cursor-pointer rounded-lg border border-border bg-surface px-2 text-xs outline-none focus:border-accent"
              >
                {ROLE_OPTIONS.map((r) => (
                  <option key={r} value={r}>
                    {ROLE_LABEL[r]}
                  </option>
                ))}
              </select>
              {confirmRemove === m.userId ? (
                <div className="flex items-center gap-1">
                  <Button
                    size="sm"
                    variant="danger"
                    loading={remove.isPending}
                    onClick={() => remove.mutate(m.userId, { onError, onSuccess: () => setConfirmRemove(null) })}
                  >
                    Emin misin?
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => setConfirmRemove(null)}>
                    <X size={14} />
                  </Button>
                </div>
              ) : (
                <Button size="sm" variant="ghost" title="Kaldır" onClick={() => setConfirmRemove(m.userId)}>
                  <Trash2 size={14} />
                </Button>
              )}
            </>
          ) : (
            <span className="text-xs text-muted">{ROLE_LABEL[m.role]}</span>
          )}
        </div>
      ))}
      {isAdmin && (
        <form onSubmit={submit} className="flex flex-col gap-2 sm:flex-row">
          <Input
            type="email"
            required
            placeholder="kullanici@sirket.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <select
            value={role}
            onChange={(e) => setRole(e.target.value as WorkspaceRole)}
            className="h-10 cursor-pointer rounded-xl border border-border bg-surface px-3 text-sm outline-none focus:border-accent"
          >
            {ROLE_OPTIONS.map((r) => (
              <option key={r} value={r}>
                {ROLE_LABEL[r]}
              </option>
            ))}
          </select>
          <Button type="submit" loading={add.isPending} className="shrink-0">
            Ekle
          </Button>
        </form>
      )}
      <p className="text-xs text-muted">
        Kullanıcı önceden <code className="rounded bg-surface-2 px-1">/register</code> ile kendi hesabını açmış olmalı — davet e-postası
        gönderilmiyor.
      </p>
    </div>
  )
}

function NotificationPreferencesSettings() {
  const { data, isLoading } = useNotificationPreferences()
  const { update } = useNotificationPreferencesActions()
  const onError = (err: unknown) => toast.error(errorMessage(err))

  function toggle(field: 'emailOnAssign' | 'emailOnMention') {
    if (!data) return
    update.mutate({ ...data, [field]: !data[field] }, { onError })
  }

  if (isLoading || !data) return null
  return (
    <div className="space-y-3">
      <Toggle
        label="Bir görev bana atandığında e-posta gönder"
        checked={data.emailOnAssign}
        onChange={() => toggle('emailOnAssign')}
      />
      <Toggle
        label="Bir yorumda etiketlendiğimde e-posta gönder"
        checked={data.emailOnMention}
        onChange={() => toggle('emailOnMention')}
      />
      <p className="text-xs text-muted">
        Doğrulama, parola sıfırlama ve güvenlik uyarısı e-postaları bu tercihlerden bağımsız her zaman gönderilir.
      </p>
    </div>
  )
}

function Toggle({ label, checked, onChange }: { label: string; checked: boolean; onChange: () => void }) {
  return (
    <button
      type="button"
      onClick={onChange}
      className="flex w-full cursor-pointer items-center justify-between gap-3 rounded-xl bg-surface-2 p-3 text-left text-sm"
    >
      <span>{label}</span>
      <span
        className={cn(
          'relative h-6 w-11 shrink-0 rounded-full transition-colors',
          checked ? 'bg-accent' : 'bg-border',
        )}
      >
        <span
          className={cn(
            'absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform',
            checked ? 'translate-x-5' : 'translate-x-0.5',
          )}
        />
      </span>
    </button>
  )
}

const DEFAULT_TAG_COLOR = '#6366f1'

function TagSettings() {
  const { data: tags } = useTags()
  const { create, update, remove } = useTagActions()
  const [name, setName] = useState('')
  const [color, setColor] = useState(DEFAULT_TAG_COLOR)
  const [editing, setEditing] = useState<string | null>(null)
  const onError = (err: unknown) => toast.error(errorMessage(err))

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      await create.mutateAsync({ name, color })
      setName('')
      setColor(DEFAULT_TAG_COLOR)
    } catch (err) {
      onError(err)
    }
  }

  return (
    <div className="space-y-3">
      {tags?.map((tag) =>
        editing === tag.id ? (
          <EditTagRow
            key={tag.id}
            tag={tag}
            onCancel={() => setEditing(null)}
            onSave={(next) =>
              update.mutate(
                { id: tag.id, ...next },
                { onError, onSuccess: () => setEditing(null) },
              )
            }
            saving={update.isPending}
          />
        ) : (
          <div key={tag.id} className="flex items-center gap-3 rounded-xl bg-surface-2 p-3 text-sm">
            <span className="h-3 w-3 shrink-0 rounded-full" style={{ backgroundColor: tag.color }} />
            <span className="flex-1 truncate">{tag.name}</span>
            <Button size="sm" variant="ghost" title="Düzenle" onClick={() => setEditing(tag.id)}>
              <Pencil size={14} />
            </Button>
            <Button size="sm" variant="ghost" title="Sil" onClick={() => remove.mutate(tag.id, { onError })}>
              <Trash2 size={14} />
            </Button>
          </div>
        ),
      )}
      {tags?.length === 0 && <p className="text-xs text-muted">Henüz etiket yok.</p>}
      <form onSubmit={submit} className="flex items-center gap-2">
        <input
          type="color"
          value={color}
          onChange={(e) => setColor(e.target.value)}
          className="h-10 w-10 shrink-0 cursor-pointer rounded-lg border border-border bg-surface p-1"
          aria-label="Etiket rengi"
        />
        <Input required maxLength={40} placeholder="Yeni etiket adı" value={name} onChange={(e) => setName(e.target.value)} />
        <Button type="submit" loading={create.isPending} className="shrink-0">
          Ekle
        </Button>
      </form>
    </div>
  )
}

function EditTagRow({
  tag,
  onSave,
  onCancel,
  saving,
}: {
  tag: Tag
  onSave: (next: { name: string; color: string }) => void
  onCancel: () => void
  saving: boolean
}) {
  const [name, setName] = useState(tag.name)
  const [color, setColor] = useState(tag.color)
  return (
    <div className="flex items-center gap-2 rounded-xl border border-accent/40 bg-surface-2 p-3 text-sm">
      <input
        type="color"
        value={color}
        onChange={(e) => setColor(e.target.value)}
        className="h-8 w-8 shrink-0 cursor-pointer rounded-lg border border-border bg-surface p-0.5"
        aria-label="Etiket rengi"
      />
      <Input value={name} onChange={(e) => setName(e.target.value)} className="h-8" />
      <Button size="sm" loading={saving} onClick={() => onSave({ name, color })}>
        Kaydet
      </Button>
      <Button size="sm" variant="ghost" onClick={onCancel}>
        <X size={14} />
      </Button>
    </div>
  )
}

function SlackSettings() {
  const { data, isLoading } = useSlack(true)
  const { save, remove } = useSlackActions()
  const [url, setUrl] = useState('')

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      await save.mutateAsync({ webhookUrl: url, enabled: true })
      setUrl('')
      toast.success('Slack bağlandı')
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  if (isLoading) return null
  return (
    <div className="space-y-4">
      {data && (
        <div className="flex items-center gap-3 rounded-xl bg-surface-2 p-3 text-sm">
          <span className={cn('h-2 w-2 rounded-full', data.enabled ? 'bg-st-done' : 'bg-st-todo')} />
          <span className="flex-1">
            {data.enabled ? 'Bağlı ve aktif' : 'Bağlı, devre dışı'} · güncellendi {fmt(data.updatedAt, 'd MMM yyyy HH:mm')}
          </span>
          <Button size="sm" variant="ghost" onClick={() => remove.mutate(undefined, { onError: (e) => toast.error(errorMessage(e)) })}>
            <Trash2 size={14} /> Kaldır
          </Button>
        </div>
      )}
      <form onSubmit={submit} className="flex flex-col gap-2 sm:flex-row">
        <Input
          type="url"
          required
          placeholder="https://hooks.slack.com/services/…"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
        />
        <Button type="submit" loading={save.isPending} className="shrink-0">
          {data ? 'Adresi değiştir' : 'Bağla'}
        </Button>
      </form>
      <p className="text-xs text-muted">Güvenlik gereği kayıtlı adres hiçbir zaman geri gösterilmez.</p>
    </div>
  )
}

function WebhookSettings() {
  const { data } = useWebhooks(true)
  const { create, rotate, remove } = useWebhookActions()
  const [secret, setSecret] = useState<{ path: string; secret: string } | null>(null)
  const onError = (e: unknown) => toast.error(errorMessage(e))

  const reveal = (r: { integration: { webhookPath: string }; secret: string }) =>
    setSecret({ path: r.integration.webhookPath, secret: r.secret })

  return (
    <div className="space-y-3">
      {secret && (
        <motion.div initial={{ opacity: 0, scale: 0.98 }} animate={{ opacity: 1, scale: 1 }} className="rounded-xl border border-st-review/40 bg-st-review/10 p-4 text-sm">
          <p className="mb-2 font-medium">Bu secret yalnızca şimdi gösteriliyor — GitHub'a kaydet.</p>
          <CopyRow label="Payload URL" value={`${location.origin}${secret.path}`} />
          <CopyRow label="Secret" value={secret.secret} />
          <Button size="sm" variant="ghost" className="mt-2" onClick={() => setSecret(null)}>
            Kaydettim, gizle
          </Button>
        </motion.div>
      )}
      {data?.map((w) => (
        <div key={w.id} className="flex items-center gap-3 rounded-xl bg-surface-2 p-3 text-sm">
          <GitBranch size={16} className="text-muted" />
          <div className="min-w-0 flex-1">
            <div className="truncate font-mono text-xs">{w.webhookPath}</div>
            <div className="text-xs text-muted">
              secret v{w.secretVersion} · {fmt(w.createdAt, 'd MMM yyyy')}
            </div>
          </div>
          <Button size="sm" variant="ghost" title="Secret'ı yenile" onClick={() => rotate.mutate(w.id, { onSuccess: reveal, onError })}>
            <RefreshCw size={14} />
          </Button>
          <Button size="sm" variant="ghost" title="Sil" onClick={() => remove.mutate(w.id, { onError })}>
            <Trash2 size={14} />
          </Button>
        </div>
      ))}
      <Button variant="outline" size="sm" loading={create.isPending} onClick={() => create.mutate(undefined, { onSuccess: reveal, onError })}>
        <GitBranch size={14} /> Yeni GitHub webhook
      </Button>
    </div>
  )
}

function CopyRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="mb-1.5 flex items-center gap-2">
      <span className="w-24 shrink-0 text-xs text-muted">{label}</span>
      <code className="min-w-0 flex-1 truncate rounded-md bg-surface px-2 py-1 text-xs">{value}</code>
      <button
        className="cursor-pointer rounded-md p-1 text-muted hover:bg-surface hover:text-fg"
        onClick={() => navigator.clipboard.writeText(value).then(() => toast.success('Kopyalandı'))}
        aria-label={`${label} kopyala`}
      >
        <Copy size={13} />
      </button>
    </div>
  )
}
