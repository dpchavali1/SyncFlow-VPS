/**
 * Browser-side End-to-End Encryption (E2EE) using Web Crypto API.
 *
 * Implements ECDH P-256 key agreement with HKDF-derived AES-256-GCM for
 * message encryption. Keypairs can be stored either as plaintext JWK in
 * IndexedDB ("legacy") or encrypted with a user passphrase via PBKDF2
 * ("encrypted"). The encrypted path protects keys at rest so that even
 * XSS cannot extract the raw private key without the passphrase.
 *
 * Wire format (v2 envelope):
 *   "v2:" + base64( ephemeralPublicKey(65B) || nonce(12B) || AES-GCM ciphertext )
 *
 * Key lifecycle:
 *   1. getOrCreateKeyPair  - generate or load legacy keypair
 *   2. setPassphraseAndEncrypt - migrate to passphrase-protected storage
 *   3. unlockKeyPairWithPassphrase - decrypt keys into memory for use
 *   4. lockKeyPair - wipe in-memory keys (e.g. on idle timeout)
 *
 * Cross-device key sync:
 *   importSyncGroupKeypair() accepts a PKCS#8 private key + X9.63 public
 *   key pushed from the Android device so all platforms share one identity.
 */
'use client'

import { secureStorage, initSecureStorage } from './secureStorage'

const DEVICE_ID_KEY = 'syncflow_device_id'
const E2EE_KEYPAIR_KEY = 'syncflow_e2ee_jwk'
const E2EE_KEYPAIR_ENCRYPTED_KEY = 'syncflow_e2ee_jwk_encrypted'
const E2EE_PUBLIC_KEY_X963_KEY = 'syncflow_e2ee_public_key_x963'
const E2EE_SIGNING_KEYPAIR_KEY = 'syncflow_e2ee_signing_jwk'
const E2EE_KEY_VERSIONS_KEY = 'syncflow_e2ee_key_versions'
const E2EE_CONTEXT = 'SyncFlow-E2EE-v2'
const E2EE_PBKDF2_ITERATIONS = 400000
const E2EE_PBKDF2_ITERATIONS_LEGACY = 150000
const IDLE_LOCK_MS = 15 * 60 * 1000 // 15 minutes

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
  const id = crypto.randomUUID()
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

/** Clear all E2EE keys from both memory and persistent storage (IndexedDB). */
export const clearAllE2EEKeys = async () => {
  lockKeyPair()
  await secureStorage.removeItem(E2EE_KEYPAIR_KEY)
  await secureStorage.removeItem(E2EE_KEYPAIR_ENCRYPTED_KEY)
  await secureStorage.removeItem(E2EE_PUBLIC_KEY_X963_KEY)
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
    const keysToMigrate = [
      E2EE_KEYPAIR_KEY,
      E2EE_KEYPAIR_ENCRYPTED_KEY,
      E2EE_PUBLIC_KEY_X963_KEY,
      E2EE_SIGNING_KEYPAIR_KEY,
      E2EE_KEY_VERSIONS_KEY,
    ]

    await initSecureStorage(keysToMigrate)
  } catch (error) {
    console.error('[E2EE] Failed to migrate keys to IndexedDB:', error)
    // Don't throw - allow app to continue with localStorage fallback
  }
}

// MARK: - ECDSA Signing Keypair

type StoredSigningKeyPair = {
  privateKeyJwk: JsonWebKey
  publicKeyJwk: JsonWebKey
}

/**
 * Get or create ECDSA P-256 signing keypair for device authentication.
 * Returns the public key in X9.63 format (Base64 encoded).
 */
