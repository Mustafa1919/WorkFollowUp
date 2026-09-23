import { format, parseISO } from 'date-fns'
import { tr } from 'date-fns/locale'

/** Backend'in `LocalDate` bicimi (saat dilimsiz). Yerel gunu kullanir, UTC'ye cevirmez. */
export const iso = (d: Date) => format(d, 'yyyy-MM-dd')

export const fromIso = (s: string) => parseISO(s)

export const fmt = (d: Date | string, pattern: string) =>
  format(typeof d === 'string' ? parseISO(d) : d, pattern, { locale: tr })

/** Kullanicinin yerel "bugun"u; backend gecmis gune tarih vermeyi reddeder (is saat dilimi). */
export const todayIso = () => iso(new Date())
