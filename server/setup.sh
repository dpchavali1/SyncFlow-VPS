#!/usr/bin/env bash
# =============================================================================
# SyncFlow Self-Hosted Setup Script
#
# Usage:
#   curl -fsSL https://get.syncflow.app | bash
#   -- or --
#   bash setup.sh
#
# This script sets up a complete SyncFlow self-hosted instance with:
#   - PostgreSQL 16
#   - Redis 7
#   - SyncFlow API server
#   - Caddy reverse proxy (automatic HTTPS)
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Colors & helpers
# ---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m' # No Color

print_banner() {
  echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ____                    _____ _               "
  echo " / ___| _   _ _ __   ___|  ___| | _____      __"
  echo " \\___ \\| | | | '_ \\ / __| |_  | |/ _ \\ \\ /\\ / /"
  echo "  ___) | |_| | | | | (__|  _| | | (_) \\ V  V / "
  echo " |____/ \\__, |_| |_|\\___|_|   |_|\\___/ \\_/\\_/  "
  echo "        |___/                                    "
  echo -e "${NC}"
  echo -e "${DIM}  Self-Hosted Setup v1.0${NC}"
  echo ""
}

info()    { echo -e "${BLUE}[*]${NC} $1"; }
success() { echo -e "${GREEN}[+]${NC} $1"; }
warn()    { echo -e "${YELLOW}[!]${NC} $1"; }
error()   { echo -e "${RED}[x]${NC} $1"; }
step()    { echo -e "\n${MAGENTA}${BOLD}==> $1${NC}"; }
prompt()  { echo -en "${CYAN}[?]${NC} $1"; }

die() {
  error "$1"
  exit 1
}

# ---------------------------------------------------------------------------
# Prerequisite checks
# ---------------------------------------------------------------------------
check_prerequisites() {
  step "Checking prerequisites"

  # Docker
  if command -v docker &>/dev/null; then
    local docker_version
    docker_version=$(docker --version 2>/dev/null | grep -oP '\d+\.\d+\.\d+' | head -1 || echo "unknown")
    success "Docker installed (${docker_version})"
  else
    warn "Docker is not installed."
    echo ""
    echo -e "  Install Docker using the official script:"
    echo -e "  ${BOLD}curl -fsSL https://get.docker.com | sh${NC}"
    echo ""
    prompt "Install Docker now? [Y/n] "
    read -r install_docker
    if [[ "${install_docker:-Y}" =~ ^[Yy]$ ]]; then
      info "Installing Docker..."
      curl -fsSL https://get.docker.com | sh
      sudo systemctl enable docker
      sudo systemctl start docker
      # Add current user to docker group
      if [ "$(id -u)" -ne 0 ]; then
        sudo usermod -aG docker "$USER"
        warn "Added $USER to docker group. You may need to log out and back in."
        warn "If 'docker compose' fails, run: newgrp docker"
      fi
      success "Docker installed"
    else
      die "Docker is required. Install it and re-run this script."
    fi
  fi

  # Docker Compose (v2 plugin)
  if docker compose version &>/dev/null 2>&1; then
    local compose_version
    compose_version=$(docker compose version 2>/dev/null | grep -oP '\d+\.\d+\.\d+' | head -1 || echo "unknown")
    success "Docker Compose installed (${compose_version})"
  elif command -v docker-compose &>/dev/null; then
    warn "Found legacy docker-compose. This script uses 'docker compose' (v2 plugin)."
    warn "It should still work, but upgrading is recommended."
  else
    die "Docker Compose is not installed. Install it: https://docs.docker.com/compose/install/"
  fi

  # OpenSSL (for secret generation)
  if ! command -v openssl &>/dev/null; then
    die "openssl is required but not installed. Install it with your package manager."
  fi
  success "OpenSSL available"

  # Check ports
  local port_warning=false
  for port in 80 443; do
    if ss -tlnp 2>/dev/null | grep -q ":${port} " || \
       netstat -tlnp 2>/dev/null | grep -q ":${port} " || \
       lsof -i ":${port}" &>/dev/null; then
      warn "Port ${port} is already in use. Caddy needs this port for HTTPS."
      port_warning=true
    fi
  done
  if [ "$port_warning" = false ]; then
    success "Ports 80 and 443 are available"
  else
    warn "If another web server (nginx, apache) is running, stop it or use Caddy as a layer."
    prompt "Continue anyway? [Y/n] "
    read -r continue_ports
    if [[ ! "${continue_ports:-Y}" =~ ^[Yy]$ ]]; then
      die "Free up ports 80/443 and re-run this script."
    fi
  fi
}

