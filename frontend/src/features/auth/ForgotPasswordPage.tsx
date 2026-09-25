import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { motion } from 'motion/react'
import { ArrowRight, MailCheck } from 'lucide-react'
import { toast } from 'sonner'
import { api, errorMessage } from '@/lib/api'
import { Button } from '@/components/ui/Button'
import { Field, Input } from '@/components/ui/Input'
import { ThemeToggle } from '@/components/ThemeToggle'
import { Logo } from './AuthPage'

/** "Şifremi unuttum": AuthService.requestPasswordReset — her durumda AYNI genel yanıt (enumeration korumasi). */
export function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const [loading, setLoading] = useState(false)
  const [sent, setSent] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setLoading(true)
    try {
      await api.post('/api/v1/auth/password-reset/request', { email })
      setSent(true)
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
        {sent ? (
          <div className="mt-6 flex flex-col items-center gap-3 rounded-2xl border border-border bg-surface p-6 text-center">
            <MailCheck size={28} className="text-accent" />
            <p className="text-sm text-muted">
              E-posta adresin kayıtlıysa parola sıfırlama bağlantısını gönderdik. 30 dakika içinde kullanılmalı.
            </p>
            <Link to="/login" className="text-sm font-medium text-accent hover:underline">
              Giriş sayfasına dön
            </Link>
          </div>
        ) : (
          <form onSubmit={submit} className="mt-6 space-y-4">
            <div>
              <h1 className="text-xl font-semibold tracking-tight">Parolanı mı unuttun?</h1>
              <p className="mt-1 text-sm text-muted">E-posta adresini gir, sana bir sıfırlama bağlantısı gönderelim.</p>
            </div>
            <Field label="E-posta" id="email">
              <Input id="email" type="email" required autoFocus value={email} onChange={(e) => setEmail(e.target.value)} />
            </Field>
            <Button type="submit" loading={loading} className="w-full justify-center">
              Sıfırlama bağlantısı gönder <ArrowRight size={16} />
            </Button>
            <p className="text-center text-sm text-muted">
              <Link to="/login" className="font-medium text-accent hover:underline">
                Giriş sayfasına dön
              </Link>
            </p>
          </form>
        )}
      </motion.div>
    </div>
  )
}
