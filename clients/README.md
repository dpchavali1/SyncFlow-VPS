# SyncFlow VPS Client Libraries

Complete client libraries for connecting to the SyncFlow VPS API. Includes low-level API clients, high-level services with caching and auto-reconnection, and ready-to-use UI components.

## Directory Structure

```
clients/
├── web/
│   ├── SyncFlowClient.ts       # Low-level API client
│   ├── services/
│   │   └── SyncFlowService.ts  # High-level service with IndexedDB cache
│   ├── hooks/
│   │   ├── useSyncFlow.ts      # React hooks
│   │   └── SyncFlowProvider.tsx # React context provider
│   └── index.ts                # Main exports
├── android/
│   ├── SyncFlowApiClient.kt    # Low-level API client
│   ├── services/
│   │   └── SyncFlowService.kt  # High-level service with EncryptedSharedPreferences
│   └── ui/
│       └── SyncFlowApp.kt      # Jetpack Compose screens
└── macos/
    ├── SyncFlowApiClient.swift # Low-level API client
    ├── services/
    │   └── SyncFlowService.swift # High-level service with Keychain
    └── ui/
        └── SyncFlowViews.swift # SwiftUI views
```

## Quick Start

### Web (React)

```tsx
import { SyncFlowProvider, useSyncFlowMessages, useSyncFlowAuth } from '@syncflow/vps-client';

function App() {
  return (
    <SyncFlowProvider apiUrl="http://5.78.188.206">
      <MyApp />
    </SyncFlowProvider>
  );
}

function MyApp() {
  const { isAuthenticated, initiatePairing } = useSyncFlowAuth();
  const { messages, loadMessages, sendMessage } = useSyncFlowMessages();

  useEffect(() => {
    if (isAuthenticated) {
      loadMessages();
    }
  }, [isAuthenticated]);

  if (!isAuthenticated) {
    return <PairingScreen onPair={initiatePairing} />;
  }

  return (
    <div>
      {messages.map(msg => (
        <div key={msg.id}>{msg.body}</div>
      ))}
    </div>
  );
}
```

### Android (Jetpack Compose)

```kotlin
class MainActivity : ComponentActivity() {
    private val service by lazy { SyncFlowService.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SyncFlowApp(service)
            }
        }
    }
}
```

### macOS (SwiftUI)

```swift
@main
struct SyncFlowMacApp: App {
    var body: some Scene {
        WindowGroup {
            SyncFlowMainView()
        }
    }
}
```

## Architecture

### Three Layers

1. **API Client** - Low-level HTTP/WebSocket client
   - Direct API calls
   - Token management
   - WebSocket connection

2. **Service Layer** - High-level wrapper
   - Token persistence (secure storage)
   - Auto-reconnection
   - Local caching (IndexedDB/SQLite)
   - Reactive state (StateFlow/Combine/EventEmitter)

3. **UI Components** - Ready-to-use screens
   - Messages list
   - Contacts
   - Call history
   - Device management
   - Pairing flow

### Token Storage

| Platform | Storage Method |
|----------|----------------|
| Web | localStorage (IndexedDB for cache) |
| Android | EncryptedSharedPreferences |
| macOS | Keychain Services |

### Real-Time Updates

All platforms connect via WebSocket for real-time updates:

```
Client → WebSocket → Server
         ↓
         Subscribe to channels: messages, contacts, calls, devices
         ↓
         Receive events: message_added, contact_updated, etc.
         ↓
         Auto-update local state
```

## React Hooks

### `useSyncFlow()`
Main hook providing full state and service reference.

### `useSyncFlowAuth()`
Authentication-specific hook.
```tsx
const { isAuthenticated, isPaired, initiatePairing, logout } = useSyncFlowAuth();
```

### `useSyncFlowMessages()`
Messages management.
```tsx
const { messages, loadMessages, sendMessage, markAsRead } = useSyncFlowMessages();
```

### `useSyncFlowContacts()`
Contacts with search.
```tsx
const { contacts, searchResults, loadContacts, searchContacts } = useSyncFlowContacts();
```

### `useSyncFlowCalls()`
Call history.
```tsx
const { calls, loadCallHistory, makeCall } = useSyncFlowCalls();
```

### `useSyncFlowDevices()`
Device management.
```tsx
const { devices, loadDevices, removeDevice } = useSyncFlowDevices();
```

### `useNewMessage(callback)`
Listen for new messages.
```tsx
useNewMessage((message) => {
  showNotification(message.body);
});
```

## API Reference

### Authentication
| Method | Description |
|--------|-------------|
| `authenticateAnonymous()` | Create anonymous user |
| `initiatePairing(deviceName)` | Start pairing (returns token) |
| `checkPairingStatus(token)` | Check if approved |
| `completePairing(token)` | Approve pairing (Android) |
| `redeemPairing(token)` | Get credentials after approval |
| `refreshAccessToken()` | Refresh expired token |

### Messages
| Method | Description |
|--------|-------------|
| `getMessages(limit, before)` | Get messages with pagination |
| `syncMessages(messages)` | Sync from device |
| `sendMessage(address, body)` | Queue message to send |
| `markMessageRead(id)` | Mark as read |
| `getOutgoingMessages()` | Get pending outgoing |

### Contacts
| Method | Description |
|--------|-------------|
| `getContacts(search)` | Get contacts with optional search |
| `syncContacts(contacts)` | Sync from device |
| `getContact(id)` | Get single contact |

### Call History
| Method | Description |
|--------|-------------|
| `getCallHistory(limit, before)` | Get calls with pagination |
| `syncCallHistory(calls)` | Sync from device |
| `requestCall(phoneNumber)` | Request call from desktop |
| `getCallRequests()` | Get pending requests |

### Devices
| Method | Description |
|--------|-------------|
| `getDevices()` | Get paired devices |
| `updateDevice(id, name, fcmToken)` | Update device info |
| `removeDevice(id)` | Remove device |

### WebSocket Events
| Event | Payload |
|-------|---------|
| `connected` | - |
| `disconnected` | - |
| `message_added` | Message |
| `message_updated` | Message |
| `message_deleted` | messageId |
| `contact_added` | Contact |
| `contact_updated` | Contact |
| `contact_deleted` | contactId |
| `call_added` | CallHistoryEntry |
| `outgoing_message` | OutgoingMessage |
| `call_request` | CallRequest |

## Dependencies

### Web
```json
{
  "dependencies": {
    "react": "^18.0.0"
  }
}
```

### Android
```kotlin
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

### macOS
- Foundation
- Combine
- Security (Keychain)
- SwiftUI (for UI components)

## Server URL

Default API URL: `http://5.78.188.206`

For production, use HTTPS:
```
https://api.sfweb.app
```

## Migration from Firebase

1. Add VPS client alongside Firebase
2. Dual-write to both during transition
3. Switch reads to VPS
4. Disable Firebase writes
5. Remove Firebase after validation
