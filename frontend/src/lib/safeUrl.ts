import { defaultUrlTransform } from 'react-markdown'

/** Mutlak, host'lu https URL mı? Ayrıştırma hatası → false. */
export function isHttpsUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname !== ''
  } catch {
    return false
  }
}

const ALLOWED_SCHEMES = new Set(['http', 'https', 'mailto'])

/** react-markdown `urlTransform`: önce varsayılan dönüşüm, sonra şema izin listesi (göreli serbest). */
export function markdownUrlTransform(url: string): string {
  const safe = defaultUrlTransform(url)
  if (!safe) return ''
  const match = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(safe)
  if (!match) return safe
  return ALLOWED_SCHEMES.has(match[1].toLowerCase()) ? safe : ''
}
