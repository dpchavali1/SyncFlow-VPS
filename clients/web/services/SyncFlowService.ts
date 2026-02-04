/**
 * SyncFlow Service - Web Application Integration
 *
 * High-level service that wraps SyncFlowClient with:
 * - Token persistence (localStorage)
 * - Auto-reconnection
 * - Local caching (IndexedDB)
 * - React-friendly state management
 */

import SyncFlowClient, {
  Message,
  Contact,
  CallHistoryEntry,
  Device,
  User,
  TokenPair,
} from '../SyncFlowClient';

const API_URL = 'http://5.78.188.206';
const STORAGE_KEY = 'syncflow_auth';

// ==================== IndexedDB Cache ====================

class SyncFlowCache {
  private dbName = 'syncflow_cache';
  private db: IDBDatabase | null = null;

  async init(): Promise<void> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(this.dbName, 1);

      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        this.db = request.result;
        resolve();
      };

      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;

        // Messages store
        if (!db.objectStoreNames.contains('messages')) {
          const msgStore = db.createObjectStore('messages', { keyPath: 'id' });
          msgStore.createIndex('date', 'date', { unique: false });
          msgStore.createIndex('address', 'address', { unique: false });
        }

        // Contacts store
        if (!db.objectStoreNames.contains('contacts')) {
          const contactStore = db.createObjectStore('contacts', { keyPath: 'id' });
          contactStore.createIndex('displayName', 'displayName', { unique: false });
        }

        // Call history store
        if (!db.objectStoreNames.contains('calls')) {
          const callStore = db.createObjectStore('calls', { keyPath: 'id' });
          callStore.createIndex('callDate', 'callDate', { unique: false });
        }

        // Metadata store
        if (!db.objectStoreNames.contains('metadata')) {
          db.createObjectStore('metadata', { keyPath: 'key' });
        }
      };
    });
  }

  async saveMessages(messages: Message[]): Promise<void> {
    if (!this.db) return;
    const tx = this.db.transaction('messages', 'readwrite');
    const store = tx.objectStore('messages');
    for (const msg of messages) {
      store.put(msg);
    }
  }

  async getMessages(limit: number = 100): Promise<Message[]> {
    if (!this.db) return [];
    return new Promise((resolve) => {
      const tx = this.db!.transaction('messages', 'readonly');
      const store = tx.objectStore('messages');
      const index = store.index('date');
      const request = index.openCursor(null, 'prev');
      const results: Message[] = [];

      request.onsuccess = () => {
        const cursor = request.result;
        if (cursor && results.length < limit) {
          results.push(cursor.value);
          cursor.continue();
        } else {
          resolve(results);
        }
      };
    });
  }

  async saveContacts(contacts: Contact[]): Promise<void> {
    if (!this.db) return;
    const tx = this.db.transaction('contacts', 'readwrite');
    const store = tx.objectStore('contacts');
    for (const contact of contacts) {
      store.put(contact);
    }
  }

  async getContacts(): Promise<Contact[]> {
    if (!this.db) return [];
    return new Promise((resolve) => {
      const tx = this.db!.transaction('contacts', 'readonly');
      const store = tx.objectStore('contacts');
      const request = store.getAll();
      request.onsuccess = () => resolve(request.result || []);
    });
  }

  async saveCalls(calls: CallHistoryEntry[]): Promise<void> {
    if (!this.db) return;
    const tx = this.db.transaction('calls', 'readwrite');
    const store = tx.objectStore('calls');
    for (const call of calls) {
      store.put(call);
    }
  }

  async getCalls(limit: number = 100): Promise<CallHistoryEntry[]> {
    if (!this.db) return [];
    return new Promise((resolve) => {
      const tx = this.db!.transaction('calls', 'readonly');
      const store = tx.objectStore('calls');
      const index = store.index('callDate');
      const request = index.openCursor(null, 'prev');
      const results: CallHistoryEntry[] = [];

      request.onsuccess = () => {
        const cursor = request.result;
        if (cursor && results.length < limit) {
          results.push(cursor.value);
          cursor.continue();
        } else {
          resolve(results);
        }
      };
    });
  }

  async setMetadata(key: string, value: any): Promise<void> {
    if (!this.db) return;
    const tx = this.db.transaction('metadata', 'readwrite');
    const store = tx.objectStore('metadata');
    store.put({ key, value });
  }

  async getMetadata(key: string): Promise<any> {
    if (!this.db) return null;
    return new Promise((resolve) => {
      const tx = this.db!.transaction('metadata', 'readonly');
      const store = tx.objectStore('metadata');
      const request = store.get(key);
      request.onsuccess = () => resolve(request.result?.value);
    });
  }

  async clear(): Promise<void> {
    if (!this.db) return;
    const stores = ['messages', 'contacts', 'calls', 'metadata'];
    for (const storeName of stores) {
      const tx = this.db.transaction(storeName, 'readwrite');
      tx.objectStore(storeName).clear();
    }
  }
}

