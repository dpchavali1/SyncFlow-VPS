//
//  ScreenCaptureService.swift
//  SyncFlowMac
//
//  Screen capture service using ScreenCaptureKit and WebRTC.
//  Captures display or window content and feeds it to an RTCVideoSource.
//

import Foundation
import ScreenCaptureKit
import Combine
import CoreMedia
import AppKit
import SwiftUI
import WebRTC

// MARK: - ScreenCaptureService

@available(macOS 12.3, *)
class ScreenCaptureService: NSObject, ObservableObject {

    // MARK: - Published State

    @Published var isScreenSharing = false
    @Published var hasPermission = false
    @Published var availableDisplays: [SCDisplay] = []
    @Published var availableWindows: [SCWindow] = []
    @Published var selectedDisplay: SCDisplay?
    @Published var selectedWindow: SCWindow?

    // MARK: - WebRTC Video

    private var rtcVideoSource: RTCVideoSource?
    private(set) var rtcVideoTrack: RTCVideoTrack?
    private var scStream: SCStream?
    private var dummyCapturer: RTCVideoCapturer?
    private var captureWidth: Int = 1280
    private var captureHeight: Int = 720
    private var captureFps: Int = 15

    static let screenTrackId = "screen0"
    static let screenStreamId = "screen_stream"

    // MARK: - Initialization

    override init() {
        super.init()
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

    // MARK: - Start Screen Sharing

    /// Start sharing a display via WebRTC.
    /// - Parameters:
    ///   - display: The SCDisplay to capture
    ///   - factory: RTCPeerConnectionFactory for creating video source/track
    ///   - width: Capture width (default 1280)
    ///   - height: Capture height (default 720)
    ///   - fps: Frames per second (default 15)
    func startSharingDisplay(_ display: SCDisplay, factory: RTCPeerConnectionFactory, width: Int = 1280, height: Int = 720, fps: Int = 15) async throws {
        guard hasPermission else {
            throw ScreenCaptureError.permissionDenied
        }

        captureWidth = width
        captureHeight = height
        captureFps = fps

        // Create RTCVideoSource and a dummy capturer to feed frames
        rtcVideoSource = factory.videoSource()
        dummyCapturer = RTCVideoCapturer(delegate: rtcVideoSource!)
        rtcVideoTrack = factory.videoTrack(with: rtcVideoSource!, trackId: Self.screenTrackId)
        rtcVideoTrack?.isEnabled = true

        // Create SCContentFilter for the display
        let filter = SCContentFilter(display: display, excludingWindows: [])

        // Configure capture
        let config = SCStreamConfiguration()
        config.width = width
        config.height = height
        config.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        config.pixelFormat = kCVPixelFormatType_32BGRA
        config.showsCursor = true
        if #available(macOS 13.0, *) {
            config.capturesAudio = false
        }

        // Create and start stream
        scStream = SCStream(filter: filter, configuration: config, delegate: self)
        try scStream?.addStreamOutput(self, type: .screen, sampleHandlerQueue: DispatchQueue(label: "com.syncflow.screencapture"))
        try await scStream?.startCapture()

        await MainActor.run {
            self.isScreenSharing = true
            self.selectedDisplay = display
            self.selectedWindow = nil
        }

        print("[ScreenCapture] Started sharing display \(display.displayID) at \(width)x\(height)@\(fps)fps")
    }

    /// Start sharing a specific window via WebRTC.
    func startSharingWindow(_ window: SCWindow, factory: RTCPeerConnectionFactory, width: Int = 1280, height: Int = 720, fps: Int = 15) async throws {
        guard hasPermission else {
            throw ScreenCaptureError.permissionDenied
        }

        captureWidth = width
        captureHeight = height
        captureFps = fps

        rtcVideoSource = factory.videoSource()
        dummyCapturer = RTCVideoCapturer(delegate: rtcVideoSource!)
        rtcVideoTrack = factory.videoTrack(with: rtcVideoSource!, trackId: Self.screenTrackId)
        rtcVideoTrack?.isEnabled = true

        let filter = SCContentFilter(desktopIndependentWindow: window)

        let config = SCStreamConfiguration()
        config.width = width
        config.height = height
        config.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        config.pixelFormat = kCVPixelFormatType_32BGRA
        config.showsCursor = true
        if #available(macOS 13.0, *) {
            config.capturesAudio = false
        }

        scStream = SCStream(filter: filter, configuration: config, delegate: self)
        try scStream?.addStreamOutput(self, type: .screen, sampleHandlerQueue: DispatchQueue(label: "com.syncflow.screencapture"))
        try await scStream?.startCapture()

        await MainActor.run {
            self.isScreenSharing = true
            self.selectedWindow = window
            self.selectedDisplay = nil
        }

