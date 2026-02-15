/**
 * VPS WebSocket + REST service for the web client.
 *
 * Replaces the Firebase Realtime Database path with a self-hosted VPS
 * backend (Express + PostgreSQL). Provides:
 *   - JWT-based auth with access/refresh token rotation
 *   - QR-code pairing flow (web generates token, Android scans + approves)
 *   - REST endpoints for messages, contacts, calls, file transfers, spam
 *   - WebSocket for real-time push (message_added, delivery_status_changed, etc.)
 *   - Full admin API surface (users, cleanup, R2 storage, crash reports)
 *
 * The class is exported as a true singleton (attached to window) so it
 * survives Next.js chunk re-evaluation across page navigations.
 */

import { getPublicKeyX963Base64, getOrCreateSigningKeyPair } from './e2ee';
import { PhoneNumberNormalizer } from './phoneNumberNormalizer';
import { secureStorage } from './secureStorage';

// VPS Server Configuration
const VPS_BASE_URL = process.env.NEXT_PUBLIC_VPS_URL || 'https://api.sfweb.app';
// WebSocket URL: convert http(s) to ws(s), same host/port as REST API
const VPS_WS_URL = VPS_BASE_URL.replace(/^http/, 'ws');

// Storage keys
const STORAGE_KEYS = {
  accessToken: 'vps_access_token',
  refreshToken: 'vps_refresh_token',
  userId: 'vps_user_id',
  deviceId: 'vps_device_id',
};

// Types
export interface VPSUser {
  userId: string;
  deviceId: string;
  admin?: boolean;
}

export interface VPSMessage {
  id: string;
  threadId?: number;
  address: string;
  contactName?: string;
  body?: string;
  date: number;
  type: number;
  read: boolean;
  isMms: boolean;
  encrypted?: boolean;
  encryptedBody?: string;
  encryptedNonce?: string;
  keyMap?: Record<string, string>;
  mmsParts?: any[];
  deliveryStatus?: string;
}

export interface VPSDeviceE2eeKey {
  encryptedKey: string;
  createdAt?: number;
  updatedAt?: number | null;
}

export interface VPSContact {
  id: string;
  displayName?: string;
  phoneNumbers: string[];
  emails?: string[];
  photoThumbnail?: string;
}

export interface VPSCallHistoryEntry {
  id: string;
  phoneNumber: string;
  contactName?: string;
  callType: string;
  callDate: number;
  duration: number;
  simSubscriptionId?: number;
}

export interface VPSDevice {
  id: string;
  name?: string;
  deviceType: 'android' | 'macos' | 'web';
  pairedAt: string;
  lastSeen: string;
  isCurrent: boolean;
}

export interface VPSPairingRequest {
  pairingToken: string;
  deviceId: string;
  tempUserId: string;
  accessToken: string;
  refreshToken: string;
}

export interface VPSPairingStatus {
  status: string;
  deviceName?: string;
  approved: boolean;
}

type WebSocketEventType =
  | 'connected'
  | 'disconnected'
  | 'error'
  | 'message_added'
  | 'message_updated'
  | 'message_deleted'
  | 'messages_deleted'
  | 'contact_added'
  | 'contact_updated'
  | 'contact_deleted'
  | 'call_added'
  | 'messages_synced'
  | 'device_removed'
  | 'e2ee_key_available'
  | 'outgoing_status_changed'
  | 'delivery_status_changed'
  | 'spam_updated';

/** Default request timeout in milliseconds (30 seconds). */
const REQUEST_TIMEOUT_MS = 30_000;

// ==================== WebSocket Event Data Types ====================

/** Payload for message-related WebSocket events. */
export interface WSMessageData {
  id?: string;
  messageId?: string;
  messages?: VPSMessage[];
  [key: string]: unknown;
}

/** Payload for device-related WebSocket events. */
export interface WSDeviceData {
  id?: string;
  deviceId?: string;
  [key: string]: unknown;
}

/** Payload for delivery/outgoing status events. */
export interface WSStatusData {
  id?: string;
  status?: string;
  deliveryStatus?: string;
  [key: string]: unknown;
}

/** Union of all possible WebSocket event payloads. */
export type WSEventData = WSMessageData | WSDeviceData | WSStatusData | Event | null;

/** Parsed WebSocket message envelope. */
interface WSMessageEnvelope {
  type: string;
  data?: Record<string, unknown>;
}

type EventCallback = (data: WSEventData) => void;

