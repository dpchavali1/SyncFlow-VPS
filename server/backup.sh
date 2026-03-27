#!/usr/bin/env bash
# =============================================================================
# SyncFlow Self-Hosted Backup Script
# =============================================================================
# Creates a timestamped backup of the PostgreSQL database and uploads volume,
# compresses everything into a single tar.gz, and prunes backups older than
# 30 days.
#
# Usage (from the docker host, in the server/ directory):
#   ./backup.sh
#
# Automate with cron (daily at 2 AM):
#   0 2 * * * cd /path/to/server && ./backup.sh >> /var/log/syncflow-backup.log 2>&1
# =============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="${SYNCFLOW_BACKUP_DIR:-$SCRIPT_DIR/backups}"
RETENTION_DAYS=30
TIMESTAMP=$(date +"%Y-%m-%d_%H%M%S")
BACKUP_NAME="syncflow-backup-${TIMESTAMP}"
WORK_DIR="${BACKUP_DIR}/${BACKUP_NAME}"

# Compose project name detection (docker compose uses directory name by default)
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"

# Load .env to get PG_PASSWORD
if [ -f "${SCRIPT_DIR}/.env" ]; then
    # shellcheck disable=SC1091
    set -a; source "${SCRIPT_DIR}/.env"; set +a
fi

PG_PASSWORD="${PG_PASSWORD:?PG_PASSWORD not set. Source .env or export it.}"

# ---------------------------------------------------------------------------
# Pre-flight checks
# ---------------------------------------------------------------------------
if ! command -v docker &>/dev/null; then
    echo "[backup] ERROR: docker is not installed or not in PATH."
    exit 1
fi

if ! docker compose -f "$COMPOSE_FILE" ps --status running postgres 2>/dev/null | grep -q postgres; then
    echo "[backup] ERROR: PostgreSQL container is not running."
    exit 1
fi

mkdir -p "$WORK_DIR"

echo "[backup] Starting backup: $BACKUP_NAME"
echo "[backup] Backup directory: $BACKUP_DIR"

# ---------------------------------------------------------------------------
# 1. Dump PostgreSQL
# ---------------------------------------------------------------------------
echo "[backup] Dumping PostgreSQL database ..."

docker compose -f "$COMPOSE_FILE" exec -T postgres \
    pg_dump -U syncflow -d syncflow_prod \
    --no-owner --no-acl --clean --if-exists \
    > "${WORK_DIR}/database.sql"

DB_SIZE=$(du -sh "${WORK_DIR}/database.sql" | cut -f1)
echo "[backup]   Database dump: ${DB_SIZE}"

# ---------------------------------------------------------------------------
# 2. Copy downloads volume
# ---------------------------------------------------------------------------
echo "[backup] Copying downloads volume ..."

# Copy from the named volume via a temporary container
docker run --rm \
    -v syncflow-downloads:/source:ro \
    -v "${WORK_DIR}:/backup" \
    alpine:3.19 \
    sh -c "if [ -d /source ] && [ \"\$(ls -A /source 2>/dev/null)\" ]; then cp -a /source /backup/downloads; else mkdir -p /backup/downloads; fi"

DOWNLOADS_SIZE=$(du -sh "${WORK_DIR}/downloads" 2>/dev/null | cut -f1 || echo "0")
echo "[backup]   Downloads: ${DOWNLOADS_SIZE}"

# ---------------------------------------------------------------------------
# 3. Compress into tar.gz
# ---------------------------------------------------------------------------
echo "[backup] Compressing ..."

tar -czf "${BACKUP_DIR}/${BACKUP_NAME}.tar.gz" \
    -C "$BACKUP_DIR" \
    "$BACKUP_NAME"

ARCHIVE_SIZE=$(du -sh "${BACKUP_DIR}/${BACKUP_NAME}.tar.gz" | cut -f1)
echo "[backup]   Archive: ${ARCHIVE_SIZE}"

# Clean up uncompressed working directory
rm -rf "$WORK_DIR"

# ---------------------------------------------------------------------------
# 4. Prune old backups (retain last N days)
# ---------------------------------------------------------------------------
echo "[backup] Pruning backups older than ${RETENTION_DAYS} days ..."

PRUNED=0
find "$BACKUP_DIR" -name "syncflow-backup-*.tar.gz" -type f -mtime +${RETENTION_DAYS} -print0 | \
    while IFS= read -r -d '' old_backup; do
        echo "[backup]   Removing: $(basename "$old_backup")"
        rm -f "$old_backup"
        PRUNED=$((PRUNED + 1))
    done

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
TOTAL_BACKUPS=$(find "$BACKUP_DIR" -name "syncflow-backup-*.tar.gz" -type f | wc -l | tr -d ' ')
TOTAL_SIZE=$(du -sh "$BACKUP_DIR" 2>/dev/null | cut -f1 || echo "0")

echo "[backup] Complete."
echo "[backup]   Archive: ${BACKUP_DIR}/${BACKUP_NAME}.tar.gz (${ARCHIVE_SIZE})"
echo "[backup]   Total backups: ${TOTAL_BACKUPS} (${TOTAL_SIZE} total)"
