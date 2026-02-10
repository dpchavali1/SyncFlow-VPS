//
//  Message.swift
//  SyncFlowMac
//
//  Data models for SMS/MMS messages, conversations, and related response structures.
//
//  This file contains the core messaging data models used throughout the SyncFlowMac application:
//  - MmsAttachment: Represents media attachments in MMS messages (images, videos, audio, etc.)
//  - Message: Represents an individual SMS or MMS message
//  - Conversation: Represents a conversation thread with one or more contacts
//  - FirebaseAttachment/FirebaseMessage: Legacy DTOs for deserializing message data
//  - ReadReceipt: Tracks when messages have been read across devices
//
//  These models are synchronized from the companion Android app via VPS server.
//
//  Serialization Notes:
//  - Message and MmsAttachment conform to Codable for JSON serialization
//  - Legacy response models (FirebaseAttachment, FirebaseMessage) handle flexible typing
//    (e.g., integers may come as Double)
//  - Conversation does not conform to Codable as it is constructed from Message data
//

import Foundation

// MARK: - MMS Attachment

/// Represents a multimedia attachment within an MMS message.
///
/// MMS (Multimedia Messaging Service) messages can contain various types of media attachments
/// including images, videos, audio files, vCards (contact cards), and other file types.
/// This model supports both URL-based attachments (stored in cloud storage) and inline
/// Base64-encoded data for smaller files.
///
/// ## Relationships
/// - Parent: `Message` - An MmsAttachment belongs to a single Message
/// - Used by: `FirebaseAttachment.toMmsAttachment()` for conversion from server format
///
/// ## Codable Conformance
/// This struct conforms to `Codable` for JSON serialization. All properties use standard
/// Swift types that have built-in Codable support. Optional fields gracefully handle
/// missing data from the server.
struct MmsAttachment: Identifiable, Codable, Hashable {
    /// Unique identifier for this attachment, typically derived from the Android content provider ID
    let id: String

    /// MIME type of the attachment (e.g., "image/jpeg", "video/mp4", "audio/m4a")
    let contentType: String

    /// Original filename of the attachment, if available. May be nil for inline images.
    let fileName: String?

    /// Remote URL where the attachment file is stored.
    /// May be nil if the attachment data is stored inline as Base64.
    let url: String?

    /// Cloudflare R2 storage key for the attachment file.
    /// When present, use getR2DownloadUrl Cloud Function to get a presigned download URL.
    /// Format: "mms/{userId}/{messageId}/{fileId}.{ext}"
    let r2Key: String?

    /// Simplified type category for the attachment.
    /// Possible values: "image", "video", "audio", "vcard", "file"
    /// Used for quick type checking without parsing the full MIME type.
    let type: String

    /// Indicates whether the attachment data is encrypted using E2EE (end-to-end encryption).
    /// When true, the data must be decrypted using `E2EEManager` before display.
    let encrypted: Bool?

    /// Base64-encoded attachment data for small files when cloud storage is unavailable.
    /// This is used as a fallback when cloud storage upload fails or for very small files.
    /// The data may be encrypted if `encrypted` is true.
    let inlineData: String?

    /// Indicates whether this attachment's data is stored inline (Base64) rather than as a URL.
    /// When true, use `inlineData` instead of `url` to access the content.
    let isInline: Bool?

    /// Creates a new MMS attachment with the specified properties.
    ///
    /// - Parameters:
    ///   - id: Unique identifier for this attachment
    ///   - contentType: MIME type of the attachment
    ///   - fileName: Optional original filename
    ///   - url: Optional remote URL for the attachment
    ///   - r2Key: Optional Cloudflare R2 storage key
    ///   - type: Simplified type category ("image", "video", "audio", "vcard", "file")
    ///   - encrypted: Whether the attachment data is E2EE encrypted
    ///   - inlineData: Optional Base64-encoded data for inline storage
    ///   - isInline: Whether the attachment uses inline storage
    init(
        id: String,
        contentType: String,
        fileName: String? = nil,
        url: String? = nil,
        r2Key: String? = nil,
        type: String,
        encrypted: Bool? = nil,
        inlineData: String? = nil,
        isInline: Bool? = nil
    ) {
        self.id = id
        self.contentType = contentType
        self.fileName = fileName
        self.url = url
        self.r2Key = r2Key
        self.type = type
        self.encrypted = encrypted
        self.inlineData = inlineData
        self.isInline = isInline
    }

