#!/usr/bin/env bash
# =============================================================================
# SyncFlow Docker Entrypoint
# Waits for PostgreSQL, applies schema + migrations, then starts the app.
# =============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration (reads the same env vars the Node app uses)
# ---------------------------------------------------------------------------
DB_HOST="${PG_HOST:-postgres}"
DB_PORT="${PG_PORT:-5432}"
DB_USER="${PG_USER:-syncflow}"
DB_NAME="${PG_DATABASE:-syncflow_prod}"
export PGPASSWORD="${PG_PASSWORD:?PG_PASSWORD is required}"

PSQL="psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME"

# ---------------------------------------------------------------------------
# 1. Wait for PostgreSQL to accept connections
# ---------------------------------------------------------------------------
echo "[entrypoint] Waiting for PostgreSQL at $DB_HOST:$DB_PORT ..."

MAX_RETRIES=30
RETRY=0
until pg_isready -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -q 2>/dev/null; do
    RETRY=$((RETRY + 1))
    if [ "$RETRY" -ge "$MAX_RETRIES" ]; then
        echo "[entrypoint] ERROR: PostgreSQL not ready after ${MAX_RETRIES} attempts. Exiting."
        exit 1
    fi
    echo "[entrypoint]   ...not ready yet (attempt $RETRY/$MAX_RETRIES)"
    sleep 2
done

echo "[entrypoint] PostgreSQL is ready."

# ---------------------------------------------------------------------------
# 2. Create _migrations tracking table (idempotent)
# ---------------------------------------------------------------------------
$PSQL -c "
CREATE TABLE IF NOT EXISTS _migrations (
    id SERIAL PRIMARY KEY,
    filename VARCHAR(255) NOT NULL UNIQUE,
    applied_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
" >/dev/null 2>&1

# ---------------------------------------------------------------------------
# 3. Apply schema.sql on first run (check if 'users' table exists)
# ---------------------------------------------------------------------------
TABLE_EXISTS=$($PSQL -tAc "
    SELECT EXISTS (
        SELECT FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'users'
    );
")

if [ "$TABLE_EXISTS" = "f" ]; then
    echo "[entrypoint] Fresh database detected. Applying schema.sql ..."
    $PSQL -f /app/schema.sql
    echo "[entrypoint] Schema applied."

    # Mark all existing migrations as already applied (schema.sql is the baseline)
    for migration_file in /app/migrations/*.sql; do
        [ -f "$migration_file" ] || continue
        BASENAME=$(basename "$migration_file")
        $PSQL -c "INSERT INTO _migrations (filename) VALUES ('$BASENAME') ON CONFLICT DO NOTHING;" >/dev/null 2>&1
    done
    echo "[entrypoint] All migrations marked as applied (baseline from schema.sql)."
else
    echo "[entrypoint] Existing database detected. Skipping schema.sql."
fi

# ---------------------------------------------------------------------------
# 4. Apply pending migrations in order
# ---------------------------------------------------------------------------
MIGRATION_COUNT=0

for migration_file in $(ls /app/migrations/*.sql 2>/dev/null | sort); do
    BASENAME=$(basename "$migration_file")

    ALREADY_APPLIED=$($PSQL -tAc "SELECT EXISTS (SELECT 1 FROM _migrations WHERE filename = '$BASENAME');")

    if [ "$ALREADY_APPLIED" = "f" ]; then
        echo "[entrypoint] Applying migration: $BASENAME ..."
        if $PSQL -f "$migration_file"; then
            $PSQL -c "INSERT INTO _migrations (filename) VALUES ('$BASENAME');" >/dev/null 2>&1
            echo "[entrypoint]   -> $BASENAME applied."
            MIGRATION_COUNT=$((MIGRATION_COUNT + 1))
        else
            echo "[entrypoint] ERROR: Migration $BASENAME failed. Aborting."
            exit 1
        fi
    fi
done

if [ "$MIGRATION_COUNT" -gt 0 ]; then
    echo "[entrypoint] Applied $MIGRATION_COUNT migration(s)."
else
    echo "[entrypoint] No pending migrations."
fi

# ---------------------------------------------------------------------------
# 5. Hand off to CMD (node dist/index.js)
# ---------------------------------------------------------------------------
echo "[entrypoint] Starting SyncFlow API server ..."
exec "$@"
