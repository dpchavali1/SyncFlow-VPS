'use client'

/**
 * Skeleton loader components for loading states
 * Provides visual feedback while data is being fetched
 */

export function ConversationListSkeleton() {
  return (
    <div className="space-y-0" role="status" aria-label="Loading conversations">
      {Array.from({ length: 10 }).map((_, i) => (
        <div
          key={i}
          className="flex items-start gap-3 p-4 border-b border-gray-100 dark:border-gray-700 animate-pulse"
        >
          {/* Avatar skeleton */}
          <div className="flex-shrink-0 w-12 h-12 bg-gray-200 dark:bg-gray-700 rounded-full" />

          {/* Content skeleton */}
          <div className="flex-1 min-w-0 space-y-2">
            {/* Contact name */}
            <div
              className="h-4 bg-gray-200 dark:bg-gray-700 rounded"
              style={{ width: `${60 + Math.random() * 30}%` }}
            />

            {/* Last message preview */}
            <div
              className="h-3 bg-gray-200 dark:bg-gray-700 rounded"
              style={{ width: `${70 + Math.random() * 20}%` }}
            />
          </div>

          {/* Timestamp skeleton */}
          <div className="flex-shrink-0 w-12 h-3 bg-gray-200 dark:bg-gray-700 rounded" />
        </div>
      ))}
    </div>
  )
}

export function MessageListSkeleton() {
  return (
    <div className="space-y-4 p-4" role="status" aria-label="Loading messages">
      {Array.from({ length: 8 }).map((_, i) => {
        // Alternate between sent and received messages
        const isSent = i % 3 === 0

        return (
          <div
            key={i}
            className={`flex items-start gap-2 animate-pulse ${
              isSent ? 'justify-end' : 'justify-start'
            }`}
          >
            {/* Received message avatar */}
            {!isSent && (
              <div className="flex-shrink-0 w-8 h-8 bg-gray-200 dark:bg-gray-700 rounded-full" />
            )}

            {/* Message bubble */}
            <div
              className={`max-w-[70%] space-y-2 p-3 rounded-lg ${
                isSent
                  ? 'bg-blue-100 dark:bg-blue-900/30'
                  : 'bg-gray-100 dark:bg-gray-700'
              }`}
            >
              {/* Message text lines */}
              <div
                className={`h-3 rounded ${
                  isSent
                    ? 'bg-blue-200 dark:bg-blue-800'
                    : 'bg-gray-200 dark:bg-gray-600'
                }`}
                style={{ width: `${100 + Math.random() * 150}px` }}
              />
              {Math.random() > 0.5 && (
                <div
                  className={`h-3 rounded ${
                    isSent
                      ? 'bg-blue-200 dark:bg-blue-800'
                      : 'bg-gray-200 dark:bg-gray-600'
                  }`}
                  style={{ width: `${80 + Math.random() * 100}px` }}
                />
              )}

              {/* Timestamp */}
              <div
                className={`h-2 rounded ${
                  isSent
                    ? 'bg-blue-200 dark:bg-blue-800'
                    : 'bg-gray-200 dark:bg-gray-600'
                }`}
                style={{ width: '40px' }}
              />
            </div>
          </div>
        )
      })}
    </div>
  )
}

export function ContactsListSkeleton() {
  return (
    <div className="grid gap-4 p-4" role="status" aria-label="Loading contacts">
      {Array.from({ length: 12 }).map((_, i) => (
        <div
          key={i}
          className="flex items-center gap-3 p-4 bg-white dark:bg-gray-800 rounded-lg border border-gray-200 dark:border-gray-700 animate-pulse"
        >
          {/* Avatar */}
          <div className="flex-shrink-0 w-12 h-12 bg-gray-200 dark:bg-gray-700 rounded-full" />

          {/* Contact info */}
          <div className="flex-1 min-w-0 space-y-2">
            <div
              className="h-4 bg-gray-200 dark:bg-gray-700 rounded"
              style={{ width: `${50 + Math.random() * 30}%` }}
            />
            <div
              className="h-3 bg-gray-200 dark:bg-gray-700 rounded"
              style={{ width: `${40 + Math.random() * 20}%` }}
            />
          </div>
        </div>
      ))}
    </div>
  )
}

export function SettingsSkeleton() {
  return (
    <div className="space-y-6 p-6" role="status" aria-label="Loading settings">
      {Array.from({ length: 4 }).map((_, sectionIndex) => (
        <div
          key={sectionIndex}
          className="bg-white dark:bg-gray-800 rounded-lg p-6 border border-gray-200 dark:border-gray-700 animate-pulse"
        >
          {/* Section title */}
          <div
            className="h-5 bg-gray-200 dark:bg-gray-700 rounded mb-4"
            style={{ width: `${30 + Math.random() * 20}%` }}
          />

          {/* Settings items */}
          <div className="space-y-4">
            {Array.from({ length: 2 + Math.floor(Math.random() * 3) }).map((_, i) => (
              <div key={i} className="flex items-center justify-between">
                <div className="flex-1 space-y-2">
                  <div
                    className="h-4 bg-gray-200 dark:bg-gray-700 rounded"
                    style={{ width: `${40 + Math.random() * 30}%` }}
                  />
                  <div
                    className="h-3 bg-gray-200 dark:bg-gray-700 rounded"
                    style={{ width: `${60 + Math.random() * 20}%` }}
                  />
                </div>
                <div className="w-12 h-6 bg-gray-200 dark:bg-gray-700 rounded-full" />
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

/**
 * Generic inline loading spinner
 * Used for buttons and inline actions
 */
export function Spinner({ size = 'sm', className = '' }: { size?: 'sm' | 'md' | 'lg'; className?: string }) {
  const sizeClasses = {
    sm: 'w-4 h-4',
    md: 'w-6 h-6',
    lg: 'w-8 h-8',
  }

  return (
    <svg
      className={`animate-spin ${sizeClasses[size]} ${className}`}
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
      aria-label="Loading"
    >
      <circle
        className="opacity-25"
        cx="12"
        cy="12"
        r="10"
        stroke="currentColor"
        strokeWidth="4"
      />
      <path
        className="opacity-75"
        fill="currentColor"
        d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
      />
    </svg>
  )
}

/**
 * Full-page loading overlay
 * Used for critical loading operations
 */
export function LoadingOverlay({ message = 'Loading...' }: { message?: string }) {
  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50" role="status">
      <div className="bg-white dark:bg-gray-800 rounded-lg p-8 shadow-xl flex flex-col items-center gap-4">
        <Spinner size="lg" />
        <p className="text-lg font-medium text-gray-900 dark:text-white">{message}</p>
      </div>
    </div>
  )
}
