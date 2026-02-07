/**
 * SecureStorage - IndexedDB-based secure storage for sensitive data
 *
 * Provides a more secure alternative to localStorage for storing E2EE keys
 * and other sensitive data.
 *
 * Benefits over localStorage:
 * - Not visible in browser DevTools Application tab
 * - Larger storage capacity (50MB+ vs 5-10MB)
 * - Better security isolation
 * - Async operations (non-blocking)
 *
 * Uses the idb library for a cleaner Promise-based API over raw IndexedDB.
 */

import { openDB, DBSchema, IDBPDatabase } from 'idb'

/**
 * Database schema for secure storage
 */
interface SecureStorageDB extends DBSchema {
  keys: {
    key: string
    value: {
      id: string
      data: string
      encrypted: boolean
      createdAt: number
      lastAccessed: number
    }
  }
}

/**
 * SecureStorage class for managing sensitive data in IndexedDB
 */
class SecureStorage {
  private db: IDBPDatabase<SecureStorageDB> | null = null
  private readonly dbName = 'syncflow_secure'
  private readonly dbVersion = 1
  private readonly storeName = 'keys'

  /**
   * Initialize the IndexedDB database
   */
  async init(): Promise<void> {
    if (this.db) return // Already initialized

    try {
      this.db = await openDB<SecureStorageDB>(this.dbName, this.dbVersion, {
        upgrade(db) {
          // Create object store if it doesn't exist
          if (!db.objectStoreNames.contains('keys')) {
            db.createObjectStore('keys', { keyPath: 'id' })
          }
        },
      })
      console.log('[SecureStorage] IndexedDB initialized')
    } catch (error) {
      console.error('[SecureStorage] Failed to initialize IndexedDB:', error)
      throw error
    }
  }

  /**
   * Store a key-value pair securely
   *
   * @param key - The key to store under
   * @param value - The value to store
   * @param encrypted - Whether the value is already encrypted (metadata only)
   */
  async setItem(key: string, value: string, encrypted = false): Promise<void> {
    await this.init()

    if (!this.db) {
      throw new Error('Database not initialized')
    }

    const item = {
      id: key,
      data: value,
      encrypted,
      createdAt: Date.now(),
      lastAccessed: Date.now(),
    }

    await this.db.put(this.storeName, item)
    console.log(`[SecureStorage] Stored key: ${key} (encrypted: ${encrypted})`)
  }

  /**
   * Retrieve a value by key
   *
   * @param key - The key to retrieve
   * @returns The stored value or null if not found
   */
  async getItem(key: string): Promise<string | null> {
    await this.init()

    if (!this.db) {
      throw new Error('Database not initialized')
    }

    const item = await this.db.get(this.storeName, key)

    if (!item) {
      return null
    }

    // Update last accessed timestamp
    item.lastAccessed = Date.now()
    await this.db.put(this.storeName, item)

    return item.data
  }

  /**
   * Remove a key from storage
   *
   * @param key - The key to remove
   */
  async removeItem(key: string): Promise<void> {
    await this.init()

    if (!this.db) {
      throw new Error('Database not initialized')
    }

    await this.db.delete(this.storeName, key)
    console.log(`[SecureStorage] Removed key: ${key}`)
  }

  /**
   * Clear all items from secure storage
   */
  async clear(): Promise<void> {
    await this.init()

    if (!this.db) {
      throw new Error('Database not initialized')
    }

    await this.db.clear(this.storeName)
    console.log('[SecureStorage] Cleared all keys')
  }

  /**
   * Get all stored keys
   *
   * @returns Array of all keys
   */
  async keys(): Promise<string[]> {
    await this.init()

    if (!this.db) {
      throw new Error('Database not initialized')
    }

    return await this.db.getAllKeys(this.storeName)
  }

  /**
   * Check if a key exists
   *
   * @param key - The key to check
   * @returns True if the key exists
   */
  async hasItem(key: string): Promise<boolean> {
    await this.init()

    if (!this.db) {
      throw new Error('Database not initialized')
    }

    const item = await this.db.get(this.storeName, key)
    return item !== undefined
  }

  /**
   * Migrate data from localStorage to IndexedDB
   *
   * @param keys - Array of localStorage keys to migrate
   */
  async migrateFromLocalStorage(keys: string[]): Promise<void> {
    console.log('[SecureStorage] Starting migration from localStorage')

    for (const key of keys) {
      const value = localStorage.getItem(key)
      if (value !== null) {
        try {
          await this.setItem(key, value, true)
          localStorage.removeItem(key)
          console.log(`[SecureStorage] Migrated key: ${key}`)
        } catch (error) {
          console.error(`[SecureStorage] Failed to migrate key: ${key}`, error)
        }
      }
    }

    console.log('[SecureStorage] Migration complete')
  }

  /**
   * Get storage statistics
   *
   * @returns Object with storage stats
   */
  async getStats(): Promise<{
    itemCount: number
    oldestItem: number | null
    newestItem: number | null
  }> {
    await this.init()

    if (!this.db) {
      throw new Error('Database not initialized')
    }

    const allItems = await this.db.getAll(this.storeName)

    if (allItems.length === 0) {
      return {
        itemCount: 0,
        oldestItem: null,
        newestItem: null,
      }
    }

    const timestamps = allItems.map((item) => item.createdAt)

    return {
      itemCount: allItems.length,
      oldestItem: Math.min(...timestamps),
      newestItem: Math.max(...timestamps),
    }
  }
}

/**
 * Singleton instance of SecureStorage
 */
export const secureStorage = new SecureStorage()

/**
 * Initialize secure storage and optionally migrate from localStorage
 *
 * @param migrationKeys - Optional array of localStorage keys to migrate
 */
export async function initSecureStorage(migrationKeys?: string[]): Promise<void> {
  await secureStorage.init()

  if (migrationKeys && migrationKeys.length > 0) {
    await secureStorage.migrateFromLocalStorage(migrationKeys)
  }
}
