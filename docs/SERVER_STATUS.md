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

## Access

```bash
ssh syncflow@5.78.188.206
```

## Credentials (KEEP SECURE)

| Service | User | Password/Details |
|---------|------|------------------|
| SSH | syncflow | Key-based auth (no password) |
| PostgreSQL | syncflow | `bQkmT6UkSHAm5XomNekh25ma/otf51D4` |
| PostgreSQL DB | syncflow_prod | Port 5432 (localhost only) |
| Redis | - | `x46emQJ3HsK9zmeVH5Z55Dyks86feeJV` |

## Services Status

| Service | Status | Port | Notes |
|---------|--------|------|-------|
| PostgreSQL 16 | Running | 5432 | 40 tables created |
| Redis 7 | Running | 6379 | Password protected |
| Nginx | Running | 80 | Reverse proxy configured |
| Node.js 20 | Installed | - | v20.20.0 via nvm |
| PM2 | Installed | - | Auto-start on boot |
| Firewall (UFW) | Active | 22, 80, 443 | SSH + HTTP + HTTPS |
| Fail2ban | Active | - | Brute force protection |

## Domain Setup

| Domain | Status | Notes |
|--------|--------|-------|
| `api.sfweb.app` | DNS Pending | A record added in Vercel, waiting for propagation |

### Check DNS Propagation
```bash
nslookup api.sfweb.app 8.8.8.8
```

### Once DNS Works - Add SSL
```bash
ssh syncflow@5.78.188.206
sudo certbot --nginx -d api.sfweb.app
```

## Test Commands

```bash
# Health check via IP
curl http://5.78.188.206/health

# Health check via domain (once DNS works)
curl http://api.sfweb.app/health

# PostgreSQL connection
PGPASSWORD='bQkmT6UkSHAm5XomNekh25ma/otf51D4' psql -U syncflow -h localhost -d syncflow_prod

# Redis connection
redis-cli -a 'x46emQJ3HsK9zmeVH5Z55Dyks86feeJV' ping
```

## Nginx Configuration

Located at: `/etc/nginx/sites-available/syncflow`

- `/` → Proxy to Node.js API (port 3000)
- `/ws` → Proxy to WebSocket server (port 3001)
- `/health` → Returns "OK"

## Database

Schema loaded with 40 tables. See `server/schema.sql` for structure.

Key tables:
- `users` - User accounts
- `user_messages` - SMS/MMS messages
- `user_contacts` - Contact directory
- `user_call_history` - Call logs
- `user_devices` - Paired devices
- `user_e2ee_keys` - E2EE key backups
- `pairing_requests` - Device pairing tokens

## Next Steps

1. [ ] Wait for DNS propagation (`api.sfweb.app`)
2. [ ] Add SSL certificate with Certbot
3. [ ] Build Node.js API server
4. [ ] Deploy and test
5. [ ] Update client apps

## Backup

Daily PostgreSQL backups configured via cron (3 AM UTC):
```bash
/home/syncflow/backup.sh
```

Backups stored in: `/home/syncflow/backups/`
