import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { motion } from 'motion/react'
import { ArrowRight, CheckCircle2 } from 'lucide-react'
import { toast } from 'sonner'
import { api, errorMessage } from '@/lib/api'
import { Button } from '@/components/ui/Button'
import { Field, Input } from '@/components/ui/Input'
import { ThemeToggle } from '@/components/ThemeToggle'
import { Logo } from './AuthPage'

/** {@code /reset-password?token=} — AuthService.confirmPasswordReset (30 dakika gecerli token). */
export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const token = params.get('token') ?? ''
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [done, setDone] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setLoading(true)
    try {
      await api.post('/api/v1/auth/password-reset/confirm', { token, newPassword: password })
      setDone(true)
      setTimeout(() => navigate('/login', { replace: true }), 2000)
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="grid min-h-screen place-items-center px-4">
      <div className="absolute top-4 right-4">
        <ThemeToggle />
      </div>
      <motion.div
        initial={{ opacity: 0, y: 14 }}
        animate={{ opacity: 1, y: 0 }}
        className="w-full max-w-sm space-y-4"
      >
        <Logo />
        {!token ? (
          <div className="mt-6 rounded-2xl border border-border bg-surface p-6 text-center text-sm text-muted">
            Bağlantı geçersiz. <Link to="/forgot-password" className="font-medium text-accent hover:underline">Yeniden iste</Link>
          </div>
        ) : done ? (
          <div className="mt-6 flex flex-col items-center gap-3 rounded-2xl border border-border bg-surface p-6 text-center">
            <CheckCircle2 size={28} className="text-st-done" />
            <p className="text-sm text-muted">Parolan güncellendi, giriş sayfasına yönlendiriliyorsun.</p>
          </div>
        ) : (
          <form onSubmit={submit} className="mt-6 space-y-4">
            <div>
              <h1 className="text-xl font-semibold tracking-tight">Yeni parola belirle</h1>
              <p className="mt-1 text-sm text-muted">Bu bağlantı yalnızca bir kez kullanılabilir.</p>
            </div>
            <Field label="Yeni parola" id="password">
              <Input
                id="password"
                type="password"
                required
                minLength={12}
                placeholder="En az 12 karakter"
                autoFocus
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="new-password"
              />
            </Field>
            <Button type="submit" loading={loading} className="w-full justify-center">
              Parolayı güncelle <ArrowRight size={16} />
            </Button>
          </form>
        )}
      </motion.div>
    </div>
  )
}