// ==================== Event Emitter ====================

type EventCallback = (...args: any[]) => void;

class EventEmitter {
  private events: Map<string, Set<EventCallback>> = new Map();

  on(event: string, callback: EventCallback): () => void {
    if (!this.events.has(event)) {
      this.events.set(event, new Set());
    }
    this.events.get(event)!.add(callback);
    return () => this.off(event, callback);
  }

  off(event: string, callback: EventCallback): void {
    this.events.get(event)?.delete(callback);
  }

  emit(event: string, ...args: any[]): void {
    this.events.get(event)?.forEach((cb) => cb(...args));
  }
}

// ==================== Main Service ====================

export interface SyncFlowState {
  isAuthenticated: boolean;
  isConnected: boolean;
  isPaired: boolean;
  userId: string | null;
  deviceId: string | null;
  messages: Message[];
  contacts: Contact[];
  calls: CallHistoryEntry[];
  devices: Device[];
}

export class SyncFlowService extends EventEmitter {
  private client: SyncFlowClient;
  private cache: SyncFlowCache;
  private state: SyncFlowState = {
    isAuthenticated: false,
    isConnected: false,
    isPaired: false,
    userId: null,
    deviceId: null,
    messages: [],
    contacts: [],
    calls: [],
    devices: [],
  };

  constructor(apiUrl: string = API_URL) {
    super();
    this.client = new SyncFlowClient(apiUrl);
    this.cache = new SyncFlowCache();
    this.setupWebSocketHandlers();
  }

  // ==================== Initialization ====================

  async init(): Promise<boolean> {
    await this.cache.init();

    // Try to restore session
    const savedAuth = localStorage.getItem(STORAGE_KEY);
    if (savedAuth) {
      try {
        const { accessToken, refreshToken, userId, deviceId } = JSON.parse(savedAuth);
        this.client.setTokens(accessToken, refreshToken);
        this.state.userId = userId;
        this.state.deviceId = deviceId;

        // Verify token is still valid
        await this.client.getCurrentUser();
        this.state.isAuthenticated = true;
        this.state.isPaired = true;

        // Load cached data
        this.state.messages = await this.cache.getMessages();
        this.state.contacts = await this.cache.getContacts();
        this.state.calls = await this.cache.getCalls();

        // Connect WebSocket
        this.connectWebSocket();

        this.emitStateChange();
        return true;
      } catch {
        // Token invalid, clear
        localStorage.removeItem(STORAGE_KEY);
      }
    }

    return false;
  }

  // ==================== Authentication ====================

  async authenticateAnonymous(): Promise<User> {
    const user = await this.client.authenticateAnonymous();
    this.saveSession(user);
    this.state.isAuthenticated = true;
    this.emitStateChange();
    return user;
  }

  async initiatePairing(deviceName: string): Promise<string> {
    const result = await this.client.initiatePairing(deviceName, 'web');
    this.state.deviceId = result.deviceId;
    return result.pairingToken;
  }

  async pollPairingStatus(token: string): Promise<boolean> {
    const status = await this.client.checkPairingStatus(token);
    if (status.approved) {
      const user = await this.client.redeemPairing(token);
      this.saveSession(user);
      this.state.isAuthenticated = true;
      this.state.isPaired = true;
      this.connectWebSocket();
      this.emitStateChange();
      return true;
    }
    return false;
  }

  async logout(): Promise<void> {
    this.client.disconnectWebSocket();
    localStorage.removeItem(STORAGE_KEY);
    await this.cache.clear();
    this.state = {
      isAuthenticated: false,
      isConnected: false,
      isPaired: false,
      userId: null,
      deviceId: null,
      messages: [],
      contacts: [],
      calls: [],
      devices: [],
    };
    this.emitStateChange();
  }

