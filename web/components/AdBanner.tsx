'use client'

import { useEffect, useState, useRef } from 'react'
import { getUsageSummary } from '@/lib/firebase'
import { useAppStore } from '@/lib/store'

// Check if VPS mode is enabled
const isVPSMode = () => {
  if (typeof window === 'undefined') return false
  return localStorage.getItem('useVPSMode') === 'true' ||
         localStorage.getItem('vps_access_token') !== null ||
         process.env.NEXT_PUBLIC_USE_VPS === 'true'
}

declare global {
  interface Window {
    adsbygoogle: unknown[]
  }
}

/**
 * Ad banner component that shows Google AdSense ads to free/trial users.
 * Premium subscribers don't see ads.
 *
 * Publisher ID: ca-pub-4962910048695842
 * Ad Slot: 6045234757 (sfwebad)
 */
export default function AdBanner() {
  const { userId } = useAppStore()
  const [showAd, setShowAd] = useState(false)
  const [isLoading, setIsLoading] = useState(true)
  const adRef = useRef<HTMLModElement>(null)
  const adInitialized = useRef(false)

  useEffect(() => {
    const checkPremiumStatus = async () => {
      if (!userId) {
        setShowAd(true) // Show ads to unauthenticated users
        setIsLoading(false)
        return
      }

      // Skip Firebase calls in VPS mode
      if (isVPSMode()) {
        setShowAd(true) // Show ads in VPS mode (no premium check available yet)
        setIsLoading(false)
        return
      }

      try {
        const usage = await getUsageSummary(userId)
        // Only show ads to non-premium users
        setShowAd(!usage.isPaid)
      } catch (error) {
        console.error('Error checking premium status:', error)
        setShowAd(true) // Show ads on error (conservative approach)
      }
      setIsLoading(false)
    }

    checkPremiumStatus()
  }, [userId])

  // Initialize AdSense ad
  useEffect(() => {
    if (!showAd || isLoading || adInitialized.current) return

    // Small delay to ensure DOM is ready
    const timer = setTimeout(() => {
      try {
        if (typeof window !== 'undefined' && adRef.current) {
          (window.adsbygoogle = window.adsbygoogle || []).push({})
          adInitialized.current = true
        }
      } catch (error) {
        console.error('AdSense error:', error)
      }
    }, 100)

    return () => clearTimeout(timer)
  }, [showAd, isLoading])

  if (isLoading || !showAd) {
    return null
  }

  return (
    <div className="w-full bg-gray-100 dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700 flex-shrink-0 h-[60px]">
      <ins
        ref={adRef}
        className="adsbygoogle"
        style={{ display: 'block', height: '60px' }}
        data-ad-client="ca-pub-4962910048695842"
        data-ad-slot="6045234757"
        data-ad-format="horizontal"
        data-full-width-responsive="true"
      />
    </div>
  )
}

/**
 * Compact ad banner for inline placement
 */
export function AdBannerCompact() {
  const { userId } = useAppStore()
  const [showAd, setShowAd] = useState(false)
  const adRef = useRef<HTMLModElement>(null)
  const adInitialized = useRef(false)

  useEffect(() => {
    const checkPremiumStatus = async () => {
      if (!userId) {
        setShowAd(true)
        return
      }

      try {
        const usage = await getUsageSummary(userId)
        setShowAd(!usage.isPaid)
      } catch {
        setShowAd(true)
      }
    }

    checkPremiumStatus()
  }, [userId])

  // Initialize AdSense ad
  useEffect(() => {
    if (!showAd || adInitialized.current) return

    const timer = setTimeout(() => {
      try {
        if (typeof window !== 'undefined' && adRef.current) {
          (window.adsbygoogle = window.adsbygoogle || []).push({})
          adInitialized.current = true
        }
      } catch (error) {
        console.error('AdSense error:', error)
      }
    }, 100)

    return () => clearTimeout(timer)
  }, [showAd])

  if (!showAd) {
    return null
  }

  return (
    <div className="w-full p-2 flex-shrink-0">
      <ins
        ref={adRef}
        className="adsbygoogle"
        style={{ display: 'block', minHeight: '50px' }}
        data-ad-client="ca-pub-4962910048695842"
        data-ad-slot="6045234757"
        data-ad-format="horizontal"
        data-full-width-responsive="true"
      />
    </div>
  )
}
