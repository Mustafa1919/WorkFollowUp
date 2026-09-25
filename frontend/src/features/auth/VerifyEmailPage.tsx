import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { motion } from 'motion/react'
import { CheckCircle2, XCircle, Loader2 } from 'lucide-react'
import { api, errorMessage } from '@/lib/api'
import { ThemeToggle } from '@/components/ThemeToggle'
import { Logo } from './AuthPage'

type Status = 'checking' | 'done' | 'failed'

/** {@code /verify-email?token=} — AuthController.verifyEmail. */
export function VerifyEmailPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const [status, setStatus] = useState<Status>(token ? 'checking' : 'failed')
  const [error, setError] = useState('')

  useEffect(() => {
    if (!token) return
    api
      .get('/api/v1/auth/verify-email', { params: { token } })
      .then(() => setStatus('done'))
      .catch((err) => {
        setError(errorMessage(err))
        setStatus('failed')
      })
  }, [token])

  return (
    <div className="grid min-h-screen place-items-center px-4">
      <div className="absolute top-4 right-4">
        <ThemeToggle />
      </div>
      <motion.div initial={{ opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }} className="w-full max-w-sm space-y-6 text-center">
        <div className="flex justify-center">
          <Logo />
        </div>
        <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-surface p-6">
          {status === 'checking' && <Loader2 size={28} className="animate-spin text-accent" />}
          {status === 'done' && <CheckCircle2 size={28} className="text-st-done" />}
          {status === 'failed' && <XCircle size={28} className="text-danger" />}
          <p className="text-sm text-muted">
            {status === 'checking' && 'E-posta adresin doğrulanıyor...'}
            {status === 'done' && 'E-posta adresin doğrulandı.'}
            {status === 'failed' && (error || 'Bağlantı geçersiz veya süresi dolmuş.')}
          </p>
          <Link to="/login" className="text-sm font-medium text-accent hover:underline">
            Giriş sayfasına dön
          </Link>
        </div>
      </motion.div>
    </div>
  )
}
