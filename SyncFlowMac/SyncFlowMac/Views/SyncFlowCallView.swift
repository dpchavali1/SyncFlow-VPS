//
//  SyncFlowCallView.swift
//  SyncFlowMac
//
//  Call view with video rendering and controls.
//

import SwiftUI
import WebRTC
import ScreenCaptureKit

// MARK: - WebRTC Video View Wrapper

struct RTCVideoViewRepresentable: NSViewRepresentable {
    let videoTrack: RTCVideoTrack?

    func makeNSView(context: Context) -> RTCMTLNSVideoView {
        let view = RTCMTLNSVideoView(frame: .zero)
        return view
    }

    func updateNSView(_ nsView: RTCMTLNSVideoView, context: Context) {
        // Remove previous track
        context.coordinator.currentTrack?.remove(nsView)
        // Add new track
        videoTrack?.add(nsView)
        context.coordinator.currentTrack = videoTrack
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    class Coordinator {
        var currentTrack: RTCVideoTrack?
    }

    static func dismantleNSView(_ nsView: RTCMTLNSVideoView, coordinator: Coordinator) {
        coordinator.currentTrack?.remove(nsView)
    }
}

// MARK: - SyncFlowCallView

struct SyncFlowCallView: View {
    @EnvironmentObject var appState: AppState
    @ObservedObject var callManager: SyncFlowCallManager
    @ObservedObject var screenCaptureService: ScreenCaptureService
    @State private var showControls = true
    @State private var controlsTimer: Timer?
    @State private var callDurationTimer: Timer?
    @State private var now = Date()
    @State private var showScreenSharePicker = false

    var body: some View {
        ZStack {
            // Background
            Color.black.ignoresSafeArea()

            // Remote screen share (full screen, takes priority over camera)
            if let screenTrack = callManager.screenShareTrack {
                RTCVideoViewRepresentable(videoTrack: screenTrack)
                    .ignoresSafeArea()
            }
            // Remote video (full screen)
            else if let remoteTrack = callManager.remoteVideoTrack {
                RTCVideoViewRepresentable(videoTrack: remoteTrack)
                    .ignoresSafeArea()
            } else {
                // No remote video - show avatar/status
                VStack(spacing: 16) {
                    Image(systemName: callManager.isVideoEnabled ? "video.fill" : "phone.fill")
                        .font(.system(size: 60))
                        .foregroundColor(.white.opacity(0.4))

                    Text(callManager.currentCall?.displayName ?? "Calling...")
                        .font(.title)
                        .foregroundColor(.white)

                    Text(statusText)
                        .font(.subheadline)
                        .foregroundColor(.white.opacity(0.7))
                }
            }

            // Local video PiP (top-right)
            if let localTrack = callManager.localVideoTrack, callManager.isVideoEnabled {
                VStack {
                    HStack {
                        Spacer()
                        RTCVideoViewRepresentable(videoTrack: localTrack)
                            .frame(width: 160, height: 120)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.white.opacity(0.3), lineWidth: 1)
                            )
                            .shadow(radius: 4)
                            .padding(16)
                    }
                    Spacer()
                }
            }

