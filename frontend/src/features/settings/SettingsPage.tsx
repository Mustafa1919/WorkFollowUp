import { useState, type FormEvent, type ReactNode } from 'react'
import { motion } from 'motion/react'
import { Bell, Copy, GitBranch, Lock, Monitor, Moon, RefreshCw, Sun, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { useSlack, useSlackActions, useWebhookActions, useWebhooks, useCurrentRole } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { cn } from '@/lib/cn'
import { fmt } from '@/lib/dates'
import { useTheme } from '@/stores/theme'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Page } from '@/components/ui/misc'

export function SettingsPage() {
  const role = useCurrentRole()
  const isAdmin = role === 'WORKSPACE_ADMIN'
  return (
    <Page className="max-w-3xl">
      <h1 className="mb-8 text-2xl font-semibold tracking-tight">Ayarlar</h1>
      <div className="space-y-6">
        <Section title="Görünüm" text="Tercihin bu tarayıcıda saklanır.">
          <ThemePicker />
        </Section>
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
