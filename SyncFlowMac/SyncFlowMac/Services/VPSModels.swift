/**
 * VPSModels - Shared data models for VPS communication
 *
 * Contains all Codable structs, enums, and helper types used across the
 * VPSService and its extensions. These models represent the JSON payloads
 * exchanged with the SyncFlow VPS REST API and WebSocket events.
 */

import Foundation

// MARK: - Shared Models

/// SIM card information from Android device
struct SimInfo: Identifiable {
    let subscriptionId: Int
    let slotIndex: Int
    let displayName: String
    let carrierName: String
    let phoneNumber: String?
    let isEmbedded: Bool
    let isActive: Bool

    var id: Int { subscriptionId }

    var formattedDisplayName: String {
        if !carrierName.isEmpty {
            return "\(displayName) (\(carrierName))"
        }
        return displayName
    }
}

/// Status of a phone call request
enum CallRequestStatus {
    case completed
    case failed(error: String)

    var description: String {
        switch self {
        case .completed:
            return "Call initiated successfully"
        case .failed(let error):
            return "Call failed: \(error)"
        }
    }
}

/// Pairing session data for QR code pairing flow
struct PairingSession {
    let token: String
    let qrPayload: String
    let expiresAt: Double // ms since epoch
    let version: Int

    var timeRemaining: TimeInterval {
        let expiresDate = Date(timeIntervalSince1970: expiresAt / 1000)
        return max(0, expiresDate.timeIntervalSince(Date()))
    }
}

/// Pairing approval status
enum PairingStatus {
    case pending
    case approved(pairedUid: String, deviceId: String?)
    case rejected
    case expired
}

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
    let deliveryStatus: String?

    enum CodingKeys: String, CodingKey {
        case id, threadId, address, contactName, body, date, type, read, isMms, encrypted, encryptedBody, encryptedNonce, keyMap, mmsParts, deliveryStatus
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
        if !container.contains(.mmsParts) || (try? container.decodeNil(forKey: .mmsParts)) == true {
            // Key missing or explicitly null — expected for SMS messages
            mmsParts = nil
        } else if let partsData = try? container.decodeIfPresent([[String: AnyCodableValue]].self, forKey: .mmsParts) {
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

        deliveryStatus = try container.decodeIfPresent(String.self, forKey: .deliveryStatus)
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
        try container.encodeIfPresent(deliveryStatus, forKey: .deliveryStatus)
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
    let contentType: String?
    let r2Key: String?
    let downloadUrl: String?
    let source: String?
    let status: String?
    let timestamp: Double?
}

public struct VPSFileTransfersResponse: Codable {
    let transfers: [VPSFileTransfer]
}

public struct VPSUploadUrlResponse: Codable {
    let uploadUrl: String
    let fileKey: String
}

struct VPSSuccessResponse: Codable {
    let success: Bool?
    let id: Int?
}

public struct VPSUsageInfo: Codable {
    let plan: String
    let planExpiresAt: Int64?
    let trialStartedAt: Int64?
    let storageBytes: Int64?
    let monthlyUploadBytes: Int64?
    let monthlyMmsBytes: Int64?
    let monthlyFileBytes: Int64?
    let monthlyPhotoBytes: Int64?
    let messageCount: Int64?
    let contactCount: Int64?
    let lastUpdatedAt: Int64?
    let monthlyUploadLimit: Int64?
    let storageLimit: Int64?
    let maxFileSize: Int64?
    let maxDevices: Int?
    let monthlyResetDate: Int64?
}

public struct VPSUsageResponse: Codable {
    let success: Bool?
    let usage: VPSUsageInfo
}

// MARK: - Stripe Billing Response Models

public struct VPSCheckoutResponse: Codable {
    let url: String
}

public struct VPSPortalResponse: Codable {
    let url: String
}

public struct VPSCancelResponse: Codable {
    let success: Bool?
    let message: String?
}

public struct VPSSubscriptionSyncResponse: Codable {
    let synced: Bool
    let message: String?
    let plan: String?
}

public struct VPSSubscriptionStatus: Codable {
    let plan: String
    let status: String
    let startedAt: Int64?
    let expiresAt: Int64?
    let hasStripeCustomer: Bool?
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
