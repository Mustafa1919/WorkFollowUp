import { cn } from '@/lib/cn'

/** Isim/e-postadan sabit bir renk: ayni kisi her yerde ayni renkte gorunur. */
const HUES = [210, 262, 330, 20, 150, 45, 185, 290]

function hueFor(seed: string): number {
  let hash = 0
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) | 0
  return HUES[Math.abs(hash) % HUES.length]
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}

export function Avatar({
  name,
  seed,
  size = 20,
  className,
}: {
  name: string
  /** Renk icin kararlilik anahtari (genelde userId). */
  seed: string
  size?: number
  className?: string
}) {
  const hue = hueFor(seed)
  return (
    <span
      title={name}
      className={cn('inline-grid shrink-0 place-items-center rounded-full font-semibold text-white select-none', className)}
      style={{
        width: size,
        height: size,
        fontSize: Math.max(9, Math.round(size * 0.42)),
        backgroundColor: `hsl(${hue} 55% 48%)`,
      }}
    >
      {initials(name)}
    </span>
  )
}
