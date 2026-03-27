# Self-Hosting SyncFlow

Run your own SyncFlow server. Your messages, your infrastructure, your rules.

SyncFlow self-hosting gives you a private sync server that relays SMS, MMS, calls, contacts, clipboard, notifications, and files between your Android phone and your Mac or web browser. Nothing touches third-party servers unless you explicitly configure it.

---

## Table of Contents

- [Getting Started](#getting-started)
- [Architecture Overview](#architecture-overview)
- [One-Command Setup](#one-command-setup)
- [Manual Setup](#manual-setup)
- [Configuration Reference](#configuration-reference)
- [Connecting Your Devices](#connecting-your-devices)
- [Push Notifications Setup](#push-notifications-setup)
- [HTTPS Setup](#https-setup)
- [Video Calling (TURN Server)](#video-calling-turn-server)
- [Backups](#backups)
- [Updating](#updating)
- [Troubleshooting](#troubleshooting)
- [Licensing](#licensing)

---

## Getting Started

### Prerequisites

- A server or VPS with a public IP address (any provider: Hetzner, DigitalOcean, Linode, Oracle Cloud free tier, a Raspberry Pi on your home network, etc.)
- Docker and Docker Compose installed ([install guide](https://docs.docker.com/engine/install/))
- A domain name pointed at your server (optional but recommended for HTTPS)

### Hardware Requirements

| Tier | RAM | CPU | Storage | Users |
|------|-----|-----|---------|-------|
| Minimum | 512 MB | 1 vCPU | 5 GB | 1 |
| Recommended | 1 GB | 1 vCPU | 20 GB | 1-5 |
| Family/Team | 2 GB | 2 vCPU | 50 GB | 5-10 |

SyncFlow is lightweight. PostgreSQL and Redis are the main memory consumers. The Node.js API server idles at around 50 MB.

---

## Architecture Overview

```
+------------------+          +----------------------------+          +----------------+
|                  |          |    SyncFlow Server         |          |                |
|  Android Phone   | <------> |                            | <------> |   Mac App      |
|  (SyncFlow app)  |   HTTPS  |  +---------+  +---------+ |   WSS    |   (SyncFlow)   |
|                  |   + WS   |  | Node.js |  | WebSocket| |          |                |
+------------------+          |  | API     |  | Server   | |          +----------------+
                              |  +---------+  +---------+ |
                              |       |            |       |          +----------------+
                              |  +---------+  +---------+ |          |                |
                              |  |PostgreSQL|  |  Redis  | |  <-----> |   Web App      |
                              |  | messages |  |  cache  | |   HTTPS  |   (Browser)    |
                              |  | contacts |  |  rate   | |          |                |
                              |  | calls    |  |  limit  | |          +----------------+
                              |  +---------+  +---------+ |
                              |       |                    |
                              |  +---------+               |
                              |  |  Files  |  (local or    |
                              |  |  Store  |   R2/S3)      |
                              |  +---------+               |
                              +----------------------------+
```

**Components:**

| Component | Purpose | Required |
|-----------|---------|----------|
| Node.js API | REST API + WebSocket for real-time sync | Yes |
| PostgreSQL 16 | Persistent storage for messages, contacts, calls, devices | Yes |
| Redis 7 | Rate limiting, caching, session state | Yes |
| Caddy | Automatic HTTPS reverse proxy | Optional (recommended) |
| Cloudflare R2 / S3 | Cloud storage for MMS attachments and file transfers | Optional (local storage works) |
| Firebase Cloud Messaging | Push notifications to Android and Mac when app is backgrounded | Optional (recommended) |
| TURN Server | NAT traversal relay for video calls | Optional |

---

## One-Command Setup

The setup script installs Docker (if needed), generates secure passwords, creates the configuration, and starts all services.

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/dpchavali1/SyncFlow-VPS/main/server/setup.sh)
```

The script will:

1. Install Docker and Docker Compose if not present
2. Create a `syncflow` directory with all required files
3. Generate random passwords for PostgreSQL, Redis, JWT, and the admin account
4. Ask for your domain name (optional -- press Enter to skip for IP-only access)
5. Start all services with `docker compose up -d`
6. Run database migrations automatically
7. Print your server URL and admin credentials

After setup, skip to [Connecting Your Devices](#connecting-your-devices).

---

## Manual Setup

If you prefer to set things up yourself, follow these steps.

### Step 1: Clone the Repository

```bash
git clone https://github.com/dpchavali1/SyncFlow-VPS.git
cd SyncFlow-VPS/server
```

### Step 2: Create Your Environment File

```bash
cp .env.example .env
```

Open `.env` and fill in the required values. At minimum, you need:

```bash
# Generate a secure JWT secret (required)
JWT_SECRET=$(openssl rand -base64 32)

# Generate a bcrypt admin password hash (required)
# Replace 'your-admin-password' with something strong
ADMIN_PASSWORD=$(node -e "require('bcrypt').hash('your-admin-password',12).then(console.log)")

# Database credentials
PG_PASSWORD=$(openssl rand -base64 16)
REDIS_PASSWORD=$(openssl rand -base64 16)
```

### Step 3: Start with Docker Compose

```bash
docker compose up -d
```

This starts PostgreSQL, Redis, and the SyncFlow API server. The database schema and all migrations are applied automatically on first startup.

### Step 4: Verify It Works

```bash
curl http://localhost:3000/health
```

You should see:

```json
{
  "status": "healthy",
  "services": {
    "database": "up",
    "redis": "up"
  }
}
```

### Step 5 (Optional): Set Up HTTPS

See the [HTTPS Setup](#https-setup) section below.

---

## Configuration Reference

All configuration is done through environment variables in your `.env` file.

### Server

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `PORT` | No | `3000` | HTTP and WebSocket listen port |
| `NODE_ENV` | No | `production` | Environment mode (`production` or `development`) |
| `CORS_ORIGINS` | No | `http://localhost:3000,...` | Comma-separated list of allowed CORS origins. Add your web app URL here. |

### Database (PostgreSQL)

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DATABASE_URL` | No | -- | Full PostgreSQL connection string. If set, overrides individual PG_* variables. |
| `PG_HOST` | No | `localhost` | PostgreSQL hostname. Use `postgres` when running in Docker Compose. |
| `PG_PORT` | No | `5432` | PostgreSQL port |
| `PG_USER` | No | `syncflow` | PostgreSQL username |
| `PG_PASSWORD` | **Yes** | -- | PostgreSQL password |
| `PG_DATABASE` | No | `syncflow_prod` | PostgreSQL database name |

### Redis

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `REDIS_URL` | No | -- | Full Redis connection string. If set, overrides individual REDIS_* variables. |
| `REDIS_HOST` | No | `localhost` | Redis hostname. Use `redis` when running in Docker Compose. |
| `REDIS_PORT` | No | `6379` | Redis port |
| `REDIS_PASSWORD` | **Yes** | -- | Redis password |

### Authentication (JWT)

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `JWT_SECRET` | **Yes** | -- | Secret key for signing JWT tokens. Generate with `openssl rand -base64 32`. |
| `JWT_EXPIRES_IN` | No | `7d` | Access token expiration (e.g., `7d`, `24h`, `1h`) |
| `JWT_REFRESH_EXPIRES_IN` | No | `30d` | Refresh token expiration |

### Admin

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `ADMIN_USERNAME` | No | `admin` | Admin panel login username |
| `ADMIN_PASSWORD` | **Yes** | -- | Admin panel password. **Must be a bcrypt hash.** Generate: `node -e "require('bcrypt').hash('yourpass',12).then(console.log)"` |
| `ADMIN_API_KEY` | No | -- | Optional API key for programmatic admin access |

### Rate Limiting

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `RATE_LIMIT_WINDOW_MS` | No | `60000` | Rate limit window in milliseconds (default: 1 minute) |
| `RATE_LIMIT_MAX_REQUESTS` | No | `100` | Maximum requests per window per IP |

### Storage (Cloudflare R2 / S3-Compatible)

These are optional. Without them, MMS attachments and file transfers use local disk storage.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `R2_ENDPOINT` | No | -- | S3-compatible endpoint URL (e.g., `https://<account_id>.r2.cloudflarestorage.com`) |
| `R2_ACCESS_KEY_ID` | No | -- | S3 access key ID |
| `R2_SECRET_ACCESS_KEY` | No | -- | S3 secret access key |
| `R2_BUCKET_NAME` | No | `syncflow-files` | Bucket name for file storage |

### Push Notifications (Firebase Cloud Messaging)

Optional but recommended. Without FCM, messages only sync when the app is open and connected via WebSocket.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `FCM_SERVICE_ACCOUNT_PATH` | No | -- | Path to Firebase service account JSON file. Place it at `data/fcm/serviceAccountKey.json` in Docker. |

### Video Calling (TURN Server)

Optional. Required only for video calls that need NAT traversal.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `CLOUDFLARE_TURN_KEY_ID` | No | -- | Cloudflare TURN key ID |
| `CLOUDFLARE_TURN_API_TOKEN` | No | -- | Cloudflare TURN API token |

### Email Notifications

Optional. Used for admin alerts and account recovery emails.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `RESEND_API_KEY` | No | -- | [Resend](https://resend.com) API key for sending transactional email |
| `ADMIN_EMAIL` | No | -- | Email address to receive admin notifications |

### Billing (Stripe)

Optional. Only needed if you want to run subscription billing through your self-hosted instance. Most self-hosters can ignore these.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `STRIPE_SECRET_KEY` | No | -- | Stripe secret key |
| `STRIPE_WEBHOOK_SECRET` | No | -- | Stripe webhook signing secret |
| `STRIPE_PRICE_MONTHLY` | No | -- | Stripe price ID for monthly plan |
| `STRIPE_PRICE_YEARLY` | No | -- | Stripe price ID for yearly plan |
| `STRIPE_PRICE_3YEAR` | No | -- | Stripe price ID for 3-year plan |

### License

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SYNCFLOW_LICENSE_KEY` | No | -- | License key for self-hosted features. See [Licensing](#licensing). |

---

## Connecting Your Devices

Once your server is running, connect your devices to it.

### Android

1. Install [SyncFlow from the Play Store](https://play.google.com/store/apps/details?id=com.phoneintegration.app) (or sideload the APK from your server's `/downloads` page)
2. Open SyncFlow on your Android phone
3. Go to **Settings** (gear icon in the top bar)
4. Tap **Server URL**
5. Enter your server URL (e.g., `https://sync.yourdomain.com` or `http://your-server-ip:3000`)
6. Tap **Save** -- the app will verify the connection
7. Return to the main screen and follow the pairing flow

### Mac

The Mac app connects using the QR code generated during Android pairing. Your server URL is embedded in the QR code, so the Mac app auto-discovers it.

1. Install [SyncFlow for Mac](https://syncflow.app/download) (or download from your server's `/downloads` page)
2. Open SyncFlow on your Mac
3. On your Android phone, open SyncFlow and go to **Settings > Pair New Device**
4. Scan the QR code shown on Android with your Mac app
5. The Mac app connects to your self-hosted server automatically

### Web

1. Open your server URL in a browser (e.g., `https://sync.yourdomain.com`)
2. If the web interface is not bundled, deploy the web app from the `web/` directory and set `NEXT_PUBLIC_VPS_URL` to your server URL
3. Scan the QR code with your Android app to pair

---

## Push Notifications Setup

**Why you want this:** Without Firebase Cloud Messaging (FCM), your Mac and Android apps only receive messages while they have an active WebSocket connection (i.e., the app is in the foreground or recently backgrounded). With FCM, the server sends a push notification that wakes the app to sync new messages immediately.

### Step 1: Create a Firebase Project

1. Go to the [Firebase Console](https://console.firebase.google.com/)
2. Click **Add project** and follow the prompts
3. You do not need to enable Google Analytics

### Step 2: Generate a Service Account Key

1. In your Firebase project, go to **Project Settings > Service accounts**
2. Click **Generate new private key**
3. Save the downloaded JSON file

### Step 3: Configure SyncFlow

Place the JSON file where SyncFlow can find it:

```bash
# If using Docker Compose:
mkdir -p data/fcm
cp ~/Downloads/your-firebase-serviceAccountKey.json data/fcm/serviceAccountKey.json
```

Set the environment variable in your `.env`:

```bash
FCM_SERVICE_ACCOUNT_PATH=/app/data/fcm/serviceAccountKey.json
```

Restart the server:

```bash
docker compose restart syncflow
```

### Step 4: Verify

Check the server logs for FCM initialization:

```bash
docker compose logs syncflow | grep -i fcm
```

You should see `FCM initialized successfully` (or similar). If the path is wrong, you will see a warning instead.

---

## HTTPS Setup

HTTPS is required for production use. WebSocket connections and API calls carry authentication tokens that must be encrypted in transit.

### Option 1: Automatic HTTPS with Caddy (Recommended)

The Docker Compose setup includes Caddy, which automatically provisions and renews TLS certificates from Let's Encrypt.

1. Set your domain in the `.env` file:

   ```bash
   DOMAIN=sync.yourdomain.com
   ```

2. Point your domain's DNS A record to your server's IP address

3. Open ports 80 and 443 on your firewall

4. Start (or restart) the stack:

   ```bash
   docker compose up -d
   ```

Caddy handles everything: HTTP-to-HTTPS redirect, certificate provisioning, and automatic renewal.

Your server is now reachable at `https://sync.yourdomain.com`.

### Option 2: Existing Reverse Proxy (nginx, Traefik, HAProxy)

If you already have a reverse proxy, remove the `caddy` service from `docker-compose.yml` and configure your proxy to forward traffic to the SyncFlow container.

**nginx example:**

```nginx
server {
    listen 443 ssl;
    server_name sync.yourdomain.com;

    ssl_certificate     /etc/letsencrypt/live/sync.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/sync.yourdomain.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;

        # WebSocket support (required for real-time sync)
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Increase timeout for WebSocket connections
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }
}
```

**Important:** You must proxy WebSocket connections (`Upgrade` and `Connection` headers). Without this, real-time sync will not work and clients will fall back to polling.

### Option 3: Cloudflare Tunnel

Cloudflare Tunnel exposes your server to the internet without opening any ports or configuring a reverse proxy. This is the simplest option if you are behind a firewall or CGNAT.

1. Install `cloudflared` on your server: [Cloudflare Tunnel docs](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/get-started/)
2. Create a tunnel:
   ```bash
   cloudflared tunnel create syncflow
   ```
3. Configure the tunnel to forward to `http://localhost:3000`
4. Route your domain through the tunnel

Note: Cloudflare Tunnel has a 100-second idle timeout on WebSocket connections. SyncFlow's 30-second heartbeat keeps connections alive, so this is normally not an issue.

---

## Video Calling (TURN Server)

SyncFlow supports WebRTC video and voice calls between your devices. WebRTC tries to establish a direct peer-to-peer connection first (using STUN). If that fails (due to NAT or firewall restrictions), a TURN relay server is needed.

### Option 1: Cloudflare TURN (Easiest)

Cloudflare offers a managed TURN service. It is a paid service but requires zero maintenance.

1. Go to the [Cloudflare Dashboard > TURN](https://dash.cloudflare.com/?to=/:account/calls/turn)
2. Create a TURN key
3. Add to your `.env`:
   ```bash
   CLOUDFLARE_TURN_KEY_ID=your_key_id
   CLOUDFLARE_TURN_API_TOKEN=your_api_token
   ```
4. Restart the server

### Option 2: Self-Hosted coturn

Run your own TURN server using [coturn](https://github.com/coturn/coturn):

```bash
docker run -d \
  --name coturn \
  --network host \
  coturn/coturn:latest \
  -n \
  --realm=sync.yourdomain.com \
  --fingerprint \
  --lt-cred-mech \
  --user=syncflow:your-turn-password \
  --external-ip=$(curl -s https://api.ipify.org) \
  --listening-port=3478 \
  --min-port=49152 \
  --max-port=65535
```

Open UDP ports 3478 and 49152-65535 on your firewall.

Then configure the TURN credentials in your app's client-side configuration or extend the server's `/api/calls/turn-credentials` endpoint to return your coturn server details.

### Option 3: STUN Only (No TURN)

If you do not need video calls to work across strict corporate NATs, you can skip TURN entirely. SyncFlow uses Google's public STUN servers by default. This works for most home and mobile networks.

---

## Backups

### Automatic Backups with Cron

Create a backup script:

```bash
#!/bin/bash
# /home/syncflow/backup.sh
BACKUP_DIR="/home/syncflow/backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

mkdir -p "$BACKUP_DIR"

# Dump the database
docker compose exec -T postgres pg_dump -U syncflow syncflow_prod \
  | gzip > "$BACKUP_DIR/syncflow_${TIMESTAMP}.sql.gz"

# Keep only the last 7 days of backups
find "$BACKUP_DIR" -name "syncflow_*.sql.gz" -mtime +7 -delete

echo "Backup completed: syncflow_${TIMESTAMP}.sql.gz"
```

Schedule it with cron:

```bash
chmod +x /home/syncflow/backup.sh

# Run daily at 2 AM
crontab -e
# Add this line:
0 2 * * * /home/syncflow/backup.sh >> /home/syncflow/backups/backup.log 2>&1
```

### Manual Backup

```bash
# Full database dump
docker compose exec -T postgres pg_dump -U syncflow syncflow_prod > backup.sql

# Compressed
docker compose exec -T postgres pg_dump -U syncflow syncflow_prod | gzip > backup.sql.gz
```

### Restore from Backup

```bash
# From a plain SQL backup
docker compose exec -T postgres psql -U syncflow syncflow_prod < backup.sql

# From a compressed backup
gunzip -c backup.sql.gz | docker compose exec -T postgres psql -U syncflow syncflow_prod
```

**Warning:** Restoring a backup replaces all current data. If you need to merge data, use `pg_dump` with `--data-only` and `--on-conflict-do-nothing` (PostgreSQL 16+).

### Backing Up Uploaded Files

If you use local file storage (no R2/S3), also back up the `data/` directory:

```bash
tar -czf syncflow_files_${TIMESTAMP}.tar.gz data/
```

---

## Updating

### Manual Update

```bash
cd /path/to/SyncFlow-VPS/server

# Pull the latest images
docker compose pull

# Restart with the new version
docker compose up -d
```

Database migrations run automatically on startup. You do not need to apply them manually when using Docker.

### Automatic Updates with Watchtower

[Watchtower](https://containrrr.dev/watchtower/) monitors your running containers and automatically pulls and restarts them when new images are available.

```bash
docker run -d \
  --name watchtower \
  --restart unless-stopped \
  -v /var/run/docker.sock:/var/run/docker.sock \
  containrrr/watchtower \
  --interval 86400 \
  --cleanup \
  syncflow
```

This checks for updates once per day and only updates the `syncflow` container.

### Checking Your Current Version

```bash
docker compose exec syncflow node -e "console.log(require('./package.json').version)"
```

Or check the health endpoint:

```bash
curl -s http://localhost:3000/health | jq .
```

---

## Troubleshooting

### Health Check

The first thing to check when something is not working:

```bash
curl http://localhost:3000/health
```

**Healthy response:**
```json
{
  "status": "healthy",
  "services": { "database": "up", "redis": "up" },
  "websocket": { "connections": 2 }
}
```

**Unhealthy response (database down):**
```json
{
  "status": "unhealthy",
  "services": { "database": "down", "redis": "up" }
}
```

### Viewing Logs

```bash
# All services
docker compose logs -f

# Just the SyncFlow API
docker compose logs -f syncflow

# Last 100 lines, no follow
docker compose logs syncflow --tail 100
```

### Common Issues

**Port 3000 already in use**

Another process is using the port. Find and stop it:

```bash
lsof -i :3000
# Then either stop that process or change SyncFlow's PORT in .env
```

**"Connection refused" when connecting from your phone**

- Verify the server is running: `docker compose ps`
- Verify the port is open: `curl http://your-server-ip:3000/health`
- If using a firewall (ufw, iptables), open the port: `sudo ufw allow 3000/tcp`
- If using HTTPS with Caddy, ensure ports 80 and 443 are open instead

**"DNS_PROBE_FINISHED_NXDOMAIN" or domain not resolving**

- Verify your DNS A record points to the correct IP: `dig sync.yourdomain.com`
- DNS changes can take up to 48 hours to propagate (usually under 5 minutes with Cloudflare)

**Certificate errors / HTTPS not working**

- Check Caddy logs: `docker compose logs caddy`
- Ensure ports 80 and 443 are open (Let's Encrypt uses HTTP-01 challenge on port 80)
- Ensure no other process (nginx, Apache) is already bound to port 80

**Redis connection refused**

- Check if Redis is running: `docker compose ps redis`
- Check Redis logs: `docker compose logs redis`
- Verify `REDIS_HOST` is set to `redis` (the Docker Compose service name), not `localhost`

**Messages not syncing in the background**

- This usually means FCM is not configured. See [Push Notifications Setup](#push-notifications-setup).
- Without FCM, messages only sync when the app has an active foreground connection.

**"JWT_SECRET environment variable is required"**

- The server requires a `JWT_SECRET`. Generate one: `openssl rand -base64 32`
- Add it to your `.env` file and restart

**"ADMIN_PASSWORD environment variable is required"**

- The server requires an `ADMIN_PASSWORD`. It must be a bcrypt hash:
  ```bash
  node -e "require('bcrypt').hash('your-password', 12).then(console.log)"
  ```
- Add the hash (starts with `$2b$`) to your `.env` and restart

**WebSocket connections dropping every 30 seconds**

- If you are behind a reverse proxy, increase the proxy read timeout to at least 86400 seconds (24 hours)
- See the [nginx example](#option-2-existing-reverse-proxy-nginx-traefik-haproxy) above for correct WebSocket proxy settings

**Database migrations failed on startup**

- Check the logs: `docker compose logs syncflow | grep -i migration`
- Migrations are idempotent -- restarting the container will retry them
- If a migration is permanently broken, you can apply it manually:
  ```bash
  docker compose exec -T postgres psql -U syncflow syncflow_prod < migrations/NNN_migration_name.sql
  ```

---

## Licensing

SyncFlow self-hosting works in two modes.

### Community Mode (Free, No License)

Without a license key, SyncFlow runs in community mode with these limits:

| Feature | Limit |
|---------|-------|
| Users | 1 |
| Devices | 2 (1 Android + 1 Mac or Web) |
| Message sync | Full |
| Contact sync | Full |
| Call history | Full |
| Clipboard sync | Full |
| File transfers | 50 MB per file |
| MMS attachments | Full |
| E2EE encryption | Full |
| Video calling | STUN only |
| Push notifications | Not available |

Community mode is fully functional for a single user with two devices. This is enough for most personal use cases.

### Self-Hosted License ($49/year)

Unlock additional capacity for power users and small families.

| Feature | Limit |
|---------|-------|
| Users | 5 |
| Devices | 10 |
| File transfers | 500 MB per file |
| Push notifications | Full (with your own FCM) |
| Video calling | Full (with TURN) |
| Priority support | Email |

### Self-Hosted Family ($99/year)

For larger households and small teams.

| Feature | Limit |
|---------|-------|
| Users | 10 |
| Devices | 25 |
| File transfers | 2 GB per file |
| Push notifications | Full |
| Video calling | Full |
| Priority support | Email + Discord |

### Getting a License

1. Visit [syncflow.app/self-hosted](https://syncflow.app/self-hosted)
2. Purchase a license for your tier
3. You will receive a license key by email
4. Add the key to your `.env` file:
   ```bash
   SYNCFLOW_LICENSE_KEY=sh_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
   ```
5. Restart the server: `docker compose restart syncflow`

The server validates the license on startup and once daily. If the license expires or is invalid, the server gracefully falls back to community mode limits. No data is lost.

---

## Security Considerations

A few things to keep in mind when running your own server:

- **Change all default passwords.** The setup script generates random passwords. If you configured manually, ensure `PG_PASSWORD`, `REDIS_PASSWORD`, `JWT_SECRET`, and `ADMIN_PASSWORD` are all unique, random, and strong.
- **Keep your server updated.** Run `docker compose pull && docker compose up -d` regularly to get security patches.
- **ADMIN_PASSWORD must be a bcrypt hash**, not plaintext. The server logs a security warning if it detects a plaintext admin password.
- **JWT tokens are signed with HS256.** Do not share your `JWT_SECRET` -- anyone with it can forge authentication tokens.
- **Rate limiting fails closed for authentication.** If Redis goes down, auth endpoints return 503 rather than allowing unlimited login attempts.
- **Enable HTTPS in production.** API calls and WebSocket connections carry JWT tokens. Without TLS, these tokens are exposed to network observers.
- **Restrict the admin panel.** The admin API at `/api/admin` has access to all user data. Consider firewall rules or VPN to limit access.
- **Back up your encryption keys.** If you use E2EE and lose your database, encrypted messages cannot be recovered without the key backups.

---

## Getting Help

- **GitHub Issues**: [github.com/dpchavali1/SyncFlow-VPS/issues](https://github.com/dpchavali1/SyncFlow-VPS/issues) -- bug reports and feature requests
- **Discussions**: [github.com/dpchavali1/SyncFlow-VPS/discussions](https://github.com/dpchavali1/SyncFlow-VPS/discussions) -- questions and community help
- **Email**: support@syncflow.app -- for licensed self-hosters with priority support
