# SyncFlow VPS Migration - Complete Task List

## Phase 1: Infrastructure Setup

### 1.1 VPS Creation
- [ ] **1.1.1** Create Hetzner account (if not exists)
- [ ] **1.1.2** Create VPS (CX32 or CX42 recommended)
- [ ] **1.1.3** Configure SSH key authentication
- [ ] **1.1.4** Disable password SSH authentication
- [ ] **1.1.5** Set up firewall (UFW)
- [ ] **1.1.6** Configure automatic security updates

### 1.2 Domain & SSL
- [ ] **1.2.1** Point domain/subdomain to VPS IP (e.g., api.syncflow.app)
- [ ] **1.2.2** Install Nginx
- [ ] **1.2.3** Install Certbot
- [ ] **1.2.4** Generate Let's Encrypt SSL certificate
- [ ] **1.2.5** Configure Nginx as reverse proxy
- [ ] **1.2.6** Set up auto-renewal for SSL

### 1.3 Database Installation
- [ ] **1.3.1** Install PostgreSQL 16
- [ ] **1.3.2** Configure PostgreSQL for remote connections (internal only)
- [ ] **1.3.3** Create database and user
- [ ] **1.3.4** Install Redis 7
- [ ] **1.3.5** Configure Redis authentication
- [ ] **1.3.6** Test database connections

### 1.4 Runtime Environment
- [ ] **1.4.1** Install Node.js 20 LTS (via nvm)
- [ ] **1.4.2** Install PM2 globally
- [ ] **1.4.3** Configure PM2 startup script
- [ ] **1.4.4** Set up log rotation

---

## Phase 2: Database Schema

### 2.1 Core Tables
- [ ] **2.1.1** Create `users` table (uid, created_at, phone, email)
- [ ] **2.1.2** Create `user_profiles` table
- [ ] **2.1.3** Create `user_devices` table
- [ ] **2.1.4** Create `user_sims` table

### 2.2 Messaging Tables
- [ ] **2.2.1** Create `user_messages` table with all columns
- [ ] **2.2.2** Create indexes on (user_id, date), (user_id, thread_id)
- [ ] **2.2.3** Create `user_outgoing_messages` table
- [ ] **2.2.4** Create `user_spam_messages` table
- [ ] **2.2.5** Create `user_scheduled_messages` table
- [ ] **2.2.6** Create `user_message_reactions` table
- [ ] **2.2.7** Create `user_read_receipts` table

### 2.3 Contact Tables
- [ ] **2.3.1** Create `user_contacts` table
- [ ] **2.3.2** Create indexes on display_name, phone_number
- [ ] **2.3.3** Create `user_groups` table (group messaging)

### 2.4 Call Tables
- [ ] **2.4.1** Create `user_call_history` table
- [ ] **2.4.2** Create `user_call_requests` table
- [ ] **2.4.3** Create `user_active_calls` table
- [ ] **2.4.4** Create `user_syncflow_calls` table (WebRTC)
- [ ] **2.4.5** Create `user_webrtc_signaling` table

### 2.5 E2EE Tables
- [ ] **2.5.1** Create `user_e2ee_keys` table (private key backups)
- [ ] **2.5.2** Create `e2ee_public_keys` table (global)
- [ ] **2.5.3** Create `e2ee_key_requests` table
- [ ] **2.5.4** Create `e2ee_key_responses` table

### 2.6 Sync & File Tables
- [ ] **2.6.1** Create `user_file_transfers` table
- [ ] **2.6.2** Create `user_photos` table
- [ ] **2.6.3** Create `user_voicemails` table
- [ ] **2.6.4** Create `user_clipboard` table
- [ ] **2.6.5** Create `user_continuity_state` table

### 2.7 Notification & Status Tables
- [ ] **2.7.1** Create `user_mirrored_notifications` table
- [ ] **2.7.2** Create `user_dnd_status` table
- [ ] **2.7.3** Create `user_hotspot_status` table
- [ ] **2.7.4** Create `user_media_status` table
- [ ] **2.7.5** Create `fcm_tokens` table

