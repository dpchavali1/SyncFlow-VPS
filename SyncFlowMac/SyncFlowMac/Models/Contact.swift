//
//  Contact.swift
//  SyncFlowMac
//
//  Contact model for displaying Android and desktop-synced contacts on macOS
//

import Foundation

struct Contact: Identifiable, Hashable {
    struct SyncMetadata: Hashable {
        let lastUpdatedAt: Double
        let lastSyncedAt: Double?
        let lastUpdatedBy: String
        let version: Int
        let pendingAndroidSync: Bool
        let desktopOnly: Bool
    }

    let id: String
    let displayName: String
    let phoneNumber: String?
    let normalizedNumber: String?
    let phoneType: String
    let photoBase64: String?
    let notes: String?
    let email: String?
    let sync: SyncMetadata

    var initials: String {
        let components = displayName.components(separatedBy: " ")
        if components.count >= 2 {
            let first = components.first?.prefix(1).uppercased() ?? ""
            let last = components.last?.prefix(1).uppercased() ?? ""
            return "\(first)\(last)"
        } else {
            return String(displayName.prefix(2)).uppercased()
        }
    }

    var formattedPhoneNumber: String {
        let value = phoneNumber ?? ""
        if value.starts(with: "+") {
            return value
        }
        let digits = value.filter { $0.isNumber }
        if digits.count == 10 {
            let areaCode = digits.prefix(3)
            let middle = digits.dropFirst(3).prefix(3)
            let last = digits.suffix(4)
            return "(\(areaCode)) \(middle)-\(last)"
        }
        return value
    }

    var isPendingSync: Bool {
        sync.pendingAndroidSync && !sync.lastUpdatedBy.lowercased().contains("android")
    }

    static func from(_ data: [String: Any], id: String) -> Contact? {
        guard let displayName = data["displayName"] as? String else {
            return nil
        }

        guard let phoneMap = data["phoneNumbers"] as? [String: [String: Any]],
              let firstEntry = phoneMap.values.first,
              let phoneNumber = firstEntry["number"] as? String else {
            return nil
        }

        let normalizedNumber = firstEntry["normalizedNumber"] as? String
        let phoneType = firstEntry["type"] as? String ?? "Mobile"

        let photoData = data["photo"] as? [String: Any]
        let photoBase64 = photoData?["thumbnailBase64"] as? String

        let notes = data["notes"] as? String

        let emails = data["emails"] as? [String: [String: Any]]
        let email = emails?.values.first?["address"] as? String

        let syncData = data["sync"] as? [String: Any]
        let lastUpdatedAt = (syncData?["lastUpdatedAt"] as? Double) ??
            (syncData?["lastUpdatedAt"] as? Int).map(Double.init) ?? 0
        let lastSyncedAt = (syncData?["lastSyncedAt"] as? Double) ??
            (syncData?["lastSyncedAt"] as? Int).map(Double.init)
        let lastUpdatedBy = syncData?["lastUpdatedBy"] as? String ?? ""

        let versionValue = syncData?["version"]
        let version: Int
        if let intVersion = versionValue as? Int {
            version = intVersion
        } else if let doubleVersion = versionValue as? Double {
            version = Int(doubleVersion)
        } else {
            version = 0
        }

        let pendingAndroidSync = (syncData?["pendingAndroidSync"] as? Bool) ?? false
        let desktopOnly = (syncData?["desktopOnly"] as? Bool) ?? false

        let syncMetadata = SyncMetadata(
            lastUpdatedAt: lastUpdatedAt,
            lastSyncedAt: lastSyncedAt,
            lastUpdatedBy: lastUpdatedBy,
            version: version,
            pendingAndroidSync: pendingAndroidSync,
            desktopOnly: desktopOnly
        )

        return Contact(
            id: id,
            displayName: displayName,
            phoneNumber: phoneNumber,
            normalizedNumber: normalizedNumber,
            phoneType: phoneType,
            photoBase64: photoBase64,
            notes: notes,
            email: email,
            sync: syncMetadata
        )
    }
}
