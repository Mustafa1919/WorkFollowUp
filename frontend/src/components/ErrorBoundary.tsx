import { Component, type ErrorInfo, type ReactNode } from 'react'
import { reportError } from '@/lib/telemetry'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
}

/**
 * React render hatalari window.onerror'a DUSMEZ -- bu yuzden ayri bir mekanizma (componentDidCatch)
 * gerekir. window.onerror/unhandledrejection kaydi main.tsx'te (script hatalari + yakalanmamis
 * Promise redleri icin, bu boundary'nin kapsami disinda).
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    reportError('ReactRenderError', `${error.message}\n${info.componentStack ?? ''}`, window.location.pathname)
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="grid min-h-screen place-items-center px-4 text-center">
          <div>
            <h1 className="text-lg font-semibold">Bir şeyler ters gitti</h1>
            <p className="mt-1 text-sm text-muted">Sayfayı yenilemeyi deneyebilirsin.</p>
            <button
              onClick={() => window.location.reload()}
              className="mt-4 cursor-pointer rounded-xl border border-border bg-surface px-4 py-2 text-sm hover:bg-surface-2"
            >
              Yenile
            </button>
          </div>
        </div>
      )
    }
    return this.props.children
  }
}