export const getOrCreateSigningKeyPair = async (): Promise<string | null> => {
  if (typeof window === 'undefined') return null
  await initSecureStorage()

  // Check if signing key already exists
  const existing = await secureStorage.getItem(E2EE_SIGNING_KEYPAIR_KEY)
  if (existing) {
    try {
      const parsed = JSON.parse(existing) as StoredSigningKeyPair
      const publicKey = await crypto.subtle.importKey(
        'jwk',
        parsed.publicKeyJwk,
        { name: 'ECDSA', namedCurve: 'P-256' },
        true,
        ['verify']
      )
      const raw = await crypto.subtle.exportKey('raw', publicKey)
      return bytesToBase64(new Uint8Array(raw))
    } catch {
      // Corrupt, regenerate
      await secureStorage.removeItem(E2EE_SIGNING_KEYPAIR_KEY)
    }
  }

  // Generate new signing keypair
  const keyPair = await crypto.subtle.generateKey(
    { name: 'ECDSA', namedCurve: 'P-256' },
    true,
    ['sign', 'verify']
  )

  const privateKeyJwk = await crypto.subtle.exportKey('jwk', keyPair.privateKey)
  const publicKeyJwk = await crypto.subtle.exportKey('jwk', keyPair.publicKey)

  await secureStorage.setItem(
    E2EE_SIGNING_KEYPAIR_KEY,
    JSON.stringify({ privateKeyJwk, publicKeyJwk })
  )

  const raw = await crypto.subtle.exportKey('raw', keyPair.publicKey)
  return bytesToBase64(new Uint8Array(raw))
}

/**
 * Verify an ECDSA signature on data using a provided signing public key.
 */
export const verifySignature = async (
  data: Uint8Array,
  signature: Uint8Array,
  signingPublicKeyX963Base64: string
): Promise<boolean> => {
  try {
    const publicKeyBytes = base64ToBytes(signingPublicKeyX963Base64)
    const publicKey = await crypto.subtle.importKey(
      'raw',
      publicKeyBytes,
      { name: 'ECDSA', namedCurve: 'P-256' },
      false,
      ['verify']
    )

    return crypto.subtle.verify(
      { name: 'ECDSA', hash: 'SHA-256' },
      publicKey,
      signature.buffer as ArrayBuffer,
      data.buffer as ArrayBuffer
    )
  } catch {
    return false
  }
}

/**
 * Derive a safety number from the sync group public key.
 * SHA-256(publicKeyX963) → first 30 bytes → 12 groups of 5 digits.
 */
export const deriveSafetyNumber = async (): Promise<string | null> => {
  const publicKeyX963 = await getPublicKeyX963Base64()
  if (!publicKeyX963) return null

  try {
    const publicKeyBytes = base64ToBytes(publicKeyX963)
    const hashBuffer = await crypto.subtle.digest('SHA-256', publicKeyBytes)
    const hashBytes = new Uint8Array(hashBuffer)
    const groups: string[] = []

    for (let i = 0; i < 30; i += 5) {
      const chunk = hashBytes.slice(i, i + 5)
      let value = 0n
      for (const byte of chunk) {
        value = (value << 8n) | BigInt(byte)
      }
      groups.push(String(Number(value % 100000n)).padStart(5, '0'))
    }

    return groups.join(' ')
  } catch {
    return null
  }
}

// MARK: - Key Versioning

/**
 * Import a sync group keypair with a specific key version.
 */
export const importSyncGroupKeypairVersioned = async (
  privateKeyPKCS8Base64: string,
  publicKeyX963Base64: string,
  keyVersion: number
): Promise<boolean> => {
  const result = await importSyncGroupKeypair(privateKeyPKCS8Base64, publicKeyX963Base64)
  if (!result) return false

  // Store version mapping
  try {
    await initSecureStorage()
    const existingRaw = await secureStorage.getItem(E2EE_KEY_VERSIONS_KEY)
    const versions: Record<string, JsonWebKey> = existingRaw ? JSON.parse(existingRaw) : {}

    if (cachedKeyPairJwk) {
      versions[String(keyVersion)] = cachedKeyPairJwk.privateKeyJwk
      await secureStorage.setItem(E2EE_KEY_VERSIONS_KEY, JSON.stringify(versions))
    }
  } catch {
    // Non-fatal
  }

  return true
}

/**
 * Decrypt a data key, trying the specified key version and falling back to all stored versions.
 */
