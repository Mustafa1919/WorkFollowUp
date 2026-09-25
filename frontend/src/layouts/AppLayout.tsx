import { useEffect, useState, type FormEvent } from 'react'
import { NavLink, useLocation, useNavigate, useOutlet } from 'react-router-dom'
import { AnimatePresence, motion } from 'motion/react'
import * as DM from '@radix-ui/react-dropdown-menu'
import { BarChart3, CalendarClock, Check, ChevronsUpDown, CircleUserRound, FolderKanban, Home, LogOut, Menu, MessageCircleMore, Plus, Search, Settings, X } from 'lucide-react'
import { toast } from 'sonner'
import { useCreateWorkspace, useCurrentRole, useNotificationRealtime, useProjects, useWorkspaces } from '@/api/queries'
import { api, errorMessage } from '@/lib/api'
import { cn } from '@/lib/cn'
import { disconnectRealtime, ensureRealtimeConnected } from '@/lib/realtime'
import { useSession } from '@/stores/session'
import { CommandPalette } from '@/components/CommandPalette'
import { ShortcutsDialog } from '@/components/ShortcutsDialog'
import { openCommandPalette } from '@/lib/commandPalette'
import { NotificationBell } from '@/components/NotificationBell'
import { ThemeToggle } from '@/components/ThemeToggle'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Dialog } from '@/components/ui/Dialog'
import { Aurora } from '@/components/Aurora'
import { Logo } from '@/features/auth/AuthPage'
import { CreateProjectDialog } from '@/features/dashboard/CreateProjectDialog'
import { useQueryClient } from '@tanstack/react-query'

export function AppLayout() {
  const { data: workspaces, isLoading } = useWorkspaces()
  const accessToken = useSession((s) => s.accessToken)
  const workspaceId = useSession((s) => s.workspaceId)
  const setWorkspace = useSession((s) => s.setWorkspace)
  // Mobil menu acildigi sayfaya baglidir: gezinince kendiliginden kapanir (effect gerekmez).
  const [mobileOpenAt, setMobileOpenAt] = useState<string | null>(null)
  const location = useLocation()
  const outlet = useOutlet()
  const mobileOpen = mobileOpenAt === location.pathname

  // Secili workspace artik uye olunan listede yoksa (silindi / baska kullanici) ilkine dus.
  useEffect(() => {
    if (!workspaces) return
    if (!workspaces.some((w) => w.id === workspaceId)) setWorkspace(workspaces[0]?.id ?? null)
  }, [workspaces, workspaceId, setWorkspace])

  // Tek paylasimli STOMP baglantisi: jeton yenilenince (refresh) veya workspace degisince tam
  // yeniden kurulur (coordinator karari — proje kanali bagimsiz olarak BoardPage'de abone olur).
  useEffect(() => {
    disconnectRealtime()
    if (accessToken) ensureRealtimeConnected(accessToken)
    return () => disconnectRealtime()
  }, [accessToken, workspaceId])

  // Inbox push'u: workspace/proje'den bağımsız, oturum boyunca tek yerden abone olunur.
  useNotificationRealtime()

  if (isLoading) return null
  if (workspaces && workspaces.length === 0) return <Onboarding />

  return (
    <div className="flex min-h-screen">
      <aside className="sticky top-0 hidden h-screen w-64 shrink-0 border-r border-border bg-surface lg:block">
        <Sidebar />
      </aside>

      <AnimatePresence>
        {mobileOpen && (
          <>
            <motion.div
              className="fixed inset-0 z-30 bg-black/40 backdrop-blur-sm lg:hidden"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setMobileOpenAt(null)}
            />
            <motion.aside
              className="fixed inset-y-0 left-0 z-40 w-72 border-r border-border bg-surface lg:hidden"
              initial={{ x: '-100%' }}
              animate={{ x: 0 }}
              exit={{ x: '-100%' }}
              transition={{ type: 'spring', stiffness: 380, damping: 36 }}
            >
              <button
                className="absolute top-4 right-3 cursor-pointer rounded-lg p-1.5 text-muted hover:bg-surface-2"
                onClick={() => setMobileOpenAt(null)}
                aria-label="Menüyü kapat"
              >
                <X size={18} />
              </button>
              <Sidebar />
            </motion.aside>
          </>
        )}
      </AnimatePresence>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-20 flex h-14 items-center gap-2 border-b border-border bg-bg/75 px-4 backdrop-blur-xl sm:px-6">
          <button
            className="-ml-1 cursor-pointer rounded-lg p-2 text-muted hover:bg-surface-2 lg:hidden"
            onClick={() => setMobileOpenAt(location.pathname)}
            aria-label="Menüyü aç"
          >
            <Menu size={18} />
          </button>
          <WorkspaceSwitcher />
          <div className="flex-1" />
          <button
            onClick={openCommandPalette}
            className="hidden cursor-pointer items-center gap-2 rounded-xl border border-border bg-surface px-3 py-1.5 text-xs text-muted transition-colors hover:bg-surface-2 sm:flex"
            aria-label="Komut paleti (Ctrl+K)"
          >
            <Search size={13} /> Ara
            <kbd className="rounded border border-border px-1 py-px text-[10px]">Ctrl K</kbd>
          </button>
          <NotificationBell />
          <ThemeToggle />
          <UserMenu />
        </header>
        <main className="min-w-0 flex-1">
          <AnimatePresence mode="wait">
            <motion.div key={location.pathname}>{outlet}</motion.div>
          </AnimatePresence>
        </main>
      </div>
      <CommandPalette />
      <ShortcutsDialog />
    </div>
  )
}

