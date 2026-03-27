# Changelog

All notable changes to the SyncFlow server are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-03-27

### Added
- Self-hosting support with Docker Compose
- Local file storage backend (no Cloudflare R2 required)
- License system for self-hosted deployments
- Automatic database migrations on startup
- Setup script for one-command installation
- Backup and restore scripts
- Connection status indicator on macOS and Web

### Security
- JWT algorithm pinned to HS256
- Admin SQL browser uses READ ONLY transactions
- Device blacklist check on WebSocket connections
- File download LIKE injection fix
- Refresh token rotation
- Rate limiter fails closed for auth on Redis failure

### Removed
- Photo sync feature (all platforms)

### Fixed
- Deals card removed from Android conversation list
- Double upgrade banners on macOS
- macOS dialer not dismissable
- Android 5-second registration delay reduced to 1.5s
- AppDatabase destructive migration replaced with proper migration

## [1.0.0] - 2026-02-15

### Added
- Initial release of SyncFlow VPS server
- SMS and MMS sync between Android, macOS, and Web
- Contact sync with photo thumbnails
- Call history sync with incoming, outgoing, and missed call types
- Real-time WebSocket push for instant message delivery
- WebRTC video and voice calling between devices (SyncFlow calls)
- End-to-end encryption (E2EE) with per-device key exchange
- E2EE key backup and cross-device key sync
- Clipboard sync across all connected devices
- File transfer support with Cloudflare R2 storage
- Notification mirroring from Android to macOS and Web
- Do Not Disturb sync between devices
- Media playback control from macOS (play, pause, skip on Android)
- Scheduled message support (compose now, send later)
- Spam filtering with ML classification and pattern matching
- Voicemail sync and transcription storage
- Device pairing via QR code with time-limited tokens
- Admin panel with user management, device management, and analytics
- Firebase Cloud Messaging for background push notifications
- Stripe integration for subscription billing
- Daily automated cleanup of expired tokens, old crash reports, and orphaned data
- PostgreSQL triggers for real-time change notification
- Graceful shutdown with connection draining
- Structured logging with request duration tracking
- Health check endpoint at `/health` with database and Redis status
- 14 database migrations for schema evolution
