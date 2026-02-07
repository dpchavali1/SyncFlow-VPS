//
//  GroupMessagingModels.swift
//  SyncFlowMac
//
//  Simple data models for Group Messaging (friends groups)
//

import Foundation

// MARK: - Contact Group

struct ContactGroup: Identifiable, Hashable {
    let id: String
    let name: String
    let createdAt: Double
    var contactCount: Int
    var contacts: [GroupContact]

    var formattedCreatedDate: String {
        let date = Date(timeIntervalSince1970: createdAt / 1000)
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter.string(from: date)
    }

    static func from(_ data: [String: Any], id: String) -> ContactGroup? {
        guard let name = data["name"] as? String else { return nil }

        let createdAt = (data["created_at"] as? Double) ?? (data["createdAt"] as? Double) ?? 0

        // Handle different numeric types from Firebase
        let contactCount: Int
        if let count = data["contact_count"] as? Int {
            contactCount = count
        } else if let count = data["contact_count"] as? Int64 {
            contactCount = Int(count)
        } else if let count = data["contact_count"] as? Double {
            contactCount = Int(count)
        } else if let count = data["contact_count"] as? NSNumber {
            contactCount = count.intValue
        } else if let contacts = data["contacts"] as? [String: Any] {
            contactCount = contacts.count
        } else {
            contactCount = 0
        }

        return ContactGroup(
            id: id,
            name: name,
            createdAt: createdAt,
            contactCount: contactCount,
            contacts: []
        )
    }
}

// MARK: - Group Contact

struct GroupContact: Identifiable, Hashable, Codable {
    let id: String
    let name: String
    let phone: String
    let addedAt: Double

    var formattedPhone: String {
        let digits = phone.filter { $0.isNumber }
        if digits.count == 10 {
            let areaCode = digits.prefix(3)
            let middle = digits.dropFirst(3).prefix(3)
            let last = digits.suffix(4)
            return "(\(areaCode)) \(middle)-\(last)"
        } else if digits.count == 11 && digits.first == "1" {
            let withoutCountry = String(digits.dropFirst())
            let areaCode = withoutCountry.prefix(3)
            let middle = withoutCountry.dropFirst(3).prefix(3)
            let last = withoutCountry.suffix(4)
            return "+1 (\(areaCode)) \(middle)-\(last)"
        }
        return phone
    }

    static func from(_ data: [String: Any], id: String) -> GroupContact? {
        guard let name = data["name"] as? String,
              let phone = data["phone"] as? String else { return nil }

        let addedAt = (data["added_at"] as? Double) ?? (data["addedAt"] as? Double) ?? 0

        return GroupContact(
            id: id,
            name: name,
            phone: phone,
            addedAt: addedAt
        )
    }
}
