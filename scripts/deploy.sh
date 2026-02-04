#!/bin/bash

# SyncFlow VPS Deployment Script
# Run this on the VPS server

set -e

echo "=== SyncFlow API Deployment ==="

# Configuration
APP_DIR="/home/syncflow/syncflow-api"
REPO_URL="https://github.com/dpchavali1/SyncFlow-VPS.git"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}Step 1: Creating directories...${NC}"
mkdir -p $APP_DIR
mkdir -p /home/syncflow/logs

echo -e "${YELLOW}Step 2: Cloning/updating repository...${NC}"
if [ -d "$APP_DIR/.git" ]; then
    cd $APP_DIR
    git pull origin main
else
    git clone $REPO_URL $APP_DIR
    cd $APP_DIR
fi

echo -e "${YELLOW}Step 3: Installing dependencies...${NC}"
cd $APP_DIR/server
npm install

echo -e "${YELLOW}Step 4: Building TypeScript...${NC}"
npm run build

echo -e "${YELLOW}Step 5: Setting up environment...${NC}"
if [ ! -f "$APP_DIR/server/.env" ]; then
    echo -e "${YELLOW}Creating .env file from template...${NC}"
    cp .env.example .env
    echo -e "${YELLOW}⚠️  Please edit $APP_DIR/server/.env with your credentials${NC}"
fi

echo -e "${YELLOW}Step 6: Starting/restarting PM2...${NC}"
cd $APP_DIR/server
pm2 delete syncflow-api 2>/dev/null || true
pm2 start ecosystem.config.js
pm2 save

echo -e "${GREEN}=== Deployment complete ===${NC}"
echo ""
echo "Check status: pm2 status"
echo "View logs:    pm2 logs syncflow-api"
echo "Health check: curl http://localhost:3000/health"
