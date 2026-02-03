# SyncFlow Firebase to Self-Hosted RTDB Migration Plan

## Overview

This document outlines the complete migration strategy from Firebase Realtime Database to a self-hosted solution on Hetzner VPS.

## Architecture Decision

### Recommended Stack: PostgreSQL + WebSocket Server

| Component | Technology | Purpose |
|-----------|------------|---------|
| Database | PostgreSQL 16 | Primary data storage |
| Real-time | WebSocket Server (Node.js) | Push updates to clients |
| Change Detection | PostgreSQL LISTEN/NOTIFY | Trigger real-time events |
| Authentication | Custom JWT | Token-based auth with claims |
| API | REST + WebSocket | Client communication |
| Cache | Redis | Session cache, rate limiting |

### Why This Stack?

1. **PostgreSQL** - Battle-tested, excellent indexing, JSONB for flexible schemas
2. **WebSocket** - Direct replacement for Firebase listeners
3. **LISTEN/NOTIFY** - Native PostgreSQL pub/sub for change detection
4. **Redis** - Fast caching, rate limiting, session management

---

## Data Path Mapping

### User-Scoped Data (`/users/{userId}/` → `user_*` tables)

| Firebase Path | PostgreSQL Table | Indexes |
|---------------|------------------|---------|
| messages | user_messages | (user_id, date), (user_id, thread_id) |
| outgoing_messages | user_outgoing_messages | (user_id, status, timestamp) |
| spam_messages | user_spam_messages | (user_id, date) |
| contacts | user_contacts | (user_id, display_name) |
| call_history | user_call_history | (user_id, call_date), (user_id, phone_number) |
| devices | user_devices | (user_id) |
| sims | user_sims | (user_id) |
| e2ee_key_backups | user_e2ee_keys | (user_id, device_id) |
| profile | user_profiles | (user_id) |
| subscription | user_subscriptions | (user_id) |
| usage | user_usage | (user_id) |
| photos | user_photos | (user_id, timestamp) |
| voicemails | user_voicemails | (user_id, date) |
| clipboard | user_clipboard | (user_id) |
| mirrored_notifications | user_notifications | (user_id, timestamp) |
| scheduled_messages | user_scheduled_messages | (user_id, scheduled_time, status) |
| groups | user_groups | (user_id) |
| file_transfers | user_file_transfers | (user_id, timestamp, status) |
| webrtc_signaling | user_webrtc_signaling | (user_id, session_id) |
| syncflow_calls | user_syncflow_calls | (user_id, status, started_at) |
| call_requests | user_call_requests | (user_id, status) |
| active_calls | user_active_calls | (user_id) |

### Global Data

| Firebase Path | PostgreSQL Table | Indexes |
|---------------|------------------|---------|
| recovery_codes | recovery_codes | (code_hash), (user_id) |
| e2ee_keys | e2ee_public_keys | (uid, device_id) |
| phone_to_uid | phone_uid_mapping | (phone_number) |
| pairing_requests | pairing_requests | (token), (expires_at) |
| fcm_tokens | fcm_tokens | (uid) |
| crashes | crash_reports | (uid, timestamp) |
| sync_groups | sync_groups | (sync_group_id) |
| deleted_accounts | deleted_accounts | (user_id, deleted_at) |

---

## Query Translation

### Firebase → PostgreSQL

```javascript
// Firebase: orderByChild + limitToLast
query(ref(db, 'users/{uid}/messages'), orderByChild('date'), limitToLast(500))

// PostgreSQL equivalent
SELECT * FROM user_messages
WHERE user_id = $1
ORDER BY date DESC
LIMIT 500;
```

```javascript
// Firebase: startAt for incremental sync
query(ref(db, 'users/{uid}/messages'), orderByChild('date'), startAt(lastSync))

// PostgreSQL equivalent
SELECT * FROM user_messages
WHERE user_id = $1 AND date > $2
ORDER BY date ASC;
```

```javascript
// Firebase: endAt for pagination backward
query(ref(db, 'users/{uid}/messages'), orderByChild('date'), endAt(oldest), limitToLast(100))

// PostgreSQL equivalent
SELECT * FROM user_messages
WHERE user_id = $1 AND date < $2
ORDER BY date DESC
LIMIT 100;
```

---

## Real-Time Sync Architecture

### WebSocket Event Flow

```
Client                    Server                    PostgreSQL
  |                         |                           |
  |-- Subscribe(messages) -->|                           |
  |                         |-- LISTEN messages_{uid} -->|
  |                         |                           |
  |                         |<-- INSERT trigger ---------|
  |                         |-- NOTIFY messages_{uid} -->|
  |<-- WS: message_added ---|                           |
  |                         |                           |
```

### PostgreSQL Trigger for Real-Time

