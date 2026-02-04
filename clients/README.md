# SyncFlow VPS Client Libraries

Client libraries for connecting to the SyncFlow VPS API.

## Available Clients

| Platform | Language | File |
|----------|----------|------|
| Web | TypeScript | [web/SyncFlowClient.ts](web/SyncFlowClient.ts) |
| Android | Kotlin | [android/SyncFlowApiClient.kt](android/SyncFlowApiClient.kt) |
| macOS | Swift | [macos/SyncFlowApiClient.swift](macos/SyncFlowApiClient.swift) |

## Quick Start

### Web (TypeScript)

```typescript
import { SyncFlowClient } from './SyncFlowClient';

const client = new SyncFlowClient('http://5.78.188.206');

// Authenticate
const user = await client.authenticateAnonymous();
console.log('User ID:', user.userId);

// Get messages
const { messages } = await client.getMessages({ limit: 50 });

// Connect WebSocket for real-time updates
client.on('message_added', (msg) => console.log('New message:', msg));
client.subscribe('messages');
client.connectWebSocket();
```

### Android (Kotlin)

```kotlin
val client = SyncFlowApiClient("http://5.78.188.206")

// Authenticate
val user = client.authenticateAnonymous()
println("User ID: ${user.userId}")

// Get messages
val response = client.getMessages(limit = 50)
println("Messages: ${response.messages.size}")

// Connect WebSocket
client.connectWebSocket(object : SyncFlowWebSocketListener {
    override fun onMessageAdded(message: Message) {
        println("New message: ${message.body}")
    }
    // ... implement other methods
})
client.subscribe("messages")
```

### macOS (Swift)

```swift
let client = SyncFlowApiClient(baseUrl: "http://5.78.188.206")

// Authenticate
let user = try await client.authenticateAnonymous()
print("User ID: \(user.userId)")

// Get messages
let response = try await client.getMessages(limit: 50)
print("Messages: \(response.messages.count)")

// Connect WebSocket
client.webSocketDelegate = self
client.subscribe("messages")
client.connectWebSocket()
```

## API Endpoints

All clients support:

### Authentication
- `authenticateAnonymous()` - Create anonymous user
- `initiatePairing()` - Start device pairing (macOS/Web)
- `completePairing()` - Approve pairing (Android)
- `redeemPairing()` - Get credentials after approval
- `refreshAccessToken()` - Refresh expired token

### Messages
- `getMessages()` - Get messages with pagination
- `syncMessages()` - Sync messages from device
- `sendMessage()` - Queue message to send
- `getOutgoingMessages()` - Get pending outgoing
- `markMessageRead()` - Mark as read

### Contacts
- `getContacts()` - Get contacts
- `syncContacts()` - Sync contacts from device
- `getContact()` - Get single contact

### Call History
- `getCallHistory()` - Get calls with pagination
- `syncCallHistory()` - Sync call history
- `requestCall()` - Request call from desktop
- `getCallRequests()` - Get pending call requests

### Devices
- `getDevices()` - Get paired devices
- `updateDevice()` - Update device info
- `removeDevice()` - Remove device

### WebSocket
- `connectWebSocket()` - Connect for real-time updates
- `subscribe()` / `unsubscribe()` - Subscribe to channels
- Events: `message_added`, `message_updated`, `contact_added`, etc.

## Integration Guide

### Replacing Firebase

To migrate from Firebase to VPS:

1. **Keep both services running** during transition
2. **Add VPS client** alongside Firebase
3. **Dual-write**: Write to both Firebase and VPS
4. **Read from VPS**: Switch reads to VPS
5. **Disable Firebase writes**: Once VPS is stable
6. **Remove Firebase**: After validation period

### Token Storage

Store tokens securely:
- **Web**: Use `localStorage` or `sessionStorage`
- **Android**: Use `EncryptedSharedPreferences`
- **macOS**: Use Keychain Services

```typescript
// Web example
const tokens = client.getTokens();
if (tokens) {
  localStorage.setItem('syncflow_tokens', JSON.stringify(tokens));
}

// Restore on app start
const saved = localStorage.getItem('syncflow_tokens');
if (saved) {
  const { accessToken, refreshToken } = JSON.parse(saved);
  client.setTokens(accessToken, refreshToken);
}
```