### 2.8 Global Tables
- [ ] **2.8.1** Create `recovery_codes` table
- [ ] **2.8.2** Create `phone_uid_mapping` table
- [ ] **2.8.3** Create `pairing_requests` table
- [ ] **2.8.4** Create `sync_groups` table
- [ ] **2.8.5** Create `crash_reports` table
- [ ] **2.8.6** Create `deleted_accounts` table
- [ ] **2.8.7** Create `user_subscriptions` table
- [ ] **2.8.8** Create `user_usage` table

### 2.9 Real-Time Triggers
- [ ] **2.9.1** Create notify function for messages
- [ ] **2.9.2** Create notify function for contacts
- [ ] **2.9.3** Create notify function for call history
- [ ] **2.9.4** Create notify function for notifications
- [ ] **2.9.5** Create notify function for e2ee keys
- [ ] **2.9.6** Create triggers on all real-time tables
- [ ] **2.9.7** Test LISTEN/NOTIFY functionality

---

## Phase 3: Server Application

### 3.1 Project Setup
- [ ] **3.1.1** Initialize Node.js project
- [ ] **3.1.2** Set up TypeScript configuration
- [ ] **3.1.3** Install dependencies (express, ws, pg, ioredis, jsonwebtoken)
- [ ] **3.1.4** Set up environment variable handling
- [ ] **3.1.5** Create project structure (routes, services, models)

### 3.2 Database Layer
- [ ] **3.2.1** Create PostgreSQL connection pool
- [ ] **3.2.2** Create Redis client
- [ ] **3.2.3** Create base repository class
- [ ] **3.2.4** Implement MessageRepository
- [ ] **3.2.5** Implement ContactRepository
- [ ] **3.2.6** Implement CallHistoryRepository
- [ ] **3.2.7** Implement DeviceRepository
- [ ] **3.2.8** Implement UserRepository
- [ ] **3.2.9** Implement E2EERepository

### 3.3 Authentication
- [ ] **3.3.1** Implement JWT token generation
- [ ] **3.3.2** Implement JWT token validation middleware
- [ ] **3.3.3** Implement anonymous auth endpoint
- [ ] **3.3.4** Implement pairing token generation
- [ ] **3.3.5** Implement pairing redemption
- [ ] **3.3.6** Implement admin authentication
- [ ] **3.3.7** Implement refresh token flow

### 3.4 REST API Endpoints
- [ ] **3.4.1** POST /api/auth/anonymous
- [ ] **3.4.2** POST /api/auth/pair/initiate
- [ ] **3.4.3** POST /api/auth/pair/redeem
- [ ] **3.4.4** POST /api/auth/admin
- [ ] **3.4.5** GET /api/messages (paginated)
- [ ] **3.4.6** POST /api/messages
- [ ] **3.4.7** PUT /api/messages/:id
- [ ] **3.4.8** DELETE /api/messages/:id
- [ ] **3.4.9** GET /api/contacts
- [ ] **3.4.10** POST /api/contacts/sync
- [ ] **3.4.11** GET /api/call-history
- [ ] **3.4.12** POST /api/call-history
- [ ] **3.4.13** GET /api/devices
- [ ] **3.4.14** POST /api/devices
- [ ] **3.4.15** DELETE /api/devices/:id
- [ ] **3.4.16** GET /api/profile
- [ ] **3.4.17** PUT /api/profile
- [ ] **3.4.18** GET /api/e2ee/keys
- [ ] **3.4.19** POST /api/e2ee/keys
- [ ] **3.4.20** POST /api/call/request
- [ ] **3.4.21** POST /api/sms/send
- [ ] **3.4.22** POST /api/mms/send

### 3.5 WebSocket Server
- [ ] **3.5.1** Set up WebSocket server (ws library)
- [ ] **3.5.2** Implement connection authentication
- [ ] **3.5.3** Implement subscription manager
- [ ] **3.5.4** Set up PostgreSQL LISTEN connections
- [ ] **3.5.5** Implement message broadcast to subscribers
- [ ] **3.5.6** Handle subscribe/unsubscribe events
- [ ] **3.5.7** Implement heartbeat/ping-pong
- [ ] **3.5.8** Handle disconnection cleanup
- [ ] **3.5.9** Implement reconnection handling

