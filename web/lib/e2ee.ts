'use client'

import { secureStorage, initSecureStorage } from './secureStorage'

const DEVICE_ID_KEY = 'syncflow_device_id'
const E2EE_KEYPAIR_KEY = 'syncflow_e2ee_jwk'
const E2EE_KEYPAIR_ENCRYPTED_KEY = 'syncflow_e2ee_jwk_encrypted'
const E2EE_PUBLIC_KEY_X963_KEY = 'syncflow_e2ee_public_key_x963'
const E2EE_CONTEXT = 'SyncFlow-E2EE-v2'
const E2EE_PBKDF2_ITERATIONS = 150000

type StoredKeyPair = {
  privateKeyJwk: JsonWebKey
  publicKeyJwk: JsonWebKey
}

type EncryptedKeyPayload = {
  v: 1
  salt: string
  iv: string
  ciphertext: string
  publicKeyJwk: JsonWebKey
  publicKeyX963: string
  iterations: number
}

const textEncoder = new TextEncoder()
const textDecoder = new TextDecoder()

let cachedKeyPair: CryptoKeyPair | null = null
let cachedKeyPairJwk: StoredKeyPair | null = null
let cachedEncryptionKey: CryptoKey | null = null
let cachedEncryptionSalt: Uint8Array | null = null

const base64ToBytes = (base64: string) => {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

export const bytesToBase64 = (bytes: Uint8Array) => {
  let binary = ''
  bytes.forEach((b) => (binary += String.fromCharCode(b)))
  return btoa(binary)
}

const bytesToBase64url = (bytes: Uint8Array) => {
  return bytesToBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

const getEncryptedPayload = async (): Promise<EncryptedKeyPayload | null> => {
  if (typeof window === 'undefined') return null
  await initSecureStorage()
  const raw = await secureStorage.getItem(E2EE_KEYPAIR_ENCRYPTED_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as EncryptedKeyPayload
  } catch {
    return null
  }
}

const saveEncryptedPayload = async (payload: EncryptedKeyPayload): Promise<void> => {
  if (typeof window === 'undefined') return
  await initSecureStorage()
  await secureStorage.setItem(E2EE_KEYPAIR_ENCRYPTED_KEY, JSON.stringify(payload))
}

const deriveEncryptionKey = async (passphrase: string, salt: Uint8Array, iterations: number) => {
  const saltBytes = new Uint8Array(salt)
  const baseKey = await crypto.subtle.importKey(
    'raw',
    encodeUtf8(passphrase),
    'PBKDF2',
    false,
    ['deriveKey']
  )

  return crypto.subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: saltBytes,
      iterations,
      hash: 'SHA-256',
    },
    baseKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  )
}

export const getOrCreateDeviceId = async (): Promise<string | null> => {
  if (typeof window === 'undefined') return null
  await initSecureStorage()
  const existing = await secureStorage.getItem(DEVICE_ID_KEY)
  if (existing) return existing
  const id = `web_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`
  await secureStorage.setItem(DEVICE_ID_KEY, id)
  return id
}

export const hasEncryptedKeyPair = async (): Promise<boolean> => {
  if (typeof window === 'undefined') return false
  await initSecureStorage()
  const value = await secureStorage.getItem(E2EE_KEYPAIR_ENCRYPTED_KEY)
  return value !== null
}

export const hasLegacyKeyPair = async (): Promise<boolean> => {
  if (typeof window === 'undefined') return false
  await initSecureStorage()
  const value = await secureStorage.getItem(E2EE_KEYPAIR_KEY)
  return value !== null
}

export const isKeyPairUnlocked = () => cachedKeyPair !== null
export const isKeyPairLocked = async (): Promise<boolean> => {
  const hasEncrypted = await hasEncryptedKeyPair()
  return hasEncrypted && !cachedKeyPair
}

export const lockKeyPair = () => {
  cachedKeyPair = null
  cachedKeyPairJwk = null
  cachedEncryptionKey = null
  cachedEncryptionSalt = null
}

