//
//  SyncFlowCallView.swift
//  SyncFlowMac
//
//  VPS-only version - WebRTC calling removed.
//

import SwiftUI

struct SyncFlowCallView: View {
    @EnvironmentObject var appState: AppState
    @ObservedObject var callManager: SyncFlowCallManager

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 20) {
                Image(systemName: "video.slash.fill")
                    .font(.system(size: 60))
                    .foregroundColor(.white.opacity(0.5))

                Text("Video Calling Not Available")
                    .font(.title2)
                    .foregroundColor(.white)

                Text("WebRTC calling has been disabled in VPS mode")
                    .font(.subheadline)
                    .foregroundColor(.white.opacity(0.7))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                Button("Close") {
                    appState.showSyncFlowCallView = false
                }
                .buttonStyle(.borderedProminent)
                .padding(.top, 20)
            }
        }
    }
}

// MARK: - Incoming SyncFlow Call View (Stub - WebRTC Removed)

/// Stub view for incoming SyncFlow calls - WebRTC calling disabled in VPS mode
struct IncomingSyncFlowCallView: View {
    let call: SyncFlowCall
    let onAcceptVideo: () -> Void
    let onAcceptAudio: () -> Void
    let onDecline: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.85).ignoresSafeArea()

            VStack(spacing: 24) {
                Image(systemName: "video.slash.fill")
                    .font(.system(size: 60))
                    .foregroundColor(.white.opacity(0.5))

                Text("Incoming Call")
                    .font(.title2)
                    .foregroundColor(.white)

                Text("From: \(call.callerName)")
                    .font(.headline)
                    .foregroundColor(.white.opacity(0.8))

                Text("WebRTC calling is not available in VPS mode")
                    .font(.subheadline)
                    .foregroundColor(.white.opacity(0.6))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                Button("Dismiss") {
                    onDecline()
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
                .padding(.top, 20)
            }
        }
    }
}

// MARK: - Preview

#Preview {
    SyncFlowCallView(callManager: SyncFlowCallManager())
}