    // MARK: - Type Checking Computed Properties

    /// Returns true if this attachment is an image file.
    /// Checks both the simplified `type` field and the full MIME type in `contentType`.
    var isImage: Bool {
        return type == "image" || contentType.hasPrefix("image/")
    }

    /// Returns true if this attachment is a video file.
    /// Checks both the simplified `type` field and the full MIME type in `contentType`.
    var isVideo: Bool {
        return type == "video" || contentType.hasPrefix("video/")
    }

    /// Returns true if this attachment is an audio file (voice memo, music, etc.).
    /// Checks both the simplified `type` field and the full MIME type in `contentType`.
    var isAudio: Bool {
        return type == "audio" || contentType.hasPrefix("audio/")
    }

    /// Returns true if this attachment is a vCard (contact card) file.
    /// vCards are commonly shared for exchanging contact information.
    var isVCard: Bool {
        return type == "vcard" || contentType.contains("vcard")
    }

    /// Returns true if this attachment's data is encrypted with E2EE.
    /// Defaults to false if the `encrypted` field is nil.
    var isEncrypted: Bool {
        return encrypted ?? false
    }

    // MARK: - URL Generation

    /// Returns a URL suitable for playing or viewing the attachment content.
    ///
    /// This computed property handles two scenarios:
    /// 1. **Remote URL**: If `url` is set, returns it directly as a URL object
    /// 2. **Inline Data**: If `inlineData` is set, decodes the Base64 data (decrypting if needed),
    ///    writes it to a temporary file, and returns the temporary file URL
    ///
    /// The temporary file is written to the system's temporary directory with the attachment ID
    /// as the filename and an appropriate extension based on the content type.
    ///
    /// - Returns: A URL for accessing the attachment, or nil if the URL is invalid or file creation fails
    var playableURL: URL? {
        if let urlString = url, let url = URL(string: urlString) {
            return url
        }

        // Handle inline base64 data
        if let base64Data = inlineData,
           let data = Data(base64Encoded: base64Data) {
            // Note: E2EE for MMS attachments is currently disabled
            // MMS attachments are self-sync and don't use E2EE encryption
            let finalData = data

            // Create temporary file
            let tempDir = FileManager.default.temporaryDirectory
            let fileExtension = extensionForContentType()
            let tempFile = tempDir.appendingPathComponent("\(id).\(fileExtension)")

            do {
                try finalData.write(to: tempFile)
                return tempFile
            } catch {
                print("Failed to write inline data to temp file: \(error)")
                return nil
            }
        }

        return nil
    }

    /// Maps the attachment's MIME content type to an appropriate file extension.
    ///
    /// This is used when creating temporary files for inline data playback.
    /// Supports common audio, image, and video formats. Falls back to "bin" for unknown types.
    ///
    /// - Returns: A file extension string (without the leading dot)
    private func extensionForContentType() -> String {
        switch contentType {
        case "audio/mp4", "audio/m4a":
            return "m4a"
        case "audio/mpeg":
            return "mp3"
        case "audio/wav":
            return "wav"
        case "audio/aac":
            return "aac"
        case "image/jpeg":
            return "jpg"
        case "image/png":
            return "png"
        case "video/mp4":
            return "mp4"
        default:
            return "bin"
        }
    }
}

// MARK: - Message Model

