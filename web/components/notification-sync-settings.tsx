'use client'

import { useNotificationSync } from '@/lib/hooks/use-notification-sync'
import { Bell, BellOff, AlertTriangle, CheckCircle, Shield, Clock, Eye } from 'lucide-react'

export const NotificationSyncSettings = () => {
  const {
    permissionStatus,
    isEnabled,
    hasConsent,
    error,
    sessionExpiry,
    timeUntilExpiry,
    requestPermission,
    enableSync,
    disableSync,
    isSupported,
    checkConsent
  } = useNotificationSync()

  // Debug information
  const debugInfo = {
    isSupported,
    permissionStatus,
    hasConsent,
    isEnabled,
    sessionExpiry: sessionExpiry ? new Date(sessionExpiry).toLocaleString() : null,
    timeUntilExpiry: timeUntilExpiry ? `${Math.floor(timeUntilExpiry / (1000 * 60))}m remaining` : null,
    protocol: typeof window !== 'undefined' ? window.location.protocol : 'unknown',
    hostname: typeof window !== 'undefined' ? window.location.hostname : 'unknown',
    userAgent: typeof window !== 'undefined' ? window.navigator.userAgent.substring(0, 50) + '...' : 'unknown'
  }

  // Format time until expiry
  const formatTimeUntilExpiry = (ms: number) => {
    const hours = Math.floor(ms / (1000 * 60 * 60))
    const minutes = Math.floor((ms % (1000 * 60 * 60)) / (1000 * 60))
    return `${hours}h ${minutes}m`
  }

  if (!isSupported) {
    return (
      <div className="bg-white dark:bg-gray-800 rounded-xl border border-gray-200 dark:border-gray-700 p-6">
        <div className="flex items-center gap-2 mb-2">
          <BellOff className="w-5 h-5" />
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white">Browser Notifications</h3>
        </div>
        <p className="text-sm text-gray-600 dark:text-gray-400 mb-4">
          Sync Android notifications to your browser (with security safeguards)
        </p>
        <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 rounded-lg p-4">
          <div className="flex items-center gap-2">
            <AlertTriangle className="h-4 w-4 text-yellow-600 dark:text-yellow-400" />
            <p className="text-sm text-yellow-800 dark:text-yellow-200">
              Your browser doesn't support notifications. Try using a modern browser like Chrome or Firefox.
            </p>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="bg-white dark:bg-gray-800 rounded-xl border border-gray-200 dark:border-gray-700 p-6">
      <div className="flex items-center gap-2 mb-2">
        <Shield className="w-5 h-5" />
        <h3 className="text-lg font-semibold text-gray-900 dark:text-white">Secure Browser Notifications</h3>
      </div>
        <p className="text-sm text-gray-600 dark:text-gray-400 mb-4">
          Sync Android notifications to your browser with built-in security safeguards.
          {!isEnabled && <span className="block text-xs mt-1">First-time setup will ask for security consent and browser permissions.</span>}
        </p>
      <div className="space-y-4">
        {/* Security Status Indicators */}
        <div className="flex flex-wrap gap-2">
          <span className="inline-flex items-center gap-1 px-2 py-1 text-xs border border-gray-200 dark:border-gray-700 rounded-md">
            <Eye className="w-3 h-3" />
            Title Only
          </span>
          <span className="inline-flex items-center gap-1 px-2 py-1 text-xs border border-gray-200 dark:border-gray-700 rounded-md">
            <Clock className="w-3 h-3" />
            30s Auto-Expire
          </span>
          <span className="inline-flex items-center gap-1 px-2 py-1 text-xs border border-gray-200 dark:border-gray-700 rounded-md">
            <Shield className="w-3 h-3" />
            Audit Logged
          </span>
        </div>

        {/* Consent Status */}
        <div className="flex items-center justify-between">
          <div className="space-y-1">
            <p className="text-sm font-medium">Security Consent</p>
            <div className="flex items-center gap-2">
              {hasConsent ? (
                <CheckCircle className="w-4 h-4 text-green-500" />
              ) : (
                <AlertTriangle className="w-4 h-4 text-orange-500" />
              )}
              <p className="text-sm text-muted-foreground">
                {hasConsent ? 'Granted' : 'Required for activation'}
              </p>
            </div>
            {timeUntilExpiry && hasConsent && (
              <p className="text-xs text-muted-foreground">
                Session expires in {formatTimeUntilExpiry(timeUntilExpiry)}
              </p>
            )}
          </div>
        </div>

        {/* Permission Status */}
        <div className="flex items-center justify-between">
          <div className="space-y-1">
            <p className="text-sm font-medium">Browser Permission</p>
            <div className="flex items-center gap-2">
              {permissionStatus === 'granted' && (
                <CheckCircle className="w-4 h-4 text-green-500" />
            )}
              <p className="text-sm text-muted-foreground capitalize">
                {permissionStatus === 'default' ? 'Not requested' : permissionStatus}
              </p>
            </div>
          </div>

          {permissionStatus !== 'granted' && (
            <button
              onClick={requestPermission}
              className="px-3 py-1 text-sm border border-gray-300 dark:border-gray-600 rounded-md hover:bg-gray-50 dark:hover:bg-gray-700"
            >
              Request Browser Permission
            </button>
          )}
        </div>

        {/* Sync Toggle */}
        <div className="flex items-center justify-between">
          <div className="space-y-1">
            <p className="text-sm font-medium">Notification Sync</p>
            <p className="text-sm text-muted-foreground">
              Show Android notifications in browser (secure mode)
              {!isEnabled && <span className="block text-xs mt-1 text-blue-600 dark:text-blue-400">Click to start setup process</span>}
            </p>
          </div>
          <div className="flex items-center gap-3">
            <button
              onClick={isEnabled ? disableSync : enableSync}
              disabled={false} // Always allow clicking to start the flow
              className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
                isEnabled ? 'bg-blue-600' : 'bg-gray-200 dark:bg-gray-700'
              } cursor-pointer`}
              title={
                isEnabled
                  ? 'Click to disable web notifications'
                  : 'Click to enable web notifications (will request consent and permissions)'
              }
            >
              <span
                className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                  isEnabled ? 'translate-x-6' : 'translate-x-1'
                }`}
              />
            </button>
            {(!hasConsent || permissionStatus !== 'granted') && (
              <span className="text-xs text-gray-500 dark:text-gray-400">
                {!hasConsent
                  ? 'Grant consent above first'
                  : 'Allow browser notifications first'
                }
              </span>
            )}
          </div>
        </div>

        {/* Error Display */}
        {error && (
          <div className="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg p-4">
            <div className="flex items-center gap-2">
              <AlertTriangle className="h-4 w-4 text-red-600 dark:text-red-400" />
              <p className="text-sm text-red-800 dark:text-red-200">{error}</p>
            </div>
          </div>
        )}

        {/* Security Warnings */}
        <div className="bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-lg p-4">
          <div className="flex items-center gap-2 mb-2">
            <Shield className="h-4 w-4 text-blue-600 dark:text-blue-400" />
            <p className="text-sm font-medium text-blue-800 dark:text-blue-200">Security Safeguards Active</p>
          </div>
          <ul className="text-sm text-blue-700 dark:text-blue-300 space-y-1 ml-6">
            <li>• Only notification titles are shown (no message content)</li>
            <li>• No reply or dismiss actions allowed</li>
            <li>• Notifications auto-expire after 30 seconds</li>
            <li>• All access is audited and logged</li>
            <li>• Requires explicit consent every 24 hours</li>
            <li>• Only works when this browser tab is active</li>
          </ul>
        </div>

        {/* Risk Warning */}
        <div className="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg p-4">
          <div className="flex items-center gap-2 mb-2">
            <AlertTriangle className="h-4 w-4 text-red-600 dark:text-red-400" />
            <p className="text-sm font-medium text-red-800 dark:text-red-200">⚠️ Security Notice</p>
          </div>
          <p className="text-sm text-red-700 dark:text-red-300">
            Web notifications are less secure than native notifications. Browser extensions may intercept notifications,
            and web sessions are more vulnerable to theft. Use this feature only if you understand and accept these risks.
          </p>
        </div>

        {/* Debug Information */}
        <details className="mt-4">
          <summary className="text-sm text-gray-600 dark:text-gray-400 cursor-pointer hover:text-gray-800 dark:hover:text-gray-200">
            🔧 Debug Information (Click to expand)
          </summary>
          <div className="mt-2 p-3 bg-gray-50 dark:bg-gray-900 rounded-lg">
            <pre className="text-xs text-gray-700 dark:text-gray-300 whitespace-pre-wrap">
              {JSON.stringify(debugInfo, null, 2)}
            </pre>
            <div className="mt-2 flex gap-2">
              <button
                onClick={checkConsent}
                className="px-2 py-1 text-xs bg-blue-600 text-white rounded hover:bg-blue-700"
              >
                Refresh Status
              </button>
              <button
                onClick={() => {
                  if (typeof window !== 'undefined') {
                    localStorage.removeItem('syncflow_web_notifications_consent')
                    checkConsent()
                    window.location.reload()
                  }
                }}
                className="px-2 py-1 text-xs bg-red-600 text-white rounded hover:bg-red-700"
              >
                Reset Consent
              </button>
            </div>
          </div>
        </details>
      </div>
    </div>
  )
}