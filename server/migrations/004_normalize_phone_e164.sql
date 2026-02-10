-- Migration: Normalize all phone numbers to E.164 format
-- This ensures consistent lookups across platforms.

-- Helper function implementing the same rules as server/src/utils/phoneNumber.ts
CREATE OR REPLACE FUNCTION normalize_phone_e164(input TEXT) RETURNS TEXT AS $$
DECLARE
  stripped TEXT;
  digits_only TEXT;
BEGIN
  IF input IS NULL OR trim(input) = '' THEN RETURN input; END IF;

  -- Leave emails unchanged
  IF position('@' IN input) > 0 THEN RETURN input; END IF;

  -- Leave alphanumeric sender IDs unchanged
  IF input ~ '[a-zA-Z]' THEN RETURN input; END IF;

  -- Strip everything except digits and '+'
  stripped := regexp_replace(trim(input), '[^0-9+]', '', 'g');
  IF stripped = '' OR stripped = '+' THEN RETURN trim(input); END IF;

  -- Digits-only (without +)
  digits_only := replace(stripped, '+', '');

  -- Short codes (<=6 digits) — leave unchanged
  IF length(digits_only) <= 6 THEN RETURN digits_only; END IF;

  -- Already has '+' prefix → keep as-is
  IF left(stripped, 1) = '+' THEN RETURN stripped; END IF;

  -- 10 digits without '+' → US number, prepend '+1'
  IF length(digits_only) = 10 THEN RETURN '+1' || digits_only; END IF;

  -- 11 digits starting with '1' without '+' → US number, prepend '+'
  IF length(digits_only) = 11 AND left(digits_only, 1) = '1' THEN RETURN '+' || digits_only; END IF;

  -- Anything else: return digits
  RETURN digits_only;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- 1. users.phone
UPDATE users SET phone = normalize_phone_e164(phone) WHERE phone IS NOT NULL AND phone != '';

-- 2. user_call_history.phone_number
UPDATE user_call_history SET phone_number = normalize_phone_e164(phone_number);

-- 3. user_messages.address (skip alphanumeric, emails, short codes — function handles these)
UPDATE user_messages SET address = normalize_phone_e164(address);

-- 4. user_spam_lists.phone_number
UPDATE user_spam_lists SET phone_number = normalize_phone_e164(phone_number);

-- 5. user_voicemails.phone_number
UPDATE user_voicemails SET phone_number = normalize_phone_e164(phone_number) WHERE phone_number IS NOT NULL;

-- 6. user_sims.phone_number
UPDATE user_sims SET phone_number = normalize_phone_e164(phone_number) WHERE phone_number IS NOT NULL;

-- 7. user_contacts.phone_numbers (JSONB array — normalize each element)
UPDATE user_contacts
SET phone_numbers = (
  SELECT jsonb_agg(normalize_phone_e164(elem::text #>> '{}'))
  FROM jsonb_array_elements(phone_numbers) AS elem
)
WHERE phone_numbers IS NOT NULL AND jsonb_array_length(phone_numbers) > 0;

-- 8. phone_uid_mapping.phone_number (PRIMARY KEY — handle deduplication)
-- First, delete rows that would become duplicates after normalization
-- (keep the row that already has '+' prefix or the first one)
DELETE FROM phone_uid_mapping a
USING phone_uid_mapping b
WHERE normalize_phone_e164(a.phone_number) = normalize_phone_e164(b.phone_number)
  AND a.phone_number != b.phone_number
  AND (
    -- Keep the one already starting with '+'
    (left(b.phone_number, 1) = '+' AND left(a.phone_number, 1) != '+')
    OR
    -- If neither starts with '+', keep the one with lower ctid (arbitrary but stable)
    (left(b.phone_number, 1) != '+' AND left(a.phone_number, 1) != '+' AND a.ctid > b.ctid)
  );

-- Now safe to update (use a temp table to avoid PK conflicts during update)
CREATE TEMP TABLE _phone_uid_new AS
SELECT normalize_phone_e164(phone_number) AS phone_number, uid, created_at
FROM phone_uid_mapping;

DELETE FROM phone_uid_mapping;
INSERT INTO phone_uid_mapping (phone_number, uid, created_at)
SELECT phone_number, uid, created_at FROM _phone_uid_new
ON CONFLICT (phone_number) DO NOTHING;
DROP TABLE _phone_uid_new;

-- 9. user_phone_registry.phone_number (has UNIQUE index on phone_number)
-- Deduplicate: keep the most recently registered entry per normalized number
DELETE FROM user_phone_registry a
USING user_phone_registry b
WHERE normalize_phone_e164(a.phone_number) = normalize_phone_e164(b.phone_number)
  AND a.phone_number != b.phone_number
  AND a.registered_at < b.registered_at;

-- Now safe to update via temp table
CREATE TEMP TABLE _phone_reg_new AS
SELECT user_id, normalize_phone_e164(phone_number) AS phone_number, registered_at, created_at
FROM user_phone_registry;

DELETE FROM user_phone_registry;
INSERT INTO user_phone_registry (user_id, phone_number, registered_at, created_at)
SELECT user_id, phone_number, registered_at, created_at FROM _phone_reg_new
ON CONFLICT (user_id) DO UPDATE SET
  phone_number = EXCLUDED.phone_number,
  registered_at = EXCLUDED.registered_at;
DROP TABLE _phone_reg_new;

-- Clean up: drop the helper function (optional, can keep for future use)
-- DROP FUNCTION IF EXISTS normalize_phone_e164(TEXT);
