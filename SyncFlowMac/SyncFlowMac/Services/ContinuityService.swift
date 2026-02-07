//
//  ContinuityService.swift
//  SyncFlowMac
//
//  Syncs active conversation state between devices for seamless continuity.
//

import Foundation
import Combine
// FirebaseDatabase - using FirebaseStubs.swift

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

    private let database = Database.database()
    private let deviceId = FirebaseService.shared.getDeviceId()
    private let deviceName: String = Host.current().localizedName ?? "Mac"

    private var currentUserId: String?
    private var handle: DatabaseHandle?
    private var lastSeenDeviceId: String?
    private var lastSeenTimestamp: Int64 = 0
    private var lastPublishAt: TimeInterval = 0
    private var lastPayloadHash: Int = 0

    private init() {}

    func configure(userId: String?) {
        currentUserId = userId
    }

    func startListening() {
        guard let userId = currentUserId, handle == nil else { return }

        let ref = database.reference()
            .child("users")
            .child(userId)
            .child("continuity_state")

        handle = ref.observe(.value) { [weak self] snapshot in
            guard let self = self else { return }
            let latest = snapshot.children.allObjects
                .compactMap { $0 as? DataSnapshot }
                .compactMap { self.parseState(from: $0) }
                .filter { $0.deviceId != self.deviceId }
                .max(by: { $0.timestamp < $1.timestamp })

            guard let state = latest else {
                DispatchQueue.main.async {
                    self.latestState = nil
                }
                return
            }

            if state.deviceId == self.lastSeenDeviceId && state.timestamp <= self.lastSeenTimestamp {
                return
            }

            let now = Int64(Date().timeIntervalSince1970 * 1000)
            if now - state.timestamp > 5 * 60 * 1000 {
                return
            }

            self.lastSeenDeviceId = state.deviceId
            self.lastSeenTimestamp = state.timestamp

            DispatchQueue.main.async {
                self.latestState = state
            }
        }
    }

    func stopListening() {
        guard let userId = currentUserId, let handle = handle else { return }
        database.reference()
            .child("users")
            .child(userId)
            .child("continuity_state")
            .removeObserver(withHandle: handle)
        self.handle = nil
    }

    func publishConversation(address: String, contactName: String?, threadId: Int64?, draft: String?) {
        guard let userId = currentUserId else { return }

        let now = Date().timeIntervalSince1970
        let payloadHash = [address, contactName ?? "", String(threadId ?? 0), draft ?? ""].joined(separator: "|").hashValue
        if now - lastPublishAt < 0.8 && payloadHash == lastPayloadHash {
            return
        }

        lastPublishAt = now
        lastPayloadHash = payloadHash

        let trimmedDraft = draft?.prefix(1000)
        let data: [String: Any] = [
            "deviceId": deviceId,
            "deviceName": deviceName,
            "platform": "macos",
            "type": "conversation",
            "address": address,
            "contactName": contactName ?? "",
            "threadId": threadId ?? 0,
            "draft": trimmedDraft.map(String.init) ?? "",
            "timestamp": ServerValue.timestamp()
        ]

        database.goOnline()

        database.reference()
            .child("users")
            .child(userId)
            .child("continuity_state")
            .child(deviceId)
            .setValue(data)
    }

    private func parseState(from snapshot: DataSnapshot) -> ContinuityState? {
        guard let deviceId = snapshot.key as String?,
              let data = snapshot.value as? [String: Any] else {
            return nil
        }

        guard let address = data["address"] as? String else { return nil }

        let timestamp = (data["timestamp"] as? Int64)
            ?? (data["timestamp"] as? Double).map(Int64.init)
            ?? 0

        let threadId = (data["threadId"] as? Int64)
            ?? (data["threadId"] as? Double).map(Int64.init)

        return ContinuityState(
            id: deviceId,
            deviceId: deviceId,
            deviceName: data["deviceName"] as? String ?? "Device",
            platform: data["platform"] as? String ?? "unknown",
            type: data["type"] as? String ?? "conversation",
            address: address,
            contactName: (data["contactName"] as? String)?.isEmpty == true ? nil : data["contactName"] as? String,
            threadId: threadId,
            draft: (data["draft"] as? String)?.isEmpty == true ? nil : data["draft"] as? String,
            timestamp: timestamp
        )
    }
}
