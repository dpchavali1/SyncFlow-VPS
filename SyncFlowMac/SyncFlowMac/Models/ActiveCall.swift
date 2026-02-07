//
//  ActiveCall.swift
//  SyncFlowMac
//
//  Model for active/incoming calls
//

import Foundation

struct ActiveCall: Identifiable, Hashable {
    let id: String
    let phoneNumber: String
    let contactName: String?
    let callState: CallState
    let timestamp: Date

    enum CallState: String {
        case ringing = "ringing"
        case active = "active"
        case ended = "ended"

        var displayName: String {
            switch self {
            case .ringing: return "Incoming..."
            case .active: return "Active"
            case .ended: return "Ended"
            }
        }
    }

    var displayName: String {
        contactName ?? phoneNumber
    }

    var formattedPhoneNumber: String {
        // Format phone number for display
        let cleaned = phoneNumber.components(separatedBy: CharacterSet.decimalDigits.inverted).joined()

        if cleaned.count == 10 {
            let areaCode = String(cleaned.prefix(3))
            let prefix = String(cleaned.dropFirst(3).prefix(3))
            let suffix = String(cleaned.suffix(4))
            return "(\(areaCode)) \(prefix)-\(suffix)"
        } else if cleaned.count == 11 && cleaned.hasPrefix("1") {
            let areaCode = String(cleaned.dropFirst().prefix(3))
            let prefix = String(cleaned.dropFirst(4).prefix(3))
            let suffix = String(cleaned.suffix(4))
            return "+1 (\(areaCode)) \(prefix)-\(suffix)"
        }

        return phoneNumber
    }

    /// Create ActiveCall from Firebase data
    static func from(_ data: [String: Any], id: String) -> ActiveCall? {
        guard let phoneNumber = data["phoneNumber"] as? String,
              // Support both "state" (new) and "callState" (legacy) field names
              let callStateString = (data["state"] as? String) ?? (data["callState"] as? String),
              let callState = CallState(rawValue: callStateString) else {
            return nil
        }

        let contactName = data["contactName"] as? String
        let timestampValue = data["timestamp"] as? Double ?? Date().timeIntervalSince1970 * 1000
        let timestamp = Date(timeIntervalSince1970: timestampValue / 1000)

        return ActiveCall(
            id: id,
            phoneNumber: phoneNumber,
            contactName: contactName?.isEmpty == false ? contactName : nil,
            callState: callState,
            timestamp: timestamp
        )
    }
}
