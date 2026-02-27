#!/bin/bash
# =============================================================================
# SyncFlow Release Script
# =============================================================================
# Builds Android APK + macOS DMG, uploads to VPS, and updates download page.
#
# Usage: ./scripts/release.sh <version>
#
# Examples:
#   ./scripts/release.sh 1.0.0
#   ./scripts/release.sh 1.1.0
#   ./scripts/release.sh 2.0.0-beta
#
# What it does:
#   1. Builds Android release APK  (./gradlew assembleRelease)
#   2. Builds macOS release app    (xcodebuild archive + export)
#   3. Creates DMG from .app       (hdiutil)
#   4. Computes SHA-256 checksums
#   5. Uploads APK + DMG to VPS    (scp)
#   6. Updates web/app/download/page.tsx with new sizes + checksums
#
# Prerequisites:
#   - Android SDK configured (ANDROID_HOME or local.properties)
#   - Xcode with valid signing identity
#   - SSH access to VPS (root@5.78.188.206)
# =============================================================================

set -e

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VPS_USER="root"
VPS_IP="5.78.188.206"
VPS_DOWNLOADS="/home/syncflow/syncflow-api/server/downloads"
DOWNLOAD_PAGE="$ROOT_DIR/web/app/download/page.tsx"

XCODE_WORKSPACE="$ROOT_DIR/SyncFlowMac/SyncFlowMac.xcworkspace"
XCODE_SCHEME="SyncFlowMac"
ARCHIVE_PATH="/tmp/SyncFlowMac.xcarchive"
EXPORT_PATH="/tmp/SyncFlowMac-export"
DMG_STAGING="/tmp/dmg-staging"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
VERSION="$1"

if [ -z "$VERSION" ]; then
    echo -e "${RED}Error: Version number required${NC}"
    echo "Usage: ./scripts/release.sh <version>"
    echo "Example: ./scripts/release.sh 1.0.0"
    exit 1
fi

APK_NAME="SyncFlow-${VERSION}.apk"
DMG_NAME="SyncFlow-${VERSION}.dmg"

echo -e "${GREEN}=== SyncFlow Release v${VERSION} ===${NC}"
echo ""

# ---------------------------------------------------------------------------
# Step 1: Build Android APK
# ---------------------------------------------------------------------------
echo -e "${YELLOW}[1/6] Building Android APK...${NC}"
cd "$ROOT_DIR/android"
./gradlew assembleRelease

APK_SOURCE="$ROOT_DIR/android/app/build/outputs/apk/release/app-release.apk"
APK_DEST="/tmp/$APK_NAME"

if [ ! -f "$APK_SOURCE" ]; then
    echo -e "${RED}Error: APK not found at $APK_SOURCE${NC}"
    exit 1
fi

cp "$APK_SOURCE" "$APK_DEST"
echo -e "${GREEN}  APK built: $APK_DEST${NC}"

# ---------------------------------------------------------------------------
# Step 2: Build macOS app
# ---------------------------------------------------------------------------
echo -e "${YELLOW}[2/6] Building macOS app...${NC}"

# Clean previous build artifacts
rm -rf "$ARCHIVE_PATH" "$EXPORT_PATH"

# Archive (allow failure — falls back to direct build if provisioning profile unavailable)
ARCHIVE_OK=false
if xcodebuild archive \
    -workspace "$XCODE_WORKSPACE" \
    -scheme "$XCODE_SCHEME" \
    -configuration Release \
    -archivePath "$ARCHIVE_PATH" \
    -allowProvisioningUpdates \
    -quiet 2>/dev/null; then
    ARCHIVE_OK=true
fi

# Export (direct copy from archive since it's a Developer ID / direct distribution app)
APP_PATH=""
if [ "$ARCHIVE_OK" = true ]; then
    if [ -d "$ARCHIVE_PATH/Products/Applications/SyncFlowMac.app" ]; then
        APP_PATH="$ARCHIVE_PATH/Products/Applications/SyncFlowMac.app"
    elif [ -d "$ARCHIVE_PATH/Products/usr/local/bin/SyncFlowMac.app" ]; then
        APP_PATH="$ARCHIVE_PATH/Products/usr/local/bin/SyncFlowMac.app"
    fi
fi

if [ -z "$APP_PATH" ] || [ ! -d "$APP_PATH" ]; then
    # Fallback: build without archive for development signing
    echo -e "${YELLOW}  Archive not available, building directly...${NC}"
    xcodebuild build \
        -workspace "$XCODE_WORKSPACE" \
        -scheme "$XCODE_SCHEME" \
        -configuration Release \
        -derivedDataPath "$ROOT_DIR/SyncFlowMac/build" \
        -quiet
    APP_PATH="$ROOT_DIR/SyncFlowMac/build/Build/Products/Release/SyncFlowMac.app"
fi

if [ ! -d "$APP_PATH" ]; then
    echo -e "${RED}Error: SyncFlowMac.app not found${NC}"
    exit 1
fi

echo -e "${GREEN}  macOS app built: $APP_PATH${NC}"

