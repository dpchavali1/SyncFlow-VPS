-- Migration 013: Update normalize_phone_e164 function for international support
-- Run against: syncflow_prod
-- Usage: psql -d syncflow_prod -f 013_update_e164_normalization.sql
--
-- Context: The app now uses google-libphonenumber (with device locale) for normalization.
-- The SQL function can't use libphonenumber, so it keeps the existing heuristic logic.
-- This is fine because:
--   1. Existing US numbers already have correct +1 prefix from migration 004
--   2. Numbers with '+' prefix (international) already pass through correctly
--   3. New messages get correct E.164 from the app layer (with device locale)
--   4. This migration adds a comment documenting the limitation and is a no-op for data
--
-- The only data change is adding a GIN index on user_contacts.phone_numbers
-- to support the JSONB containment query (@>) used in the fixed calls.ts contact lookup.

-- Add GIN index for JSONB containment queries on phone_numbers
CREATE INDEX IF NOT EXISTS idx_user_contacts_phone_numbers_gin
  ON user_contacts USING GIN (phone_numbers);

-- Update the helper function with a note about international numbers
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

  -- Already has '+' prefix → keep as-is (correct E.164 from app layer)
  IF left(stripped, 1) = '+' THEN RETURN stripped; END IF;

  -- NOTE: Numbers without '+' prefix are assumed US here (server-side fallback).
  -- The app layer now uses libphonenumber with device locale for correct inference.
  -- This SQL fallback only applies to admin scripts or direct DB operations.

  -- 10 digits without '+' → US number, prepend '+1'
  IF length(digits_only) = 10 THEN RETURN '+1' || digits_only; END IF;

  -- 11 digits starting with '1' without '+' → US number, prepend '+'
  IF length(digits_only) = 11 AND left(digits_only, 1) = '1' THEN RETURN '+' || digits_only; END IF;

  -- Anything else: return digits
  RETURN digits_only;
END;
$$ LANGUAGE plpgsql IMMUTABLE;
