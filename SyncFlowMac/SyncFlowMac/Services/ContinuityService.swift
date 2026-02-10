//
//  ContinuityService.swift
//  SyncFlowMac
//
//  Syncs active conversation state between devices for seamless continuity.
//

import Foundation
import Combine

class ContinuityService: ObservableObject {
    static let shared = ContinuityService()

    struct ContinuityState: Identifiable {
        let id: String
        let deviceId: String
        let deviceName: String
        let platform: String
        let type: String
        let address: String
        let contactName: String?
        let threadId: Int64?
        let draft: String?
        let timestamp: Int64
    }

    @Published private(set) var latestState: ContinuityState?

    private let deviceId = DeviceIdentifier.shared.getDeviceId()
    private let deviceName: String = Host.current().localizedName ?? "Mac"

    private var currentUserId: String?
    private var lastPublishAt: TimeInterval = 0
    private var lastPayloadHash: Int = 0

    private init() {}

    func configure(userId: String?) {
        currentUserId = userId
    }

    func startListening() {
        // Continuity state sync not yet implemented via VPS WebSocket
    }

    func stopListening() {
        // No-op - VPS WebSocket handles cleanup
    }

    func publishConversation(address: String, contactName: String?, threadId: Int64?, draft: String?) {
        // Continuity state publishing not yet implemented via VPS
    }
}
