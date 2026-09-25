import { Link, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { ArrowLeft, Bot, GitMerge, ListTree, ShieldAlert, Timer, UserCheck } from 'lucide-react'
import { useAutomationRuleActions, useAutomationRules, useCurrentRole, useProjects } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import type { AutomationRuleResponse, AutomationTemplateKey } from '@/lib/types'
import { EmptyState, Page, Skeleton } from '@/components/ui/misc'

const TEMPLATES: {
  key: AutomationTemplateKey
  icon: typeof Bot
  title: string
  description: string
}[] = [
  {
    key: 'PR_MERGE_TO_DONE',
    icon: GitMerge,
    title: 'PR birleşince görev Done olsun',
    description:
      'Bağlantılı bir pull request "merge" edilince görev otomatik Done durumuna geçer. Varsayılan olarak açıktır.',
  },
  {
    key: 'SUBTASK_ALL_DONE_PARENT_TO_REVIEW',
    icon: ListTree,
    title: 'Tüm alt görevler bitince üst görev Review olsun',
    description: 'Bir görevin tüm alt görevleri Done olduğunda üst görev otomatik Review durumuna geçer.',
  },
  {
    key: 'BLOCKER_DONE_NOTIFY',
    icon: ShieldAlert,
    title: 'Bloklayan görev bitince bildir',
    description:
      'Bir görevi bloklayan görev Done olunca, bloklanan görevin atananına ve izleyicilerine bildirim gider.',
  },
  {
    key: 'OVERDUE_NOTIFY',
    icon: Timer,
    title: 'Süresi geçen görevler için bildir',
    description: 'Bitiş tarihi geçmiş, henüz Done olmayan görevler için günde bir kez bildirim gider.',
  },
  {
    key: 'ASSIGNED_TODO_TO_IN_PROGRESS',
    icon: UserCheck,
    title: 'Atama yapılınca In Progress olsun',
    description: 'To Do durumundaki bir görev birine atanınca otomatik In Progress durumuna geçer.',
  },
]

/** Dalga 3.1 (ADR-0016) — proje bazinda hazir otomasyon sablonlarinin ac/kapa listesi. */
export function AutomationRulesPage() {
  const { projectId = '' } = useParams()
  const { data: projects } = useProjects()
  const project = projects?.find((p) => p.id === projectId)
  const role = useCurrentRole()
  const canManage = role === 'WORKSPACE_ADMIN' || role === 'MANAGER'

  const { data: rules, isLoading } = useAutomationRules(projectId, true)
  const { setEnabled } = useAutomationRuleActions(projectId)

  const byKey = new Map((rules ?? []).map((r) => [r.templateKey, r]))

  return (
    <Page>
      <Link to={`/projects/${projectId}`} className="mb-3 inline-flex items-center gap-1.5 text-sm text-muted hover:text-fg">
        <ArrowLeft size={15} /> Panoya dön
      </Link>

      <div className="mb-6">
        <h1 className="text-2xl font-semibold tracking-tight">{project?.name ?? '…'} · Otomasyon</h1>
        <p className="mt-1 text-sm text-muted">
          Hazır şablonlar — serbest kural editörü yok, her şablon proje bazında açılıp kapatılabilir.
        </p>
      </div>

      {isLoading ? (
        <div className="grid gap-3">
          {TEMPLATES.map((t) => (
            <Skeleton key={t.key} className="h-20" />
          ))}
        </div>
      ) : !rules ? (
        <EmptyState icon={<Bot size={22} />} title="Otomasyon bilgisi yüklenemedi" text="Lütfen tekrar deneyin." />
      ) : (
        <div className="grid gap-3">
          {TEMPLATES.map((template) => {
            const status: AutomationRuleResponse | undefined = byKey.get(template.key)
            const enabled = status?.enabled ?? false
            const Icon = template.icon
            return (
              <label
                key={template.key}
                className="flex items-start gap-3 rounded-lg border border-border bg-surface p-4"
              >
                <Icon size={20} className="mt-0.5 shrink-0 text-muted" />
                <div className="flex-1">
                  <div className="font-medium">{template.title}</div>
                  <p className="mt-0.5 text-sm text-muted">{template.description}</p>
                </div>
                <input
                  type="checkbox"
                  className="mt-1 h-4 w-4 shrink-0 cursor-pointer disabled:cursor-not-allowed"
                  checked={enabled}
                  disabled={!canManage || setEnabled.isPending}
                  onChange={(e) =>
                    setEnabled.mutate(
                      { templateKey: template.key, enabled: e.target.checked },
                      { onError: (err) => toast.error(errorMessage(err)) },
                    )
                  }
                />
              </label>
            )
          })}
        </div>
      )}
    </Page>
  )
}
