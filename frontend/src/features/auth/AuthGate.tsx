import { useEffect } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { motion } from 'motion/react'
import { refreshAccessToken } from '@/lib/api'
import { useSession } from '@/stores/session'

/** Uygulama acilisinda bir kez: HttpOnly refresh cookie'si varsa access token'i sessizce yenile. */
function useBootstrap() {
  const bootstrapped = useSession((s) => s.bootstrapped)
  useEffect(() => {
    if (bootstrapped) return
    refreshAccessToken().finally(() => useSession.getState().setBootstrapped())
  }, [bootstrapped])
  return bootstrapped
}

function Splash() {
  return (
    <div className="grid min-h-screen place-items-center">
      <motion.div
        className="h-10 w-10 rounded-2xl bg-gradient-to-br from-accent to-fuchsia-500"
        animate={{ rotate: [0, 90, 180, 270, 360], borderRadius: ['30%', '50%', '30%'] }}
        transition={{ duration: 1.6, repeat: Infinity, ease: 'easeInOut' }}
      />
    </div>
  )
}

export function AuthGate() {
  const ready = useBootstrap()
  const token = useSession((s) => s.accessToken)
  const location = useLocation()
  if (!ready) return <Splash />
  if (!token) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  return <Outlet />
}

export function GuestOnly() {
  const ready = useBootstrap()
  const token = useSession((s) => s.accessToken)
  if (!ready) return <Splash />
  if (token) return <Navigate to="/" replace />
  return <Outlet />
}
