//
//  SyncFlowCallManager.swift
//  SyncFlowMac
//
//  WebRTC calling manager for audio/video calls between devices.
//

import Foundation
import Combine
import AVFoundation
import WebRTC

// MARK: - SyncFlowCallManager

class SyncFlowCallManager: NSObject, ObservableObject {

    // MARK: - Published State

    @Published var callState: CallState = .idle
    @Published var currentCall: SyncFlowCall?
    @Published var isMuted = false
    @Published var isVideoEnabled = false
    @Published var isSpeakerOn = true
    @Published var videoEffect: VideoEffect = .none

    // MARK: - Video Tracks

    @Published var localVideoTrack: RTCVideoTrack?
    @Published var remoteVideoTrack: RTCVideoTrack?
    @Published var remoteAudioTrack: RTCAudioTrack?

    // MARK: - Screen Sharing State

    @Published var isScreenSharing = false
    @Published var screenShareTrack: RTCVideoTrack?

    // MARK: - Network Quality

    @Published var networkQuality: NetworkQuality = .unknown
    @Published var connectionQuality: ConnectionQuality = .unknown

    // MARK: - Incoming Call

    @Published var incomingUserCall: IncomingUserCall?

    // MARK: - Enums

    enum CallState: Equatable {
        case idle
        case initializing
        case ringing
        case connecting
        case connected
        case failed(String)
        case ended
    }

    enum VideoEffect: String, CaseIterable {
        case none = "None"
        case faceFocus = "Face Focus"
        case backgroundBlur = "Background Blur"
    }

    enum NetworkQuality: String {
        case unknown = "Unknown"
        case poor = "Poor"
        case fair = "Fair"
        case good = "Good"
        case excellent = "Excellent"
    }

    enum ConnectionQuality: String {
        case unknown = "Unknown"
        case poor = "Poor"
        case fair = "Fair"
        case good = "Good"
        case excellent = "Excellent"
    }

    struct IncomingUserCall {
        let callId: String
        let callerUid: String
        let callerName: String
        let callerPhone: String?
        let callerPlatform: String
        let isVideo: Bool
    }

    // MARK: - WebRTC Properties

