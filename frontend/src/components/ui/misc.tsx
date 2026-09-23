import type { ReactNode } from 'react'
import { motion } from 'motion/react'
import type { TaskStatus } from '@/lib/types'
import { STATUS_META } from '@/lib/status'
import { cn } from '@/lib/cn'

export function StatusDot({ status, className }: { status: TaskStatus; className?: string }) {
  return <span className={cn('inline-block h-2 w-2 shrink-0 rounded-full', STATUS_META[status].dot, className)} />
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn('animate-pulse rounded-xl bg-surface-2', className)} />
}

export function EmptyState({
  icon,
  title,
  text,
  action,
}: {
  icon: ReactNode
  title: string
  text: string
  action?: ReactNode
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      className="flex flex-col items-center justify-center rounded-2xl border border-dashed border-border px-6 py-14 text-center"
    >
      <div className="mb-4 grid h-12 w-12 place-items-center rounded-2xl bg-accent-soft text-accent">{icon}</div>
      <h3 className="font-semibold">{title}</h3>
      <p className="mt-1 max-w-sm text-sm text-muted">{text}</p>
      {action && <div className="mt-5">{action}</div>}
    </motion.div>
  )
}

/** Sayfa giris animasyonu: route degisiminde icerik hafifce yukari kayarak belirir. */
export function Page({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -6 }}
      transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
      className={cn('mx-auto w-full max-w-7xl px-4 py-6 sm:px-8 sm:py-8', className)}
    >
      {children}
    </motion.div>
  )
}