        print("[ScreenCapture] Started sharing window: \(window.title ?? "Unknown")")
    }

    // MARK: - Stop Screen Sharing

    func stopSharing() async {
        if let stream = scStream {
            try? await stream.stopCapture()
            scStream = nil
        }

        rtcVideoTrack?.isEnabled = false
        rtcVideoTrack = nil
        rtcVideoSource = nil
        dummyCapturer = nil

        await MainActor.run {
            isScreenSharing = false
            selectedDisplay = nil
            selectedWindow = nil
        }
        print("[ScreenCapture] Stopped screen sharing")
    }

    // MARK: - Get Video Track

    func getVideoTrack() -> RTCVideoTrack? {
        return rtcVideoTrack
    }

    // MARK: - Quality Presets

    enum QualityPreset {
        case low       // 480p @ 10fps
        case balanced  // 720p @ 15fps
        case high      // 1080p @ 30fps

        var width: Int {
            switch self {
            case .low: return 854
            case .balanced: return 1280
            case .high: return 1920
            }
        }

        var height: Int {
            switch self {
            case .low: return 480
            case .balanced: return 720
            case .high: return 1080
            }
        }

        var fps: Int {
            switch self {
            case .low: return 10
            case .balanced: return 15
            case .high: return 30
            }
        }
    }

    // MARK: - Error

    enum ScreenCaptureError: Error, LocalizedError {
        case notSupported
        case permissionDenied
        case captureStartFailed

        var errorDescription: String? {
            switch self {
            case .notSupported:
                return "Screen sharing is not supported on this system"
            case .permissionDenied:
                return "Screen recording permission denied"
            case .captureStartFailed:
                return "Failed to start screen capture"
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

// MARK: - SCStreamOutput

@available(macOS 12.3, *)
extension ScreenCaptureService: SCStreamOutput {
    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen else { return }
        guard let capturer = dummyCapturer else { return }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let timeStampNs = Int64(CMTimeGetSeconds(timestamp) * 1_000_000_000)

        let rtcPixelBuffer = RTCCVPixelBuffer(pixelBuffer: pixelBuffer)
        let videoFrame = RTCVideoFrame(
            buffer: rtcPixelBuffer,
            rotation: ._0,
            timeStampNs: timeStampNs
        )

        // Feed frame to RTCVideoSource via the delegate pattern
        rtcVideoSource?.capturer(capturer, didCapture: videoFrame)
    }
}

// MARK: - Screen Share Picker View

@available(macOS 12.3, *)
struct ScreenSharePicker: View {
    @ObservedObject var screenCaptureService: ScreenCaptureService
    let onSelectDisplay: (SCDisplay) -> Void
    let onSelectWindow: (SCWindow) -> Void
    let onCancel: () -> Void

    @State private var selectedTab = 0

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

            if !screenCaptureService.hasPermission {
                // Permission required
                VStack(spacing: 16) {
                    Image(systemName: "lock.shield")
                        .font(.system(size: 50))
                        .foregroundColor(.secondary)

                    Text("Screen Recording Permission Required")
                        .font(.title3)
                        .fontWeight(.semibold)

                    Text("Grant Screen Recording permission in System Settings to share your screen.")
                        .font(.body)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)

                    Button("Open System Settings") {
                        screenCaptureService.requestPermission()
                    }
                    .buttonStyle(.borderedProminent)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                // Tab picker: Displays vs Windows
                Picker("", selection: $selectedTab) {
                    Text("Displays").tag(0)
                    Text("Windows").tag(1)
                }
                .pickerStyle(.segmented)
                .padding()

                ScrollView {
                    if selectedTab == 0 {
                        // Displays
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 200))], spacing: 12) {
                            ForEach(screenCaptureService.availableDisplays, id: \.displayID) { display in
                                DisplayPreviewCard(
                                    display: display,
                                    isSelected: screenCaptureService.selectedDisplay?.displayID == display.displayID,
                                    onSelect: { onSelectDisplay(display) }
                                )
                            }
                        }
                        .padding()
                    } else {
                        // Windows
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 200))], spacing: 12) {
                            ForEach(screenCaptureService.availableWindows, id: \.windowID) { window in
                                WindowPreviewCard(
                                    window: window,
                                    isSelected: false,
                                    onSelect: { onSelectWindow(window) }
                                )
                            }
                        }
                        .padding()

                        if screenCaptureService.availableWindows.isEmpty {
                            Text("No windows available")
                                .foregroundColor(.secondary)
                                .padding()
                        }
                    }
                }
            }

            Divider()

            // Footer
            HStack {
                Button("Refresh") {
                    Task { await screenCaptureService.refreshAvailableContent() }
                }
                Spacer()
                Button("Cancel") {
                    onCancel()
                }
            }
            .padding()
        }
        .frame(width: 500, height: 400)
        .onAppear {
            Task { await screenCaptureService.refreshAvailableContent() }
        }
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