    private static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        let encoderFactory = RTCDefaultVideoEncoderFactory()
        let decoderFactory = RTCDefaultVideoDecoderFactory()
        return RTCPeerConnectionFactory(encoderFactory: encoderFactory, decoderFactory: decoderFactory)
    }()

    private var peerConnection: RTCPeerConnection?
    private var localAudioTrack: RTCAudioTrack?
    private var videoCapturer: RTCCameraVideoCapturer?
    private var pendingIceCandidates: [RTCIceCandidate] = []
    private var pendingOffer: (callId: String, data: [String: Any])?
    private var hasRemoteDescription = false
    private var currentCallId: String?
    private var isEndingCall = false // Guard against concurrent endCall() calls
    private var ringingTimeoutTask: Task<Void, Never>?
    private var disconnectTimeoutTask: Task<Void, Never>?
    private var answerPollingTask: Task<Void, Never>?
    private var icePollingTask: Task<Void, Never>?
    private var cancellables = Set<AnyCancellable>()
    private var signalObserver: NSObjectProtocol?
    private var incomingCallObserver: NSObjectProtocol?
    private var callStatusObserver: NSObjectProtocol?
    private var processedIceCandidates = Set<String>()
    private var isCallInitiator = false
    private var iceRestartAttempted = false

    // MARK: - Initialization

    override init() {
        super.init()
        print("[SyncFlowCallManager] WebRTC calling initialized")
        setupSignalObserver()
    }

    deinit {
        if let observer = signalObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        if let observer = incomingCallObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        if let observer = callStatusObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    // MARK: - Signal Observer

    private func setupSignalObserver() {
        signalObserver = NotificationCenter.default.addObserver(
            forName: .webRTCSignalReceived,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let userInfo = notification.userInfo,
                  let callId = userInfo["callId"] as? String,
                  let signalType = userInfo["signalType"] as? String,
                  let signalData = userInfo["signalData"] else {
                return
            }
            self?.handleSignal(callId: callId, signalType: signalType, signalData: signalData)
        }

        incomingCallObserver = NotificationCenter.default.addObserver(
            forName: .syncFlowCallIncoming,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let userInfo = notification.userInfo,
                  let callId = userInfo["callId"] as? String,
                  let callerId = userInfo["callerId"] as? String,
                  let callerName = userInfo["callerName"] as? String,
                  let callerPlatform = userInfo["callerPlatform"] as? String,
                  let callType = userInfo["callType"] as? String else {
                return
            }
            self?.handleIncomingCall(
                callId: callId,
                callerId: callerId,
                callerName: callerName,
                callerPlatform: callerPlatform,
                callType: callType
            )
        }

        callStatusObserver = NotificationCenter.default.addObserver(
            forName: .syncFlowCallStatusChanged,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let userInfo = notification.userInfo,
                  let callId = userInfo["callId"] as? String,
                  let status = userInfo["status"] as? String else {
                return
            }
            self?.handleCallStatusChange(callId: callId, status: status)
        }
    }

    // MARK: - Incoming Call Handling

    private func handleIncomingCall(callId: String, callerId: String, callerName: String, callerPlatform: String, callType: String) {
        print("[SyncFlowCallManager] Incoming call: \(callId) from \(callerName)")
        incomingUserCall = IncomingUserCall(
            callId: callId,
            callerUid: callerId,
            callerName: callerName,
            callerPhone: nil,
            callerPlatform: callerPlatform,
            isVideo: callType == "video"
        )
    }

    private func handleCallStatusChange(callId: String, status: String) {
        // Accept status updates for current call OR for an incoming call we haven't answered yet
        guard callId == currentCallId || callId == incomingUserCall?.callId else { return }
        print("[SyncFlowCallManager] Call \(callId) status changed: \(status)")

        switch status {
        case "ended", "rejected", "missed", "failed":
            // Synchronous guard: prevent multiple endCall() tasks from rapid WS messages
            guard !isEndingCall else { return }
            isEndingCall = true
            // Clear currentCallId synchronously to prevent further signals/status for this call
            currentCallId = nil
            Task { try? await endCall() }
        case "active":
            callState = .connected
            disconnectTimeoutTask?.cancel()
            ringingTimeoutTask?.cancel()
            currentCall?.status = .active
            if currentCall?.answeredAt == nil {
                currentCall?.answeredAt = Date()
            }
        default:
            break
        }
    }

    // MARK: - Call Methods

    func startCall(userId: String, calleeDeviceId: String, calleeName: String, isVideo: Bool) async throws -> String {
        await MainActor.run {
            callState = .initializing
            isVideoEnabled = isVideo
            isEndingCall = false
        }
        isCallInitiator = true
        iceRestartAttempted = false
        processedIceCandidates.removeAll()
        icePollingTask?.cancel()
        icePollingTask = nil

        do {
            // 1. Create call on server
            let vps = VPSService.shared
            let callResult = try await vps.createSyncFlowCall(
                calleeId: calleeDeviceId,
                calleeName: calleeName,
                callType: isVideo ? "video" : "audio"
            )

            guard let callId = callResult["callId"] ?? callResult["id"] else {
                throw CallError.connectionFailed
            }

            currentCallId = callId

            // 2. Create SyncFlowCall model
            let call = SyncFlowCall.createOutgoing(
                callId: callId,
                callerId: userId,
                callerName: "Me",
                calleeId: calleeDeviceId,
                calleeName: calleeName,
                isVideo: isVideo
            )
            await MainActor.run {
                currentCall = call
                callState = .ringing
            }

            // 3. Start ringing timeout (60 seconds)
            startRingingTimeout()

            // 4. Fetch TURN credentials
            let turnCreds = try await vps.getTurnCredentials()
            let iceServers = parseIceServers(from: turnCreds)

            // 5. Create PeerConnection
            try await createPeerConnection(iceServers: iceServers, isVideo: isVideo)

            // 6. Create and send offer
            let offer = try await createOffer()
            let offerData: [String: Any] = ["sdp": offer.sdp, "type": RTCSessionDescription.string(for: offer.type)]
            try await vps.sendSignal(callId: callId, signalType: "offer", signalData: offerData)

            // 7. Start answer polling as fallback if WebSocket misses the answer
            startAnswerPolling(callId: callId)

            print("[SyncFlowCallManager] Call started: \(callId)")
            return callId

        } catch {
            await MainActor.run {
                callState = .failed(error.localizedDescription)
            }
            cleanupWebRTC()
            throw error
        }
    }

    func startCallToUser(recipientPhoneNumber: String, recipientName: String, isVideo: Bool) async throws -> String {
        let normalizedPhone = PhoneNumberNormalizer.shared.toE164(recipientPhoneNumber)
        return try await startCall(userId: "", calleeDeviceId: normalizedPhone, calleeName: recipientName, isVideo: isVideo)
    }

    func answerCall(userId: String, callId: String, withVideo: Bool) async throws {
        try await answerUserCall(callId: callId, withVideo: withVideo, userId: userId)
    }

    func answerUserCall(callId: String, withVideo: Bool, userId: String) async throws {
        await MainActor.run {
            callState = .connecting
            isVideoEnabled = withVideo
            isEndingCall = false
        }

        isCallInitiator = false
        iceRestartAttempted = false
        currentCallId = callId
        processedIceCandidates.removeAll()
        icePollingTask?.cancel()
        icePollingTask = nil

        do {
            let vps = VPSService.shared

            // 1. Update call status to active
            try await vps.updateSyncFlowCallStatus(callId: callId, status: "active")

            // 2. Set currentCall model from incomingUserCall data so the UI shows caller info
            if let incoming = incomingUserCall, incoming.callId == callId {
                let call = SyncFlowCall(
                    id: callId,
                    callerId: incoming.callerUid,
                    callerName: incoming.callerName,
                    callerPlatform: incoming.callerPlatform,
                    calleeId: userId,
                    calleeName: "Me",
                    calleePlatform: "macos",
                    callType: incoming.isVideo ? .video : .audio,
                    status: .active,
                    startedAt: Date(),
                    answeredAt: Date(),
                    endedAt: nil,
                    offer: nil,
                    answer: nil
                )
                await MainActor.run {
                    currentCall = call
                }
            }

            // 3. Fetch TURN credentials
            let turnCreds = try await vps.getTurnCredentials()
            let iceServers = parseIceServers(from: turnCreds)

            // 4. Create PeerConnection (preserves any pendingIceCandidates accumulated before)
            try await createPeerConnection(iceServers: iceServers, isVideo: withVideo)

            // 5. Process all pending signals (offer + ICE candidates) from REST
            let signals = try await vps.getSignals(callId: callId)
            print("[SyncFlowCallManager] Fetched \(signals.count) pending signals for call \(callId)")
            for signal in signals {
                let signalType = signal["signalType"] as? String ?? signal["signal_type"] as? String
                let signalDataRaw = signal["signalData"] ?? signal["signal_data"]
                guard let signalType = signalType, let signalDataRaw = signalDataRaw else { continue }
                let signalData: Any
                if let str = signalDataRaw as? String,
                   let data = str.data(using: .utf8),
                   let parsed = try? JSONSerialization.jsonObject(with: data) {
                    signalData = parsed
                } else {
                    signalData = signalDataRaw
                }
                handleSignal(callId: callId, signalType: signalType, signalData: signalData)
            }

            await MainActor.run {
                incomingUserCall = nil
            }

            print("[SyncFlowCallManager] Answering call: \(callId)")

        } catch {
            await MainActor.run {
                callState = .failed(error.localizedDescription)
            }
            cleanupWebRTC()
            throw error
        }
    }

    func rejectCall(userId: String, callId: String) async throws {
        try await rejectUserCall(callId: callId, userId: userId)
    }

    func rejectUserCall(callId: String, userId: String) async throws {
        try await VPSService.shared.updateSyncFlowCallStatus(callId: callId, status: "rejected")
        await MainActor.run {
            incomingUserCall = nil
        }
    }

    func endCall() async throws {
        let endingCallId = currentCallId

        // Clear call ID immediately to prevent re-entry and unblock new calls
        currentCallId = nil
        ringingTimeoutTask?.cancel()
        ringingTimeoutTask = nil
        disconnectTimeoutTask?.cancel()
        disconnectTimeoutTask = nil
        answerPollingTask?.cancel()
        answerPollingTask = nil

        if let callId = endingCallId {
            try? await VPSService.shared.updateSyncFlowCallStatus(callId: callId, status: "ended")
            try? await VPSService.shared.deleteSignals(callId: callId)
        }

        cleanupWebRTC()

        await MainActor.run {
            callState = .ended
            currentCall?.status = .ended
            currentCall?.endedAt = Date()
            // Reset after brief delay so UI can show "ended" state
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                guard self?.callState == .ended else { return }
                self?.callState = .idle
                self?.currentCall = nil
                self?.isEndingCall = false
            }
        }
    }

    // MARK: - Ringing Timeout

    private func startRingingTimeout() {
        ringingTimeoutTask?.cancel()
        ringingTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(SyncFlowCall.callTimeoutSeconds) * 1_000_000_000)
            guard !Task.isCancelled else { return }
            guard let self = self else { return }
            // Only timeout if still ringing
            if self.callState == .ringing {
                print("[SyncFlowCallManager] Call timed out after \(SyncFlowCall.callTimeoutSeconds)s")
                await MainActor.run {
                    self.callState = .failed("No answer")
                }
                try? await self.endCall()
            }
        }
    }

    // MARK: - Answer Polling (Caller Fallback)

    /// Poll for answer signal as fallback if WebSocket misses it.
    private func startAnswerPolling(callId: String) {
        answerPollingTask?.cancel()
        answerPollingTask = Task { [weak self] in
            for _ in 0..<120 { // Poll up to 60 seconds (every 500ms)
                guard !Task.isCancelled else { return }
                guard let self = self, self.currentCallId == callId, !self.hasRemoteDescription else { return }

                do {
                    let signals = try await VPSService.shared.getSignals(callId: callId)
                    let answerSignal = signals.first { ($0["signalType"] as? String) == "answer" }
                    if let answerSignal = answerSignal, !self.hasRemoteDescription {
                        print("[SyncFlowCallManager] Answer received via polling fallback")
                        if let signalData = answerSignal["signalData"] {
                            await MainActor.run {
                                self.handleSignal(callId: callId, signalType: "answer", signalData: signalData)
                            }
                        }
                        // Also process any ICE candidates
                        for signal in signals {
                            if (signal["signalType"] as? String) == "ice-candidate",
                               let data = signal["signalData"] {
                                await MainActor.run {
                                    self.handleSignal(callId: callId, signalType: "ice-candidate", signalData: data)
                                }
                            }
                        }
                        return
                    }
                } catch {
                    print("[SyncFlowCallManager] Answer polling error: \(error.localizedDescription)")
                }

                try? await Task.sleep(nanoseconds: 500_000_000)
            }
        }
    }

    // MARK: - ICE Restart

    /// Attempt ICE restart by creating a new offer with iceRestart flag.
    /// Only the call initiator (caller) should initiate ICE restart to avoid glare.
    private func attemptIceRestart() async {
        guard let callId = currentCallId, let pc = peerConnection else { return }
        print("[SyncFlowCallManager] Attempting ICE restart for call \(callId)")
        iceRestartAttempted = true

        do {
            // Mark ICE transport for restart - next offer will include iceRestart
            pc.restartIce()

            // Clear processed ICE candidates so new ones can be accepted
            processedIceCandidates.removeAll()

            // Create new offer (automatically includes iceRestart after restartIce())
            let offer = try await createOffer()
            let offerData: [String: Any] = ["sdp": offer.sdp, "type": RTCSessionDescription.string(for: offer.type)]
            try await VPSService.shared.sendSignal(callId: callId, signalType: "offer", signalData: offerData)
            print("[SyncFlowCallManager] ICE restart offer sent for call \(callId)")
        } catch {
            print("[SyncFlowCallManager] ICE restart failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Controls

    func toggleMute() {
        isMuted.toggle()
        localAudioTrack?.isEnabled = !isMuted
    }

    func toggleVideo() {
        isVideoEnabled.toggle()
        localVideoTrack?.isEnabled = isVideoEnabled
        if !isVideoEnabled {
            videoCapturer?.stopCapture()
        } else {
            startVideoCapture()
        }
    }

    func toggleSpeaker() {
        isSpeakerOn.toggle()
    }

    func setVideoEffect(_ effect: VideoEffect) {
        videoEffect = effect
    }

    func startScreenSharing() async throws {
        throw CallError.notSupported
    }

    func stopScreenSharing() {
        isScreenSharing = false
    }

    func updateDeviceStatus(userId: String, online: Bool) async throws {
        // Device status is tracked via WebSocket connection
    }

    func startListeningForIncomingUserCalls(userId: String) {
        print("[SyncFlowCallManager] Listening for incoming calls via WebSocket")
    }

    func stopListeningForIncomingUserCalls() {
        // No-op - WebSocket handles this
    }

    // MARK: - WebRTC Signal Handling

    private func handleSignal(callId: String, signalType: String, signalData: Any) {
        // Accept signals for our current call, or for an incoming call we're about to answer
        let isForCurrentCall = callId == currentCallId
        let isForIncomingCall = currentCallId == nil && (signalType == "offer" || incomingUserCall?.callId == callId)
        guard isForCurrentCall || isForIncomingCall else {
            print("[SyncFlowCallManager] Ignoring signal for \(callId) (current: \(currentCallId ?? "nil"), signal: \(signalType))")
            return
        }

        let dataDict: [String: Any]
        if let dict = signalData as? [String: Any] {
            dataDict = dict
        } else if let str = signalData as? String,
                  let data = str.data(using: .utf8),
                  let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            dataDict = parsed
        } else {
            print("[SyncFlowCallManager] Cannot parse signal data")
            return
        }

        switch signalType {
        case "offer":
            handleOfferSignal(callId: callId, data: dataDict)
        case "answer":
            handleAnswerSignal(data: dataDict)
        case "ice-candidate":
            handleIceCandidateSignal(data: dataDict)
        default:
            print("[SyncFlowCallManager] Unknown signal type: \(signalType)")
        }
    }

    private func handleOfferSignal(callId: String, data: [String: Any]) {
        guard let sdp = data["sdp"] as? String else {
            print("[SyncFlowCallManager] Offer missing SDP")
            return
        }

        // Allow renegotiation offers (ICE restart) when already connected
        let isRenegotiation = hasRemoteDescription
        if isRenegotiation {
            print("[SyncFlowCallManager] Processing renegotiation offer (ICE restart) for call \(callId)")
            processedIceCandidates.removeAll()
        }

        let typeStr = data["type"] as? String ?? "offer"
        let sdpType = RTCSessionDescription.type(for: typeStr)
        let remoteDescription = RTCSessionDescription(type: sdpType, sdp: sdp)

        guard let pc = peerConnection else {
            print("[SyncFlowCallManager] No PeerConnection for offer - queuing for later")
            pendingOffer = (callId: callId, data: data)
            return
        }

        pc.setRemoteDescription(remoteDescription) { [weak self] error in
            if let error = error {
                print("[SyncFlowCallManager] Failed to set remote description: \(error)")
                return
            }

            self?.hasRemoteDescription = true

            if !isRenegotiation {
                self?.drainPendingIceCandidates()
            }

            // Create answer
            let constraints = RTCMediaConstraints(
                mandatoryConstraints: ["OfferToReceiveAudio": "true", "OfferToReceiveVideo": "true"],
                optionalConstraints: nil
            )
            pc.answer(for: constraints) { [weak self] answer, error in
                if let error = error {
                    print("[SyncFlowCallManager] Failed to create answer: \(error)")
                    return
                }
                guard let answer = answer else { return }

                pc.setLocalDescription(answer) { [weak self] error in
                    if let error = error {
                        print("[SyncFlowCallManager] Failed to set local description: \(error)")
                        return
                    }

                    let answerData: [String: Any] = [
                        "sdp": answer.sdp,
                        "type": RTCSessionDescription.string(for: answer.type)
                    ]
                    Task {
                        try? await VPSService.shared.sendSignal(
                            callId: callId,
                            signalType: "answer",
                            signalData: answerData
                        )
                    }

                    if !isRenegotiation {
                        self?.startIcePolling(callId: callId)
                    }
                }
            }
        }
    }

    private func handleAnswerSignal(data: [String: Any]) {
        guard let sdp = data["sdp"] as? String,
              let pc = peerConnection else {
            print("[SyncFlowCallManager] Answer missing SDP or no PeerConnection")
            return
        }

        // Allow renegotiation answers (response to ICE restart offer)
        let isRenegotiation = hasRemoteDescription
        if isRenegotiation {
            print("[SyncFlowCallManager] Processing renegotiation answer (ICE restart response)")
        } else {
            // Cancel answer polling since we got the initial answer
            answerPollingTask?.cancel()
            answerPollingTask = nil
        }

        let typeStr = data["type"] as? String ?? "answer"
        let sdpType = RTCSessionDescription.type(for: typeStr)
        let remoteDescription = RTCSessionDescription(type: sdpType, sdp: sdp)

        pc.setRemoteDescription(remoteDescription) { [weak self] error in
            if let error = error {
                print("[SyncFlowCallManager] Failed to set remote answer: \(error)")
                return
            }
            self?.hasRemoteDescription = true

            if !isRenegotiation {
                self?.drainPendingIceCandidates()
                if let callId = self?.currentCallId {
                    self?.startIcePolling(callId: callId)
                }
            }
        }
    }

    private func handleIceCandidateSignal(data: [String: Any]) {
        guard let candidate = data["candidate"] as? String else {
            return
        }

        let sdpMid = data["sdpMid"] as? String ?? "0"
        let sdpMLineIndex = Int32(data["sdpMLineIndex"] as? Int ?? 0)
        let candidateKey = "\(candidate)|\(sdpMid)|\(sdpMLineIndex)"
        if processedIceCandidates.contains(candidateKey) {
            return
        }
        processedIceCandidates.insert(candidateKey)
        let iceCandidate = RTCIceCandidate(sdp: candidate, sdpMLineIndex: sdpMLineIndex, sdpMid: sdpMid)

        if hasRemoteDescription, let pc = peerConnection {
            pc.add(iceCandidate) { error in
                if let error = error {
                    print("[SyncFlowCallManager] Failed to add ICE candidate: \(error)")
                }
            }
        } else {
            pendingIceCandidates.append(iceCandidate)
        }
    }

    private func drainPendingIceCandidates() {
        guard let pc = peerConnection else { return }
        let candidates = pendingIceCandidates
        pendingIceCandidates.removeAll()
        for candidate in candidates {
            pc.add(candidate) { error in
                if let error = error {
                    print("[SyncFlowCallManager] Failed to add queued ICE: \(error)")
                }
            }
        }
    }

    /// Poll for ICE candidates as fallback if WebSocket misses trickle ICE.
    private func startIcePolling(callId: String) {
        icePollingTask?.cancel()
        icePollingTask = Task { [weak self] in
            for _ in 0..<120 { // 60 seconds (500ms interval)
                guard !Task.isCancelled else { return }
                guard let self = self, self.currentCallId == callId else { return }

                switch self.callState {
                case .connected, .ended, .failed:
                    return
                default:
                    break
                }

                do {
                    let signals = try await VPSService.shared.getSignals(callId: callId)
                    for signal in signals {
                        let signalType = signal["signalType"] as? String ?? signal["signal_type"] as? String
                        guard signalType == "ice-candidate" else { continue }
                        let signalData = signal["signalData"] ?? signal["signal_data"] ?? [:]
                        await MainActor.run {
                            self.handleSignal(callId: callId, signalType: "ice-candidate", signalData: signalData)
                        }
                    }
                } catch {
                    print("[SyncFlowCallManager] ICE polling error: \(error.localizedDescription)")
                }

                try? await Task.sleep(nanoseconds: 500_000_000)
            }
        }
    }

    // MARK: - WebRTC Setup

    private func createPeerConnection(iceServers: [RTCIceServer], isVideo: Bool) async throws {
        let config = RTCConfiguration()
        config.iceServers = iceServers
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually
        config.bundlePolicy = .maxBundle
        config.rtcpMuxPolicy = .require

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: ["DtlsSrtpKeyAgreement": "true"]
        )

        guard let pc = SyncFlowCallManager.factory.peerConnection(
            with: config,
            constraints: constraints,
            delegate: self
        ) else {
            throw CallError.connectionFailed
        }

        peerConnection = pc
        hasRemoteDescription = false
        // NOTE: Do NOT clear pendingIceCandidates here - they may contain
        // ICE candidates received via WebSocket before PeerConnection was created

        // Add audio track
        let audioConstraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: [
                "googEchoCancellation": "true",
                "googNoiseSuppression": "true",
                "googAutoGainControl": "true"
            ]
        )
        let audioSource = SyncFlowCallManager.factory.audioSource(with: audioConstraints)
        let audioTrack = SyncFlowCallManager.factory.audioTrack(with: audioSource, trackId: "audio0")
        pc.add(audioTrack, streamIds: ["stream0"])
        localAudioTrack = audioTrack

        // Add video track if video call
        if isVideo {
            let videoSource = SyncFlowCallManager.factory.videoSource()
            let capturer = RTCCameraVideoCapturer(delegate: videoSource)
            videoCapturer = capturer

            let videoTrack = SyncFlowCallManager.factory.videoTrack(with: videoSource, trackId: "video0")
            pc.add(videoTrack, streamIds: ["stream0"])

            await MainActor.run {
                localVideoTrack = videoTrack
            }

            startVideoCapture()
        }

        // Process any queued offer that arrived before PeerConnection was ready
        if let queued = pendingOffer {
            print("[SyncFlowCallManager] Processing queued offer for call \(queued.callId)")
            pendingOffer = nil
            handleOfferSignal(callId: queued.callId, data: queued.data)
        }
    }

    private func startVideoCapture() {
        guard let capturer = videoCapturer else { return }

        let devices = RTCCameraVideoCapturer.captureDevices()
        guard let frontCamera = devices.first(where: {
            $0.position == .front
        }) ?? devices.first else {
            print("[SyncFlowCallManager] No camera available")
            return
        }

        let formats = RTCCameraVideoCapturer.supportedFormats(for: frontCamera)
        let targetWidth: Int32 = 1280
        let targetHeight: Int32 = 720
        let format = formats.sorted { f1, f2 in
            let dim1 = CMVideoFormatDescriptionGetDimensions(f1.formatDescription)
            let dim2 = CMVideoFormatDescriptionGetDimensions(f2.formatDescription)
            let diff1 = abs(dim1.width - targetWidth) + abs(dim1.height - targetHeight)
            let diff2 = abs(dim2.width - targetWidth) + abs(dim2.height - targetHeight)
            return diff1 < diff2
        }.first ?? formats.first

        guard let selectedFormat = format else {
            print("[SyncFlowCallManager] No camera format available")
            return
        }

        let fps = selectedFormat.videoSupportedFrameRateRanges
            .max(by: { $0.maxFrameRate < $1.maxFrameRate })?
            .maxFrameRate ?? 30.0

        capturer.startCapture(with: frontCamera, format: selectedFormat, fps: Int(min(fps, 30)))
    }

    private func createOffer() async throws -> RTCSessionDescription {
        guard let pc = peerConnection else {
            throw CallError.connectionFailed
        }

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "true", "OfferToReceiveVideo": "true"],
            optionalConstraints: nil
        )

        return try await withCheckedThrowingContinuation { continuation in
            pc.offer(for: constraints) { [weak pc] sdp, error in
                if let error = error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let sdp = sdp, let pc = pc else {
                    continuation.resume(throwing: CallError.connectionFailed)
                    return
                }
                pc.setLocalDescription(sdp) { error in
                    if let error = error {
                        continuation.resume(throwing: error)
                    } else {
                        continuation.resume(returning: sdp)
                    }
                }
            }
        }
    }

    // MARK: - ICE Server Parsing

    private func parseIceServers(from turnCreds: [String: Any]) -> [RTCIceServer] {
        guard let iceServersArray = turnCreds["iceServers"] as? [[String: Any]] else {
            return [RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"])]
        }

        return iceServersArray.compactMap { serverDict -> RTCIceServer? in
            let urls: [String]
            if let urlsArray = serverDict["urls"] as? [String] {
                urls = urlsArray
            } else if let singleUrl = serverDict["urls"] as? String {
                urls = [singleUrl]
            } else {
                return nil
            }

            let username = serverDict["username"] as? String
            let credential = serverDict["credential"] as? String

            if let username = username, let credential = credential {
                return RTCIceServer(urlStrings: urls, username: username, credential: credential)
            }
            return RTCIceServer(urlStrings: urls)
        }
    }

    // MARK: - Cleanup

    private func cleanupWebRTC() {
        videoCapturer?.stopCapture()
        videoCapturer = nil

        peerConnection?.close()
        peerConnection = nil

        localAudioTrack = nil
        pendingIceCandidates = []
        pendingOffer = nil
        hasRemoteDescription = false
        icePollingTask?.cancel()
        icePollingTask = nil
        processedIceCandidates.removeAll()

        DispatchQueue.main.async { [weak self] in
            self?.localVideoTrack = nil
            self?.remoteVideoTrack = nil
            self?.remoteAudioTrack = nil
            self?.isMuted = false
            self?.isVideoEnabled = false
            self?.networkQuality = .unknown
            self?.connectionQuality = .unknown
        }
    }

    // MARK: - Call Error

    enum CallError: Error, LocalizedError {
        case notSupported
        case notAuthenticated
        case connectionFailed

        var errorDescription: String? {
            switch self {
            case .notSupported:
                return "This feature is not supported"
            case .notAuthenticated:
                return "Not authenticated"
            case .connectionFailed:
                return "Connection failed"
            }
        }
    }
}

