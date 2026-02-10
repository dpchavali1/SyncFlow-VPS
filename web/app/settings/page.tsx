'use client'

import { useEffect, useState, useCallback } from 'react'
import { useRouter } from 'next/navigation'
import Header from '@/components/Header'
import {
  ensureWebE2EEKeyBackup,
  ensureWebE2EEKeyPublished,
  getUsageSummary,
  requestWebE2EEKeySync,
  UsageSummary,
  waitForAuth,
  waitForWebE2EEKeySyncResponse,
} from '@/lib/firebase'
import vpsService from '@/lib/vps'
import {
  hasEncryptedKeyPair,
  hasLegacyKeyPair,
  isKeyPairUnlocked,
  lockKeyPair,
  setPassphraseAndEncrypt,
  unlockKeyPairWithPassphrase,
} from '@/lib/e2ee'
import { useAppStore } from '@/lib/store'
import { NotificationSyncSettings } from '@/components/notification-sync-settings'
import { ArrowLeft, Bell, BarChart3, User } from 'lucide-react'

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
  const { userId, setUserId } = useAppStore()
  const [usage, setUsage] = useState<UsageSummary | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [activeTab, setActiveTab] = useState<TabType>('notifications')
  const [keySyncStatus, setKeySyncStatus] = useState<string | null>(null)
  const [keySyncLoading, setKeySyncLoading] = useState(false)
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
        // VPS mode: load usage from VPS API
        try {
          const data = await vpsService.getUsage()
          if (data?.usage) {
            const u = data.usage
            const plan = u.plan || 'free'
            const isPaid = plan !== 'free'
            const planLabels: Record<string, string> = { free: 'Free', monthly: 'Monthly', yearly: 'Yearly', lifetime: 'Lifetime' }
            const MONTHLY_LIMIT = isPaid ? 5 * 1024 * 1024 * 1024 : 500 * 1024 * 1024
            const STORAGE_LIMIT = isPaid ? 10 * 1024 * 1024 * 1024 : 1 * 1024 * 1024 * 1024

            setUsage({
              planLabel: planLabels[plan] || plan,
              planExpiresAt: u.planExpiresAt || null,
              trialDaysRemaining: isPaid ? null : (u.trialStartedAt ? Math.max(0, 30 - Math.floor((Date.now() - u.trialStartedAt) / 86400000)) : 30),
              monthlyUsedBytes: u.monthlyUploadBytes || 0,
              monthlyLimitBytes: MONTHLY_LIMIT,
              storageUsedBytes: u.storageBytes || 0,
              storageLimitBytes: STORAGE_LIMIT,
              mmsBytes: u.monthlyMmsBytes || 0,
              fileBytes: u.monthlyFileBytes || 0,
              photoBytes: u.monthlyPhotoBytes || 0,
              lastUpdatedAt: u.lastUpdatedAt || null,
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
  }, [refreshKeyProtection])

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

  const handleKeySync = async () => {
    if (!userId) return
    setKeySyncLoading(true)
    setKeySyncStatus(null)
    try {
      await requestWebE2EEKeySync(userId)
      await waitForWebE2EEKeySyncResponse(userId)
      setKeySyncStatus('Keys synced successfully. Messages will decrypt when you return to Messages.')
    } catch (err: any) {
      setKeySyncStatus(err?.message || 'Key sync failed')
    } finally {
      setKeySyncLoading(false)
    }
  }

  if (!userId) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="animate-spin rounded-full h-16 w-16 border-b-2 border-blue-600"></div>
      </div>
    )
  }

  return (
    <div className="flex flex-col h-screen bg-gray-100 dark:bg-gray-900">
      <Header />

      <main className="flex-1 overflow-auto p-6">
        <div className="max-w-3xl mx-auto">
          {/* Back Button */}
          <div className="mb-6">
            <button
              onClick={() => router.push('/messages')}
              className="flex items-center gap-2 px-4 py-2 text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white hover:bg-gray-100 dark:hover:bg-gray-800 rounded-lg transition-colors"
            >
              <ArrowLeft className="w-4 h-4" />
              Back to Messages
            </button>
          </div>

          <div className="mb-6">
            <h2 className="text-2xl font-semibold text-gray-900 dark:text-white">Settings</h2>
          </div>

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

          {/* Tab Navigation */}
          <div className="mb-6">
            <div className="border-b border-gray-200 dark:border-gray-700">
              <nav className="-mb-px flex space-x-8">
                <button
                  onClick={() => setActiveTab('notifications')}
                  className={`py-2 px-1 border-b-2 font-medium text-sm flex items-center gap-2 ${
                    activeTab === 'notifications'
                      ? 'border-blue-500 text-blue-600 dark:text-blue-400'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300 dark:text-gray-400 dark:hover:text-gray-300'
                  }`}
                >
                  <Bell className="w-4 h-4" />
                  Notifications
                </button>

                <button
                  onClick={() => setActiveTab('usage')}
                  className={`py-2 px-1 border-b-2 font-medium text-sm flex items-center gap-2 ${
                    activeTab === 'usage'
                      ? 'border-blue-500 text-blue-600 dark:text-blue-400'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300 dark:text-gray-400 dark:hover:text-gray-300'
                  }`}
                >
                  <BarChart3 className="w-4 h-4" />
                  Usage & Limits
                </button>

                <button
                  onClick={() => setActiveTab('account')}
                  className={`py-2 px-1 border-b-2 font-medium text-sm flex items-center gap-2 ${
                    activeTab === 'account'
                      ? 'border-blue-500 text-blue-600 dark:text-blue-400'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300 dark:text-gray-400 dark:hover:text-gray-300'
                  }`}
                >
                  <User className="w-4 h-4" />
                  Account
                </button>
              </nav>
            </div>
          </div>

          {/* Tab Content */}
          <div className="space-y-6">
            {/* Notifications Tab */}
            {activeTab === 'notifications' && (
              <div className="space-y-6">
                <div className="bg-white dark:bg-gray-800 rounded-xl border border-gray-200 dark:border-gray-700 p-6">
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
                        className="px-4 py-2 rounded-lg bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-200 border border-gray-200 dark:border-gray-700 hover:bg-gray-50 dark:hover:bg-gray-700"
                        disabled={isLoading}
                      >
                        {isLoading ? 'Refreshing...' : 'Refresh'}
                      </button>
                    </div>

                    <div className="grid gap-6 md:grid-cols-2">
                      <div className="bg-white dark:bg-gray-800 rounded-xl border border-gray-200 dark:border-gray-700 p-6">
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
                      </div>

                      <div className="bg-white dark:bg-gray-800 rounded-xl border border-gray-200 dark:border-gray-700 p-6">
                        <div className="text-sm text-gray-500 dark:text-gray-400 mb-2">Monthly uploads</div>
                        <div className="flex items-center justify-between mb-2">
                          <div className="text-lg font-semibold text-gray-900 dark:text-white">
                            {formatBytes(usage.monthlyUsedBytes)} / {formatBytes(usage.monthlyLimitBytes)}
                          </div>
                          <div className="text-sm text-gray-500 dark:text-gray-400">
                            MMS {formatBytes(usage.mmsBytes)} • Photos {formatBytes(usage.photoBytes)} • Files {formatBytes(usage.fileBytes)}
                          </div>
                        </div>
                        <div className="w-full bg-gray-100 dark:bg-gray-700 rounded-full h-2">
                          <div
                            className="bg-blue-600 h-2 rounded-full"
                            style={{
                              width: `${Math.min(100, (usage.monthlyUsedBytes / Math.max(1, usage.monthlyLimitBytes)) * 100)}%`,
                            }}
                          />
                        </div>
                      </div>

                      <div className="bg-white dark:bg-gray-800 rounded-xl border border-gray-200 dark:border-gray-700 p-6 md:col-span-2">
                        <div className="text-sm text-gray-500 dark:text-gray-400 mb-2">Storage</div>
                        <div className="text-lg font-semibold text-gray-900 dark:text-white mb-2">
                          {formatBytes(usage.storageUsedBytes)} / {formatBytes(usage.storageLimitBytes)}
                        </div>
                        <div className="w-full bg-gray-100 dark:bg-gray-700 rounded-full h-2">
                          <div
                            className="bg-emerald-500 h-2 rounded-full"
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
                <div className="bg-white dark:bg-gray-800 rounded-xl border border-gray-200 dark:border-gray-700 p-6">
                  <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-4">Account Information</h3>
                  <p className="text-sm text-gray-600 dark:text-gray-400 mb-6">
                    Your account details and pairing status
                  </p>

                  <div className="space-y-4">
                    <div className="flex items-center justify-between p-4 bg-gray-50 dark:bg-gray-700 rounded-lg">
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

                    <div className="p-4 bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-lg">
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
                  </div>
                </div>

                <div className="bg-white dark:bg-gray-800 rounded-xl border border-gray-200 dark:border-gray-700 p-6">
                  <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-4">End-to-end encryption</h3>
                  <p className="text-sm text-gray-600 dark:text-gray-400 mb-4">
                    If messages show “sync keys to decrypt”, request a key sync from your phone.
                  </p>
                  <button
                    onClick={handleKeySync}
                    disabled={keySyncLoading}
                    className="inline-flex items-center justify-center px-4 py-2 rounded-lg bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-60 disabled:cursor-not-allowed transition-colors"
                  >
                    {keySyncLoading ? 'Syncing…' : 'Sync Keys'}
                  </button>
                  {keySyncStatus && (
                    <div className="mt-3 text-sm text-gray-600 dark:text-gray-300">
                      {keySyncStatus}
                    </div>
                  )}
                </div>

                <div className="bg-white dark:bg-gray-800 rounded-xl border border-gray-200 dark:border-gray-700 p-6">
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
                            className="w-full px-3 py-2 rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 text-gray-900 dark:text-gray-100"
                          />
                          <button
                            onClick={handleUnlockKeys}
                            disabled={keyProtectionLoading}
                            className="inline-flex items-center justify-center px-4 py-2 rounded-lg bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-60 disabled:cursor-not-allowed transition-colors"
                          >
                            {keyProtectionLoading ? 'Unlocking…' : 'Unlock Keys'}
                          </button>
                        </div>
                      )}
                      {keyProtectionState.unlocked && (
                        <button
                          onClick={handleLockKeys}
                          className="inline-flex items-center justify-center px-4 py-2 rounded-lg bg-gray-700 text-white hover:bg-gray-800 transition-colors"
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
                        className="w-full px-3 py-2 rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 text-gray-900 dark:text-gray-100"
                      />
                      <input
                        type="password"
                        value={confirmPassphrase}
                        onChange={(e) => setConfirmPassphrase(e.target.value)}
                        placeholder="Confirm passphrase"
                        className="w-full px-3 py-2 rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 text-gray-900 dark:text-gray-100"
                      />
                      <button
                        onClick={handleEnableKeyProtection}
                        disabled={keyProtectionLoading}
                        className="inline-flex items-center justify-center px-4 py-2 rounded-lg bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-60 disabled:cursor-not-allowed transition-colors"
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
