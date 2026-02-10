//
//  SyncFlowCall.swift
//  SyncFlowMac
//
//  Model for SyncFlow-to-SyncFlow audio/video calls between devices.
//  This is separate from cellular phone calls - it's app-to-app VoIP.
//

import Foundation

/// Represents a SyncFlow audio/video call between devices
struct SyncFlowCall: Identifiable, Equatable {
    let id: String
    let callerId: String
    let callerName: String
    let callerPlatform: String
    let calleeId: String
    let calleeName: String
    let calleePlatform: String
    let callType: CallType
    var status: CallStatus
    let startedAt: Date
    var answeredAt: Date?
    var endedAt: Date?
    var offer: SDPData?
    var answer: SDPData?

    enum CallType: String, Codable {
        case audio
        case video

        var isVideo: Bool { self == .video }
    }

    enum CallStatus: String, Codable {
        case ringing
        case active
        case ended
        case rejected
        case missed
        case failed
    }

    /// SDP (Session Description Protocol) data for WebRTC signaling
    struct SDPData: Equatable {
        let sdp: String
        let type: String
    }

    /// ICE (Interactive Connectivity Establishment) candidate for WebRTC
    struct IceCandidate: Equatable {
        let candidate: String
        let sdpMid: String?
        let sdpMLineIndex: Int

        func toDict() -> [String: Any] {
            return [
                "candidate": candidate,
                "sdpMid": sdpMid ?? "",
                "sdpMLineIndex": sdpMLineIndex
            ]
        }

        static func from(_ dict: [String: Any]) -> IceCandidate? {
            guard let candidate = dict["candidate"] as? String else { return nil }
            return IceCandidate(
                candidate: candidate,
                sdpMid: dict["sdpMid"] as? String,
                sdpMLineIndex: (dict["sdpMLineIndex"] as? Int) ?? 0
            )
        }
    }

    // Computed properties
    var isIncoming: Bool { callerPlatform != "macos" }
    var isOutgoing: Bool { callerPlatform == "macos" }
    var isVideoCall: Bool { callType == .video }
    var isActive: Bool { status == .active }
    var isRinging: Bool { status == .ringing }

    var displayName: String {
        isIncoming ? callerName : calleeName
    }

    var duration: TimeInterval {
        guard let start = answeredAt else { return 0 }
        let end = endedAt ?? Date()
        return end.timeIntervalSince(start)
    }

    var formattedDuration: String {
        let totalSeconds = Int(duration)
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }

    // MARK: - Factory Methods

    /// Create an outgoing call from this Mac
    static func createOutgoing(
        callId: String,
        callerId: String,
        callerName: String,
        calleeId: String,
        calleeName: String,
        isVideo: Bool
    ) -> SyncFlowCall {
        return SyncFlowCall(
            id: callId,
            callerId: callerId,
            callerName: callerName,
            callerPlatform: "macos",
            calleeId: calleeId,
            calleeName: calleeName,
            calleePlatform: "android",
            callType: isVideo ? .video : .audio,
            status: .ringing,
            startedAt: Date(),
            answeredAt: nil,
            endedAt: nil,
            offer: nil,
            answer: nil
        )
    }

    /// Parse from dictionary
    static func from(id: String, dict: [String: Any]) -> SyncFlowCall? {
        guard let callerId = dict["callerId"] as? String,
              let callerName = dict["callerName"] as? String,
              let callerPlatform = dict["callerPlatform"] as? String,
              let calleeId = dict["calleeId"] as? String,
              let calleeName = dict["calleeName"] as? String,
              let calleePlatform = dict["calleePlatform"] as? String,
              let callTypeStr = dict["callType"] as? String,
              let statusStr = dict["status"] as? String,
              let startedAtMs = dict["startedAt"] as? Double
        else {
            return nil
        }

        let callType = CallType(rawValue: callTypeStr) ?? .audio
        let status = CallStatus(rawValue: statusStr) ?? .ringing
        let startedAt = Date(timeIntervalSince1970: startedAtMs / 1000)

        var answeredAt: Date?
        if let answeredAtMs = dict["answeredAt"] as? Double {
            answeredAt = Date(timeIntervalSince1970: answeredAtMs / 1000)
        }

        var endedAt: Date?
        if let endedAtMs = dict["endedAt"] as? Double {
            endedAt = Date(timeIntervalSince1970: endedAtMs / 1000)
        }

        var offer: SDPData?
        if let offerDict = dict["offer"] as? [String: Any],
           let sdp = offerDict["sdp"] as? String,
           let type = offerDict["type"] as? String {
            offer = SDPData(sdp: sdp, type: type)
        }

        var answer: SDPData?
        if let answerDict = dict["answer"] as? [String: Any],
           let sdp = answerDict["sdp"] as? String,
           let type = answerDict["type"] as? String {
            answer = SDPData(sdp: sdp, type: type)
        }

        return SyncFlowCall(
            id: id,
            callerId: callerId,
            callerName: callerName,
            callerPlatform: callerPlatform,
            calleeId: calleeId,
            calleeName: calleeName,
            calleePlatform: calleePlatform,
            callType: callType,
            status: status,
            startedAt: startedAt,
            answeredAt: answeredAt,
            endedAt: endedAt,
            offer: offer,
            answer: answer
        )
    }

    /// Convert to dictionary
    func toDict() -> [String: Any] {
        var dict: [String: Any] = [
            "id": id,
            "callerId": callerId,
            "callerName": callerName,
            "callerPlatform": callerPlatform,
            "calleeId": calleeId,
            "calleeName": calleeName,
            "calleePlatform": calleePlatform,
            "callType": callType.rawValue,
            "status": status.rawValue,
            "startedAt": startedAt.timeIntervalSince1970 * 1000
        ]

        if let answeredAt = answeredAt {
            dict["answeredAt"] = answeredAt.timeIntervalSince1970 * 1000
        }
        if let endedAt = endedAt {
            dict["endedAt"] = endedAt.timeIntervalSince1970 * 1000
        }
        if let offer = offer {
            dict["offer"] = ["sdp": offer.sdp, "type": offer.type]
        }
        if let answer = answer {
            dict["answer"] = ["sdp": answer.sdp, "type": answer.type]
        }

        return dict
    }

    // MARK: - Constants

    static let callTimeoutSeconds: TimeInterval = 60
}

/// Device information for SyncFlow calling
struct SyncFlowDevice: Identifiable, Equatable {
    let id: String
    let name: String
    let platform: String
    let online: Bool
    let lastSeen: Date

    var isMacOS: Bool { platform == "macos" }
    var isAndroid: Bool { platform == "android" }

    static func from(id: String, dict: [String: Any]) -> SyncFlowDevice? {
        guard let name = dict["name"] as? String,
              let platform = dict["platform"] as? String
        else {
            return nil
        }

        let online = dict["online"] as? Bool ?? false
        let lastSeenMs = dict["lastSeen"] as? Double ?? 0
        let lastSeen = Date(timeIntervalSince1970: lastSeenMs / 1000)

        return SyncFlowDevice(
            id: id,
            name: name,
            platform: platform,
            online: online,
            lastSeen: lastSeen
        )
    }

    func toDict() -> [String: Any] {
        return [
            "id": id,
            "name": name,
            "platform": platform,
            "online": online,
            "lastSeen": lastSeen.timeIntervalSince1970 * 1000
        ]
    }
}
