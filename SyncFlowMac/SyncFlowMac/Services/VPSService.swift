/**
 * VPS Service - Replacement for FirebaseService
 *
 * This service handles all communication with the VPS server instead of Firebase.
 * It provides similar functionality to FirebaseService but uses REST API WebSocket.
 */

import Foundation
import Combine

// MARK: - VPS Models

public struct VPSTokenPair: Codable {
    let accessToken: String
    let refreshToken: String
}

public struct VPSUser: Codable {
    let userId: String
    let deviceId: String
    let admin: Bool?
}

public struct VPSAuthResponse: Codable {
    let userId: String
    let deviceId: String
    let accessToken: String
    let refreshToken: String
}

public struct VPSPairingRequest: Codable {
    let pairingToken: String
    let deviceId: String
    let tempUserId: String
    let accessToken: String
    let refreshToken: String
}

public struct VPSPairingStatus: Codable {
    let status: String
    let deviceName: String?
    let approved: Bool
}

public struct VPSDeviceE2eeKey: Codable {
    let encryptedKey: String
    let createdAt: Int64?
    let updatedAt: Int64?
}

public struct VPSMessage: Codable, Identifiable {
    public let id: String
    let threadId: Int?
    let address: String
    let contactName: String?
    let body: String?
    let date: Int64
    let type: Int
    let read: Bool
    let isMms: Bool
    let encrypted: Bool?
    let encryptedBody: String?
    let encryptedNonce: String?
    let keyMap: [String: String]?
    let mmsParts: [[String: Any]]?

    enum CodingKeys: String, CodingKey {
        case id, threadId, address, contactName, body, date, type, read, isMms, encrypted, encryptedBody, encryptedNonce, keyMap, mmsParts
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        threadId = try container.decodeIfPresent(Int.self, forKey: .threadId)
        address = try container.decode(String.self, forKey: .address)
        contactName = try container.decodeIfPresent(String.self, forKey: .contactName)
        body = try container.decodeIfPresent(String.self, forKey: .body)
        date = try container.decode(Int64.self, forKey: .date)
        type = try container.decode(Int.self, forKey: .type)
        read = try container.decodeIfPresent(Bool.self, forKey: .read) ?? true
        isMms = try container.decodeIfPresent(Bool.self, forKey: .isMms) ?? false
        encrypted = try container.decodeIfPresent(Bool.self, forKey: .encrypted)
        encryptedBody = try container.decodeIfPresent(String.self, forKey: .encryptedBody)
        encryptedNonce = try container.decodeIfPresent(String.self, forKey: .encryptedNonce)
        if let map = try? container.decodeIfPresent([String: String].self, forKey: .keyMap) {
            keyMap = map
        } else if let jsonString = try? container.decodeIfPresent(String.self, forKey: .keyMap),
                  let jsonData = jsonString.data(using: .utf8),
                  let parsed = try? JSONSerialization.jsonObject(with: jsonData) as? [String: String] {
            keyMap = parsed
        } else {
            keyMap = nil
        }

        // Decode mmsParts from JSON - may be array of dicts, JSON string, or nested JSON
        if let partsData = try? container.decodeIfPresent([[String: AnyCodableValue]].self, forKey: .mmsParts) {
            mmsParts = partsData.map { dict in
                var result: [String: Any] = [:]
                for (key, value) in dict {
                    result[key] = value.anyValue
                }
                return result
            }
        } else if let jsonString = try? container.decodeIfPresent(String.self, forKey: .mmsParts),
                  let jsonData = jsonString.data(using: .utf8),
                  let parsed = try? JSONSerialization.jsonObject(with: jsonData) as? [[String: Any]] {
            mmsParts = parsed
        } else if let anyValue = try? container.decodeIfPresent(AnyCodableValue.self, forKey: .mmsParts) {
            mmsParts = VPSMessage.extractMmsParts(from: anyValue)
        } else {
            mmsParts = nil
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encodeIfPresent(threadId, forKey: .threadId)
        try container.encode(address, forKey: .address)
        try container.encodeIfPresent(contactName, forKey: .contactName)
        try container.encodeIfPresent(body, forKey: .body)
        try container.encode(date, forKey: .date)
        try container.encode(type, forKey: .type)
        try container.encode(read, forKey: .read)
        try container.encode(isMms, forKey: .isMms)
        try container.encodeIfPresent(encrypted, forKey: .encrypted)
        try container.encodeIfPresent(encryptedBody, forKey: .encryptedBody)
        try container.encodeIfPresent(encryptedNonce, forKey: .encryptedNonce)
        try container.encodeIfPresent(keyMap, forKey: .keyMap)
    }