// MARK: - RTCPeerConnectionDelegate

extension SyncFlowCallManager: RTCPeerConnectionDelegate {

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {
        print("[SyncFlowCallManager] Signaling state: \(stateChanged.rawValue)")
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        print("[SyncFlowCallManager] Remote stream added: \(stream.streamId)")
        // Legacy callback - kept as fallback but modern didAdd rtpReceiver below is preferred
        DispatchQueue.main.async { [weak self] in
            if let videoTrack = stream.videoTracks.first {
                videoTrack.isEnabled = true
                self?.remoteVideoTrack = videoTrack
            }
            if let audioTrack = stream.audioTracks.first {
                audioTrack.isEnabled = true
                self?.remoteAudioTrack = audioTrack
            }
        }
    }

    // Modern Unified Plan callback - primary method for receiving remote tracks
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams mediaStreams: [RTCMediaStream]) {
        print("[SyncFlowCallManager] Track received via didAdd receiver: \(rtpReceiver.track?.kind ?? "unknown"), id=\(rtpReceiver.track?.trackId ?? "nil")")

        if let videoTrack = rtpReceiver.track as? RTCVideoTrack {
            DispatchQueue.main.async { [weak self] in
                videoTrack.isEnabled = true
                self?.remoteVideoTrack = videoTrack
            }
        } else if let audioTrack = rtpReceiver.track as? RTCAudioTrack {
            DispatchQueue.main.async { [weak self] in
                audioTrack.isEnabled = true
                self?.remoteAudioTrack = audioTrack
            }
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {
        print("[SyncFlowCallManager] Remote stream removed")
        DispatchQueue.main.async { [weak self] in
            self?.remoteVideoTrack = nil
            self?.remoteAudioTrack = nil
        }
    }

    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {
        print("[SyncFlowCallManager] Negotiation needed")
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        print("[SyncFlowCallManager] ICE connection state: \(newState.rawValue)")
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            switch newState {
            case .checking:
                self.callState = .connecting
                self.connectionQuality = .unknown
            case .connected, .completed:
                self.disconnectTimeoutTask?.cancel()
                self.disconnectTimeoutTask = nil
                self.ringingTimeoutTask?.cancel()
                self.ringingTimeoutTask = nil
                self.icePollingTask?.cancel()
                self.icePollingTask = nil
                self.iceRestartAttempted = false // Reset so we can restart again if needed
                self.callState = .connected
                self.connectionQuality = .good
                self.currentCall?.status = .active
                if self.currentCall?.answeredAt == nil {
                    self.currentCall?.answeredAt = Date()
                }
            case .disconnected:
                self.connectionQuality = .poor
                self.disconnectTimeoutTask?.cancel()

                // Attempt ICE restart if we're the caller and haven't tried yet
                if self.isCallInitiator && !self.iceRestartAttempted {
                    Task { [weak self] in
                        await self?.attemptIceRestart()
                    }
                }

                // Allow 15 seconds for ICE restart / natural recovery
                self.disconnectTimeoutTask = Task { [weak self] in
                    try? await Task.sleep(nanoseconds: 15_000_000_000)
                    guard !Task.isCancelled else { return }
                    guard let self = self else { return }
                    print("[SyncFlowCallManager] Still disconnected after 15s, ending call")
                    await MainActor.run {
                        self.callState = .failed("Connection lost")
                    }
                    try? await self.endCall()
                }
            case .failed:
                // Guard against concurrent endCall
                guard !self.isEndingCall else { return }
                self.isEndingCall = true
                self.currentCallId = nil
                self.callState = .failed("Connection failed")
                self.connectionQuality = .unknown
                Task { [weak self] in try? await self?.endCall() }
            case .closed:
                self.connectionQuality = .unknown
            default:
                break
            }
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        print("[SyncFlowCallManager] ICE gathering state: \(newState.rawValue)")
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        guard let callId = currentCallId else { return }

        let candidateData: [String: Any] = [
            "candidate": candidate.sdp,
            "sdpMid": candidate.sdpMid ?? "0",
            "sdpMLineIndex": candidate.sdpMLineIndex
        ]

        Task {
            try? await VPSService.shared.sendSignal(
                callId: callId,
                signalType: "ice-candidate",
                signalData: candidateData
            )
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {
        print("[SyncFlowCallManager] ICE candidates removed")
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        print("[SyncFlowCallManager] Data channel opened")
    }
}

// NOTE: SyncFlowCall and ActiveCall types are defined in:
// - Models/SyncFlowCall.swift
// - Models/ActiveCall.swift
