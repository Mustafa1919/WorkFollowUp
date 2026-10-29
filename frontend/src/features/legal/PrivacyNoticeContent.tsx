import { PRIVACY_NOTICE_SECTIONS, PRIVACY_NOTICE_VERSION } from './privacyNotice'

export function PrivacyNoticeContent() {
  return (
    <div className="space-y-5">
      <p className="text-sm text-muted">Sürüm: {PRIVACY_NOTICE_VERSION}</p>
      {PRIVACY_NOTICE_SECTIONS.map((section) => (
        <section key={section.heading} className="space-y-2">
          <h2 className="font-semibold">{section.heading}</h2>
          {section.paragraphs.map((paragraph, index) => (
            <p key={index} className="text-sm text-muted">{paragraph}</p>
          ))}
        </section>
      ))}
    </div>
  )
}
