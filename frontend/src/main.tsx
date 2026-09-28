import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import './index.css'
import { router } from './router'
import { ThemedToaster } from './components/ThemedToaster'
import { ErrorBoundary } from './components/ErrorBoundary'
import { reportError } from './lib/telemetry'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, retry: 1, refetchOnWindowFocus: true },
  },
})

// React render disi hatalar (senkron script hatalari + yakalanmamis Promise redleri) -- render
// hatalari ErrorBoundary.componentDidCatch'te ayrica yakalanir.
window.addEventListener('error', (event) => {
  reportError('WindowError', event.message, window.location.pathname)
})
window.addEventListener('unhandledrejection', (event) => {
  reportError('UnhandledRejection', String(event.reason), window.location.pathname)
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
        <ThemedToaster />
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>,
)
