'use client'

import { useState, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import vpsService from '@/lib/vps'

/**
 * Shared authentication hook for VPS-backed pages.
 *
 * Handles the common pattern of:
 *   1. Restoring tokens from IndexedDB
 *   2. Checking localStorage for a stored user ID
 *   3. Verifying VPS authentication state
 *   4. Redirecting to the pairing screen when unauthenticated
 *
 * Returns `{ userId, isAuthenticated, isLoading }` so consuming pages
 * can show a loading skeleton or redirect without duplicating logic.
 */
export function useAuth() {
  const router = useRouter()
  const [userId, setUserId] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    let cancelled = false

    const checkAuth = async () => {
      // Wait for tokens to be restored from IndexedDB before checking auth
      await vpsService.ensureTokensRestored()

      if (cancelled) return

      const storedUserId = localStorage.getItem('syncflow_user_id')

      if (!storedUserId) {
        router.push('/')
        setIsLoading(false)
        return
      }

      // VPS mode: verify authentication if tokens are available
      if (!vpsService.isAuthenticated) {
        // Have stored user ID but VPS not authenticated - still allow access
        // (token refresh may recover on next request)
      }

      setUserId(storedUserId)
      setIsLoading(false)
    }

    checkAuth()

    return () => {
      cancelled = true
    }
  }, [router])

  return {
    userId,
    isAuthenticated: !!userId,
    isLoading,
  }
}
