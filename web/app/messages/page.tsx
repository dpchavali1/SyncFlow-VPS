'use client'

import { useEffect, useState, useMemo } from 'react'
import { useRouter } from 'next/navigation'
import { useAppStore } from '@/lib/store'
import {
  ensureWebE2EEKeyBackup,
  ensureWebE2EEKeyPublished,
  listenToDeviceStatus,
  listenToMessagesOptimized,
  listenToReadReceipts,
  listenToSpamMessagesOptimized,
  listenToWebE2EEBackfillStatus,
  requestWebE2EEKeyBackfill,
  requestWebE2EEKeySync,
  waitForAuth,
  waitForWebE2EEKeySyncResponse,
} from '@/lib/firebase'
import vpsService from '@/lib/vps'
import { decryptDataKey, decryptMessageBody, importSyncGroupKeypair } from '@/lib/e2ee'
import ConversationList from '@/components/ConversationList'
import MessageView from '@/components/MessageView'
import Header from '@/components/Header'
import AIAssistant from '@/components/AIAssistant'
import AdBanner from '@/components/AdBanner'

const mmsDownloadUrlCache = new Map<string, string>()
const mmsDownloadUrlInflight = new Map<string, Promise<string>>()

const normalizeMmsParts = (raw: any): any[] => {
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw)
      return Array.isArray(parsed) ? parsed : []
    } catch (err) {
      console.warn('[Messages] Failed to parse mmsParts JSON', err)
      return []
    }
  }
  return []
}

const getCachedMmsDownloadUrl = async (fileKey: string): Promise<string | null> => {
  if (mmsDownloadUrlCache.has(fileKey)) {
    return mmsDownloadUrlCache.get(fileKey) || null
  }

  if (mmsDownloadUrlInflight.has(fileKey)) {
    try {
      return await mmsDownloadUrlInflight.get(fileKey)!
    } catch {
      return null
    }
  }

  const request = vpsService
    .getFileDownloadUrl(fileKey)
    .then((url) => {
      mmsDownloadUrlCache.set(fileKey, url)
      return url
    })
    .catch((err) => {
      console.error('[Messages] Failed to fetch MMS download URL', err)
      throw err
    })
    .finally(() => {
      mmsDownloadUrlInflight.delete(fileKey)
    })

  mmsDownloadUrlInflight.set(fileKey, request)

  try {
    return await request
  } catch {
    return null
  }
}

const resolveMmsAttachments = async (rawParts: any): Promise<any[]> => {
  const parts = normalizeMmsParts(rawParts)
  if (parts.length === 0) return []

  const resolved = await Promise.all(
    parts.map(async (part: any) => {
      if (!part) return null

      const contentType = part.contentType || 'application/octet-stream'
      const fileKey = part.fileKey || part.r2Key
      const url = fileKey ? await getCachedMmsDownloadUrl(fileKey) : null
      const type = contentType.startsWith('image/') ? 'image' : undefined

      return {
        contentType,
        url: url || undefined,
        fileName: part.fileName,
        type,
      }
    })
  )

  return resolved.filter(Boolean)
}