            // Controls overlay
            if showControls {
                VStack {
                    // Top bar - call info
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(isViewingRemoteScreenShare
                                ? "Screen Share from \(callManager.currentCall?.displayName ?? "")"
                                : (callManager.currentCall?.displayName ?? ""))
                                .font(.headline)
                                .foregroundColor(.white)

                            HStack(spacing: 6) {
                                connectionIndicator
                                Text(statusText)
                                    .font(.caption)
                                    .foregroundColor(.white.opacity(0.7))
                            }
                        }
                        Spacer()
                    }
                    .padding()
                    .background(
                        LinearGradient(
                            colors: [Color.black.opacity(0.6), Color.clear],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )

                    Spacer()

                    // Bottom controls
                    HStack(spacing: 24) {
                        if !isViewingRemoteScreenShare {
                            // Mute button
                            controlButton(
                                icon: callManager.isMuted ? "mic.slash.fill" : "mic.fill",
                                label: callManager.isMuted ? "Unmute" : "Mute",
                                isActive: callManager.isMuted
                            ) {
                                callManager.toggleMute()
                            }

                            // Video toggle
                            controlButton(
                                icon: callManager.isVideoEnabled ? "video.fill" : "video.slash.fill",
                                label: callManager.isVideoEnabled ? "Camera Off" : "Camera On",
                                isActive: !callManager.isVideoEnabled
                            ) {
                                callManager.toggleVideo()
                            }

                            // Screen share toggle
                            if callManager.isScreenSharing {
                                controlButton(
                                    icon: "rectangle.on.rectangle.slash",
                                    label: "Stop Share",
                                    isActive: true
                                ) {
                                    Task {
                                        await callManager.stopScreenSharing(screenCaptureService: screenCaptureService)
                                    }
                                }
                            } else {
                                controlButton(
                                    icon: "rectangle.on.rectangle",
                                    label: "Share Screen",
                                    isActive: false
                                ) {
                                    showScreenSharePicker = true
                                }
                            }
                        }

                        // End call / Stop Viewing
                        Button {
                            appState.endSyncFlowCall()
                        } label: {
                            Image(systemName: isViewingRemoteScreenShare ? "xmark" : "phone.down.fill")
                                .font(.title2)
                                .foregroundColor(.white)
                                .frame(width: 56, height: 56)
                                .background(Color.red)
                                .clipShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .help(isViewingRemoteScreenShare ? "Stop Viewing" : "End Call")
                    }
                    .padding(.bottom, 40)
                    .padding(.horizontal)
                    .background(
                        LinearGradient(
                            colors: [Color.clear, Color.black.opacity(0.6)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                }
            }
        }
        .onAppear { startControlsAutoHide(); startDurationTimer() }
        .onDisappear { controlsTimer?.invalidate(); callDurationTimer?.invalidate() }
        .onTapGesture { toggleControls() }
        .onKeyPress("m") {
            callManager.toggleMute()
            return .handled
        }
        .onKeyPress("v") {
            callManager.toggleVideo()
            return .handled
        }
        .onKeyPress(.escape) {
            appState.endSyncFlowCall()
            return .handled
        }
        .sheet(isPresented: $showScreenSharePicker) {
            ScreenSharePicker(
                screenCaptureService: screenCaptureService,
                onSelectDisplay: { display in
                    showScreenSharePicker = false
                    Task {
                        try? await callManager.startScreenSharing(
                            display: display,
                            screenCaptureService: screenCaptureService
                        )
                    }
                },
                onSelectWindow: { window in
                    showScreenSharePicker = false
                    Task {
                        try? await callManager.startWindowSharing(
                            window: window,
                            screenCaptureService: screenCaptureService
                        )
                    }
                },
                onCancel: { showScreenSharePicker = false }
            )
        }
    }

    // MARK: - Helpers

    /// True when we're viewing a remote screen share (not sending one)
    private var isViewingRemoteScreenShare: Bool {
        callManager.screenShareTrack != nil && !callManager.isScreenSharing
    }

    private var statusText: String {
        switch callManager.callState {
        case .idle: return ""
        case .initializing: return "Initializing..."
        case .ringing: return "Ringing..."
        case .connecting: return "Connecting..."
        case .connected:
            return isViewingRemoteScreenShare ? "Viewing screen share" : formattedDuration
        case .failed(let msg): return "Failed: \(msg)"
        case .ended: return "Call Ended"
        }
    }

    private var formattedDuration: String {
        // Derive duration from answeredAt so it survives view recreation
        let elapsed: TimeInterval
        if let answeredAt = callManager.currentCall?.answeredAt {
            elapsed = now.timeIntervalSince(answeredAt)
        } else {
            elapsed = 0
        }
        let totalSeconds = max(0, Int(elapsed))
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }

    @ViewBuilder
    private var connectionIndicator: some View {
        Circle()
            .fill(connectionColor)
            .frame(width: 8, height: 8)
    }

    private var connectionColor: Color {
        switch callManager.callState {
        case .connected:
            // Use stats-based quality when connected
            switch callManager.connectionQuality {
            case .good, .excellent: return .green
            case .fair: return .yellow
            case .poor: return .red
            case .unknown: return .green
            }
        case .connecting: return .yellow
        case .failed: return .red
        default: return .gray
        }
    }

    private func controlButton(icon: String, label: String, isActive: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.title3)
                    .foregroundColor(.white)
                    .frame(width: 48, height: 48)
                    .background(isActive ? Color.white.opacity(0.3) : Color.white.opacity(0.15))
                    .clipShape(Circle())

                Text(label)
                    .font(.caption2)
                    .foregroundColor(.white.opacity(0.8))
            }
        }
        .buttonStyle(.plain)
    }

    private func toggleControls() {
        showControls.toggle()
        if showControls { startControlsAutoHide() }
    }

    private func startControlsAutoHide() {
        controlsTimer?.invalidate()
        controlsTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: false) { _ in
            if callManager.callState == .connected {
                withAnimation { showControls = false }
            }
        }
    }

    private func startDurationTimer() {
        callDurationTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            if callManager.callState == .connected {
                now = Date()
            }
        }
    }
}

