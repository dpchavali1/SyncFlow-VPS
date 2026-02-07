// Web Notification Sync Functions - BASIC VERSION WITH SECURITY SAFEGUARDS
import { getDatabase, ref, onValue, remove, query, orderByChild, limitToLast } from 'firebase/database'
import { getAuth } from 'firebase/auth'

// Check if VPS mode is enabled - skip Firebase in VPS mode
function isVPSMode(): boolean {
  if (typeof window === 'undefined') return false
  if (localStorage.getItem('useVPSMode') === 'true') return true
  if (process.env.NEXT_PUBLIC_VPS_URL) return true
  if (localStorage.getItem('vps_access_token')) return true
  return true // Default to VPS mode
}

// Lazy initialize Firebase services (only when needed and not in VPS mode)
let _database: ReturnType<typeof getDatabase> | null = null
let _auth: ReturnType<typeof getAuth> | null = null

function getFirebaseDatabase() {
  if (isVPSMode()) return null
  if (!_database) _database = getDatabase()
  return _database
}

function getFirebaseAuth() {
  if (isVPSMode()) return null
  if (!_auth) _auth = getAuth()
  return _auth
}

// Legacy aliases for compatibility
const database = null as any // Lazy loaded via getFirebaseDatabase()
const auth = null as any // Lazy loaded via getFirebaseAuth()

export interface MirroredNotification {
  id: string
  packageName: string
  title: string  // Only title, no message content for security
  timestamp: number
  iconBase64?: string
  priority: number
  category?: string
  // Removed: text, actions (reply/dismiss) - too risky for web
}

// Security settings for basic version
const SECURITY_CONFIG = {
  titleOnly: true,              // No message content
  noActions: true,              // No reply/dismiss actions
  sessionTimeout: 24 * 60 * 60 * 1000, // 24 hours max
  requireExplicitConsent: true, // Must opt-in explicitly
  auditAllAccess: true,         // Log all web notification access
  activeTabOnly: true,          // Only when web app tab is active
  noBackgroundSync: true,       // No service worker background sync
  autoExpireMs: 30000,          // Auto-expire notifications after 30s
} as const

// Security audit logging
const auditLog = (action: string, details: any) => {
  if (!SECURITY_CONFIG.auditAllAccess) return

  const logEntry = {
    timestamp: Date.now(),
    action,
    userAgent: typeof window !== 'undefined' ? window.navigator.userAgent : 'unknown',
    sessionId: typeof window !== 'undefined' ? sessionStorage.getItem('syncflow_session') : 'unknown',
    details
  }

  console.log('[WebNotificationAudit]', logEntry)

  // In production, send to secure logging endpoint
  // await fetch('/api/audit-log', { method: 'POST', body: JSON.stringify(logEntry) })
}

// Request browser notification permission with security checks
export const requestNotificationPermission = async (): Promise<NotificationPermission> => {
  // Security check: Browser support
  if (!('Notification' in window)) {
    auditLog('permission_denied', { reason: 'browser_not_supported' })
    throw new Error('This browser does not support notifications')
  }

  // Security check: HTTPS required in production (allow HTTP in development)
  if (typeof window !== 'undefined') {
    const isProduction = window.location.hostname !== 'localhost' &&
                        !window.location.hostname.startsWith('127.0.0.1') &&
                        !window.location.hostname.startsWith('192.168.') &&
                        !window.location.hostname.includes('.local')

    if (isProduction && window.location.protocol !== 'https:') {
      auditLog('permission_denied', { reason: 'https_required_in_production', protocol: window.location.protocol })
      throw new Error('Web notifications require HTTPS for security in production')
    }
  }

  // Check existing permission
  if (Notification.permission === 'granted') {
    auditLog('permission_already_granted', {})
    return 'granted'
  }

  if (Notification.permission === 'denied') {
    auditLog('permission_previously_denied', {})
    throw new Error('Notification permission was previously denied. Reset in browser settings.')
  }

  // Request permission with security context
  auditLog('permission_request_started', {})
  const permission = await Notification.requestPermission()
  auditLog('permission_request_completed', { result: permission })

  return permission
}

// Check if user has explicitly consented to web notifications
export const hasWebNotificationConsent = (): boolean => {
  if (typeof window === 'undefined') return false

  const consent = localStorage.getItem('syncflow_web_notifications_consent')
  if (!consent) return false

  try {
    const consentData = JSON.parse(consent)
    const now = Date.now()

    // Check if consent is still valid (not expired)
    if (consentData.expiresAt && now > consentData.expiresAt) {
      auditLog('consent_expired', { expiredAt: consentData.expiresAt, now })
      revokeWebNotificationConsent()
      return false
    }

    // Check if consent version matches current security requirements
    if (consentData.version !== '1.0') {
      auditLog('consent_version_mismatch', { oldVersion: consentData.version, newVersion: '1.0' })
      revokeWebNotificationConsent()
      return false
    }

    return consentData.consented === true
  } catch (error) {
    auditLog('consent_parse_error', { error: error instanceof Error ? error.message : String(error) })
    revokeWebNotificationConsent()
    return false
  }
}

// Grant explicit consent for web notifications
export const grantWebNotificationConsent = (): void => {
  if (typeof window === 'undefined') return

  const consentData = {
    consented: true,
    grantedAt: Date.now(),
    expiresAt: Date.now() + SECURITY_CONFIG.sessionTimeout,
    version: '1.0',
    securityConfig: SECURITY_CONFIG
  }

  localStorage.setItem('syncflow_web_notifications_consent', JSON.stringify(consentData))
  auditLog('consent_granted', { expiresAt: consentData.expiresAt })
}