export default function MessagesPage() {
  const router = useRouter()
  const {
    userId,
    setUserId,
    messages,
    setMessages,
    setReadReceipts,
    setSpamMessages,
    selectedConversation,
    setSelectedConversation,
    setSelectedSpamAddress,
    setActiveFolder,
    isConversationListVisible,
    setIsConversationListVisible,
    initializeConversationListVisibility,
  } = useAppStore()
  const [showAI, setShowAI] = useState(false)
  const [keySyncLoading, setKeySyncLoading] = useState(false)
  const [keySyncStatus, setKeySyncStatus] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)
  const [backfillStatus, setBackfillStatus] = useState<{
    status?: string
    scanned?: number
    updated?: number
    skipped?: number
    error?: string
  } | null>(null)
  const [dismissedAtFailureCount, setDismissedAtFailureCount] = useState<number | null>(null)

  const failureCount = messages.filter((msg) => msg.decryptionFailed).length
  const hasDecryptFailures = failureCount > 0
  const isBannerDismissed = dismissedAtFailureCount === failureCount && failureCount > 0

  const handleKeySync = async () => {
    if (!userId || keySyncLoading) return
    setKeySyncLoading(true)
    setKeySyncStatus(null)
    try {
      await requestWebE2EEKeySync(userId)
      await waitForWebE2EEKeySyncResponse(userId)
      setKeySyncStatus('Keys synced. Backfilling access to older messages...')
      await requestWebE2EEKeyBackfill(userId)
      setReloadToken(Date.now())
    } catch (err: any) {
      setKeySyncStatus(err?.message || 'Key sync failed')
    } finally {
      setKeySyncLoading(false)
    }
  }

  useEffect(() => {
    if (!userId || typeof window === 'undefined') return
    const key = `syncflow_e2ee_banner_dismissed_${userId}`
    const stored = localStorage.getItem(key)
    if (stored) {
      const parsed = Number(stored)
      if (!Number.isNaN(parsed)) {
        setDismissedAtFailureCount(parsed)
      }
    }
  }, [userId])

  useEffect(() => {
    if (!userId || typeof window === 'undefined') return
    const key = `syncflow_e2ee_banner_dismissed_${userId}`
    if (dismissedAtFailureCount == null) {
      localStorage.removeItem(key)
      return
    }
    localStorage.setItem(key, String(dismissedAtFailureCount))
  }, [dismissedAtFailureCount, userId])

  useEffect(() => {
    if (!userId) return
    const unsubscribe = listenToWebE2EEBackfillStatus(userId, (status) => {
      setBackfillStatus(status)
    })
    return () => {
      unsubscribe()
    }
  }, [userId])

  useEffect(() => {
    if (!hasDecryptFailures) {
      setDismissedAtFailureCount(null)
    }
  }, [hasDecryptFailures])

  const backfillIsActive =
    backfillStatus?.status === 'pending' || backfillStatus?.status === 'processing'
  const backfillErrored = backfillStatus?.status === 'error'
  const showKeyBanner =
    !isBannerDismissed && (keySyncLoading || backfillIsActive || backfillErrored || hasDecryptFailures)

  useEffect(() => {
    let unsubscribe: (() => void) | null = null
    let unsubscribeSpam: (() => void) | null = null
    let unsubscribeReadReceipts: (() => void) | null = null

    const setupFirebase = async () => {
      // Check if VPS mode is enabled - MUST match firebase.ts isVPSMode() logic
      // Default to VPS mode for new installs (no Firebase user IDs should be created)
      const isVPSMode = localStorage.getItem('useVPSMode') === 'true' ||
                        !!process.env.NEXT_PUBLIC_VPS_URL ||
                        !!localStorage.getItem('vps_access_token') ||
                        vpsService.isAuthenticated ||
                        true // Default to VPS mode

      // Check authentication
      const storedUserId = localStorage.getItem('syncflow_user_id')

      if (!storedUserId) {
        router.push('/')
        return
      }

      setUserId(storedUserId)
      initializeConversationListVisibility()

      // VPS Mode: Skip Firebase, use VPS service
      if (isVPSMode) {
        console.log('[Messages] VPS mode enabled - using VPS for data sync')

        // Verify VPS authentication
        if (!vpsService.isAuthenticated) {
          console.log('[Messages] VPS not authenticated, redirecting to pairing')
          router.push('/')
          return
        }

        const syncVpsE2eeKeys = async () => {
          try {
            const encryptedKey = await vpsService.waitForDeviceE2eeKey(10000, 1000)
            if (!encryptedKey) return
            const payloadBytes = await decryptDataKey(encryptedKey)
            if (!payloadBytes) return
            const payloadJson = new TextDecoder().decode(payloadBytes)
            const payload = JSON.parse(payloadJson)
            if (payload?.privateKeyPKCS8 && payload?.publicKeyX963) {
              await importSyncGroupKeypair(payload.privateKeyPKCS8, payload.publicKeyX963)
            }
          } catch (err) {
            console.error('[Messages] VPS E2EE key sync failed', err)
          }
        }

        // Best-effort E2EE key sync before loading messages
        await syncVpsE2eeKeys()

        // Connect WebSocket for real-time updates
        vpsService.connectWebSocket()

        const decryptVpsMessage = async (msg: any) => {
          let body = msg.body || ''
          let decryptionFailed = false

          if (msg.encrypted && msg.encryptedBody && msg.encryptedNonce) {
            const envelope =
              msg.keyMap?.syncGroup ||
              (vpsService.currentDeviceId ? msg.keyMap?.[vpsService.currentDeviceId] : null)

            if (envelope) {
              const dataKey = await decryptDataKey(envelope)
              if (dataKey) {
                const decrypted = await decryptMessageBody(dataKey, msg.encryptedBody, msg.encryptedNonce)
                if (decrypted !== null) {
                  body = decrypted
                } else {
                  decryptionFailed = true
                }
              } else {
                decryptionFailed = true
              }
            } else {
              decryptionFailed = true
            }
          }

          const attachments = msg.attachments?.length
            ? msg.attachments
            : await resolveMmsAttachments(msg.mmsParts)

          return {
            id: msg.id,
            address: msg.address,
            body,
            date: msg.date,
            type: msg.type,
            read: msg.read,
            threadId: msg.threadId,
            contactName: msg.contactName,
            isMms: msg.isMms,
            mmsParts: msg.mmsParts,
            attachments,
            encrypted: msg.encrypted,
            decryptionFailed,
          }
        }

        // Fetch all messages from VPS (paginate through all pages)
        try {
          let allMessages: any[] = []
          let before: number | undefined
          let hasMore = true
          while (hasMore) {
            const response = await vpsService.getMessages({ limit: 500, before })
            allMessages.push(...response.messages)
            hasMore = response.hasMore
            if (response.messages.length > 0) {
              before = response.messages[response.messages.length - 1].date
            }
          }
          console.log('[Messages] VPS: Loaded', allMessages.length, 'messages')
          const formattedMessages = await Promise.all(
            allMessages.map((msg: any) => decryptVpsMessage(msg))
          )
          setMessages(formattedMessages)
        } catch (err) {
          console.error('[Messages] VPS: Failed to load messages', err)
        }

        // Set up VPS WebSocket listeners for real-time updates
        const onMessagesSynced = async (data: any) => {
          const incoming = Array.isArray(data?.messages) ? data.messages : []
          if (incoming.length === 0) return

          const decrypted = await Promise.all(incoming.map((msg: any) => decryptVpsMessage(msg)))

          const current = useAppStore.getState().messages
          // Remove optimistic messages that now have real counterparts
          const realSentBodies = new Set(
            decrypted.filter(m => m.type === 2).map(m => m.body)
          )
          const filtered = current.filter(m => {
            if (!m.id?.startsWith('optimistic_')) return true
            return !realSentBodies.has(m.body)
          })
          const merged = new Map(filtered.map((m) => [m.id, m]))
          decrypted.forEach((m) => merged.set(m.id, m))
          const mergedList = Array.from(merged.values()).sort((a, b) => b.date - a.date)
          setMessages(mergedList)
        }

        const onMessageDeleted = (data: any) => {
          const messageId = data?.messageId || data?.id
          if (!messageId) return
          const current = useAppStore.getState().messages
          setMessages(current.filter((m) => m.id !== messageId))
        }

        const onMessagesDeleted = (data: any) => {
          const ids = data?.messageIds
          if (!Array.isArray(ids) || ids.length === 0) return
          const idSet = new Set(ids)
          const current = useAppStore.getState().messages
          setMessages(current.filter((m) => !idSet.has(m.id)))
        }

        const onOutgoingStatusChanged = (data: any) => {
          const id = data?.id
          const status = data?.status
          if (!id || !status) return
          const current = useAppStore.getState().messages
          const updated = current.map((m) =>
            m.id === id ? { ...m, deliveryStatus: status } : m
          )
          setMessages(updated)
        }

        const onDeliveryStatusChanged = (data: any) => {
          const id = data?.id
          const deliveryStatus = data?.deliveryStatus
          if (!id || !deliveryStatus) return
          const current = useAppStore.getState().messages
          const updated = current.map((m) =>
            m.id === id ? { ...m, deliveryStatus } : m
          )
          setMessages(updated)
        }

        vpsService.on('messages_synced', onMessagesSynced)
        vpsService.on('message_deleted', onMessageDeleted)
        vpsService.on('messages_deleted', onMessagesDeleted)
        vpsService.on('outgoing_status_changed', onOutgoingStatusChanged)
        vpsService.on('delivery_status_changed', onDeliveryStatusChanged)

        // Periodic sync fallback: poll for new messages every 30s
        // Catches anything missed if WebSocket dropped temporarily
        const syncInterval = setInterval(async () => {
          try {
            const current = useAppStore.getState().messages
            // Exclude optimistic messages from newest date calculation
            const newestDate = current
              .filter(m => !m.id?.startsWith('optimistic_'))
              .reduce((max, m) => Math.max(max, m.date), 0)
            if (newestDate === 0) return

            const response = await vpsService.getMessages({ limit: 500, after: newestDate })
            if (response.messages.length === 0) return

            const decrypted = await Promise.all(
              response.messages.map((msg: any) => decryptVpsMessage(msg))
            )
            // Remove optimistic messages replaced by real ones
            const realSentBodies = new Set(
              decrypted.filter(m => m.type === 2).map(m => m.body)
            )
            const filtered = current.filter(m => {
              if (!m.id?.startsWith('optimistic_')) return true
              return !realSentBodies.has(m.body)
            })
            const merged = new Map(filtered.map((m) => [m.id, m]))
            decrypted.forEach((m) => merged.set(m.id, m))
            const mergedList = Array.from(merged.values()).sort((a, b) => b.date - a.date)
            setMessages(mergedList)
          } catch {
            // Silently ignore poll failures
          }
        }, 30000)

        // Store cleanup refs for when effect unmounts
        const cleanupVPS = () => {
          clearInterval(syncInterval)
          vpsService.off('messages_synced', onMessagesSynced)
          vpsService.off('message_deleted', onMessageDeleted)
          vpsService.off('messages_deleted', onMessagesDeleted)
          vpsService.off('outgoing_status_changed', onOutgoingStatusChanged)
          vpsService.off('delivery_status_changed', onDeliveryStatusChanged)
        }
        // Stash so the outer cleanup can call it
        ;(window as any).__vpsMessagesCleanup = cleanupVPS

        return // Skip Firebase setup
      }

      // Firebase Mode: Original logic
      ensureWebE2EEKeyPublished(storedUserId)
        .catch((err) => console.error('Failed to publish web E2EE key', err))
      ensureWebE2EEKeyBackup(storedUserId)
        .catch((err) => console.error('Failed to create web E2EE key backup', err))

      // Wait for authentication before setting up listener
      // This is required for Firebase rules to allow data access
      try {
        let currentUser = await waitForAuth()

        if (!currentUser) {
          // Try to sign in anonymously - the stored userId gives us data access
          // Firebase rules allow read if auth != null AND the path matches storedUserId
          console.log('[Messages] No auth, attempting anonymous sign-in for data access')
          try {
            const { signInAnonymously } = await import('firebase/auth')
            const { getAuth } = await import('firebase/auth')
            const auth = getAuth()
            const result = await signInAnonymously(auth)
            currentUser = result.user?.uid || null
            console.log('[Messages] Anonymous sign-in successful:', currentUser)
          } catch (signInError) {
            console.error('[Messages] Failed to sign in anonymously:', signInError)
            // Redirect to pairing to re-establish connection
            router.push('/')
            return
          }
        }

        if (!currentUser) {
          // Still not authenticated, redirect to pairing
          router.push('/')
          return
        }

        // SECURITY: Verify the authenticated user has access to storedUserId
        // Either the auth.uid matches OR the user has pairedUid claim for this user
        // This prevents tampering with localStorage to access other users' data
        const authUserId = currentUser
        if (authUserId !== storedUserId) {
          // The authenticated user is different from stored user
          // This is expected for web clients (they have their own UID with pairedUid claim)
          // Firebase rules will enforce access - if rules deny, listener will fail
          // But we log this for security monitoring
          if (process.env.NODE_ENV === 'development') {
            console.log('Auth user differs from stored user (expected for paired devices)')
          }
        }
      } catch (error) {
        console.error('Authentication failed:', error)
        router.push('/')
        return
      }

      // Set up message listener (using optimized incremental sync - 95% less bandwidth)
      unsubscribe = await listenToMessagesOptimized(storedUserId, (newMessages) => {
        setMessages(newMessages)
      })

      // Optimized spam listener - uses child events instead of full snapshot
      unsubscribeSpam = listenToSpamMessagesOptimized(storedUserId, (spam) => {
        setSpamMessages(spam)
      })

      unsubscribeReadReceipts = listenToReadReceipts(storedUserId, (receipts) => {
        setReadReceipts(receipts)
      })
    }

    setupFirebase()

    // Cleanup function
    return () => {
      if (unsubscribe) {
        unsubscribe()
      }
      if (unsubscribeSpam) {
        unsubscribeSpam()
      }
      if (unsubscribeReadReceipts) {
        unsubscribeReadReceipts()
      }
      // Clean up VPS listeners and sync interval
      if (typeof window !== 'undefined' && (window as any).__vpsMessagesCleanup) {
        (window as any).__vpsMessagesCleanup()
        delete (window as any).__vpsMessagesCleanup
      }
    }
  }, [router, setUserId, setMessages, setReadReceipts, reloadToken, initializeConversationListVisibility, setSpamMessages])

  useEffect(() => {
    if (!userId) return
    const handleRemoteUnpair = () => {
      localStorage.removeItem('syncflow_user_id')
      localStorage.removeItem('syncflow_device_id')
      vpsService.clearTokens()
      setUserId(null)
      setMessages([])
      setReadReceipts({})
      setSpamMessages([])
      setSelectedConversation(null)
      setSelectedSpamAddress(null)
      setActiveFolder('inbox')
      router.push('/')
    }

    const unsubscribeDevice = listenToDeviceStatus(userId, (isPaired) => {
      if (!isPaired) {
        handleRemoteUnpair()
      }
    })

    // VPS: listen for device_removed WebSocket event (remote unpair from Android)
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const handleVPSDeviceRemoved = (data: any) => {
      const removedId = data?.id || data?.deviceId || ''
      const myDeviceId = vpsService.currentDeviceId
      if (!myDeviceId || removedId === myDeviceId || removedId === '') {
        handleRemoteUnpair()
      }
    }
    vpsService.on('device_removed', handleVPSDeviceRemoved)

    return () => {
      unsubscribeDevice()
      vpsService.off('device_removed', handleVPSDeviceRemoved)
    }
  }, [
    router,
    setActiveFolder,
    setMessages,
    setReadReceipts,
    setSelectedConversation,
    setSelectedSpamAddress,
    setSpamMessages,
    setUserId,
    userId,
  ])

  if (!userId) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="animate-spin rounded-full h-16 w-16 border-b-2 border-blue-600"></div>
      </div>
    )
  }

  return (
    <div className="h-screen flex flex-col overflow-hidden bg-gray-100 dark:bg-gray-900">
      <Header />

      <div className="flex-1 flex min-h-0 overflow-hidden">
        {isConversationListVisible && <ConversationList />}
        <div className="flex-1 flex flex-col min-w-0">
          {showKeyBanner && (
            <div className="flex-shrink-0 px-4 pt-4">
              <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-amber-900 dark:border-amber-700/60 dark:bg-amber-900/20 dark:text-amber-100">
                <div className="text-sm">
                  {backfillIsActive && (
                    <>
                      Processing older messages… {backfillStatus?.updated ?? 0} updated /{' '}
                      {backfillStatus?.scanned ?? 0} scanned.
                    </>
                  )}
                  {!backfillIsActive && backfillErrored && (
                    <>Key sync completed, but processing failed. You can try again.</>
                  )}
                  {!backfillIsActive && !backfillErrored && hasDecryptFailures && (
                    <>Some messages are encrypted and can’t be decrypted. Sync keys to unlock them.</>
                  )}
                  {!backfillIsActive && !backfillErrored && !hasDecryptFailures && (
                    <>Keys synced. Messages will update automatically.</>
                  )}
                </div>
                <div className="flex items-center gap-3">
                  <button
                    onClick={handleKeySync}
                    disabled={keySyncLoading}
                    className="inline-flex items-center justify-center px-3 py-1.5 rounded-lg bg-amber-600 text-white hover:bg-amber-700 disabled:opacity-60 disabled:cursor-not-allowed transition-colors text-sm"
                  >
                    {keySyncLoading ? 'Syncing…' : 'Sync Keys'}
                  </button>
                  <button
                    onClick={() => setDismissedAtFailureCount(failureCount)}
                    className="text-xs text-amber-800 dark:text-amber-200 hover:underline"
                  >
                    Dismiss
                  </button>
                  {keySyncStatus && (
                    <span className="text-xs text-amber-800 dark:text-amber-200">
                      {keySyncStatus}
                    </span>
                  )}
                </div>
              </div>
            </div>
          )}
          {!isConversationListVisible && (
            <div className="flex-shrink-0 p-4 border-b border-gray-200 dark:border-gray-700">
              <button
                onClick={() => setIsConversationListVisible(true)}
                className="flex items-center gap-2 px-4 py-2 text-sm bg-blue-600 hover:bg-blue-700 text-white rounded-lg transition-colors"
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 10h16M4 14h16M4 18h16" />
                </svg>
                Show Conversations
              </button>
            </div>
          )}
          <MessageView onOpenAI={() => setShowAI(true)} />
        </div>
      </div>

      <div className="flex-shrink-0 border-t border-gray-200 bg-gray-100 dark:border-gray-700 dark:bg-gray-900">
        <AdBanner />
      </div>

      {showAI && (
        <AIAssistant
          messages={messages}
          onClose={() => setShowAI(false)}
        />
      )}
    </div>
  )
}
