'use client'

import React from 'react'
import { AlertTriangle, RefreshCw, Home } from 'lucide-react'

interface ErrorBoundaryProps {
  children: React.ReactNode
  fallback?: React.ComponentType<{ error: Error; reset: () => void }>
}

interface ErrorBoundaryState {
  error: Error | null
}

/**
 * Error Boundary Component
 *
 * Catches JavaScript errors anywhere in the child component tree,
 * logs those errors, and displays a fallback UI instead of crashing.
 *
 * @example
 * ```tsx
 * <ErrorBoundary fallback={CustomErrorFallback}>
 *   <App />
 * </ErrorBoundary>
 * ```
 */
export class ErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props)
    this.state = { error: null }
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    // Update state so the next render will show the fallback UI
    return { error }
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    // Log error details for debugging
    console.error('[ErrorBoundary] Caught error:', error)
    console.error('[ErrorBoundary] Error info:', errorInfo)

    // You can also log to an error reporting service here
    // Example: Sentry.captureException(error, { extra: errorInfo })
  }

  reset = () => {
    this.setState({ error: null })
  }

  render() {
    if (this.state.error) {
      const FallbackComponent = this.props.fallback || DefaultErrorFallback
      return <FallbackComponent error={this.state.error} reset={this.reset} />
    }

    return this.props.children
  }
}

/**
 * Default Error Fallback UI
 *
 * Shown when an error is caught by the Error Boundary.
 * Provides user-friendly error message and recovery options.
 */
function DefaultErrorFallback({ error, reset }: { error: Error; reset: () => void }) {
  const handleReload = () => {
    window.location.reload()
  }

  const handleGoHome = () => {
    window.location.href = '/'
  }

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900 flex items-center justify-center p-4">
      <div className="max-w-md w-full bg-white dark:bg-gray-800 rounded-lg shadow-xl p-6">
        {/* Error Icon */}
        <div className="flex justify-center mb-4">
          <div className="w-16 h-16 rounded-full bg-red-100 dark:bg-red-900/30 flex items-center justify-center">
            <AlertTriangle className="w-8 h-8 text-red-600 dark:text-red-400" />
          </div>
        </div>

        {/* Error Title */}
        <h2 className="text-xl font-semibold text-center text-gray-900 dark:text-white mb-2">
          Something went wrong
        </h2>

        {/* Error Description */}
        <p className="text-sm text-center text-gray-600 dark:text-gray-400 mb-4">
          The application encountered an unexpected error. This has been logged and we'll look into it.
        </p>

        {/* Error Details (Collapsible) */}
        <details className="mb-4">
          <summary className="text-sm font-medium text-gray-700 dark:text-gray-300 cursor-pointer hover:text-gray-900 dark:hover:text-white">
            Error Details
          </summary>
          <div className="mt-2 p-3 bg-gray-100 dark:bg-gray-900 rounded-lg overflow-auto max-h-40">
            <pre className="text-xs text-red-600 dark:text-red-400 whitespace-pre-wrap break-words">
              {error.message}
            </pre>
            {error.stack && (
              <pre className="text-xs text-gray-600 dark:text-gray-400 mt-2 whitespace-pre-wrap break-words">
                {error.stack}
              </pre>
            )}
          </div>
        </details>

        {/* Action Buttons */}
        <div className="space-y-2">
          <button
            onClick={reset}
            className="w-full flex items-center justify-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg transition-colors"
          >
            <RefreshCw className="w-4 h-4" />
            Try Again
          </button>

          <button
            onClick={handleReload}
            className="w-full flex items-center justify-center gap-2 px-4 py-2 bg-gray-200 dark:bg-gray-700 hover:bg-gray-300 dark:hover:bg-gray-600 text-gray-900 dark:text-white rounded-lg transition-colors"
          >
            <RefreshCw className="w-4 h-4" />
            Reload Page
          </button>

          <button
            onClick={handleGoHome}
            className="w-full flex items-center justify-center gap-2 px-4 py-2 border border-gray-300 dark:border-gray-600 hover:bg-gray-50 dark:hover:bg-gray-700 text-gray-700 dark:text-gray-300 rounded-lg transition-colors"
          >
            <Home className="w-4 h-4" />
            Go to Home
          </button>
        </div>

        {/* Help Text */}
        <p className="text-xs text-center text-gray-500 dark:text-gray-400 mt-4">
          If this problem persists, please contact support or check our status page.
        </p>
      </div>
    </div>
  )
}
