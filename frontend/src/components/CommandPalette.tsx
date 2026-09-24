import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import * as RD from '@radix-ui/react-dialog'
import { AnimatePresence, motion } from 'motion/react'
import { useQueryClient } from '@tanstack/react-query'
import { BarChart3, CalendarClock, CircleUserRound, CornerDownLeft, Home, Keyboard, LogOut, Moon, Search, Settings, Sun } from 'lucide-react'
import { useAllTasks, useProjects } from '@/api/queries'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { onCommandPaletteOpenRequested } from '@/lib/commandPalette'
import { openShortcuts } from '@/lib/shortcuts'
import { taskKey } from '@/lib/status'
import { useSession } from '@/stores/session'
import { useTheme } from '@/stores/theme'
import { StatusDot } from '@/components/ui/misc'

interface Item {
  id: string
  group: 'Git' | 'Görevler' | 'Eylemler'
  label: string
  sublabel?: string
  icon: React.ReactNode
  onSelect: () => void
}

/**
 * Cmd/Ctrl+K ile acilan komut paleti. Gorev arama sadece kullanici yazmaya baslayinca calisir
 * (bos sorguda tum workspace gorevlerini listelemek gurultu uretir); navigasyon/eylemler her zaman
 * gorunur. Tek dugumluk index tum gruplari kapsar (ok tuslariyla gruplar arasi da gezilir).
 */