export const decryptDataKeyWithVersion = async (
  envelope: string,
  keyVersion?: number
): Promise<Uint8Array | null> => {
  // Try current key first
  const result = await decryptDataKey(envelope)
  if (result) return result

  // Try versioned keys
  try {
    await initSecureStorage()
    const versionsRaw = await secureStorage.getItem(E2EE_KEY_VERSIONS_KEY)
    if (!versionsRaw) return null
    const versions: Record<string, JsonWebKey> = JSON.parse(versionsRaw)

    // Try specific version first if provided
    const versionOrder = keyVersion
      ? [String(keyVersion), ...Object.keys(versions).filter(v => v !== String(keyVersion)).sort((a, b) => Number(b) - Number(a))]
      : Object.keys(versions).sort((a, b) => Number(b) - Number(a))

    for (const v of versionOrder) {
      const jwk = versions[v]
      if (!jwk) continue
      const vResult = await tryDecryptWithJwk(envelope, jwk)
      if (vResult) return vResult
    }
  } catch {
    // Fall through
  }

  return null
}

const tryDecryptWithJwk = async (
  envelope: string,
  privateKeyJwk: JsonWebKey
): Promise<Uint8Array | null> => {
  try {
    const privateKey = await crypto.subtle.importKey(
      'jwk',
      privateKeyJwk,
      { name: 'ECDH', namedCurve: 'P-256' },
      false,
      ['deriveBits']
    )

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
      privateKey,
      256
    )

    const aesKey = await deriveAesKey(sharedSecret)
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

// MARK: - Key Backup & Recovery

type KeyBackup = {
  encryptedBackup: string
  salt: string
  iterations: number
  keyVersion: number
  createdAt: number
}

/**
 * Restore sync group keys from encrypted server backups.
 * Decrypts each backup using the user's passphrase and imports the keys.
 */
export const restoreFromBackup = async (
  passphrase: string,
  backups: KeyBackup[]
): Promise<boolean> => {
  if (typeof window === 'undefined') return false

  try {
    for (const backup of backups) {
      const combined = base64ToBytes(backup.encryptedBackup)
      const salt = base64ToBytes(backup.salt)

      if (combined.length <= 12) continue

      // Derive AES-256 key from passphrase using PBKDF2
      const baseKey = await crypto.subtle.importKey(
        'raw',
        encodeUtf8(passphrase),
        'PBKDF2',
        false,
        ['deriveKey']
      )

      const aesKey = await crypto.subtle.deriveKey(
        {
          name: 'PBKDF2',
          salt,
          iterations: backup.iterations,
          hash: 'SHA-256',
        },
        baseKey,
        { name: 'AES-GCM', length: 256 },
        false,
        ['decrypt']
      )

      // Decrypt: first 12 bytes = nonce, rest = ciphertext + tag
      const nonce = combined.slice(0, 12)
      const ciphertext = combined.slice(12)

      const plaintext = await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: nonce },
        aesKey,
        ciphertext
      )

      const json = JSON.parse(new TextDecoder().decode(plaintext))
      const privateKeyPKCS8 = json.privateKeyPKCS8 as string
      const publicKeyX963 = json.publicKeyX963 as string | undefined

      if (privateKeyPKCS8 && publicKeyX963) {
        await importSyncGroupKeypairVersioned(privateKeyPKCS8, publicKeyX963, backup.keyVersion)
      }
    }

    return true
  } catch (error) {
    console.error('[E2EE] Failed to restore from backup:', error)
    return false
  }
}

// MARK: - Idle Auto-Lock

let idleTimer: ReturnType<typeof setTimeout> | null = null

/**
 * Reset the idle auto-lock timer. Only active when passphrase-protected keys exist.
 * After IDLE_LOCK_MS of inactivity, keys are wiped from memory.
 */
export const resetIdleTimer = async () => {
  if (typeof window === 'undefined') return

  // Only auto-lock if passphrase-protected keypair exists
  const hasEncrypted = await hasEncryptedKeyPair()
  if (!hasEncrypted) return

  if (idleTimer) clearTimeout(idleTimer)
  idleTimer = setTimeout(() => {
    lockKeyPair()
    console.log('[E2EE] Keys auto-locked due to inactivity')
  }, IDLE_LOCK_MS)
}
