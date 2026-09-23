import * as RD from '@radix-ui/react-dialog'
import { AnimatePresence, motion } from 'motion/react'
import { X } from 'lucide-react'
import type { ReactNode } from 'react'

interface DialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  children: ReactNode
}

/** Radix (erisilebilirlik, focus trap, Esc) + Motion (giris/cikis animasyonu). */
export function Dialog({ open, onOpenChange, title, description, children }: DialogProps) {
  return (
    <RD.Root open={open} onOpenChange={onOpenChange}>
      <AnimatePresence>
        {open && (
          <RD.Portal forceMount>
            <RD.Overlay asChild forceMount>
              <motion.div
                className="fixed inset-0 z-40 bg-black/40 backdrop-blur-sm"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
              />
            </RD.Overlay>
            <div className="pointer-events-none fixed inset-0 z-50 grid place-items-center p-4">
              <RD.Content asChild forceMount>
                <motion.div
                  className="pointer-events-auto max-h-[calc(100vh-32px)] w-full max-w-md overflow-y-auto rounded-2xl border border-border bg-surface p-6 shadow-2xl outline-none"
                  initial={{ opacity: 0, scale: 0.95, y: 12 }}
                  animate={{ opacity: 1, scale: 1, y: 0 }}
                  exit={{ opacity: 0, scale: 0.96, y: 8 }}
                  transition={{ type: 'spring', stiffness: 420, damping: 32 }}
                >
                  <div className="mb-5 flex items-start justify-between gap-4">
                    <div>
                      <RD.Title className="text-lg font-semibold">{title}</RD.Title>
                      <RD.Description className={description ? 'mt-1 text-sm text-muted' : 'sr-only'}>
                        {description ?? title}
                      </RD.Description>
                    </div>
                    <RD.Close className="cursor-pointer rounded-lg p-1 text-muted transition-colors hover:bg-surface-2 hover:text-fg">
                      <X size={18} />
                    </RD.Close>
                  </div>
                  {children}
                </motion.div>
              </RD.Content>
            </div>
          </RD.Portal>
        )}
      </AnimatePresence>
    </RD.Root>
  )
}
