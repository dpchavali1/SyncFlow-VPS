//
//  ScreenShareViewerView.swift
//  SyncFlowMac
//
//  Standalone resizable window for viewing remote screen share from Android.
//

import SwiftUI
import WebRTC

struct ScreenShareViewerView: View {
    @ObservedObject var callManager: SyncFlowCallManager
    let onDisconnect: () -> Void

    @State private var isFullScreen = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let screenTrack = callManager.screenShareTrack {
                RTCVideoViewRepresentable(videoTrack: screenTrack)
                    .ignoresSafeArea()
            } else {
                VStack(spacing: 16) {
                    ProgressView()
                        .scaleEffect(1.5)
                        .tint(.white)

                    Text("Waiting for screen share...")
                        .font(.title3)
                        .foregroundColor(.white.opacity(0.7))
                }
            }

            // Floating toolbar at bottom
            VStack {
                Spacer()

                HStack(spacing: 16) {
                    // Connection indicator
                    HStack(spacing: 6) {
                        Circle()
                            .fill(callManager.screenShareTrack != nil ? Color.green : Color.yellow)
                            .frame(width: 8, height: 8)

                        Text(callManager.screenShareTrack != nil ? "Receiving" : "Connecting")
                            .font(.caption)
                            .foregroundColor(.white.opacity(0.8))
                    }

                    Spacer()

                    // Disconnect button
                    Button {
                        onDisconnect()
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "xmark.circle.fill")
                            Text("Disconnect")
                        }
                        .font(.caption)
                        .foregroundColor(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.red.opacity(0.8))
                        .cornerRadius(8)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color.black.opacity(0.7))
                )
                .padding(.horizontal, 20)
                .padding(.bottom, 16)
            }
        }
        .frame(minWidth: 400, minHeight: 300)
    }
}
