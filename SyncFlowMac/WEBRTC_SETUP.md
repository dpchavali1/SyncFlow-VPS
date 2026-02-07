# WebRTC Audio Routing Setup Guide

This guide explains how to complete the WebRTC setup for routing call audio through your MacBook.

## Prerequisites

- CocoaPods installed: `sudo gem install cocoapods`
- Xcode 14+ installed
- macOS 13.0+ (Ventura or later)

## Setup Steps

### 1. Install WebRTC Framework

Navigate to the SyncFlowMac directory and install pods:

```bash
cd SyncFlowMac
pod install
```

This will:
- Download Google's WebRTC framework (~500MB)
- Create `SyncFlowMac.xcworkspace`
- Link WebRTC to your project

### 2. Open Workspace (Important!)

**From now on, open the `.xcworkspace` file, NOT the `.xcodeproj` file:**

```bash
open SyncFlowMac.xcworkspace
```

### 3. Configure Microphone Permission

Add microphone permission to `Info.plist`:

1. Open `SyncFlowMac/Info.plist` (or create if it doesn't exist)
2. Add the following key:

```xml
<key>NSMicrophoneUsageDescription</key>
<string>SyncFlow needs microphone access to route call audio through your MacBook</string>
```

### 4. Build and Run

1. Select the SyncFlowMac scheme in Xcode
2. Build the project (Cmd+B)
3. Run the app (Cmd+R)

## How It Works

### Architecture

```
┌─────────────┐         Firebase          ┌──────────────┐
│   Android   │◄──────WebRTC Signaling───►│    macOS     │
│   Phone     │      (SDP/ICE Exchange)    │   MacBook    │
└─────────────┘                            └──────────────┘
      │                                            │
      │                                            │
  Phone Call                                  Audio Stream
  (Cellular)                                 (Speakers/Mic)
      │                                            │
      ▼                                            ▼
   Captures                                    Plays
Phone Audio ──────────WebRTC Stream─────────►  MacBook
                                              Speakers
```

### When You Receive a Call

1. **Incoming Call Notification** appears on macOS
2. **Toggle "Use MacBook Audio"** to enable WebRTC
3. **Click "Accept"** to answer the call
4. **Audio Routes**:
   - Phone's call audio → Android captures → WebRTC → MacBook speakers
   - MacBook microphone → WebRTC → Android → Phone call

### Firebase Data Flow

**Signaling Path:**
```
users/{userId}/webrtc_signaling/{callId}/
  ├── offer/                    # Android's SDP offer
  ├── answer/                   # macOS's SDP answer
  ├── ice_candidates_android/   # Android's network candidates
  └── ice_candidates_desktop/   # macOS's network candidates
```

**Control Path:**
```
users/{userId}/audio_routing_requests/
  └── {requestId}/
      ├── callId: "call_123"
      ├── enable: true
      └── processed: false
```

## Features

### Audio Controls

- **Use MacBook Audio**: Toggle to route audio through computer
- **Connection Status**: Real-time WebRTC connection state
- **Auto-Cleanup**: Stops audio routing when call ends

### Connection States

- 🟡 **Connecting...**: Establishing WebRTC connection
- 🟢 **MacBook Audio Active**: Successfully streaming audio
- 🔴 **Connection Failed**: WebRTC connection error
- ⚪ **Disconnected**: No audio routing

## Troubleshooting

### Pod Install Fails

```bash
# Update CocoaPods repo
pod repo update

# Clean install
pod deintegrate
pod install
```

### WebRTC Framework Not Found

Make sure you're opening the `.xcworkspace` file, not `.xcodeproj`:
```bash
open SyncFlowMac.xcworkspace
```

### Microphone Permission Denied

1. Go to System Settings → Privacy & Security → Microphone
2. Enable SyncFlow
3. Restart the app

### Audio Not Working

1. Check that "Use MacBook Audio" toggle is ON
2. Verify connection status shows "MacBook Audio Active"
3. Check Android logs: `adb logcat -s CallAudioManager:D`
4. Check macOS logs in Xcode console

### No Offer Received from Android

1. Ensure Android app is updated and installed
2. Check Firebase connection on both devices
3. Verify call is actually active on Android
4. Check Android logs for WebRTC errors

## Implementation Details

### Android Side (Already Implemented ✅)

- **CallAudioManager**: Captures phone call audio
- **WebRTCSignalingService**: Sends SDP offer and ICE candidates
- **Firebase Listener**: Receives audio routing requests from macOS

### macOS Side (Just Implemented ✅)

- **WebRTCClient**: Manages peer connection and audio playback
- **WebRTCSignalingService**: Handles SDP/ICE exchange
- **CallAudioManager**: Coordinates audio routing
- **IncomingCallView**: UI with audio toggle

## Performance Notes

- **WebRTC Framework Size**: ~500MB (will increase app size significantly)
- **Network Usage**: ~40-80 Kbps for audio streaming
- **Latency**: Typically 100-300ms depending on network
- **Battery Impact**: Moderate (WebRTC is optimized but uses resources)

## Security

- All audio is encrypted end-to-end via WebRTC (DTLS-SRTP)
- Signaling data is secured by Firebase authentication
- No audio data passes through Firebase (only signaling metadata)
- Direct peer-to-peer connection between devices

## Next Steps

After setup is complete:

1. Answer an incoming call on macOS
2. Toggle "Use MacBook Audio" ON
3. Speak into your MacBook's microphone
4. Listen through MacBook speakers
5. Toggle OFF to route back to phone

Enjoy hands-free calling through your MacBook! 🎉
