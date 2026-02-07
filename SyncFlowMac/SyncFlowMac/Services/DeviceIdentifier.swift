//
//  DeviceIdentifier.swift
//  SyncFlowMac
//
//  Persistent device identification that survives app reinstalls.
//  Uses hardware UUID stored in Keychain for maximum persistence.
//

import Foundation
import IOKit
import Security
import CryptoKit

/**
 * DeviceIdentifier provides a stable, persistent device ID for this Mac.
 *
 * Strategy:
 * 1. First check Keychain for existing device ID (survives reinstalls)
 * 2. If not found, generate from hardware UUID (IOPlatformUUID)
 * 3. Store in Keychain for persistence
 * 4. Format: "mac_{16-char-hash}" for privacy
 */
class DeviceIdentifier {

    static let shared = DeviceIdentifier()

    private let keychainService = "com.syncflow.device"
    private let keychainAccount = "device_id"

    private var cachedDeviceId: String?

    private init() {}

    // MARK: - Public API

    /**
     * Get the persistent device ID for this Mac.
     * This ID survives app reinstalls and updates.
     */
    func getDeviceId() -> String {
        // Return cached value if available
        if let cached = cachedDeviceId {
            return cached
        }

        // Try to get from Keychain first
        if let keychainId = getFromKeychain() {
            cachedDeviceId = keychainId
            return keychainId
        }

        // Generate new ID from hardware UUID
        let newId = generateDeviceId()

        // Store in Keychain for persistence
        saveToKeychain(newId)
        cachedDeviceId = newId

        return newId
    }

    /**
     * Get the raw hardware UUID (for debugging only)
     */
    func getHardwareUUID() -> String? {
        return getRawHardwareUUID()
    }

    /**
     * Clear cached device ID (for testing)
     */
    func clearCache() {
        cachedDeviceId = nil
    }

    /**
     * Delete device ID from Keychain (for complete reset)
     */
    func resetDeviceId() {
        deleteFromKeychain()
        cachedDeviceId = nil
    }

    // MARK: - Private Methods

    /**
     * Generate device ID from hardware UUID
     */
    private func generateDeviceId() -> String {
        guard let hardwareUUID = getRawHardwareUUID() else {
            // Fallback to random UUID if hardware UUID unavailable
            let fallbackId = "mac_\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16))"
            print("[DeviceIdentifier] Warning: Using random UUID fallback")
            return fallbackId
        }

        // Hash the hardware UUID for privacy
        let hashedId = hashString(hardwareUUID)
        return "mac_\(hashedId.prefix(16))"
    }

    /**
     * Get the Mac's hardware UUID (IOPlatformUUID)
     */
    private func getRawHardwareUUID() -> String? {
        let platformExpert = IOServiceGetMatchingService(
            kIOMainPortDefault,
            IOServiceMatching("IOPlatformExpertDevice")
        )

        defer {
            IOObjectRelease(platformExpert)
        }

        guard platformExpert != 0 else {
            print("[DeviceIdentifier] Failed to get IOPlatformExpertDevice")
            return nil
        }

        guard let serialNumberAsCFString = IORegistryEntryCreateCFProperty(
            platformExpert,
            kIOPlatformUUIDKey as CFString,
            kCFAllocatorDefault,
            0
        )?.takeUnretainedValue() as? String else {
            print("[DeviceIdentifier] Failed to read IOPlatformUUID")
            return nil
        }

        return serialNumberAsCFString.replacingOccurrences(of: "-", with: "")
    }

    /**
     * Hash a string using SHA-256
     */
    private func hashString(_ input: String) -> String {
        let inputData = Data(input.utf8)
        let hashed = SHA256.hash(data: inputData)
        return hashed.compactMap { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Keychain Operations

    /**
     * Get device ID from Keychain
     */
    private func getFromKeychain() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess,
              let data = result as? Data,
              let deviceId = String(data: data, encoding: .utf8) else {
            if status != errSecItemNotFound {
                print("[DeviceIdentifier] Keychain read error: \(status)")
            }
            return nil
        }

        return deviceId
    }

    /**
     * Save device ID to Keychain
     */
    private func saveToKeychain(_ deviceId: String) {
        guard let data = deviceId.data(using: .utf8) else {
            print("[DeviceIdentifier] Failed to encode device ID")
            return
        }

        // First, try to update existing item
        let updateQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount
        ]

        let updateAttributes: [String: Any] = [
            kSecValueData as String: data
        ]

        var status = SecItemUpdate(updateQuery as CFDictionary, updateAttributes as CFDictionary)

        if status == errSecItemNotFound {
            // Item doesn't exist, add new
            let addQuery: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: keychainService,
                kSecAttrAccount as String: keychainAccount,
                kSecValueData as String: data,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            ]

            status = SecItemAdd(addQuery as CFDictionary, nil)
        }

        if status != errSecSuccess {
            print("[DeviceIdentifier] Keychain write error: \(status)")
        } else {
        }
    }

    /**
     * Delete device ID from Keychain
     */
    private func deleteFromKeychain() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount
        ]

        let status = SecItemDelete(query as CFDictionary)

        if status != errSecSuccess && status != errSecItemNotFound {
            print("[DeviceIdentifier] Keychain delete error: \(status)")
        }
    }
}

// MARK: - Extension for FirebaseService Integration

extension DeviceIdentifier {

    /**
     * Get device info dictionary for pairing requests
     */
    func getDeviceInfo() -> [String: Any] {
        return [
            "id": getDeviceId(),
            "name": Host.current().localizedName ?? "Mac",
            "type": "macos",
            "model": getMacModel(),
            "osVersion": ProcessInfo.processInfo.operatingSystemVersionString
        ]
    }

    /**
     * Get Mac model identifier
     */
    private func getMacModel() -> String {
        var size = 0
        sysctlbyname("hw.model", nil, &size, nil, 0)

        var model = [CChar](repeating: 0, count: size)
        sysctlbyname("hw.model", &model, &size, nil, 0)

        return String(cString: model)
    }
}
