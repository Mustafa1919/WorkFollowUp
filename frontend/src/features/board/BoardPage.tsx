import { useEffect, useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { motion, AnimatePresence } from 'motion/react'
import * as DM from '@radix-ui/react-dropdown-menu'
import { toast } from 'sonner'
import { BarChart3, BookmarkPlus, Bookmark, CalendarDays, Check, CheckCircle2, KanbanSquare, MessageSquareQuote, Plus, Tag as TagIcon, X } from 'lucide-react'
import { useMembers, useProjectRealtime, useProjects, useSavedViewActions, useSavedViews, useSprints, useTags, useTasks, useCurrentRole } from '@/api/queries'
import { useCurrentUserId } from '@/stores/session'
import { cn } from '@/lib/cn'
import { errorMessage } from '@/lib/api'
import type { Task } from '@/lib/types'
import { Button } from '@/components/ui/Button'
import { Page, Skeleton } from '@/components/ui/misc'
import { KanbanView } from './KanbanView'
import { CalendarView } from '@/features/calendar/CalendarView'
import { TaskDialog } from './TaskDialog'
import { NewTaskDialog } from './NewTaskDialog'
import { SprintsDialog } from './SprintsDialog'
import { SaveViewDialog } from './SaveViewDialog'

type View = 'kanban' | 'calendar'

/** Gorunum ve sprint filtresi URL'de: yenilemede/paylasimda korunur. */
export function BoardPage() {
  const { projectId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const view: View = params.get('view') === 'calendar' ? 'calendar' : 'kanban'
  const sprintFilter = params.get('sprint') ?? 'all'
  // Coklu-secim tek bir query param'da virgullu tutulur (URL'de paylasilabilir/kalici kalsin).
  const tagFilter = useMemo(() => (params.get('tags') ?? '').split(',').filter(Boolean), [params])
  // 'me' | 'none' | <userId>; yoksa herkes. 'me' URL'de kisiden bagimsiz kalir (paylasilan link
  // aciliginda aciyan kisinin kendi islerini gosterir).
  const assigneeFilter = params.get('assignee')
  const me = useCurrentUserId()
  const { data: members } = useMembers(true)
  const assigneeTarget: string | null | undefined =
    assigneeFilter === null ? undefined : assigneeFilter === 'none' ? null : assigneeFilter === 'me' ? me : assigneeFilter

  const { data: projects } = useProjects()
  const project = projects?.find((p) => p.id === projectId)
  const { data: tasks, isLoading } = useTasks(projectId)
  const { data: sprints } = useSprints(projectId)
  const { data: tags } = useTags()
  const role = useCurrentRole()
  const canWrite = role !== undefined && role !== 'VIEWER'
  useProjectRealtime(projectId)

  const [selected, setSelected] = useState<Task | null>(null)
  const [newTaskOpen, setNewTaskOpen] = useState(false)
  const [sprintsOpen, setSprintsOpen] = useState(false)
  const [saveViewOpen, setSaveViewOpen] = useState(false)

  // Dalga 1.7: kayitli gorunum — sunucuda hic yorumlanmayan opak JSON (sadece bu 3 filtre).
  const { data: savedViews } = useSavedViews(projectId)
  const { remove: removeSavedView } = useSavedViewActions(projectId)
  const currentViewQuery = useMemo(
    () =>
      JSON.stringify({
        sprint: sprintFilter !== 'all' ? sprintFilter : undefined,
        tags: tagFilter.length > 0 ? tagFilter : undefined,
        assignee: assigneeFilter ?? undefined,
      }),
    [sprintFilter, tagFilter, assigneeFilter],
  )
  function applySavedView(query: string) {
    let parsed: { sprint?: string; tags?: string[]; assignee?: string } = {}
    try {
      parsed = JSON.parse(query)
    } catch {
      toast.error('Görünüm bozuk, uygulanamadı')
      return
    }
    const next = new URLSearchParams(params)
    if (parsed.sprint) next.set('sprint', parsed.sprint)
    else next.delete('sprint')
    if (parsed.tags && parsed.tags.length > 0) next.set('tags', parsed.tags.join(','))
    else next.delete('tags')
    if (parsed.assignee) next.set('assignee', parsed.assignee)
    else next.delete('assignee')
    setParams(next, { replace: true })
  }

  // Komut paletinden derin baglanti: `?task=<id>` ile gelinirse ilgili gorevi ac, sonra param'i
  // temizle (bir daha tasks yenilendiginde tekrar acilmasin).
  const taskParam = params.get('task')
  useEffect(() => {
    if (!taskParam || !tasks) return
    const found = tasks.find((t) => t.id === taskParam)
    if (found) setSelected(found)
    const next = new URLSearchParams(params)
    next.delete('task')
    setParams(next, { replace: true })
  }, [taskParam, tasks, params, setParams])

  const filtered = useMemo(() => {
    if (!tasks) return []
    let result = tasks
    if (sprintFilter === 'backlog') result = result.filter((t) => !t.sprintId)
    else if (sprintFilter !== 'all') result = result.filter((t) => t.sprintId === sprintFilter)
    // Secili etiketlerden HERHANGI BIRINI tasiyan gorev eslesir (OR); backend bunu ayrica
    // desteklemiyor (bkz. rapor: liste zaten tamami cekilip client-side filtreleniyor, sprint
    // filtresiyle AYNI desen).
    if (tagFilter.length > 0) result = result.filter((t) => t.tags.some((tag) => tagFilter.includes(tag.id)))
    if (assigneeTarget !== undefined) result = result.filter((t) => t.assigneeId === assigneeTarget)
    return result
  }, [tasks, sprintFilter, tagFilter, assigneeTarget])

  function set(key: string, value: string | null) {
    const next = new URLSearchParams(params)
    if (value === null) next.delete(key)
    else next.set(key, value)
    setParams(next, { replace: true })
  }

  // Dialog acikken guncel veriyi goster (optimistic guncelleme sonrasi).
  const selectedLive = selected ? (tasks?.find((t) => t.id === selected.id) ?? selected) : null

  return (
    <Page className="max-w-none">
      <div className="mb-6 flex flex-wrap items-center gap-3">
        <div className="mr-auto min-w-0">
          <div className="flex items-center gap-2 text-xs text-muted">
            <Link to="/" className="hover:text-fg">
              Projeler
            </Link>
            <span>/</span>
            <span className="font-mono">{project?.key}</span>
          </div>
          <h1 className="truncate text-2xl font-semibold tracking-tight">{project?.name ?? <Skeleton className="h-7 w-48" />}</h1>
        </div>

        <ViewSwitch view={view} onChange={(v) => set('view', v === 'kanban' ? null : v)} />

        <select
          value={sprintFilter}
          onChange={(e) => set('sprint', e.target.value === 'all' ? null : e.target.value)}
          className="h-9 cursor-pointer rounded-xl border border-border bg-surface px-3 text-sm outline-none focus:border-accent"
          aria-label="Sprint filtresi"
        >
          <option value="all">Tüm görevler</option>
          <option value="backlog">Backlog (sprintsiz)</option>
          {sprints?.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
              {s.status === 'active' ? ' • aktif' : s.status === 'completed' ? ' • bitti' : ''}
            </option>
          ))}
        </select>
        <select
          value={assigneeFilter ?? 'all'}
          onChange={(e) => set('assignee', e.target.value === 'all' ? null : e.target.value)}
          className={cn(
            'h-9 cursor-pointer rounded-xl border bg-surface px-3 text-sm outline-none focus:border-accent',
            assigneeFilter ? 'border-accent text-accent' : 'border-border',
          )}
          aria-label="Atanan filtresi"
        >
          <option value="all">Herkes</option>
          <option value="me">Bana atananlar</option>
          <option value="none">Atanmamış</option>
          {members
            ?.filter((m) => m.role !== 'VIEWER' && m.userId !== me)
            .map((m) => (
              <option key={m.userId} value={m.userId}>
                {m.fullName}
              </option>
            ))}
        </select>
        {tags && tags.length > 0 && (
          <TagFilter
            tags={tags}
            selected={tagFilter}
            onChange={(ids) => set('tags', ids.length === 0 ? null : ids.join(','))}
          />
        )}
        <SavedViewsMenu
          views={savedViews ?? []}
          onApply={applySavedView}
          onSave={() => setSaveViewOpen(true)}
          onDelete={(id) => removeSavedView.mutate(id, { onError: (err) => toast.error(errorMessage(err)) })}
        />
        <Button variant="outline" size="sm" className="h-9" onClick={() => setSprintsOpen(true)}>
          Sprintler
        </Button>
        <Link to={`/projects/${projectId}/completed`}>
          <Button variant="outline" size="sm" className="h-9">
            <CheckCircle2 size={15} /> Tamamlananlar
          </Button>
        </Link>
        <Link to={`/projects/${projectId}/analytics`}>
          <Button variant="outline" size="sm" className="h-9">
            <BarChart3 size={15} /> Analitik
          </Button>
        </Link>
        <Link to={`/projects/${projectId}/retro`}>
          <Button variant="outline" size="sm" className="h-9">
            <MessageSquareQuote size={15} /> Retro
          </Button>
        </Link>
        {canWrite && (
          <Button size="sm" className="h-9" onClick={() => setNewTaskOpen(true)}>
            <Plus size={15} /> Görev
          </Button>
        )}
      </div>

      {isLoading ? (
        <div className="grid gap-4 md:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-72" />
          ))}
        </div>
      ) : (
        <AnimatePresence mode="wait" initial={false}>
          <motion.div
            key={view}
            initial={{ opacity: 0, x: view === 'calendar' ? 24 : -24 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: view === 'calendar' ? -24 : 24 }}
            transition={{ duration: 0.25, ease: [0.22, 1, 0.36, 1] }}
          >
            {view === 'kanban' ? (
              <KanbanView projectId={projectId} projectKey={project?.key} tasks={filtered} canWrite={canWrite} onOpen={setSelected} />
            ) : (
              <CalendarView
                projectId={projectId}
                projectKey={project?.key}
                sprintFilter={sprintFilter}
                tagFilter={tagFilter}
                assigneeTarget={assigneeTarget}
                undated={filtered.filter((t) => !t.dueDate)}
                canWrite={canWrite}
                onOpen={setSelected}
              />
            )}
          </motion.div>
        </AnimatePresence>
      )}

      <TaskDialog
        task={selectedLive}
        projectKey={project?.key}
        sprints={sprints ?? []}
        canWrite={canWrite}
        onOpenChange={(o) => !o && setSelected(null)}
      />
      <NewTaskDialog projectId={projectId} open={newTaskOpen} onOpenChange={setNewTaskOpen} />
      <SprintsDialog projectId={projectId} open={sprintsOpen} onOpenChange={setSprintsOpen} canManage={role === 'WORKSPACE_ADMIN' || role === 'MANAGER'} />
      <SaveViewDialog projectId={projectId} query={currentViewQuery} open={saveViewOpen} onOpenChange={setSaveViewOpen} />
    </Page>
  )
}

