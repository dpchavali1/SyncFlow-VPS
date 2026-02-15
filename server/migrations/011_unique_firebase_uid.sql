-- Migration: Make firebase_uid unique to prevent fingerprint collisions
-- Previously, uid = firebase_uid = device fingerprint, which caused collisions
-- when two same-model devices had the same ANDROID_ID. Now uid is a UUID and
-- firebase_uid is just a lookup key, but it must be unique per user.

-- Step 1: Find and fix duplicate firebase_uid values before adding unique constraint.
-- For duplicates, keep the user with the most messages and NULL-out the rest.
DO $$
DECLARE
  dup RECORD;
  keep_uid TEXT;
BEGIN
  FOR dup IN
    SELECT firebase_uid, COUNT(*) as cnt
    FROM users
    WHERE firebase_uid IS NOT NULL
    GROUP BY firebase_uid
    HAVING COUNT(*) > 1
  LOOP
    -- Keep the user with the most messages
    SELECT u.uid INTO keep_uid
    FROM users u
    LEFT JOIN user_messages m ON m.user_id = u.uid
    WHERE u.firebase_uid = dup.firebase_uid
    GROUP BY u.uid
    ORDER BY COUNT(m.id) DESC
    LIMIT 1;

    -- NULL out firebase_uid for the duplicates (not the keeper)
    UPDATE users SET firebase_uid = NULL
    WHERE firebase_uid = dup.firebase_uid AND uid != keep_uid;

    RAISE NOTICE 'Fixed duplicate firebase_uid: % (kept user %)', dup.firebase_uid, keep_uid;
  END LOOP;
END $$;

-- Step 2: Drop the old non-unique index and create a unique one
DROP INDEX IF EXISTS idx_users_firebase_uid;
CREATE UNIQUE INDEX idx_users_firebase_uid ON users(firebase_uid) WHERE firebase_uid IS NOT NULL;
