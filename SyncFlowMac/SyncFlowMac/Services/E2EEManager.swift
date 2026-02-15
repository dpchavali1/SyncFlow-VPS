//
//  E2EEManager.swift
//  SyncFlowMac
//
//  End-to-end encryption manager using CryptoKit
//  Compatible with Android's Tink ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM
//

/**
 * E2EEManager - End-to-End Encryption Engine
 *
 * Manages all cryptographic operations for SyncFlow's E2EE message sync.
 * Uses Apple CryptoKit with P-256 ECDH key agreement and AES-GCM encryption,
 * compatible with Android's Tink ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM scheme.
 *
 * Key Architecture:
 * - Sync Group Keypair: All devices in a user's sync group share the same P-256
 *   keypair, eliminating the need for per-device keyMaps. The Android device
 *   generates the keypair and securely transfers it to macOS during pairing.
 * - Device Keypair: Used temporarily before sync group keys are imported, and
 *   for encrypting the sync group key transfer itself.
 * - Keys are stored in the macOS Keychain (kSecClassGenericPassword).
 *
 * Encryption Formats Supported:
 * - v2 envelope: [65B ephemeral pubkey | 12B nonce | ciphertext | 16B tag]
 *   Used for data key encryption and sync group key exchange.
 * - Tink ECIES: [4B key ID | 1B pubkey len | 65B ephemeral pubkey | 12B nonce
 *   | ciphertext | 16B tag] -- Android's native Tink format.
 * - CryptoKit native: [32B raw pubkey | AES-GCM combined] -- fallback format.
 *
 * PKCS#8 / DER Parsing:
 * - Android exports private keys in PKCS#8 DER format; this manager includes
 *   a minimal DER reader to extract the raw 32-byte P-256 scalar from the
 *   nested ASN.1 structure (PKCS#8 > OCTET STRING > SEC1 ECPrivateKey).
 */

import Foundation
import CryptoKit
import CommonCrypto

class E2EEManager {

    static let shared = E2EEManager()

    private let keychainService = "com.syncflow.e2ee"
    private let privateKeyTag = "e2ee_private_key"
    private let publicKeyTag = "e2ee_public_key"
    private let signingPrivateKeyTag = "e2ee_signing_private_key"
    private let signingPublicKeyTag = "e2ee_signing_public_key"

    private var privateKey: P256.KeyAgreement.PrivateKey?
    private var publicKey: P256.KeyAgreement.PublicKey?

    // ECDSA signing keypair (per-device, proves "this device sent this payload")
    private var signingPrivateKey: P256.Signing.PrivateKey?
    private var signingPublicKey: P256.Signing.PublicKey?

    // Thread safety lock for key access
    private let keyLock = NSLock()

    // Key versioning
    private(set) var currentKeyVersion: Int {
        get {
            let v = UserDefaults.standard.integer(forKey: "e2ee_current_key_version")
            return v > 0 ? v : 1
        }
        set { UserDefaults.standard.set(newValue, forKey: "e2ee_current_key_version") }
    }
    var keyVersions: [Int: P256.KeyAgreement.PrivateKey] = [:]

    private let contextInfo = "SyncFlow-E2EE-v1".data(using: .utf8)!
    private let contextInfoV2 = "SyncFlow-E2EE-v2".data(using: .utf8)!

    private init() {
        loadExistingKeys()
        loadSigningKeys()
    }

    // MARK: - Key Management

    /// Initialize E2EE keys - generates new keys if not exists
    func initializeKeys() async throws {
        if privateKey != nil {
            return
        }

        // Generate new key pair
        let newPrivateKey = P256.KeyAgreement.PrivateKey()
        let newPublicKey = newPrivateKey.publicKey

        // Store keys in Keychain
        try storePrivateKey(newPrivateKey)
        try storePublicKey(newPublicKey)

        self.privateKey = newPrivateKey
        self.publicKey = newPublicKey
    }

    /// Check if E2EE is initialized
    var isInitialized: Bool {
        return privateKey != nil
    }

    /// Load existing keys from Keychain
    private func loadExistingKeys() {
        guard let privateKeyData = loadFromKeychain(tag: privateKeyTag) else {
            return
        }

        do {
            let rawRepresentation = privateKeyData
            privateKey = try P256.KeyAgreement.PrivateKey(rawRepresentation: rawRepresentation)
            publicKey = privateKey?.publicKey
        } catch {
            #if DEBUG
            print("[E2EE] Error loading keys: \(error)")
            #endif
            // Clear corrupt keys
            deleteFromKeychain(tag: privateKeyTag)
            deleteFromKeychain(tag: publicKeyTag)
        }
    }

    // MARK: - Keychain Operations

    private func storePrivateKey(_ key: P256.KeyAgreement.PrivateKey) throws {
        let rawKey = key.rawRepresentation
        try storeInKeychain(data: rawKey, tag: privateKeyTag)
    }

    private func storePublicKey(_ key: P256.KeyAgreement.PublicKey) throws {
        let rawKey = key.rawRepresentation
        try storeInKeychain(data: rawKey, tag: publicKeyTag)
    }

