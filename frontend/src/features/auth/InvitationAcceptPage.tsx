import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { motion } from 'motion/react'
import { ArrowRight, CheckCircle2, MailWarning, XCircle } from 'lucide-react'
import { toast } from 'sonner'
import { useAcceptInvitation, useInvitationPreview } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { useSession } from '@/stores/session'
import { Button } from '@/components/ui/Button'
import { ThemeToggle } from '@/components/ThemeToggle'
import { Logo } from './AuthPage'

const ROLE_LABEL: Record<string, string> = {
  WORKSPACE_ADMIN: 'Yönetici',
  MANAGER: 'Yönetmen',
  DEVELOPER: 'Geliştirici',
  VIEWER: 'İzleyici',
}

const STATUS_MESSAGE: Record<string, string> = {
  ACCEPTED: 'Bu davet daha önce kabul edilmiş.',
  REVOKED: 'Bu davet iptal edilmiş.',
  EXPIRED: 'Bu davetin süresi dolmuş. Workspace yöneticisinden yenisini iste.',
}

/**
 * Token oturum durumundan BAGIMSIZ calisir (router.tsx'te GuestOnly/AuthGate DISINDA) — eski bir
 * e-postadaki linke giris yapmamis biri de, halihazirda baska bir hesapla giris yapmis biri de
 * girebilir. Gercek eslesme kontrolu (davet e-postasi == hesap e-postasi) sunucudadir.
 */
export function InvitationAcceptPage() {
  const { token = '' } = useParams()
  const navigate = useNavigate()
  const { data: preview, isLoading, isError } = useInvitationPreview(token)
  const accept = useAcceptInvitation()
  const [accepting, setAccepting] = useState(false)
  const accessToken = useSession((s) => s.accessToken)

  async function handleAccept() {
    setAccepting(true)
    try {
      const res = await accept.mutateAsync(token)
      useSession.getState().setWorkspace(res.workspaceId)
      toast.success(`${res.workspaceName} workspace'ine katıldın`)
      navigate('/', { replace: true })
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setAccepting(false)
    }
  }

  function goToAuth(mode: 'login' | 'register') {
    if (preview) useSession.getState().setEmail(preview.email)
    navigate(`/${mode}`, { state: { from: `/invitations/${token}` } })
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
        <div className="mt-6 rounded-2xl border border-border bg-surface p-6 text-center">
          {isLoading ? (
            <p className="text-sm text-muted">Davet kontrol ediliyor…</p>
          ) : isError || !preview ? (
            <div className="flex flex-col items-center gap-3">
              <XCircle size={28} className="text-st-todo" />
              <p className="text-sm text-muted">Bağlantı geçersiz.</p>
            </div>
          ) : preview.status !== 'PENDING' ? (
            <div className="flex flex-col items-center gap-3">
              <MailWarning size={28} className="text-st-review" />
              <p className="text-sm text-muted">{STATUS_MESSAGE[preview.status] ?? 'Bu davet artık geçerli değil.'}</p>
            </div>
          ) : (
            <div className="flex flex-col items-center gap-4">
              <CheckCircle2 size={28} className="text-accent" />
              <div>
                <h1 className="text-lg font-semibold tracking-tight">{preview.workspaceName}</h1>
                <p className="mt-1 text-sm text-muted">
                  <span className="font-medium text-fg">{preview.email}</span> adresi{' '}
                  <span className="font-medium text-fg">{ROLE_LABEL[preview.role] ?? preview.role}</span> rolüyle davet edildi.
                </p>
              </div>
              {accessToken ? (
                <Button onClick={handleAccept} loading={accepting} className="w-full justify-center">
                  Daveti kabul et <ArrowRight size={16} />
                </Button>
              ) : (
                <div className="w-full space-y-2">
                  <Button onClick={() => goToAuth('login')} className="w-full justify-center">
                    Giriş yap ve katıl
                  </Button>
                  <Button variant="outline" onClick={() => goToAuth('register')} className="w-full justify-center">
                    Hesap oluştur ve katıl
                  </Button>
                </div>
              )}
            </div>
          )}
        </div>
        <p className="text-center text-sm text-muted">
          <Link to="/" className="font-medium text-accent hover:underline">
            Ana sayfaya dön
          </Link>
        </p>
      </motion.div>
    </div>
  )
}