const loadLegacyKeyPair = async (): Promise<StoredKeyPair | null> => {
  if (typeof window === 'undefined') return null
  await initSecureStorage()
  const existing = await secureStorage.getItem(E2EE_KEYPAIR_KEY)
  if (!existing) return null
  try {
    return JSON.parse(existing) as StoredKeyPair
  } catch {
    return null
  }
}

const storeLegacyKeyPair = async (payload: StoredKeyPair): Promise<void> => {
  if (typeof window === 'undefined') return
  await initSecureStorage()
  await secureStorage.setItem(E2EE_KEYPAIR_KEY, JSON.stringify(payload))
}

const storePublicKeyX963 = async (publicKey: CryptoKey): Promise<void> => {
  if (typeof window === 'undefined') return
  await initSecureStorage()
  const raw = await crypto.subtle.exportKey('raw', publicKey)
  await secureStorage.setItem(E2EE_PUBLIC_KEY_X963_KEY, bytesToBase64(new Uint8Array(raw)))
}

export const getOrCreateKeyPair = async () => {
  if (typeof window === 'undefined') return null
  if (cachedKeyPair) return cachedKeyPair

  if (await hasEncryptedKeyPair()) {
    return null
  }

  const legacy = await loadLegacyKeyPair()
  if (legacy) {
    const privateKey = await crypto.subtle.importKey(
      'jwk',
      legacy.privateKeyJwk,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      ['deriveBits']
    )
    const publicKey = await crypto.subtle.importKey(
      'jwk',
      legacy.publicKeyJwk,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      []
    )
    cachedKeyPair = { privateKey, publicKey }
    cachedKeyPairJwk = legacy
    await storePublicKeyX963(publicKey)
    return cachedKeyPair
  }

  const keyPair = await crypto.subtle.generateKey(
    { name: 'ECDH', namedCurve: 'P-256' },
    true,
    ['deriveBits']
  )

  const privateKeyJwk = await crypto.subtle.exportKey('jwk', keyPair.privateKey)
  const publicKeyJwk = await crypto.subtle.exportKey('jwk', keyPair.publicKey)

  await storeLegacyKeyPair({ privateKeyJwk, publicKeyJwk })
  await storePublicKeyX963(keyPair.publicKey)
  cachedKeyPair = keyPair
  cachedKeyPairJwk = { privateKeyJwk, publicKeyJwk }

  return keyPair
}

export const getStoredKeyPairJwk = async (): Promise<StoredKeyPair | null> => {
  if (typeof window === 'undefined') return null
  if (cachedKeyPairJwk) return cachedKeyPairJwk
  if (await hasEncryptedKeyPair()) return null
  await initSecureStorage()
  const existing = await secureStorage.getItem(E2EE_KEYPAIR_KEY)
  if (!existing) return null
  try {
    return JSON.parse(existing) as StoredKeyPair
  } catch {
    return null
  }
}

export const importKeyPairFromJwk = async (payload: StoredKeyPair) => {
  if (typeof window === 'undefined') return false
  try {
    const privateKey = await crypto.subtle.importKey(
      'jwk',
      payload.privateKeyJwk,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      ['deriveBits']
    )
    const publicKey = await crypto.subtle.importKey(
      'jwk',
      payload.publicKeyJwk,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      []
    )
    if (await hasEncryptedKeyPair()) {
      if (!cachedEncryptionKey || !cachedEncryptionSalt) return false
      cachedKeyPair = { privateKey, publicKey }
      const iv = crypto.getRandomValues(new Uint8Array(12))
      const plaintext = encodeUtf8(JSON.stringify(payload))
      const ciphertext = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv },
        cachedEncryptionKey,
        plaintext
      )
      const publicKeyX963 = await getPublicKeyX963Base64()
      const encryptedPayload: EncryptedKeyPayload = {
        v: 1,
        salt: bytesToBase64(cachedEncryptionSalt),
        iv: bytesToBase64(iv),
        ciphertext: bytesToBase64(new Uint8Array(ciphertext)),
        publicKeyJwk: payload.publicKeyJwk,
        publicKeyX963: publicKeyX963 || '',
        iterations: E2EE_PBKDF2_ITERATIONS,
      }
      await saveEncryptedPayload(encryptedPayload)
    } else {
      await storeLegacyKeyPair(payload)
    }
    cachedKeyPair = { privateKey, publicKey }
    cachedKeyPairJwk = payload
    return true
  } catch {
    return false
  }
}