    private func storeInKeychain(data: Data, tag: String) throws {
        // Delete existing item first
        deleteFromKeychain(tag: tag)

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: tag,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]

        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw E2EEError.keychainError(status)
        }
    }

    private func loadFromKeychain(tag: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: tag,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess else {
            return nil
        }

        return result as? Data
    }

    private func deleteFromKeychain(tag: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: tag
        ]

        SecItemDelete(query as CFDictionary)
    }

    /// Encrypt a message body for sync storage.
    /// Returns (encryptedBody, encryptedNonce, keyMap) or nil if E2EE is not initialized.
    func encryptForSync(_ body: String) -> (encryptedBody: String, encryptedNonce: String, keyMap: [String: String])? {
        guard let publicKey = publicKey else { return nil }

        do {
            // Generate random data key
            var dataKey = Data(count: 32)
            _ = dataKey.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }

            // Encrypt body with data key (AES-GCM)
            let bodyData = body.data(using: .utf8) ?? Data()
            let symmetricKey = SymmetricKey(data: dataKey)
            let sealedBox = try AES.GCM.seal(bodyData, using: symmetricKey)
            guard let combined = sealedBox.combined else { return nil }

            let nonceSize = 12
            let nonce = combined.prefix(nonceSize)
            let ciphertextAndTag = combined.dropFirst(nonceSize)

            let encryptedBody = Data(ciphertextAndTag).base64EncodedString()
            let encryptedNonce = Data(nonce).base64EncodedString()

            // Encrypt data key for sync group
            let publicKeyX963 = publicKey.x963Representation.base64EncodedString()
            let encryptedDataKey = try encryptDataKeyForDevice(
                publicKeyX963Base64: publicKeyX963,
                data: dataKey
            )

            return (encryptedBody, encryptedNonce, ["syncGroup": encryptedDataKey])
        } catch {
            #if DEBUG
            print("[E2EE] encryptForSync failed: \(error.localizedDescription)")
            #endif
            return nil
        }
    }

    // MARK: - VPS Key Operations

    /// Publish device public key via VPS
    func publishDevicePublicKey() async throws {
        guard let publicKey = publicKey else {
            throw E2EEError.notInitialized
        }

        let publicKeyX963 = publicKey.x963Representation.base64EncodedString()
        try await VPSService.shared.publishE2eePublicKey(publicKeyX963Base64: publicKeyX963)
    }

    /// Reset E2EE keys - clears existing keys and generates new ones.
    /// Used for manual re-sync when key exchange fails.
    func resetAndRegenerateKeys() async throws {
        // Clear in-memory keys
        privateKey = nil
        publicKey = nil

        // Clear Keychain
        deleteFromKeychain(tag: privateKeyTag)
        deleteFromKeychain(tag: publicKeyTag)

        // Generate new key pair
        let newPrivateKey = P256.KeyAgreement.PrivateKey()
        let newPublicKey = newPrivateKey.publicKey

        // Store keys in Keychain
        try storePrivateKey(newPrivateKey)
        try storePublicKey(newPublicKey)

        self.privateKey = newPrivateKey
        self.publicKey = newPublicKey

        #if DEBUG
        print("[E2EE] Keys reset and regenerated")
        #endif
    }

    /// Get recipient's public key - E2EE uses shared sync group keypair,
    /// so we use our own public key for encryption
    func getPublicKey(for uid: String) async throws -> P256.KeyAgreement.PublicKey? {
        return publicKey
    }

    /// Parse Tink JSON format public key
    private func parseTinkPublicKey(_ jsonString: String) throws -> P256.KeyAgreement.PublicKey {
        // Tink stores keys in a specific JSON format
        // For now, we'll need to extract the raw key bytes
        // This is a simplified parser - in production, use proper Tink SDK

        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let keyArray = json["key"] as? [[String: Any]],
              let firstKey = keyArray.first,
              let keyData = firstKey["keyData"] as? [String: Any],
              let value = keyData["value"] as? String,
              let keyBytes = Data(base64Encoded: value) else {
            throw E2EEError.invalidPublicKey
        }

        // Tink ECIES key format: skip first few bytes (metadata) to get raw P256 key
        // The exact format depends on Tink version, this may need adjustment
        let rawKeyStart = keyBytes.count - 65  // P256 uncompressed public key is 65 bytes
        if rawKeyStart >= 0 {
            let rawKey = keyBytes.suffix(65)
            return try P256.KeyAgreement.PublicKey(x963Representation: rawKey)
        }

        throw E2EEError.invalidPublicKey
    }

    // MARK: - Encryption/Decryption

    /// Encrypt a message for a recipient
    func encryptMessage(_ message: String, for recipientUid: String) async throws -> String {
        guard let privateKey = privateKey else {
            throw E2EEError.notInitialized
        }

        guard let recipientPublicKey = try await getPublicKey(for: recipientUid) else {
            throw E2EEError.recipientKeyNotFound
        }

        // Perform ECDH key agreement
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: recipientPublicKey)

        // Derive symmetric key using HKDF
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfo,
            outputByteCount: 32
        )

        // Encrypt with AES-GCM
        guard let messageData = message.data(using: .utf8) else {
            throw E2EEError.encodingError
        }

        let sealedBox = try AES.GCM.seal(messageData, using: symmetricKey)

        guard let combined = sealedBox.combined else {
            throw E2EEError.encryptionFailed
        }

        // Include our ephemeral public key in the output for the recipient
        var output = Data()
        output.append(publicKey!.rawRepresentation)  // 32 bytes
        output.append(combined)

        return output.base64EncodedString()
    }

    /// Decrypt a message from a sender
    /// Supports both CryptoKit format and Android Tink ECIES format
    func decryptMessage(_ encryptedMessage: String) throws -> String {
        guard let privateKey = privateKey else {
            throw E2EEError.notInitialized
        }

        guard let encryptedData = Data(base64Encoded: encryptedMessage) else {
            throw E2EEError.decodingError
        }

        // Try Tink ECIES format first (Android)
        // Tink ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM format:
        // - 4 bytes: key ID (we skip)
        // - 1 byte: length of ephemeral public key
        // - 65 bytes: ephemeral public key (uncompressed P256)
        // - remaining: AES-GCM ciphertext (nonce + ciphertext + tag)
        if encryptedData.count > 70 {
            do {
                return try decryptTinkMessage(encryptedData)
            } catch {
                #if DEBUG
                print("[E2EE] Tink decryption failed, trying CryptoKit format: \(error)")
                #endif
            }
        }

        // Fall back to CryptoKit format
        // Format: 32 bytes public key + AES-GCM ciphertext
        guard encryptedData.count > 32 else {
            throw E2EEError.invalidCiphertext
        }

        let senderPublicKeyData = encryptedData.prefix(32)
        let ciphertext = encryptedData.dropFirst(32)

        // Reconstruct sender's public key
        let senderPublicKey = try P256.KeyAgreement.PublicKey(rawRepresentation: senderPublicKeyData)

        // Perform ECDH key agreement
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: senderPublicKey)

        // Derive symmetric key using HKDF
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfo,
            outputByteCount: 32
        )

        // Decrypt with AES-GCM
        let sealedBox = try AES.GCM.SealedBox(combined: ciphertext)
        let decryptedData = try AES.GCM.open(sealedBox, using: symmetricKey)

        guard let decryptedMessage = String(data: decryptedData, encoding: .utf8) else {
            throw E2EEError.decodingError
        }

        return decryptedMessage
    }

    func decryptDataKey(from envelope: String) throws -> Data {
        guard let privateKey = privateKey else {
            throw E2EEError.notInitialized
        }

        let payload = envelope.replacingOccurrences(of: "v2:", with: "")
        guard let bytes = Data(base64Encoded: payload), bytes.count > 65 + 12 + 16 else {
            throw E2EEError.invalidCiphertext
        }

        let ephemeralPublicKeyData = bytes.prefix(65)
        let nonce = bytes.dropFirst(65).prefix(12)
        let ciphertextAndTag = bytes.dropFirst(77)

        let ephemeralPublicKey = try P256.KeyAgreement.PublicKey(x963Representation: ephemeralPublicKeyData)
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: ephemeralPublicKey)

        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfoV2,
            outputByteCount: 32
        )

        let sealedBox = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonce),
            ciphertext: ciphertextAndTag.dropLast(16),
            tag: ciphertextAndTag.suffix(16)
        )

        return try AES.GCM.open(sealedBox, using: symmetricKey)
    }

    func decryptMessageBody(dataKey: Data, ciphertextWithTag: Data, nonce: Data) throws -> String {
        let sealedBox = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonce),
            ciphertext: ciphertextWithTag.dropLast(16),
            tag: ciphertextWithTag.suffix(16)
        )

        let decrypted = try AES.GCM.open(sealedBox, using: SymmetricKey(data: dataKey))
        guard let message = String(data: decrypted, encoding: .utf8) else {
            throw E2EEError.decodingError
        }
        return message
    }

    /// Decrypt a message encrypted with Android's Tink ECIES
    private func decryptTinkMessage(_ encryptedData: Data) throws -> String {
        guard let privateKey = privateKey else {
            throw E2EEError.notInitialized
        }

        // Tink ECIES format:
        // - 4 bytes: key ID prefix
        // - 1 byte: public key length (should be 65 for uncompressed P256)
        // - 65 bytes: ephemeral public key (uncompressed format: 0x04 + X + Y)
        // - 12 bytes: AES-GCM nonce/IV
        // - N bytes: ciphertext
        // - 16 bytes: AES-GCM tag

        guard encryptedData.count > 4 + 1 + 65 + 12 + 16 else {
            throw E2EEError.invalidCiphertext
        }

        var offset = 4  // Skip key ID prefix

        // Read public key length
        let pubKeyLength = Int(encryptedData[offset])
        offset += 1

        guard pubKeyLength == 65 else {
            throw E2EEError.invalidPublicKey
        }

        // Extract ephemeral public key (uncompressed P256: 0x04 + 32 bytes X + 32 bytes Y)
        let ephemeralPublicKeyData = encryptedData[offset..<(offset + 65)]
        offset += 65

        let ephemeralPublicKey = try P256.KeyAgreement.PublicKey(x963Representation: ephemeralPublicKeyData)

        // Perform ECDH key agreement
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: ephemeralPublicKey)

        // Tink uses HKDF with:
        // - Hash: SHA256
        // - Salt: empty (all zeros)
        // - Info: "SyncFlow-E2EE-v1" (our context) - but Tink might use empty
        // - Output: 32 bytes for AES-256 or 16 bytes for AES-128

        // Try with our context info first
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfo,
            outputByteCount: 16  // AES-128 as per Tink template
        )

        // Extract nonce (12 bytes) and ciphertext+tag
        let nonce = encryptedData[offset..<(offset + 12)]
        offset += 12

        let ciphertextAndTag = encryptedData[offset...]

        // Construct AES-GCM sealed box
        let sealedBox = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonce),
            ciphertext: ciphertextAndTag.dropLast(16),
            tag: ciphertextAndTag.suffix(16)
        )

        let decryptedData = try AES.GCM.open(sealedBox, using: symmetricKey)

        guard let decryptedMessage = String(data: decryptedData, encoding: .utf8) else {
            throw E2EEError.decodingError
        }

        return decryptedMessage
    }

    /// Get my public key as Base64 string
    func getMyPublicKey() -> String? {
        return publicKey?.rawRepresentation.base64EncodedString()
    }

    /// Get my public key as X9.63 Base64 string (for per-device E2EE v2)
    func getMyPublicKeyX963Base64() -> String? {
        return publicKey?.x963Representation.base64EncodedString()
    }

    /// Get my private key raw bytes
    func getMyPrivateKeyRaw() -> Data? {
        return privateKey?.rawRepresentation
    }

    /// Replace local keypair with a provided private key
    func importPrivateKey(rawData: Data) throws {
        let newPrivateKey = try P256.KeyAgreement.PrivateKey(rawRepresentation: rawData)
        let newPublicKey = newPrivateKey.publicKey

        try storePrivateKey(newPrivateKey)
        try storePublicKey(newPublicKey)

        self.privateKey = newPrivateKey
        self.publicKey = newPublicKey
    }

    /// Import sync group keypair from Android device during key sync.
    ///
    /// NEW BEHAVIOR (v3 - Shared Sync Group Keypair):
    /// Replaces the macOS device's locally generated keypair with the sync group keypair
    /// from the Android device. All devices in the sync group share the same keypair,
    /// eliminating the need for per-device keyMaps and backfill.
    ///
    /// This allows the macOS device to immediately decrypt all existing messages without
    /// needing a backfill operation to add its public key to every message's keyMap.
    ///
    /// - Parameters:
    ///   - privateKeyPKCS8Base64: Android's private key in PKCS#8 DER format (Base64 encoded)
    ///   - publicKeyX963Base64: Android's public key in X9.63 format (Base64 encoded)
    /// - Throws: E2EEError if import fails
    func importSyncGroupKeypair(privateKeyPKCS8Base64: String, publicKeyX963Base64: String) throws {
        // Decode the PKCS#8 private key
        guard let privateKeyData = Data(base64Encoded: privateKeyPKCS8Base64) else {
            throw E2EEError.invalidPrivateKey
        }

        // Decode the X9.63 public key
        guard let publicKeyData = Data(base64Encoded: publicKeyX963Base64) else {
            throw E2EEError.invalidPublicKey
        }

        // PKCS#8 DER format parsing:
        // We need to extract the raw P-256 private key (32 bytes) from the PKCS#8 structure
        // PKCS#8 format: SEQUENCE { version, privateKeyAlgorithm, OCTET STRING containing EC private key }
        // The EC private key itself is wrapped in another SEQUENCE with the 32-byte raw key in an OCTET STRING
        let rawPrivateKey: Data
        if let normalized = normalizeRawPrivateKey(privateKeyData) {
            rawPrivateKey = normalized
        } else if let parsed = extractRawPrivateKeyFromPkcs8(privateKeyData) {
            rawPrivateKey = parsed
            #if DEBUG
            print("[E2EE] Extracted raw private key from PKCS#8 (\(parsed.count) bytes)")
            #endif
        } else {
            // PKCS#8 DER parsing failed and raw key is not 32 bytes — reject
            throw E2EEError.invalidPrivateKey
        }

        // Import the keys
        let newPrivateKey = try P256.KeyAgreement.PrivateKey(rawRepresentation: rawPrivateKey)
        let newPublicKey = try P256.KeyAgreement.PublicKey(x963Representation: publicKeyData)

        // Verify the keys match (public key should be derivable from private key)
        guard newPrivateKey.publicKey.x963Representation == newPublicKey.x963Representation else {
            throw E2EEError.keyMismatch
        }

        // Store the sync group keypair
        try storePrivateKey(newPrivateKey)
        try storePublicKey(newPublicKey)

        keyLock.lock()
        self.privateKey = newPrivateKey
        self.publicKey = newPublicKey
        keyLock.unlock()

        // Mark that sync group keys have been imported (distinct from local key init)
        // Store in Keychain for tamper resistance (not UserDefaults)
        try storeInKeychain(data: Data([1]), tag: "e2ee_sync_group_imported")

        #if DEBUG
        print("[E2EE] Successfully imported sync group keypair from Android device")
        #endif
    }

    /// Whether sync group keys from Android have been successfully imported.
    /// Distinct from `isInitialized` which only checks for local device keys.
    var hasSyncGroupKeys: Bool {
        // Check Keychain first (new), fall back to UserDefaults (legacy)
        if let data = loadFromKeychain(tag: "e2ee_sync_group_imported"), !data.isEmpty {
            return true
        }
        return UserDefaults.standard.bool(forKey: "e2ee_sync_group_imported")
    }

    /// Clear sync group keys so they can be re-negotiated with Android.
    /// Used by the "Repair Encryption" flow after reinstalling.
    func clearSyncGroupKeys() {
        deleteFromKeychain(tag: "e2ee_sync_group_imported")
        UserDefaults.standard.removeObject(forKey: "e2ee_sync_group_imported")

        // Also clear the sync group private/public keys themselves
        deleteFromKeychain(tag: privateKeyTag)
        deleteFromKeychain(tag: publicKeyTag)
        privateKey = nil
        publicKey = nil

        #if DEBUG
        print("[E2EE] Cleared sync group keys for encryption repair")
        #endif
    }

    /// Normalize raw private key data to 32 bytes when possible.
    private func normalizeRawPrivateKey(_ data: Data, target: Int = 32) -> Data? {
        if data.count == target {
            return data
        }
        if data.count == target + 1, data.first == 0x00 {
            return data.suffix(target)
        }
        return nil
    }

    /// Parse PKCS#8 DER and extract the inner EC private key (32 bytes for P-256).
    ///
    /// PKCS#8 DER structure for an EC key:
    /// ```
    /// SEQUENCE {                          -- outer PKCS#8 wrapper
    ///   INTEGER (version = 0)
    ///   SEQUENCE { OID, OID }             -- algorithm identifier (ecPublicKey + P-256)
    ///   OCTET STRING {                    -- contains the SEC1 ECPrivateKey
    ///     SEQUENCE {                      -- SEC1 ECPrivateKey
    ///       INTEGER (version = 1)
    ///       OCTET STRING (32 bytes)       -- raw private key scalar <-- this is what we extract
    ///       [0] OID (curve, optional)
    ///       [1] BIT STRING (public key, optional)
    ///     }
    ///   }
    /// }
    /// ```
    /// The method walks through TLV (Tag-Length-Value) triplets to reach the inner
    /// OCTET STRING containing the 32-byte P-256 private key scalar.
    private func extractRawPrivateKeyFromPkcs8(_ pkcs8: Data) -> Data? {
        var reader = DERReader(data: pkcs8)
        guard let pkcs8Seq = reader.readTLV(expectedTag: 0x30) else { return nil }

        var pkcs8Reader = DERReader(data: pkcs8Seq.value)
        _ = pkcs8Reader.readTLV(expectedTag: 0x02) // version
        _ = pkcs8Reader.readTLV(expectedTag: 0x30) // algorithm

        guard let privateKeyOctet = pkcs8Reader.readTLV(expectedTag: 0x04) else { return nil }
        let inner = privateKeyOctet.value

        if let normalized = normalizeRawPrivateKey(inner) {
            return normalized
        }

        // ECPrivateKey (SEC1) is wrapped in this OCTET STRING.
        var ecReader = DERReader(data: inner)
        guard let ecSeq = ecReader.readTLV(expectedTag: 0x30) else { return nil }

        var ecSeqReader = DERReader(data: ecSeq.value)
        _ = ecSeqReader.readTLV(expectedTag: 0x02) // version
        guard let ecKeyOctet = ecSeqReader.readTLV(expectedTag: 0x04) else { return nil }

        return normalizeRawPrivateKey(ecKeyOctet.value)
    }

    /// Minimal DER (Distinguished Encoding Rules) reader for PKCS#8 parsing.
    ///
    /// DER encodes data as TLV (Tag-Length-Value) triplets:
    /// - Tag: 1 byte identifying the type (0x30=SEQUENCE, 0x02=INTEGER, 0x04=OCTET STRING)
    /// - Length: 1 byte if < 128, otherwise multi-byte (high bit set, remaining bits = byte count)
    /// - Value: the raw payload bytes
    ///
    /// This reader only supports the subset needed for PKCS#8 EC key extraction.
    private struct DERReader {
        let data: Data
        var index: Int = 0

        mutating func readTLV(expectedTag: UInt8? = nil) -> (tag: UInt8, value: Data)? {
            guard let tag = readByte() else { return nil }
            if let expectedTag, tag != expectedTag {
                return nil
            }
            guard let length = readLength() else { return nil }
            guard index + length <= data.count else { return nil }
            let value = data[index..<index + length]
            index += length
            return (tag, Data(value))
        }

        private mutating func readByte() -> UInt8? {
            guard index < data.count else { return nil }
            let byte = data[index]
            index += 1
            return byte
        }

        private mutating func readLength() -> Int? {
            guard let first = readByte() else { return nil }
            if (first & 0x80) == 0 {
                return Int(first)
            }
            let count = Int(first & 0x7F)
            if count == 0 || index + count > data.count {
                return nil
            }
            var value = 0
            for _ in 0..<count {
                value = (value << 8) | Int(data[index])
                index += 1
            }
            return value
        }
    }

    // MARK: - ECDSA Signing Keys

    /// Load existing signing keys from Keychain
    private func loadSigningKeys() {
        guard let privateKeyData = loadFromKeychain(tag: signingPrivateKeyTag) else {
            return
        }

        do {
            signingPrivateKey = try P256.Signing.PrivateKey(rawRepresentation: privateKeyData)
            signingPublicKey = signingPrivateKey?.publicKey
        } catch {
            #if DEBUG
            print("[E2EE] Error loading signing keys: \(error)")
            #endif
            deleteFromKeychain(tag: signingPrivateKeyTag)
            deleteFromKeychain(tag: signingPublicKeyTag)
        }
    }

    /// Ensure ECDSA P-256 signing keys exist (generate if not).
    func ensureSigningKeys() {
        keyLock.lock()
        defer { keyLock.unlock() }

        if signingPrivateKey != nil { return }

        let newPrivateKey = P256.Signing.PrivateKey()
        let newPublicKey = newPrivateKey.publicKey

        do {
            try storeInKeychain(data: newPrivateKey.rawRepresentation, tag: signingPrivateKeyTag)
            try storeInKeychain(data: newPublicKey.rawRepresentation, tag: signingPublicKeyTag)
            signingPrivateKey = newPrivateKey
            signingPublicKey = newPublicKey
            #if DEBUG
            print("[E2EE] ECDSA signing keys initialized")
            #endif
        } catch {
            #if DEBUG
            print("[E2EE] Error initializing signing keys: \(error)")
            #endif
        }
    }

    /// Get the signing public key in X9.63 format (Base64 encoded).
    func getSigningPublicKeyX963Base64() -> String? {
        keyLock.lock()
        defer { keyLock.unlock() }
        return signingPublicKey?.x963Representation.base64EncodedString()
    }

    /// Verify an ECDSA signature on data using the provided signing public key.
    func verifySignature(data: Data, signature: Data, signingPublicKey: P256.Signing.PublicKey) -> Bool {
        guard let ecdsaSignature = try? P256.Signing.ECDSASignature(derRepresentation: signature) else {
            return false
        }
        return signingPublicKey.isValidSignature(ecdsaSignature, for: data)
    }

    /// Import a sync group keypair with a specific key version (for key rotation support).
    func importSyncGroupKeypair(privateKeyPKCS8Base64: String, publicKeyX963Base64: String, keyVersion: Int) throws {
        try importSyncGroupKeypair(privateKeyPKCS8Base64: privateKeyPKCS8Base64, publicKeyX963Base64: publicKeyX963Base64)

        // Store versioned key
        keyLock.lock()
        defer { keyLock.unlock() }

        if let key = privateKey {
            keyVersions[keyVersion] = key
            try storeInKeychain(data: key.rawRepresentation, tag: "e2ee_private_key_v\(keyVersion)")
            currentKeyVersion = keyVersion
        }
    }

    /// Decrypt a data key using a specific key version, falling back to all stored versions.
    func decryptDataKeyWithVersion(from envelope: String, keyVersion: Int? = nil) throws -> Data {
        // Try current key first
        if let result = try? decryptDataKey(from: envelope) {
            return result
        }

        // Try specific version
        if let version = keyVersion, let key = keyVersions[version] ?? loadVersionedKey(version: version) {
            if let result = try? decryptDataKeyWithPrivateKey(from: envelope, privateKey: key) {
                return result
            }
        }

        // Iterate all stored versions
        for v in stride(from: currentKeyVersion, through: 1, by: -1) {
            if let key = keyVersions[v] ?? loadVersionedKey(version: v),
               let result = try? decryptDataKeyWithPrivateKey(from: envelope, privateKey: key) {
                return result
            }
        }

        throw E2EEError.decryptionFailed
    }

    private func loadVersionedKey(version: Int) -> P256.KeyAgreement.PrivateKey? {
        guard let data = loadFromKeychain(tag: "e2ee_private_key_v\(version)") else { return nil }
        return try? P256.KeyAgreement.PrivateKey(rawRepresentation: data)
    }

    private func decryptDataKeyWithPrivateKey(from envelope: String, privateKey: P256.KeyAgreement.PrivateKey) throws -> Data {
        let payload = envelope.replacingOccurrences(of: "v2:", with: "")
        guard let bytes = Data(base64Encoded: payload), bytes.count > 65 + 12 + 16 else {
            throw E2EEError.invalidCiphertext
        }

        let ephemeralPublicKeyData = bytes.prefix(65)
        let nonce = bytes.dropFirst(65).prefix(12)
        let ciphertextAndTag = bytes.dropFirst(77)

        let ephemeralPublicKey = try P256.KeyAgreement.PublicKey(x963Representation: ephemeralPublicKeyData)
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: ephemeralPublicKey)

        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfoV2,
            outputByteCount: 32
        )

        let sealedBox = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonce),
            ciphertext: ciphertextAndTag.dropLast(16),
            tag: ciphertextAndTag.suffix(16)
        )

        return try AES.GCM.open(sealedBox, using: symmetricKey)
    }

    /// Derive a safety number from the sync group public key.
    /// SHA-256(publicKeyX963) → first 30 bytes → 12 groups of 5 digits.
    func deriveSafetyNumber() -> String? {
        keyLock.lock()
        let key = publicKey
        keyLock.unlock()

        guard let key = key else { return nil }
        let hash = SHA256.hash(data: key.x963Representation)
        let bytes = Array(hash.prefix(30))

        var groups: [String] = []
        for i in stride(from: 0, to: 30, by: 5) {
            let chunk = bytes[i..<min(i + 5, bytes.count)]
            // Each 5 bytes → 5-digit number (mod 100000)
            var value: UInt64 = 0
            for byte in chunk {
                value = (value << 8) | UInt64(byte)
            }
            groups.append(String(format: "%05d", value % 100000))
        }
        return groups.joined(separator: " ")
    }

    /// Encrypt arbitrary data for a device public key using E2EE v2 envelope format
    func encryptDataKeyForDevice(publicKeyX963Base64: String, data: Data) throws -> String {
        guard let privateKey = privateKey else {
            throw E2EEError.notInitialized
        }

        guard let publicKeyData = Data(base64Encoded: publicKeyX963Base64) else {
            throw E2EEError.invalidPublicKey
        }

        let recipientPublicKey = try P256.KeyAgreement.PublicKey(x963Representation: publicKeyData)

        let ephemeralPrivateKey = P256.KeyAgreement.PrivateKey()
        let sharedSecret = try ephemeralPrivateKey.sharedSecretFromKeyAgreement(with: recipientPublicKey)
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfoV2,
            outputByteCount: 32
        )

        let sealedBox = try AES.GCM.seal(data, using: symmetricKey)
        guard let combined = sealedBox.combined else {
            throw E2EEError.encryptionFailed
        }

        let nonceSize = 12
        let nonce = combined.prefix(nonceSize)
        let ciphertextAndTag = combined.dropFirst(nonceSize)

        let ephemeralPublicKey = ephemeralPrivateKey.publicKey.x963Representation

        var output = Data()
        output.append(ephemeralPublicKey)
        output.append(nonce)
        output.append(ciphertextAndTag)

        return "v2:" + output.base64EncodedString()
    }

    // MARK: - Binary Data Encryption/Decryption (for MMS attachments)

    /// Encrypt binary data (for MMS attachments)
    func encryptData(_ data: Data, for recipientUid: String) async throws -> Data {
        guard let privateKey = privateKey,
              let publicKey = publicKey else {
            throw E2EEError.notInitialized
        }

        guard let recipientPublicKey = try await getPublicKey(for: recipientUid) else {
            throw E2EEError.recipientKeyNotFound
        }

        // Perform ECDH key agreement
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: recipientPublicKey)

        // Derive symmetric key using HKDF
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfo,
            outputByteCount: 32
        )

        // Encrypt with AES-GCM
        let sealedBox = try AES.GCM.seal(data, using: symmetricKey)

        guard let combined = sealedBox.combined else {
            throw E2EEError.encryptionFailed
        }

        // Include our public key in the output for the recipient to decrypt
        var output = Data()
        output.append(publicKey.rawRepresentation)  // 32 bytes
        output.append(combined)

        return output
    }

    /// Decrypt binary data (for MMS attachments)
    /// Supports both CryptoKit format and Android Tink ECIES format
    func decryptData(_ encryptedData: Data) throws -> Data {
        guard let privateKey = privateKey else {
            throw E2EEError.notInitialized
        }

        // Try Tink ECIES format first (Android)
        if encryptedData.count > 70 {
            do {
                return try decryptTinkData(encryptedData)
            } catch {
                #if DEBUG
                print("[E2EE] Tink data decryption failed, trying CryptoKit format: \(error)")
                #endif
            }
        }

        // Fall back to CryptoKit format
        guard encryptedData.count > 32 else {
            throw E2EEError.invalidCiphertext
        }

        let senderPublicKeyData = encryptedData.prefix(32)
        let ciphertext = encryptedData.dropFirst(32)

        let senderPublicKey = try P256.KeyAgreement.PublicKey(rawRepresentation: senderPublicKeyData)

        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: senderPublicKey)

        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfo,
            outputByteCount: 32
        )

        let sealedBox = try AES.GCM.SealedBox(combined: ciphertext)
        return try AES.GCM.open(sealedBox, using: symmetricKey)
    }

    /// Decrypt binary data encrypted with Android's Tink ECIES
    private func decryptTinkData(_ encryptedData: Data) throws -> Data {
        guard let privateKey = privateKey else {
            throw E2EEError.notInitialized
        }

        guard encryptedData.count > 4 + 1 + 65 + 12 + 16 else {
            throw E2EEError.invalidCiphertext
        }

        var offset = 4  // Skip key ID prefix

        let pubKeyLength = Int(encryptedData[offset])
        offset += 1

        guard pubKeyLength == 65 else {
            throw E2EEError.invalidPublicKey
        }

        let ephemeralPublicKeyData = encryptedData[offset..<(offset + 65)]
        offset += 65

        let ephemeralPublicKey = try P256.KeyAgreement.PublicKey(x963Representation: ephemeralPublicKeyData)

        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: ephemeralPublicKey)

        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfo,
            outputByteCount: 16  // AES-128 as per Tink template
        )

        let nonce = encryptedData[offset..<(offset + 12)]
        offset += 12

        let ciphertextAndTag = encryptedData[offset...]

        let sealedBox = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonce),
            ciphertext: ciphertextAndTag.dropLast(16),
            tag: ciphertextAndTag.suffix(16)
        )

        return try AES.GCM.open(sealedBox, using: symmetricKey)
    }

    // MARK: - Key Backup & Recovery

    /// Restore sync group keys from an encrypted backup.
    /// Decrypts the backup using the user's passphrase and imports all key versions.
    func restoreFromBackup(passphrase: String, backups: [[String: Any]]) throws {
        for backup in backups {
            guard let encryptedBackupBase64 = backup["encryptedBackup"] as? String,
                  let saltBase64 = backup["salt"] as? String,
                  let iterations = backup["iterations"] as? Int,
                  let keyVersion = backup["keyVersion"] as? Int,
                  let combined = Data(base64Encoded: encryptedBackupBase64),
                  let salt = Data(base64Encoded: saltBase64) else {
                continue
            }

            guard combined.count > 12 else { continue }

            // Derive AES-256 key from passphrase using PBKDF2
            let passphraseBytes = Array(passphrase.utf8)
            var derivedKeyBytes = [UInt8](repeating: 0, count: 32)
            let result = salt.withUnsafeBytes { saltPtr in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    passphraseBytes,
                    passphraseBytes.count,
                    saltPtr.bindMemory(to: UInt8.self).baseAddress!,
                    salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    UInt32(iterations),
                    &derivedKeyBytes,
                    32
                )
            }

            guard result == kCCSuccess else {
                throw E2EEError.decryptionFailed
            }
            let derivedKey = Data(derivedKeyBytes)

            // Decrypt: first 12 bytes = nonce, rest = ciphertext + tag
            let nonce = combined.prefix(12)
            let ciphertextAndTag = combined.dropFirst(12)

            let symmetricKey = SymmetricKey(data: derivedKey)
            let sealedBox = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonce),
                ciphertext: ciphertextAndTag.dropLast(16),
                tag: ciphertextAndTag.suffix(16)
            )

            let decryptedData = try AES.GCM.open(sealedBox, using: symmetricKey)

            // Parse the decrypted JSON
            guard let json = try JSONSerialization.jsonObject(with: decryptedData) as? [String: Any],
                  let privateKeyPKCS8 = json["privateKeyPKCS8"] as? String else {
                continue
            }

            let publicKeyX963 = json["publicKeyX963"] as? String

            // Import the key
            if let publicKeyX963 = publicKeyX963 {
                try importSyncGroupKeypair(
                    privateKeyPKCS8Base64: privateKeyPKCS8,
                    publicKeyX963Base64: publicKeyX963,
                    keyVersion: keyVersion
                )
            }

            #if DEBUG
            print("[E2EE] Restored key backup version \(keyVersion)")
            #endif
        }
    }

    /// Check if a key backup exists on the server.
    var hasKeyBackup: Bool {
        // This would need to be checked asynchronously via VPSService
        // For now, return false and let the caller check via API
        return false
    }

    /// Clear all E2EE keys (for logout/reset)
    func clearKeys() {
        deleteFromKeychain(tag: privateKeyTag)
        deleteFromKeychain(tag: publicKeyTag)
        privateKey = nil
        publicKey = nil
    }
}

// MARK: - E2EE Errors

enum E2EEError: LocalizedError {
    case notInitialized
    case notAuthenticated
    case keychainError(OSStatus)
    case invalidPublicKey
    case invalidPrivateKey
    case keyMismatch
    case recipientKeyNotFound
    case encodingError
    case decodingError
    case encryptionFailed
    case decryptionFailed
    case invalidCiphertext

    var errorDescription: String? {
        switch self {
        case .notInitialized:
            return "E2EE not initialized"
        case .notAuthenticated:
            return "Not authenticated"
        case .keychainError(let status):
            return "Keychain error: \(status)"
        case .invalidPublicKey:
            return "Invalid public key format"
        case .invalidPrivateKey:
            return "Invalid private key format"
        case .keyMismatch:
            return "Public key does not match private key"
        case .recipientKeyNotFound:
            return "Recipient's public key not found"
        case .encodingError:
            return "Message encoding error"
        case .decodingError:
            return "Message decoding error"
        case .encryptionFailed:
            return "Encryption failed"
        case .decryptionFailed:
            return "Decryption failed"
        case .invalidCiphertext:
            return "Invalid ciphertext"
        }
    }
}
