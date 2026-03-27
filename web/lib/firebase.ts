/**
 * firebase.ts - Stub Module (Firebase Removed)
 *
 * The web app exclusively uses VPS mode. All real-time data flows through
 * VPSService (lib/vps.ts) via REST + WebSocket.
 *
 * This stub exists only to satisfy compile-time imports from legacy code
 * paths that are never reached at runtime (isVPSMode is always true).
 * Every exported function is a no-op or returns safe defaults.
 */

// No Firebase SDK imports - VPS mode does not use Firebase at all.

// ============================================
// STUB: Firebase instances (null in VPS mode)
// ============================================

export const app = null
export const auth = null as any
export const database = null as any
export const storage = null
export const functions = null

// ============================================
// AUTHENTICATION STUBS
// ============================================

export const signInAnon = async () => null

export const adminLogin = async (_username: string, _password: string) => null

export const getCurrentUserId = () => null

export const waitForAuth = (): Promise<string | null> => Promise.resolve(null)

// ============================================
// PAIRING STUBS
// ============================================

export interface PairingSession {
  token: string
  qrPayload: string
  expiresAt: number
}

export interface PairingStatus {
  status: 'pending' | 'approved' | 'rejected' | 'expired'
  pairedUid?: string
  deviceId?: string
  customToken?: string
}

export const initiatePairing = async (
  _deviceName?: string,
  _syncGroupId?: string
): Promise<PairingSession> => {
  throw new Error('Use VPS pairing in VPS mode')
}

export const listenForPairingApproval = (
  _token: string,
  _callback: (status: PairingStatus) => void
): (() => void) => {
  return () => {}
}

// ============================================
// E2EE STUBS (all no-ops in VPS mode)
// ============================================

export const ensureWebE2EEKeyPublished = async (_userId: string) => {}

export const ensureWebE2EEKeyBackup = async (_userId: string) => {}

export const requestWebE2EEKeySync = async (_userId: string) => {}

export const requestWebE2EEKeyBackfill = async (_userId: string) => {}

export type WebE2eeBackfillStatus = {
  status?: string
  scanned?: number
  updated?: number
  skipped?: number
  error?: string
  startedAt?: number
  updatedAt?: number
  completedAt?: number
}

export const listenToWebE2EEBackfillStatus = (
  _userId: string,
  callback: (status: WebE2eeBackfillStatus | null) => void
) => {
  callback(null)
  return () => {}
}

export const waitForWebE2EEKeySyncResponse = async (
  _userId: string,
  _timeoutMs = 60000
) => {
  return true
}

// ============================================
// DEVICE STATUS STUB
// ============================================

export const listenToDeviceStatus = (
  _userId: string,
  callback: (isPaired: boolean) => void
) => {
  callback(true) // Assume paired in VPS mode
  return () => {}
}

// ============================================
// MESSAGE STUBS (dead code - VPS uses vpsService)
// ============================================

export const listenToMessagesOptimized = async (
  _userId: string,
  _callback: (messages: any[]) => void
): Promise<() => void> => {
  return () => {}
}

export const listenToSpamMessagesOptimized = (
  _userId: string,
  _callback: (messages: any[]) => void
): (() => void) => {
  return () => {}
}

export const listenToReadReceipts = (
  _userId: string,
  _callback: (receipts: Record<string, any>) => void
) => {
  return () => {}
}

export const markMessagesRead = async (
  _userId: string,
  _messageIds: string[],
  _conversationAddress: string
) => {}

export const sendSmsFromWeb = async (
  _userId: string,
  _address: string,
  _body: string
) => null

export const sendMmsFromWeb = async (
  _userId: string,
  _address: string,
  _body: string,
  _attachments: Array<{ url: string; contentType: string; fileName: string }>
) => null

export const uploadMmsImage = async (
  _userId: string,
  _file: File
): Promise<{ url: string; contentType: string; fileName: string }> => {
  throw new Error('Use VPS file upload in VPS mode')
}

