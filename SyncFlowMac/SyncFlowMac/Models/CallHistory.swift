//
//  CallHistory.swift
//  SyncFlowMac
//
//  Model for call history entries synchronized from the connected Android device.
//
//  This file defines the data model for phone call history, including incoming, outgoing,
//  missed, rejected, blocked, and voicemail calls. Call history is synchronized from the
//  Android device via Firebase, allowing users to view their complete call log on macOS.
//
//  ## Firebase Data Structure
//  Call history entries are stored at: `users/{userId}/callHistory/{callId}`
//  The Android app uploads call log entries, and the Mac app observes them in real-time.
//
//  ## Type Mapping
//  Call types follow Android's CallLog.Calls constants:
//  - 1 = Incoming
//  - 2 = Outgoing
//  - 3 = Missed
//  - 4 = Voicemail
//  - 5 = Rejected
//  - 6 = Blocked
//
//  ## Serialization Notes
//  This model does not conform to Codable directly. Instead, it uses a factory method
//  `from(_:id:)` to parse Firebase dictionary data, handling the type flexibility of
//  Firebase's JSON (numbers may be Int, Int64, or Double).
//

import Foundation

/// Represents a single entry in the user's call history, synchronized from Android.
///
/// Call history entries capture phone calls made to or received from the connected Android device.
/// This includes metadata such as call type, duration, and the SIM card used for the call.
///
/// ## Usage
/// Call history is displayed in a dedicated view in the Mac app, allowing users to:
/// - View recent calls with contact names and phone numbers
/// - See call duration and timestamps
/// - Distinguish between incoming, outgoing, missed, and other call types
/// - Initiate return calls or send messages to callers
///
/// ## Relationships
/// - Related to: `Conversation` - A call may be associated with an existing conversation by phone number
/// - Created by: `from(_:id:)` factory method for Firebase data parsing
struct CallHistoryEntry: Identifiable, Hashable {
    /// Unique identifier for this call entry, typically the Firebase key
    let id: String

    /// Phone number of the caller or recipient.
    /// Format may vary (E.164, national, or as displayed by the carrier).
    /// Shows "Unknown" if the number was not available (e.g., blocked caller ID).
    let phoneNumber: String

    /// Contact name from the device's address book, if the number is saved.
    /// Will be nil for numbers not in the user's contacts.
    let contactName: String?

    /// Type/direction of the call (incoming, outgoing, missed, etc.)
    let callType: CallType

    /// When the call occurred, converted from Firebase milliseconds to Swift Date
    let callDate: Date

    /// Duration of the call in seconds. Zero for missed/rejected calls.
    let duration: Int

    /// Pre-formatted duration string from Android (e.g., "2:34" for 2 minutes 34 seconds)
    let formattedDuration: String

    /// Pre-formatted date string from Android for display purposes
    let formattedDate: String

    /// Identifier of the SIM card used for this call (0 or 1 for dual-SIM devices).
    /// Useful for users with multiple phone numbers.
    let simId: Int

    // MARK: - Call Type Enum

    /// Enumeration of possible call types, matching Android's CallLog.Calls constants.
    ///
    /// Each call type has an associated SF Symbol icon and color for UI display.
    enum CallType: String, CaseIterable {
        /// An incoming call that was answered
        case incoming = "Incoming"

        /// An outgoing call initiated by the user
        case outgoing = "Outgoing"

        /// An incoming call that was not answered
        case missed = "Missed"

        /// An incoming call that was explicitly declined by the user
        case rejected = "Rejected"

        /// A call from a blocked number
        case blocked = "Blocked"

        /// A voicemail message left by a caller
        case voicemail = "Voicemail"

        /// Parses a call type from a string value, supporting both text names and numeric codes.
        ///
        /// This handles the various formats that Firebase data may contain:
        /// - Lowercase text: "incoming", "outgoing", etc.
        /// - Android CallLog.Calls numeric constants: "1", "2", "3", etc.
        /// - Raw enum values: "Incoming", "Outgoing", etc.
        ///
        /// - Parameter value: The string to parse
        /// - Returns: The matching CallType, or nil if no match is found
        static func fromString(_ value: String) -> CallType? {
            let lower = value.lowercased()
            switch lower {
            case "incoming", "1": return .incoming
            case "outgoing", "2": return .outgoing
            case "missed", "3": return .missed
            case "rejected", "5": return .rejected
            case "blocked", "6": return .blocked
            case "voicemail", "4": return .voicemail
            default: return CallType(rawValue: value)
            }
        }

        /// SF Symbol name for displaying this call type in the UI.
        /// Icons are chosen to visually indicate the call direction and status.
        var icon: String {
            switch self {
            case .incoming:
                return "phone.arrow.down.left.fill"
            case .outgoing:
                return "phone.arrow.up.right.fill"
            case .missed:
                return "phone.down.fill"
            case .rejected:
                return "phone.down.circle.fill"
            case .blocked:
                return "hand.raised.fill"
            case .voicemail:
                return "voicemail.fill"
            }
        }