function Sidebar() {
  const { data: projects } = useProjects()
  const [createOpen, setCreateOpen] = useState(false)
  const role = useCurrentRole()
  const canCreate = role === 'WORKSPACE_ADMIN' || role === 'MANAGER'

  const link = ({ isActive }: { isActive: boolean }) =>
    cn(
      'group relative flex items-center gap-2.5 rounded-xl px-3 py-2 text-sm transition-colors',
      isActive ? 'text-fg font-medium' : 'text-muted hover:text-fg hover:bg-surface-2',
    )

  return (
    <div className="flex h-full flex-col p-4">
      <div className="px-2 pt-1 pb-6">
        <Logo />
      </div>
      <nav className="space-y-1">
        <NavLink to="/" end className={link}>
          {({ isActive }) => (
            <>
              {isActive && <ActivePill />}
              <Home size={17} className="relative" /> <span className="relative">Ana sayfa</span>
            </>
          )}
        </NavLink>
        <NavLink to="/my-work" className={link}>
          {({ isActive }) => (
            <>
              {isActive && <ActivePill />}
              <CircleUserRound size={17} className="relative" /> <span className="relative">Benim işlerim</span>
            </>
          )}
        </NavLink>
        <NavLink to="/meetings" className={link}>
          {({ isActive }) => (
            <>
              {isActive && <ActivePill />}
              <CalendarClock size={17} className="relative" /> <span className="relative">Toplantılar</span>
            </>
          )}
        </NavLink>
        <NavLink to="/standups" className={link}>
          {({ isActive }) => (
            <>
              {isActive && <ActivePill />}
              <MessageCircleMore size={17} className="relative" /> <span className="relative">Standup</span>
            </>
          )}
        </NavLink>
        <NavLink to="/reports" className={link}>
          {({ isActive }) => (
            <>
              {isActive && <ActivePill />}
              <BarChart3 size={17} className="relative" /> <span className="relative">Raporlar</span>
            </>
          )}
        </NavLink>
      </nav>

      <div className="mt-6 mb-2 flex items-center justify-between px-3">
        <span className="text-xs font-medium tracking-wide text-muted uppercase">Projeler</span>
        {canCreate && (
          <button
            onClick={() => setCreateOpen(true)}
            className="cursor-pointer rounded-md p-1 text-muted transition-colors hover:bg-surface-2 hover:text-fg"
            aria-label="Yeni proje"
          >
            <Plus size={15} />
          </button>
        )}
      </div>
      <nav className="-mx-1 flex-1 space-y-0.5 overflow-y-auto px-1">
        {projects?.map((p) => (
          <NavLink key={p.id} to={`/projects/${p.id}`} className={link}>
            {({ isActive }) => (
              <>
                {isActive && <ActivePill />}
                <span className="relative grid h-5 w-5 shrink-0 place-items-center rounded-md bg-accent-soft text-[10px] font-bold text-accent">
                  {p.key.slice(0, 2)}
                </span>
                <span className="relative truncate">{p.name}</span>
              </>
            )}
          </NavLink>
        ))}
        {projects?.length === 0 && <p className="px-3 py-2 text-xs text-muted">Henüz proje yok.</p>}
      </nav>

      <NavLink to="/settings" className={link}>
        {({ isActive }) => (
          <>
            {isActive && <ActivePill />}
            <Settings size={17} className="relative" /> <span className="relative">Ayarlar</span>
          </>
        )}
      </NavLink>
      <CreateProjectDialog open={createOpen} onOpenChange={setCreateOpen} />
    </div>
  )
}

/** Aktif menu ogesinin arka plani ogeler arasinda "kayarak" gecer (shared layout animasyonu). */
function ActivePill() {
  return (
    <motion.span
      layoutId="nav-active"
      className="absolute inset-0 rounded-xl bg-accent-soft"
      transition={{ type: 'spring', stiffness: 500, damping: 38 }}
    />
  )
}