export type DeliveryStatus = 'pending' | 'sending' | 'sent' | 'failed' | 'delivered'

export interface DeliveryResult {
  messageId: string
  status: DeliveryStatus
  error?: string
}

// ============================================
// USAGE / QUOTA STUBS
// ============================================

export interface UsageSummary {
  planLabel: string
  planExpiresAt: number | null
  trialDaysRemaining: number | null
  monthlyUsedBytes: number
  monthlyLimitBytes: number
  storageUsedBytes: number
  storageLimitBytes: number
  mmsBytes: number
  fileBytes: number
  photoBytes: number
  lastUpdatedAt: number | null
  monthlyResetDate: number | null
  isPaid: boolean
}

export const getUsageSummary = async (_userId: string): Promise<UsageSummary> => ({
  planLabel: 'VPS Mode',
  planExpiresAt: null,
  trialDaysRemaining: null,
  monthlyUsedBytes: 0,
  monthlyLimitBytes: 2 * 1024 * 1024 * 1024,
  storageUsedBytes: 0,
  storageLimitBytes: 1 * 1024 * 1024 * 1024,
  mmsBytes: 0,
  fileBytes: 0,
  photoBytes: 0,
  lastUpdatedAt: null,
  monthlyResetDate: null,
  isPaid: true,
})

// ============================================
// CONTACT STUBS
// ============================================

export interface ContactPhoto {
  thumbnailBase64?: string
  hash?: string
  storagePath?: string
  updatedAt?: number
}

export interface ContactSources {
  android?: boolean
  web?: boolean
  macos?: boolean
}

export interface ContactSyncMetadata {
  lastUpdatedAt: number
  lastSyncedAt?: number
  lastUpdatedBy: string
  version: number
  pendingAndroidSync: boolean
  desktopOnly: boolean
}

export interface Contact {
  id: string
  displayName: string
  phoneNumber?: string
  normalizedNumber?: string
  phoneType: string
  photo?: ContactPhoto
  notes?: string
  email?: string
  sync: ContactSyncMetadata
  sources: ContactSources
  androidContactId?: number
}

export const listenToContactsOptimized = (
  _userId: string,
  callback: (contacts: Contact[]) => void
): (() => void) => {
  callback([])
  return () => {}
}

export const createContact = async (
  _userId: string,
  _displayName: string,
  _phoneNumber: string,
  _phoneType?: string,
  _email?: string,
  _notes?: string
) => {}

export const updateContact = async (
  _userId: string,
  _contactId: string,
  _displayName: string,
  _phoneNumber: string,
  _phoneType?: string,
  _email?: string,
  _notes?: string
) => {}

export const deleteContact = async (_userId: string, _contactId: string) => {}

// ============================================
// SYNC GROUP STUBS
// ============================================

export const createSyncGroup = async (
  _deviceType: 'web' | 'macos' | 'android'
): Promise<{ success: boolean; syncGroupId?: string }> => {
  return { success: false }
}

export const recoverSyncGroup = async (
  _deviceType: 'web' | 'macos' | 'android'
): Promise<{ success: boolean; syncGroupId?: string }> => {
  return { success: false }
}

export const getSyncGroupInfo = async (
  _syncGroupId: string
): Promise<{
  success: boolean
  data?: {
    plan: string
    deviceLimit: number
    deviceCount: number
    devices: Array<{
      deviceId: string
      deviceType: string
      joinedAt: number
      status: string
    }>
  }
}> => {
  return { success: false }
}

// ============================================
// CLEANUP REPORT STUB (used by admin pages)
// ============================================

export interface CleanupStats {
  messages: number
  outgoingMessages: number
  readReceipts: number
  devices: number
  spamMessages: number
  fileTransfers: number
  notifications: number
  sessions: number
  typingIndicators: number
  total: number
}

export function generateCleanupReport(_cleanupStats: CleanupStats, _userId?: string): string {
  return 'Cleanup not available in VPS mode'
}
