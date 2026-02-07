//
//  SyncFlowCallManager.swift
//  SyncFlowMac
//
//  VPS-only version - WebRTC calling removed.
//  Audio/video calling is not supported in VPS mode.
//

import Foundation
import Combine
import AVFoundation

// MARK: - SyncFlowCallManager (Stub - WebRTC Removed)

/// Stub call manager - WebRTC calling has been removed in VPS mode.
/// This maintains the interface for compilation but calling features are disabled.
class SyncFlowCallManager: NSObject, ObservableObject {

    // MARK: - Published State

    @Published var callState: CallState = .idle
    @Published var currentCall: SyncFlowCall?
    @Published var isMuted = false
    @Published var isVideoEnabled = false
    @Published var isSpeakerOn = true
    @Published var videoEffect: VideoEffect = .none

    // MARK: - Video Tracks (Stub types)

    @Published var localVideoTrack: Any?
    @Published var remoteVideoTrack: Any?
    @Published var remoteAudioTrack: Any?

    // MARK: - Screen Sharing State

    @Published var isScreenSharing = false
    @Published var screenShareTrack: Any?

    // MARK: - Call Recording State

    @Published var isRecordingCall = false
    @Published var recordingDuration: TimeInterval = 0

    // MARK: - Network Quality

    @Published var networkQuality: NetworkQuality = .unknown
    @Published var connectionQuality: ConnectionQuality = .unknown

    // MARK: - Incoming Call

    @Published var incomingUserCall: IncomingUserCall?

    // MARK: - Call State Enum

    enum CallState: Equatable {
        case idle
        case initializing
        case ringing
        case connecting
        case connected
        case failed(String)
        case ended
    }

    // MARK: - Video Effect Enum

    enum VideoEffect: String, CaseIterable {
        case none = "None"
        case faceFocus = "Face Focus"
        case backgroundBlur = "Background Blur"
    }

    // MARK: - Network Quality Enum

    enum NetworkQuality: String {
        case unknown = "Unknown"
        case poor = "Poor"
        case fair = "Fair"
        case good = "Good"
        case excellent = "Excellent"
    }

    // MARK: - Connection Quality Enum

    enum ConnectionQuality: String {
        case unknown = "Unknown"
        case poor = "Poor"
        case fair = "Fair"
        case good = "Good"
        case excellent = "Excellent"
    }

    // MARK: - Incoming User Call

    struct IncomingUserCall {
        let callId: String
        let callerUid: String
        let callerName: String
        let callerPhone: String?
        let callerPlatform: String
        let isVideo: Bool
    }

    // MARK: - Initialization

    override init() {
        super.init()
        print("[SyncFlowCallManager] WebRTC calling disabled in VPS mode")
    }

    // MARK: - Call Methods (Stub implementations)

    func startCall(userId: String, calleeDeviceId: String, calleeName: String, isVideo: Bool) async throws -> String {
        print("[SyncFlowCallManager] startCall() - WebRTC calling disabled")
        throw CallError.notSupported
    }

    func startCallToUser(recipientPhoneNumber: String, recipientName: String, isVideo: Bool) async throws -> String {
        print("[SyncFlowCallManager] startCallToUser() - WebRTC calling disabled")
        throw CallError.notSupported
    }

    func answerCall(userId: String, callId: String, withVideo: Bool) async throws {
        print("[SyncFlowCallManager] answerCall() - WebRTC calling disabled")
        throw CallError.notSupported
    }

    func answerUserCall(callId: String, withVideo: Bool, userId: String) async throws {
        print("[SyncFlowCallManager] answerUserCall() - WebRTC calling disabled")
        throw CallError.notSupported
    }

    func rejectCall(userId: String, callId: String) async throws {
        print("[SyncFlowCallManager] rejectCall() - WebRTC calling disabled")
    }

    func rejectUserCall(callId: String, userId: String) async throws {
        print("[SyncFlowCallManager] rejectUserCall() - WebRTC calling disabled")
    }

    func endCall() async throws {
        print("[SyncFlowCallManager] endCall() - WebRTC calling disabled")
        await MainActor.run {
            callState = .ended
            currentCall = nil
        }
    }

    func toggleMute() {
        isMuted.toggle()
    }

    func toggleVideo() {
        isVideoEnabled.toggle()
    }

    func toggleSpeaker() {
        isSpeakerOn.toggle()
    }

    func setVideoEffect(_ effect: VideoEffect) {
        videoEffect = effect
    }

    func startScreenSharing() async throws {
        print("[SyncFlowCallManager] startScreenSharing() - WebRTC calling disabled")
        throw CallError.notSupported
    }

    func stopScreenSharing() {
        isScreenSharing = false
    }

    func startRecording() throws {
        print("[SyncFlowCallManager] startRecording() - WebRTC calling disabled")
        throw CallError.notSupported
    }

    func stopRecording() -> URL? {
        isRecordingCall = false
        return nil
    }

    func updateDeviceStatus(userId: String, online: Bool) async throws {
        // Stub - device status not managed in VPS mode via this manager
    }

    func startListeningForIncomingUserCalls(userId: String) {
        print("[SyncFlowCallManager] startListeningForIncomingUserCalls() - WebRTC calling disabled")
    }

    func stopListeningForIncomingUserCalls() {
        // No-op
    }

    // MARK: - Call Error

    enum CallError: Error, LocalizedError {
        case notSupported
        case notAuthenticated
        case connectionFailed

        var errorDescription: String? {
            switch self {
            case .notSupported:
                return "Audio/video calling is not available in VPS mode"
            case .notAuthenticated:
                return "Not authenticated"
            case .connectionFailed:
                return "Connection failed"
            }
        }
    }
}

// NOTE: SyncFlowCall and ActiveCall types are defined in:
// - Models/SyncFlowCall.swift
// - Models/ActiveCall.swift