### 3.6 Rate Limiting & Security
- [ ] **3.6.1** Implement Redis-based rate limiting
- [ ] **3.6.2** Add per-user rate limits
- [ ] **3.6.3** Add per-IP rate limits
- [ ] **3.6.4** Implement request validation
- [ ] **3.6.5** Add SQL injection protection
- [ ] **3.6.6** Add CORS configuration
- [ ] **3.6.7** Implement request logging

### 3.7 Admin Endpoints
- [ ] **3.7.1** GET /api/admin/users (paginated)
- [ ] **3.7.2** GET /api/admin/users/:id
- [ ] **3.7.3** DELETE /api/admin/users/:id
- [ ] **3.7.4** GET /api/admin/stats
- [ ] **3.7.5** POST /api/admin/cleanup
- [ ] **3.7.6** GET /api/admin/crashes

---

## Phase 4: Client Libraries

### 4.1 Shared Client Library (TypeScript)
- [ ] **4.1.1** Create SyncFlowClient class
- [ ] **4.1.2** Implement REST API methods
- [ ] **4.1.3** Implement WebSocket connection
- [ ] **4.1.4** Implement subscription management
- [ ] **4.1.5** Implement automatic reconnection
- [ ] **4.1.6** Implement token refresh
- [ ] **4.1.7** Add TypeScript types for all data models

### 4.2 Android Client
- [ ] **4.2.1** Create SyncFlowApiClient class (Kotlin)
- [ ] **4.2.2** Implement REST API with Retrofit/Ktor
- [ ] **4.2.3** Implement WebSocket client (OkHttp)
- [ ] **4.2.4** Implement subscription manager
- [ ] **4.2.5** Implement token storage (encrypted)
- [ ] **4.2.6** Implement automatic reconnection
- [ ] **4.2.7** Update DesktopSyncService to use new client
- [ ] **4.2.8** Update all Firebase references

### 4.3 macOS Client
- [ ] **4.3.1** Create SyncFlowApiClient class (Swift)
- [ ] **4.3.2** Implement REST API with URLSession
- [ ] **4.3.3** Implement WebSocket client (URLSessionWebSocketTask)
- [ ] **4.3.4** Implement subscription manager
- [ ] **4.3.5** Implement token storage (Keychain)
- [ ] **4.3.6** Update FirebaseService to use new client
- [ ] **4.3.7** Update MessageStore to use new client
- [ ] **4.3.8** Update all Firebase references

### 4.4 Web Client
- [ ] **4.4.1** Create SyncFlowClient class (TypeScript)
- [ ] **4.4.2** Implement REST API with fetch
- [ ] **4.4.3** Implement WebSocket connection
- [ ] **4.4.4** Implement subscription manager
- [ ] **4.4.5** Update firebase.ts to use new client
- [ ] **4.4.6** Update incrementalSync.ts
- [ ] **4.4.7** Update all Firebase imports

---

## Phase 5: Data Migration

### 5.1 Export Scripts
- [ ] **5.1.1** Create Firebase export script for users
- [ ] **5.1.2** Create Firebase export script for messages
- [ ] **5.1.3** Create Firebase export script for contacts
- [ ] **5.1.4** Create Firebase export script for call history
- [ ] **5.1.5** Create Firebase export script for devices
- [ ] **5.1.6** Create Firebase export script for e2ee keys
- [ ] **5.1.7** Create Firebase export script for global data

### 5.2 Transform Scripts
- [ ] **5.2.1** Transform user data to PostgreSQL format
- [ ] **5.2.2** Transform messages (flatten nested structure)
- [ ] **5.2.3** Transform contacts
- [ ] **5.2.4** Transform call history
- [ ] **5.2.5** Generate UUIDs for records without IDs
- [ ] **5.2.6** Validate data integrity

### 5.3 Import Scripts
- [ ] **5.3.1** Create PostgreSQL import script with batch inserts
- [ ] **5.3.2** Handle duplicate detection
- [ ] **5.3.3** Verify record counts match
- [ ] **5.3.4** Verify data integrity post-import
- [ ] **5.3.5** Create rollback script

---

## Phase 6: Testing