/** Dalga 1.7 — kisisel kayitli gorunumler: uygula/kaydet/sil. */
function SavedViewsMenu({
  views,
  onApply,
  onSave,
  onDelete,
}: {
  views: { id: string; name: string; query: string }[]
  onApply: (query: string) => void
  onSave: () => void
  onDelete: (id: string) => void
}) {
  return (
    <DM.Root>
      <DM.Trigger
        className={cn(
          'flex h-9 cursor-pointer items-center gap-1.5 rounded-xl border border-border bg-surface px-3 text-sm text-fg outline-none transition-colors hover:bg-surface-2',
        )}
      >
        <Bookmark size={14} /> Görünümler{views.length > 0 ? ` (${views.length})` : ''}
      </DM.Trigger>
      <DM.Portal>
        <DM.Content align="start" sideOffset={6} className="z-50 max-h-80 w-64 overflow-y-auto rounded-xl border border-border bg-surface p-1.5 shadow-xl">
          {views.length === 0 && <div className="px-2 py-2 text-xs text-muted">Henüz kayıtlı görünüm yok</div>}
          {views.map((v) => (
            <div
              key={v.id}
              className="group flex items-center gap-1 rounded-lg px-2 py-1.5 text-sm outline-none hover:bg-surface-2"
            >
              <button onClick={() => onApply(v.query)} className="flex-1 cursor-pointer truncate text-left">
                {v.name}
              </button>
              <button
                onClick={() => onDelete(v.id)}
                className="cursor-pointer rounded-md p-1 text-muted opacity-0 hover:bg-surface hover:text-danger group-hover:opacity-100"
                aria-label={`${v.name} görünümünü sil`}
              >
                <X size={12} />
              </button>
            </div>
          ))}
          <DM.Separator className="my-1 h-px bg-border" />
          <DM.Item
            onSelect={onSave}
            className="flex cursor-pointer items-center gap-1.5 rounded-lg px-2 py-1.5 text-sm text-accent outline-none data-[highlighted]:bg-surface-2"
          >
            <BookmarkPlus size={14} /> Şu anki görünümü kaydet
          </DM.Item>
        </DM.Content>
      </DM.Portal>
    </DM.Root>
  )
}

