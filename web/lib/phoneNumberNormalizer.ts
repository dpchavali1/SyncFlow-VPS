/**
 * Consistent phone number normalization across all platforms
 * Ensures contacts sync correctly between Android, macOS, and Web
 */

// ==================== Conversation-level normalization ====================

/**
 * LRU Cache for conversation-level phone number normalization.
 * Shared across all components that group messages by address.
 */
const normalizationCache = new Map<string, string>()
const MAX_CACHE_SIZE = 1000

/**
 * Normalize a phone number for conversation grouping / comparison.
 *
 * - Emails and short codes (< 6 chars): lowercased as-is
 * - Phone numbers: stripped to digits, last 10 digits used (handles country codes)
 *
 * Results are cached in an LRU map for performance.
 */
export function normalizePhoneForConversation(address: string): string {
  const cached = normalizationCache.get(address)
  if (cached !== undefined) return cached

  let result: string
  if (address.includes('@') || address.length < 6) {
    result = address.toLowerCase()
  } else {
    const digitsOnly = address.replace(/[^0-9]/g, '')
    result = digitsOnly.length >= 10 ? digitsOnly.slice(-10) : digitsOnly
  }

  // Maintain cache size with LRU eviction
  if (normalizationCache.size >= MAX_CACHE_SIZE) {
    const firstKey = normalizationCache.keys().next().value
    if (firstKey) normalizationCache.delete(firstKey)
  }
  normalizationCache.set(address, result)
  return result
}

// ==================== Full phone number normalization ====================

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
   * Convert phone number to E.164 format for server communication.
   * Rules:
   * - 10 digits → +1XXXXXXXXXX (US)
   * - 11 digits starting with 1 → +1XXXXXXXXXX (US)
   * - Already starts with + → keep as-is
   * - Short codes (<=6 digits), emails, alphanumeric sender IDs → unchanged
   */
  static toE164(phoneNumber: string | null | undefined): string {
    if (!phoneNumber || phoneNumber.trim() === '') return phoneNumber ?? ''

    const trimmed = phoneNumber.trim()

    // Leave emails unchanged
    if (trimmed.includes('@')) return trimmed

    // Leave alphanumeric sender IDs unchanged
    if (/[a-zA-Z]/.test(trimmed)) return trimmed

    // Strip everything except digits and '+'
    const stripped = trimmed.replace(/[^0-9+]/g, '')
    if (stripped === '' || stripped === '+') return trimmed

    const digitsOnly = stripped.replace(/\+/g, '')

    // Short codes (<=6 digits)
    if (digitsOnly.length <= 6) return digitsOnly

    // Already has '+' prefix → keep as-is
    if (stripped.startsWith('+')) return stripped

    // 10 digits → US number
    if (digitsOnly.length === 10) return `+1${digitsOnly}`

    // 11 digits starting with '1' → US number
    if (digitsOnly.length === 11 && digitsOnly.startsWith('1')) return `+${digitsOnly}`

    return digitsOnly
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
