# VPS Migration Status - Firebase Removal

## Overview
This document tracks the migration from Firebase to VPS-only backend.

## Completed Changes

### 1. Build Configuration (`build.gradle.kts` files)
- ✅ Removed `com.google.gms.google-services` plugin from project-level build.gradle.kts
- ✅ Removed `com.google.firebase.crashlytics` plugin from project-level build.gradle.kts
- ✅ Removed all Firebase dependencies from app/build.gradle.kts:
  - firebase-bom
  - firebase-database-ktx
  - firebase-auth-ktx
  - firebase-storage-ktx
  - firebase-messaging-ktx
  - firebase-functions-ktx
  - firebase-analytics-ktx

### 2. Core VPS Services (Already Implemented)
- ✅ `/vps/VPSClient.kt` - HTTP/WebSocket client for VPS API
- ✅ `/vps/VPSAuthManager.kt` - JWT-based authentication
- ✅ `/vps/VPSSyncService.kt` - Message/contact/call sync via VPS
- ✅ `/vps/SyncBackendConfig.kt` - Backend configuration (defaults to VPS)
- ✅ `/vps/VPSSecurityConfig.kt` - NEW: VPS security configuration

### 3. Updated to VPS-Only
- ✅ `SyncFlowApp.kt` - Completely rewritten to use VPS services only
- ✅ `AuthManager.kt` - Wrapped VPSAuthManager, removed FirebaseAuth
- ✅ `CustomCrashReporter.kt` - Updated to send crashes to VPS API
- ✅ `UnifiedSyncService.kt` - Removed Firebase hybrid support, VPS-only

## Remaining Files (42 files still import Firebase)

### High Priority - Core Functionality
1. `DesktopSyncService.kt` - Very large file, heavily Firebase-dependent
2. `CallMonitorService.kt` - Uses Firebase for call state sync
3. `SmsReceiver.kt` - May use Firebase for message sync
4. `UnifiedIdentityManager.kt` - Device pairing, needs VPS version
5. `RecoveryCodeManager.kt` - Account recovery
6. `PhoneAuthManager.kt` - Phone number verification (Firebase Auth)

### Medium Priority - Sync Workers
7. `ContactsSyncWorker.kt` / `ContactsSyncService.kt`
8. `CallHistorySyncWorker.kt` / `CallHistorySyncService.kt`
9. `SmsSyncWorker.kt`
10. `PhotoSyncService.kt`

### Lower Priority - Additional Features
11. `SyncFlowMessagingService.kt` - FCM push notifications
12. `OutgoingMessageService.kt` - Desktop-to-phone messaging
13. `IntelligentSyncManager.kt`
14. `DataCleanupService.kt`
15. `SyncGroupManager.kt`
16. `IncrementalSyncManager.kt`

### Feature-Specific (can be disabled/stubbed)
17. `ClipboardSyncService.kt`
18. `DNDSyncService.kt`
19. `FileTransferService.kt`
20. `FindMyPhoneService.kt`
21. `HotspotControlService.kt`
22. `LinkSharingService.kt`
23. `MediaControlService.kt`
24. `NotificationMirrorService.kt`
25. `PhoneStatusService.kt`
26. `ScheduledMessageService.kt`
27. `VoicemailSyncService.kt`

### E2EE
28. `SignalProtocolManager.kt` - E2EE encryption, stores keys in Firebase

### UI Screens
29. `DeleteAccountScreen.kt`
30. `SupportChatScreen.kt`
31. `UsageSettingsScreen.kt`
32. `PhoneNumberRegistrationDialog.kt`
33. `AdBanner.kt`

### Other
34. `FirebaseSecurityConfig.kt` - Can be deleted (replaced by VPSSecurityConfig)
35. `UsageTracker.kt`
36. `ReadReceiptManager.kt`
37. `TypingIndicatorManager.kt`
38. `DeviceAwareStorage.kt`
39. `SyncFlowCallManager.kt`
40. `SyncFlowCallService.kt`
41. `ContinuityService.kt`
42. `SyncFlowCall.kt` (model)

## Migration Strategy for Remaining Files

### Option 1: Create Stubs (Quick Fix for Build)
Create stub classes that provide empty Firebase implementations to allow the app to compile. This enables gradual migration.

### Option 2: Conditional Compilation
Use build variants (debug/release) to include/exclude Firebase.

### Option 3: Full Migration (Recommended)
Systematically update each file to:
1. Remove Firebase imports
2. Use VPS services (VPSClient, VPSSyncService, etc.)
3. Test each feature individually

## VPS Server Endpoints Needed

The VPS server at http://5.78.188.206 should provide these APIs:

### Already Implemented
- POST /api/auth/anonymous - Create anonymous user
- POST /api/auth/pair/initiate - Start pairing
- GET /api/auth/pair/status/:token - Check pairing status
- POST /api/auth/pair/complete - Complete pairing (Android approves)
- POST /api/auth/pair/redeem - Redeem pairing (desktop/web)
- POST /api/auth/refresh - Refresh access token
- GET /api/auth/me - Get current user
- GET/POST /api/messages - Message CRUD
- GET/POST /api/contacts - Contact CRUD
- GET/POST /api/calls - Call history CRUD
- GET/POST /api/devices - Device management
- WebSocket at :3001 for real-time sync

### May Need to Add
- POST /api/crashes - Crash reporting
- E2EE key storage/sync
- Photo/attachment storage
- Group messaging
- Scheduled messages
- Notification preferences

## Next Steps

1. **Build Test**: Try to build the app - it will fail due to missing Firebase classes
2. **Add Stubs or Keep Firebase Auth Temporarily**:
   - Option A: Create Firebase stub classes
   - Option B: Keep firebase-auth-ktx dependency temporarily
3. **Gradually Migrate**: Update remaining 42 files one at a time
4. **Test Each Feature**: Ensure each feature works with VPS
5. **Remove Stubs/Dependencies**: Once all features migrated, remove all Firebase

## Files Safe to Delete
- `FirebaseSecurityConfig.kt` - Replaced by VPSSecurityConfig

## Notes
- The google-services.json file can be deleted once all Firebase is removed
- Some features (FCM push, phone auth) may need alternative implementations
