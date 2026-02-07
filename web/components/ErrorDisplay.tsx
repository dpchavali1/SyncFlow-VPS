'use client'

import { AlertTriangle, XCircle, Info, RefreshCw } from 'lucide-react'
import { formatErrorForDisplay, type ErrorContext, type UserError } from '@/lib/errorMessages'

interface ErrorDisplayProps {
  error: any
  context?: ErrorContext
  onRetry?: () => void
  onDismiss?: () => void
  className?: string
}

/**
 * User-Friendly Error Display Component
 *
 * Shows errors with clear titles, actionable messages, and retry buttons
 */
export function ErrorDisplay({ error, context, onRetry, onDismiss, className = '' }: ErrorDisplayProps) {
  if (!error) return null

  const userError: UserError = formatErrorForDisplay(error, context)

  const Icon = userError.severity === 'error' ? XCircle : userError.severity === 'warning' ? AlertTriangle : Info

  const severityColors = {
    error: 'bg-red-50 dark:bg-red-900/20 border-red-200 dark:border-red-800',
    warning: 'bg-yellow-50 dark:bg-yellow-900/20 border-yellow-200 dark:border-yellow-800',
    info: 'bg-blue-50 dark:bg-blue-900/20 border-blue-200 dark:border-blue-800',
  }

  const iconColors = {
    error: 'text-red-600 dark:text-red-400',
    warning: 'text-yellow-600 dark:text-yellow-400',
    info: 'text-blue-600 dark:text-blue-400',
  }

  const buttonColors = {
    error: 'bg-red-600 hover:bg-red-700',
    warning: 'bg-yellow-600 hover:bg-yellow-700',
    info: 'bg-blue-600 hover:bg-blue-700',
  }

  return (
    <div className={`rounded-lg border p-4 ${severityColors[userError.severity]} ${className}`}>
      <div className="flex items-start gap-3">
        <Icon className={`w-5 h-5 mt-0.5 flex-shrink-0 ${iconColors[userError.severity]}`} />

        <div className="flex-1 min-w-0">
          <h3 className="font-semibold text-gray-900 dark:text-white mb-1">
            {userError.title}
          </h3>
          <p className="text-sm text-gray-700 dark:text-gray-300 mb-3">
            {userError.message}
          </p>

          <div className="flex gap-2">
            {userError.canRetry && onRetry && (
              <button
                onClick={onRetry}
                className={`px-4 py-2 text-white rounded-lg text-sm font-medium transition-colors flex items-center gap-2 ${buttonColors[userError.severity]}`}
              >
                <RefreshCw className="w-4 h-4" />
                {userError.actionButton || 'Retry'}
              </button>
            )}

            {userError.actionUrl && (
              <a
                href={userError.actionUrl}
                className={`px-4 py-2 text-white rounded-lg text-sm font-medium transition-colors ${buttonColors[userError.severity]}`}
              >
                {userError.actionButton || 'Go'}
              </a>
            )}

            {onDismiss && (
              <button
                onClick={onDismiss}
                className="px-4 py-2 text-gray-600 dark:text-gray-400 hover:text-gray-800 dark:hover:text-gray-200 rounded-lg text-sm font-medium transition-colors"
              >
                Dismiss
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * Inline Error Display (compact version)
 */
export function InlineError({ error, context, onRetry }: ErrorDisplayProps) {
  if (!error) return null

  const userError: UserError = formatErrorForDisplay(error, context)

  return (
    <div className="flex items-center gap-2 text-red-600 dark:text-red-400 text-sm">
      <XCircle className="w-4 h-4 flex-shrink-0" />
      <span>{userError.message}</span>
      {userError.canRetry && onRetry && (
        <button
          onClick={onRetry}
          className="underline hover:no-underline font-medium"
        >
          Retry
        </button>
      )}
    </div>
  )
}

/**
 * Toast-style Error Notification
 */
export function ErrorToast({ error, context, onDismiss }: ErrorDisplayProps) {
  if (!error) return null

  const userError: UserError = formatErrorForDisplay(error, context)

  const severityColors = {
    error: 'bg-red-600',
    warning: 'bg-yellow-600',
    info: 'bg-blue-600',
  }

  return (
    <div className={`fixed bottom-4 right-4 max-w-md ${severityColors[userError.severity]} text-white rounded-lg shadow-lg p-4 animate-slide-up z-50`}>
      <div className="flex items-start gap-3">
        <AlertTriangle className="w-5 h-5 flex-shrink-0" />
        <div className="flex-1">
          <h4 className="font-semibold mb-1">{userError.title}</h4>
          <p className="text-sm opacity-90">{userError.message}</p>
        </div>
        {onDismiss && (
          <button
            onClick={onDismiss}
            className="text-white/80 hover:text-white transition-colors"
          >
            <XCircle className="w-5 h-5" />
          </button>
        )}
      </div>
    </div>
  )
}