// VPS Service Class
class VPSService {
  private accessToken: string | null = null;
  private refreshToken: string | null = null;
  private userId: string | null = null;
  private deviceId: string | null = null;
  private ws: WebSocket | null = null;
  private wsReconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private eventListeners: Map<WebSocketEventType, Set<EventCallback>> = new Map();
  private reconnectAttempts = 0;
  private e2eeKeyPushResolver: (() => void) | null = null;
  private tokenRestorePromise: Promise<void> | null = null;

  constructor() {
    // Restore non-secret identifiers from localStorage (synchronous)
    if (typeof window !== 'undefined') {
      this.userId = localStorage.getItem(STORAGE_KEYS.userId);
      this.deviceId = localStorage.getItem(STORAGE_KEYS.deviceId);

      // Migrate tokens from localStorage to secureStorage (IndexedDB) if present
      // Then restore tokens from secureStorage asynchronously
      this.tokenRestorePromise = this.restoreTokensFromSecureStorage();
    }
  }

  /**
   * Restore tokens from IndexedDB-based secureStorage.
   * If tokens still exist in localStorage (pre-migration), migrate them over
   * and remove from localStorage.
   */
  private async restoreTokensFromSecureStorage(): Promise<void> {
    try {
      // Check for legacy localStorage tokens and migrate
      const legacyAccess = localStorage.getItem(STORAGE_KEYS.accessToken);
      const legacyRefresh = localStorage.getItem(STORAGE_KEYS.refreshToken);
      if (legacyAccess) {
        await secureStorage.setItem(STORAGE_KEYS.accessToken, legacyAccess);
        localStorage.removeItem(STORAGE_KEYS.accessToken);
      }
      if (legacyRefresh) {
        await secureStorage.setItem(STORAGE_KEYS.refreshToken, legacyRefresh);
        localStorage.removeItem(STORAGE_KEYS.refreshToken);
      }

      // Restore from secureStorage
      const [storedAccess, storedRefresh] = await Promise.all([
        secureStorage.getItem(STORAGE_KEYS.accessToken),
        secureStorage.getItem(STORAGE_KEYS.refreshToken),
      ]);
      if (storedAccess) this.accessToken = storedAccess;
      if (storedRefresh) this.refreshToken = storedRefresh;
    } catch (err) {
      console.error('[VPS] Failed to restore tokens from secureStorage:', err);
    }
  }

  /**
   * Ensure tokens have been restored from secureStorage before proceeding.
   * Call this before any operation that depends on authentication state.
   */
  async ensureTokensRestored(): Promise<void> {
    if (this.tokenRestorePromise) {
      await this.tokenRestorePromise;
      this.tokenRestorePromise = null;
    }
  }

  // ==================== Token Management ====================

  get isAuthenticated(): boolean {
    return !!this.accessToken && !!this.userId;
  }

  get currentUserId(): string | null {
    return this.userId;
  }

  get currentDeviceId(): string | null {
    return this.deviceId;
  }

  private async saveTokens(accessToken: string, refreshToken: string, userId: string, deviceId: string) {
    this.accessToken = accessToken;
    this.refreshToken = refreshToken;
    this.userId = userId;
    this.deviceId = deviceId;

    if (typeof window !== 'undefined') {
      // Non-secret identifiers stay in localStorage
      localStorage.setItem(STORAGE_KEYS.userId, userId);
      localStorage.setItem(STORAGE_KEYS.deviceId, deviceId);

      // Tokens go to secureStorage (IndexedDB) instead of localStorage
      try {
        await Promise.all([
          secureStorage.setItem(STORAGE_KEYS.accessToken, accessToken),
          secureStorage.setItem(STORAGE_KEYS.refreshToken, refreshToken),
        ]);
      } catch (err) {
        console.error('[VPS] Failed to persist tokens to secureStorage:', err);
      }
    }
  }

  clearTokens() {
    this.accessToken = null;
    this.refreshToken = null;
    this.userId = null;
    this.deviceId = null;

    if (typeof window !== 'undefined') {
      // Clear all syncflow_* and vps_* keys from localStorage
      const keysToRemove: string[] = [];
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (key && (key.startsWith('syncflow_') || key.startsWith('vps_'))) {
          keysToRemove.push(key);
        }
      }
      keysToRemove.forEach(key => localStorage.removeItem(key));

      // Remove tokens from secureStorage (IndexedDB)
      secureStorage.removeItem(STORAGE_KEYS.accessToken).catch(() => {});
      secureStorage.removeItem(STORAGE_KEYS.refreshToken).catch(() => {});
    }

