import { create } from 'zustand'

/**
 * Access token YALNIZ bellekte tutulur (XSS ile localStorage'dan calinamasin). Sayfa yenilenince
 * HttpOnly refresh cookie'si ile /auth/refresh cagrilip yeniden alinir (bkz. lib/api.ts).
 * Secili workspace ve e-posta tercih oldugu icin localStorage'da.
 */
const WS_KEY = 'wf-workspace'
const EMAIL_KEY = 'wf-email'

function read(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function write(key: string, value: string | null) {
  try {
    if (value === null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch {
    /* yoksay */
  }
}

interface SessionState {
  accessToken: string | null
  email: string | null
  workspaceId: string | null
  /** Ilk acilista refresh denemesi bitene kadar false. */
  bootstrapped: boolean
  setToken: (token: string | null) => void
  setEmail: (email: string | null) => void
  setWorkspace: (id: string | null) => void
  setBootstrapped: () => void
  clear: () => void
}

export const useSession = create<SessionState>((set) => ({
  accessToken: null,
  email: read(EMAIL_KEY),
  workspaceId: read(WS_KEY),
  bootstrapped: false,
  setToken: (accessToken) => set({ accessToken }),
  setEmail: (email) => {
    write(EMAIL_KEY, email)
    set({ email })
  },
  setWorkspace: (workspaceId) => {
    write(WS_KEY, workspaceId)
    set({ workspaceId })
  },
  setBootstrapped: () => set({ bootstrapped: true }),
  clear: () => {
    write(EMAIL_KEY, null)
    set({ accessToken: null, email: null })
  },
}))