### 6.1 Unit Tests
- [ ] **6.1.1** Test all repository methods
- [ ] **6.1.2** Test authentication flows
- [ ] **6.1.3** Test JWT generation/validation
- [ ] **6.1.4** Test rate limiting

### 6.2 Integration Tests
- [ ] **6.2.1** Test REST API endpoints
- [ ] **6.2.2** Test WebSocket subscriptions
- [ ] **6.2.3** Test real-time message flow
- [ ] **6.2.4** Test pairing flow end-to-end

### 6.3 Load Tests
- [ ] **6.3.1** Test concurrent WebSocket connections
- [ ] **6.3.2** Test message throughput
- [ ] **6.3.3** Test database performance under load
- [ ] **6.3.4** Identify and fix bottlenecks

### 6.4 Client Testing
- [ ] **6.4.1** Test Android app with new backend
- [ ] **6.4.2** Test macOS app with new backend
- [ ] **6.4.3** Test Web app with new backend
- [ ] **6.4.4** Test Admin app with new backend

---

## Phase 7: Deployment & Cutover

### 7.1 Dual-Write Setup
- [ ] **7.1.1** Update Android to write to both Firebase and VPS
- [ ] **7.1.2** Update macOS to write to both
- [ ] **7.1.3** Update Web to write to both
- [ ] **7.1.4** Verify data consistency

### 7.2 Gradual Migration
- [ ] **7.2.1** Switch read operations to VPS (keep writes dual)
- [ ] **7.2.2** Monitor for issues
- [ ] **7.2.3** Switch writes to VPS only
- [ ] **7.2.4** Keep Firebase as read-only fallback

### 7.3 Final Cutover
- [ ] **7.3.1** Update DNS records
- [ ] **7.3.2** Disable Firebase writes
- [ ] **7.3.3** Monitor for 48 hours
- [ ] **7.3.4** Remove Firebase code (after 30 days)

### 7.4 Cleanup
- [ ] **7.4.1** Document new architecture
- [ ] **7.4.2** Update CLAUDE.md with new instructions
- [ ] **7.4.3** Archive Firebase export
- [ ] **7.4.4** Cancel Firebase billing (after verification)

---

## Phase 8: Operations

### 8.1 Monitoring
- [ ] **8.1.1** Set up server monitoring (htop, netdata, or similar)
- [ ] **8.1.2** Set up PostgreSQL monitoring
- [ ] **8.1.3** Set up application logging
- [ ] **8.1.4** Configure alerts for downtime
- [ ] **8.1.5** Set up uptime monitoring (UptimeRobot or similar)

### 8.2 Backups
- [ ] **8.2.1** Configure daily PostgreSQL backups
- [ ] **8.2.2** Set up backup rotation (keep 7 daily, 4 weekly)
- [ ] **8.2.3** Configure offsite backup storage
- [ ] **8.2.4** Test backup restoration

### 8.3 Security Maintenance
- [ ] **8.3.1** Configure automatic security updates
- [ ] **8.3.2** Set up fail2ban
- [ ] **8.3.3** Schedule SSL certificate renewal check
- [ ] **8.3.4** Document incident response procedure

---

## Summary

| Phase | Tasks | Critical Path |
|-------|-------|---------------|
| Phase 1: Infrastructure | 20 | Yes |
| Phase 2: Database Schema | 35 | Yes |
| Phase 3: Server Application | 45 | Yes |
| Phase 4: Client Libraries | 30 | Yes |
| Phase 5: Data Migration | 15 | Yes |
| Phase 6: Testing | 15 | Yes |
| Phase 7: Deployment | 12 | Yes |
| Phase 8: Operations | 12 | No (can be parallel) |
| **Total** | **184** | |

---

## Dependencies

```
Phase 1 (VPS) ──┬──> Phase 2 (Schema) ──> Phase 3 (Server)
                │                              │
                │                              v
                │                        Phase 4 (Clients)
                │                              │
                v                              v
          Phase 5 (Migration) <────────────────┘
                │
                v
          Phase 6 (Testing)
                │
                v
          Phase 7 (Cutover)
                │
                v
          Phase 8 (Operations)
```