/// Represents a single SMS or MMS message synchronized from the connected Android device.
///
/// Messages are the core data unit in SyncFlow. They are uploaded from the Android app to the VPS
/// server and then observed in real-time by the macOS app. Each message contains the sender/recipient
/// address, message content, timestamp, and metadata about whether it was sent or received.
///
/// ## Message Types
/// The `type` field follows the Android SMS/MMS content provider conventions:
/// - `1` = Received (incoming message from another person)
/// - `2` = Sent (outgoing message from the user)
///
/// ## MMS Support
/// MMS messages are indicated by `isMms = true` and may contain attachments in the `attachments`
/// array. Common attachment types include images, videos, audio files, and vCards.
///
/// ## End-to-End Encryption (E2EE)
/// Messages can optionally be encrypted using E2EE. If encryption fails during send (e.g., the
/// recipient doesn't have E2EE enabled), the message is sent as plaintext and `e2eeFailed` is
/// set to true with the reason in `e2eeFailureReason`.
///
/// ## Relationships
/// - Child: `MmsAttachment` - A Message can have zero or more attachments
/// - Parent: `Conversation` - Messages are grouped into conversations by address
/// - DTO: `FirebaseMessage` - Converts to Message after deserialization
///
/// ## Codable Conformance
/// This struct conforms to `Codable` for JSON serialization. The `date` field is stored as a
/// Double (milliseconds since Unix epoch) for compatibility with JSON number format.
struct Message: Identifiable, Codable, Hashable {
    /// Unique identifier for this message, typically the server key or Android message ID
    let id: String

    /// Phone number or address of the other party (sender for received, recipient for sent).
    /// Format varies by carrier but is typically E.164 format (e.g., "+15551234567") or
    /// a short code (e.g., "12345").
    let address: String

    /// Text content of the message. May be empty for MMS messages that contain only attachments.
    let body: String

    /// Timestamp when the message was sent or received, in milliseconds since Unix epoch.
    /// Use the `timestamp` computed property to get this as a Swift `Date` object.
    let date: Double

    /// Message direction type following Android SMS content provider conventions.
    /// - `1` = Received (incoming)
    /// - `2` = Sent (outgoing)
    let type: Int

    /// Display name of the contact, if resolved from the device's contacts.
    /// May be nil if the sender is not in the user's contacts.
    let contactName: String?

    /// Whether this message has been read. Defaults to true for backwards compatibility
    /// with messages that were synced before read status tracking was implemented.
    var isRead: Bool = true

    /// Whether this is an MMS (multimedia) message rather than a plain SMS.
    /// When true, check the `attachments` array for media content.
    var isMms: Bool = false

    /// Array of multimedia attachments for MMS messages.
    /// Will be nil or empty for plain SMS messages.
    var attachments: [MmsAttachment]? = nil

    /// Indicates that E2EE encryption was attempted but failed for this message.
    /// The message was sent as plaintext instead. Check `e2eeFailureReason` for details.
    var e2eeFailed: Bool = false

    /// Explanation of why E2EE encryption failed, if applicable.
    /// Common reasons: recipient not enrolled in E2EE, key exchange failed, etc.
    var e2eeFailureReason: String? = nil

    /// Whether this message was encrypted end-to-end when synced.
    /// Nil when the source didn't provide encryption metadata.
    var isEncrypted: Bool? = nil

    /// Carrier delivery status for sent messages.
    /// Values: nil (unknown), "sending", "sent", "delivered", "failed"
    var deliveryStatus: String? = nil

    // MARK: - Computed Properties

    /// Returns true if this is a received (incoming) message.
    /// Equivalent to checking `type == 1`.
    var isReceived: Bool {
        return type == 1
    }

    /// Returns true if this message has one or more attachments.
    /// This is attachment-driven (does not depend on `isMms`).
    var hasAttachments: Bool {
        return !(attachments?.isEmpty ?? true)
    }

    /// Converts the `date` field (milliseconds since epoch) to a Swift `Date` object.
    /// The division by 1000 converts from milliseconds to seconds.
    var timestamp: Date {
        return Date(timeIntervalSince1970: date / 1000.0)
    }

