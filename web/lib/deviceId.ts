/**
 * Persistent Device Identification for Web
 *
 * Generates a stable device ID that survives:
 * - Browser restarts
 * - localStorage clears (uses IndexedDB as primary storage)
 * - Cookie clears
 *
 * Strategy:
 * 1. Check IndexedDB for existing device ID (most persistent)
 * 2. Fall back to localStorage (less persistent but widely supported)
 * 3. If no stored ID, generate a random UUID-based ID
 * 4. Store in both IndexedDB and localStorage for redundancy
 */

const DB_NAME = 'syncflow_device';
const DB_VERSION = 1;
const STORE_NAME = 'device_info';
const DEVICE_ID_KEY = 'device_id';
const LOCALSTORAGE_KEY = 'syncflow_device_id';

// Cache the device ID in memory
let cachedDeviceId: string | null = null;

/**
 * Get the persistent device ID for this browser/device.
 * This ID aims to survive browser data clears.
 */
export async function getDeviceId(): Promise<string> {
  // Return cached value if available
  if (cachedDeviceId) {
    return cachedDeviceId;
  }

  try {
    // Try IndexedDB first (most persistent)
    const indexedDBId = await getFromIndexedDB();
    if (indexedDBId) {
      cachedDeviceId = indexedDBId;
      saveToLocalStorage(indexedDBId);
      return indexedDBId;
    }

    const localStorageId = getFromLocalStorage();
    if (localStorageId) {
      cachedDeviceId = localStorageId;
      await saveToIndexedDB(localStorageId);
      return localStorageId;
    }

    const newId = await generateDeviceId();
    cachedDeviceId = newId;
    await saveToIndexedDB(newId);
    saveToLocalStorage(newId);
    return newId;
  } catch (error) {
    console.error('[DeviceId] Error getting device ID:', error);

    // Last resort: generate a random ID (won't persist across clears)
    const fallbackId = `web_${generateRandomId()}`;
    cachedDeviceId = fallbackId;
    saveToLocalStorage(fallbackId);
    return fallbackId;
  }
}

/**
 * Get device info object for pairing requests
 */
export async function getDeviceInfo(): Promise<{
  id: string;
  name: string;
  type: string;
  userAgent: string;
  platform: string;
}> {
  const deviceId = await getDeviceId();

  return {
    id: deviceId,
    name: getBrowserName(),
    type: 'web',
    userAgent: navigator.userAgent,
    platform: navigator.platform,
  };
}

/**
 * Clear the cached device ID (for testing)
 */
export function clearCache(): void {
  cachedDeviceId = null;
}

/**
 * Reset device ID completely (removes from all storage)
 */
export async function resetDeviceId(): Promise<void> {
  cachedDeviceId = null;
  removeFromLocalStorage();
  await removeFromIndexedDB();
}

// ─────────────────────────────────────────────────────────────────────────────
// Private Helper Functions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Generate a new device ID using a random component
 */
async function generateDeviceId(): Promise<string> {
  return `web_${generateRandomId()}`;
}

/**
 * Generate a random ID
 */
function generateRandomId(): string {
  if (crypto.randomUUID) {
    return crypto.randomUUID().replace(/-/g, '').substring(0, 16);
  }

  // Fallback for older browsers
  const array = new Uint8Array(8);
  crypto.getRandomValues(array);
  return Array.from(array, (b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Get browser name for device name
 */
function getBrowserName(): string {
  const ua = navigator.userAgent;

  if (ua.includes('Firefox')) return 'Firefox';
  if (ua.includes('Edg')) return 'Edge';
  if (ua.includes('Chrome')) return 'Chrome';
  if (ua.includes('Safari')) return 'Safari';
  if (ua.includes('Opera') || ua.includes('OPR')) return 'Opera';

  return 'Web Browser';
}

// ─────────────────────────────────────────────────────────────────────────────
// IndexedDB Operations
// ─────────────────────────────────────────────────────────────────────────────

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (!indexedDB) {
      reject(new Error('IndexedDB not supported'));
      return;
    }

    const request = indexedDB.open(DB_NAME, DB_VERSION);

    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);

    request.onupgradeneeded = (event) => {
      const db = (event.target as IDBOpenDBRequest).result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME, { keyPath: 'key' });
      }
    };
  });
}

async function getFromIndexedDB(): Promise<string | null> {
  try {
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(STORE_NAME, 'readonly');
      const store = transaction.objectStore(STORE_NAME);
      const request = store.get(DEVICE_ID_KEY);

      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const result = request.result;
        resolve(result ? result.value : null);
      };

      transaction.oncomplete = () => db.close();
    });
  } catch (error) {
    console.warn('[DeviceId] IndexedDB read failed:', error);
    return null;
  }
}

async function saveToIndexedDB(deviceId: string): Promise<void> {
  try {
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(STORE_NAME, 'readwrite');
      const store = transaction.objectStore(STORE_NAME);
      const request = store.put({ key: DEVICE_ID_KEY, value: deviceId });

      request.onerror = () => reject(request.error);
      request.onsuccess = () => resolve();

      transaction.oncomplete = () => db.close();
    });
  } catch (error) {
    console.warn('[DeviceId] IndexedDB write failed:', error);
  }
}

async function removeFromIndexedDB(): Promise<void> {
  try {
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(STORE_NAME, 'readwrite');
      const store = transaction.objectStore(STORE_NAME);
      const request = store.delete(DEVICE_ID_KEY);

      request.onerror = () => reject(request.error);
      request.onsuccess = () => resolve();

      transaction.oncomplete = () => db.close();
    });
  } catch (error) {
    console.warn('[DeviceId] IndexedDB delete failed:', error);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// LocalStorage Operations (Fallback)
// ─────────────────────────────────────────────────────────────────────────────

function getFromLocalStorage(): string | null {
  try {
    return localStorage.getItem(LOCALSTORAGE_KEY);
  } catch (error) {
    console.warn('[DeviceId] localStorage read failed:', error);
    return null;
  }
}

function saveToLocalStorage(deviceId: string): void {
  try {
    localStorage.setItem(LOCALSTORAGE_KEY, deviceId);
  } catch (error) {
    console.warn('[DeviceId] localStorage write failed:', error);
  }
}

function removeFromLocalStorage(): void {
  try {
    localStorage.removeItem(LOCALSTORAGE_KEY);
  } catch (error) {
    console.warn('[DeviceId] localStorage delete failed:', error);
  }
}