// MARK: - Incoming SyncFlow Call View

struct IncomingSyncFlowCallView: View {
    let call: SyncFlowCall
    let onAcceptVideo: () -> Void
    let onAcceptAudio: () -> Void
    let onDecline: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.85).ignoresSafeArea()

            VStack(spacing: 24) {
                // Caller avatar
                Image(systemName: "person.circle.fill")
                    .font(.system(size: 80))
                    .foregroundColor(.white.opacity(0.6))

                Text("Incoming Call")
                    .font(.title2)
                    .foregroundColor(.white)

                Text(call.callerName)
                    .font(.title3)
                    .foregroundColor(.white.opacity(0.9))

                Text(call.callType == .video ? "Video Call" : "Audio Call")
                    .font(.subheadline)
                    .foregroundColor(.white.opacity(0.6))

                HStack(spacing: 40) {
                    // Decline
                    Button {
                        onDecline()
                    } label: {
                        VStack(spacing: 8) {
                            Image(systemName: "phone.down.fill")
                                .font(.title2)
                                .foregroundColor(.white)
                                .frame(width: 56, height: 56)
                                .background(Color.red)
                                .clipShape(Circle())

                            Text("Decline")
                                .font(.caption)
                                .foregroundColor(.white.opacity(0.8))
                        }
                    }
                    .buttonStyle(.plain)

                    // Accept Audio
                    Button {
                        onAcceptAudio()
                    } label: {
                        VStack(spacing: 8) {
                            Image(systemName: "phone.fill")
                                .font(.title2)
                                .foregroundColor(.white)
                                .frame(width: 56, height: 56)
                                .background(Color.green)
                                .clipShape(Circle())

                            Text("Audio")
                                .font(.caption)
                                .foregroundColor(.white.opacity(0.8))
                        }
                    }
                    .buttonStyle(.plain)

                    // Accept Video (only for video calls)
                    if call.callType == .video {
                        Button {
                            onAcceptVideo()
                        } label: {
                            VStack(spacing: 8) {
                                Image(systemName: "video.fill")
                                    .font(.title2)
                                    .foregroundColor(.white)
                                    .frame(width: 56, height: 56)
                                    .background(Color.green)
                                    .clipShape(Circle())

                                Text("Video")
                                    .font(.caption)
                                    .foregroundColor(.white.opacity(0.8))
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.top, 16)
            }
        }
    }
}

// MARK: - Preview

#Preview {
    SyncFlowCallView(callManager: SyncFlowCallManager(), screenCaptureService: ScreenCaptureService())
}
