'use client'

import { useEffect, useRef, useState, useCallback } from 'react'
import { useRouter, usePathname } from 'next/navigation'
import { Smartphone, CheckCircle, AlertCircle, RefreshCw, Download } from 'lucide-react'
import { QRCodeSVG } from 'qrcode.react'
import { useAppStore } from '@/lib/store'
import {
  createSyncGroup,
  recoverSyncGroup,
  getSyncGroupInfo,
  signInAnon,
  initiatePairing,
  listenForPairingApproval,
  PairingSession,
} from '@/lib/firebase'
import { getDeviceId } from '@/lib/deviceId'
import Link from 'next/link'

export default function PairingScreen() {
  const router = useRouter()
  const pathname = usePathname()
  const { setSyncGroupId, setDeviceInfo, setUserId } = useAppStore()

  const [step, setStep] = useState<'loading' | 'waiting' | 'approved' | 'error'>('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [syncGroupId, setSyncGroupIdLocal] = useState<string>('')
  const [deviceCount, setDeviceCount] = useState(0)
  const [deviceLimit, setDeviceLimit] = useState(3)
  const [isInitializing, setIsInitializing] = useState(false)
  const [pairingSession, setPairingSession] = useState<PairingSession | null>(null)
  const hasApprovedRef = useRef(false)

  const redirectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const unsubscribeRef = useRef<(() => void) | null>(null)

  // Cleanup function
  useEffect(() => {
    return () => {
      if (redirectTimeoutRef.current) {
        clearTimeout(redirectTimeoutRef.current)
      }
      if (unsubscribeRef.current) {
        unsubscribeRef.current()
        unsubscribeRef.current = null
      }
    }
  }, [])

  const initializePairing = useCallback(async () => {
    if (isInitializing) {
      return
    }

    setIsInitializing(true)
    setStep('loading')
    setErrorMessage('')

    try {
      await signInAnon()

      let groupId: string | undefined
      const recovered = await recoverSyncGroup('web')
      if (recovered.success && recovered.syncGroupId) {
        groupId = recovered.syncGroupId
      } else {
        const created = await createSyncGroup('web')
        if (created.success && created.syncGroupId) {
          groupId = created.syncGroupId
        }
      }

      if (!groupId) {
        throw new Error('Failed to initialize sync group')
      }

      setSyncGroupIdLocal(groupId)
      setSyncGroupId(groupId)

      const info = await getSyncGroupInfo(groupId)
      if (info.success && info.data) {
        setDeviceCount(info.data.deviceCount)
        setDeviceLimit(info.data.deviceLimit)
        setDeviceInfo(info.data.deviceCount, info.data.deviceLimit)
      }

      console.log('[PairingScreen] Initiating pairing with groupId:', groupId)
      const session = await initiatePairing(undefined, groupId)
      console.log('[PairingScreen] Pairing session created:', session)
      console.log('[PairingScreen] QR Payload:', session.qrPayload ? 'Present' : 'MISSING!')
      setPairingSession(session)
      setStep('waiting')

      if (unsubscribeRef.current) {
        unsubscribeRef.current()
        unsubscribeRef.current = null
      }

      unsubscribeRef.current = listenForPairingApproval(session.token, (status) => {
        if (hasApprovedRef.current) {
          return
        }
        if (status.status === 'approved') {
          hasApprovedRef.current = true

          // SECURITY CHECK: Verify if cache belongs to this user
          const lastPairedUserId = localStorage.getItem('last_paired_user_id')
          const newUserId = status.pairedUid

          if (newUserId) {
            if (lastPairedUserId) {
              if (lastPairedUserId === newUserId) {
                // ✅ Same user re-pairing - cache is valid
                console.log('[Pairing] ✅ Same user re-pairing - using cached data (instant, 0 bandwidth)')
                console.log('[Pairing] 📦 Cache preserved for user', newUserId)
              } else {
                // ⚠️ Different user - clear previous user's cache for privacy
                console.log('[Pairing] ⚠️ Different user detected!')
                console.log('[Pairing]    Previous user:', lastPairedUserId)
                console.log('[Pairing]    New user:', newUserId)
                console.log('[Pairing] 🗑️ Clearing previous user\'s cache (privacy protection)')

                // Import incrementalSync to clear cache
                import('@/lib/incrementalSync').then(({ incrementalSyncManager }) => {
                  incrementalSyncManager.clearCache(lastPairedUserId)
                    .then(() => {
                      console.log('[Pairing] ✅ Previous user\'s cache cleared')
                    })
                    .catch((error) => {
                      console.error('[Pairing] ❌ Failed to clear cache:', error)
                    })
                })

                console.log('[Pairing] 📥 Will fetch fresh data for new user')
              }
            } else {
              console.log('[Pairing] 📥 No previous cache found - first time pairing')
            }

            // Save this user as the last paired user for future verification
            localStorage.setItem('last_paired_user_id', newUserId)
            localStorage.setItem('syncflow_user_id', newUserId)
            setUserId(newUserId)
          }

          if (status.deviceId) {
            localStorage.setItem('syncflow_device_id', status.deviceId)
          }

          setStep('approved')
          if (unsubscribeRef.current) {
            unsubscribeRef.current()
            unsubscribeRef.current = null
          }

          if (redirectTimeoutRef.current) {
            clearTimeout(redirectTimeoutRef.current)
          }
          redirectTimeoutRef.current = setTimeout(() => {
            router.push('/messages')
          }, 1500)
        } else if (status.status === 'rejected') {
          if (unsubscribeRef.current) {
            unsubscribeRef.current()
            unsubscribeRef.current = null
          }
          setStep('error')
          setErrorMessage('Pairing was rejected on your phone.')
        } else if (status.status === 'expired') {
          if (unsubscribeRef.current) {
            unsubscribeRef.current()
            unsubscribeRef.current = null
          }
          setStep('error')
          setErrorMessage('Pairing session expired. Please try again.')
        }
      })
    } catch (error: any) {
      console.error('[PairingScreen] Pairing initialization failed:', error)
      console.error('[PairingScreen] Error details:', {
        message: error?.message,
        code: error?.code,
        stack: error?.stack,
      })
      setStep('error')
      setErrorMessage(error?.message || 'Failed to initialize pairing')
    } finally {
      setIsInitializing(false)
    }
  }, [isInitializing, router, setDeviceInfo, setSyncGroupId, setUserId])

  const hasInitialized = useRef(false)

  useEffect(() => {
    // CRITICAL: Only auto-initialize pairing on home page (/)
    // Do NOT auto-initialize on admin routes (/admin/*) to prevent:
    // - Phantom pairing sessions
    // - Unnecessary database writes
    // - Excessive Firebase connections
    const isHomePage = pathname === '/'

    if (!isHomePage) {
      console.log('[PairingScreen] Skipping auto-init: not on home page (current path:', pathname, ')')
      return
    }

    if (hasInitialized.current) return
    hasInitialized.current = true

    console.log('[PairingScreen] Auto-initializing pairing on home page')
    initializePairing()
  }, [initializePairing, pathname])

  return (
    <div className="h-screen bg-gradient-to-br from-blue-50 to-indigo-100 dark:from-gray-900 dark:to-gray-800 overflow-y-auto">
      {isInitializing && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white dark:bg-gray-800 rounded-lg p-6 shadow-xl">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600 mx-auto mb-4"></div>
            <p className="text-gray-600 dark:text-gray-400">Initializing pairing...</p>
          </div>
        </div>
      )}

      <div className="max-w-2xl mx-auto w-full py-8 px-4">
        <div className="text-center mb-6">
          <div className="flex items-center justify-center mb-3">
            <Smartphone className="w-10 h-10 text-blue-600 mr-2" />
            <h1 className="text-3xl font-bold text-gray-900 dark:text-white">SyncFlow</h1>
          </div>
          <p className="text-gray-600 dark:text-gray-400 text-sm">
            Access your phone messages from your desktop
          </p>
        </div>

        <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-xl p-6">
          {step === 'loading' && (
            <div className="text-center py-12">
              <div className="animate-spin rounded-full h-16 w-16 border-b-2 border-blue-600 mx-auto mb-4"></div>
              <h2 className="text-xl font-semibold text-gray-900 dark:text-white mb-2">
                Initializing Pairing...
              </h2>
              <p className="text-gray-600 dark:text-gray-400">
                Please wait while we set up your sync group
              </p>
            </div>
          )}

          {step === 'waiting' && (
            <div>
              <h2 className="text-xl font-semibold text-gray-900 dark:text-white mb-1 text-center">
                Scan to Pair
              </h2>
              <p className="text-gray-600 dark:text-gray-400 text-sm mb-4 text-center">
                Scan this QR code with your Android device and approve pairing
              </p>

              {/* QR Code and Download Section Side by Side */}
              <div className="flex flex-col md:flex-row gap-4 items-start justify-center mb-4">
                {/* QR Code */}
                {pairingSession?.qrPayload && (
                  <div className="flex-shrink-0 mx-auto md:mx-0">
                    <div
                      id="qr-code-section"
                      className="bg-white p-5 rounded-xl shadow-inner border border-gray-200"
                    >
                      {/* Larger QR (220px) with high error correction for better scanning */}
                      <QRCodeSVG value={pairingSession.qrPayload} size={220} level="H" includeMargin={true} />
                      <p className="text-xs text-green-600 mt-2 font-medium text-center">Ready to scan</p>
                    </div>
                  </div>
                )}

                {/* Download Apps Section */}
                <div className="flex-1 max-w-sm mx-auto md:mx-0">
                  <div className="bg-gradient-to-r from-blue-500 to-purple-600 rounded-xl p-1 shadow-lg h-full">
                    <div className="bg-white dark:bg-gray-800 rounded-lg p-4 h-full flex flex-col justify-between">
                      <div>
                        <div className="flex items-start gap-3 mb-3">
                          <div className="w-12 h-12 bg-gradient-to-br from-blue-500 to-purple-600 rounded-xl flex items-center justify-center flex-shrink-0">
                            <Download className="w-6 h-6 text-white" />
                          </div>
                          <div>
                            <h3 className="text-base font-bold text-gray-900 dark:text-white mb-1">
                              Download Apps
                            </h3>
                            <p className="text-xs text-gray-600 dark:text-gray-400">
                              Get SyncFlow for Mac & Android
                            </p>
                          </div>
                        </div>

                        <div className="space-y-2 mb-4">
                          <div className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
                            <svg className="w-4 h-4 text-blue-500" fill="currentColor" viewBox="0 0 20 20">
                              <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                            </svg>
                            macOS 13+ (Apple Silicon & Intel)
                          </div>
                          <div className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
                            <svg className="w-4 h-4 text-green-500" fill="currentColor" viewBox="0 0 20 20">
                              <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                            </svg>
                            Android 8.0+ (All devices)
                          </div>
                          <div className="flex items-center gap-2 text-xs text-gray-700 dark:text-gray-300">
                            <svg className="w-4 h-4 text-purple-500" fill="currentColor" viewBox="0 0 20 20">
                              <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                            </svg>
                            Code signed & secure
                          </div>
                        </div>
                      </div>

                      <Link
                        href="/download"
                        className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-gradient-to-r from-blue-600 to-purple-600 hover:from-blue-700 hover:to-purple-700 text-white text-sm font-semibold rounded-lg transition-all hover:scale-105 shadow-md"
                      >
                        <Download className="w-4 h-4" />
                        Download Now
                      </Link>
                    </div>
                  </div>
                </div>
              </div>

              {/* Device Info and Refresh Button */}
              <div className="space-y-3">
                <div className="bg-blue-50 dark:bg-blue-900/20 rounded-lg p-3">
                  <p className="text-sm text-gray-700 dark:text-gray-300">
                    <span className="font-semibold">{deviceCount}/{deviceLimit}</span> devices connected
                    {deviceCount >= deviceLimit && (
                      <span className="text-yellow-600 dark:text-yellow-400 block text-xs mt-1">
                        Upgrade to Pro for unlimited devices
                      </span>
                    )}
                  </p>
                </div>

                {syncGroupId && (
                  <div className="bg-gray-100 dark:bg-gray-700 p-3 rounded-lg text-left">
                    <p className="text-xs text-gray-600 dark:text-gray-400 mb-1">Sync Group ID:</p>
                    <code className="text-xs font-mono text-gray-900 dark:text-white break-all">{syncGroupId}</code>
                  </div>
                )}

                <div className="flex justify-center pt-2">
                  <button
                    onClick={initializePairing}
                    disabled={isInitializing}
                    className="flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:bg-blue-400 text-white text-sm rounded-lg transition-colors"
                  >
                    <RefreshCw className={`w-4 h-4 ${isInitializing ? 'animate-spin' : ''}`} />
                    {isInitializing ? 'Refreshing...' : 'Refresh Pairing'}
                  </button>
                </div>
              </div>
            </div>
          )}

          {step === 'approved' && (
            <div className="text-center py-12">
              <CheckCircle className="w-16 h-16 text-green-600 mx-auto mb-4" />
              <h2 className="text-xl font-semibold text-gray-900 dark:text-white mb-2">
                Paired Successfully
              </h2>
              <p className="text-gray-600 dark:text-gray-400">
                Redirecting to messages...
              </p>
            </div>
          )}

          {step === 'error' && (
            <div className="text-center py-12">
              <AlertCircle className="w-20 h-20 text-red-500 mx-auto mb-4" />
              <h2 className="text-2xl font-semibold text-gray-900 dark:text-white mb-2">
                Pairing Failed
              </h2>
              <p className="text-gray-600 dark:text-gray-400 mb-6">{errorMessage}</p>
              <div className="space-y-3">
                <button
                  onClick={initializePairing}
                  disabled={isInitializing}
                  className="bg-blue-600 hover:bg-blue-700 disabled:bg-blue-400 text-white font-semibold py-3 px-6 rounded-lg transition-colors flex items-center justify-center mx-auto"
                >
                  <RefreshCw className={`w-5 h-5 mr-2 ${isInitializing ? 'animate-spin' : ''}`} />
                  {isInitializing ? 'Retrying...' : 'Try Again'}
                </button>
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  If this persists, try refreshing the page
                </p>
              </div>
            </div>
          )}
        </div>

        <div className="flex justify-center mt-4 md:hidden">
          <div className="flex flex-col items-center text-gray-400 dark:text-gray-500">
            <div className="w-6 h-1 bg-gray-300 dark:bg-gray-600 rounded-full mb-1"></div>
            <div className="w-4 h-1 bg-gray-300 dark:bg-gray-600 rounded-full mb-1 opacity-60"></div>
            <div className="w-2 h-1 bg-gray-300 dark:bg-gray-600 rounded-full opacity-40"></div>
          </div>
        </div>

        <div className="text-center mt-4 space-y-1">
          <p className="text-gray-500 dark:text-gray-400 text-xs">
            Your messages are end-to-end encrypted and never leave your control
          </p>
          {step === 'waiting' && (
            <p className="text-xs text-gray-500 dark:text-gray-400">
              Waiting for phone approval...
            </p>
          )}
        </div>
      </div>
    </div>
  )
}
