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
    // Check if user is already paired
    // For VPS mode, check VPS tokens; for Firebase mode, check Firebase user ID
    if (isVPSMode) {
      // Check VPS authentication
      if (vpsService.isAuthenticated && vpsService.currentUserId) {
        useAppStore.getState().setUserId(vpsService.currentUserId)
        router.push('/messages')
        return
      }
    }

    // Legacy: check localStorage for Firebase mode
    const storedUserId = localStorage.getItem('syncflow_user_id')
    if (storedUserId) {
      useAppStore.getState().setUserId(storedUserId)
      router.push('/messages')
    } else {
      setIsLoading(false)
    }
  }, [router, isVPSMode])

  // Handle VPS pairing completion
  const handleVPSPairingComplete = async (user: { userId: string; deviceId: string }) => {
    useAppStore.getState().setUserId(user.userId)
    localStorage.setItem('syncflow_user_id', user.userId)

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
      <div className="flex items-center justify-center min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100 dark:from-gray-900 dark:to-gray-800">
        <div className="text-center">
          <div className="animate-spin rounded-full h-16 w-16 border-b-2 border-blue-600 mx-auto mb-4"></div>
          <p className="text-gray-600 dark:text-gray-400">Loading SyncFlow...</p>
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