# ---------------------------------------------------------------------------
# Directory setup
# ---------------------------------------------------------------------------
setup_directory() {
  step "Setting up directory structure"

  INSTALL_DIR="${HOME}/syncflow"

  if [ -d "$INSTALL_DIR" ] && [ -f "$INSTALL_DIR/docker-compose.yml" ]; then
    warn "Existing SyncFlow installation found at ${INSTALL_DIR}"
    prompt "Overwrite configuration files? Existing data will be preserved. [y/N] "
    read -r overwrite
    if [[ ! "${overwrite:-N}" =~ ^[Yy]$ ]]; then
      die "Aborted. Your existing installation is untouched."
    fi
  fi

  mkdir -p "${INSTALL_DIR}"/{data/uploads,data/fcm,backups}
  success "Created ${INSTALL_DIR}/"
  info "  data/uploads/  - file uploads and media"
  info "  data/fcm/      - Firebase service account key"
  info "  backups/       - database backup storage"
}

# ---------------------------------------------------------------------------
# Generate secrets
# ---------------------------------------------------------------------------
generate_secrets() {
  step "Generating secure credentials"

  PG_PASSWORD=$(openssl rand -hex 32)
  REDIS_PASSWORD=$(openssl rand -hex 32)
  JWT_SECRET=$(openssl rand -hex 64)

  success "PostgreSQL password generated"
  success "Redis password generated"
  success "JWT secret generated (128 chars)"
}

