//
//  VoicemailSyncService.swift
//  SyncFlowMac
//
//  Service to display voicemails synced from Android
//

import Foundation
import Combine
// FirebaseDatabase - using FirebaseStubs.swift

class VoicemailSyncService: ObservableObject {
    static let shared = VoicemailSyncService()

    @Published var voicemails: [Voicemail] = []
    @Published var unreadCount: Int = 0

    private let database = Database.database()
    private var voicemailsHandle: DatabaseHandle?
    private var currentUserId: String?

    private init() {}

    // MARK: - Voicemail Model

    struct Voicemail: Identifiable, Hashable {
        let id: String
        let number: String
        let contactName: String?
        let duration: Int
        let date: Date
        let isRead: Bool
        let transcription: String?
        let hasAudio: Bool
        let syncedAt: Date?

        var displayName: String {
            contactName ?? number
        }

        var formattedDuration: String {
            let minutes = duration / 60
            let seconds = duration % 60
            if minutes > 0 {
                return "\(minutes):\(String(format: "%02d", seconds))"
            }
            return "0:\(String(format: "%02d", seconds))"
        }
    }

    /// Start listening for voicemails
    func startListening(userId: String) {
        currentUserId = userId

        let voicemailsRef = database.reference()
            .child("users")
            .child(userId)
            .child("voicemails")
            .queryOrdered(byChild: "date")

        voicemailsHandle = voicemailsRef.observe(.value) { [weak self] snapshot in
            guard let self = self else { return }

            var voicemails: [Voicemail] = []

            for child in snapshot.children {
                guard let childSnapshot = child as? DataSnapshot,
                      let data = childSnapshot.value as? [String: Any] else { continue }

                if let vm = self.parseVoicemail(id: childSnapshot.key, data: data) {
                    voicemails.append(vm)
                }
            }

            // Sort by date descending (newest first)
            voicemails.sort { $0.date > $1.date }

            DispatchQueue.main.async {
                self.voicemails = voicemails
                self.unreadCount = voicemails.filter { !$0.isRead }.count
            }
        }

    }

    /// Stop listening
    func stopListening() {
        guard let userId = currentUserId, let handle = voicemailsHandle else { return }

        database.reference()
            .child("users")
            .child(userId)
            .child("voicemails")
            .removeObserver(withHandle: handle)

        voicemailsHandle = nil
        currentUserId = nil
        voicemails = []
        unreadCount = 0
    }

    // MARK: - Actions

    /// Mark a voicemail as read
    func markAsRead(_ voicemailId: String) async throws {
        guard let userId = currentUserId else { return }

        database.goOnline()

        let voicemailRef = database.reference()
            .child("users")
            .child(userId)
            .child("voicemails")
            .child(voicemailId)
            .child("isRead")

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            voicemailRef.setValue(true) { error, _ in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    /// Delete a voicemail
    func deleteVoicemail(_ voicemailId: String) async throws {
        guard let userId = currentUserId else { return }

        database.goOnline()

        let voicemailRef = database.reference()
            .child("users")
            .child(userId)
            .child("voicemails")
            .child(voicemailId)

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            voicemailRef.removeValue { error, _ in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    /// Call back the voicemail sender
    func callBack(_ voicemail: Voicemail, makeCall: (String) -> Void) {
        makeCall(voicemail.number)
    }

    // MARK: - Private Helpers

    private func parseVoicemail(id: String, data: [String: Any]) -> Voicemail? {
        guard let number = data["number"] as? String,
              let duration = data["duration"] as? Int,
              let dateMs = data["date"] as? Double else {
            return nil
        }

        let date = Date(timeIntervalSince1970: dateMs / 1000)

        var syncedAt: Date? = nil
        if let syncedAtMs = data["syncedAt"] as? Double {
            syncedAt = Date(timeIntervalSince1970: syncedAtMs / 1000)
        }

        return Voicemail(
            id: id,
            number: number,
            contactName: data["contactName"] as? String,
            duration: duration,
            date: date,
            isRead: data["isRead"] as? Bool ?? false,
            transcription: data["transcription"] as? String,
            hasAudio: data["hasAudio"] as? Bool ?? false,
            syncedAt: syncedAt
        )
    }

    /// Pause voicemail sync temporarily
    func pauseSync() {
        stopListening()
    }

    /// Resume voicemail sync
    func resumeSync() {
        if let userId = currentUserId {
            startListening(userId: userId)
        }
    }
}
