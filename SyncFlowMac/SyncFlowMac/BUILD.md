# Building SyncFlow macOS App

Quick guide to build and run the SyncFlow macOS app.

## Prerequisites

✅ macOS 13.0 (Ventura) or later
✅ Xcode 15.0 or later
✅ Firebase project configured (same as Android app)
✅ SyncFlow Android app paired with Firebase

## Quick Start

### 1. Install Xcode

```bash
# Install from Mac App Store
# OR install Command Line Tools:
xcode-select --install
```

### 2. Get Firebase Configuration

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select your SyncFlow project (same project as Android app)
3. Click "Add app" → Select "iOS+" (works for macOS)
4. Register app:
   - **Apple bundle ID:** `com.syncflow.mac`
   - **App nickname:** SyncFlow macOS
5. Download `GoogleService-Info.plist`
6. Place in project:
   ```bash
   cp ~/Downloads/GoogleService-Info.plist SyncFlowMac/SyncFlowMac/Resources/
   ```

### 3. Open in Xcode

```bash
cd SyncFlowMac
open SyncFlowMac.xcodeproj
```

**Important:** If you see an error about missing Firebase packages:
1. In Xcode: File → Packages → Resolve Package Versions
2. Wait for Swift Package Manager to download dependencies
3. Build again (⌘B)

### 4. Configure Code Signing

1. Select the project in Xcode navigator
2. Select "SyncFlowMac" target
3. Go to "Signing & Capabilities" tab
4. Check "Automatically manage signing"
5. Select your Apple ID team

### 5. Build and Run

**Option A: Run in Xcode**
```
Click the Play button (▶️) or press ⌘R
```

**Option B: Build from Terminal**
```bash
xcodebuild -scheme SyncFlowMac -configuration Debug build
```

The app will be at:
```
~/Library/Developer/Xcode/DerivedData/SyncFlowMac-*/Build/Products/Debug/SyncFlowMac.app
```

## First Run

1. App launches → Shows pairing screen
2. On Android:
   - Open SyncFlow app
   - Settings → Desktop Integration
   - Tap "Pair New Device"
   - Copy the pairing code shown
3. On Mac:
   - Paste pairing code
   - Click "Pair"
4. Done! Messages will sync automatically

## Common Build Issues

### Issue: "Firebase module not found"

**Solution:**
```bash
cd SyncFlowMac
swift package resolve
# Then rebuild in Xcode
```

### Issue: "GoogleService-Info.plist not found"

**Solution:**
```bash
# Make sure the file is in the right location
ls SyncFlowMac/SyncFlowMac/Resources/GoogleService-Info.plist

# If not, download from Firebase Console and place it there
```

### Issue: "Code signing failed"

**Solution:**
1. Xcode → Preferences → Accounts
2. Add your Apple ID
3. Select the team in Signing & Capabilities

### Issue: "SwiftUI preview failed"

**Solution:**
```
SwiftUI previews are optional. The app will still build and run.
You can disable previews by removing Preview structs from view files.
```

## Building for Release

### Option 1: Archive (Recommended)

1. In Xcode: Product → Archive
2. When archive completes, click "Distribute App"
3. Select "Copy App"
4. Save the .app file

### Option 2: Command Line

```bash
xcodebuild archive \
  -scheme SyncFlowMac \
  -archivePath SyncFlowMac.xcarchive \
  -configuration Release

xcodebuild -exportArchive \
  -archivePath SyncFlowMac.xcarchive \
  -exportPath dist \
  -exportOptionsPlist ExportOptions.plist
```

## Distribution

### For Personal Use

Just copy the .app to Applications:
```bash
cp -r SyncFlowMac.app /Applications/
```

### For Public Distribution

Requires Apple Developer account ($99/year):

1. **Code Sign:**
   ```bash
   codesign --deep --force --verify --verbose \
     --sign "Developer ID Application: Your Name" \
     SyncFlowMac.app
   ```

2. **Notarize:**
   ```bash
   # Create ZIP
   ditto -c -k --keepParent SyncFlowMac.app SyncFlowMac.zip

   # Submit for notarization
   xcrun notarytool submit SyncFlowMac.zip \
     --apple-id your@email.com \
     --team-id YOUR_TEAM_ID \
     --wait

   # Staple notarization
   xcrun stapler staple SyncFlowMac.app
   ```

## Development Tips

### Swift Package Manager

If you want to update Firebase version:

1. Edit `Package.swift`:
   ```swift
   .package(url: "https://github.com/firebase/firebase-ios-sdk.git", from: "10.21.0")
   ```

2. Resolve packages:
   ```bash
   swift package resolve
   swift package update
   ```

### Debugging

**View Firebase logs:**
```
macOS Console.app → Filter: "Firebase" or "SyncFlow"
```

**Check network traffic:**
```bash
# Install Firebase CLI
npm install -g firebase-tools

# Monitor Realtime Database
firebase database:get / --project your-project-id
```

### Performance Profiling

In Xcode:
- Product → Profile (⌘I)
- Select "Time Profiler" or "Leaks"
- Record and analyze

## Next Steps

Once the app is running:

1. ✅ Test pairing with Android phone
2. ✅ Verify messages sync in real-time
3. ✅ Try sending a message from Mac
4. ✅ Check it sends via Android phone
5. 🎉 Enjoy your native macOS SMS app!

---

**Need help?** Open an issue on GitHub or check the README.md for troubleshooting.
