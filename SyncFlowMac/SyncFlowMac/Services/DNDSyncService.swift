//
//  DNDSyncService.swift
//  SyncFlowMac
//
//  Service to sync Do Not Disturb status between Android and macOS
//

import Foundation
import Combine

class DNDSyncService: ObservableObject {
    static let shared = DNDSyncService()

    @Published var isPhoneDndEnabled: Bool = false
    @Published var phoneDndMode: String = "off"
    @Published var hasPhonePermission: Bool = false
    @Published var statusMessage: String?
    @Published var lastUpdated: Date?

    private var currentUserId: String?
    private var cancellables = Set<AnyCancellable>()

    private init() {}

    /// Start listening for DND status from phone
    func startListening(userId: String) {
        currentUserId = userId

        // Fetch current DND status
        Task {
            do {
                let status = try await VPSService.shared.getDndStatus()
                await MainActor.run { self.applyDndStatus(status) }
            } catch {
                print("[DND] Error fetching initial status: \(error)")
            }
        }

        // Listen for real-time WebSocket updates
        VPSService.shared.dndStatusUpdated
            .receive(on: DispatchQueue.main)
            .sink { [weak self] data in
                self?.applyDndStatus(data)
            }
            .store(in: &cancellables)
    }

    /// Stop listening
    func stopListening() {
        currentUserId = nil
        cancellables.removeAll()
    }

    /// Toggle phone DND
    func togglePhoneDnd() {
        sendCommand(isPhoneDndEnabled ? "disable" : "enable")
    }

    /// Enable phone DND
    func enablePhoneDnd() {
        sendCommand("enable")
    }

    /// Disable phone DND
    func disablePhoneDnd() {
        sendCommand("disable")
    }

    /// Set priority only mode
    func setPriorityMode() {
        sendCommand("priority")
    }

    /// Set alarms only mode
    func setAlarmsOnlyMode() {
        sendCommand("alarms")
    }

    /// Set total silence mode
    func setTotalSilenceMode() {
        sendCommand("silence")
    }

    /// Send command to phone
    private func sendCommand(_ action: String) {
        Task {
            do {
                try await VPSService.shared.sendDndCommand(action: action)
            } catch {
                print("[DND] Error sending command \(action): \(error)")
            }
        }
    }

    /// Get display text for DND mode
    var dndModeDisplayText: String {
        switch phoneDndMode {
        case "off": return "Off"
        case "priority": return "Priority Only"
        case "alarms_only": return "Alarms Only"
        case "total_silence": return "Total Silence"
        default: return phoneDndMode.capitalized
        }
    }

    /// Pause DND sync temporarily
    func pauseSync() {
        stopListening()
    }

    /// Stop DND sync
    func stopSync() {
        stopListening()
    }

    /// Resume DND sync
    func resumeSync() {
        if let userId = currentUserId {
            startListening(userId: userId)
        }
    }

    // MARK: - Private

    private func applyDndStatus(_ data: [String: Any]) {
        if let enabled = data["enabled"] as? Bool {
            isPhoneDndEnabled = enabled
        }
        if let mode = data["mode"] as? String {
            phoneDndMode = mode
        }
        if let perm = data["hasPermission"] as? Bool {
            hasPhonePermission = perm
        }
        lastUpdated = Date()
    }
}
