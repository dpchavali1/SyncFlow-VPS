//
//  VoicemailSyncService.swift
//  SyncFlowMac
//
//  Service to display voicemails synced from Android
//

import Foundation
import Combine

class VoicemailSyncService: ObservableObject {
    static let shared = VoicemailSyncService()

    @Published var voicemails: [Voicemail] = []
    @Published var unreadCount: Int = 0

    private var currentUserId: String?
    private var cancellables = Set<AnyCancellable>()

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

        // Fetch current voicemails
        Task {
            await fetchVoicemails()
        }

        // Listen for real-time WebSocket updates
        VPSService.shared.voicemailUpdated
            .receive(on: DispatchQueue.main)
            .sink { [weak self] data in
                let eventType = data["eventType"] as? String ?? ""
                if eventType == "voicemail_deleted" {
                    if let id = data["id"] as? String {
                        self?.voicemails.removeAll { $0.id == id }
                        self?.updateUnreadCount()
                    }
                } else {
                    // For added/updated, re-fetch the full list
                    Task { await self?.fetchVoicemails() }
                }
            }
            .store(in: &cancellables)
    }

    /// Stop listening
    func stopListening() {
        currentUserId = nil
        cancellables.removeAll()
        voicemails = []
        unreadCount = 0
    }

    // MARK: - Actions

    /// Mark a voicemail as read
    func markAsRead(_ voicemailId: String) async throws {
        try await VPSService.shared.markVoicemailRead(voicemailId: voicemailId)
        await MainActor.run {
            if let index = voicemails.firstIndex(where: { $0.id == voicemailId }) {
                let vm = voicemails[index]
                voicemails[index] = Voicemail(
                    id: vm.id, number: vm.number, contactName: vm.contactName,
                    duration: vm.duration, date: vm.date, isRead: true,
                    transcription: vm.transcription, hasAudio: vm.hasAudio, syncedAt: vm.syncedAt
                )
                updateUnreadCount()
            }
        }
    }

    /// Delete a voicemail
    func deleteVoicemail(_ voicemailId: String) async throws {
        try await VPSService.shared.deleteVoicemail(voicemailId: voicemailId)
        await MainActor.run {
            voicemails.removeAll { $0.id == voicemailId }
            updateUnreadCount()
        }
    }

    /// Call back the voicemail sender
    func callBack(_ voicemail: Voicemail, makeCall: (String) -> Void) {
        makeCall(voicemail.number)
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

    // MARK: - Private

    private func fetchVoicemails() async {
        do {
            let rawVoicemails = try await VPSService.shared.getVoicemails()
            let parsed = rawVoicemails.compactMap { parseVoicemail($0) }
            await MainActor.run {
                self.voicemails = parsed.sorted { $0.date > $1.date }
                self.updateUnreadCount()
            }
        } catch {
            print("[Voicemail] Error fetching voicemails: \(error)")
        }
    }

    private func parseVoicemail(_ data: [String: Any]) -> Voicemail? {
        guard let id = data["id"] as? String else { return nil }
        let number = data["number"] as? String ?? data["phoneNumber"] as? String ?? ""
        let contactName = data["contactName"] as? String
        let duration = data["duration"] as? Int ?? 0
        let dateMs = data["date"] as? Double ?? data["timestamp"] as? Double ?? 0
        let isRead = data["isRead"] as? Bool ?? data["read"] as? Bool ?? false
        let transcription = data["transcription"] as? String
        let hasAudio = data["hasAudio"] as? Bool ?? false
        let syncedMs = data["syncedAt"] as? Double

        return Voicemail(
            id: id,
            number: number,
            contactName: contactName,
            duration: duration,
            date: Date(timeIntervalSince1970: dateMs / 1000),
            isRead: isRead,
            transcription: transcription,
            hasAudio: hasAudio,
            syncedAt: syncedMs != nil ? Date(timeIntervalSince1970: syncedMs! / 1000) : nil
        )
    }

    private func updateUnreadCount() {
        unreadCount = voicemails.filter { !$0.isRead }.count
    }
}