// Revoke consent for web notifications
export const revokeWebNotificationConsent = (): void => {
  if (typeof window === 'undefined') return

  localStorage.removeItem('syncflow_web_notifications_consent')
  auditLog('consent_revoked', {})
}

// Listen for mirrored notifications from Android with security checks
export const listenToMirroredNotifications = (
  userId: string,
  callback: (notification: MirroredNotification) => void
): (() => void) => {
  // Skip in VPS mode - notification sync uses Firebase
  if (isVPSMode()) {
    console.log('[NotificationSync] VPS mode - notification sync not available')
    return () => {}
  }

  // Security check: Must have explicit consent
  if (!hasWebNotificationConsent()) {
    auditLog('notification_sync_blocked', { reason: 'no_consent' })
    return () => {} // Return no-op unsubscribe function
  }

  // Security check: Must have browser permission
  if (typeof window !== 'undefined' && Notification.permission !== 'granted') {
    auditLog('notification_sync_blocked', { reason: 'no_browser_permission' })
    return () => {}
  }

  // Get lazy-loaded database
  const db = getFirebaseDatabase()
  if (!db) {
    console.log('[NotificationSync] Firebase database not available')
    return () => {}
  }

  // BANDWIDTH OPTIMIZATION: Limit to last 50 notifications to prevent unbounded downloads
  const notificationsRef = query(
    ref(db, `users/${userId}/mirrored_notifications`),
    orderByChild('timestamp'),
    limitToLast(50)
  )
  auditLog('notification_sync_started', { userId })

  return onValue(notificationsRef, (snapshot) => {
    const data = snapshot.val()
    if (data) {
      // Process new notifications with security filtering
      Object.entries(data).forEach(([key, value]: [string, any]) => {
        const notification = value as MirroredNotification

        // Security filter: Only allow title-only notifications
        if (!SECURITY_CONFIG.titleOnly) {
          auditLog('notification_filtered', { id: key, reason: 'content_policy' })
          return
        }

        // Security filter: Check for sensitive content in title
        if (notification.title.toLowerCase().includes('password') ||
            notification.title.toLowerCase().includes('otp') ||
            notification.title.toLowerCase().includes('code')) {
          auditLog('notification_filtered', { id: key, reason: 'sensitive_content' })
          return
        }

        auditLog('notification_processed', { id: key, title: notification.title })
        callback(notification)
      })
    }
  })
}

// Display notification in browser with security safeguards
export const showBrowserNotification = async (notification: MirroredNotification) => {
  try {
    // Security check: Must have consent
    if (!hasWebNotificationConsent()) {
      auditLog('browser_notification_blocked', { reason: 'no_consent' })
      return
    }

    // Security check: Must have browser permission
    const permission = await requestNotificationPermission()
    if (permission !== 'granted') {
      auditLog('browser_notification_blocked', { reason: 'no_permission' })
      return
    }

    // Security check: Active tab only (if enabled)
    if (SECURITY_CONFIG.activeTabOnly && typeof document !== 'undefined' && document.hidden) {
      auditLog('browser_notification_blocked', { reason: 'tab_not_active' })
      return
    }

    // Security enforcement: Title-only (no body text)
    const safeTitle = notification.title || 'New Notification'
    const safeBody = SECURITY_CONFIG.titleOnly ? '' : 'Notification received'

    // Create secure browser notification
    const browserNotification = new Notification(safeTitle, {
      body: safeBody,
      icon: notification.iconBase64 ? `data:image/png;base64,${notification.iconBase64}` : undefined,
      tag: `syncflow-${notification.packageName}-${notification.id}`,
      requireInteraction: false, // Always false for security (no interaction required)
      silent: false,
      // Security: Add timestamp and source info
      data: {
        source: 'syncflow_web',
        timestamp: Date.now(),
        packageName: notification.packageName,
        securityVersion: '1.0'
      }
    })

    auditLog('browser_notification_shown', {
      id: notification.id,
      title: safeTitle,
      packageName: notification.packageName
    })

    // Handle notification click (focus web app only)
    browserNotification.onclick = () => {
      auditLog('browser_notification_clicked', { id: notification.id })
      // Focus web app window only (no other actions allowed)
      if (typeof window !== 'undefined') {
        window.focus()
      }
      browserNotification.close()
    }

    // Security: Auto-expire notifications quickly
    const expireTime = SECURITY_CONFIG.autoExpireMs
    setTimeout(() => {
      browserNotification.close()
      auditLog('browser_notification_auto_expired', { id: notification.id, expireTime })
    }, expireTime)

  } catch (error) {
    auditLog('browser_notification_error', { error: error instanceof Error ? error.message : String(error) })
    console.error('Failed to show browser notification:', error)
  }
}

// Mark notification as read/dismissed
export const dismissMirroredNotification = async (
  userId: string,
  notificationId: string
) => {
  // Skip in VPS mode
  if (isVPSMode()) {
    console.log('[NotificationSync] VPS mode - dismiss not available')
    return
  }

  const db = getFirebaseDatabase()
  if (!db) {
    console.log('[NotificationSync] Firebase database not available')
    return
  }

  const notificationRef = ref(db, `users/${userId}/mirrored_notifications/${notificationId}`)
  await remove(notificationRef)
}