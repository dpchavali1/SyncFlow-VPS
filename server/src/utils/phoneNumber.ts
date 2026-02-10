/**
 * E.164 phone number normalization utility.
 *
 * Rules:
 * 1. Strip all non-digit, non-'+' characters
 * 2. 10 digits (no '+') → prepend '+1' (US number)
 * 3. 11 digits starting with '1' (no '+') → prepend '+'
 * 4. Already starts with '+' → keep as-is
 * 5. Short codes (<=6 digits), alphanumeric sender IDs, emails → leave unchanged
 */
export function normalizePhoneNumber(input: string | null | undefined): string {
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

  // Already has '+' prefix → keep as-is (already E.164 or international)
  if (stripped.startsWith('+')) return stripped;

  // 10 digits without '+' → US number, prepend '+1'
  if (digitsOnly.length === 10) return `+1${digitsOnly}`;

  // 11 digits starting with '1' without '+' → US number, prepend '+'
  if (digitsOnly.length === 11 && digitsOnly.startsWith('1')) return `+${digitsOnly}`;

  // Anything else: return as-is (with stripped formatting)
  return digitsOnly;
}