    this.disconnectWebSocket();
  }

  // ==================== HTTP Helpers ====================

  private async request<T>(method: string, path: string, body?: unknown, skipAuth = false): Promise<T> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };

    if (!skipAuth && this.accessToken) {
      headers['Authorization'] = `Bearer ${this.accessToken}`;
    }

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

    try {
      const response = await fetch(`${VPS_BASE_URL}${path}`, {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined,
        signal: controller.signal,
      });

      if (response.status === 401 && !skipAuth) {
        // Try to refresh token
        await this.refreshAccessToken();
        return this.request(method, path, body, false);
      }

      if (!response.ok) {
        const error = await response.text();
        throw new Error(`HTTP ${response.status}: ${error}`);
      }

      return response.json();
    } finally {
      clearTimeout(timeoutId);
    }
  }

  private async refreshAccessToken(): Promise<void> {
    if (!this.refreshToken) {
      throw new Error('No refresh token');
    }

    const response = await this.request<{ accessToken: string }>(
      'POST',
      '/api/auth/refresh',
      { refreshToken: this.refreshToken },
      true
    );

    this.accessToken = response.accessToken;
    if (typeof window !== 'undefined') {
      // Persist refreshed access token to secureStorage (IndexedDB)
      secureStorage.setItem(STORAGE_KEYS.accessToken, response.accessToken).catch(() => {});
    }
  }

  // ==================== Authentication ====================

  async adminLogin(username: string, password: string): Promise<{ userId: string; admin: boolean }> {
    const response = await this.request<{
      userId: string;
      deviceId: string;
      admin: boolean;
      accessToken: string;
      refreshToken: string;
    }>('POST', '/api/auth/admin/login', { username, password }, true);

    this.saveTokens(response.accessToken, response.refreshToken, response.userId, response.deviceId);
    return { userId: response.userId, admin: response.admin };
  }

  async initiatePairing(deviceName: string): Promise<VPSPairingRequest> {
    const signingKey = await getOrCreateSigningKeyPair();
    const body: Record<string, string> = { deviceName, deviceType: 'web' };
    if (signingKey) body.signingKey = signingKey;

    const response = await this.request<VPSPairingRequest>(
      'POST',
      '/api/auth/pair/initiate',
      body,
      true
    );

    this.saveTokens(
      response.accessToken,
      response.refreshToken,
      response.tempUserId,
      response.deviceId
    );

    return response;
  }

  async checkPairingStatus(token: string): Promise<VPSPairingStatus> {
    const response = await fetch(`${VPS_BASE_URL}/api/auth/pair/status/${token}`);

    if (response.status === 404) {
      throw new Error('Pairing request expired');
    }

    if (!response.ok) {
      throw new Error('Failed to check pairing status');
    }

    return response.json();
  }

  async redeemPairing(token: string): Promise<VPSUser> {
    const signingKey = await getOrCreateSigningKeyPair();
    const body: Record<string, string> = { token, deviceType: 'web' };
    if (signingKey) body.signingKey = signingKey;

    const response = await this.request<{
      userId: string;
      deviceId: string;
      accessToken: string;
      refreshToken: string;
    }>(
      'POST',
      '/api/auth/pair/redeem',
      body,
      true
    );

    this.saveTokens(
      response.accessToken,
      response.refreshToken,
      response.userId,
      response.deviceId
    );

    // Connect WebSocket after successful pairing
    this.connectWebSocket();

    return { userId: response.userId, deviceId: response.deviceId };
  }

  async waitForPairingApproval(token: string, timeoutMs = 300000): Promise<VPSUser> {
    const startTime = Date.now();

    while (Date.now() - startTime < timeoutMs) {
      let status;
      try {
        status = await this.checkPairingStatus(token);
      } catch (error: any) {
        if (error.message === 'Pairing request expired') {
          throw error;
        }
        // Continue polling on status check errors
        console.warn('[Pairing] Status check error, retrying...', error.message);
        await new Promise(resolve => setTimeout(resolve, 2000));
        continue;
      }

      if (status.approved) {
        // Redeem errors should NOT be swallowed - throw immediately
        return this.redeemPairing(token);
      }

      if (status.status === 'expired') {
        throw new Error('Pairing request expired');
      }

      // Wait 2 seconds before checking again
      await new Promise(resolve => setTimeout(resolve, 2000));
    }

    throw new Error('Pairing timeout');
  }

  async getCurrentUser(): Promise<VPSUser> {
    return this.request('GET', '/api/auth/me');
  }

  // ==================== Messages ====================

  async getMessages(options: {
    limit?: number;
    before?: number;
    after?: number;
    threadId?: number;
  } = {}): Promise<{ messages: VPSMessage[]; hasMore: boolean }> {
    const params = new URLSearchParams();
    if (options.limit) params.set('limit', String(options.limit));
    if (options.before) params.set('before', String(options.before));
    if (options.after) params.set('after', String(options.after));
    if (options.threadId) params.set('threadId', String(options.threadId));

    return this.request('GET', `/api/messages?${params}`);
  }

  async sendMessage(address: string, body: string, simSubscriptionId?: number): Promise<void> {
    await this.request('POST', '/api/messages/send', {
      address,
      body,
      simSubscriptionId,
    });
  }

  async markMessageRead(messageId: string): Promise<void> {
    await this.request('PUT', `/api/messages/${messageId}/read`, null);
  }

  async deleteMessages(messageIds: string[]): Promise<{ deleted: number }> {
    return await this.request('DELETE', '/api/messages', { messageIds });
  }

  async sendMmsMessage(address: string, body: string, attachments: { fileKey: string; contentType: string; fileName: string }[]): Promise<void> {
    await this.request('POST', '/api/messages/send', {
      address,
      body,
      isMms: true,
      attachments,
    });
  }

  async getFileUploadUrl(fileName: string, contentType: string, fileSize: number): Promise<{ uploadUrl: string; fileKey: string }> {
    return await this.request('POST', '/api/file-transfers/upload-url', {
      fileName,
      contentType,
      fileSize,
      transferType: 'mms',
    });
  }

  async confirmFileUpload(fileKey: string, fileSize: number): Promise<void> {
    await this.request('POST', '/api/file-transfers/confirm-upload', {
      fileKey,
      fileSize,
      transferType: 'mms',
    });
  }

  // ==================== File Transfers ====================

  async getFileDownloadUrl(fileKey: string): Promise<string> {
    const response = await this.request<{ downloadUrl: string }>(
      'POST',
      '/api/file-transfers/download-url',
      { fileKey }
    );

    if (!response?.downloadUrl) {
      throw new Error('No download URL returned');
    }

    return response.downloadUrl;
  }

  // ==================== Contacts ====================

  async getContacts(options: { search?: string; limit?: number } = {}): Promise<{ contacts: VPSContact[] }> {
    const params = new URLSearchParams();
    if (options.limit) params.set('limit', String(options.limit));
    if (options.search) params.set('search', options.search);

    return this.request('GET', `/api/contacts?${params}`);
  }

  async createContact(contact: {
    id?: string;
    displayName: string;
    phoneNumbers: string[];
    emails?: string[];
  }): Promise<void> {
    const contactId = contact.id || `web_${Date.now()}`;
    await this.request('POST', '/api/contacts/sync', {
      contacts: [{
        id: contactId,
        displayName: contact.displayName,
        phoneNumbers: contact.phoneNumbers,
        emails: contact.emails || [],
      }],
    });
  }

  async updateContact(contactId: string, data: {
    displayName?: string;
    phoneNumbers?: string[];
    emails?: string[];
    photoThumbnail?: string;
  }): Promise<void> {
    await this.request('PUT', `/api/contacts/${contactId}`, data);
  }

  async deleteContact(contactId: string): Promise<void> {
    await this.request('DELETE', `/api/contacts/${contactId}`, null);
  }

  // ==================== Call History ====================

  async getCallHistory(options: {
    limit?: number;
    before?: number;
  } = {}): Promise<{ calls: VPSCallHistoryEntry[]; hasMore: boolean }> {
    const params = new URLSearchParams();
    if (options.limit) params.set('limit', String(options.limit));
    if (options.before) params.set('before', String(options.before));

    return this.request('GET', `/api/calls?${params}`);
  }

  async requestCall(phoneNumber: string, simSubscriptionId?: number): Promise<void> {
    await this.request('POST', '/api/calls/request', {
      phoneNumber: PhoneNumberNormalizer.toE164(phoneNumber),
      simSubscriptionId,
    });
  }

  // ==================== Devices ====================

  async getDevices(): Promise<{ devices: VPSDevice[] }> {
    return this.request('GET', '/api/devices');
  }

  async removeDevice(deviceId: string): Promise<void> {
    await this.request('DELETE', `/api/devices/${deviceId}`);
  }

  // ==================== WebSocket ====================

  connectWebSocket() {
    if (!this.accessToken) {
      if (process.env.NODE_ENV === 'development') {
        console.log('[VPS] Cannot connect WebSocket - not authenticated');
      }
      return;
    }

    if (this.ws?.readyState === WebSocket.OPEN) {
      return;
    }

    // Close any existing socket in CONNECTING state
    if (this.ws) {
      this.ws.onclose = null; // Prevent triggering reconnect
      this.ws.close();
      this.ws = null;
    }

    try {
      this.ws = new WebSocket(`${VPS_WS_URL}?token=${this.accessToken}`);

      this.ws.onopen = () => {
        this.reconnectAttempts = 0;
        this.emit('connected', null);

        // Subscribe to channels - ensure connection is really open
        if (this.ws?.readyState === WebSocket.OPEN) {
          this.ws.send(JSON.stringify({
            type: 'subscribe',
            channels: ['messages', 'contacts', 'calls', 'devices', 'spam'],
          }));
        }
      };

      this.ws.onclose = () => {
        this.emit('disconnected', null);
        this.scheduleReconnect();
      };

      this.ws.onerror = (error) => {
        console.error('[VPS] WebSocket error:', error);
        this.emit('error', error);
      };

      this.ws.onmessage = (event) => {
        try {
          const envelope: WSMessageEnvelope = JSON.parse(event.data);
          if (envelope.type) {
            // Handle E2EE key push: wake up polling immediately
            if (envelope.type === 'e2ee_key_available') {
              const targetDeviceId = envelope.data?.deviceId;
              if (targetDeviceId === this.deviceId && this.e2eeKeyPushResolver) {
                this.e2eeKeyPushResolver();
                this.e2eeKeyPushResolver = null;
              }
            }

            // Handle device removal: if this device was unpaired remotely, clear auth
            if (envelope.type === 'device_removed') {
              const removedId = (envelope.data?.id || envelope.data?.deviceId || '') as string;
              if (removedId === this.deviceId || removedId === '') {
                this.clearTokens();
                // Redirect to pairing screen
                if (typeof window !== 'undefined') {
                  window.location.href = '/';
                }
              }
            }
            this.emit(envelope.type as WebSocketEventType, (envelope.data || envelope) as WSEventData);
          }
        } catch (error) {
          console.error('[VPS] Failed to parse WebSocket message:', error);
        }
      };
    } catch (error) {
      console.error('[VPS] Failed to create WebSocket:', error);
      this.scheduleReconnect();
    }
  }

  disconnectWebSocket() {
    if (this.wsReconnectTimer) {
      clearTimeout(this.wsReconnectTimer);
      this.wsReconnectTimer = null;
    }

    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }

  /**
   * Reconnect with exponential backoff.
   * Delay doubles each attempt (2s, 4s, 8s, 16s) and caps at 30s.
   * Resets to 0 on successful connection (see ws.onopen).
   * Skipped entirely if auth tokens have been cleared (user logged out).
   */
  private scheduleReconnect() {
    if (!this.accessToken) return;

    this.reconnectAttempts++;
    if (this.reconnectAttempts > 20) {
      console.warn('[VPS] WebSocket max reconnect attempts (20) reached, giving up');
      return;
    }
    const delay = Math.min(Math.pow(2, Math.min(this.reconnectAttempts, 5)) * 1000, 30000);

    this.wsReconnectTimer = setTimeout(() => {
      this.connectWebSocket();
    }, delay);
  }

  // ==================== Event Emitter ====================

  on(event: WebSocketEventType, callback: EventCallback) {
    if (!this.eventListeners.has(event)) {
      this.eventListeners.set(event, new Set());
    }
    this.eventListeners.get(event)!.add(callback);
  }

  off(event: WebSocketEventType, callback: EventCallback) {
    this.eventListeners.get(event)?.delete(callback);
  }

  private emit(event: WebSocketEventType, data: WSEventData) {
    this.eventListeners.get(event)?.forEach(callback => callback(data));
  }

  // ==================== QR Code Generation ====================

  async generatePairingQRData(): Promise<{ token: string; qrData: string }> {
    const deviceName = typeof window !== 'undefined' ? 'Web Browser' : 'Web Client';
    const pairing = await this.initiatePairing(deviceName);

    const publicKeyX963 = await getPublicKeyX963Base64();
    const encodedKey = publicKeyX963 ? encodeURIComponent(publicKeyX963) : '';

    // QR code data format: syncflow://pair?token=<token>&server=<server>&deviceId=<deviceId>&e2eeKey=<publicKeyX963>
    const qrData = `syncflow://pair?token=${pairing.pairingToken}&server=${VPS_BASE_URL}&deviceId=${pairing.deviceId}` +
      (publicKeyX963 ? `&e2eeKey=${encodedKey}` : '');

    return { token: pairing.pairingToken, qrData };
  }

  // ==================== E2EE Keys ====================

  async getDeviceE2eeKeys(): Promise<Record<string, VPSDeviceE2eeKey>> {
    if (!this.userId) throw new Error('Not authenticated');
    return this.request('GET', `/api/e2ee/device-keys/${this.userId}`);
  }

  async waitForDeviceE2eeKey(timeoutMs = 60000, intervalMs = 2000): Promise<string | null> {
    if (!this.userId || !this.deviceId) return null;
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      const keys = await this.getDeviceE2eeKeys();
      const entry = keys[this.deviceId];
      if (entry?.encryptedKey) return entry.encryptedKey;
      // Wait for poll interval OR e2ee_key_available push (whichever comes first)
      await new Promise<void>((resolve) => {
        const timer = setTimeout(resolve, intervalMs);
        this.e2eeKeyPushResolver = () => {
          clearTimeout(timer);
          resolve();
        };
      });
      this.e2eeKeyPushResolver = null;
    }
    return null;
  }

  async publishE2EEPublicKey(publicKey: string): Promise<{ success: boolean }> {
    return this.request('POST', '/api/e2ee/public-key', { publicKey });
  }

  async requestE2EEKeySync(targetDevice: string): Promise<{ success: boolean }> {
    return this.request('POST', '/api/e2ee/key-request', { targetDevice });
  }

  async repairEncryption(): Promise<{ success: boolean }> {
    return this.request('POST', '/api/e2ee/repair');
  }

  async getKeyBackups(): Promise<{ backups: Array<{ encryptedBackup: string; salt: string; iterations: number; keyVersion: number; createdAt: number }> }> {
    return this.request('GET', '/api/e2ee/key-backup');
  }

  // ==================== Admin API ====================

  async deleteAdminUser(userId: string): Promise<{ success: boolean; message: string }> {
    return this.request('DELETE', `/api/admin/users/${userId}`);
  }

  async getAdminUserMessages(userId: string, options: { limit?: number; offset?: number } = {}): Promise<any> {
    const params = new URLSearchParams();
    if (options.limit) params.set('limit', String(options.limit));
    if (options.offset) params.set('offset', String(options.offset));
    return this.request('GET', `/api/admin/users/${userId}/messages?${params}`);
  }

  async getAdminDevices(options: { limit?: number; offset?: number } = {}): Promise<any> {
    const params = new URLSearchParams();
    if (options.limit) params.set('limit', String(options.limit));
    if (options.offset) params.set('offset', String(options.offset));
    return this.request('GET', `/api/admin/devices?${params}`);
  }

  async getAdminPairingRequests(): Promise<any> {
    return this.request('GET', '/api/admin/pairing-requests');
  }

  async getAdminOverview(): Promise<any> {
    return this.request('GET', '/api/admin/overview');
  }

  async getAdminStats(): Promise<any> {
    return this.request('GET', '/api/admin/stats');
  }

  async getAdminUsers(params?: { limit?: number; offset?: number; search?: string }): Promise<any> {
    const q = new URLSearchParams();
    if (params?.limit) q.set('limit', String(params.limit));
    if (params?.offset) q.set('offset', String(params.offset));
    if (params?.search) q.set('search', params.search);
    const qs = q.toString();
    return this.request('GET', `/api/admin/users${qs ? '?' + qs : ''}`);
  }

  async getAdminUserDetails(userId: string): Promise<any> {
    return this.request('GET', `/api/admin/users/${userId}`);
  }

  // ==================== Admin Cleanup ====================

  async runAutoCleanup(): Promise<any> {
    return this.request('POST', '/api/admin/cleanup/auto');
  }

  async cleanupOldMessages(olderThanDays: number, mmsOnly?: boolean): Promise<any> {
    return this.request('POST', '/api/admin/cleanup/messages', { olderThanDays, mmsOnly });
  }

  async cleanupOldDevices(): Promise<any> {
    return this.request('POST', '/api/admin/cleanup/devices');
  }

  async bulkDeleteInactiveUsers(inactiveDays: number = 90): Promise<any> {
    return this.request('POST', '/api/admin/users/bulk-delete', { inactiveDays });
  }

  async getOrphans(): Promise<any> {
    return this.request('GET', '/api/admin/orphans');
  }

  async cleanupOrphans(deleteUsersWithoutDevices?: boolean): Promise<any> {
    return this.request('POST', '/api/admin/cleanup/orphans', { deleteUsersWithoutDevices });
  }

  async getDuplicates(): Promise<any> {
    return this.request('GET', '/api/admin/duplicates');
  }

  async cleanupDuplicates(keepUserId: string, deleteUserIds: string[]): Promise<any> {
    return this.request('POST', '/api/admin/cleanup/duplicates', { keepUserId, deleteUserIds });
  }

  // ==================== Admin User Management ====================

  async setUserPlan(userId: string, plan: string, expiresAt?: string): Promise<any> {
    return this.request('POST', `/api/admin/users/${userId}/plan`, { plan, expiresAt });
  }

  async recalculateUserStorage(userId: string): Promise<any> {
    return this.request('POST', `/api/admin/users/${userId}/recalculate-storage`);
  }

  // ==================== Admin R2 Storage ====================

  async getR2Analytics(): Promise<any> {
    return this.request('GET', '/api/admin/r2/analytics');
  }

  async getR2Files(params?: { type?: string; limit?: number; offset?: number }): Promise<any> {
    const q = new URLSearchParams();
    if (params?.type) q.set('type', params.type);
    if (params?.limit) q.set('limit', String(params.limit));
    if (params?.offset) q.set('offset', String(params.offset));
    const qs = q.toString();
    return this.request('GET', `/api/admin/r2/files${qs ? '?' + qs : ''}`);
  }

  async cleanupR2Files(olderThanDays: number, type?: string): Promise<any> {
    return this.request('POST', '/api/admin/r2/cleanup', { olderThanDays, type });
  }

  async deleteR2File(key: string): Promise<any> {
    return this.request('DELETE', `/api/admin/r2/files/${encodeURIComponent(key)}`);
  }

  // ==================== Admin Crash Reports ====================

  async getCrashReports(params?: { limit?: number; offset?: number }): Promise<any> {
    const q = new URLSearchParams();
    if (params?.limit) q.set('limit', String(params.limit));
    if (params?.offset) q.set('offset', String(params.offset));
    const qs = q.toString();
    return this.request('GET', `/api/admin/crashes${qs ? '?' + qs : ''}`);
  }

  async deleteCrashReport(id: string): Promise<any> {
    return this.request('DELETE', `/api/admin/crashes/${id}`);
  }

  // ==================== Admin Database Browser ====================

  async getAdminTables(): Promise<any> {
    return this.request('GET', '/api/admin/tables');
  }

  async getAdminTableSchema(tableName: string): Promise<any> {
    return this.request('GET', `/api/admin/tables/${encodeURIComponent(tableName)}/schema`);
  }

  async getAdminTableData(tableName: string, params?: {
    limit?: number;
    offset?: number;
    sort?: string;
    order?: 'ASC' | 'DESC';
    search?: string;
  }): Promise<any> {
    const q = new URLSearchParams();
    if (params?.limit) q.set('limit', String(params.limit));
    if (params?.offset) q.set('offset', String(params.offset));
    if (params?.sort) q.set('sort', params.sort);
    if (params?.order) q.set('order', params.order);
    if (params?.search) q.set('search', params.search);
    const qs = q.toString();
    return this.request('GET', `/api/admin/tables/${encodeURIComponent(tableName)}${qs ? '?' + qs : ''}`);
  }

  async updateAdminTableRow(tableName: string, rowId: string, data: Record<string, any>): Promise<any> {
    return this.request('PUT', `/api/admin/tables/${encodeURIComponent(tableName)}/${encodeURIComponent(rowId)}`, data);
  }

  async deleteAdminTableRow(tableName: string, rowId: string): Promise<any> {
    return this.request('DELETE', `/api/admin/tables/${encodeURIComponent(tableName)}/${encodeURIComponent(rowId)}`);
  }

  async runAdminQuery(sql: string): Promise<any> {
    return this.request('POST', '/api/admin/query', { sql });
  }

  async getAdminDbHealth(): Promise<any> {
    return this.request('GET', '/api/admin/db/health');
  }

  async getAdminSyncGroups(): Promise<any> {
    return this.request('GET', '/api/admin/sync-groups');
  }

  // ==================== Admin: New Features ====================

  async adminUserLookup(q: string): Promise<any> {
    return this.request('GET', `/api/admin/user-lookup?q=${encodeURIComponent(q)}`);
  }

  async getAdminSessions(): Promise<any> {
    return this.request('GET', '/api/admin/sessions');
  }

  async getAdminE2EEHealth(): Promise<any> {
    return this.request('GET', '/api/admin/e2ee/health');
  }

  async cleanupE2eeKeys(): Promise<any> {
    return this.request('POST', '/api/admin/cleanup/e2ee');
  }

  async getAdminLogs(params?: { limit?: number; level?: string; search?: string }): Promise<any> {
    const q = new URLSearchParams();
    if (params?.limit) q.set('limit', String(params.limit));
    if (params?.level) q.set('level', params.level);
    if (params?.search) q.set('search', params.search);
    const qs = q.toString();
    return this.request('GET', `/api/admin/logs${qs ? '?' + qs : ''}`);
  }

  async clearAdminLogs(): Promise<any> {
    return this.request('DELETE', '/api/admin/logs');
  }

  async getAdminAlerts(): Promise<any> {
    return this.request('GET', '/api/admin/system/alerts');
  }

  async getAdminMaintenance(): Promise<any> {
    return this.request('GET', '/api/admin/maintenance');
  }

  async setAdminMaintenance(enabled: boolean, message?: string): Promise<any> {
    return this.request('POST', '/api/admin/maintenance', { enabled, message });
  }

  // ==================== Admin: Analytics ====================

  async getAdminAnalyticsCosts(): Promise<any> {
    return this.request('GET', '/api/admin/analytics/costs');
  }

  async getAdminAnalyticsBandwidth(): Promise<any> {
    return this.request('GET', '/api/admin/analytics/bandwidth');
  }

  async getAdminAnalyticsDashboard(): Promise<any> {
    return this.request('GET', '/api/admin/analytics/dashboard');
  }

  async getAdminAnalyticsRetention(): Promise<any> {
    return this.request('GET', '/api/admin/analytics/retention');
  }

  async getAdminAnalyticsFeatures(): Promise<any> {
    return this.request('GET', '/api/admin/analytics/features');
  }

  // ==================== Admin: Site Analytics ====================

  async getSiteAnalyticsOverview(days: number = 30): Promise<any> {
    return this.request('GET', `/api/analytics/admin/overview?days=${days}`);
  }

  async getSiteAnalyticsVisits(days: number = 30): Promise<any> {
    return this.request('GET', `/api/analytics/admin/visits?days=${days}`);
  }

  async getSiteAnalyticsDownloads(days: number = 30): Promise<any> {
    return this.request('GET', `/api/analytics/admin/downloads?days=${days}`);
  }

  // ==================== Admin: Pending Deletions ====================

  async getPendingDeletions(): Promise<any> {
    return this.request('GET', '/api/admin/pending-deletions');
  }

  async processUserDeletion(userId: string): Promise<any> {
    return this.request('POST', `/api/admin/process-deletion/${userId}`);
  }

  async cancelUserDeletion(userId: string): Promise<any> {
    return this.request('POST', `/api/admin/cancel-deletion/${userId}`);
  }

  // ==================== Spam ====================

  async getSpamMessages(limit = 100): Promise<{ messages: any[] }> {
    return this.request('GET', `/api/spam/messages?limit=${limit}`);
  }

  async deleteSpamMessage(messageId: string): Promise<void> {
    await this.request('DELETE', `/api/spam/messages/${messageId}`);
  }

  async clearAllSpamMessages(): Promise<void> {
    await this.request('DELETE', '/api/spam/messages');
  }

  async addToWhitelist(phoneNumber: string): Promise<void> {
    await this.request('POST', '/api/spam/whitelist', { phoneNumber });
  }

  async removeFromWhitelist(phoneNumber: string): Promise<void> {
    await this.request('DELETE', '/api/spam/whitelist', { phoneNumber });
  }

  // ==================== Usage ====================

  async getUsage(): Promise<any> {
    return this.request('GET', '/api/usage');
  }

  async syncSubscription(): Promise<any> {
    return this.request('POST', '/api/usage/subscription/sync');
  }

  // Photos
  async getPhotos(limit = 50): Promise<any> {
    return this.request('GET', `/api/photos?limit=${limit}`);
  }

  async getPhotoDownloadUrl(r2Key: string): Promise<string> {
    const res: any = await this.request('POST', '/api/photos/download-url', { r2Key });
    return res.downloadUrl;
  }

  async deletePhoto(photoId: string): Promise<void> {
    await this.request('POST', '/api/photos/delete', { photoId });
  }
}

// Export true singleton — survives Next.js chunk re-evaluation
const getVPSService = (): VPSService => {
  if (typeof window !== 'undefined') {
    if (!(window as any).__vpsService) {
      (window as any).__vpsService = new VPSService();
    }
    return (window as any).__vpsService;
  }
  return new VPSService();
};

export const vpsService = getVPSService();
export default vpsService;
