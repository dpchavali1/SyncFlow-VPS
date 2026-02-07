# SyncFlow for macOS

Native macOS app for accessing your Android SMS messages on your Mac.

## Features

✅ **Native macOS Experience**
- SwiftUI interface with native macOS design
- Menu bar integration
- Keyboard shortcuts (⌘N for new message, ⌘F for search)
- Native notifications

✅ **Real-time Message Sync**
- Instant message updates via Firebase
- View all conversations from your Android phone
- Send messages from your Mac

✅ **QR Code Pairing**
- Simple pairing process
- Secure Firebase authentication
- Device management

✅ **Privacy & Security**
- End-to-end user isolation
- Local data storage
- Secure Firebase connection

## Requirements

- macOS 13.0 (Ventura) or later
- Xcode 15.0 or later
- SyncFlow Android app installed on your phone
- Firebase project configured

## Setup Instructions

### 1. Install Xcode

If you don't have Xcode installed:

```bash
# Install Xcode from Mac App Store
# Or install Command Line Tools
xcode-select --install
```

### 2. Configure Firebase

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select your SyncFlow project
3. Add a macOS app to your project:
   - Click "Add app" → Choose macOS
   - Bundle ID: `com.syncflow.mac`
   - App nickname: "SyncFlow macOS"
4. Download `GoogleService-Info.plist`
5. Copy it to the project root:
   ```bash
   cp ~/Downloads/GoogleService-Info.plist SyncFlowMac/SyncFlowMac/Resources/
   ```

### 3. Build the App

```bash
cd SyncFlowMac

# Open in Xcode
open SyncFlowMac.xcodeproj

# Or build from command line
xcodebuild -scheme SyncFlowMac -configuration Release

# The app will be at:
# ~/Library/Developer/Xcode/DerivedData/SyncFlowMac-.../Build/Products/Release/SyncFlowMac.app
```

### 4. Pair with Android Phone

1. **On Android:**
   - Open SyncFlow app
   - Go to Settings → Desktop Integration
   - Tap "Pair New Device"
   - A QR code and pairing code will appear

2. **On macOS:**
   - Open SyncFlowMac.app
   - Paste the pairing code
   - Click "Pair"
   - Done! 🎉

## Usage

### Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| ⌘N | New message |
| ⌘F | Search conversations |
| ⌘, | Open settings |
| ⌘W | Close window |
| ⌘Q | Quit app |
| Return | Send message |
| Shift+Return | New line in message |

### Menu Bar

- **File** → New Message (⌘N)
- **Edit** → Search (⌘F)
- **View** → Toggle sidebar
- **Window** → Minimize, Zoom

## Project Structure

```
SyncFlowMac/
├── SyncFlowMac/
│   ├── App/
│   │   ├── SyncFlowMacApp.swift      # App entry point
│   │   └── ContentView.swift          # Main view
│   │
│   ├── Models/
│   │   └── Message.swift              # Data models
│   │
│   ├── Services/
│   │   ├── FirebaseService.swift      # Firebase integration
│   │   └── MessageStore.swift         # State management
│   │
│   ├── Views/
│   │   ├── PairingView.swift          # Pairing screen
│   │   ├── ConversationListView.swift # Sidebar
│   │   ├── MessageView.swift          # Message display
│   │   └── SettingsView.swift         # Settings
│   │
│   └── Resources/
│       ├── Assets.xcassets/           # App icons, images
│       └── GoogleService-Info.plist   # Firebase config
│
├── Package.swift                      # Swift Package Manager
└── README.md                          # This file
```

## Development

### Adding Features

The codebase is organized into clear modules:

**Models** - Data structures
```swift
// Add new model in Models/
struct YourModel: Identifiable, Codable {
    let id: String
    // ...
}
```

**Services** - Business logic
```swift
// Add new service in Services/
class YourService {
    func doSomething() async throws {
        // ...
    }
}
```

**Views** - UI components
```swift
// Add new view in Views/
struct YourView: View {
    var body: some View {
        // ...
    }
}
```

### Firebase Schema

The app uses this Firebase structure:

```
users/
  {userId}/
    messages/
      {messageId}/
        - id, address, body, date, type
    devices/
      {deviceId}/
        - name, type, pairedAt
    outgoing_messages/
      {messageId}/
        - address, body, timestamp, status
```

## Troubleshooting

### Build Errors

**Problem:** "Firebase module not found"
```bash
# Solution: Install Swift Package Manager dependencies
cd SyncFlowMac
swift package resolve
swift build
```

**Problem:** "Code signing required"
```
# Solution: Set up development team in Xcode
# 1. Open project in Xcode
# 2. Select project → Signing & Capabilities
# 3. Select your Apple ID team
```

### Runtime Errors

**Problem:** "Firebase not configured"
- Make sure `GoogleService-Info.plist` is in Resources folder
- Verify it's added to the app target

**Problem:** "Cannot pair with Android"
- Check Firebase console for errors
- Verify Android app is using same Firebase project
- Check internet connection

**Problem:** "Messages not syncing"
- Check Firebase Realtime Database rules
- Verify authentication is working
- Check Android app is syncing messages

### Performance Issues

If the app feels slow:
- Check Activity Monitor for high CPU usage
- Look for Firebase connection issues in Console.app
- Reduce message history limit in MessageStore

## Distribution

### Building for Distribution

```bash
# Archive the app
xcodebuild archive \
  -scheme SyncFlowMac \
  -archivePath SyncFlowMac.xcarchive

# Export for distribution
xcodebuild -exportArchive \
  -archivePath SyncFlowMac.xcarchive \
  -exportPath dist \
  -exportOptionsPlist ExportOptions.plist
```

### Notarization (for public distribution)

```bash
# Requires Apple Developer account ($99/year)
xcrun notarytool submit SyncFlowMac.app.zip \
  --apple-id your@email.com \
  --team-id YOUR_TEAM_ID \
  --wait

# Staple the notarization
xcrun stapler staple SyncFlowMac.app
```

## Roadmap

### Phase 2 Features (Coming Soon)
- [ ] Camera-based QR scanning
- [ ] Native macOS notifications
- [ ] Menu bar icon with quick access
- [ ] Message search
- [ ] Contact sync
- [ ] Dark mode improvements

### Phase 3 Features (Future)
- [ ] Voice calls (WebRTC)
- [ ] Video calls
- [ ] File transfer
- [ ] Screen sharing
- [ ] Clipboard sync

## Support

**Issues:** https://github.com/dpchavali1/SyncFlow/issues
**Discussions:** https://github.com/dpchavali1/SyncFlow/discussions

## License

Copyright © 2025 SyncFlow. All rights reserved.

---

Built with ❤️ using SwiftUI and Firebase
