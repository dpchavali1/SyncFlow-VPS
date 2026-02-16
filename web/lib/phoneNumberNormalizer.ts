/**
 * Consistent phone number normalization across all platforms
 * Ensures contacts sync correctly between Android, macOS, and Web
 *
 * Uses google-libphonenumber for proper international support.
 */
import {
  PhoneNumberUtil,
  PhoneNumberFormat,
} from 'google-libphonenumber'

const phoneUtil = PhoneNumberUtil.getInstance()

// Default region derived from browser locale (e.g. "en-US" → "US", "hi-IN" → "IN")
function getDefaultRegion(): string {
  if (typeof navigator !== 'undefined' && navigator.language) {
    const parts = navigator.language.split('-')
    if (parts.length >= 2) {
      return parts[parts.length - 1].toUpperCase()
    }
  }
  return 'US'
}

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
 * - Phone numbers: normalized to E.164 via libphonenumber (canonical key)
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
    // Use E.164 as canonical conversation key
    result = PhoneNumberNormalizer.toE164(address)
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
   * Normalize phone number to E.164 format for comparison.
   * Returns E.164 string (e.g. "+12345678900") or stripped digits for unparseable input.
   */
  static normalize(phoneNumber: string | null | undefined): string {
    if (!phoneNumber || phoneNumber.trim() === '') {
      return ''
    }

    return this.toE164(phoneNumber)
  }

  /**
   * Format phone number for display.
   * Same-region numbers: NATIONAL format (e.g. "(234) 567-8900" for US)
   * Different-region numbers: INTERNATIONAL format (e.g. "+91 98765 43210")
   */
  static formatForDisplay(phoneNumber: string | null | undefined): string {
    if (!phoneNumber || phoneNumber === '') {
      return ''
    }

    const defaultRegion = getDefaultRegion()

    try {
      const parsed = phoneUtil.parse(phoneNumber, defaultRegion)
      if (phoneUtil.isValidNumber(parsed)) {
        const numberRegion = phoneUtil.getRegionCodeForNumber(parsed)
        if (numberRegion === defaultRegion) {
          return phoneUtil.format(parsed, PhoneNumberFormat.NATIONAL)
        }
        return phoneUtil.format(parsed, PhoneNumberFormat.INTERNATIONAL)
      }
    } catch {
      // Fall through
    }

    // Fallback: return as-is
    return phoneNumber
  }

  /**
   * Create deduplication key for contacts
   * Uses E.164 phone number (not hashCode which changes)
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
   * Uses libphonenumber with browser locale as default region.
   * Falls back to legacy heuristics if libphonenumber can't parse.
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

    // Try libphonenumber parsing
    const defaultRegion = getDefaultRegion()
    try {
      const parsed = phoneUtil.parse(stripped, defaultRegion)
      if (phoneUtil.isValidNumber(parsed)) {
        return phoneUtil.format(parsed, PhoneNumberFormat.E164)
      }
    } catch {
      // Fall through to legacy behavior
    }

    // Legacy fallback: already has '+' prefix → keep as-is
    if (stripped.startsWith('+')) return stripped

    // Legacy fallback: 10 digits → US number
    if (digitsOnly.length === 10) return `+1${digitsOnly}`

    // Legacy fallback: 11 digits starting with '1' → US number
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