    private static func extractMmsParts(from value: AnyCodableValue) -> [[String: Any]]? {
        switch value {
        case .array(let items):
            let parts = items.compactMap { item -> [String: Any]? in
                if case .dictionary(let dict) = item {
                    return dict.mapValues { $0.anyValue }
                }
                return nil
            }
            return parts.isEmpty ? nil : parts
        case .dictionary(let dict):
            if let partsValue = dict["parts"] {
                return extractMmsParts(from: partsValue)
            }
            if let partsValue = dict["attachments"] {
                return extractMmsParts(from: partsValue)
            }
            return nil
        case .string(let jsonString):
            if let jsonData = jsonString.data(using: .utf8),
               let parsed = try? JSONSerialization.jsonObject(with: jsonData) as? [[String: Any]] {
                return parsed
            }
            return nil
        default:
            return nil
        }
    }
}

public struct VPSMessagesResponse: Codable {
    let messages: [VPSMessage]
    let hasMore: Bool
}

public struct VPSContact: Codable, Identifiable {
    public let id: String
    let displayName: String?
    let phoneNumbers: [String]?
    let emails: [String]?
    let photoThumbnail: String?
}

public struct VPSContactsResponse: Codable {
    let contacts: [VPSContact]
}

public struct VPSCallHistoryEntry: Codable, Identifiable {
    public let id: String
    let phoneNumber: String
    let contactName: String?
    let callType: String
    let callDate: Int64
    let duration: Int
}

public struct VPSCallsResponse: Codable {
    let calls: [VPSCallHistoryEntry]
    let hasMore: Bool
}

public struct VPSDevice: Codable, Identifiable {
    public let id: String
    let name: String?
    let deviceType: String
    let pairedAt: String?
    let lastSeen: String?
    let isCurrent: Bool
}

public struct VPSDevicesResponse: Codable {
    let devices: [VPSDevice]
}

public struct VPSSyncResponse: Codable {
    let synced: Int
    let skipped: Int
    let total: Int?
}

// MARK: - Spam Models

public struct VPSSpamMessage: Codable, Identifiable {
    public let id: String
    let address: String
    let body: String?
    let date: Int64
    let spamScore: Float?
    let spamReason: String?
}

public struct VPSSpamMessagesResponse: Codable {
    let messages: [VPSSpamMessage]
}

public struct VPSWhitelistEntry: Codable {
    let phoneNumber: String
    let addedAt: Int64?
}

public struct VPSWhitelistResponse: Codable {
    let whitelist: [VPSWhitelistEntry]
}

public struct VPSBlocklistEntry: Codable {
    let phoneNumber: String
    let addedAt: Int64?
}

public struct VPSBlocklistResponse: Codable {
    let blocklist: [VPSBlocklistEntry]
}

// MARK: - File Transfer Response

public struct VPSFileTransfer: Codable {
    let id: String
    let fileName: String
    let fileSize: Int64
    let contentType: String
    let r2Key: String?
    let downloadUrl: String?
    let source: String
    let status: String
    let timestamp: Double?
}

public struct VPSFileTransfersResponse: Codable {
    let transfers: [VPSFileTransfer]
}

public struct VPSUploadUrlResponse: Codable {
    let uploadUrl: String
    let fileKey: String
}

private struct VPSSuccessResponse: Codable {
    let success: Bool?
    let id: Int?
}

public struct VPSUsageInfo: Codable {
    let plan: String
}

public struct VPSUsageResponse: Codable {
    let usage: VPSUsageInfo
}

// MARK: - Generic Response

struct VPSGenericResponse: Codable {
    let success: Bool?
    let id: String?
    let deleted: Int?

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        success = try container.decodeIfPresent(Bool.self, forKey: .success)
        id = try container.decodeIfPresent(String.self, forKey: .id)
        deleted = try container.decodeIfPresent(Int.self, forKey: .deleted)
    }

    enum CodingKeys: String, CodingKey {
        case success, id, deleted
    }
}

// MARK: - AnyCodableValue (for decoding heterogeneous JSON)

enum AnyCodableValue: Codable {
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case array([AnyCodableValue])
    case dictionary([String: AnyCodableValue])
    case null

    var anyValue: Any {
        switch self {
        case .string(let v): return v
        case .int(let v): return v
        case .double(let v): return v
        case .bool(let v): return v
        case .array(let v): return v.map { $0.anyValue }
        case .dictionary(let v): return v.mapValues { $0.anyValue }
        case .null: return NSNull()
        }
    }

    init(from decoder: Decoder) throws {
        if let keyed = try? decoder.container(keyedBy: DynamicCodingKey.self) {
            var dict: [String: AnyCodableValue] = [:]
            for key in keyed.allKeys {
                if let value = try? keyed.decode(AnyCodableValue.self, forKey: key) {
                    dict[key.stringValue] = value
                }
            }
            self = .dictionary(dict)
            return
        }

        let unkeyed = try? decoder.unkeyedContainer()
        if var unkeyed = unkeyed {
            var values: [AnyCodableValue] = []
            while !unkeyed.isAtEnd {
                if let value = try? unkeyed.decode(AnyCodableValue.self) {
                    values.append(value)
                } else {
                    _ = try? unkeyed.decode(EmptyCodable.self)
                }
            }
            self = .array(values)
            return
        }

        let container = try decoder.singleValueContainer()
        if let v = try? container.decode(String.self) { self = .string(v) }
        else if let v = try? container.decode(Int.self) { self = .int(v) }
        else if let v = try? container.decode(Double.self) { self = .double(v) }
        else if let v = try? container.decode(Bool.self) { self = .bool(v) }
        else if container.decodeNil() { self = .null }
        else { self = .null }
    }

