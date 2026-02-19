'use client'

import { useEffect, useState, useMemo } from 'react'
import { useRouter } from 'next/navigation'
import PairingScreen from '@/components/PairingScreen'
import VPSPairingScreen from '@/components/VPSPairingScreen'
import { useAppStore } from '@/lib/store'
import vpsService from '@/lib/vps'
import { decryptDataKey, importSyncGroupKeypair } from '@/lib/e2ee'

export default function Home() {
  const router = useRouter()
  const { userId, isAuthenticated } = useAppStore()
  const [isLoading, setIsLoading] = useState(true)

  // Check if VPS mode is enabled
  const isVPSMode = useMemo(() => {
    // Check localStorage for VPS mode setting
    if (typeof window !== 'undefined') {
      if (localStorage.getItem('useVPSMode') === 'true') {
        return true
      }
    }
    // Check if VPS URL is configured via environment
    if (process.env.NEXT_PUBLIC_VPS_URL) {
      return true
    }
    // Default to VPS mode for new installs
    return true
  }, [])

  useEffect(() => {
    const checkAuth = async () => {
      // Check if user is already paired
      // For VPS mode, check VPS tokens; for Firebase mode, check Firebase user ID
      if (isVPSMode) {
        // VPS auth tokens exist from initiatePairing() even before QR scan completes.
        // Only redirect to /messages if pairing was fully completed (syncflow_user_id set).
        const pairingComplete = !!localStorage.getItem('syncflow_user_id')

        if (pairingComplete) {
          // Wait for tokens to be restored from IndexedDB before checking auth
          await vpsService.ensureTokensRestored()

          if (vpsService.isAuthenticated && vpsService.currentUserId) {
            useAppStore.getState().setUserId(vpsService.currentUserId)
            router.push('/messages')
            return
          }
        }

        // Not fully paired — clear any stale temp tokens and show pairing screen
        if (!pairingComplete && vpsService.isAuthenticated) {
          vpsService.clearTokens()
        }
        setIsLoading(false)
        return
      }

      // Legacy: check localStorage for Firebase mode
      const storedUserId = localStorage.getItem('syncflow_user_id')
      if (storedUserId) {
        useAppStore.getState().setUserId(storedUserId)
        router.push('/messages')
      } else {
        setIsLoading(false)
      }
    }
    checkAuth()
  }, [router, isVPSMode])

  // Handle VPS pairing completion
  const handleVPSPairingComplete = async (user: { userId: string; deviceId: string }) => {
    useAppStore.getState().setUserId(user.userId)
    localStorage.setItem('syncflow_user_id', user.userId)
    localStorage.setItem('syncflow_device_id', user.deviceId)

    try {
      const encryptedKey = await vpsService.waitForDeviceE2eeKey(60000, 2000)
      if (encryptedKey) {
        const payloadBytes = await decryptDataKey(encryptedKey)
        if (payloadBytes) {
          const payloadJson = new TextDecoder().decode(payloadBytes)
          const payload = JSON.parse(payloadJson)
          if (payload?.privateKeyPKCS8 && payload?.publicKeyX963) {
            await importSyncGroupKeypair(payload.privateKeyPKCS8, payload.publicKeyX963)
          }
        }
      }
    } catch (err) {
      console.error('[Pairing] VPS E2EE key sync failed', err)
    }

    router.push('/messages')
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-mesh">
        <div className="text-center">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-gradient-to-br from-blue-500 to-violet-600 shadow-lg shadow-blue-500/25 mb-5">
            <svg className="w-7 h-7 text-white animate-pulse" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
            </svg>
          </div>
          <p className="text-sm text-gray-400 dark:text-gray-500">Loading SyncFlow...</p>
        </div>
      </div>
    )
  }

  // Use VPS pairing for VPS mode, Firebase pairing otherwise
  if (isVPSMode) {
    return <VPSPairingScreen onPairingComplete={handleVPSPairingComplete} />
  }

  return <PairingScreen />
}
