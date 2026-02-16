import { PhoneNumberUtil, PhoneNumberFormat } from 'google-libphonenumber';

const phoneUtil = PhoneNumberUtil.getInstance();

/**
 * E.164 phone number normalization utility using google-libphonenumber.
 *
 * Rules:
 * 1. Emails, alphanumeric sender IDs → leave unchanged
 * 2. Short codes (<=6 digits) → leave unchanged
 * 3. Already starts with '+' → parse with libphonenumber for validation/formatting
 * 4. No '+' prefix → parse using defaultRegion (device locale on clients, 'US' on server)
 * 5. If libphonenumber can't parse → fall through to legacy digit-based heuristic
 */
export function normalizePhoneNumber(input: string | null | undefined, defaultRegion: string = 'US'): string {
  if (!input || input.trim() === '') return '';

  const trimmed = input.trim();

  // Leave emails unchanged
  if (trimmed.includes('@')) return trimmed;

  // Leave alphanumeric sender IDs unchanged (e.g. "JM-HDFCEL-S", "AMAZON")
  if (/[a-zA-Z]/.test(trimmed)) return trimmed;

  // Strip everything except digits and '+'
  const stripped = trimmed.replace(/[^0-9+]/g, '');
  if (stripped === '' || stripped === '+') return trimmed;

  // Extract digits-only portion (without any '+')
  const digitsOnly = stripped.replace(/\+/g, '');

  // Short codes (<=6 digits) — leave unchanged
  if (digitsOnly.length <= 6) return digitsOnly;

  // Try libphonenumber parsing
  try {
    const parsed = phoneUtil.parse(stripped, defaultRegion);
    if (phoneUtil.isValidNumber(parsed)) {
      return phoneUtil.format(parsed, PhoneNumberFormat.E164);
    }
  } catch {
    // Fall through to legacy behavior
  }

  // Legacy fallback: already has '+' prefix → keep as-is
  if (stripped.startsWith('+')) return stripped;

  // Legacy fallback: 10 digits without '+' → US number, prepend '+1'
  if (digitsOnly.length === 10) return `+1${digitsOnly}`;

  // Legacy fallback: 11 digits starting with '1' without '+' → US number, prepend '+'
  if (digitsOnly.length === 11 && digitsOnly.startsWith('1')) return `+${digitsOnly}`;

  // Anything else: return as-is (with stripped formatting)
  return digitsOnly;
}