export const getPublicKeyX963Base64 = async () => {
  if (typeof window !== 'undefined') {
    await initSecureStorage()
    const stored = await secureStorage.getItem(E2EE_PUBLIC_KEY_X963_KEY)
    if (!cachedKeyPair && stored) return stored
    if (!cachedKeyPair && !stored) {
      const encryptedPayload = await getEncryptedPayload()
      if (encryptedPayload?.publicKeyX963) return encryptedPayload.publicKeyX963
    }
  }
  const keyPair = await getOrCreateKeyPair()
  if (!keyPair) return null
  const raw = await crypto.subtle.exportKey('raw', keyPair.publicKey)
  return bytesToBase64(new Uint8Array(raw))
}

const deriveAesKey = async (sharedSecret: ArrayBuffer) => {
  const hkdfKey = await crypto.subtle.importKey(
    'raw',
    sharedSecret,
    'HKDF',
    false,
    ['deriveKey']
  )

  return crypto.subtle.deriveKey(
    {
      name: 'HKDF',
      hash: 'SHA-256',
      salt: new Uint8Array([]),
      info: textEncoder.encode(E2EE_CONTEXT),
    },
    hkdfKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  )
}

export const encryptDataForDevice = async (
  publicKeyX963Base64: string,
  data: Uint8Array
) => {
  const recipientKeyBytes = base64ToBytes(publicKeyX963Base64)
  const recipientKey = await crypto.subtle.importKey(
    'raw',
    recipientKeyBytes,
    { name: 'ECDH', namedCurve: 'P-256' },
    false,
    []
  )

  const ephemeralKeyPair = await crypto.subtle.generateKey(
    { name: 'ECDH', namedCurve: 'P-256' },
    true,
    ['deriveBits']
  )

  const sharedSecret = await crypto.subtle.deriveBits(
    { name: 'ECDH', public: recipientKey },
    ephemeralKeyPair.privateKey,
    256
  )

  const aesKey = await deriveAesKey(sharedSecret)
  const nonce = crypto.getRandomValues(new Uint8Array(12))
  const dataBytes = new Uint8Array(data)
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: nonce },
    aesKey,
    dataBytes
  )

  const ephemeralPublic = new Uint8Array(
    await crypto.subtle.exportKey('raw', ephemeralKeyPair.publicKey)
  )
  const ciphertextBytes = new Uint8Array(ciphertext)
  const envelope = new Uint8Array(ephemeralPublic.length + nonce.length + ciphertextBytes.length)
  envelope.set(ephemeralPublic, 0)
  envelope.set(nonce, ephemeralPublic.length)
  envelope.set(ciphertextBytes, ephemeralPublic.length + nonce.length)

  return `v2:${bytesToBase64(envelope)}`
}

export const decodeUtf8 = (bytes: Uint8Array) => textDecoder.decode(bytes)

export const encodeUtf8 = (value: string) => textEncoder.encode(value)

