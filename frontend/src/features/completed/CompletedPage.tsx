import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, CheckCircle2, Search } from 'lucide-react'
import { useApprovedTasks, useProjects, useSprints } from '@/api/queries'
import { fmt } from '@/lib/dates'
import { taskKey } from '@/lib/status'
import type { Task } from '@/lib/types'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { EmptyState, Page, Skeleton } from '@/components/ui/misc'
import { TaskDialog } from '@/features/board/TaskDialog'

/** Onaylanmis gorevler: Kanban'dan kalkan isler burada, onay zamanina gore yeniden eskiye. */
export function CompletedPage() {
  const { projectId = '' } = useParams()
  const { data: projects } = useProjects()
  const project = projects?.find((p) => p.id === projectId)
  const { data: sprints } = useSprints(projectId)
  const { data, isLoading, hasNextPage, fetchNextPage, isFetchingNextPage } = useApprovedTasks(projectId)
  const [query, setQuery] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const tasks = useMemo(() => data?.pages.flatMap((p) => p.data) ?? [], [data])
  const filtered = useMemo(() => {
    const q = query.trim().toLocaleLowerCase('tr')
    if (!q) return tasks
    return tasks.filter(
      (t) => t.title.toLocaleLowerCase('tr').includes(q) || taskKey(project?.key, t.taskNumber).toLowerCase().includes(q),
    )
  }, [tasks, query, project?.key])
  // Onay geri alinirsa gorev listeden duser; dialog kapanir.
  const selected = tasks.find((t) => t.id === selectedId) ?? null
  const sprintName = (t: Task) => sprints?.find((s) => s.id === t.sprintId)?.name

  return (
    <Page>
      <Link to={`/projects/${projectId}`} className="mb-3 inline-flex items-center gap-1.5 text-sm text-muted hover:text-fg">
        <ArrowLeft size={15} /> Board
      </Link>
      <div className="mb-6 flex flex-wrap items-end gap-3">
        <div className="mr-auto">
          <h1 className="text-2xl font-semibold tracking-tight">Tamamlananlar</h1>
          <p className="text-sm text-muted">
            {project?.name} · onaylanmış görevler{tasks.length > 0 && ` · ${tasks.length}${hasNextPage ? '+' : ''}`}
          </p>
        </div>
        <div className="relative w-full sm:w-64">
          <Search size={15} className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-muted" />
          <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Başlık veya anahtar" className="pl-9" />
        </div>
      </div>

      {isLoading ? (
        <div className="space-y-2">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-14" />
          ))}
        </div>
      ) : tasks.length === 0 ? (
        <EmptyState
          icon={<CheckCircle2 size={22} />}
          title="Henüz onaylanmış görev yok"
          text="Kanban'da 'Tamamlandı' kolonundaki görevler yönetici veya admin onayından sonra burada listelenir."
        />
      ) : (
        <>
          <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-surface">
            {filtered.map((t) => (
              <li key={t.id}>
                <button
                  onClick={() => setSelectedId(t.id)}
                  className="flex w-full cursor-pointer items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-surface-2"
                >
                  <CheckCircle2 size={16} className="shrink-0 text-st-done" />
                  <span className="shrink-0 font-mono text-xs text-muted">{taskKey(project?.key, t.taskNumber)}</span>
                  <span className="min-w-0 flex-1 truncate text-sm">{t.title}</span>
                  {sprintName(t) && <span className="hidden rounded-md bg-surface-2 px-1.5 py-0.5 text-xs text-muted sm:inline">{sprintName(t)}</span>}
                  <span className="shrink-0 text-xs text-muted tabular-nums">{t.approvedAt && fmt(t.approvedAt, 'd MMM yyyy')}</span>
                </button>
              </li>
            ))}
            {filtered.length === 0 && <li className="px-4 py-6 text-center text-sm text-muted">Eşleşen görev yok.</li>}
          </ul>
          {hasNextPage && (
            <div className="mt-4 flex justify-center">
              <Button variant="outline" size="sm" loading={isFetchingNextPage} onClick={() => fetchNextPage()}>
                Daha fazla
              </Button>
            </div>
          )}
        </>
      )}

      <TaskDialog
        task={selected}
        projectKey={project?.key}
        sprints={sprints ?? []}
        canWrite={false}
        onOpenChange={(o) => !o && setSelectedId(null)}
      />
    </Page>
  )
}
