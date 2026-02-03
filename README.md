# SyncFlow VPS - Self-Hosted Backend

Self-hosted alternative to Firebase Realtime Database for SyncFlow.

## Architecture

- **Database**: PostgreSQL 16
- **Real-time**: WebSocket + PostgreSQL LISTEN/NOTIFY
- **Cache**: Redis 7
- **API**: Node.js 20 + Express
- **Proxy**: Nginx with SSL

## Documentation

- [Migration Plan](docs/MIGRATION_PLAN.md) - Complete migration strategy
- [Task List](docs/TASK_LIST.md) - All tasks with subtasks
- [VPS Setup Instructions](docs/VPS_SETUP_INSTRUCTIONS.md) - Hetzner setup guide

## Project Structure

```
SyncFlow-VPS/
├── docs/
│   ├── MIGRATION_PLAN.md      # Migration strategy
│   ├── TASK_LIST.md           # Complete task list (184 tasks)
│   └── VPS_SETUP_INSTRUCTIONS.md  # Hetzner VPS setup
├── server/
│   ├── schema.sql             # PostgreSQL database schema
│   ├── src/                   # Server source code (TODO)
│   │   ├── index.ts           # Entry point
│   │   ├── routes/            # REST API routes
│   │   ├── services/          # Business logic
│   │   ├── repositories/      # Database access
│   │   └── websocket/         # WebSocket server
│   └── package.json           # Dependencies
├── scripts/
│   ├── migrate-export.ts      # Export from Firebase
│   ├── migrate-import.ts      # Import to PostgreSQL
│   └── backup.sh              # Database backup script
└── README.md
```

## Quick Start

### 1. Set up VPS
Follow [VPS Setup Instructions](docs/VPS_SETUP_INSTRUCTIONS.md)

### 2. Create Database Schema
```bash
psql -U syncflow -d syncflow_prod -f server/schema.sql
```

### 3. Deploy Server
```bash
cd server
npm install
pm2 start ecosystem.config.js
```

## Status

- [x] Documentation complete
- [x] Database schema designed
- [ ] VPS created and configured
- [ ] Server application built
- [ ] Client libraries updated
- [ ] Data migrated
- [ ] Production cutover

## Cost Comparison

| Service | Firebase | Self-Hosted |
|---------|----------|-------------|
| Monthly | $85-350 | ~$15-30 |
| Bandwidth | Pay per GB | 20TB included |
| Control | Limited | Full |

## Security

- All connections over TLS
- JWT authentication with claims
- Rate limiting per user/IP
- SQL injection protection
- Firewall (UFW) configured
- Fail2ban for brute force protection
