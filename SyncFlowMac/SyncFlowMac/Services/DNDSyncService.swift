//
//  DNDSyncService.swift
//  SyncFlowMac
//
//  Service to sync Do Not Disturb status between Android and macOS
//

import Foundation
import Combine
// FirebaseDatabase - using FirebaseStubs.swift

class DNDSyncService: ObservableObject {
    static let shared = DNDSyncService()

    @Published var isPhoneDndEnabled: Bool = false
    @Published var phoneDndMode: String = "off"
    @Published var hasPhonePermission: Bool = false
    @Published var statusMessage: String?
    @Published var lastUpdated: Date?

    private let database = Database.database()
    private var statusHandle: DatabaseHandle?
    private var currentUserId: String?

    private init() {}

    /// Start listening for DND status from phone
    func startListening(userId: String) {
        currentUserId = userId

        let statusRef = database.reference()
            .child("users")
            .child(userId)
            .child("dnd_status")

        statusHandle = statusRef.observe(.value) { [weak self] snapshot in
            guard let self = self,
                  let data = snapshot.value as? [String: Any] else { return }

            DispatchQueue.main.async {
                self.isPhoneDndEnabled = data["enabled"] as? Bool ?? false
                self.phoneDndMode = data["mode"] as? String ?? "off"
                self.hasPhonePermission = data["hasPermission"] as? Bool ?? false
                self.statusMessage = data["message"] as? String

                if let timestamp = data["timestamp"] as? Double {
                    self.lastUpdated = Date(timeIntervalSince1970: timestamp / 1000)
                }

                // Clear message after showing
                if self.statusMessage != nil {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                        self.statusMessage = nil
                    }
                }
            }
        }

    }

    /// Stop listening
    func stopListening() {
        guard let userId = currentUserId, let handle = statusHandle else { return }

        database.reference()
            .child("users")
            .child(userId)
            .child("dnd_status")
            .removeObserver(withHandle: handle)

        statusHandle = nil
        currentUserId = nil
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
        guard let userId = currentUserId else {
            return
        }

        database.goOnline()

        let commandRef = database.reference()
            .child("users")
            .child(userId)
            .child("dnd_command")

        let commandData: [String: Any] = [
            "action": action,
            "timestamp": ServerValue.timestamp()
        ]

        commandRef.setValue(commandData) { error, _ in
            if let error = error {
                print("DNDSyncService: Error sending command: \(error)")
            } else {
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
}
