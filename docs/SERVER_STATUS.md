# SyncFlow VPS - Server Status

## Server Details

| Property | Value |
|----------|-------|
| **Provider** | Hetzner Cloud |
| **Plan** | CPX21 (3 vCPU, 4GB RAM, 80GB SSD) |
| **Location** | Hillsboro, OR (US West) |
| **IP Address** | `5.78.188.206` |
| **OS** | Ubuntu 24.04 LTS |
| **Hostname** | syncflow-prod-1 |
| **Monthly Cost** | $12.59 |

## API Endpoints

**Base URL:** `http://5.78.188.206` (will be `https://api.sfweb.app` once DNS propagates)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check |
| `/api/auth/anonymous` | POST | Create anonymous user |
| `/api/auth/pair/initiate` | POST | Start device pairing |
| `/api/auth/pair/status/:token` | GET | Check pairing status |
| `/api/auth/pair/complete` | POST | Android approves pairing |
| `/api/auth/pair/redeem` | POST | Desktop gets credentials |
| `/api/auth/refresh` | POST | Refresh access token |
| `/api/auth/me` | GET | Get current user info |
| `/api/messages` | GET | Get messages (paginated) |
| `/api/messages/sync` | POST | Sync messages from device |
| `/api/messages/send` | POST | Queue message to send |
| `/api/messages/outgoing` | GET | Get pending outgoing messages |
| `/api/contacts` | GET | Get contacts |
| `/api/contacts/sync` | POST | Sync contacts from device |
| `/api/calls` | GET | Get call history |
| `/api/calls/sync` | POST | Sync call history |
| `/api/calls/request` | POST | Request call from desktop |
| `/api/devices` | GET | Get user devices |
| `/api/devices/sims` | GET/POST | Get/register SIM cards |

## WebSocket

**URL:** `ws://5.78.188.206:3001?token=JWT_TOKEN`

**Events:**
- `subscribe` / `unsubscribe` - Subscribe to channels
- `message_added` / `message_updated` / `message_deleted`
- `contact_added` / `contact_updated` / `contact_deleted`
- `call_added`
- `device_added` / `device_removed`
- `outgoing_message`

## Access

```bash
ssh syncflow@5.78.188.206
```

## Credentials (KEEP SECURE)

| Service | User | Password/Details |
|---------|------|------------------|
| SSH | syncflow | Key-based auth |
| PostgreSQL | syncflow | `bQkmT6UkSHAm5XomNekh25ma/otf51D4` |
| PostgreSQL DB | syncflow_prod | Port 5432 (localhost only) |
| Redis | - | `x46emQJ3HsK9zmeVH5Z55Dyks86feeJV` |

## Services Status

| Service | Status | Port |
|---------|--------|------|
| PostgreSQL 16 | Running | 5432 |
| Redis 7 | Running | 6379 |
| Nginx | Running | 80, 443 |
| Node.js API | Running (PM2) | 3000 |
| WebSocket | Running (PM2) | 3001 |
| Firewall (UFW) | Active | 22, 80, 443 |

## PM2 Commands

```bash
pm2 status              # Check status
pm2 logs syncflow-api   # View logs
pm2 restart syncflow-api # Restart
pm2 reload syncflow-api  # Zero-downtime reload
```

## Domain Setup

| Domain | Status |
|--------|--------|
| `api.sfweb.app` | DNS pending propagation |

### Once DNS Works
```bash
sudo certbot --nginx -d api.sfweb.app
```

## Test Commands

```bash
# Health check
curl http://5.78.188.206/health

# Create anonymous user
curl -X POST http://5.78.188.206/api/auth/anonymous

# Get user info (with token)
curl -H "Authorization: Bearer TOKEN" http://5.78.188.206/api/auth/me
```

## Deployment

```bash
cd ~/syncflow-api
git pull
cd server
npm run build
pm2 reload syncflow-api
```