    /// Returns a short time string for display (e.g., "3:45 PM").
    /// Uses the user's locale settings for formatting.
    var formattedTime: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: timestamp)
    }

    /// Returns a human-friendly date string with smart formatting.
    ///
    /// Formatting rules:
    /// - Today: Returns "Today"
    /// - Yesterday: Returns "Yesterday"
    /// - This week: Returns the day name (e.g., "Tuesday")
    /// - Older: Returns formatted date (e.g., "Jan 15, 2024")
    var formattedDate: String {
        let formatter = DateFormatter()
        let calendar = Calendar.current

        if calendar.isDateInToday(timestamp) {
            return "Today"
        } else if calendar.isDateInYesterday(timestamp) {
            return "Yesterday"
        } else if calendar.isDate(timestamp, equalTo: Date(), toGranularity: .weekOfYear) {
            formatter.dateFormat = "EEEE"  // Day of week
            return formatter.string(from: timestamp)
        } else {
            formatter.dateStyle = .medium
            formatter.timeStyle = .none
            return formatter.string(from: timestamp)
        }
    }

    /// Detects whether the message body contains clickable links (URLs).
    /// Uses NSDataDetector for reliable link detection across various URL formats.
    var hasLinks: Bool {
        let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let matches = detector?.matches(in: body, range: NSRange(body.startIndex..., in: body))
        return !(matches?.isEmpty ?? true)
    }
}

// MARK: - Conversation Model

/// Represents a conversation thread containing messages with a specific contact or phone number.
///
/// Conversations are the primary organizational unit in the messaging UI. They group all messages
/// exchanged with a particular address (phone number) and provide summary information like the
/// most recent message, unread count, and contact details.
///
/// ## Identification
/// Conversations are identified by the `address` field, which serves as a unique key. For group
/// MMS messages (not yet fully supported), `allAddresses` contains all participants.
///
/// ## State Management
/// Conversations support several user-managed states:
/// - `isPinned`: Pinned conversations appear at the top of the list
/// - `isArchived`: Archived conversations are hidden from the main list
/// - `isBlocked`: Messages from blocked conversations are filtered out
///
/// ## Relationships
/// - Children: `Message` - A Conversation contains multiple messages (not directly referenced)
/// - Note: Conversation does not directly hold Message references; messages are fetched separately
///
/// ## Codable Conformance
/// This struct does NOT conform to Codable. Conversations are constructed dynamically by aggregating
/// Message data. They are not stored directly on the server; instead, conversation state (pinned,
/// archived, blocked) is stored separately and merged at runtime.
struct Conversation: Identifiable, Hashable {
    /// Unique identifier for this conversation. Currently uses the primary address as the ID.
    let id: String

    /// Primary phone number or address for this conversation.
    /// Used for sending new messages and identifying the conversation partner.
    let address: String

    /// Display name of the contact from the device's address book.
    /// May be nil if the number is not saved as a contact.
    let contactName: String?

    /// Text content of the most recent message in this conversation.
    /// Used for preview display in the conversation list.
    let lastMessage: String

    /// Timestamp of the most recent message. Used for sorting conversations
    /// by recency in the conversation list.
    let timestamp: Date

    /// Number of unread messages in this conversation.
    /// Displayed as a badge in the UI when greater than zero.
    let unreadCount: Int

    /// All phone numbers/addresses associated with this conversation.
    /// For one-on-one SMS conversations, this contains just the single address.
    /// For group MMS messages, this contains all participant addresses.
    let allAddresses: [String]

    /// Whether this conversation is pinned to the top of the list.
    /// Pinned conversations appear before unpinned ones regardless of timestamp.
    var isPinned: Bool = false

    /// Whether this conversation has been archived by the user.
    /// Archived conversations are hidden from the main conversation list.
    var isArchived: Bool = false

    /// Whether messages from this conversation should be blocked/hidden.
    /// Blocked conversations don't show notifications and may be filtered from view.
    var isBlocked: Bool = false

    /// Hex color code for the avatar background (e.g., "#FF5733").
    /// If nil, a default color is generated based on the contact name or address.
    var avatarColor: String?

    /// Whether the most recent message in this conversation was E2EE encrypted.
    var lastMessageEncrypted: Bool = false

