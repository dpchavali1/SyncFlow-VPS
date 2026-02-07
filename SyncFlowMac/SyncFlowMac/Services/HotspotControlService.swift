//
//  HotspotControlService.swift
//  SyncFlowMac
//
//  Service to control Android phone's mobile hotspot
//

import Foundation
import Combine
// FirebaseDatabase - using FirebaseStubs.swift

class HotspotControlService: ObservableObject {
    static let shared = HotspotControlService()

    @Published var isHotspotEnabled: Bool = false
    @Published var hotspotSSID: String = ""
    @Published var connectedDevices: Int = 0
    @Published var canToggleProgrammatically: Bool = false
    @Published var statusMessage: String?
    @Published var lastUpdated: Date?

    private let database = Database.database()
    private var statusHandle: DatabaseHandle?
    private var currentUserId: String?

    private init() {}

    /// Start listening for hotspot status
    func startListening(userId: String) {
        currentUserId = userId

        let statusRef = database.reference()
            .child("users")
            .child(userId)
            .child("hotspot_status")

        statusHandle = statusRef.observe(.value) { [weak self] snapshot in
            guard let self = self,
                  let data = snapshot.value as? [String: Any] else { return }

            DispatchQueue.main.async {
                self.isHotspotEnabled = data["enabled"] as? Bool ?? false
                self.hotspotSSID = data["ssid"] as? String ?? ""
                self.connectedDevices = data["connectedDevices"] as? Int ?? 0
                self.canToggleProgrammatically = data["canToggleProgrammatically"] as? Bool ?? false
                self.statusMessage = data["message"] as? String

                if let timestamp = data["timestamp"] as? Double {
                    self.lastUpdated = Date(timeIntervalSince1970: timestamp / 1000)
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
            .child("hotspot_status")
            .removeObserver(withHandle: handle)

        statusHandle = nil
        currentUserId = nil
    }

    /// Toggle hotspot on phone
    func toggleHotspot() {
        sendCommand("toggle")
    }

    /// Enable hotspot
    func enableHotspot() {
        sendCommand("enable")
    }

    /// Disable hotspot
    func disableHotspot() {
        sendCommand("disable")
    }

    /// Open hotspot settings on phone
    func openHotspotSettings() {
        sendCommand("open_settings")
    }

    /// Request status refresh
    func refreshStatus() {
        sendCommand("status")
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
            .child("hotspot_command")

        let commandData: [String: Any] = [
            "action": action,
            "timestamp": ServerValue.timestamp()
        ]

        commandRef.setValue(commandData) { error, _ in
            if let error = error {
                print("HotspotControlService: Error sending command: \(error)")
            } else {
            }
        }
    }
}