function WorkspaceSwitcher() {
  const { data: workspaces } = useWorkspaces()
  const workspaceId = useSession((s) => s.workspaceId)
  const setWorkspace = useSession((s) => s.setWorkspace)
  const navigate = useNavigate()
  const [creating, setCreating] = useState(false)
  const current = workspaces?.find((w) => w.id === workspaceId)

  return (
    <>
      <DM.Root>
        <DM.Trigger className="flex max-w-[60vw] cursor-pointer items-center gap-2 rounded-xl px-2.5 py-1.5 text-sm font-medium transition-colors outline-none hover:bg-surface-2">
          <span className="grid h-6 w-6 shrink-0 place-items-center rounded-lg bg-gradient-to-br from-accent to-fuchsia-500 text-[11px] text-white">
            {current?.name.charAt(0).toUpperCase() ?? '?'}
          </span>
          <span className="truncate">{current?.name ?? 'Workspace seç'}</span>
          <ChevronsUpDown size={14} className="shrink-0 text-muted" />
        </DM.Trigger>
        <DM.Portal>
          <DM.Content align="start" sideOffset={6} className="z-50 min-w-60 rounded-xl border border-border bg-surface p-1.5 shadow-xl">
            <DM.Label className="px-2 py-1.5 text-xs text-muted">Workspace'ler</DM.Label>
            {workspaces?.map((w) => (
              <DM.Item
                key={w.id}
                onSelect={() => {
                  setWorkspace(w.id)
                  navigate('/')
                }}
                className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-sm outline-none data-[highlighted]:bg-surface-2"
              >
                <span className="flex-1 truncate">{w.name}</span>
                <span className="text-[10px] text-muted">{w.role.replace('WORKSPACE_', '')}</span>
                {w.id === workspaceId && <Check size={14} className="text-accent" />}
              </DM.Item>
            ))}
            <DM.Separator className="my-1 h-px bg-border" />
            <DM.Item
              onSelect={() => setCreating(true)}
              className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-muted outline-none data-[highlighted]:bg-surface-2 data-[highlighted]:text-fg"
            >
              <Plus size={14} /> Yeni workspace
            </DM.Item>
          </DM.Content>
        </DM.Portal>
      </DM.Root>
      <CreateWorkspaceDialog open={creating} onOpenChange={setCreating} />
    </>
  )
}

function UserMenu() {
  const email = useSession((s) => s.email)
  const qc = useQueryClient()
  const navigate = useNavigate()

  async function logout() {
    try {
      await api.post('/api/v1/auth/logout')
    } catch {
      /* cookie zaten gecersiz olabilir */
    }
    useSession.getState().clear()
    qc.clear()
    navigate('/login')
  }

  return (
    <DM.Root>
      <DM.Trigger
        className="grid h-8 w-8 cursor-pointer place-items-center rounded-full bg-surface-2 text-xs font-semibold uppercase ring-accent/40 outline-none hover:ring-2"
        aria-label="Kullanıcı menüsü"
      >
        {email?.charAt(0) ?? 'U'}
      </DM.Trigger>
      <DM.Portal>
        <DM.Content align="end" sideOffset={6} className="z-50 min-w-56 rounded-xl border border-border bg-surface p-1.5 shadow-xl">
          <div className="truncate px-2 py-1.5 text-xs text-muted">{email}</div>
          <DM.Separator className="my-1 h-px bg-border" />
          <DM.Item
            onSelect={logout}
            className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-danger outline-none data-[highlighted]:bg-surface-2"
          >
            <LogOut size={14} /> Çıkış yap
          </DM.Item>
        </DM.Content>
      </DM.Portal>
    </DM.Root>
  )
}


function CreateWorkspaceDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const [name, setName] = useState('')
  const create = useCreateWorkspace()
  const setWorkspace = useSession((s) => s.setWorkspace)

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      const ws = await create.mutateAsync(name)
      setWorkspace(ws.id)
      setName('')
      onOpenChange(false)
      toast.success('Workspace oluşturuldu')
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="Yeni workspace" description="Ekibin ve projelerin için ayrı bir alan.">
      <form onSubmit={submit} className="space-y-4">
        <Input autoFocus required maxLength={100} placeholder="ör. Acme Mühendislik" value={name} onChange={(e) => setName(e.target.value)} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
            Vazgeç
          </Button>
          <Button type="submit" loading={create.isPending}>
            Oluştur
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

/** Hic workspace'i olmayan kullanici: tek adimlik karsilama ekrani. */
function Onboarding() {
  const [name, setName] = useState('')
  const create = useCreateWorkspace()
  const setWorkspace = useSession((s) => s.setWorkspace)

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      const ws = await create.mutateAsync(name)
      setWorkspace(ws.id)
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  return (
    <div className="relative grid min-h-screen place-items-center overflow-hidden px-4">
      <Aurora />
      <div className="absolute top-4 right-4">
        <ThemeToggle />
      </div>
      <motion.form
        onSubmit={submit}
        initial={{ opacity: 0, y: 20, scale: 0.98 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
        className="relative w-full max-w-md rounded-3xl border border-border bg-surface/80 p-8 shadow-2xl backdrop-blur-xl"
      >
        <div className="mb-6 grid h-12 w-12 place-items-center rounded-2xl bg-accent-soft text-accent">
          <FolderKanban size={22} />
        </div>
        <h1 className="text-2xl font-semibold tracking-tight">İlk workspace'ini oluştur</h1>
        <p className="mt-1 mb-6 text-sm text-muted">Projelerin ve görevlerin burada yaşayacak. Yöneticisi sen olacaksın.</p>
        <Input autoFocus required maxLength={100} placeholder="Workspace adı" value={name} onChange={(e) => setName(e.target.value)} />
        <Button type="submit" loading={create.isPending} className="mt-4 w-full justify-center">
          Başla
        </Button>
      </motion.form>
    </div>
  )
}