    /// Whether E2EE encryption failed for the most recent message.
    var lastMessageE2eeFailed: Bool = false

    // MARK: - Computed Properties

    /// Returns the best available display name for the conversation.
    /// Prefers the contact name if available, otherwise falls back to the phone number.
    var displayName: String {
        return contactName ?? address
    }

    /// Returns a formatted timestamp string appropriate for display in the conversation list.
    ///
    /// Formatting rules (progressively less specific for older messages):
    /// - Today: Short time (e.g., "3:45 PM")
    /// - Yesterday: "Yesterday"
    /// - This week: Short day name (e.g., "Tue")
    /// - This year: Month and day (e.g., "Jan 15")
    /// - Older: Full date (e.g., "01/15/23")
    var formattedTime: String {
        let formatter = DateFormatter()
        let calendar = Calendar.current

        if calendar.isDateInToday(timestamp) {
            formatter.dateStyle = .none
            formatter.timeStyle = .short
            return formatter.string(from: timestamp)
        } else if calendar.isDateInYesterday(timestamp) {
            return "Yesterday"
        } else if calendar.isDate(timestamp, equalTo: Date(), toGranularity: .weekOfYear) {
            formatter.dateFormat = "EEE"  // Short day
            return formatter.string(from: timestamp)
        } else if calendar.isDate(timestamp, equalTo: Date(), toGranularity: .year) {
            formatter.dateFormat = "MMM d"
            return formatter.string(from: timestamp)
        } else {
            formatter.dateFormat = "MM/dd/yy"
            return formatter.string(from: timestamp)
        }
    }

    /// Returns a truncated preview of the last message for display in the conversation list.
    /// Messages longer than 50 characters are truncated with an ellipsis.
    var preview: String {
        if lastMessage.count > 50 {
            return String(lastMessage.prefix(50)) + "..."
        }
        return lastMessage
    }

    /// Generates initials for the avatar display.
    ///
    /// For contacts with multiple name components (e.g., "John Smith"), returns the first
    /// letter of the first and last names ("JS"). For single-word names or phone numbers,
    /// returns the first two characters.
    var initials: String {
        let name = displayName
        let components = name.split(separator: " ")
        if components.count >= 2 {
            let first = components[0].prefix(1)
            let last = components[1].prefix(1)
            return "\(first)\(last)".uppercased()
        }
        return String(name.prefix(2)).uppercased()
    }
}

// MARK: - Response Models

/// Data Transfer Object (DTO) for deserializing attachment data from server responses.
///
/// Server JSON data uses flexible typing, so this struct uses optional types throughout
/// to handle potentially missing or differently-typed fields. After deserialization, use
/// `toMmsAttachment()` to convert to the app's native `MmsAttachment` type.
///
/// ## Why a Separate DTO?
/// JSON may return:
/// - Numbers as Int or Double depending on value
/// - Missing fields as nil rather than default values
/// - The `id` field as Int (from Android's content provider) rather than String
///
/// This DTO handles these variations and normalizes them in `toMmsAttachment()`.
struct FirebaseAttachment: Codable {
    /// Attachment ID from Android, stored as Int. Converted to String in MmsAttachment.
    let id: Int?

    /// MIME type of the attachment. Defaults to "application/octet-stream" if missing.
    let contentType: String?

    /// Original filename, if available
    let fileName: String?

    /// Remote URL for the attachment in cloud storage
    let url: String?

    /// Simplified type category. Defaults to "file" if missing.
    let type: String?

    /// Whether the attachment is E2EE encrypted
    let encrypted: Bool?

    /// Base64-encoded inline data for small attachments
    let inlineData: String?

    /// Whether the attachment uses inline storage
    let isInline: Bool?

