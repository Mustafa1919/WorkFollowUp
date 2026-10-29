import { Link } from 'react-router-dom'
import { PrivacyNoticeContent } from './PrivacyNoticeContent'

export function PrivacyNoticePage() {
  return (
    <main className="mx-auto max-w-3xl space-y-6 px-6 py-12">
      <h1 className="text-2xl font-semibold">Kişisel Verilerin Korunması Aydınlatma Metni</h1>
      <PrivacyNoticeContent />
      <Link to="/" className="inline-block text-sm text-accent hover:underline">Geri dön</Link>
    </main>
  )
}
