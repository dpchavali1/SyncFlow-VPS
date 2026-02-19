'use client'

/**
 * Skeleton loader components for loading states
 * Provides visual feedback while data is being fetched
 * Uses shimmer gradient sweep for premium feel
 */

export function ConversationListSkeleton() {
  return (
    <div className="space-y-1 px-2 pt-2" role="status" aria-label="Loading conversations">
      {Array.from({ length: 8 }).map((_, i) => (
        <div
          key={i}
          className="flex items-center gap-3 px-3 py-3 rounded-xl"
        >
          {/* Avatar skeleton */}
          <div className="flex-shrink-0 w-11 h-11 bg-gray-200/60 dark:bg-gray-700/40 rounded-xl skeleton-shimmer" />

          {/* Content skeleton */}
          <div className="flex-1 min-w-0 space-y-2">
            {/* Contact name */}
            <div
              className="h-4 bg-gray-200/60 dark:bg-gray-700/40 rounded-lg skeleton-shimmer"
              style={{ width: `${55 + (i * 7) % 35}%` }}
            />

            {/* Last message preview */}
            <div
              className="h-3 bg-gray-200/40 dark:bg-gray-700/30 rounded-lg skeleton-shimmer"
              style={{ width: `${65 + (i * 11) % 25}%` }}
            />
          </div>

          {/* Timestamp skeleton */}
          <div className="flex-shrink-0 w-10 h-3 bg-gray-200/40 dark:bg-gray-700/30 rounded-lg skeleton-shimmer" />
        </div>
      ))}
    </div>
  )
}

export function MessageListSkeleton() {
  return (
    <div className="space-y-3 px-6 py-4" role="status" aria-label="Loading messages">
      {Array.from({ length: 8 }).map((_, i) => {
        // Alternate between sent and received messages
        const isSent = i % 3 === 0

        return (
          <div
            key={i}
            className={`flex ${
              isSent ? 'justify-end' : 'justify-start'
            }`}
          >
            {/* Message bubble */}
            <div
              className={`max-w-[65%] space-y-2 px-4 py-3 ${
                isSent
                  ? 'rounded-2xl rounded-br-md bg-blue-500/10 dark:bg-blue-500/10'
                  : 'rounded-2xl rounded-bl-md bg-gray-200/40 dark:bg-gray-700/30'
              }`}
            >
              {/* Message text lines */}
              <div
                className={`h-3 rounded-lg skeleton-shimmer ${
                  isSent
                    ? 'bg-blue-200/50 dark:bg-blue-800/30'
                    : 'bg-gray-200/60 dark:bg-gray-600/30'
                }`}
                style={{ width: `${100 + (i * 23) % 150}px` }}
              />
              {i % 2 === 0 && (
                <div
                  className={`h-3 rounded-lg skeleton-shimmer ${
                    isSent
                      ? 'bg-blue-200/50 dark:bg-blue-800/30'
                      : 'bg-gray-200/60 dark:bg-gray-600/30'
                  }`}
                  style={{ width: `${80 + (i * 17) % 100}px` }}
                />
              )}
            </div>
          </div>
        )
      })}
    </div>
  )
}

export function ContactsListSkeleton() {
  return (
    <div className="grid gap-3 p-4" role="status" aria-label="Loading contacts">
      {Array.from({ length: 12 }).map((_, i) => (
        <div
          key={i}
          className="flex items-center gap-3 p-4 glass-panel rounded-2xl"
        >
          {/* Avatar */}
          <div className="flex-shrink-0 w-11 h-11 bg-gray-200/60 dark:bg-gray-700/40 rounded-xl skeleton-shimmer" />

          {/* Contact info */}
          <div className="flex-1 min-w-0 space-y-2">
            <div
              className="h-4 bg-gray-200/60 dark:bg-gray-700/40 rounded-lg skeleton-shimmer"
              style={{ width: `${45 + (i * 9) % 35}%` }}
            />
            <div
              className="h-3 bg-gray-200/40 dark:bg-gray-700/30 rounded-lg skeleton-shimmer"
              style={{ width: `${35 + (i * 7) % 25}%` }}
            />
          </div>
        </div>
      ))}
    </div>
  )
}

export function SettingsSkeleton() {
  return (
    <div className="space-y-4 p-6" role="status" aria-label="Loading settings">
      {Array.from({ length: 4 }).map((_, sectionIndex) => (
        <div
          key={sectionIndex}
          className="glass-panel rounded-2xl p-6"
        >
          {/* Section title */}
          <div
            className="h-5 bg-gray-200/60 dark:bg-gray-700/40 rounded-lg mb-5 skeleton-shimmer"
            style={{ width: `${25 + (sectionIndex * 13) % 25}%` }}
          />

          {/* Settings items */}
          <div className="space-y-5">
            {Array.from({ length: 2 + (sectionIndex % 3) }).map((_, i) => (
              <div key={i} className="flex items-center justify-between">
                <div className="flex-1 space-y-2">
                  <div
                    className="h-4 bg-gray-200/60 dark:bg-gray-700/40 rounded-lg skeleton-shimmer"
                    style={{ width: `${35 + (i * 11) % 35}%` }}
                  />
                  <div
                    className="h-3 bg-gray-200/40 dark:bg-gray-700/30 rounded-lg skeleton-shimmer"
                    style={{ width: `${55 + (i * 7) % 25}%` }}
                  />
                </div>
                <div className="w-12 h-6 bg-gray-200/60 dark:bg-gray-700/40 rounded-full skeleton-shimmer" />
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
    <div className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center z-50" role="status">
      <div className="glass-elevated rounded-2xl p-8 shadow-xl flex flex-col items-center gap-4">
        <Spinner size="lg" className="text-blue-500" />
        <p className="text-lg font-medium text-gray-900 dark:text-white">{message}</p>
      </div>
    </div>
  )
}