    /// Converts this DTO to a native MmsAttachment model.
    ///
    /// Applies default values for any nil fields:
    /// - `id`: Converts to String, defaults to "0"
    /// - `contentType`: Defaults to "application/octet-stream"
    /// - `type`: Defaults to "file"
    ///
    /// - Returns: A properly typed `MmsAttachment` instance
    func toMmsAttachment() -> MmsAttachment {
        return MmsAttachment(
            id: String(id ?? 0),
            contentType: contentType ?? "application/octet-stream",
            fileName: fileName,
            url: url,
            type: type ?? "file",
            encrypted: encrypted,
            inlineData: inlineData,
            isInline: isInline
        )
    }
}

/// Data Transfer Object (DTO) for deserializing message data from server responses.
///
/// Similar to `FirebaseAttachment`, this struct handles flexible JSON typing and
/// provides conversion to the app's native `Message` type via `toMessage(id:)`.
///
/// ## Data Structure
/// Messages are stored on the server with a message ID as the key.
/// The message ID may not be stored within the message data itself,
/// which is why `toMessage(id:)` requires the ID as a parameter.
///
/// ## Type Handling
/// - `id`: May be present in the data or provided externally (server key)
/// - `isMms`: Optional, defaults to false
/// - `attachments`: Optional array, only present for MMS messages
/// - `e2eeFailed`: Optional, defaults to false
struct FirebaseMessage: Codable {
    /// Optional message ID (may be stored externally as server key)
    let id: String?

    /// Phone number or address of the message sender/recipient
    let address: String

    /// Text content of the message
    let body: String

    /// Timestamp in milliseconds since Unix epoch
    let date: Double

    /// Message type: 1 = received, 2 = sent
    let type: Int

    /// Contact name if resolved from address book
    let contactName: String?

    /// Whether this is an MMS message. Defaults to false if nil.
    let isMms: Bool?

    /// Array of attachment DTOs for MMS messages
    let attachments: [FirebaseAttachment]?

    /// Whether E2EE encryption failed for this message
    let e2eeFailed: Bool?

    /// Reason for E2EE encryption failure, if applicable
    let e2eeFailureReason: String?

    /// Whether the message was E2EE encrypted
    let encrypted: Bool?

    /// Converts this DTO to a native Message model.
    ///
    /// - Parameter id: The message ID (typically the server key for this message)
    /// - Returns: A properly typed `Message` instance with all attachments converted
    func toMessage(id: String) -> Message {
        let mmsAttachments = attachments?.map { $0.toMmsAttachment() }
        return Message(
            id: id,
            address: address,
            body: body,
            date: date,
            type: type,
            contactName: contactName,
            isMms: isMms ?? false,
            attachments: mmsAttachments,
            e2eeFailed: e2eeFailed ?? false,
            e2eeFailureReason: e2eeFailureReason,
            isEncrypted: encrypted
        )
    }
}

// MARK: - Read Receipts

/// Tracks when a message or conversation has been read, supporting multi-device sync.
///
/// Read receipts allow the app to synchronize read status across multiple devices. When a user
/// reads a message on one device (e.g., their phone), the read receipt is synced to the server,
/// and other devices (e.g., the Mac app) can update their UI accordingly.
///
/// ## Multi-Device Support
/// The `readBy` and `readDeviceName` fields identify which device marked the message as read,
/// enabling conflict resolution when messages are read on multiple devices simultaneously.
///
/// ## Relationships
/// - Related to: `Message` - Read receipts reference messages via address and source ID
/// - Related to: `Conversation` - The conversationAddress links to a Conversation
struct ReadReceipt: Identifiable, Hashable {
    /// Unique identifier for this read receipt
    let id: String

    /// Timestamp when the message was marked as read, in milliseconds since Unix epoch
    let readAt: Double

    /// Identifier of the user/device that marked the message as read
    let readBy: String

    /// Human-readable name of the device that marked the message as read (e.g., "John's iPhone")
    let readDeviceName: String?

    /// Phone number/address of the conversation containing the read message.
    /// Used to identify which conversation's unread count should be updated.
    let conversationAddress: String

    /// Original message ID from the Android content provider, if available.
    /// Used for precise message identification in large conversations.
    let sourceId: Int64?

    /// Type of the source (e.g., "sms", "mms"). Used with sourceId for identification.
    let sourceType: String?
}
