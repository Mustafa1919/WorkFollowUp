import { format, formatDistanceToNow, parseISO } from 'date-fns'
import { tr } from 'date-fns/locale'

/** Backend'in `LocalDate` bicimi (saat dilimsiz). Yerel gunu kullanir, UTC'ye cevirmez. */
export const iso = (d: Date) => format(d, 'yyyy-MM-dd')

export const fromIso = (s: string) => parseISO(s)

export const fmt = (d: Date | string, pattern: string) =>
  format(typeof d === 'string' ? parseISO(d) : d, pattern, { locale: tr })

/** Kullanicinin yerel "bugun"u; backend gecmis gune tarih vermeyi reddeder (is saat dilimi). */
export const todayIso = () => iso(new Date())

/** "5 dakika önce" gibi bagil zaman; Inbox bildirim listesi icin. */
export const fromNow = (d: string) =>
  formatDistanceToNow(parseISO(d), { locale: tr, addSuffix: true })
