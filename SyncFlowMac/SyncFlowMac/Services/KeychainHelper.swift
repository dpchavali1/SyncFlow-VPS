import Foundation
import Security

class KeychainHelper {

    static let shared = KeychainHelper()

    private let service = "com.syncflow.mac"

    private init() {}

    func save(key: String, data: Data) -> OSStatus {
        let query = [
            kSecClass as String: kSecClassGenericPassword as String,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock as String,
            kSecValueData as String: data
        ] as [String: Any]

        SecItemDelete(query as CFDictionary)

        return SecItemAdd(query as CFDictionary, nil)
    }

    func load(key: String) -> Data? {
        let query = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: kCFBooleanTrue!,
            kSecMatchLimit as String: kSecMatchLimitOne
        ] as [String: Any]

        var dataTypeRef: AnyObject?
        let status: OSStatus = SecItemCopyMatching(query as CFDictionary, &dataTypeRef)

        if status == noErr {
            return dataTypeRef as? Data
        } else {
            // Fallback: try loading without service attribute (migration from old format)
            let legacyQuery = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrAccount as String: key,
                kSecReturnData as String: kCFBooleanTrue!,
                kSecMatchLimit as String: kSecMatchLimitOne
            ] as [String: Any]

            let legacyStatus = SecItemCopyMatching(legacyQuery as CFDictionary, &dataTypeRef)
            if legacyStatus == noErr, let data = dataTypeRef as? Data {
                // Migrate to new format
                _ = save(key: key, data: data)
                SecItemDelete(legacyQuery as CFDictionary)
                return data
            }
            return nil
        }
    }

    func delete(key: String) -> OSStatus {
        let query = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ] as [String: Any]

        return SecItemDelete(query as CFDictionary)
    }
}
