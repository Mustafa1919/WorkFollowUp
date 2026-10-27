// Yorum govdesi `@[userId]` token'i olarak saklanir (ADR-0009, backend MentionParser). Bu dosya
// token <-> gorunur ad cevirilerini saf fonksiyonlarla yapar.

export type MentionMember = { userId: string; fullName: string | null; email: string }

export const MENTION_TOKEN = /@\[([0-9a-fA-F-]{36})\]/g

/** Gosterim: token -> "@Ad Soyad" (uye yoksa "@Eski üye"). Markdown kalinligini cagiran ekler. */
export function withMentionNames(body: string, nameOf: (id: string) => string | undefined): string {
  return body.replace(MENTION_TOKEN, (_m, id: string) => `@${nameOf(id) ?? 'Eski üye'}`)
}

const displayName = (m: MentionMember) => (m.fullName && m.fullName.trim() !== '' ? m.fullName.trim() : m.email)
const lower = (s: string) => s.toLocaleLowerCase('tr')

/** Ayirt edici gorunur ad: ad workspace'te tekse "@Ad Soyad", degilse "@Ad Soyad (eposta-oneki)". */
export function mentionLabel(member: MentionMember, all: MentionMember[]): string {
  const name = displayName(member)
  const dup = all.some((o) => o.userId !== member.userId && lower(displayName(o)) === lower(name))
  return dup ? `@${name} (${member.email.split('@')[0]})` : `@${name}`
}

/** Duzenleme acilisi: token'lari gorunur ada cevirir, gorunur ad -> userId eslemesini dondurur. */
export function toEditable(body: string, members: MentionMember[]): { text: string; map: Map<string, string> } {
  const byId = new Map(members.map((m) => [m.userId, m]))
  const map = new Map<string, string>()
  const unknown = new Map<string, string>() // uuid -> etiket
  const text = body.replace(MENTION_TOKEN, (_m, id: string) => {
    const member = byId.get(id)
    if (member) {
      const label = mentionLabel(member, members)
      map.set(label, member.userId)
      return label
    }
    let label = unknown.get(id)
    if (!label) {
      label = unknown.size === 0 ? '@Eski üye' : `@Eski üye (${unknown.size + 1})`
      unknown.set(id, label)
      map.set(label, id)
    }
    return label
  })
  return { text, map }
}

/** Gonderim: map'teki gorunur adlari (en uzun once) token'a cevirir. Haritada olmayan "@..." metni oldugu gibi kalir. */
export function toStorage(text: string, map: Map<string, string>): string {
  if (map.size === 0) return text
  const labels = [...map.keys()].sort((a, b) => b.length - a.length)
  const escaped = labels.map((l) => l.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  const re = new RegExp(`(${escaped.join('|')})(?![\\p{L}\\p{N}_])`, 'gu')
  return text.replace(re, (m) => {
    const id = map.get(m)
    return id ? `@[${id}]` : m
  })
}

/** Arama parcasi: tam token'lari ada cevirir; ts_headline'in kestigi yarim token parcalarini
 *  (basta "...-uuid-parcasi]" ya da sonda "@[uuid-parcasi") "@…" ile degistirir. */
export function cleanSnippet(snippet: string, nameOf: (id: string) => string | undefined): string {
  let s = withMentionNames(snippet, nameOf)
  // Sonda kesilmis: "@[" + kismi uuid (\u0001/\u0002 vurgu isaretleyicileri araya girebilir)
  // eslint-disable-next-line no-control-regex -- ts_headline isaretleyicileri bilerek kontrol karakteri
  s = s.replace(/@\[[0-9a-fA-F\u0001\u0002-]*$/, '@…')
  // Basta kesilmis: uuid'in son parcalari + "]"
  // eslint-disable-next-line no-control-regex -- ayni gerekce
  s = s.replace(/^[0-9a-fA-F\u0001\u0002]*(?:-[0-9a-fA-F\u0001\u0002]+)+\]/, '@…')
  return s
}

/** Imlecin solundaki acik "@sorgu" parcasini bulur (satir basi ya da bosluktan sonra). */
export function findMentionQuery(text: string, caret: number): { start: number; query: string } | null {
  const before = text.slice(0, caret)
  const m = /(^|\s)@([^\s@]*)$/.exec(before)
  if (!m) return null
  return { start: before.length - m[2].length - 1, query: m[2] }
}

/** Secili aralige gorunur mention adi ekler; onunde bosluk yoksa bosluk koyar (Etiketle butonu). */
export function insertLabel(value: string, start: number, end: number, label: string): { next: string; caret: number } {
  const pad = start > 0 && !/\s/.test(value[start - 1]) ? ' ' : ''
  const ins = `${pad}${label} `
  return { next: value.slice(0, start) + ins + value.slice(end), caret: start + ins.length }
}

/** Oneri suzme: ad ve e-posta, buyuk/kucuk harf duyarsiz (tr), en fazla `limit` oge. */
export function filterMembers<T extends MentionMember>(members: T[], query: string, limit = 8): T[] {
  const q = lower(query)
  return members
    .filter((m) => q === '' || lower(displayName(m)).includes(q) || lower(m.email).includes(q))
    .slice(0, limit)
}
