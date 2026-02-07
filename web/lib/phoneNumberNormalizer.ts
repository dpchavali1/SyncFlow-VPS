/**
 * Consistent phone number normalization across all platforms
 * Ensures contacts sync correctly between Android, macOS, and Web
 */

export class PhoneNumberNormalizer {
  /**
   * Normalize phone number to standard format for comparison
   * Handles: +1-234-567-8900, (234) 567-8900, 234-567-8900, 2345678900, etc.
   */
  static normalize(phoneNumber: string | null | undefined): string {
    if (!phoneNumber || phoneNumber.trim() === '') {
      return ''
    }

    // Remove all non-digit characters
    const digitsOnly = phoneNumber.replace(/[^0-9+]/g, '')

    // Remove leading + if present
    const withoutPlus = digitsOnly.replace(/\+/g, '')

    // If starts with 1 (US country code), remove it to get 10 digits
    let normalized = withoutPlus
    if (withoutPlus.startsWith('1') && withoutPlus.length > 10) {
      normalized = withoutPlus.substring(1)
    }

    return normalized
  }

  /**
   * Format phone number for display
   * US: (234) 567-8900
   * International: keep as-is
   */
  static formatForDisplay(phoneNumber: string | null | undefined): string {
    if (!phoneNumber || phoneNumber === '') {
      return ''
    }

    const normalized = this.normalize(phoneNumber)

    // US format (10 digits)
    if (normalized.length === 10) {
      const areaCode = normalized.substring(0, 3)
      const prefix = normalized.substring(3, 6)
      const lineNumber = normalized.substring(6)
      return `(${areaCode}) ${prefix}-${lineNumber}`
    } else {
      // International or non-standard
      return normalized
    }
  }

  /**
   * Create deduplication key for contacts
   * Uses normalized phone number only (not hashCode which changes)
   */
  static getDeduplicationKey(phoneNumber: string | null | undefined, displayName?: string | null): string {
    const normalized = this.normalize(phoneNumber)

    if (normalized) {
      return `phone_${normalized}`
    } else if (displayName && displayName.trim() !== '') {
      const cleanName = displayName
        .toLowerCase()
        .replace(/[^a-z0-9]/g, '')
      return `name_${cleanName}`
    } else {
      return `unknown_${Date.now()}`
    }
  }

  /**
   * Check if two phone numbers are the same person
   */
  static isSameContact(phone1: string | null | undefined, phone2: string | null | undefined): boolean {
    const norm1 = this.normalize(phone1)
    const norm2 = this.normalize(phone2)
    return norm1 !== '' && norm1 === norm2
  }
}
