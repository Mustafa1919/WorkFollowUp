import { cn } from '@/lib/cn'

/** Yavasca hareket eden bulanik renk lekeleri (dekoratif, pointer-events yok). */
export function Aurora({ className }: { className?: string }) {
  return (
    <div aria-hidden className={cn('pointer-events-none absolute inset-0 overflow-hidden', className)}>
      <div className="absolute -top-1/3 -left-1/4 h-[70%] w-[70%] animate-aurora rounded-full bg-accent/25 blur-3xl" />
      <div
        className="absolute -right-1/4 -bottom-1/3 h-[70%] w-[70%] animate-aurora rounded-full bg-fuchsia-500/20 blur-3xl"
        style={{ animationDelay: '-6s' }}
      />
      <div
        className="absolute top-1/4 left-1/3 h-[45%] w-[45%] animate-aurora rounded-full bg-sky-400/15 blur-3xl"
        style={{ animationDelay: '-12s' }}
      />
      <div className="absolute inset-0 bg-[radial-gradient(var(--border)_1px,transparent_1px)] [background-size:22px_22px] opacity-60 [mask-image:radial-gradient(ellipse_at_center,black,transparent_75%)]" />
    </div>
  )
}