        /// Color name for displaying this call type in the UI.
        /// Colors provide quick visual identification of call status:
        /// - Blue: Incoming (neutral/informational)
        /// - Green: Outgoing (action taken)
        /// - Red: Missed (attention needed)
        /// - Orange: Rejected (user action)
        /// - Gray: Blocked (filtered)
        /// - Purple: Voicemail (message waiting)
        var color: String {
            switch self {
            case .incoming:
                return "blue"
            case .outgoing:
                return "green"
            case .missed:
                return "red"
            case .rejected:
                return "orange"
            case .blocked:
                return "gray"
            case .voicemail:
                return "purple"
            }
        }
    }

    // MARK: - Computed Properties

    /// Returns the best available name for display.
    /// Prefers contact name if available, otherwise shows the phone number.
    var displayName: String {
        return contactName ?? phoneNumber
    }

    // MARK: - Factory Method

    /// Creates a CallHistoryEntry from a Firebase dictionary.
    ///
    /// This factory method handles the parsing complexities of Firebase data:
    /// - Numbers may arrive as Int, Int64, or Double depending on their value
    /// - Timestamps may be stored under different field names ("callDate", "date", "timestamp")
    /// - Missing or empty values are handled with sensible defaults
    ///
    /// ## Required Fields
    /// - `phoneNumber`: String (returns nil if missing)
    /// - `callType`: String that can be parsed by `CallType.fromString()` (returns nil if invalid)
    /// - One of `callDate`, `date`, or `timestamp`: Numeric milliseconds since epoch (returns nil if missing)
    ///
    /// ## Optional Fields with Defaults
    /// - `contactName`: String, nil if missing or empty
    /// - `duration`: Numeric, defaults to 0
    /// - `formattedDuration`: String, defaults to "0:00"
    /// - `formattedDate`: String, defaults to ""
    /// - `simId`: Numeric, defaults to 0
    ///
    /// - Parameters:
    ///   - data: Dictionary of key-value pairs from Firebase
    ///   - id: The Firebase key to use as the entry's identifier
    /// - Returns: A CallHistoryEntry if parsing succeeds, or nil if required fields are missing/invalid
    static func from(_ data: [String: Any], id: String) -> CallHistoryEntry? {
        // Validate required fields: phoneNumber and callType
        guard let phoneNumber = data["phoneNumber"] as? String,
              let callTypeString = data["callType"] as? String,
              let callType = CallType.fromString(callTypeString) else {
            return nil
        }

        // Handle callDate - Firebase stores Long as Double
        // Also check for "date" and "timestamp" as alternative field names
        // This flexibility accommodates different data formats from the Android app
        let callDate: Double
        if let dateDouble = data["callDate"] as? Double {
            callDate = dateDouble
        } else if let dateInt = data["callDate"] as? Int {
            callDate = Double(dateInt)
        } else if let dateInt64 = data["callDate"] as? Int64 {
            callDate = Double(dateInt64)
        } else if let dateDouble = data["date"] as? Double {
            callDate = dateDouble
        } else if let dateInt = data["date"] as? Int {
            callDate = Double(dateInt)
        } else if let dateInt64 = data["date"] as? Int64 {
            callDate = Double(dateInt64)
        } else if let timestampDouble = data["timestamp"] as? Double {
            callDate = timestampDouble
        } else if let timestampInt = data["timestamp"] as? Int {
            callDate = Double(timestampInt)
        } else if let timestampInt64 = data["timestamp"] as? Int64 {
            callDate = Double(timestampInt64)
        } else {
            return nil
        }

        // Contact name is optional - may be nil if not in address book
        let contactName = data["contactName"] as? String

        // Handle duration - Firebase stores Long as Double
        // Defaults to 0 for missed/rejected calls or if not provided
        let duration: Int
        if let durationInt = data["duration"] as? Int {
            duration = durationInt
        } else if let durationDouble = data["duration"] as? Double {
            duration = Int(durationDouble)
        } else if let durationInt64 = data["duration"] as? Int64 {
            duration = Int(durationInt64)
        } else {
            duration = 0
        }

        // Pre-formatted strings from Android for consistent display
        let formattedDuration = data["formattedDuration"] as? String ?? "0:00"
        let formattedDate = data["formattedDate"] as? String ?? ""

        // Handle simId - Firebase stores Long as Double
        // Defaults to 0 (primary SIM) if not provided
        let simId: Int
        if let simIdInt = data["simId"] as? Int {
            simId = simIdInt
        } else if let simIdDouble = data["simId"] as? Double {
            simId = Int(simIdDouble)
        } else {
            simId = 0
        }

        return CallHistoryEntry(
            id: id,
            phoneNumber: phoneNumber.isEmpty ? "Unknown" : phoneNumber,
            contactName: contactName?.isEmpty == false ? contactName : nil,
            callType: callType,
            callDate: Date(timeIntervalSince1970: callDate / 1000), // Convert from milliseconds to seconds
            duration: duration,
            formattedDuration: formattedDuration,
            formattedDate: formattedDate,
            simId: simId
        )
    }
}