export const setPassphraseAndEncrypt = async (passphrase: string) => {
  if (typeof window === 'undefined') return false
  const legacy = await loadLegacyKeyPair()
  let keyPair: CryptoKeyPair | null = cachedKeyPair

  if (!keyPair && legacy) {
    const privateKey = await crypto.subtle.importKey(
      'jwk',
      legacy.privateKeyJwk,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      ['deriveBits']
    )
    const publicKey = await crypto.subtle.importKey(
      'jwk',
      legacy.publicKeyJwk,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      []
    )
    keyPair = { privateKey, publicKey }
  }

  if (!keyPair) {
    keyPair = await crypto.subtle.generateKey(
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      ['deriveBits']
    )
  }

  const privateKeyJwk = await crypto.subtle.exportKey('jwk', keyPair.privateKey)
  const publicKeyJwk = await crypto.subtle.exportKey('jwk', keyPair.publicKey)
  const payload: StoredKeyPair = { privateKeyJwk, publicKeyJwk }

  const salt = crypto.getRandomValues(new Uint8Array(16))
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const encryptionKey = await deriveEncryptionKey(passphrase, salt, E2EE_PBKDF2_ITERATIONS)
  const plaintext = encodeUtf8(JSON.stringify(payload))
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv },
    encryptionKey,
    plaintext
  )

  const rawPublic = await crypto.subtle.exportKey('raw', keyPair.publicKey)
  const publicKeyX963 = bytesToBase64(new Uint8Array(rawPublic))

  await saveEncryptedPayload({
    v: 1,
    salt: bytesToBase64(salt),
    iv: bytesToBase64(iv),
    ciphertext: bytesToBase64(new Uint8Array(ciphertext)),
    publicKeyJwk,
    publicKeyX963,
    iterations: E2EE_PBKDF2_ITERATIONS,
  })

  await initSecureStorage()
  await secureStorage.setItem(E2EE_PUBLIC_KEY_X963_KEY, publicKeyX963)
  await secureStorage.removeItem(E2EE_KEYPAIR_KEY)

  cachedKeyPair = keyPair
  cachedKeyPairJwk = payload
  cachedEncryptionKey = encryptionKey
  cachedEncryptionSalt = salt
  return true
}

export const unlockKeyPairWithPassphrase = async (passphrase: string) => {
  if (typeof window === 'undefined') return false
  const encryptedPayload = await getEncryptedPayload()
  if (!encryptedPayload) return false

  try {
    const salt = base64ToBytes(encryptedPayload.salt)
    const iv = base64ToBytes(encryptedPayload.iv)
    const ciphertext = base64ToBytes(encryptedPayload.ciphertext)
    const encryptionKey = await deriveEncryptionKey(
      passphrase,
      salt,
      encryptedPayload.iterations || E2EE_PBKDF2_ITERATIONS
    )
    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv },
      encryptionKey,
      ciphertext
    )
    const parsed = JSON.parse(decodeUtf8(new Uint8Array(plaintext))) as StoredKeyPair
    const privateKey = await crypto.subtle.importKey(
      'jwk',
      parsed.privateKeyJwk,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      ['deriveBits']
    )
    const publicKey = await crypto.subtle.importKey(
      'jwk',
      parsed.publicKeyJwk,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      []
    )
    cachedKeyPair = { privateKey, publicKey }
    cachedKeyPairJwk = parsed
    cachedEncryptionKey = encryptionKey
    cachedEncryptionSalt = salt
    await initSecureStorage()
    await secureStorage.setItem(E2EE_PUBLIC_KEY_X963_KEY, encryptedPayload.publicKeyX963 || '')
    return true
  } catch {
    return false
  }
}

export const importSyncGroupKeypair = async (
  privateKeyPKCS8Base64: string,
  publicKeyX963Base64: string
) => {
  if (typeof window === 'undefined') return false

  try {
    // Decode PKCS#8 private key
    const pkcs8Bytes = base64ToBytes(privateKeyPKCS8Base64)

    // Decode X9.63 public key
    const publicKeyBytes = base64ToBytes(publicKeyX963Base64)

    // Import private key using WebCrypto's native PKCS#8 support
    // (Previously we extracted the last 32 bytes as the raw key, but Java's PKCS#8
    // includes the public key at the end, so slice(-32) got the Y coordinate instead)
    const privateKey = await crypto.subtle.importKey(
      'pkcs8',
      pkcs8Bytes,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      ['deriveBits']
    )

    // Export as JWK to get correct d, x, y values
    const privateKeyJwk = await crypto.subtle.exportKey('jwk', privateKey) as JsonWebKey

    // Import public key from X9.63 raw format
    const publicKey = await crypto.subtle.importKey(
      'raw',
      publicKeyBytes,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      []
    )

    const publicKeyJwk = await crypto.subtle.exportKey('jwk', publicKey) as JsonWebKey

    // Store keypair
    const payload: StoredKeyPair = { privateKeyJwk, publicKeyJwk }

    if (await hasEncryptedKeyPair() && cachedEncryptionKey && cachedEncryptionSalt) {
      // Re-encrypt with existing passphrase
      const iv = crypto.getRandomValues(new Uint8Array(12))
      const plaintext = encodeUtf8(JSON.stringify(payload))
      const ciphertext = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv },
        cachedEncryptionKey,
        plaintext
      )
      const encryptedPayload: EncryptedKeyPayload = {
        v: 1,
        salt: bytesToBase64(cachedEncryptionSalt),
        iv: bytesToBase64(iv),
        ciphertext: bytesToBase64(new Uint8Array(ciphertext)),
        publicKeyJwk,
        publicKeyX963: publicKeyX963Base64,
        iterations: E2EE_PBKDF2_ITERATIONS,
      }
      await saveEncryptedPayload(encryptedPayload)
    } else {
      // Store as legacy unencrypted keypair
      await storeLegacyKeyPair(payload)
    }

    // Update cache
    cachedKeyPair = { privateKey, publicKey }
    cachedKeyPairJwk = payload
    await initSecureStorage()
    await secureStorage.setItem(E2EE_PUBLIC_KEY_X963_KEY, publicKeyX963Base64)

    return true
  } catch (error) {
    console.error('Failed to import sync group keypair:', error)
    return false
  }
}

