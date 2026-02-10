import Foundation

/**
 * Consistent phone number normalization across all platforms
 * Ensures contacts sync correctly between Android, macOS, and Web
 */
class PhoneNumberNormalizer {

    static let shared = PhoneNumberNormalizer()

    /**
     * Normalize phone number to standard format for comparison
     * Handles: +1-234-567-8900, (234) 567-8900, 234-567-8900, 2345678900, etc.
     */
    func normalize(_ phoneNumber: String?) -> String {
        guard let phoneNumber = phoneNumber, !phoneNumber.trimmingCharacters(in: .whitespaces).isEmpty else {
            return ""
        }

        // Remove all non-digit characters
        let digitsOnly = phoneNumber.replacingOccurrences(
            of: "[^0-9+]",
            with: "",
            options: .regularExpression
        )

        // Remove leading + if present
        let withoutPlus = digitsOnly.replacingOccurrences(of: "+", with: "")

        // If starts with 1 (US country code), remove it to get 10 digits
        let normalized: String
        if withoutPlus.hasPrefix("1") && withoutPlus.count > 10 {
            normalized = String(withoutPlus.dropFirst())
        } else {
            normalized = withoutPlus
        }

        return normalized
    }

    /**
     * Format phone number for display
     * US: (234) 567-8900
     * International: keep as-is
     */
    func formatForDisplay(_ phoneNumber: String?) -> String {
        guard let phoneNumber = phoneNumber, !phoneNumber.isEmpty else { return "" }

        let normalized = normalize(phoneNumber)

        // US format (10 digits)
        if normalized.count == 10 {
            let areaCode = String(normalized.prefix(3))
            let prefix = String(normalized.dropFirst(3).prefix(3))
            let lineNumber = String(normalized.suffix(4))
            return "(\(areaCode)) \(prefix)-\(lineNumber)"
        } else {
            // International or non-standard
            return normalized
        }
    }

    /**
     * Create deduplication key for contacts
     * Uses normalized phone number only (not hashCode which changes)
     */
    func getDeduplicationKey(phoneNumber: String?, displayName: String? = nil) -> String {
        let normalized = normalize(phoneNumber)

        if !normalized.isEmpty {
            return "phone_\(normalized)"
        } else if let displayName = displayName, !displayName.isEmpty {
            let cleanName = displayName.lowercased()
                .replacingOccurrences(of: "[^a-z0-9]", with: "", options: .regularExpression)
            return "name_\(cleanName)"
        } else {
            return "unknown_\(Date().timeIntervalSince1970)"
        }
    }

    /**
     * Convert phone number to E.164 format for server communication.
     * Rules:
     * - 10 digits → +1XXXXXXXXXX (US)
     * - 11 digits starting with 1 → +1XXXXXXXXXX (US)
     * - Already starts with + → keep as-is
     * - Short codes (<=6 digits), emails, alphanumeric sender IDs → unchanged
     */
    func toE164(_ phoneNumber: String?) -> String {
        guard let phoneNumber = phoneNumber, !phoneNumber.trimmingCharacters(in: .whitespaces).isEmpty else {
            return phoneNumber ?? ""
        }

        let trimmed = phoneNumber.trimmingCharacters(in: .whitespaces)

        // Leave emails unchanged
        if trimmed.contains("@") { return trimmed }

        // Leave alphanumeric sender IDs unchanged
        if trimmed.rangeOfCharacter(from: .letters) != nil { return trimmed }

        // Strip everything except digits and '+'
        let stripped = trimmed.replacingOccurrences(of: "[^0-9+]", with: "", options: .regularExpression)
        if stripped.isEmpty || stripped == "+" { return trimmed }

        let digitsOnly = stripped.replacingOccurrences(of: "+", with: "")

        // Short codes (<=6 digits)
        if digitsOnly.count <= 6 { return digitsOnly }

        // Already has '+' prefix → keep as-is
        if stripped.hasPrefix("+") { return stripped }

        // 10 digits → US number
        if digitsOnly.count == 10 { return "+1\(digitsOnly)" }

        // 11 digits starting with '1' → US number
        if digitsOnly.count == 11 && digitsOnly.hasPrefix("1") { return "+\(digitsOnly)" }

        return digitsOnly
    }

    /**
     * Check if two phone numbers are the same person
     */
    func isSameContact(phone1: String?, phone2: String?) -> Bool {
        let norm1 = normalize(phone1)
        let norm2 = normalize(phone2)
        return !norm1.isEmpty && norm1 == norm2
    }
}