/** Coklu-secim etiket filtresi: secili tag id'leri virgullu URL param'ina yansir (paylasilabilir). */
function TagFilter({
  tags,
  selected,
  onChange,
}: {
  tags: { id: string; name: string; color: string }[]
  selected: string[]
  onChange: (ids: string[]) => void
}) {
  function toggle(id: string) {
    onChange(selected.includes(id) ? selected.filter((s) => s !== id) : [...selected, id])
  }
  return (
    <DM.Root>
      <DM.Trigger
        className={cn(
          'flex h-9 cursor-pointer items-center gap-1.5 rounded-xl border px-3 text-sm outline-none transition-colors',
          selected.length > 0 ? 'border-accent bg-accent-soft text-accent' : 'border-border bg-surface text-fg hover:bg-surface-2',
        )}
      >
        <TagIcon size={14} /> Etiket{selected.length > 0 ? ` (${selected.length})` : ''}
      </DM.Trigger>
      <DM.Portal>
        <DM.Content align="start" sideOffset={6} className="z-50 max-h-72 w-56 overflow-y-auto rounded-xl border border-border bg-surface p-1.5 shadow-xl">
          {tags.map((tag) => (
            <DM.CheckboxItem
              key={tag.id}
              checked={selected.includes(tag.id)}
              onSelect={(e) => {
                e.preventDefault()
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
          ))}
          {selected.length > 0 && (
            <>
              <DM.Separator className="my-1 h-px bg-border" />
              <DM.Item
                onSelect={() => onChange([])}
                className="cursor-pointer rounded-lg px-2 py-1.5 text-sm text-muted outline-none data-[highlighted]:bg-surface-2 data-[highlighted]:text-fg"
              >
                Filtreyi temizle
              </DM.Item>
            </>
          )}
        </DM.Content>
      </DM.Portal>
    </DM.Root>
  )
}

function ViewSwitch({ view, onChange }: { view: View; onChange: (v: View) => void }) {
  const items: { id: View; label: string; icon: typeof KanbanSquare }[] = [
    { id: 'kanban', label: 'Kanban', icon: KanbanSquare },
    { id: 'calendar', label: 'Takvim', icon: CalendarDays },
  ]
  return (
    <div role="tablist" className="flex rounded-xl border border-border bg-surface p-1">
      {items.map((i) => (
        <button
          key={i.id}
          role="tab"
          aria-selected={view === i.id}
          onClick={() => onChange(i.id)}
          className={cn(
            'relative flex cursor-pointer items-center gap-1.5 rounded-lg px-3 py-1 text-sm transition-colors',
            view === i.id ? 'text-accent-fg' : 'text-muted hover:text-fg',
          )}
        >
          {view === i.id && (
            <motion.span
              layoutId="view-pill"
              className="absolute inset-0 rounded-lg bg-accent"
              transition={{ type: 'spring', stiffness: 500, damping: 36 }}
            />
          )}
          <i.icon size={15} className="relative" />
          <span className="relative">{i.label}</span>
        </button>
      ))}
    </div>
  )
}
