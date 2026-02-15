'use client'

import { useEffect, useRef } from 'react'
import { usePathname, useSearchParams } from 'next/navigation'

const TRACK_URL = (process.env.NEXT_PUBLIC_VPS_URL || 'https://api.sfweb.app') + '/api/analytics/track'

function sendEvent(payload: Record<string, string | null>) {
  const body = JSON.stringify(payload)
  // Prefer sendBeacon (fire-and-forget, survives navigation)
  if (typeof navigator !== 'undefined' && navigator.sendBeacon) {
    const blob = new Blob([body], { type: 'application/json' })
    navigator.sendBeacon(TRACK_URL, blob)
  } else {
    fetch(TRACK_URL, { method: 'POST', body, headers: { 'Content-Type': 'application/json' }, keepalive: true }).catch(() => {})
  }
}

export function trackDownloadClick(platform: 'macos' | 'android') {
  sendEvent({
    eventType: 'download_click',
    pagePath: typeof window !== 'undefined' ? window.location.pathname : null,
    referrer: typeof document !== 'undefined' ? document.referrer || null : null,
    downloadPlatform: platform,
  })
}

export default function AnalyticsTracker() {
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const lastTrackedPath = useRef<string | null>(null)

  useEffect(() => {
    // Skip admin pages and deduplicate
    if (pathname.startsWith('/admin')) return
    if (pathname === lastTrackedPath.current) return
    lastTrackedPath.current = pathname

    sendEvent({
      eventType: 'page_view',
      pagePath: pathname,
      referrer: document.referrer || null,
      utmSource: searchParams.get('utm_source'),
      utmMedium: searchParams.get('utm_medium'),
      utmCampaign: searchParams.get('utm_campaign'),
    })
  }, [pathname, searchParams])

  // Zero DOM nodes
  return null
}