# ---------------------------------------------------------------------------
# Step 3: Create DMG
# ---------------------------------------------------------------------------
echo -e "${YELLOW}[3/6] Creating DMG...${NC}"
DMG_DEST="/tmp/$DMG_NAME"

# Clean staging
rm -rf "$DMG_STAGING"
mkdir -p "$DMG_STAGING"

# Copy app and create Applications symlink
cp -R "$APP_PATH" "$DMG_STAGING/SyncFlowMac.app"
ln -s /Applications "$DMG_STAGING/Applications"

# Remove any existing DMG
rm -f "$DMG_DEST"

# Create DMG
hdiutil create \
    -volname "SyncFlow" \
    -srcfolder "$DMG_STAGING" \
    -ov \
    -format UDZO \
    "$DMG_DEST"

# Clean staging
rm -rf "$DMG_STAGING"

echo -e "${GREEN}  DMG created: $DMG_DEST${NC}"

# ---------------------------------------------------------------------------
# Step 4: Compute checksums and sizes
# ---------------------------------------------------------------------------
echo -e "${YELLOW}[4/6] Computing checksums...${NC}"

APK_SHA256=$(shasum -a 256 "$APK_DEST" | awk '{print $1}')
DMG_SHA256=$(shasum -a 256 "$DMG_DEST" | awk '{print $1}')

# File sizes in MB (human-readable, rounded)
APK_SIZE_BYTES=$(stat -f%z "$APK_DEST")
DMG_SIZE_BYTES=$(stat -f%z "$DMG_DEST")
APK_SIZE_MB=$(( (APK_SIZE_BYTES + 524288) / 1048576 ))  # Round to nearest MB
DMG_SIZE_MB=$(( (DMG_SIZE_BYTES + 524288) / 1048576 ))

echo -e "  APK: ${APK_SIZE_MB} MB  SHA-256: ${APK_SHA256}"
echo -e "  DMG: ${DMG_SIZE_MB} MB  SHA-256: ${DMG_SHA256}"

# ---------------------------------------------------------------------------
# Step 5: Upload to VPS
# ---------------------------------------------------------------------------
echo -e "${YELLOW}[5/6] Uploading to VPS...${NC}"

scp "$APK_DEST" "${VPS_USER}@${VPS_IP}:${VPS_DOWNLOADS}/${APK_NAME}"
scp "$DMG_DEST" "${VPS_USER}@${VPS_IP}:${VPS_DOWNLOADS}/${DMG_NAME}"

# Verify uploads
ssh "${VPS_USER}@${VPS_IP}" "ls -lh ${VPS_DOWNLOADS}/${APK_NAME} ${VPS_DOWNLOADS}/${DMG_NAME}"

echo -e "${GREEN}  Uploaded to VPS${NC}"

# ---------------------------------------------------------------------------
# Step 6: Update download page
# ---------------------------------------------------------------------------
echo -e "${YELLOW}[6/6] Updating download page...${NC}"

if [ ! -f "$DOWNLOAD_PAGE" ]; then
    echo -e "${RED}Error: Download page not found at $DOWNLOAD_PAGE${NC}"
    exit 1
fi

# Use sed to update the values in the download page
# macOS
sed -i '' "s|const version = '.*'|const version = '${VERSION}'|" "$DOWNLOAD_PAGE"
sed -i '' "s|const macFileSize = '.*'|const macFileSize = '${DMG_SIZE_MB} MB'|" "$DOWNLOAD_PAGE"
sed -i '' "s|const apkFileSize = '.*'|const apkFileSize = '${APK_SIZE_MB} MB'|" "$DOWNLOAD_PAGE"
sed -i '' "s|const macSha256 = '.*'|const macSha256 = '${DMG_SHA256}'|" "$DOWNLOAD_PAGE"
sed -i '' "s|const apkSha256 = '.*'|const apkSha256 = '${APK_SHA256}'|" "$DOWNLOAD_PAGE"

echo -e "${GREEN}  Download page updated${NC}"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo -e "${GREEN}=== Release v${VERSION} Complete ===${NC}"
echo ""
echo -e "${CYAN}Artifacts:${NC}"
echo "  APK: ${VPS_DOWNLOADS}/${APK_NAME} (${APK_SIZE_MB} MB)"
echo "  DMG: ${VPS_DOWNLOADS}/${DMG_NAME} (${DMG_SIZE_MB} MB)"
echo ""
echo -e "${CYAN}SHA-256 Checksums:${NC}"
echo "  APK: ${APK_SHA256}"
echo "  DMG: ${DMG_SHA256}"
echo ""
echo -e "${CYAN}Download URL:${NC}"
echo "  https://api.sfweb.app/downloads/${APK_NAME}"
echo "  https://api.sfweb.app/downloads/${DMG_NAME}"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo "  1. Review changes: git diff web/app/download/page.tsx"
echo "  2. Commit & deploy: git add -A && git commit -m 'Release v${VERSION}'"
echo "  3. Deploy web:      cd web && vercel --prod"