```sql
CREATE OR REPLACE FUNCTION notify_message_change()
RETURNS TRIGGER AS $$
BEGIN
  PERFORM pg_notify(
    'messages_' || NEW.user_id,
    json_build_object(
      'action', TG_OP,
      'id', NEW.id,
      'data', row_to_json(NEW)
    )::text
  );
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER message_change_trigger
AFTER INSERT OR UPDATE OR DELETE ON user_messages
FOR EACH ROW EXECUTE FUNCTION notify_message_change();
```

### WebSocket Subscription Types

| Firebase Listener | WebSocket Event |
|-------------------|-----------------|
| onValue | Full snapshot on subscribe + incremental updates |
| onChildAdded | message_added, contact_added, etc. |
| onChildChanged | message_updated, contact_updated, etc. |
| onChildRemoved | message_deleted, contact_deleted, etc. |

---

## Authentication System

### JWT Token Structure (Matching Firebase)

```json
{
  "sub": "user-uid",
  "aud": "syncflow-vps",
  "iat": 1706918400,
  "exp": 1706922000,
  "claims": {
    "admin": false,
    "pairedUid": "primary-user-uid",
    "deviceId": "device-123"
  }
}
```

### Auth Flow

1. **Anonymous Auth** → Generate temporary JWT
2. **Pairing** → Generate device-specific JWT with pairedUid claim
3. **Admin** → Generate JWT with admin=true claim

---

## API Endpoints

### REST API

| Endpoint | Method | Description |
|----------|--------|-------------|
| /api/auth/anonymous | POST | Get anonymous token |
| /api/auth/pair | POST | Complete pairing, get user token |
| /api/auth/admin | POST | Admin login |
| /api/messages | GET | Paginated messages |
| /api/messages | POST | Send message |
| /api/contacts | GET | Get contacts |
| /api/contacts | POST | Sync contacts |
| /api/call-history | GET | Paginated call history |
| /api/devices | GET/POST/DELETE | Device management |

### WebSocket API

| Event | Direction | Description |
|-------|-----------|-------------|
| subscribe | Client→Server | Subscribe to data path |
| unsubscribe | Client→Server | Unsubscribe from path |
| message_added | Server→Client | New message |
| message_updated | Server→Client | Message changed |
| message_deleted | Server→Client | Message removed |
| contact_added | Server→Client | New contact |
| call_added | Server→Client | New call record |
| ... | ... | Similar for all data types |

---

## Server Requirements

### Minimum VPS Specs (Hetzner CX32)

- **vCPU**: 4 cores
- **RAM**: 8 GB
- **Storage**: 80 GB NVMe SSD
- **Bandwidth**: 20 TB/month
- **OS**: Ubuntu 22.04 LTS

### Software Stack

- Node.js 20 LTS
- PostgreSQL 16
- Redis 7
- Nginx (reverse proxy + SSL)
- PM2 (process manager)
- Certbot (Let's Encrypt SSL)

---

## Migration Phases

### Phase 1: Infrastructure Setup (VPS + Database)
- Create Hetzner VPS
- Install PostgreSQL, Redis, Node.js
- Configure firewall and security
- Set up SSL certificates

### Phase 2: Database Schema + API
- Create all PostgreSQL tables
- Implement triggers for real-time
- Build REST API endpoints
- Build WebSocket server

### Phase 3: Authentication
- Implement JWT token generation
- Port pairing flow
- Admin authentication

### Phase 4: Data Migration
- Export data from Firebase
- Transform to PostgreSQL format
- Import with validation

### Phase 5: Client Updates
- Update Android app
- Update macOS app
- Update Web app
- Update Admin app

### Phase 6: Testing + Cutover
- Parallel running (dual-write)
- Validation and testing
- DNS cutover
- Firebase deprecation

---

## Security Considerations

1. **Firewall** - Only expose 443 (HTTPS) and WebSocket port
2. **SSL** - Let's Encrypt for all connections
3. **Rate Limiting** - Redis-based rate limiting
4. **Input Validation** - Sanitize all inputs
5. **SQL Injection** - Use parameterized queries only
6. **Authentication** - JWT with short expiry + refresh tokens

---

## Backup Strategy

1. **PostgreSQL** - Daily pg_dump to offsite storage
2. **Redis** - RDB snapshots (less critical, can rebuild)
3. **Server Config** - Version controlled in this repo

---

## Cost Comparison

| Service | Firebase (Current) | Self-Hosted (Hetzner) |
|---------|-------------------|----------------------|
| Database | ~$25-100/month | Included |
| Bandwidth | ~$50-200/month | 20TB included |
| Functions | ~$10-50/month | Included |
| VPS | N/A | ~$15-30/month |
| **Total** | **$85-350/month** | **~$15-30/month** |

---

## Rollback Plan

1. Keep Firebase running for 30 days post-migration
2. Implement dual-write during transition
3. DNS-based traffic routing for quick rollback
4. Data export from PostgreSQL → Firebase if needed
