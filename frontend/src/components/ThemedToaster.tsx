import { Toaster } from 'sonner'
import { useTheme } from '@/stores/theme'

export function ThemedToaster() {
  const theme = useTheme((s) => s.theme)
  return <Toaster theme={theme} position="bottom-right" richColors closeButton />
}
