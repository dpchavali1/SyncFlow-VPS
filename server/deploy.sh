#!/bin/bash
# SyncFlow VPS Deployment Script
# Usage: ./deploy.sh [VPS_IP]

set -e

VPS_IP="${1:-5.78.188.206}"
VPS_USER="root"
DEPLOY_PATH="/home/syncflow/syncflow-api/server"

echo "=== SyncFlow VPS Deployment ==="
echo "Target: ${VPS_USER}@${VPS_IP}:${DEPLOY_PATH}"
echo ""

# Build
echo "[1/5] Building TypeScript..."
npm run build

# Create deployment package
echo "[2/5] Creating deployment package..."
rm -rf /tmp/syncflow-deploy
mkdir -p /tmp/syncflow-deploy
cp -r dist /tmp/syncflow-deploy/
cp package.json /tmp/syncflow-deploy/
cp package-lock.json /tmp/syncflow-deploy/ 2>/dev/null || true
mkdir -p /tmp/syncflow-deploy/migrations
cp migrations/*.sql /tmp/syncflow-deploy/migrations/

# Upload to VPS
echo "[3/5] Uploading to VPS..."
scp -r /tmp/syncflow-deploy/* ${VPS_USER}@${VPS_IP}:${DEPLOY_PATH}/

# Install dependencies on VPS (as syncflow user with proper PATH)
echo "[4/5] Installing dependencies on VPS..."
ssh ${VPS_USER}@${VPS_IP} "su - syncflow -c 'cd ${DEPLOY_PATH} && source ~/.nvm/nvm.sh && npm install --production'"

# Restart PM2 (as syncflow user)
echo "[5/5] Restarting PM2..."
ssh ${VPS_USER}@${VPS_IP} "su - syncflow -c 'source ~/.nvm/nvm.sh && pm2 restart all'"

echo ""
echo "=== Deployment Complete ==="
echo ""
echo "IMPORTANT: Run the database migration on VPS:"
echo "  ssh ${VPS_USER}@${VPS_IP}"
echo "  sudo -u postgres psql -d syncflow_prod -f ${DEPLOY_PATH}/migrations/001_add_missing_tables.sql"
echo ""
echo "IMPORTANT: Set R2 environment variables in /home/syncflow/.env if not already set:"
echo "  R2_ENDPOINT=https://xxx.r2.cloudflarestorage.com"
echo "  R2_ACCESS_KEY_ID=your_access_key"
echo "  R2_SECRET_ACCESS_KEY=your_secret_key"
echo "  R2_BUCKET_NAME=syncflow-files"
