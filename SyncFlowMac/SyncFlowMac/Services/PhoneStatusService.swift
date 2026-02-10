//
//  PhoneStatusService.swift
//  SyncFlowMac
//
//  Service to monitor phone status (battery, signal, WiFi)
//

import Foundation
import Combine

/// Represents the current status of the paired Android phone
struct PhoneStatus: Equatable {
    var batteryLevel: Int = -1
    var isCharging: Bool = false
    var signalStrength: Int = -1 // 0-4 bars
    var networkType: String = "unknown" // "5G", "LTE", "3G", "2G"
    var wifiConnected: Bool = false
    var wifiStrength: Int = -1 // 0-4 bars
    var wifiSsid: String = ""
    var cellularConnected: Bool = false
    var deviceName: String = "Phone"
    var lastUpdated: Date?

    var isConnected: Bool {
        guard let lastUpdated = lastUpdated else { return false }
        // Consider connected if updated within last 5 minutes
        return Date().timeIntervalSince(lastUpdated) < 300
    }

    var batteryIcon: String {
        if isCharging {
            return "battery.100.bolt"
        }
        switch batteryLevel {
        case 0..<10: return "battery.0"
        case 10..<25: return "battery.25"
        case 25..<50: return "battery.50"
        case 50..<75: return "battery.75"
        case 75...100: return "battery.100"
        default: return "battery.0"
        }
    }

    var batteryColor: String {
        if isCharging { return "green" }
        if batteryLevel < 20 { return "red" }
        if batteryLevel < 50 { return "orange" }
        return "green"
    }

    var signalIcon: String {
        switch signalStrength {
        case 0: return "cellularbars"
        case 1: return "cellularbars"
        case 2: return "cellularbars"
        case 3: return "cellularbars"
        case 4: return "cellularbars"
        default: return "cellularbars"
        }
    }

    var wifiIcon: String {
        if !wifiConnected { return "wifi.slash" }
        switch wifiStrength {
        case 0: return "wifi"
        case 1: return "wifi"
        case 2: return "wifi"
        case 3: return "wifi"
        case 4: return "wifi"
        default: return "wifi"
        }
    }

    var networkDisplayText: String {
        if wifiConnected {
            return wifiSsid.isEmpty ? "WiFi" : wifiSsid
        } else if cellularConnected {
            return networkType
        }
        return "No Network"
    }
}

class PhoneStatusService: ObservableObject {
    static let shared = PhoneStatusService()

    @Published var phoneStatus = PhoneStatus()
    @Published var isListening = false

    private var currentUserId: String?
    private var lastLoggedSignature: String?
    private var cancellables = Set<AnyCancellable>()

    private init() {}

    /// Start listening for phone status updates
    func startListening(userId: String) {
        currentUserId = userId
        isListening = true

        // Fetch current phone status
        Task {
            do {
                let status = try await VPSService.shared.getPhoneStatus()
                await MainActor.run { self.applyPhoneStatus(status) }
            } catch {
                print("[PhoneStatus] Error fetching initial status: \(error)")
            }
        }

        // Listen for real-time WebSocket updates
        VPSService.shared.phoneStatusUpdated
            .receive(on: DispatchQueue.main)
            .sink { [weak self] data in
                self?.applyPhoneStatus(data)
            }
            .store(in: &cancellables)
    }

    /// Stop listening for updates
    func stopListening() {
        currentUserId = nil
        isListening = false
        cancellables.removeAll()
    }

    /// Request a status refresh from the phone
    func requestRefresh() {
        Task {
            do {
                try await VPSService.shared.requestPhoneStatusRefresh()
            } catch {
                print("[PhoneStatus] Error requesting refresh: \(error)")
            }
        }
    }

    // MARK: - Private

    private func applyPhoneStatus(_ data: [String: Any]) {
        if let battery = data["batteryLevel"] as? Int { phoneStatus.batteryLevel = battery }
        if let charging = data["isCharging"] as? Bool { phoneStatus.isCharging = charging }
        if let signal = data["signalStrength"] as? Int { phoneStatus.signalStrength = signal }
        if let network = data["networkType"] as? String { phoneStatus.networkType = network }
        if let wifi = data["wifiConnected"] as? Bool { phoneStatus.wifiConnected = wifi }
        if let wifiStr = data["wifiStrength"] as? Int { phoneStatus.wifiStrength = wifiStr }
        if let ssid = data["wifiSsid"] as? String { phoneStatus.wifiSsid = ssid }
        if let cellular = data["cellularConnected"] as? Bool { phoneStatus.cellularConnected = cellular }
        if let name = data["deviceName"] as? String { phoneStatus.deviceName = name }
        phoneStatus.lastUpdated = Date()
    }
}
