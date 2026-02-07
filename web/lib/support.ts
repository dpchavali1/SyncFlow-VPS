/**
 * Support utilities for SyncFlow Web
 * Emails include [SyncFlow Web] tag in subject for identification
 */

const SUPPORT_EMAIL = process.env.NEXT_PUBLIC_SUPPORT_EMAIL || 'syncflow.contact@gmail.com'
const SUBJECT_PREFIX = '[SyncFlow Web]'
const APP_VERSION = process.env.NEXT_PUBLIC_APP_VERSION || '1.0.0'

/**
 * Get browser and OS information for support emails
 */
function getDeviceInfo(): string {
  if (typeof window === 'undefined') return 'Server-side render'

  const ua = navigator.userAgent
  let browser = 'Unknown Browser'
  let os = 'Unknown OS'

  // Detect browser
  if (ua.includes('Firefox')) browser = 'Firefox'
  else if (ua.includes('Chrome')) browser = 'Chrome'
  else if (ua.includes('Safari')) browser = 'Safari'
  else if (ua.includes('Edge')) browser = 'Edge'

  // Detect OS
  if (ua.includes('Windows')) os = 'Windows'
  else if (ua.includes('Mac')) os = 'macOS'
  else if (ua.includes('Linux')) os = 'Linux'
  else if (ua.includes('Android')) os = 'Android'
  else if (ua.includes('iOS')) os = 'iOS'

  return `${browser} on ${os}`
}

/**
 * Open support email with pre-filled information
 */
export function openSupportEmail(): void {
  const subject = encodeURIComponent(`${SUBJECT_PREFIX} Support Request - v${APP_VERSION}`)
  const body = encodeURIComponent(`Please describe your issue:



---
Device Information (please don't delete):
App Version: ${APP_VERSION}
Platform: Web
Browser: ${getDeviceInfo()}
URL: ${typeof window !== 'undefined' ? window.location.href : 'N/A'}
Timestamp: ${new Date().toISOString()}
`)

  window.open(`mailto:${SUPPORT_EMAIL}?subject=${subject}&body=${body}`, '_blank')
}

/**
 * Open bug report email
 */
export function openBugReport(errorMessage?: string): void {
  const subject = encodeURIComponent(`${SUBJECT_PREFIX} Bug Report - v${APP_VERSION}`)
  const body = encodeURIComponent(`Please describe the bug:


${errorMessage ? `\nError Details:\n${errorMessage}\n` : ''}
---
Device Information (please don't delete):
App Version: ${APP_VERSION}
Platform: Web
Browser: ${getDeviceInfo()}
URL: ${typeof window !== 'undefined' ? window.location.href : 'N/A'}
Timestamp: ${new Date().toISOString()}
`)

  window.open(`mailto:${SUPPORT_EMAIL}?subject=${subject}&body=${body}`, '_blank')
}

/**
 * Open feature request email
 */
export function openFeatureRequest(): void {
  const subject = encodeURIComponent(`${SUBJECT_PREFIX} Feature Request - v${APP_VERSION}`)
  const body = encodeURIComponent(`Feature Request
---------------

Please describe the feature you'd like to see:



---
App Version: ${APP_VERSION}
Platform: Web
`)

  window.open(`mailto:${SUPPORT_EMAIL}?subject=${subject}&body=${body}`, '_blank')
}

/**
 * Get support email address
 */
export function getSupportEmail(): string {
  return SUPPORT_EMAIL
}

/**
 * Get mailto URL for custom implementations
 */
export function getSupportMailtoUrl(): string {
  const subject = encodeURIComponent(`${SUBJECT_PREFIX} Support Request - v${APP_VERSION}`)
  return `mailto:${SUPPORT_EMAIL}?subject=${subject}`
}
