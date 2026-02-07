'use client'

import { useEffect, useState, useCallback } from 'react'
import {
  requestNotificationPermission,
  listenToMirroredNotifications,
  showBrowserNotification,
  dismissMirroredNotification,
  MirroredNotification,
  hasWebNotificationConsent,
  grantWebNotificationConsent,
  revokeWebNotificationConsent
} from '@/lib/notification-sync'
import { getCurrentUserId } from '@/lib/firebase'

export const useNotificationSync = () => {
  const [permissionStatus, setPermissionStatus] = useState<NotificationPermission>('default')
  const [isEnabled, setIsEnabled] = useState(() => {
    // Restore enabled state from localStorage
    if (typeof window !== 'undefined') {
      const saved = localStorage.getItem('syncflow_web_notifications_enabled')
      return saved === 'true'
    }
    return false
  })
  const [hasConsent, setHasConsent] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sessionExpiry, setSessionExpiry] = useState<number | null>(null)

  // Check consent status
  const checkConsent = useCallback(() => {
    const consent = hasWebNotificationConsent()
    setHasConsent(consent)

    if (consent && typeof window !== 'undefined') {
      const consentData = localStorage.getItem('syncflow_web_notifications_consent')
      if (consentData) {
        try {
          const data = JSON.parse(consentData)
          setSessionExpiry(data.expiresAt)
        } catch {
          setSessionExpiry(null)
        }
      }
    }

    return consent
  }, [])

  // Request notification permission
  const requestPermission = async () => {
    try {
      const permission = await requestNotificationPermission()
      setPermissionStatus(permission)
      setError(null)
      return permission === 'granted'
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Permission request failed'
      setError(message)
      return false
    }
  }

  // Show security consent dialog
  const showSecurityConsent = useCallback(() => {
    return new Promise<boolean>((resolve) => {
      try {
        console.log('[WebNotification] Showing consent dialog...')
        const confirmed = window.confirm(
          '🔒 SECURITY NOTICE: Web Notifications\n\n' +
          'Web notifications are less secure than native notifications.\n\n' +
          'RISKS:\n' +
          '• Browser extensions can intercept notifications\n' +
          '• Web sessions are easier to steal than native apps\n' +
          '• Notifications auto-expire after 30 seconds\n' +
          '• Only notification titles are shown (no message content)\n' +
          '• No reply or dismiss actions allowed\n\n' +
          'This feature will:\n' +
          '• Only work when this tab is active\n' +
          '• Require re-consent every 24 hours\n' +
          '• Log all access for security\n\n' +
          'Do you want to enable web notifications with these security measures?'
        )
        console.log('[WebNotification] Consent dialog result:', confirmed)
        resolve(confirmed)
      } catch (error) {
        console.error('[WebNotification] Error showing consent dialog:', error)
        resolve(false)
      }
    })
  }, [])

  // Enable notification sync with security consent
  const enableSync = async () => {
    console.log('[WebNotification] Starting enable process...')
    try {
      setError(null)

      // Step 1: Check if we already have consent
      if (!hasConsent) {
        // Get explicit security consent
        console.log('[WebNotification] Showing security consent dialog...')
        const consented = await showSecurityConsent()
        console.log('[WebNotification] Security consent result:', consented)

        if (!consented) {
          const msg = 'Security consent required to enable web notifications. Please accept the security dialog to continue.'
          console.log('[WebNotification] Consent denied:', msg)
          setError(msg)
          return
        }

        // Grant web notification consent
        console.log('[WebNotification] Granting web notification consent...')
        grantWebNotificationConsent()
        setHasConsent(true)
      }

      // Step 2: Request browser permission if not already granted
      if (permissionStatus !== 'granted') {
        console.log('[WebNotification] Requesting browser permission...')
        const granted = await requestPermission()
        console.log('[WebNotification] Browser permission result:', granted)

        if (!granted) {
          console.log('[WebNotification] Browser permission denied')
          setError('Browser notification permission required. Please allow notifications in your browser.')
          return
        }
      }

      // Step 3: Enable sync
      console.log('[WebNotification] Enabling notification sync...')
      setIsEnabled(true)
      if (typeof window !== 'undefined') {
        localStorage.setItem('syncflow_web_notifications_enabled', 'true')
      }
      setError(null)
      console.log('[WebNotification] Successfully enabled!')

    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to enable notifications'
      console.error('[WebNotification] Error during enable process:', err)
      setError(message)
      revokeWebNotificationConsent()
      setHasConsent(false)
    }
  }

  // Disable notification sync and revoke consent
  const disableSync = () => {
    setIsEnabled(false)
    revokeWebNotificationConsent()
    setHasConsent(false)
    setSessionExpiry(null)
    setError(null)
    if (typeof window !== 'undefined') {
      localStorage.setItem('syncflow_web_notifications_enabled', 'false')
    }
  }

  // Check consent on mount and periodically
  useEffect(() => {
    checkConsent()

    // Check consent validity every minute
    const interval = setInterval(() => {
      const stillValid = checkConsent()
      if (!stillValid && isEnabled) {
        // Auto-disable if consent expired
        disableSync()
        setError('Web notification session expired for security. Please re-enable if needed.')
      }
    }, 60000) // Check every minute

    return () => clearInterval(interval)
  }, [checkConsent, isEnabled])

  // Listen for notifications when enabled and consented
  useEffect(() => {
    if (!isEnabled || !hasConsent) return

    const userId = getCurrentUserId()
    if (!userId) return

    const unsubscribe = listenToMirroredNotifications(userId, (notification) => {
      // Show browser notification with security measures
      showBrowserNotification(notification)

      // Auto-dismiss from Firebase after configured time
      setTimeout(() => {
        dismissMirroredNotification(userId, notification.id).catch((error) => {
          console.error('Failed to dismiss notification:', error)
        })
      }, 30000)
    })

    return unsubscribe
  }, [isEnabled, hasConsent])

  // Check initial permission status
  useEffect(() => {
    if (typeof window !== 'undefined' && 'Notification' in window) {
      setPermissionStatus(Notification.permission)
    }
  }, [])

  // Calculate time until session expiry
  const timeUntilExpiry = sessionExpiry ? Math.max(0, sessionExpiry - Date.now()) : null

  return {
    permissionStatus,
    isEnabled,
    hasConsent,
    error,
    sessionExpiry,
    timeUntilExpiry,
    requestPermission,
    enableSync,
    disableSync,
    isSupported: typeof window !== 'undefined' && 'Notification' in window,
    checkConsent
  }
}