export const decryptDataKey = async (envelope: string) => {
  const keyPair = await getOrCreateKeyPair()
  if (!keyPair) return null

  const payload = envelope.replace(/^v2:/, '')
  const bytes = base64ToBytes(payload)
  if (bytes.length < 65 + 12 + 16) return null

  const ephemeralPublic = bytes.slice(0, 65)
  const nonce = bytes.slice(65, 77)
  const ciphertext = bytes.slice(77)

  const publicKey = await crypto.subtle.importKey(
    'raw',
    ephemeralPublic,
    { name: 'ECDH', namedCurve: 'P-256' },
    false,
    []
  )

  const sharedSecret = await crypto.subtle.deriveBits(
    { name: 'ECDH', public: publicKey },
    keyPair.privateKey,
    256
  )

  const aesKey = await deriveAesKey(sharedSecret)
  try {
    const dataKey = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: nonce },
      aesKey,
      ciphertext
    )
    return new Uint8Array(dataKey)
  } catch {
    return null
  }
}

export const decryptMessageBody = async (
  dataKey: Uint8Array,
  ciphertextBase64: string,
  nonceBase64: string
) => {
  const ciphertext = base64ToBytes(ciphertextBase64)
  const nonce = base64ToBytes(nonceBase64)
  // Copy to a fresh ArrayBuffer to satisfy TypeScript's strict type checking
  const keyArrayBuffer = new ArrayBuffer(dataKey.length)
  new Uint8Array(keyArrayBuffer).set(dataKey)
  const aesKey = await crypto.subtle.importKey(
    'raw',
    keyArrayBuffer,
    { name: 'AES-GCM' },
    false,
    ['decrypt']
  )
  try {
    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: nonce },
      aesKey,
      ciphertext
    )
    return new TextDecoder().decode(plaintext)
  } catch {
    return null
  }
}

/**
 * Migrate E2EE keys from localStorage to IndexedDB
 *
 * SECURITY FIX: localStorage is vulnerable to XSS attacks.
 * This function migrates keys to IndexedDB for better security.
 *
 * Call this on app startup to ensure keys are moved to secure storage.
 *
 * NOTE: This is a partial fix. Full migration requires making all E2EE
 * functions async, which is a larger refactoring tracked in task #21.
 */
export async function migrateE2EEKeysToIndexedDB(): Promise<void> {
  try {
    console.log('[E2EE] Checking for keys to migrate from localStorage...')

    const keysToMigrate = [
      E2EE_KEYPAIR_KEY,
      E2EE_KEYPAIR_ENCRYPTED_KEY,
      E2EE_PUBLIC_KEY_X963_KEY,
    ]

    await initSecureStorage(keysToMigrate)

    console.log('[E2EE] Migration to IndexedDB complete')
  } catch (error) {
    console.error('[E2EE] Failed to migrate keys to IndexedDB:', error)
    // Don't throw - allow app to continue with localStorage fallback
  }
}