export function CommandPalette() {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { theme, setTheme } = useTheme()
  const { data: projects } = useProjects()
  // Sadece palet acikken cek: kapaliyken workspace'teki tum projelerin gorevlerini bosuna yuklemeyelim.
  const { data: allTasks } = useAllTasks(open ? (projects ?? []) : [])

  const openRef = useRef(open)
  useEffect(() => {
    openRef.current = open
  }, [open])

  function openPalette() {
    setQuery('')
    setActive(0)
    setOpen(true)
  }

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key.toLowerCase() === 'k' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault()
        if (openRef.current) setOpen(false)
        else openPalette()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  useEffect(() => onCommandPaletteOpenRequested(openPalette), [])

  useEffect(() => {
    // Radix icerigi mount olduktan sonra odakla (forceMount + AnimatePresence araya giriyor);
    // bu tek basina "DOM'la senkronize ol" oldugu icin setState icermez.
    if (open) requestAnimationFrame(() => inputRef.current?.focus())
  }, [open])

  const logout = useCallback(async () => {
    try {
      await api.post('/api/v1/auth/logout')
    } catch {
      /* cookie zaten gecersiz olabilir */
    }
    useSession.getState().clear()
    qc.clear()
    navigate('/login')
  }, [qc, navigate])

  const items = useMemo<Item[]>(() => {
    const nav: Item[] = [
      { id: 'nav-home', group: 'Git', label: 'Ana sayfa', icon: <Home size={15} />, onSelect: () => navigate('/') },
      { id: 'nav-my-work', group: 'Git', label: 'Benim işlerim', icon: <CircleUserRound size={15} />, onSelect: () => navigate('/my-work') },
      { id: 'nav-meetings', group: 'Git', label: 'Toplantılar', icon: <CalendarClock size={15} />, onSelect: () => navigate('/meetings') },
      { id: 'nav-reports', group: 'Git', label: 'Raporlar', icon: <BarChart3 size={15} />, onSelect: () => navigate('/reports') },
      { id: 'nav-settings', group: 'Git', label: 'Ayarlar', icon: <Settings size={15} />, onSelect: () => navigate('/settings') },
      ...(projects ?? []).map((p) => ({
        id: `nav-project-${p.id}`,
        group: 'Git' as const,
        label: p.name,
        sublabel: `${p.key} panosu`,
        icon: (
          <span className="grid h-5 w-5 shrink-0 place-items-center rounded-md bg-accent-soft text-[9px] font-bold text-accent">
            {p.key.slice(0, 2)}
          </span>
        ),
        onSelect: () => navigate(`/projects/${p.id}`),
      })),
    ]

    const actions: Item[] = [
      {
        id: 'action-theme',
        group: 'Eylemler',
        label: theme === 'dark' ? 'Açık temaya geç' : 'Koyu temaya geç',
        icon: theme === 'dark' ? <Sun size={15} /> : <Moon size={15} />,
        onSelect: () => setTheme(theme === 'dark' ? 'light' : 'dark'),
      },
      { id: 'action-shortcuts', group: 'Eylemler', label: 'Klavye kısayolları', icon: <Keyboard size={15} />, onSelect: openShortcuts },
      { id: 'action-logout', group: 'Eylemler', label: 'Çıkış yap', icon: <LogOut size={15} />, onSelect: logout },
    ]

    const q = query.trim().toLocaleLowerCase('tr-TR')
    if (!q) return [...nav, ...actions]

    const matchedNav = nav.filter((i) => i.label.toLocaleLowerCase('tr-TR').includes(q) || i.sublabel?.toLocaleLowerCase('tr-TR').includes(q))
    const matchedActions = actions.filter((i) => i.label.toLocaleLowerCase('tr-TR').includes(q))
    const projectById = new Map((projects ?? []).map((p) => [p.id, p]))
    const matchedTasks: Item[] = (allTasks ?? [])
      .filter((t) => {
        const key = taskKey(projectById.get(t.projectId)?.key, t.taskNumber).toLocaleLowerCase('tr-TR')
        return t.title.toLocaleLowerCase('tr-TR').includes(q) || key.includes(q)
      })
      .slice(0, 8)
      .map((t) => ({
        id: `task-${t.id}`,
        group: 'Görevler' as const,
        label: t.title,
        sublabel: taskKey(projectById.get(t.projectId)?.key, t.taskNumber),
        icon: <StatusDot status={t.status} />,
        onSelect: () => navigate(`/projects/${t.projectId}?task=${t.id}`),
      }))

    return [...matchedNav, ...matchedTasks, ...matchedActions]
  }, [query, projects, allTasks, theme, navigate, setTheme, logout])

  function runActive() {
    const item = items[active]
    if (!item) return
    item.onSelect()
    setOpen(false)
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive((i) => (items.length === 0 ? 0 : (i + 1) % items.length))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((i) => (items.length === 0 ? 0 : (i - 1 + items.length) % items.length))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      runActive()
    }
  }

  const groups: Item['group'][] = ['Git', 'Görevler', 'Eylemler']

  return (
    <RD.Root open={open} onOpenChange={setOpen}>
      <AnimatePresence>
        {open && (
          <RD.Portal forceMount>
            <RD.Overlay asChild forceMount>
              <motion.div
                className="fixed inset-0 z-40 bg-black/40 backdrop-blur-sm"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
              />
            </RD.Overlay>
            <div className="pointer-events-none fixed inset-0 z-50 flex justify-center px-4 pt-[12vh]">
              <RD.Content asChild forceMount onKeyDown={onKeyDown}>
                <motion.div
                  className="pointer-events-auto h-fit max-h-[min(28rem,70vh)] w-full max-w-lg overflow-hidden rounded-2xl border border-border bg-surface shadow-2xl outline-none"
                  initial={{ opacity: 0, scale: 0.96, y: -8 }}
                  animate={{ opacity: 1, scale: 1, y: 0 }}
                  exit={{ opacity: 0, scale: 0.97, y: -6 }}
                  transition={{ type: 'spring', stiffness: 460, damping: 34 }}
                >
                  <RD.Title className="sr-only">Komut paleti</RD.Title>
                  <RD.Description className="sr-only">Sayfalar, projeler ve görevler arasında hızlı geçiş</RD.Description>
                  <div className="flex items-center gap-2.5 border-b border-border px-4">
                    <Search size={16} className="shrink-0 text-muted" />
                    <input
                      ref={inputRef}
                      value={query}
                      onChange={(e) => {
                        setQuery(e.target.value)
                        setActive(0)
                      }}
                      placeholder="Sayfa, proje veya görev ara…"
                      className="h-12 w-full bg-transparent text-sm outline-none placeholder:text-muted"
                    />
                    <kbd className="shrink-0 rounded-md border border-border px-1.5 py-0.5 text-[10px] text-muted">Esc</kbd>
                  </div>

                  <div className="max-h-80 overflow-y-auto p-1.5">
                    {items.length === 0 && <div className="px-3 py-8 text-center text-sm text-muted">Sonuç bulunamadı.</div>}
                    {groups.map((g) => {
                      const groupItems = items.filter((i) => i.group === g)
                      if (groupItems.length === 0) return null
                      return (
                        <div key={g} className="mb-1 last:mb-0">
                          <div className="px-2.5 pt-2 pb-1 text-[10px] font-medium tracking-wide text-muted uppercase">{g}</div>
                          {groupItems.map((item) => {
                            const index = items.indexOf(item)
                            const isActive = index === active
                            return (
                              <button
                                key={item.id}
                                onMouseEnter={() => setActive(index)}
                                onClick={() => {
                                  item.onSelect()
                                  setOpen(false)
                                }}
                                className={cn(
                                  'flex w-full cursor-pointer items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-sm outline-none',
                                  isActive ? 'bg-accent-soft text-accent' : 'text-fg hover:bg-surface-2',
                                )}
                              >
                                <span className="shrink-0">{item.icon}</span>
                                <span className="min-w-0 flex-1 truncate">{item.label}</span>
                                {item.sublabel && <span className="shrink-0 font-mono text-xs text-muted">{item.sublabel}</span>}
                                {isActive && <CornerDownLeft size={13} className="shrink-0" />}
                              </button>
                            )
                          })}
                        </div>
                      )
                    })}
                  </div>
                </motion.div>
              </RD.Content>
            </div>
          </RD.Portal>
        )}
      </AnimatePresence>
    </RD.Root>
  )
}
