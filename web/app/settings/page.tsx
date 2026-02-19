'use client'

import { useEffect, useState, useCallback } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import Header from '@/components/Header'
import {
  ensureWebE2EEKeyBackup,
  ensureWebE2EEKeyPublished,
  getUsageSummary,
  UsageSummary,
  waitForAuth,
} from '@/lib/firebase'
import vpsService from '@/lib/vps'
import {
  clearAllE2EEKeys,
  deriveSafetyNumber,
  hasEncryptedKeyPair,
  hasLegacyKeyPair,
  importSyncGroupKeypair,
  isKeyPairUnlocked,
  lockKeyPair,
  setPassphraseAndEncrypt,
  unlockKeyPairWithPassphrase,
  getOrCreateKeyPair,
  getPublicKeyX963Base64,
} from '@/lib/e2ee'
import { useAppStore } from '@/lib/store'
import { NotificationSyncSettings } from '@/components/notification-sync-settings'
import { ArrowLeft, Bell, BarChart3, User, Shield, Key, RefreshCw } from 'lucide-react'

const formatBytes = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb.toFixed(1)} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  const gb = mb / 1024
  return `${gb.toFixed(1)} GB`
}

const formatDate = (timestamp?: number | null) => {
  if (!timestamp) return null
  return new Date(timestamp).toLocaleDateString()
}

const formatDateTime = (timestamp?: number | null) => {
  if (!timestamp) return null
  return new Date(timestamp).toLocaleString()
}

type TabType = 'notifications' | 'usage' | 'account'

