#!/bin/bash
# =============================================================================
# SyncFlow VPS Deployment Script
# =============================================================================
# Usage: ./deploy.sh [options]
#
# Options:
#   (no options)      Full deploy: build + upload + restart
#   --restart         Only restart the server (no build/upload)
#   --quick           Skip npm install on VPS (faster for code-only changes)
#   --logs            Show server logs after deployment
#   --status          Just show server status
#
# Examples:
#   ./deploy.sh              # Full deployment
#   ./deploy.sh --quick      # Deploy without npm install (faster)
#   ./deploy.sh --restart    # Just restart the server
#   ./deploy.sh --status     # Check server status
#   ./deploy.sh --logs       # Deploy and show logs
# =============================================================================

set -e

# Configuration
VPS_IP="5.78.188.206"
VPS_USER="root"
DEPLOY_PATH="/home/syncflow/syncflow-api/server"
PM2_USER="syncflow"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# Parse options
DO_BUILD=true
DO_UPLOAD=true
DO_NPM_INSTALL=true
DO_RESTART=true
SHOW_LOGS=false
STATUS_ONLY=false

for arg in "$@"; do
    case $arg in
        --restart)
            DO_BUILD=false
            DO_UPLOAD=false
            DO_NPM_INSTALL=false
            ;;
        --quick)
            DO_NPM_INSTALL=false
            ;;
        --logs)
            SHOW_LOGS=true
            ;;
        --status)
            STATUS_ONLY=true
            ;;
    esac
done

# Helper function for SSH commands as syncflow user
run_on_vps() {
    ssh ${VPS_USER}@${VPS_IP} "su - ${PM2_USER} -c 'source ~/.nvm/nvm.sh && $1'"
}

# Status only
if [ "$STATUS_ONLY" = true ]; then
    echo -e "${CYAN}=== Server Status ===${NC}"
    run_on_vps "pm2 status"
    echo ""
    run_on_vps "pm2 logs syncflow-api --lines 20 --nostream" 2>/dev/null || true
    exit 0
fi

echo -e "${GREEN}=== SyncFlow VPS Deployment ===${NC}"
echo -e "Target: ${VPS_USER}@${VPS_IP}:${DEPLOY_PATH}"
echo ""

# Step 1: Build
if [ "$DO_BUILD" = true ]; then
    echo -e "${YELLOW}[1/4] Building TypeScript...${NC}"
    npm run build
    echo -e "${GREEN}✓ Build complete${NC}"
else
    echo -e "${YELLOW}[1/4] Skipping build${NC}"
fi

# Step 2: Upload
if [ "$DO_UPLOAD" = true ]; then
    echo -e "${YELLOW}[2/4] Uploading to VPS...${NC}"

    # Use rsync for faster incremental uploads
    rsync -avz --delete \
        --exclude 'node_modules' \
        ./dist/ \
        ${VPS_USER}@${VPS_IP}:${DEPLOY_PATH}/dist/

    # Also sync package files
    rsync -avz \
        ./package.json \
        ./package-lock.json \
        ${VPS_USER}@${VPS_IP}:${DEPLOY_PATH}/

    # Sync migrations if they exist
    if [ -d "./migrations" ]; then
        rsync -avz ./migrations/ ${VPS_USER}@${VPS_IP}:${DEPLOY_PATH}/migrations/
    fi

    # Fix ownership so syncflow user can run npm install and server
    ssh ${VPS_USER}@${VPS_IP} "chown -R ${PM2_USER}:${PM2_USER} ${DEPLOY_PATH}"

    echo -e "${GREEN}✓ Upload complete${NC}"
else
    echo -e "${YELLOW}[2/4] Skipping upload${NC}"
fi

# Step 3: Install dependencies
if [ "$DO_NPM_INSTALL" = true ]; then
    echo -e "${YELLOW}[3/4] Installing dependencies on VPS...${NC}"
    run_on_vps "cd ${DEPLOY_PATH} && npm install --production"
    echo -e "${GREEN}✓ Dependencies installed${NC}"
else
    echo -e "${YELLOW}[3/4] Skipping npm install${NC}"
fi

# Step 4: Restart
if [ "$DO_RESTART" = true ]; then
    echo -e "${YELLOW}[4/4] Restarting server...${NC}"
    run_on_vps "pm2 restart syncflow-api"
    echo -e "${GREEN}✓ Server restarted${NC}"
fi

echo ""
echo -e "${GREEN}=== Deployment Complete ===${NC}"

# Show status
echo ""
echo -e "${CYAN}Server Status:${NC}"
run_on_vps "pm2 status"

# Show logs if requested
if [ "$SHOW_LOGS" = true ]; then
    echo ""
    echo -e "${CYAN}Recent Logs:${NC}"
    run_on_vps "pm2 logs syncflow-api --lines 30 --nostream"
fi
