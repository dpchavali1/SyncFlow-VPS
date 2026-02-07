//
//  ScreenCaptureService.swift
//  SyncFlowMac
//
//  VPS-only version - WebRTC removed.
//  Screen sharing is not available in VPS mode.
//

import Foundation
import ScreenCaptureKit
import Combine
import CoreMedia
import AppKit
import SwiftUI

// MARK: - ScreenCaptureService (Stub - WebRTC Removed)

/// Stub screen capture service - WebRTC has been removed in VPS mode.
/// Screen sharing requires WebRTC for real-time video transmission.
@available(macOS 12.3, *)
class ScreenCaptureService: NSObject, ObservableObject {

    // MARK: - Published State

    @Published var isScreenSharing = false
    @Published var hasPermission = false
    @Published var availableDisplays: [SCDisplay] = []
    @Published var availableWindows: [SCWindow] = []
    @Published var selectedDisplay: SCDisplay?
    @Published var selectedWindow: SCWindow?

    // MARK: - Stub Video Types

    /// Stub type for video source (WebRTC removed)
    var videoSource: Any?
    /// Stub type for video track (WebRTC removed)
    var videoTrack: Any?

    // MARK: - Initialization

    override init() {
        super.init()
        print("[ScreenCapture] WebRTC removed - screen sharing disabled in VPS mode")
        checkPermission()
    }

    // MARK: - Permission

    func checkPermission() {
        Task {
            do {
                let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
                await MainActor.run {
                    self.hasPermission = true
                    self.availableDisplays = content.displays
                    self.availableWindows = content.windows.filter { $0.isOnScreen }
                }
            } catch {
                print("[ScreenCapture] Permission check failed: \(error)")
                await MainActor.run {
                    self.hasPermission = false
                }
            }
        }
    }

    func requestPermission() {
        // Open System Settings to Screen Recording permissions
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") {
            NSWorkspace.shared.open(url)
        }
    }

    // MARK: - Content Refresh

    func refreshAvailableContent() async {
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            await MainActor.run {
                self.availableDisplays = content.displays
                self.availableWindows = content.windows.filter {
                    $0.isOnScreen && $0.owningApplication?.bundleIdentifier != Bundle.main.bundleIdentifier
                }
            }
        } catch {
            print("[ScreenCapture] Failed to refresh content: \(error)")
        }
    }

    // MARK: - Start Screen Sharing (Disabled)

    func startSharingDisplay(_ display: SCDisplay) async throws {
        print("[ScreenCapture] startSharingDisplay() - WebRTC removed, screen sharing disabled")
        throw ScreenCaptureError.notSupported
    }

    func startSharingWindow(_ window: SCWindow) async throws {
        print("[ScreenCapture] startSharingWindow() - WebRTC removed, screen sharing disabled")
        throw ScreenCaptureError.notSupported
    }

    // MARK: - Stop Screen Sharing

    func stopSharing() async {
        await MainActor.run {
            isScreenSharing = false
            selectedDisplay = nil
            selectedWindow = nil
        }
        print("[ScreenCapture] Stopped screen sharing")
    }

    // MARK: - Get Video Track (Disabled)

    func getVideoTrack() -> Any? {
        return nil
    }

    // MARK: - Error

    enum ScreenCaptureError: Error, LocalizedError {
        case notSupported
        case permissionDenied

        var errorDescription: String? {
            switch self {
            case .notSupported:
                return "Screen sharing is not available in VPS mode (WebRTC removed)"
            case .permissionDenied:
                return "Screen recording permission denied"
            }
        }
    }
}

// MARK: - SCStreamDelegate

@available(macOS 12.3, *)
extension ScreenCaptureService: SCStreamDelegate {
    func stream(_ stream: SCStream, didStopWithError error: Error) {
        print("[ScreenCapture] Stream stopped with error: \(error)")
        Task {
            await stopSharing()
        }
    }
}

// MARK: - Screen Share Picker View

@available(macOS 12.3, *)
struct ScreenSharePicker: View {
    @ObservedObject var screenCaptureService: ScreenCaptureService
    let onSelect: () -> Void
    let onCancel: () -> Void

    @State private var selectedTab = 0
    @State private var showError = false

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("Share Your Screen")
                    .font(.title2)
                    .fontWeight(.bold)
                Spacer()
                Button(action: onCancel) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }
            .padding()

            Divider()

            // VPS Mode Notice
            VStack(spacing: 16) {
                Image(systemName: "rectangle.on.rectangle.slash")
                    .font(.system(size: 60))
                    .foregroundColor(.secondary)

                Text("Screen Sharing Not Available")
                    .font(.title3)
                    .fontWeight(.semibold)

                Text("Screen sharing requires WebRTC, which has been disabled in VPS mode.")
                    .font(.body)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            Divider()

            // Footer
            HStack {
                Spacer()
                Button("Close") {
                    onCancel()
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()
        }
        .frame(width: 400, height: 300)
    }
}

@available(macOS 12.3, *)
struct DisplayPreviewCard: View {
    let display: SCDisplay
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            VStack(spacing: 8) {
                // Display preview placeholder
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.gray.opacity(0.2))
                    .aspectRatio(16/10, contentMode: .fit)
                    .overlay(
                        Image(systemName: "display")
                            .font(.system(size: 40))
                            .foregroundColor(.secondary)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(isSelected ? Color.blue : Color.clear, lineWidth: 3)
                    )

                Text("Display \(display.displayID)")
                    .font(.subheadline)
                    .fontWeight(isSelected ? .semibold : .regular)

                Text("\(Int(display.width)) x \(Int(display.height))")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding(8)
            .background(isSelected ? Color.blue.opacity(0.1) : Color.clear)
            .cornerRadius(12)
        }
        .buttonStyle(.plain)
    }
}

@available(macOS 12.3, *)
struct WindowPreviewCard: View {
    let window: SCWindow
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            VStack(spacing: 8) {
                // Window preview placeholder
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.gray.opacity(0.2))
                    .aspectRatio(16/10, contentMode: .fit)
                    .overlay(
                        Image(systemName: "macwindow")
                            .font(.system(size: 40))
                            .foregroundColor(.secondary)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(isSelected ? Color.blue : Color.clear, lineWidth: 3)
                    )

                Text(window.title ?? "Unknown Window")
                    .font(.subheadline)
                    .fontWeight(isSelected ? .semibold : .regular)
                    .lineLimit(1)

                if let app = window.owningApplication?.applicationName {
                    Text(app)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .padding(8)
            .background(isSelected ? Color.blue.opacity(0.1) : Color.clear)
            .cornerRadius(12)
        }
        .buttonStyle(.plain)
    }
}
