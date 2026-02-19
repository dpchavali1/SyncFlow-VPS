'use client'

import { useEffect, useRef, useState, useCallback } from 'react'
import { useRouter, usePathname } from 'next/navigation'
import { motion, AnimatePresence } from 'framer-motion'
import { Smartphone, CheckCircle, AlertCircle, RefreshCw, Download, MessageSquare, Shield } from 'lucide-react'
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
import { scaleIn, fadeIn } from '@/lib/animations'
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

      const session = await initiatePairing(undefined, groupId)
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
              if (lastPairedUserId !== newUserId) {
                // Different user - clear previous user's cache for privacy
                import('@/lib/incrementalSync').then(({ incrementalSyncManager }) => {
                  incrementalSyncManager.clearCache(lastPairedUserId)
                    .catch((error) => {
                      console.error('[Pairing] Failed to clear cache:', error)
                    })
                })
              }
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
      if (process.env.NODE_ENV === 'development') {
        console.log('[PairingScreen] Error details:', {
          message: error?.message,
          code: error?.code,
          stack: error?.stack,
        })
      }
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
      return
    }

    if (hasInitialized.current) return
    hasInitialized.current = true

    initializePairing()
  }, [initializePairing, pathname])

  return (
    <div className="h-screen bg-mesh overflow-y-auto">
      {/* Initializing overlay */}
      <AnimatePresence>
        {isInitializing && step === 'loading' && (
          <motion.div
            variants={fadeIn}
            initial="hidden"
            animate="visible"
            exit="exit"
            className="fixed inset-0 bg-black/30 backdrop-blur-sm flex items-center justify-center z-50"
          >
            <div className="glass-elevated rounded-2xl p-6 shadow-xl">
              <div className="w-8 h-8 rounded-full border-2 border-blue-200 border-t-blue-500 animate-spin mx-auto mb-4" />
              <p className="text-gray-600 dark:text-gray-400 text-sm">Initializing pairing...</p>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      <div className="max-w-2xl mx-auto w-full py-8 px-4">
        {/* Header */}
        <motion.div
          variants={fadeIn}
          initial="hidden"
          animate="visible"
          className="text-center mb-6"
        >
          <div className="flex items-center justify-center mb-3">
            <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-blue-500 to-violet-600 flex items-center justify-center shadow-lg shadow-blue-500/25 mr-3">
              <MessageSquare className="w-6 h-6 text-white" />
            </div>
            <h1 className="text-3xl font-bold text-gradient">SyncFlow</h1>
          </div>
          <p className="text-gray-500 dark:text-gray-400 text-sm">
            Access your phone messages from your desktop
          </p>
        </motion.div>

        <motion.div
          variants={scaleIn}
          initial="hidden"
          animate="visible"
          className="glass-elevated rounded-3xl p-6"
        >
          <AnimatePresence mode="wait">
            {step === 'loading' && (
              <motion.div
                key="loading"
                variants={fadeIn}
                initial="hidden"
                animate="visible"
                exit="exit"
                className="text-center py-12"
              >
                <div className="w-16 h-16 rounded-full border-2 border-blue-200 border-t-blue-500 animate-spin mx-auto mb-4" />
                <h2 className="text-xl font-semibold text-gray-900 dark:text-white mb-2">
                  Initializing Pairing...
                </h2>
                <p className="text-gray-500 dark:text-gray-400 text-sm">
                  Please wait while we set up your sync group
                </p>
              </motion.div>
            )}

            {step === 'waiting' && (
              <motion.div
                key="waiting"
                variants={fadeIn}
                initial="hidden"
                animate="visible"
                exit="exit"
              >
                <h2 className="text-xl font-semibold text-gray-900 dark:text-white mb-1 text-center">
                  Scan to Pair
                </h2>
                <p className="text-gray-500 dark:text-gray-400 text-sm mb-4 text-center">
                  Scan this QR code with your Android device and approve pairing
                </p>

                {/* QR Code and Download Section Side by Side */}
                <div className="flex flex-col md:flex-row gap-4 items-start justify-center mb-4">
                  {/* QR Code */}
                  {pairingSession?.qrPayload && (
                    <div className="flex-shrink-0 mx-auto md:mx-0">
                      <motion.div
                        variants={scaleIn}
                        initial="hidden"
                        animate="visible"
                        id="qr-code-section"
                        className="bg-white p-5 rounded-2xl shadow-lg"
                      >
                        <QRCodeSVG value={pairingSession.qrPayload} size={220} level="H" includeMargin={true} />
                        <p className="text-xs text-emerald-600 mt-2 font-medium text-center flex items-center justify-center gap-1">
                          <span className="w-1.5 h-1.5 bg-emerald-500 rounded-full animate-pulse" />
                          Ready to scan
                        </p>
                      </motion.div>
                    </div>
                  )}

                  {/* Download Apps Section */}
                  <div className="flex-1 max-w-sm mx-auto md:mx-0">
                    <div className="bg-gradient-to-br from-blue-500 to-violet-600 rounded-2xl p-[1px] shadow-lg h-full">
                      <div className="bg-white dark:bg-gray-900 rounded-2xl p-4 h-full flex flex-col justify-between">
                        <div>
                          <div className="flex items-start gap-3 mb-3">
                            <div className="w-12 h-12 bg-gradient-to-br from-blue-500 to-violet-600 rounded-xl flex items-center justify-center flex-shrink-0 shadow-md">
                              <Download className="w-6 h-6 text-white" />
                            </div>
                            <div>
                              <h3 className="text-base font-bold text-gray-900 dark:text-white mb-1">
                                Download Apps
                              </h3>
                              <p className="text-[11px] text-gray-500 dark:text-gray-400">
                                Get SyncFlow for Mac & Android
                              </p>
                            </div>
                          </div>

                          <div className="space-y-2 mb-4">
                            <div className="flex items-center gap-2 text-xs text-gray-600 dark:text-gray-300">
                              <span className="text-blue-500">✓</span>
                              macOS 13+ (Apple Silicon & Intel)
                            </div>
                            <div className="flex items-center gap-2 text-xs text-gray-600 dark:text-gray-300">
                              <span className="text-emerald-500">✓</span>
                              Android 8.0+ (All devices)
                            </div>
                            <div className="flex items-center gap-2 text-xs text-gray-600 dark:text-gray-300">
                              <span className="text-violet-500">✓</span>
                              Code signed & secure
                            </div>
                          </div>
                        </div>

                        <Link
                          href="/download"
                          className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-gradient-to-r from-blue-500 to-violet-600 hover:from-blue-600 hover:to-violet-700 text-white text-sm font-semibold rounded-xl transition-all shadow-md shadow-blue-500/20"
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
                  <div className="glass-panel rounded-xl p-3">
                    <p className="text-sm text-gray-600 dark:text-gray-300">
                      <span className="font-semibold">{deviceCount}/{deviceLimit}</span> devices connected
                      {deviceCount >= deviceLimit && (
                        <span className="text-amber-600 dark:text-amber-400 block text-xs mt-1">
                          Upgrade to Pro for unlimited devices
                        </span>
                      )}
                    </p>
                  </div>

                  {syncGroupId && (
                    <div className="glass-panel rounded-xl p-3 text-left">
                      <p className="text-[11px] text-gray-400 dark:text-gray-500 mb-1">Sync Group ID:</p>
                      <code className="text-[11px] font-mono text-gray-900 dark:text-white break-all">{syncGroupId}</code>
                    </div>
                  )}

                  <div className="flex justify-center pt-2">
                    <motion.button
                      whileHover={{ scale: 1.03 }}
                      whileTap={{ scale: 0.97 }}
                      onClick={initializePairing}
                      disabled={isInitializing}
                      className="flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-blue-500 to-blue-600 hover:from-blue-600 hover:to-blue-700 disabled:from-gray-400 disabled:to-gray-500 text-white text-sm font-medium rounded-xl transition-all shadow-md shadow-blue-500/20 disabled:shadow-none"
                    >
                      <RefreshCw className={`w-4 h-4 ${isInitializing ? 'animate-spin' : ''}`} />
                      {isInitializing ? 'Refreshing...' : 'Refresh Pairing'}
                    </motion.button>
                  </div>
                </div>
              </motion.div>
            )}

            {step === 'approved' && (
              <motion.div
                key="approved"
                variants={scaleIn}
                initial="hidden"
                animate="visible"
                exit="exit"
                className="text-center py-12"
              >
                <div className="w-16 h-16 rounded-full bg-emerald-500 flex items-center justify-center mx-auto mb-4 shadow-lg shadow-emerald-500/30">
                  <CheckCircle className="w-8 h-8 text-white" />
                </div>
                <h2 className="text-xl font-semibold text-gray-900 dark:text-white mb-2">
                  Paired Successfully
                </h2>
                <p className="text-gray-500 dark:text-gray-400 text-sm">
                  Redirecting to messages...
                </p>
              </motion.div>
            )}

            {step === 'error' && (
              <motion.div
                key="error"
                variants={scaleIn}
                initial="hidden"
                animate="visible"
                exit="exit"
                className="text-center py-12"
              >
                <div className="w-16 h-16 rounded-2xl bg-red-500/10 flex items-center justify-center mx-auto mb-4">
                  <AlertCircle className="w-8 h-8 text-red-500" />
                </div>
                <h2 className="text-xl font-semibold text-gray-900 dark:text-white mb-2">
                  Pairing Failed
                </h2>
                <p className="text-gray-500 dark:text-gray-400 text-sm mb-6">{errorMessage}</p>
                <div className="space-y-3">
                  <motion.button
                    whileHover={{ scale: 1.03 }}
                    whileTap={{ scale: 0.97 }}
                    onClick={initializePairing}
                    disabled={isInitializing}
                    className="inline-flex items-center gap-2 px-6 py-3 bg-gradient-to-r from-blue-500 to-blue-600 hover:from-blue-600 hover:to-blue-700 disabled:from-gray-400 disabled:to-gray-500 text-white font-semibold rounded-xl transition-all shadow-md shadow-blue-500/20 disabled:shadow-none"
                  >
                    <RefreshCw className={`w-5 h-5 ${isInitializing ? 'animate-spin' : ''}`} />
                    {isInitializing ? 'Retrying...' : 'Try Again'}
                  </motion.button>
                  <p className="text-[11px] text-gray-400 dark:text-gray-500">
                    If this persists, try refreshing the page
                  </p>
                </div>
              </motion.div>
            )}
          </AnimatePresence>
        </motion.div>

        <motion.div
          variants={fadeIn}
          initial="hidden"
          animate="visible"
          className="text-center mt-4 space-y-1"
        >
          <div className="flex items-center justify-center gap-1.5 text-gray-400 dark:text-gray-500 text-xs">
            <Shield className="w-3.5 h-3.5" />
            <p>Your messages are end-to-end encrypted and never leave your control</p>
          </div>
          {step === 'waiting' && (
            <p className="text-[11px] text-gray-400 dark:text-gray-500 flex items-center justify-center gap-1">
              <span className="w-1.5 h-1.5 bg-amber-500 rounded-full animate-pulse" />
              Waiting for phone approval...
            </p>
          )}
        </motion.div>
      </div>
    </div>
  )
}
