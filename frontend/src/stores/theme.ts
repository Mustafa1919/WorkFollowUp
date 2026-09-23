import { create } from 'zustand'

export type Theme = 'light' | 'dark'

const KEY = 'wf-theme'

function initial(): Theme {
  const current = document.documentElement.dataset.theme
  return current === 'dark' ? 'dark' : 'light'
}

function apply(theme: Theme) {
  const root = document.documentElement
  // View Transitions API varsa tema gecisi dairesel bir "reveal" ile yapilir (bkz. ThemeToggle).
  root.dataset.theme = theme
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'dark' ? '#0b0b12' : '#f7f7fb')
  try {
    localStorage.setItem(KEY, theme)
  } catch {
    /* gizli pencere: tercih yalniz bu oturumda gecerli */
  }
}

interface ThemeState {
  theme: Theme
  setTheme: (theme: Theme) => void
}

export const useTheme = create<ThemeState>((set) => ({
  theme: initial(),
  setTheme: (theme) => {
    apply(theme)
    set({ theme })
  },
}))
