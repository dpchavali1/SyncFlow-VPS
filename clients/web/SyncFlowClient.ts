/**
 * SyncFlow VPS Client - Web/TypeScript
 *
 * Usage:
 *   const client = new SyncFlowClient('https://api.sfweb.app');
 *   await client.authenticate();
 *   const messages = await client.getMessages();
 */

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
}

export interface User {
  userId: string;
  deviceId: string;
  admin?: boolean;
}

export interface Message {
  id: string;
  threadId?: number;
  address: string;
  contactName?: string;
  body?: string;
  date: number;
  type: number;
  read: boolean;
  isMms: boolean;
  mmsParts?: any;
  encrypted?: boolean;
}

export interface Contact {
  id: string;
  displayName?: string;
  phoneNumbers: string[];
  emails: string[];
  photoThumbnail?: string;
}

export interface CallHistoryEntry {
  id: string;
  phoneNumber: string;
  contactName?: string;
  callType: 'incoming' | 'outgoing' | 'missed' | 'rejected' | 'blocked' | 'voicemail';
  callDate: number;
  duration: number;
  simSubscriptionId?: number;
}

export interface Device {
  id: string;
  name?: string;
  deviceType: 'android' | 'macos' | 'web';
  pairedAt: string;
  lastSeen: string;
  isCurrent: boolean;
}

export interface PairingRequest {
  pairingToken: string;
  deviceId: string;
  tempUserId: string;
  accessToken: string;
  refreshToken: string;
}

type WebSocketEventType =
  | 'connected' | 'disconnected' | 'error'
  | 'message_added' | 'message_updated' | 'message_deleted'
  | 'contact_added' | 'contact_updated' | 'contact_deleted'
  | 'call_added' | 'device_added' | 'device_removed'
  | 'outgoing_message' | 'call_request';

type WebSocketCallback = (data: any) => void;

export class SyncFlowClient {
  private baseUrl: string;
  private wsUrl: string;
  private accessToken: string | null = null;
  private refreshToken: string | null = null;
  private userId: string | null = null;
  private deviceId: string | null = null;
  private ws: WebSocket | null = null;
  private wsReconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private eventListeners: Map<WebSocketEventType, Set<WebSocketCallback>> = new Map();
  private subscriptions: Set<string> = new Set();

  constructor(baseUrl: string = 'http://5.78.188.206') {
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.wsUrl = baseUrl.replace(/^http/, 'ws').replace(/\/$/, '') + ':3001';
  }

  // ==================== Authentication ====================

  async authenticateAnonymous(): Promise<User> {
    const response = await this.request<{
      userId: string;
      deviceId: string;
      accessToken: string;
      refreshToken: string;
    }>('/api/auth/anonymous', { method: 'POST' });

    this.setTokens(response.accessToken, response.refreshToken);
    this.userId = response.userId;
    this.deviceId = response.deviceId;

    return { userId: response.userId, deviceId: response.deviceId };
  }

  async initiatePairing(deviceName: string, deviceType: 'macos' | 'web'): Promise<PairingRequest> {
    const response = await this.request<PairingRequest>('/api/auth/pair/initiate', {
      method: 'POST',
      body: { deviceName, deviceType },
    });

    this.setTokens(response.accessToken, response.refreshToken);
    this.deviceId = response.deviceId;

    return response;
  }

  async checkPairingStatus(token: string): Promise<{ status: string; approved: boolean }> {
    return this.request(`/api/auth/pair/status/${token}`);
  }

  async redeemPairing(token: string, deviceName?: string, deviceType?: string): Promise<User> {
    const response = await this.request<{
      userId: string;
      deviceId: string;
      accessToken: string;
      refreshToken: string;
    }>('/api/auth/pair/redeem', {
      method: 'POST',
      body: { token, deviceName, deviceType },
    });

    this.setTokens(response.accessToken, response.refreshToken);
    this.userId = response.userId;
    this.deviceId = response.deviceId;

    return { userId: response.userId, deviceId: response.deviceId };
  }

  async completePairing(token: string): Promise<void> {
    await this.request('/api/auth/pair/complete', {
      method: 'POST',
      body: { token },
    });
  }