  private saveSession(user: User): void {
    const tokens = this.client.getTokens();
    if (tokens) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        ...tokens,
        userId: user.userId,
        deviceId: user.deviceId,
      }));
    }
    this.state.userId = user.userId;
    this.state.deviceId = user.deviceId;
  }

  // ==================== Messages ====================

  async loadMessages(options: { limit?: number; before?: number } = {}): Promise<Message[]> {
    const { messages, hasMore } = await this.client.getMessages(options);

    // Merge with existing (avoid duplicates)
    const existingIds = new Set(this.state.messages.map((m) => m.id));
    const newMessages = messages.filter((m) => !existingIds.has(m.id));

    this.state.messages = [...this.state.messages, ...newMessages]
      .sort((a, b) => b.date - a.date);

    await this.cache.saveMessages(messages);
    this.emitStateChange();

    return messages;
  }

  async sendMessage(address: string, body: string): Promise<void> {
    await this.client.sendMessage(address, body);
  }

  async markAsRead(messageId: string): Promise<void> {
    await this.client.markMessageRead(messageId);
    const msg = this.state.messages.find((m) => m.id === messageId);
    if (msg) {
      msg.read = true;
      this.emitStateChange();
    }
  }

  // ==================== Contacts ====================

  async loadContacts(): Promise<Contact[]> {
    const { contacts } = await this.client.getContacts();
    this.state.contacts = contacts;
    await this.cache.saveContacts(contacts);
    this.emitStateChange();
    return contacts;
  }

  async searchContacts(query: string): Promise<Contact[]> {
    const { contacts } = await this.client.getContacts({ search: query });
    return contacts;
  }

  // ==================== Call History ====================

  async loadCallHistory(options: { limit?: number; before?: number } = {}): Promise<CallHistoryEntry[]> {
    const { calls } = await this.client.getCallHistory(options);

    const existingIds = new Set(this.state.calls.map((c) => c.id));
    const newCalls = calls.filter((c) => !existingIds.has(c.id));

    this.state.calls = [...this.state.calls, ...newCalls]
      .sort((a, b) => b.callDate - a.callDate);

    await this.cache.saveCalls(calls);
    this.emitStateChange();

    return calls;
  }

  async makeCall(phoneNumber: string): Promise<void> {
    await this.client.requestCall(phoneNumber);
  }

  // ==================== Devices ====================

  async loadDevices(): Promise<Device[]> {
    const { devices } = await this.client.getDevices();
    this.state.devices = devices;
    this.emitStateChange();
    return devices;
  }

  async removeDevice(deviceId: string): Promise<void> {
    await this.client.removeDevice(deviceId);
    this.state.devices = this.state.devices.filter((d) => d.id !== deviceId);
    this.emitStateChange();
  }

  // ==================== WebSocket ====================

  private connectWebSocket(): void {
    this.client.connectWebSocket();
    this.client.subscribe('messages');
    this.client.subscribe('contacts');
    this.client.subscribe('calls');
    this.client.subscribe('devices');
  }

  private setupWebSocketHandlers(): void {
    this.client.on('connected', () => {
      this.state.isConnected = true;
      this.emitStateChange();
      this.emit('connected');
    });

    this.client.on('disconnected', () => {
      this.state.isConnected = false;
      this.emitStateChange();
      this.emit('disconnected');
    });

    this.client.on('message_added', (data) => {
      const message = data as Message;
      if (!this.state.messages.find((m) => m.id === message.id)) {
        this.state.messages = [message, ...this.state.messages];
        this.cache.saveMessages([message]);
        this.emitStateChange();
        this.emit('message', message);
      }
    });

    this.client.on('message_updated', (data) => {
      const message = data as Message;
      const index = this.state.messages.findIndex((m) => m.id === message.id);
      if (index >= 0) {
        this.state.messages[index] = message;
        this.emitStateChange();
      }
    });

    this.client.on('contact_added', (data) => {
      const contact = data as Contact;
      if (!this.state.contacts.find((c) => c.id === contact.id)) {
        this.state.contacts = [...this.state.contacts, contact];
        this.emitStateChange();
      }
    });

    this.client.on('call_added', (data) => {
      const call = data as CallHistoryEntry;
      if (!this.state.calls.find((c) => c.id === call.id)) {
        this.state.calls = [call, ...this.state.calls];
        this.emitStateChange();
      }
    });
  }

  // ==================== State ====================

  getState(): SyncFlowState {
    return { ...this.state };
  }

  private emitStateChange(): void {
    this.emit('stateChange', this.getState());
  }
}

// Singleton instance
let serviceInstance: SyncFlowService | null = null;

export function getSyncFlowService(): SyncFlowService {
  if (!serviceInstance) {
    serviceInstance = new SyncFlowService();
  }
  return serviceInstance;
}

export default SyncFlowService;