# ---------------------------------------------------------------------------
# Prompt for configuration
# ---------------------------------------------------------------------------
prompt_configuration() {
  step "Configuration"

  # Domain
  echo ""
  info "Enter your domain name for automatic HTTPS via Caddy."
  info "Leave blank to use HTTP only (localhost / IP access)."
  echo ""
  prompt "Domain name (e.g., sync.example.com): "
  read -r DOMAIN
  DOMAIN="${DOMAIN:-}"

  if [ -n "$DOMAIN" ]; then
    success "Domain: ${DOMAIN}"
    CORS_ORIGINS="https://${DOMAIN}"
    SERVER_URL="https://${DOMAIN}"
  else
    info "No domain set - will listen on http://localhost:3000"
    CORS_ORIGINS="http://localhost:3000,http://localhost:8080"
    SERVER_URL="http://localhost:3000"
  fi

  # Admin username
  echo ""
  prompt "Admin username [admin]: "
  read -r ADMIN_USERNAME
  ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
  success "Admin username: ${ADMIN_USERNAME}"

  # Admin password
  echo ""
  info "Choose an admin password (min 8 characters)."
  while true; do
    prompt "Admin password: "
    read -rs ADMIN_PASSWORD_RAW
    echo ""
    if [ ${#ADMIN_PASSWORD_RAW} -lt 8 ]; then
      warn "Password must be at least 8 characters. Try again."
      continue
    fi
    prompt "Confirm password: "
    read -rs ADMIN_PASSWORD_CONFIRM
    echo ""
    if [ "$ADMIN_PASSWORD_RAW" != "$ADMIN_PASSWORD_CONFIRM" ]; then
      warn "Passwords do not match. Try again."
      continue
    fi
    break
  done

  info "Hashing admin password with bcrypt..."
  ADMIN_PASSWORD_HASH=$(docker run --rm node:20-alpine node -e "
    const bcrypt = require('bcrypt');
    bcrypt.hash(process.argv[1], 12).then(h => process.stdout.write(h));
  " "$ADMIN_PASSWORD_RAW" 2>/dev/null) || {
    warn "Docker bcrypt hash failed. Falling back to plaintext (change this later!)."
    ADMIN_PASSWORD_HASH="$ADMIN_PASSWORD_RAW"
  }
  success "Admin password hashed"

  # License key
  echo ""
  info "Enter your SyncFlow license key for premium features."
  info "Without a license, the server runs in community mode (1 user, 2 devices)."
  info "Get a license at: https://syncflow.app/pricing"
  echo ""
  prompt "License key (press Enter to skip): "
  read -r LICENSE_KEY
  LICENSE_KEY="${LICENSE_KEY:-}"

  if [ -n "$LICENSE_KEY" ]; then
    success "License key configured"
  else
    info "No license key - running in community mode"
  fi
}

# ---------------------------------------------------------------------------
# Write docker-compose.yml
# ---------------------------------------------------------------------------
write_docker_compose() {
  step "Writing docker-compose.yml"

  cat > "${INSTALL_DIR}/docker-compose.yml" << 'COMPOSE_EOF'
# =============================================================================
# SyncFlow Self-Hosted - Docker Compose
# Generated by setup.sh
# =============================================================================

services:
  # ---------------------------------------------------------------------------
  # PostgreSQL 16 - primary database
  # ---------------------------------------------------------------------------
  postgres:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_USER: syncflow
      POSTGRES_PASSWORD: ${PG_PASSWORD}
      POSTGRES_DB: syncflow_prod
    volumes:
      - pg_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U syncflow -d syncflow_prod"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 10s
    networks:
      - syncflow

  # ---------------------------------------------------------------------------
  # Redis 7 - caching, rate limiting, pub/sub
  # ---------------------------------------------------------------------------
  redis:
    image: redis:7-alpine
    restart: unless-stopped
    command: redis-server --requirepass ${REDIS_PASSWORD} --appendonly yes --maxmemory 256mb --maxmemory-policy allkeys-lru
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD}", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - syncflow

  # ---------------------------------------------------------------------------
  # SyncFlow API Server
  # ---------------------------------------------------------------------------
  syncflow:
    image: ghcr.io/syncflow/syncflow-api:latest
    build:
      context: .
      dockerfile: Dockerfile
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    env_file:
      - .env
    environment:
      PG_HOST: postgres
      PG_PORT: 5432
      REDIS_HOST: redis
      REDIS_PORT: 6379
    ports:
      - "3000:3000"
    volumes:
      - ./data/uploads:/app/downloads
      - ./data/fcm:/app/fcm:ro
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:3000/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 15s
    networks:
      - syncflow

  # ---------------------------------------------------------------------------
  # Caddy - reverse proxy with automatic HTTPS
  # ---------------------------------------------------------------------------
  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    depends_on:
      syncflow:
        condition: service_healthy
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    networks:
      - syncflow

volumes:
  pg_data:
  redis_data:
  caddy_data:
  caddy_config:

networks:
  syncflow:
    driver: bridge
COMPOSE_EOF

  success "docker-compose.yml written"
}

# ---------------------------------------------------------------------------
# Write Caddyfile
# ---------------------------------------------------------------------------
write_caddyfile() {
  step "Writing Caddyfile"

  if [ -n "$DOMAIN" ]; then
    cat > "${INSTALL_DIR}/Caddyfile" << CADDY_EOF
# SyncFlow Caddy Configuration - HTTPS with automatic certificates
${DOMAIN} {
    # API and WebSocket
    reverse_proxy syncflow:3000

    # Security headers
    header {
        X-Content-Type-Options nosniff
        X-Frame-Options DENY
        Referrer-Policy strict-origin-when-cross-origin
        -Server
    }

    # Request size limit (for file uploads)
    request_body {
        max_size 50MB
    }

    # Gzip compression
    encode gzip

    # Logging
    log {
        output file /data/access.log {
            roll_size 50mb
            roll_keep 5
        }
    }
}
CADDY_EOF
  else
    cat > "${INSTALL_DIR}/Caddyfile" << 'CADDY_EOF'
# SyncFlow Caddy Configuration - HTTP only (no domain configured)
:80 {
    # API and WebSocket
    reverse_proxy syncflow:3000

    # Security headers
    header {
        X-Content-Type-Options nosniff
        X-Frame-Options DENY
        Referrer-Policy strict-origin-when-cross-origin
        -Server
    }

    # Request size limit (for file uploads)
    request_body {
        max_size 50MB
    }

    # Gzip compression
    encode gzip

    # Logging
    log {
        output file /data/access.log {
            roll_size 50mb
            roll_keep 5
        }
    }
}
CADDY_EOF
  fi

  success "Caddyfile written"
}

# ---------------------------------------------------------------------------
# Write .env file
# ---------------------------------------------------------------------------
write_env_file() {
  step "Writing .env configuration"

  cat > "${INSTALL_DIR}/.env" << ENV_EOF
# =============================================================================
# SyncFlow Self-Hosted Configuration
# Generated by setup.sh on $(date -u +"%Y-%m-%d %H:%M:%S UTC")
# =============================================================================

# Server
PORT=3000
NODE_ENV=production
SELF_HOSTED=true

# PostgreSQL (container hostname used in docker-compose.yml environment override)
PG_USER=syncflow
PG_PASSWORD=${PG_PASSWORD}
PG_DATABASE=syncflow_prod

# Redis
REDIS_PASSWORD=${REDIS_PASSWORD}

# JWT
JWT_SECRET=${JWT_SECRET}
JWT_EXPIRES_IN=7d
JWT_REFRESH_EXPIRES_IN=30d

# CORS (add your web app URLs here)
CORS_ORIGINS=${CORS_ORIGINS}

# Admin credentials
ADMIN_USERNAME=${ADMIN_USERNAME}
ADMIN_PASSWORD=${ADMIN_PASSWORD_HASH}

# License (leave empty for community mode: 1 user, 2 devices)
LICENSE_KEY=${LICENSE_KEY}

# ---------------------------------------------------------------------------
# Optional: Firebase Cloud Messaging (push notifications to Android)
# Place your serviceAccountKey.json in ./data/fcm/
# ---------------------------------------------------------------------------
FCM_SERVICE_ACCOUNT_PATH=/app/fcm/serviceAccountKey.json

# ---------------------------------------------------------------------------
# Optional: Cloudflare R2 (S3-compatible storage for MMS/media)
# Without this, media is stored locally in ./data/uploads/
# ---------------------------------------------------------------------------
# R2_ENDPOINT=https://YOUR_ACCOUNT_ID.r2.cloudflarestorage.com
# R2_ACCESS_KEY_ID=
# R2_SECRET_ACCESS_KEY=
# R2_BUCKET_NAME=syncflow-files

# ---------------------------------------------------------------------------
# Optional: Cloudflare TURN (WebRTC relay for video calls)
# ---------------------------------------------------------------------------
# CLOUDFLARE_TURN_KEY_ID=
# CLOUDFLARE_TURN_API_TOKEN=

# ---------------------------------------------------------------------------
# Optional: Email notifications (Resend API)
# ---------------------------------------------------------------------------
# RESEND_API_KEY=
# ADMIN_EMAIL=

# ---------------------------------------------------------------------------
# Optional: Rate limiting overrides
# ---------------------------------------------------------------------------
# RATE_LIMIT_WINDOW_MS=60000
# RATE_LIMIT_MAX_REQUESTS=100
ENV_EOF

  chmod 600 "${INSTALL_DIR}/.env"
  success ".env written (permissions: 600)"
}

# ---------------------------------------------------------------------------
# Write backup script
# ---------------------------------------------------------------------------
write_backup_script() {
  step "Writing backup script"

  cat > "${INSTALL_DIR}/backup.sh" << 'BACKUP_EOF'
#!/usr/bin/env bash
# SyncFlow database backup script
# Run manually or add to crontab:
#   0 3 * * * /root/syncflow/backup.sh

set -euo pipefail

BACKUP_DIR="${HOME}/syncflow/backups"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILE="${BACKUP_DIR}/syncflow_${TIMESTAMP}.sql.gz"

# Load .env for PG_PASSWORD
set -a
source "${HOME}/syncflow/.env"
set +a

echo "[backup] Starting database backup..."

docker compose -f "${HOME}/syncflow/docker-compose.yml" exec -T postgres \
  pg_dump -U syncflow -d syncflow_prod --clean --if-exists | gzip > "$BACKUP_FILE"

# Keep only last 30 backups
ls -t "${BACKUP_DIR}"/syncflow_*.sql.gz 2>/dev/null | tail -n +31 | xargs -r rm --

BACKUP_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
echo "[backup] Done: ${BACKUP_FILE} (${BACKUP_SIZE})"
BACKUP_EOF

  chmod +x "${INSTALL_DIR}/backup.sh"
  success "backup.sh written"
}

# ---------------------------------------------------------------------------
# Start the stack
# ---------------------------------------------------------------------------
start_stack() {
  step "Starting SyncFlow"

  cd "${INSTALL_DIR}"

  info "Pulling Docker images..."
  docker compose pull 2>/dev/null || info "Pull skipped (will build from local Dockerfile if available)"

  info "Starting containers..."
  docker compose up -d

  # Wait for health check
  info "Waiting for SyncFlow to become healthy..."
  local retries=0
  local max_retries=30
  while [ $retries -lt $max_retries ]; do
    if docker compose ps --format json 2>/dev/null | grep -q '"Health":"healthy"' || \
       curl -sf http://localhost:3000/health >/dev/null 2>&1; then
      break
    fi
    retries=$((retries + 1))
    if [ $retries -ge $max_retries ]; then
      warn "Health check timed out after 60 seconds."
      warn "The server may still be starting. Check: docker compose -f ${INSTALL_DIR}/docker-compose.yml logs"
      return
    fi
    sleep 2
  done

  success "SyncFlow is running!"
}

# ---------------------------------------------------------------------------
# Print success message
# ---------------------------------------------------------------------------
print_success() {
  echo ""
  echo -e "${GREEN}${BOLD}"
  echo "  =============================================="
  echo "    SyncFlow is up and running!"
  echo "  =============================================="
  echo -e "${NC}"

  if [ -n "$DOMAIN" ]; then
    echo -e "  ${BOLD}Server URL:${NC}      https://${DOMAIN}"
    echo -e "  ${BOLD}Admin Panel:${NC}     https://${DOMAIN}/api/admin"
    echo -e "  ${BOLD}Health Check:${NC}    https://${DOMAIN}/health"
  else
    echo -e "  ${BOLD}Server URL:${NC}      http://localhost:3000"
    echo -e "  ${BOLD}Admin Panel:${NC}     http://localhost:3000/api/admin"
    echo -e "  ${BOLD}Health Check:${NC}    http://localhost:3000/health"
  fi

  echo -e "  ${BOLD}Admin User:${NC}      ${ADMIN_USERNAME}"
  echo ""

  if [ -z "$LICENSE_KEY" ]; then
    echo -e "  ${YELLOW}Mode:${NC}            Community (1 user, 2 devices)"
    echo -e "  ${DIM}Upgrade at https://syncflow.app/pricing${NC}"
  else
    echo -e "  ${GREEN}Mode:${NC}            Licensed"
  fi

  echo ""
  echo -e "${BOLD}  --- Next Steps ---${NC}"
  echo ""
  echo -e "  ${CYAN}1.${NC} ${BOLD}Pair your Android phone:${NC}"
  echo "     - Install SyncFlow from the Play Store"
  echo "     - Go to Settings > Server > Self-Hosted"
  echo "     - Enter server URL: ${SERVER_URL}"
  echo "     - Sign in with your account"
  echo ""
  echo -e "  ${CYAN}2.${NC} ${BOLD}Connect from Mac:${NC}"
  echo "     - Open SyncFlow on your Mac"
  echo "     - Go to Settings > Server > Self-Hosted"
  echo "     - Enter server URL: ${SERVER_URL}"
  echo "     - Scan the QR code from your Android app"
  echo ""
  echo -e "  ${CYAN}3.${NC} ${BOLD}Push notifications (optional):${NC}"
  echo "     - Create a Firebase project at https://console.firebase.google.com"
  echo "     - Download the service account JSON key"
  echo "     - Place it at: ${INSTALL_DIR}/data/fcm/serviceAccountKey.json"
  echo "     - Restart: docker compose -f ${INSTALL_DIR}/docker-compose.yml restart syncflow"
  echo ""
  echo -e "  ${CYAN}4.${NC} ${BOLD}Backups:${NC}"
  echo "     - Manual:  bash ${INSTALL_DIR}/backup.sh"
  echo "     - Auto:    Add to crontab (daily at 3 AM):"
  echo "                0 3 * * * ${INSTALL_DIR}/backup.sh"
  echo ""
  echo -e "${BOLD}  --- Useful Commands ---${NC}"
  echo ""
  echo "  View logs:     docker compose -f ${INSTALL_DIR}/docker-compose.yml logs -f syncflow"
  echo "  Restart:       docker compose -f ${INSTALL_DIR}/docker-compose.yml restart"
  echo "  Stop:          docker compose -f ${INSTALL_DIR}/docker-compose.yml down"
  echo "  Update:        docker compose -f ${INSTALL_DIR}/docker-compose.yml pull && docker compose -f ${INSTALL_DIR}/docker-compose.yml up -d"
  echo "  DB shell:      docker compose -f ${INSTALL_DIR}/docker-compose.yml exec postgres psql -U syncflow -d syncflow_prod"
  echo ""
  echo -e "${DIM}  Installation directory: ${INSTALL_DIR}${NC}"
  echo -e "${DIM}  Configuration file:     ${INSTALL_DIR}/.env${NC}"
  echo ""
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
  print_banner

  info "This script will set up a self-hosted SyncFlow instance."
  info "It will create files in ~/syncflow/ and start Docker containers."
  echo ""
  prompt "Continue? [Y/n] "
  read -r proceed
  if [[ ! "${proceed:-Y}" =~ ^[Yy]$ ]]; then
    info "Aborted."
    exit 0
  fi

  check_prerequisites
  setup_directory
  generate_secrets
  prompt_configuration
  write_docker_compose
  write_caddyfile
  write_env_file
  write_backup_script
  start_stack
  print_success
}

main "$@"
