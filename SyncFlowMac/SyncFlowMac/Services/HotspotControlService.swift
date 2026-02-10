//
//  HotspotControlService.swift
//  SyncFlowMac
//
//  Service to control Android phone's mobile hotspot
//

import Foundation
import Combine

class HotspotControlService: ObservableObject {
    static let shared = HotspotControlService()

    @Published var isHotspotEnabled: Bool = false
    @Published var hotspotSSID: String = ""
    @Published var connectedDevices: Int = 0
    @Published var canToggleProgrammatically: Bool = false
    @Published var statusMessage: String?
    @Published var lastUpdated: Date?

    private var currentUserId: String?
    private var cancellables = Set<AnyCancellable>()

    private init() {}

    /// Start listening for hotspot status
    func startListening(userId: String) {
        currentUserId = userId

        // Fetch current hotspot status
        Task {
            do {
                let status = try await VPSService.shared.getHotspotStatus()
                await MainActor.run { self.applyHotspotStatus(status) }
            } catch {
                print("[Hotspot] Error fetching initial status: \(error)")
            }
        }

        // Listen for real-time WebSocket updates
        VPSService.shared.hotspotStatusUpdated
            .receive(on: DispatchQueue.main)
            .sink { [weak self] data in
                self?.applyHotspotStatus(data)
            }
            .store(in: &cancellables)
    }

    /// Stop listening
    func stopListening() {
        currentUserId = nil
        cancellables.removeAll()
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
        Task {
            do {
                try await VPSService.shared.sendHotspotCommand(action: action)
            } catch {
                print("[Hotspot] Error sending command \(action): \(error)")
            }
        }
    }

    // MARK: - Private

    private func applyHotspotStatus(_ data: [String: Any]) {
        if let enabled = data["enabled"] as? Bool { isHotspotEnabled = enabled }
        if let ssid = data["ssid"] as? String { hotspotSSID = ssid }
        if let devices = data["connectedDevices"] as? Int { connectedDevices = devices }
        if let canToggle = data["canToggle"] as? Bool { canToggleProgrammatically = canToggle }
        lastUpdated = Date()
    }
}
