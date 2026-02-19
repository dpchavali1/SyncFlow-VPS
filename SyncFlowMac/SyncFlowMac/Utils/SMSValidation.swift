//
//  SMSValidation.swift
//  SyncFlowMac
//
//  SMS character counting and segment calculation.
//  Ported from Android InputValidation.kt to ensure consistent
//  behavior across platforms.
//

import Foundation

/// SMS encoding type detected from message content.
enum SMSEncoding: String {
    case gsm7 = "GSM-7"
    case unicode = "Unicode"
}

/// Result of SMS segment analysis.
struct SMSSegmentInfo {
    /// Number of carrier segments this message will be split into.
    let segments: Int
    /// Characters remaining in the current (last) segment.
    let charsRemaining: Int
    /// Detected encoding based on message content.
    let encoding: SMSEncoding
    /// Total character count.
    let charCount: Int
}

/// Validates and analyzes SMS messages for segment counting and character limits.
/// Matches the Android client's `InputValidation.calculateSmsSegments()` logic.
enum SMSValidation {

    // MARK: - Constants

    /// Maximum total message length (matches backend z.string().max(1600) and Android MAX_MESSAGE_LENGTH).
    static let maxMessageLength = 1600

    /// Single SMS limit for GSM-7 encoding.
    static let gsmSingleLimit = 160
    /// Per-segment limit for multipart GSM-7 (7 bytes used for concatenation header).
    static let gsmMultipartLimit = 153

    /// Single SMS limit for Unicode (UCS-2) encoding.
    static let unicodeSingleLimit = 70
    /// Per-segment limit for multipart Unicode.
    static let unicodeMultipartLimit = 67

    // MARK: - Segment Calculation

    /// Analyzes a message and returns segment info.
    /// - Parameter message: The SMS message text.
    /// - Returns: Segment count, remaining chars, encoding type, and char count.
    static func analyze(_ message: String) -> SMSSegmentInfo {
        let count = message.count
        guard count > 0 else {
            return SMSSegmentInfo(segments: 0, charsRemaining: gsmSingleLimit, encoding: .gsm7, charCount: 0)
        }

        let isUnicode = message.contains(where: { !isGsmCharacter($0) })
        let encoding: SMSEncoding = isUnicode ? .unicode : .gsm7

        let singleLimit = isUnicode ? unicodeSingleLimit : gsmSingleLimit
        let multipartLimit = isUnicode ? unicodeMultipartLimit : gsmMultipartLimit

        let segments: Int
        let charsRemaining: Int

        if count <= singleLimit {
            segments = 1
            charsRemaining = singleLimit - count
        } else {
            // Multipart: ceil(count / multipartLimit)
            segments = (count + multipartLimit - 1) / multipartLimit
            let usedInLastSegment = count - (segments - 1) * multipartLimit
            charsRemaining = multipartLimit - usedInLastSegment
        }

        return SMSSegmentInfo(
            segments: segments,
            charsRemaining: charsRemaining,
            encoding: encoding,
            charCount: count
        )
    }

    /// Calculates the number of SMS segments (matches Android's calculateSmsSegments exactly).
    /// - Parameter message: The SMS message text.
    /// - Returns: Number of carrier segments.
    static func calculateSegments(_ message: String) -> Int {
        analyze(message).segments
    }

    // MARK: - GSM-7 Character Detection

    /// The GSM 03.38 basic character set.
    private static let gsmBasicChars: Set<Character> = {
        let chars = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ ÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
        return Set(chars)
    }()

    /// GSM 03.38 extension table characters.
    private static let gsmExtChars: Set<Character> = Set("^{}\\[~]|€")

    /// Checks if a character is in the GSM-7 character set.
    /// Characters outside this set force Unicode (UCS-2) encoding,
    /// which reduces the per-segment limit from 160 to 70 characters.
    static func isGsmCharacter(_ char: Character) -> Bool {
        gsmBasicChars.contains(char) || gsmExtChars.contains(char)
    }
}