  async refreshAccessToken(): Promise<void> {
    if (!this.refreshToken) {
      throw new Error('No refresh token available');
    }

    const response = await fetch(`${this.baseUrl}/api/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: this.refreshToken }),
    });

    if (!response.ok) {
      throw new Error('Failed to refresh token');
    }

    const data = await response.json();
    this.accessToken = data.accessToken;
  }

  async getCurrentUser(): Promise<User> {
    return this.request('/api/auth/me');
  }

  setTokens(accessToken: string, refreshToken: string): void {
    this.accessToken = accessToken;
    this.refreshToken = refreshToken;
  }

  getTokens(): TokenPair | null {
    if (!this.accessToken || !this.refreshToken) return null;
    return { accessToken: this.accessToken, refreshToken: this.refreshToken };
  }

  // ==================== Messages ====================

  async getMessages(options: {
    limit?: number;
    before?: number;
    after?: number;
    threadId?: number;
  } = {}): Promise<{ messages: Message[]; hasMore: boolean }> {
    const params = new URLSearchParams();
    if (options.limit) params.set('limit', options.limit.toString());
    if (options.before) params.set('before', options.before.toString());
    if (options.after) params.set('after', options.after.toString());
    if (options.threadId) params.set('threadId', options.threadId.toString());

    return this.request(`/api/messages?${params}`);
  }

  async syncMessages(messages: Partial<Message>[]): Promise<{ synced: number; skipped: number }> {
    return this.request('/api/messages/sync', {
      method: 'POST',
      body: { messages },
    });
  }

  async sendMessage(address: string, body: string, simSubscriptionId?: number): Promise<{ id: string }> {
    return this.request('/api/messages/send', {
      method: 'POST',
      body: { address, body, simSubscriptionId },
    });
  }

  async getOutgoingMessages(): Promise<{ messages: any[] }> {
    return this.request('/api/messages/outgoing');
  }

  async updateOutgoingStatus(id: string, status: 'sent' | 'failed', error?: string): Promise<void> {
    await this.request(`/api/messages/outgoing/${id}/status`, {
      method: 'PUT',
      body: { status, error },
    });
  }

  async markMessageRead(id: string): Promise<void> {
    await this.request(`/api/messages/${id}/read`, { method: 'PUT' });
  }

  async getMessageCount(): Promise<number> {
    const result = await this.request<{ count: number }>('/api/messages/count');
    return result.count;
  }

  // ==================== Contacts ====================

  async getContacts(options: { search?: string; limit?: number } = {}): Promise<{ contacts: Contact[] }> {
    const params = new URLSearchParams();
    if (options.search) params.set('search', options.search);
    if (options.limit) params.set('limit', options.limit.toString());

    return this.request(`/api/contacts?${params}`);
  }

  async syncContacts(contacts: Partial<Contact>[]): Promise<{ synced: number; skipped: number }> {
    return this.request('/api/contacts/sync', {
      method: 'POST',
      body: { contacts },
    });
  }

  async getContact(id: string): Promise<Contact> {
    return this.request(`/api/contacts/${id}`);
  }

  async getContactCount(): Promise<number> {
    const result = await this.request<{ count: number }>('/api/contacts/meta/count');
    return result.count;
  }

  // ==================== Call History ====================

  async getCallHistory(options: {
    limit?: number;
    before?: number;
    after?: number;
    type?: string;
  } = {}): Promise<{ calls: CallHistoryEntry[]; hasMore: boolean }> {
    const params = new URLSearchParams();
    if (options.limit) params.set('limit', options.limit.toString());
    if (options.before) params.set('before', options.before.toString());
    if (options.after) params.set('after', options.after.toString());
    if (options.type) params.set('type', options.type);

    return this.request(`/api/calls?${params}`);
  }

  async syncCallHistory(calls: Partial<CallHistoryEntry>[]): Promise<{ synced: number; skipped: number }> {
    return this.request('/api/calls/sync', {
      method: 'POST',
      body: { calls },
    });
  }

  async requestCall(phoneNumber: string, simSubscriptionId?: number): Promise<{ id: string }> {
    return this.request('/api/calls/request', {
      method: 'POST',
      body: { phoneNumber, simSubscriptionId },
    });
  }

  async getCallRequests(): Promise<{ requests: any[] }> {
    return this.request('/api/calls/requests');
  }

  async updateCallRequestStatus(id: string, status: 'dialing' | 'completed' | 'failed'): Promise<void> {
    await this.request(`/api/calls/requests/${id}/status`, {
      method: 'PUT',
      body: { status },
    });
  }

  // ==================== Devices ====================

  async getDevices(): Promise<{ devices: Device[] }> {
    return this.request('/api/devices');
  }

  async updateDevice(id: string, data: { name?: string; fcmToken?: string }): Promise<void> {
    await this.request(`/api/devices/${id}`, {
      method: 'PUT',
      body: data,
    });
  }

  async removeDevice(id: string): Promise<void> {
    await this.request(`/api/devices/${id}`, { method: 'DELETE' });
  }

  async getSims(): Promise<{ sims: any[] }> {
    return this.request('/api/devices/sims');
  }

  async registerSim(sim: any): Promise<void> {
    await this.request('/api/devices/sims', {
      method: 'POST',
      body: sim,
    });
  }

  // ==================== WebSocket ====================

  connectWebSocket(): void {
    if (this.ws?.readyState === WebSocket.OPEN) return;
    if (!this.accessToken) {
      throw new Error('Must authenticate before connecting WebSocket');
    }

    this.ws = new WebSocket(`${this.wsUrl}?token=${this.accessToken}`);

    this.ws.onopen = () => {
      console.log('WebSocket connected');
      this.emit('connected', {});
      // Resubscribe to channels
      this.subscriptions.forEach((channel) => {
        this.ws?.send(JSON.stringify({ type: 'subscribe', channel }));
      });
    };

    this.ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        this.emit(data.type, data.data || data);
      } catch (e) {
        console.error('WebSocket message parse error:', e);
      }
    };

    this.ws.onclose = () => {
      console.log('WebSocket disconnected');
      this.emit('disconnected', {});
      // Auto-reconnect after 3 seconds
      this.wsReconnectTimer = setTimeout(() => this.connectWebSocket(), 3000);
    };

    this.ws.onerror = (error) => {
      console.error('WebSocket error:', error);
      this.emit('error', { error });
    };
  }

  disconnectWebSocket(): void {
    if (this.wsReconnectTimer) {
      clearTimeout(this.wsReconnectTimer);
      this.wsReconnectTimer = null;
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }

  subscribe(channel: 'messages' | 'contacts' | 'calls' | 'devices' | 'outgoing'): void {
    this.subscriptions.add(channel);
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type: 'subscribe', channel }));
    }
  }

  unsubscribe(channel: string): void {
    this.subscriptions.delete(channel);
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type: 'unsubscribe', channel }));
    }
  }

  on(event: WebSocketEventType, callback: WebSocketCallback): void {
    if (!this.eventListeners.has(event)) {
      this.eventListeners.set(event, new Set());
    }
    this.eventListeners.get(event)!.add(callback);
  }

  off(event: WebSocketEventType, callback: WebSocketCallback): void {
    this.eventListeners.get(event)?.delete(callback);
  }

  private emit(event: WebSocketEventType, data: any): void {
    this.eventListeners.get(event)?.forEach((cb) => cb(data));
  }

  // ==================== HTTP Request Helper ====================

  private async request<T>(path: string, options: {
    method?: string;
    body?: any;
  } = {}): Promise<T> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };

    if (this.accessToken) {
      headers['Authorization'] = `Bearer ${this.accessToken}`;
    }

    const response = await fetch(`${this.baseUrl}${path}`, {
      method: options.method || 'GET',
      headers,
      body: options.body ? JSON.stringify(options.body) : undefined,
    });

    if (response.status === 401 && this.refreshToken) {
      // Try to refresh token
      await this.refreshAccessToken();
      // Retry request
      headers['Authorization'] = `Bearer ${this.accessToken}`;
      const retryResponse = await fetch(`${this.baseUrl}${path}`, {
        method: options.method || 'GET',
        headers,
        body: options.body ? JSON.stringify(options.body) : undefined,
      });

      if (!retryResponse.ok) {
        throw new Error(`Request failed: ${retryResponse.status}`);
      }

      return retryResponse.json();
    }

    if (!response.ok) {
      const error = await response.json().catch(() => ({ error: 'Request failed' }));
      throw new Error(error.error || `Request failed: ${response.status}`);
    }

    return response.json();
  }
}

export default SyncFlowClient;