    func encode(to encoder: Encoder) throws {
        switch self {
        case .dictionary(let dict):
            var container = encoder.container(keyedBy: DynamicCodingKey.self)
            for (key, value) in dict {
                try container.encode(value, forKey: DynamicCodingKey(stringValue: key)!)
            }
        case .array(let values):
            var container = encoder.unkeyedContainer()
            for value in values {
                try container.encode(value)
            }
        case .string(let v):
            var container = encoder.singleValueContainer()
            try container.encode(v)
        case .int(let v):
            var container = encoder.singleValueContainer()
            try container.encode(v)
        case .double(let v):
            var container = encoder.singleValueContainer()
            try container.encode(v)
        case .bool(let v):
            var container = encoder.singleValueContainer()
            try container.encode(v)
        case .null:
            var container = encoder.singleValueContainer()
            try container.encodeNil()
        }
    }
}

private struct DynamicCodingKey: CodingKey {
    let stringValue: String
    let intValue: Int?

    init?(stringValue: String) {
        self.stringValue = stringValue
        self.intValue = nil
    }

    init?(intValue: Int) {
        self.intValue = intValue
        self.stringValue = "\(intValue)"
    }
}

private struct EmptyCodable: Codable {}

// MARK: - VPS Error

public enum VPSError: Error, LocalizedError {
    case notAuthenticated
    case invalidResponse
    case httpError(Int, String?)
    case networkError(Error)
    case pairingExpired
    case pairingNotApproved

    public var errorDescription: String? {
        switch self {
        case .notAuthenticated:
            return "Not authenticated. Please pair with your phone first."
        case .invalidResponse:
            return "Invalid response from server"
        case .httpError(let code, let message):
            return "HTTP Error \(code): \(message ?? "Unknown error")"
        case .networkError(let error):
            return "Network error: \(error.localizedDescription)"
        case .pairingExpired:
            return "Pairing request expired. Please try again."
        case .pairingNotApproved:
            return "Pairing not approved by phone."
        }
    }
}

// MARK: - VPS Service

public class VPSService: NSObject, ObservableObject {

    public static let shared = VPSService()

    // Configuration
    private let baseUrl: String
    private let wsUrl: String
    private let wsUrlFallback: String?
    private var activeWsUrl: String
    private var didFallbackToInsecureWebSocket = false
    private let session: URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    // Keychain keys
    private let keychainAccessToken = "com.syncflow.vps.accessToken"
    private let keychainRefreshToken = "com.syncflow.vps.refreshToken"
    private let keychainUserId = "com.syncflow.vps.userId"
    private let keychainDeviceId = "com.syncflow.vps.deviceId"

    // State
    private var accessToken: String?
    private var refreshToken: String?
    @Published public private(set) var userId: String?
    @Published public private(set) var deviceId: String?
    @Published public private(set) var isAuthenticated: Bool = false
    @Published public private(set) var isConnected: Bool = false

    // WebSocket
    private var webSocketTask: URLSessionWebSocketTask?
    private var subscriptions: Set<String> = []
    private var reconnectTimer: Timer?
    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 5

    // Publishers for real-time updates
    public let messageAdded = PassthroughSubject<VPSMessage, Never>()
    public let messageUpdated = PassthroughSubject<VPSMessage, Never>()
    public let messageDeleted = PassthroughSubject<String, Never>()
    public let contactAdded = PassthroughSubject<VPSContact, Never>()
    public let contactUpdated = PassthroughSubject<VPSContact, Never>()
    public let contactDeleted = PassthroughSubject<String, Never>()
    public let callAdded = PassthroughSubject<VPSCallHistoryEntry, Never>()
    public let deviceRemoved = PassthroughSubject<String, Never>()

    /// Set to true when server pushes e2ee_key_available for this device
    public var e2eeKeyPushReceived = false

    private override init() {
        // Default to VPS server
        self.baseUrl = ProcessInfo.processInfo.environment["SYNCFLOW_VPS_URL"] ?? "https://api.sfweb.app"
        if let override = ProcessInfo.processInfo.environment["SYNCFLOW_VPS_WS_URL"], !override.isEmpty {
            self.wsUrl = override
        } else {
            // WebSocket runs on the same port as HTTP, through nginx reverse proxy
            self.wsUrl = self.baseUrl.replacingOccurrences(of: "https://", with: "wss://")
                                     .replacingOccurrences(of: "http://", with: "ws://")
        }
        if wsUrl.hasPrefix("wss://") {
            self.wsUrlFallback = wsUrl.replacingOccurrences(of: "wss://", with: "ws://")
        } else {
            self.wsUrlFallback = nil
        }
        self.activeWsUrl = wsUrl
        self.session = URLSession.shared

        super.init()

        // Restore tokens from Keychain
        restoreTokens()
    }

    // MARK: - Token Management

    private func restoreTokens() {
        accessToken = loadFromKeychain(keychainAccessToken)
        refreshToken = loadFromKeychain(keychainRefreshToken)
        userId = loadFromKeychain(keychainUserId)
        deviceId = loadFromKeychain(keychainDeviceId)

        isAuthenticated = accessToken != nil && userId != nil

        if isAuthenticated {
            print("[VPS] Restored authentication for user \(userId ?? "unknown")")
        }
    }

