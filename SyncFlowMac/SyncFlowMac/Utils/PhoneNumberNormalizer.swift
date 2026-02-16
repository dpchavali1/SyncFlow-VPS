import Foundation
import PhoneNumberKit

/**
 * Consistent phone number normalization across all platforms
 * Ensures contacts sync correctly between Android, macOS, and Web
 *
 * Uses PhoneNumberKit (Swift wrapper around libphonenumber) for proper international support.
 */
class PhoneNumberNormalizer {

    static let shared = PhoneNumberNormalizer()

    private let phoneNumberKit = PhoneNumberUtility()

    private var defaultRegion: String {
        if #available(macOS 13, *) {
            return Locale.current.region?.identifier ?? "US"
        } else {
            return Locale.current.regionCode ?? "US"
        }
    }

    /**
     * Normalize phone number to E.164 format for comparison.
     */
    func normalize(_ phoneNumber: String?) -> String {
        guard let phoneNumber = phoneNumber, !phoneNumber.trimmingCharacters(in: .whitespaces).isEmpty else {
            return ""
        }

        return toE164(phoneNumber)
    }

    /**
     * Format phone number for display using PhoneNumberKit.
     * Same-region: NATIONAL format. Different-region: INTERNATIONAL format.
     */
    func formatForDisplay(_ phoneNumber: String?) -> String {
        guard let phoneNumber = phoneNumber, !phoneNumber.isEmpty else { return "" }

        let region = defaultRegion

        do {
            let parsed = try phoneNumberKit.parse(phoneNumber, withRegion: region)
            let numberRegion = phoneNumberKit.getRegionCode(of: parsed)
            if numberRegion == region {
                return phoneNumberKit.format(parsed, toType: .national)
            }
            return phoneNumberKit.format(parsed, toType: .international)
        } catch {
            // Fall through
        }

        return phoneNumber
    }

    /**
     * Create deduplication key for contacts
     * Uses E.164 phone number (not hashCode which changes)
     */
    func getDeduplicationKey(phoneNumber: String?, displayName: String? = nil) -> String {
        let normalized = normalize(phoneNumber)

        if !normalized.isEmpty && normalized.hasPrefix("+") {
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
     * Uses PhoneNumberKit with device locale as default region.
     * Falls back to legacy heuristics if PhoneNumberKit can't parse.
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

        // Try PhoneNumberKit parsing
        do {
            let parsed = try phoneNumberKit.parse(stripped, withRegion: defaultRegion)
            return phoneNumberKit.format(parsed, toType: .e164)
        } catch {
            // Fall through to legacy behavior
        }

        // Legacy fallback: already has '+' prefix → keep as-is
        if stripped.hasPrefix("+") { return stripped }

        // Legacy fallback: 10 digits → US number
        if digitsOnly.count == 10 { return "+1\(digitsOnly)" }

        // Legacy fallback: 11 digits starting with '1' → US number
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