export default function SettingsPage() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { userId, setUserId } = useAppStore()
  const [usage, setUsage] = useState<UsageSummary | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [activeTab, setActiveTab] = useState<TabType>('notifications')
  const [subscriptionBanner, setSubscriptionBanner] = useState<string | null>(null)
  const [syncingPlan, setSyncingPlan] = useState(false)
  const [keyProtectionStatus, setKeyProtectionStatus] = useState<string | null>(null)
  const [keyProtectionLoading, setKeyProtectionLoading] = useState(false)
  const [passphrase, setPassphrase] = useState('')
  const [confirmPassphrase, setConfirmPassphrase] = useState('')
  const [unlockPassphrase, setUnlockPassphrase] = useState('')
  const [keyProtectionState, setKeyProtectionState] = useState({
    encrypted: false,
    unlocked: false,
    legacy: false,
  })
  const [repairLoading, setRepairLoading] = useState(false)
  const [repairStatus, setRepairStatus] = useState<string | null>(null)
  const [safetyNumber, setSafetyNumber] = useState<string | null>(null)
  const [restorePassphrase, setRestorePassphrase] = useState('')
  const [restoreLoading, setRestoreLoading] = useState(false)
  const [restoreStatus, setRestoreStatus] = useState<string | null>(null)

  const loadUsage = useCallback(async (currentUserId: string) => {
    setIsLoading(true)
    setError(null)
    try {
      const summary = await getUsageSummary(currentUserId)
      setUsage(summary)
    } catch (err: any) {
      setError(err?.message || 'Failed to load usage')
    } finally {
      setIsLoading(false)
    }
  }, [])

  useEffect(() => {
    const setup = async () => {
      const storedUserId = localStorage.getItem('syncflow_user_id')

      if (!storedUserId) {
        router.push('/')
        return
      }

      setUserId(storedUserId)

      // Check VPS mode - must match firebase.ts isVPSMode() logic
      const isVPSMode = localStorage.getItem('useVPSMode') === 'true' ||
                        !!process.env.NEXT_PUBLIC_VPS_URL ||
                        !!localStorage.getItem('vps_access_token') ||
                        vpsService.isAuthenticated ||
                        true // Default to VPS mode

      if (isVPSMode) {
        // Wait for tokens to be restored from IndexedDB before making API calls
        await vpsService.ensureTokensRestored()

        // VPS mode: load usage from VPS API
        try {
          const data = await vpsService.getUsage()
          if (data?.usage) {
            const u = data.usage
            const plan = u.plan || 'free'
            const isPaid = plan !== 'free'
            const planLabels: Record<string, string> = { free: 'Free', monthly: 'Monthly', yearly: 'Yearly', '3year': '3-Year', lifetime: 'Lifetime' }

            setUsage({
              planLabel: planLabels[plan] || plan,
              planExpiresAt: u.planExpiresAt || null,
              trialDaysRemaining: isPaid ? null : (u.trialStartedAt ? Math.max(0, 30 - Math.floor((Date.now() - u.trialStartedAt) / 86400000)) : 30),
              monthlyUsedBytes: u.monthlyUploadBytes || 0,
              monthlyLimitBytes: u.monthlyUploadLimit || 500 * 1024 * 1024,
              storageUsedBytes: u.storageBytes || 0,
              storageLimitBytes: u.storageLimit || 100 * 1024 * 1024,
              mmsBytes: u.monthlyMmsBytes || 0,
              fileBytes: u.monthlyFileBytes || 0,
              photoBytes: u.monthlyPhotoBytes || 0,
              lastUpdatedAt: u.lastUpdatedAt || null,
              monthlyResetDate: u.monthlyResetDate || null,
              isPaid,
            })
          }
        } catch (err: any) {
          setError(err?.message || 'Failed to load usage')
        } finally {
          setIsLoading(false)
        }
        return
      }

      // Firebase mode (legacy)
      try {
        const authUser = await waitForAuth()
        if (!authUser) {
          router.push('/')
          return
        }
      } catch {
        router.push('/')
        return
      }

      await loadUsage(storedUserId)
    }

    setup()
  }, [router, setUserId, loadUsage])

  useEffect(() => {
    if (!userId) return
    const prepareE2ee = async () => {
      try {
        await ensureWebE2EEKeyPublished(userId)
        await ensureWebE2EEKeyBackup(userId)
      } catch (err) {
        console.error('Failed to prepare web E2EE keys', err)
      }
    }
    prepareE2ee()
  }, [userId])

  const refreshKeyProtection = useCallback(async () => {
    setKeyProtectionState({
      encrypted: await hasEncryptedKeyPair(),
      unlocked: await isKeyPairUnlocked(),
      legacy: await hasLegacyKeyPair(),
    })
  }, [])

  useEffect(() => {
    refreshKeyProtection()
    deriveSafetyNumber().then(setSafetyNumber).catch(() => {})
  }, [refreshKeyProtection])

  useEffect(() => {
    const sub = searchParams.get('subscription')
    if (sub === 'success') {
      setSubscriptionBanner('Payment successful! Syncing your subscription...')
      // Clear query param from URL without navigation
      window.history.replaceState({}, '', '/settings')
      // Sync subscription from Stripe into DB, then reload usage
      ;(async () => {
        try {
          await vpsService.ensureTokensRestored()
          const result = await vpsService.syncSubscription()
          if (result?.synced) {
            setSubscriptionBanner(`Payment successful! Your ${result.plan} plan is now active.`)
          } else {
            setSubscriptionBanner(result?.message || 'Payment successful! Your subscription is now active.')
          }
        } catch (err) {
          console.error('Subscription sync error:', err)
          setSubscriptionBanner('Payment received. Click "Refresh" to update your plan.')
        }
        // Reload usage to reflect the new plan
        try {
          const data = await vpsService.getUsage()
          if (data?.usage) {
            const u = data.usage
            const plan = u.plan || 'free'
            const isPaid = plan !== 'free'
            const planLabels: Record<string, string> = { free: 'Free', monthly: 'Monthly', yearly: 'Yearly', lifetime: 'Lifetime', '3year': '3-Year', pro_monthly: 'Pro Monthly', pro_yearly: 'Pro Yearly' }
            setUsage({
              planLabel: planLabels[plan] || plan,
              planExpiresAt: u.planExpiresAt || null,
              trialDaysRemaining: isPaid ? null : (u.trialStartedAt ? Math.max(0, 30 - Math.floor((Date.now() - u.trialStartedAt) / 86400000)) : 30),
              monthlyUsedBytes: u.monthlyUploadBytes || 0,
              monthlyLimitBytes: u.monthlyUploadLimit || 500 * 1024 * 1024,
              storageUsedBytes: u.storageBytes || 0,
              storageLimitBytes: u.storageLimit || 100 * 1024 * 1024,
              mmsBytes: u.monthlyMmsBytes || 0,
              fileBytes: u.monthlyFileBytes || 0,
              photoBytes: u.monthlyPhotoBytes || 0,
              lastUpdatedAt: u.lastUpdatedAt || null,
              monthlyResetDate: u.monthlyResetDate || null,
              isPaid,
            })
            setActiveTab('usage')
          }
        } catch {}
      })()
    }
  }, [searchParams])

  const handleEnableKeyProtection = async () => {
    setKeyProtectionStatus(null)
    if (passphrase.length < 8) {
      setKeyProtectionStatus('Passphrase must be at least 8 characters.')
      return
    }
    if (passphrase !== confirmPassphrase) {
      setKeyProtectionStatus('Passphrases do not match.')
      return
    }
    setKeyProtectionLoading(true)
    try {
      const ok = await setPassphraseAndEncrypt(passphrase)
      if (!ok) {
        setKeyProtectionStatus('Failed to protect keys. Try again.')
      } else {
        setKeyProtectionStatus('Key protection enabled. You will be prompted after refresh.')
        setPassphrase('')
        setConfirmPassphrase('')
        refreshKeyProtection()
      }
    } catch (err: any) {
      setKeyProtectionStatus(err?.message || 'Failed to protect keys.')
    } finally {
      setKeyProtectionLoading(false)
    }
  }

  const handleUnlockKeys = async () => {
    setKeyProtectionStatus(null)
    if (!unlockPassphrase) {
      setKeyProtectionStatus('Enter your passphrase to unlock.')
      return
    }
    setKeyProtectionLoading(true)
    try {
      const ok = await unlockKeyPairWithPassphrase(unlockPassphrase)
      if (!ok) {
        setKeyProtectionStatus('Unlock failed. Check your passphrase.')
      } else {
        setKeyProtectionStatus('Keys unlocked for this session.')
        setUnlockPassphrase('')
        refreshKeyProtection()
      }
    } catch (err: any) {
      setKeyProtectionStatus(err?.message || 'Unlock failed.')
    } finally {
      setKeyProtectionLoading(false)
    }
  }

  const handleLockKeys = () => {
    lockKeyPair()
    refreshKeyProtection()
    setKeyProtectionStatus('Keys locked.')
  }

  const handleRepairEncryption = async () => {
    if (!userId) return
    if (!confirm('This will clear encryption keys and re-sync all messages from your Android phone. Your phone must be online. Continue?')) return
    setRepairLoading(true)
    setRepairStatus(null)
    try {
      // Step 1: Clear server keys + trigger Android re-sync
      await vpsService.repairEncryption()

      // Step 2: Clear all local E2EE state (memory + IndexedDB)
      await clearAllE2EEKeys()

      // Step 3: Generate fresh device keypair and publish public key
      await getOrCreateKeyPair()
      const publicKey = await getPublicKeyX963Base64()
      if (publicKey) {
        await vpsService.publishE2EEPublicKey(publicKey)
      }

      // Step 4: Request key sync from Android device
      const devicesResp = await vpsService.getDevices()
      const androidDevice = devicesResp.devices.find((d: any) => d.deviceType === 'android')
      if (androidDevice) {
        await vpsService.requestE2EEKeySync(androidDevice.id)
      }

      // Step 5: Wait for Android to push sync group keys
      const encryptedKey = await vpsService.waitForDeviceE2eeKey(45000, 2000)
      if (encryptedKey) {
        const { decryptDataKey } = await import('@/lib/e2ee')
        const payloadBytes = await decryptDataKey(encryptedKey)
        if (payloadBytes) {
          const payload = JSON.parse(new TextDecoder().decode(payloadBytes))
          if (payload?.privateKeyPKCS8 && payload?.publicKeyX963) {
            await importSyncGroupKeypair(payload.privateKeyPKCS8, payload.publicKeyX963)
          }
        }
        setRepairStatus('Encryption repaired. Return to Messages to see decrypted messages.')
      } else {
        setRepairStatus('Key sync timed out. Make sure your Android phone is online and try again.')
      }
    } catch (err: any) {
      setRepairStatus(err?.message || 'Repair failed')
    } finally {
      setRepairLoading(false)
    }
  }

  if (!userId) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-mesh">
        <div className="w-10 h-10 rounded-full border-2 border-blue-200 border-t-blue-500 animate-spin" />
      </div>
    )
  }

  const tabs: { id: TabType; label: string; icon: React.ReactNode }[] = [
    { id: 'notifications', label: 'Notifications', icon: <Bell className="w-4 h-4" /> },
    { id: 'usage', label: 'Usage & Limits', icon: <BarChart3 className="w-4 h-4" /> },
    { id: 'account', label: 'Account', icon: <User className="w-4 h-4" /> },
  ]

  return (
    <div className="flex flex-col h-screen bg-mesh">
      <Header />

      <main className="flex-1 overflow-auto p-6">
        <div className="max-w-3xl mx-auto">
          {/* Back Button */}
          <div className="mb-6">
            <button
              onClick={() => router.push('/messages')}
              className="flex items-center gap-2 px-4 py-2 text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white hover:bg-white/50 dark:hover:bg-white/5 rounded-xl transition-colors text-sm"
            >
              <ArrowLeft className="w-4 h-4" />
              Back to Messages
            </button>
          </div>

          <div className="mb-6">
            <h2 className="text-2xl font-bold text-gray-900 dark:text-white">Settings</h2>
          </div>

          {subscriptionBanner && (
            <div className="mb-6 glass-panel rounded-2xl bg-emerald-500/10 border-emerald-200/30 dark:border-emerald-700/30 text-emerald-700 dark:text-emerald-300 px-5 py-3 flex items-center justify-between">
              <span className="text-sm">{subscriptionBanner}</span>
              <button
                onClick={() => setSubscriptionBanner(null)}
                className="text-emerald-500 hover:text-emerald-700 dark:hover:text-emerald-200 ml-3"
              >
                &times;
              </button>
            </div>
          )}

          {isLoading && (
            <div className="flex items-center justify-center py-10">
              <div className="w-10 h-10 rounded-full border-2 border-blue-200 border-t-blue-500 animate-spin" />
            </div>
          )}

          {!isLoading && error && (
            <div className="glass-panel rounded-2xl bg-red-500/10 border-red-200/30 dark:border-red-700/30 text-red-600 dark:text-red-400 px-5 py-3 text-sm">
              {error}
            </div>
          )}

          {/* Tab Navigation — Segmented Control */}
          <div className="mb-6">
            <div className="glass-panel rounded-2xl p-1 inline-flex gap-1">
              {tabs.map((tab) => (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className={`flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-medium transition-all ${
                    activeTab === tab.id
                      ? 'bg-white dark:bg-gray-800 text-blue-600 dark:text-blue-400 shadow-sm'
                      : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-300'
                  }`}
                >
                  {tab.icon}
                  {tab.label}
                </button>
              ))}
            </div>
          </div>

          {/* Tab Content */}
          <div className="space-y-6">
            {/* Notifications Tab */}
            {activeTab === 'notifications' && (
              <div className="space-y-6">
                <div className="glass-panel rounded-2xl p-6">
                  <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-4">Notification Settings</h3>
                  <p className="text-sm text-gray-600 dark:text-gray-400 mb-6">
                    Configure how Android notifications appear in your browser
                  </p>
                  <NotificationSyncSettings />
                </div>
              </div>
            )}

            {/* Usage Tab */}
            {activeTab === 'usage' && (
              <div className="space-y-6">
                {isLoading && (
                  <div className="flex items-center justify-center py-10">
                    <div className="animate-spin rounded-full h-10 w-10 border-b-2 border-blue-600"></div>
                  </div>
                )}

                {!isLoading && error && (
                  <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg">
                    {error}
                  </div>
                )}

                {!isLoading && usage && (
                  <>
                    <div className="flex items-center justify-between">
                      <h3 className="text-lg font-semibold text-gray-900 dark:text-white">Usage Statistics</h3>
                      <button
                        onClick={() => userId && loadUsage(userId)}
                        className="px-4 py-2 rounded-xl glass-panel text-gray-600 dark:text-gray-300 hover:bg-white/80 dark:hover:bg-white/10 text-sm font-medium transition-all"
                        disabled={isLoading}
                      >
                        {isLoading ? 'Refreshing...' : 'Refresh'}
                      </button>
                    </div>

                    <div className="grid gap-6 md:grid-cols-2">
                      <div className="glass-panel rounded-2xl p-6">
                        <div className="text-sm text-gray-500 dark:text-gray-400 mb-1">Plan</div>
                        <div className="text-lg font-semibold text-gray-900 dark:text-white">{usage.planLabel}</div>
                        {!usage.isPaid && (
                          <div className="text-sm text-gray-500 dark:text-gray-400 mt-1">
                            {usage.trialDaysRemaining === 0
                              ? 'Trial expired'
                              : `${usage.trialDaysRemaining ?? 30} days remaining in trial`}
                          </div>
                        )}
                        {usage.isPaid && usage.planExpiresAt && (
                          <div className="text-sm text-gray-500 dark:text-gray-400 mt-1">
                            Renews on {formatDate(usage.planExpiresAt)}
                          </div>
                        )}
                        {!usage.isPaid && (
                          <button
                            onClick={async () => {
                              setSyncingPlan(true)
                              try {
                                await vpsService.ensureTokensRestored()
                                const result = await vpsService.syncSubscription()
                                if (result?.synced) {
                                  setSubscriptionBanner(`Plan synced: ${result.plan} is now active!`)
                                } else {
                                  setSubscriptionBanner(result?.message || 'No completed checkout found. Complete payment first.')
                                }
                                // Reload usage
                                const data = await vpsService.getUsage()
                                if (data?.usage) {
                                  const u = data.usage
                                  const plan = u.plan || 'free'
                                  const isPaid = plan !== 'free'
                                  const planLabels: Record<string, string> = { free: 'Free', monthly: 'Monthly', yearly: 'Yearly', lifetime: 'Lifetime', '3year': '3-Year', pro_monthly: 'Pro Monthly', pro_yearly: 'Pro Yearly' }
                                  setUsage({
                                    planLabel: planLabels[plan] || plan,
                                    planExpiresAt: u.planExpiresAt || null,
                                    trialDaysRemaining: isPaid ? null : (u.trialStartedAt ? Math.max(0, 30 - Math.floor((Date.now() - u.trialStartedAt) / 86400000)) : 30),
                                    monthlyUsedBytes: u.monthlyUploadBytes || 0,
                                    monthlyLimitBytes: u.monthlyUploadLimit || 500 * 1024 * 1024,
                                    storageUsedBytes: u.storageBytes || 0,
                                    storageLimitBytes: u.storageLimit || 100 * 1024 * 1024,
                                    mmsBytes: u.monthlyMmsBytes || 0,
                                    fileBytes: u.monthlyFileBytes || 0,
                                    photoBytes: u.monthlyPhotoBytes || 0,
                                    lastUpdatedAt: u.lastUpdatedAt || null,
                                    monthlyResetDate: u.monthlyResetDate || null,
                                    isPaid,
                                  })
                                }
                              } catch (err: any) {
                                setSubscriptionBanner(`Sync failed: ${err?.message || 'Unknown error'}`)
                              } finally {
                                setSyncingPlan(false)
                              }
                            }}
                            disabled={syncingPlan}
                            className="mt-2 px-4 py-2 text-sm rounded-xl bg-gradient-to-r from-blue-500 to-blue-600 text-white font-medium hover:from-blue-600 hover:to-blue-700 disabled:opacity-60 transition-all shadow-sm"
                          >
                            {syncingPlan ? 'Syncing...' : 'Refresh Plan'}
                          </button>
                        )}
                      </div>

                      <div className="glass-panel rounded-2xl p-6">
                        <div className="text-sm text-gray-500 dark:text-gray-400 mb-2">Monthly uploads</div>
                        <div className="flex items-center justify-between mb-2">
                          <div className="text-lg font-semibold text-gray-900 dark:text-white">
                            {formatBytes(usage.monthlyUsedBytes)} / {formatBytes(usage.monthlyLimitBytes)}
                          </div>
                          <div className="text-sm text-gray-500 dark:text-gray-400">
                            MMS {formatBytes(usage.mmsBytes)} • Photos {formatBytes(usage.photoBytes)} • Files {formatBytes(usage.fileBytes)}
                          </div>
                        </div>
                        <div className="w-full bg-gray-200/50 dark:bg-gray-700/50 rounded-full h-2">
                          <div
                            className="bg-gradient-to-r from-blue-500 to-blue-600 h-2 rounded-full transition-all"
                            style={{
                              width: `${Math.min(100, (usage.monthlyUsedBytes / Math.max(1, usage.monthlyLimitBytes)) * 100)}%`,
                            }}
                          />
                        </div>
                        {usage.monthlyResetDate && (
                          <div className="text-xs text-gray-400 dark:text-gray-500 mt-2">
                            Resets on {new Date(usage.monthlyResetDate).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })}
                          </div>
                        )}
                      </div>

                      <div className="glass-panel rounded-2xl p-6 md:col-span-2">
                        <div className="text-sm text-gray-500 dark:text-gray-400 mb-2">Storage</div>
                        <div className="text-lg font-semibold text-gray-900 dark:text-white mb-2">
                          {formatBytes(usage.storageUsedBytes)} / {formatBytes(usage.storageLimitBytes)}
                        </div>
                        <div className="w-full bg-gray-200/50 dark:bg-gray-700/50 rounded-full h-2">
                          <div
                            className="bg-gradient-to-r from-emerald-500 to-emerald-600 h-2 rounded-full transition-all"
                            style={{
                              width: `${Math.min(100, (usage.storageUsedBytes / Math.max(1, usage.storageLimitBytes)) * 100)}%`,
                            }}
                          />
                        </div>
                      </div>
                    </div>

                    {usage.lastUpdatedAt && (
                      <div className="text-xs text-gray-500 dark:text-gray-400 text-center">
                        Last updated {formatDateTime(usage.lastUpdatedAt)}
                      </div>
                    )}
                  </>
                )}
              </div>
            )}

            {/* Account Tab */}
            {activeTab === 'account' && (
              <div className="space-y-6">
                <div className="glass-panel rounded-2xl p-6">
                  <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-4">Account Information</h3>
                  <p className="text-sm text-gray-600 dark:text-gray-400 mb-6">
                    Your account details and pairing status
                  </p>

                  <div className="space-y-4">
                    <div className="flex items-center justify-between p-4 bg-gray-50/50 dark:bg-white/5 rounded-xl">
                      <div>
                        <div className="text-sm text-gray-500 dark:text-gray-400 mb-1">User ID</div>
                        <div className="text-sm font-mono text-gray-700 dark:text-gray-300">
                          {userId || 'Not paired'}
                        </div>
                      </div>
                      <div className="text-right">
                        <div className="text-sm text-gray-500 dark:text-gray-400">Status</div>
                        <div className="text-sm font-medium text-green-600 dark:text-green-400">
                          {userId ? 'Active' : 'Not Connected'}
                        </div>
                      </div>
                    </div>

                    <div className="p-4 bg-blue-500/10 border border-blue-200/30 dark:border-blue-700/30 rounded-xl">
                      <div className="flex items-center gap-3">
                        <User className="w-5 h-5 text-blue-600 dark:text-blue-400" />
                        <div>
                          <div className="text-sm font-medium text-blue-900 dark:text-blue-100">Account Security</div>
                          <div className="text-sm text-blue-700 dark:text-blue-300">
                            Your account is secured with Firebase Authentication and end-to-end encryption
                          </div>
                        </div>
                      </div>
                    </div>

                    {safetyNumber && (
                      <div className="p-4 bg-gray-50/50 dark:bg-white/5 rounded-xl">
                        <div className="text-sm text-gray-500 dark:text-gray-400 mb-2">Safety Number</div>
                        <div className="font-mono text-sm text-gray-900 dark:text-gray-100 tracking-wider leading-relaxed">
                          {safetyNumber}
                        </div>
                        <div className="text-xs text-gray-400 dark:text-gray-500 mt-2">
                          Compare this number with your other devices to verify encryption keys match.
                        </div>
                      </div>
                    )}
                  </div>
                </div>

                <div className="glass-panel rounded-2xl p-6">
                  <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-4">Repair encryption</h3>
                  <p className="text-sm text-gray-600 dark:text-gray-400 mb-4">
                    If messages are still encrypted after reinstalling your apps, this clears stale keys and re-syncs everything from your phone.
                  </p>
                  <button
                    onClick={handleRepairEncryption}
                    disabled={repairLoading}
                    className="inline-flex items-center justify-center px-5 py-2.5 rounded-xl bg-gradient-to-r from-amber-500 to-orange-600 text-white text-sm font-medium hover:from-amber-600 hover:to-orange-700 disabled:from-gray-400 disabled:to-gray-500 disabled:cursor-not-allowed transition-all shadow-md shadow-amber-500/20 disabled:shadow-none"
                  >
                    {repairLoading ? 'Repairing...' : 'Repair Encryption'}
                  </button>
                  {repairStatus && (
                    <div className="mt-3 text-sm text-gray-600 dark:text-gray-300">
                      {repairStatus}
                    </div>
                  )}
                </div>

                <div className="glass-panel rounded-2xl p-6">
                  <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-4">Restore from backup</h3>
                  <p className="text-sm text-gray-600 dark:text-gray-400 mb-4">
                    If you backed up your encryption keys on Android, enter your backup passphrase to restore them.
                  </p>
                  <div className="space-y-3">
                    <input
                      type="password"
                      value={restorePassphrase}
                      onChange={(e) => setRestorePassphrase(e.target.value)}
                      placeholder="Backup passphrase (min 8 chars)"
                      className="w-full px-4 py-2.5 glass-input rounded-xl text-gray-900 dark:text-gray-100 text-sm placeholder:text-gray-400 focus:outline-none"
                    />
                    <button
                      onClick={async () => {
                        if (restorePassphrase.length < 8) {
                          setRestoreStatus('Passphrase must be at least 8 characters.')
                          return
                        }
                        setRestoreLoading(true)
                        setRestoreStatus(null)
                        try {
                          const { backups } = await vpsService.getKeyBackups()
                          if (!backups || backups.length === 0) {
                            setRestoreStatus('No backups found. Create a backup from your Android device first.')
                            return
                          }
                          const { restoreFromBackup } = await import('@/lib/e2ee')
                          await restoreFromBackup(restorePassphrase, backups)
                          setRestoreStatus('Keys restored successfully. Messages will decrypt when you return to Messages.')
                          setRestorePassphrase('')
                        } catch (err: any) {
                          setRestoreStatus(err?.message || 'Restore failed. Check your passphrase.')
                        } finally {
                          setRestoreLoading(false)
                        }
                      }}
                      disabled={restoreLoading || !restorePassphrase}
                      className="inline-flex items-center justify-center px-5 py-2.5 rounded-xl bg-gradient-to-r from-blue-500 to-blue-600 text-white text-sm font-medium hover:from-blue-600 hover:to-blue-700 disabled:from-gray-400 disabled:to-gray-500 disabled:cursor-not-allowed transition-all shadow-md shadow-blue-500/20 disabled:shadow-none"
                    >
                      {restoreLoading ? 'Restoring...' : 'Restore Keys'}
                    </button>
                  </div>
                  {restoreStatus && (
                    <div className="mt-3 text-sm text-gray-600 dark:text-gray-300">
                      {restoreStatus}
                    </div>
                  )}
                </div>

                <div className="glass-panel rounded-2xl p-6">
                  <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-4">Web key protection</h3>
                  <p className="text-sm text-gray-600 dark:text-gray-400 mb-4">
                    Protect your web encryption keys with a passphrase. You’ll need it after refresh to decrypt messages.
                  </p>

                  {keyProtectionState.encrypted ? (
                    <div className="space-y-4">
                      <div className="text-sm text-gray-600 dark:text-gray-300">
                        Status: {keyProtectionState.unlocked ? 'Unlocked (this session)' : 'Locked'}
                      </div>
                      {!keyProtectionState.unlocked && (
                        <div className="space-y-3">
                          <input
                            type="password"
                            value={unlockPassphrase}
                            onChange={(e) => setUnlockPassphrase(e.target.value)}
                            placeholder="Enter passphrase to unlock"
                            className="w-full px-4 py-2.5 glass-input rounded-xl text-gray-900 dark:text-gray-100 text-sm placeholder:text-gray-400 focus:outline-none"
                          />
                          <button
                            onClick={handleUnlockKeys}
                            disabled={keyProtectionLoading}
                            className="inline-flex items-center justify-center px-5 py-2.5 rounded-xl bg-gradient-to-r from-blue-500 to-blue-600 text-white text-sm font-medium hover:from-blue-600 hover:to-blue-700 disabled:from-gray-400 disabled:to-gray-500 disabled:cursor-not-allowed transition-all shadow-md shadow-blue-500/20 disabled:shadow-none"
                          >
                            {keyProtectionLoading ? 'Unlocking…' : 'Unlock Keys'}
                          </button>
                        </div>
                      )}
                      {keyProtectionState.unlocked && (
                        <button
                          onClick={handleLockKeys}
                          className="inline-flex items-center justify-center px-5 py-2.5 rounded-xl bg-gray-700 text-white text-sm font-medium hover:bg-gray-800 transition-all"
                        >
                          Lock Keys
                        </button>
                      )}
                    </div>
                  ) : (
                    <div className="space-y-3">
                      {keyProtectionState.legacy && (
                        <div className="text-sm text-amber-600 dark:text-amber-400">
                          Add an extra layer of protection to prevent key misuse by enabling a passphrase.
                        </div>
                      )}
                      <input
                        type="password"
                        value={passphrase}
                        onChange={(e) => setPassphrase(e.target.value)}
                        placeholder="New passphrase (min 8 chars)"
                        className="w-full px-4 py-2.5 glass-input rounded-xl text-gray-900 dark:text-gray-100 text-sm placeholder:text-gray-400 focus:outline-none"
                      />
                      <input
                        type="password"
                        value={confirmPassphrase}
                        onChange={(e) => setConfirmPassphrase(e.target.value)}
                        placeholder="Confirm passphrase"
                        className="w-full px-4 py-2.5 glass-input rounded-xl text-gray-900 dark:text-gray-100 text-sm placeholder:text-gray-400 focus:outline-none"
                      />
                      <button
                        onClick={handleEnableKeyProtection}
                        disabled={keyProtectionLoading}
                        className="inline-flex items-center justify-center px-5 py-2.5 rounded-xl bg-gradient-to-r from-blue-500 to-blue-600 text-white text-sm font-medium hover:from-blue-600 hover:to-blue-700 disabled:from-gray-400 disabled:to-gray-500 disabled:cursor-not-allowed transition-all shadow-md shadow-blue-500/20 disabled:shadow-none"
                      >
                        {keyProtectionLoading ? 'Enabling…' : 'Enable Key Protection'}
                      </button>
                    </div>
                  )}

                  {keyProtectionStatus && (
                    <div className="mt-3 text-sm text-gray-600 dark:text-gray-300">
                      {keyProtectionStatus}
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
        </div>
      </main>
    </div>
  )
}