    private func saveTokens(accessToken: String, refreshToken: String, userId: String, deviceId: String) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.userId = userId
        self.deviceId = deviceId
        self.isAuthenticated = true

        saveToKeychain(keychainAccessToken, value: accessToken)
        saveToKeychain(keychainRefreshToken, value: refreshToken)
        saveToKeychain(keychainUserId, value: userId)
        saveToKeychain(keychainDeviceId, value: deviceId)
    }

    public func clearTokens() {
        accessToken = nil
        refreshToken = nil
        userId = nil
        deviceId = nil
        isAuthenticated = false

        deleteFromKeychain(keychainAccessToken)
        deleteFromKeychain(keychainRefreshToken)
        deleteFromKeychain(keychainUserId)
        deleteFromKeychain(keychainDeviceId)

        disconnectWebSocket()
    }

    private func loadFromKeychain(_ key: String) -> String? {
        guard let data = KeychainHelper.shared.load(key: key),
              let string = String(data: data, encoding: .utf8) else {
            return nil
        }
        return string
    }

    private func saveToKeychain(_ key: String, value: String) {
        guard let data = value.data(using: .utf8) else { return }
        _ = KeychainHelper.shared.save(key: key, data: data)
    }

    private func deleteFromKeychain(_ key: String) {
        _ = KeychainHelper.shared.delete(key: key)
    }

    // MARK: - HTTP Helpers

    private func request<T: Decodable>(
        _ method: String,
        _ path: String,
        body: [String: Any]? = nil,
        skipAuth: Bool = false,
        retryCount: Int = 0
    ) async throws -> T {
        guard let url = URL(string: baseUrl + path) else {
            throw VPSError.invalidResponse
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if !skipAuth {
            guard let token = accessToken else {
                throw VPSError.notAuthenticated
            }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if let body = body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }

        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw VPSError.invalidResponse
        }

        if httpResponse.statusCode == 401 && !skipAuth {
            // Try to refresh token
            try await refreshAccessToken()
            return try await self.request(method, path, body: body, skipAuth: false, retryCount: retryCount + 1)
        }

        if httpResponse.statusCode == 429 {
            if retryCount >= 3 {
                let errorMessage = String(data: data, encoding: .utf8)
                throw VPSError.httpError(httpResponse.statusCode, errorMessage)
            }

            var retryAfterSeconds: Double = 1
            if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let retryAfter = json["retryAfter"] as? Double {
                retryAfterSeconds = max(0.5, retryAfter)
            } else if let retryHeader = httpResponse.value(forHTTPHeaderField: "Retry-After"),
                      let retry = Double(retryHeader) {
                retryAfterSeconds = max(0.5, retry)
            }

            print("[VPS] Rate limited (429). Retrying in \(retryAfterSeconds)s (attempt \(retryCount + 1))")
            try await Task.sleep(nanoseconds: UInt64(retryAfterSeconds * 1_000_000_000))
            return try await self.request(method, path, body: body, skipAuth: skipAuth, retryCount: retryCount + 1)
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            let errorMessage = String(data: data, encoding: .utf8)
            throw VPSError.httpError(httpResponse.statusCode, errorMessage)
        }

        return try decoder.decode(T.self, from: data)
    }

    private func get<T: Decodable>(_ path: String) async throws -> T {
        return try await request("GET", path)
    }

    private func post<T: Decodable>(_ path: String, body: [String: Any], skipAuth: Bool = false) async throws -> T {
        return try await request("POST", path, body: body, skipAuth: skipAuth)
    }

    private func put<T: Decodable>(_ path: String, body: [String: Any]?) async throws -> T {
        return try await request("PUT", path, body: body)
    }

    private func delete<T: Decodable>(_ path: String) async throws -> T {
        return try await request("DELETE", path)
    }

    // MARK: - Authentication

    public func initiatePairing(deviceName: String) async throws -> VPSPairingRequest {
        let body: [String: Any] = [
            "deviceName": deviceName,
            "deviceType": "macos"
        ]

        let response: VPSPairingRequest = try await post("/api/auth/pair/initiate", body: body, skipAuth: true)

        // Save temporary tokens
        saveTokens(
            accessToken: response.accessToken,
            refreshToken: response.refreshToken,
            userId: response.tempUserId,
            deviceId: response.deviceId
        )

        print("[VPS] Pairing initiated with token \(response.pairingToken)")
        return response
    }

    public func checkPairingStatus(token: String) async throws -> VPSPairingStatus {
        guard let url = URL(string: "\(baseUrl)/api/auth/pair/status/\(token)") else {
            throw VPSError.invalidResponse
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw VPSError.invalidResponse
        }

        if httpResponse.statusCode == 404 {
            throw VPSError.pairingExpired
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            throw VPSError.httpError(httpResponse.statusCode, nil)
        }

        return try decoder.decode(VPSPairingStatus.self, from: data)
    }

    public func redeemPairing(token: String) async throws -> VPSUser {
        // Include tempUserId so server can clean up the orphaned anonymous user
        var body: [String: Any] = [
            "token": token,
            "deviceName": Host.current().localizedName ?? "Mac",
            "deviceType": "macos"
        ]
        if let currentUserId = userId {
            body["tempUserId"] = currentUserId
        }

        let response: VPSAuthResponse = try await post("/api/auth/pair/redeem", body: body, skipAuth: true)

        saveTokens(
            accessToken: response.accessToken,
            refreshToken: response.refreshToken,
            userId: response.userId,
            deviceId: response.deviceId
        )

        print("[VPS] Pairing redeemed, userId=\(response.userId)")

        // Connect WebSocket after successful pairing
        connectWebSocket()

        return VPSUser(userId: response.userId, deviceId: response.deviceId, admin: false)
    }

    public func refreshAccessToken() async throws {
        guard let token = refreshToken else {
            throw VPSError.notAuthenticated
        }

        let body = ["refreshToken": token]

        struct RefreshResponse: Codable {
            let accessToken: String
        }

        let response: RefreshResponse = try await post("/api/auth/refresh", body: body, skipAuth: true)

        accessToken = response.accessToken
        if let data = response.accessToken.data(using: .utf8) {
            _ = KeychainHelper.shared.save(key: keychainAccessToken, data: data)
        }
    }

    public func getCurrentUser() async throws -> VPSUser {
        return try await get("/api/auth/me")
    }

    // MARK: - E2EE Keys (VPS)

    public func getDeviceE2eeKeys(userId: String) async throws -> [String: VPSDeviceE2eeKey] {
        return try await get("/api/e2ee/device-keys/\(userId)")
    }

    /// Publish this device's public E2EE key to VPS
    public func publishE2EEPublicKey(publicKey: String) async throws {
        let body: [String: Any] = [
            "publicKey": publicKey,
            "keyType": "ecdh_p256",
            "version": 2
        ]
        let _: VPSSuccessResponse = try await post("/api/e2ee/public-key", body: body)
    }

    /// Request E2EE key sync from a target device (e.g., Android)
    /// This creates a key request that notifies the target via WebSocket
    public func requestE2EEKeySync(targetDevice: String) async throws {
        let body: [String: Any] = ["targetDevice": targetDevice]
        let _: VPSSuccessResponse = try await post("/api/e2ee/key-request", body: body)
        print("[VPS] E2EE key request sent to device: \(targetDevice)")
    }

    public func waitForDeviceE2eeKey(timeout: TimeInterval = 60, pollInterval: TimeInterval = 3, initialDelay: TimeInterval = 1) async throws -> String? {
        guard let userId = userId, let deviceId = deviceId else {
            print("[VPS E2EE] Cannot poll - userId=\(userId ?? "nil"), deviceId=\(self.deviceId ?? "nil")")
            return nil
        }
        let deadline = Date().addingTimeInterval(timeout)
        e2eeKeyPushReceived = false
        print("[VPS E2EE] Polling for key (deviceId=\(deviceId), timeout=\(Int(timeout))s)")

        // Short initial delay, but skip if WebSocket already pushed key notification
        if initialDelay > 0 && !e2eeKeyPushReceived {
            // Check every 200ms during initial delay so we can skip early on push
            let delayEnd = Date().addingTimeInterval(initialDelay)
            while Date() < delayEnd && !e2eeKeyPushReceived {
                try await Task.sleep(nanoseconds: 200_000_000) // 200ms
            }
            if e2eeKeyPushReceived {
                print("[VPS E2EE] Key push received during initial delay, polling immediately")
            }
        }

        var pollCount = 0
        while Date() < deadline {
            pollCount += 1
            do {
                let keys = try await getDeviceE2eeKeys(userId: userId)
                if pollCount == 1 || pollCount % 5 == 0 {
                    print("[VPS E2EE] Poll #\(pollCount): got \(keys.count) keys, looking for deviceId=\(deviceId)")
                    if !keys.isEmpty {
                        print("[VPS E2EE]   Available deviceIds: \(keys.keys.sorted())")
                    }
                }
                if let key = keys[deviceId]?.encryptedKey, !key.isEmpty {
                    print("[VPS E2EE] Key found after \(pollCount) polls (key length=\(key.count))")
                    return key
                }
            } catch {
                print("[VPS E2EE] Poll #\(pollCount) error: \(error)")
                let errorDesc = "\(error)"
                if errorDesc.contains("429") || errorDesc.contains("Too many requests") {
                    try await Task.sleep(nanoseconds: UInt64(10 * 1_000_000_000))
                    continue
                }
                // Log detailed error for debugging
                if let decodingError = error as? DecodingError {
                    print("[VPS E2EE] Decoding error detail: \(decodingError)")
                }
            }
            // If push arrived, poll immediately instead of waiting
            if e2eeKeyPushReceived {
                e2eeKeyPushReceived = false
                continue
            }
            // Sleep in short increments so we wake up quickly on push
            let sleepEnd = Date().addingTimeInterval(pollInterval)
            while Date() < sleepEnd && !e2eeKeyPushReceived {
                try await Task.sleep(nanoseconds: 200_000_000) // 200ms
            }
        }

        print("[VPS E2EE] Timed out after \(pollCount) polls")
        return nil
    }

    // MARK: - Messages

    public func getMessages(limit: Int = 100, before: Int64? = nil, threadId: Int? = nil) async throws -> VPSMessagesResponse {
        var path = "/api/messages?limit=\(limit)"
        if let before = before {
            path += "&before=\(before)"
        }
        if let threadId = threadId {
            path += "&threadId=\(threadId)"
        }
        return try await get(path)
    }

    public func sendMessage(address: String, body: String, simSubscriptionId: Int? = nil) async throws {
        var requestBody: [String: Any] = [
            "address": address,
            "body": body
        ]
        if let simId = simSubscriptionId {
            requestBody["simSubscriptionId"] = simId
        }

        let _: [String: String] = try await post("/api/messages/send", body: requestBody)
    }

    public func markMessageRead(messageId: String) async throws {
        let _: [String: Bool] = try await put("/api/messages/\(messageId)/read", body: nil)
    }

    // MARK: - Contacts

    public func getContacts(search: String? = nil, limit: Int = 500) async throws -> VPSContactsResponse {
        var path = "/api/contacts?limit=\(limit)"
        if let search = search {
            path += "&search=\(search.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? search)"
        }
        return try await get(path)
    }

    // MARK: - Call History

    public func getCallHistory(limit: Int = 100, before: Int64? = nil) async throws -> VPSCallsResponse {
        var path = "/api/calls?limit=\(limit)"
        if let before = before {
            path += "&before=\(before)"
        }
        return try await get(path)
    }

    public func requestCall(phoneNumber: String, simSubscriptionId: Int? = nil) async throws {
        var body: [String: Any] = ["phoneNumber": phoneNumber]
        if let simId = simSubscriptionId {
            body["simSubscriptionId"] = simId
        }
        let _: [String: String] = try await post("/api/calls/request", body: body)
    }

    // MARK: - Devices

    public func getDevices() async throws -> VPSDevicesResponse {
        return try await get("/api/devices")
    }

    public func removeDevice(deviceId: String) async throws {
        let _: [String: Bool] = try await delete("/api/devices/\(deviceId)")
    }

    // MARK: - Spam

    public func getSpamMessages(limit: Int = 100) async throws -> VPSSpamMessagesResponse {
        return try await get("/api/spam/messages?limit=\(limit)")
    }

    public func syncSpamMessage(address: String, body: String, date: Double, spamScore: Double, spamReason: String) async throws {
        let payload: [String: Any] = [
            "address": address,
            "body": body,
            "date": Int64(date),
            "spamScore": spamScore,
            "spamReason": spamReason
        ]
        let _: VPSGenericResponse = try await post("/api/spam/messages", body: payload)
    }

    public func deleteSpamMessage(messageId: String) async throws {
        let _: VPSGenericResponse = try await delete("/api/spam/messages/\(messageId)")
    }

    public func clearAllSpamMessages() async throws {
        let _: VPSGenericResponse = try await delete("/api/spam/messages")
    }

    public func getWhitelist() async throws -> VPSWhitelistResponse {
        return try await get("/api/spam/whitelist")
    }

    public func addToWhitelist(phoneNumber: String) async throws {
        let _: VPSGenericResponse = try await post("/api/spam/whitelist", body: ["phoneNumber": phoneNumber])
    }

    public func removeFromWhitelist(phoneNumber: String) async throws {
        let _: VPSGenericResponse = try await delete("/api/spam/whitelist/\(phoneNumber)")
    }

    public func getBlocklist() async throws -> VPSBlocklistResponse {
        return try await get("/api/spam/blocklist")
    }

    public func addToBlocklist(phoneNumber: String) async throws {
        let _: VPSGenericResponse = try await post("/api/spam/blocklist", body: ["phoneNumber": phoneNumber])
    }

    public func removeFromBlocklist(phoneNumber: String) async throws {
        let _: VPSGenericResponse = try await delete("/api/spam/blocklist/\(phoneNumber)")
    }

    // MARK: - Usage

    public func getUsage() async throws -> VPSUsageResponse {
        return try await get("/api/usage")
    }

    // MARK: - File Transfers

    public func getDownloadUrl(r2Key: String) async throws -> String {
        return try await getFileDownloadUrl(fileKey: r2Key)
    }

    public func getFileDownloadUrl(fileKey: String) async throws -> String {
        let response: [String: String] = try await post("/api/file-transfers/download-url", body: ["fileKey": fileKey])
        guard let url = response["downloadUrl"] else {
            throw VPSError.invalidResponse
        }
        return url
    }

    public func getFileTransfers(limit: Int = 50) async throws -> VPSFileTransfersResponse {
        return try await get("/api/file-transfers?limit=\(limit)")
    }

    public func getFileUploadUrl(fileName: String, contentType: String, fileSize: Int64) async throws -> VPSUploadUrlResponse {
        let body: [String: Any] = [
            "fileName": fileName,
            "contentType": contentType,
            "fileSize": fileSize,
            "transferType": "files"
        ]
        return try await post("/api/file-transfers/upload-url", body: body)
    }

    public func confirmFileUpload(fileKey: String, fileSize: Int64) async throws {
        let body: [String: Any] = [
            "fileKey": fileKey,
            "fileSize": fileSize,
            "transferType": "files"
        ]
        let _: VPSGenericResponse = try await post("/api/file-transfers/confirm-upload", body: body)
    }

    public func createFileTransfer(id: String, fileName: String, fileSize: Int64, contentType: String, r2Key: String, source: String) async throws {
        let body: [String: Any] = [
            "id": id,
            "fileName": fileName,
            "fileSize": fileSize,
            "contentType": contentType,
            "r2Key": r2Key,
            "source": source,
            "status": "pending"
        ]
        let _: VPSGenericResponse = try await post("/api/file-transfers", body: body)
    }

    public func updateFileTransferStatus(id: String, status: String, error: String? = nil) async throws {
        var body: [String: Any] = ["status": status]
        if let error = error {
            body["error"] = error
        }
        let _: VPSGenericResponse = try await put("/api/file-transfers/\(id)/status", body: body)
    }

    public func deleteFileTransfer(id: String) async throws {
        let _: VPSGenericResponse = try await delete("/api/file-transfers/\(id)")
    }

    public func deleteR2File(fileKey: String) async throws {
        let _: VPSGenericResponse = try await post("/api/file-transfers/delete-file", body: ["fileKey": fileKey])
    }

    // MARK: - WebSocket

    public func connectWebSocket() {
        guard let token = accessToken else {
            print("[VPS Warning] Cannot connect WebSocket - not authenticated")
            return
        }

        guard let url = URL(string: "\(activeWsUrl)?token=\(token)") else {
            print("[VPS Error] Invalid WebSocket URL")
            return
        }

        webSocketTask?.cancel()
        webSocketTask = session.webSocketTask(with: url)
        webSocketTask?.resume()

        isConnected = true
        reconnectAttempts = 0
        print("[VPS] WebSocket connected")

        // Start receiving messages
        receiveWebSocketMessage()

        // Subscribe to user's data
        if let userId = userId {
            subscribeToUser(userId)
        }
    }

    public func disconnectWebSocket() {
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        isConnected = false
        subscriptions.removeAll()
        reconnectTimer?.invalidate()
        print("[VPS] WebSocket disconnected")
    }

    private func receiveWebSocketMessage() {
        webSocketTask?.receive { [weak self] result in
            guard let self = self else { return }

            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleWebSocketMessage(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.handleWebSocketMessage(text)
                    }
                @unknown default:
                    break
                }
                // Continue receiving
                self.receiveWebSocketMessage()

            case .failure(let error):
                print("[VPS Error] WebSocket error: \(error.localizedDescription)")
                self.isConnected = false
                if self.maybeFallbackToInsecureWebSocket(error: error) {
                    self.connectWebSocket()
                } else {
                    self.scheduleReconnect()
                }
            }
        }
    }

    private func handleWebSocketMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else {
            return
        }

        switch type {
        case "message_added":
            if let messageData = json["data"] as? [String: Any],
               let jsonData = try? JSONSerialization.data(withJSONObject: messageData),
               let message = try? decoder.decode(VPSMessage.self, from: jsonData) {
                DispatchQueue.main.async {
                    self.messageAdded.send(message)
                }
            }

        case "message_updated":
            if let messageData = json["data"] as? [String: Any],
               let jsonData = try? JSONSerialization.data(withJSONObject: messageData),
               let message = try? decoder.decode(VPSMessage.self, from: jsonData) {
                DispatchQueue.main.async {
                    self.messageUpdated.send(message)
                }
            }

        case "message_deleted":
            if let messageId = json["messageId"] as? String {
                DispatchQueue.main.async {
                    self.messageDeleted.send(messageId)
                }
            }

        case "contact_added":
            if let contactData = json["data"] as? [String: Any],
               let jsonData = try? JSONSerialization.data(withJSONObject: contactData),
               let contact = try? decoder.decode(VPSContact.self, from: jsonData) {
                DispatchQueue.main.async {
                    self.contactAdded.send(contact)
                }
            }

        case "call_added":
            if let callData = json["data"] as? [String: Any],
               let jsonData = try? JSONSerialization.data(withJSONObject: callData),
               let call = try? decoder.decode(VPSCallHistoryEntry.self, from: jsonData) {
                DispatchQueue.main.async {
                    self.callAdded.send(call)
                }
            }

        case "messages_synced":
            // Handle batch of synced messages from Android
            if let data = json["data"] as? [String: Any],
               let messagesArray = data["messages"] as? [[String: Any]] {
                for messageData in messagesArray {
                    if let jsonData = try? JSONSerialization.data(withJSONObject: messageData),
                       let message = try? decoder.decode(VPSMessage.self, from: jsonData) {
                        DispatchQueue.main.async {
                            self.messageAdded.send(message)
                        }
                    }
                }
                // Batch message count logged at summary level only
            }

        case "e2ee_key_available":
            if let data = json["data"] as? [String: Any],
               let targetDeviceId = data["deviceId"] as? String,
               targetDeviceId == self.deviceId {
                print("[VPS E2EE] Key push notification received for this device")
                self.e2eeKeyPushReceived = true
            }

        case "device_removed":
            if let data = json["data"] as? [String: Any] {
                let removedDeviceId = (data["id"] as? String) ?? (data["deviceId"] as? String) ?? ""
                if removedDeviceId == self.deviceId || removedDeviceId.isEmpty {
                    print("[VPS] Remote unpair detected")
                    DispatchQueue.main.async {
                        self.deviceRemoved.send(removedDeviceId)
                    }
                }
            } else {
                print("[VPS] Remote unpair detected")
                DispatchQueue.main.async {
                    self.deviceRemoved.send("")
                }
            }

        case "pong":
            // Heartbeat response
            break

        default:
            break // Unknown message type - ignore silently
        }
    }

    private func subscribeToUser(_ userId: String) {
        // Subscribe to each channel individually (server expects single channel per message)
        let channels = ["messages", "contacts", "calls", "devices"]

        for channel in channels {
            let subscription: [String: Any] = [
                "type": "subscribe",
                "channel": channel
            ]

            if let data = try? JSONSerialization.data(withJSONObject: subscription),
               let text = String(data: data, encoding: .utf8) {
                webSocketTask?.send(.string(text)) { error in
                    if let error = error {
                        print("[VPS Error] Failed to subscribe to \(channel): \(error.localizedDescription)")
                    } else {
                        self.subscriptions.insert(channel)
                        print("[VPS] Subscribed to \(channel) channel")
                    }
                }
            }
        }
    }

    private func scheduleReconnect() {
        reconnectAttempts += 1
        // Exponential backoff: 2, 4, 8, 16, 30, 30, 30... seconds (capped at 30s)
        let delay = min(Double(1 << min(reconnectAttempts, 5)), 30.0)

        if reconnectAttempts <= 3 || reconnectAttempts % 10 == 0 {
            print("[VPS] WebSocket reconnect in \(Int(delay))s (attempt \(reconnectAttempts))")
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.connectWebSocket()
        }
    }

    private func maybeFallbackToInsecureWebSocket(error: Error) -> Bool {
        guard !didFallbackToInsecureWebSocket, let fallback = wsUrlFallback else {
            return false
        }

        let nsError = error as NSError
        let urlError = URLError.Code(rawValue: nsError.code)
        let shouldFallback: Bool

        switch urlError {
        case .secureConnectionFailed,
             .serverCertificateUntrusted,
             .serverCertificateHasBadDate,
             .serverCertificateNotYetValid,
             .serverCertificateHasUnknownRoot,
             .clientCertificateRejected,
             .clientCertificateRequired:
            shouldFallback = true
        default:
            shouldFallback = false
        }

        guard shouldFallback else { return false }
        didFallbackToInsecureWebSocket = true
        activeWsUrl = fallback
        print("[VPS] WebSocket TLS failed; falling back to insecure WebSocket: \(fallback)")
        return true
    }

    // MARK: - Pairing QR Code

    public func generatePairingQRData() async throws -> (token: String, qrData: String) {
        let deviceName = Host.current().localizedName ?? "Mac"
        let pairing = try await initiatePairing(deviceName: deviceName)

        // Get macOS E2EE public key to include in QR code
        // This allows Android to encrypt the sync group keys for macOS
        // Build QR data with URLComponents to ensure proper encoding (+ / =)
        var components = URLComponents()
        components.scheme = "syncflow"
        components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "token", value: pairing.pairingToken),
            URLQueryItem(name: "server", value: baseUrl),
            URLQueryItem(name: "deviceId", value: pairing.deviceId)
        ]

        // Include E2EE public key if available
        try? await E2EEManager.shared.initializeKeys()
        if let publicKeyX963 = E2EEManager.shared.getMyPublicKeyX963Base64() {
            components.queryItems?.append(URLQueryItem(name: "e2eeKey", value: publicKeyX963))
            print("[VPS] Including E2EE public key in QR code")
        } else {
            print("[VPS] Warning: No E2EE public key available for QR code")
        }

        let qrData = components.url?.absoluteString
            ?? "syncflow://pair?token=\(pairing.pairingToken)&server=\(baseUrl)&deviceId=\(pairing.deviceId)"

        return (pairing.pairingToken, qrData)
    }

    public func waitForPairingApproval(token: String, timeout: TimeInterval = 300) async throws -> VPSUser {
        let startTime = Date()

        while Date().timeIntervalSince(startTime) < timeout {
            do {
                let status = try await checkPairingStatus(token: token)

                if status.approved {
                    // Pairing approved, redeem it
                    return try await redeemPairing(token: token)
                }

                if status.status == "expired" {
                    throw VPSError.pairingExpired
                }

                // Wait before checking again
                try await Task.sleep(nanoseconds: 2_000_000_000) // 2 seconds

            } catch VPSError.pairingExpired {
                throw VPSError.pairingExpired
            } catch {
                // Continue polling on other errors
                try await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }

        throw VPSError.pairingExpired
    }
}
