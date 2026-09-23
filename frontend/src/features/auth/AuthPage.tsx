import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { AnimatePresence, motion } from 'motion/react'
import { ArrowRight, CalendarDays, KanbanSquare, LineChart } from 'lucide-react'
import { toast } from 'sonner'
import { api, errorMessage } from '@/lib/api'
import { useSession } from '@/stores/session'
import { Button } from '@/components/ui/Button'
import { Field, Input } from '@/components/ui/Input'
import { ThemeToggle } from '@/components/ThemeToggle'
import { Aurora } from '@/components/Aurora'

const FEATURES = [
  { icon: KanbanSquare, text: 'Sürükle-bırak Kanban panosu' },
  { icon: CalendarDays, text: 'Görevleri takvimde günlere yerleştir' },
  { icon: LineChart, text: 'Velocity, throughput ve cycle time' },
]

export function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState(useSession.getState().email ?? '')
  const [password, setPassword] = useState('')
  const [fullName, setFullName] = useState('')
  const [loading, setLoading] = useState(false)
  const isLogin = mode === 'login'

  async function submit(e: FormEvent) {
    e.preventDefault()
    setLoading(true)
    try {
      if (!isLogin) await api.post('/api/v1/auth/register', { email, password, fullName })
      const res = await api.post<{ accessToken: string }>('/api/v1/auth/login', { email, password })
      const session = useSession.getState()
      session.setEmail(email)
      session.setToken(res.data.accessToken)
      const from = (location.state as { from?: string } | null)?.from
      navigate(from && from !== '/login' ? from : '/', { replace: true })
      if (!isLogin) toast.success('Hesabın oluşturuldu, hoş geldin!')
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="grid min-h-screen lg:grid-cols-[1.1fr_1fr]">
      {/* Sol: marka paneli (yalniz genis ekran) */}
      <div className="relative hidden overflow-hidden border-r border-border bg-surface lg:block">
        <Aurora />
        <div className="relative flex h-full flex-col justify-between p-12">
          <Logo />
          <div>
            <motion.h1
              initial={{ opacity: 0, y: 16 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.6, ease: [0.22, 1, 0.36, 1] }}
              className="max-w-md text-4xl leading-tight font-semibold tracking-tight"
            >
              İşini takip et,{' '}
              <span className="bg-gradient-to-r from-accent to-fuchsia-500 bg-clip-text text-transparent">
                akışını gör.
              </span>
            </motion.h1>
            <ul className="mt-8 space-y-3">
              {FEATURES.map((f, i) => (
                <motion.li
                  key={f.text}
                  initial={{ opacity: 0, x: -12 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: 0.25 + i * 0.1 }}
                  className="flex items-center gap-3 text-muted"
                >
                  <span className="grid h-8 w-8 place-items-center rounded-lg bg-accent-soft text-accent">
                    <f.icon size={16} />
                  </span>
                  {f.text}
                </motion.li>
              ))}
            </ul>
          </div>
          <p className="text-xs text-muted">© {new Date().getFullYear()} WorkFollowUp</p>
        </div>
      </div>

      {/* Sag: form */}
      <div className="relative flex flex-col px-4 py-6 sm:px-10">
        <div className="flex items-center justify-between">
          <span className="lg:invisible">
            <Logo />
          </span>
          <ThemeToggle />
        </div>
        <div className="flex flex-1 items-center justify-center">
          <AnimatePresence mode="wait">
            <motion.form
              key={mode}
              onSubmit={submit}
              initial={{ opacity: 0, y: 14 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -10 }}
              transition={{ duration: 0.25 }}
              className="w-full max-w-sm space-y-4"
            >
              <div className="mb-8">
                <h2 className="text-2xl font-semibold tracking-tight">{isLogin ? 'Tekrar hoş geldin' : 'Hesap oluştur'}</h2>
                <p className="mt-1 text-sm text-muted">
                  {isLogin ? 'Devam etmek için giriş yap.' : 'Birkaç saniyede başla.'}
                </p>
              </div>
              {!isLogin && (
                <Field label="Ad Soyad" id="fullName">
                  <Input id="fullName" required value={fullName} onChange={(e) => setFullName(e.target.value)} autoComplete="name" />
                </Field>
              )}
              <Field label="E-posta" id="email">
                <Input id="email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" />
              </Field>
              <Field label="Parola" id="password">
                <Input
                  id="password"
                  type="password"
                  required
                  minLength={isLogin ? undefined : 12}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete={isLogin ? 'current-password' : 'new-password'}
                  placeholder={isLogin ? undefined : 'En az 12 karakter'}
                />
              </Field>
              <Button type="submit" loading={loading} className="w-full justify-center">
                {isLogin ? 'Giriş yap' : 'Kayıt ol'} <ArrowRight size={16} />
              </Button>
              <p className="pt-2 text-center text-sm text-muted">
                {isLogin ? 'Hesabın yok mu?' : 'Zaten hesabın var mı?'}{' '}
                <Link to={isLogin ? '/register' : '/login'} className="font-medium text-accent hover:underline">
                  {isLogin ? 'Kayıt ol' : 'Giriş yap'}
                </Link>
              </p>
            </motion.form>
          </AnimatePresence>
        </div>
      </div>
    </div>
  )
}

export function Logo() {
  return (
    <div className="flex items-center gap-2.5 font-semibold tracking-tight">
      <span className="grid h-8 w-8 place-items-center rounded-xl bg-gradient-to-br from-accent to-fuchsia-500 text-sm text-white shadow-lg shadow-accent/30">
        W
      </span>
      WorkFollowUp
    </div>
  )
}
