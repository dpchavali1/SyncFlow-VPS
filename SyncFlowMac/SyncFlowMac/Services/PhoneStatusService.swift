//
//  PhoneStatusService.swift
//  SyncFlowMac
//
//  Service to monitor phone status (battery, signal, WiFi) from Firebase
//

import Foundation
import Combine
// FirebaseDatabase - using FirebaseStubs.swift

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

    private let database = Database.database()
    private var statusHandle: DatabaseHandle?
    private var currentUserId: String?
    private var lastLoggedSignature: String?

    private init() {}

    /// Start listening for phone status updates
    func startListening(userId: String) {
        guard !isListening else { return }
        currentUserId = userId

        let statusRef = database.reference()
            .child("users")
            .child(userId)
            .child("phone_status")

        statusHandle = statusRef.observe(.value) { [weak self] snapshot in
            guard let self = self else { return }

            if let data = snapshot.value as? [String: Any] {
                DispatchQueue.main.async {
                    self.updateStatus(from: data)
                }
            }
        }

        isListening = true
    }

    /// Stop listening for updates
    func stopListening() {
        guard isListening, let userId = currentUserId else { return }

        if let handle = statusHandle {
            database.reference()
                .child("users")
                .child(userId)
                .child("phone_status")
                .removeObserver(withHandle: handle)
        }

        statusHandle = nil
        currentUserId = nil
        isListening = false
    }

    /// Parse status data from Firebase
    private func updateStatus(from data: [String: Any]) {
        var status = PhoneStatus()

        status.batteryLevel = data["batteryLevel"] as? Int ?? -1
        status.isCharging = data["isCharging"] as? Bool ?? false
        status.signalStrength = data["signalStrength"] as? Int ?? -1
        status.networkType = data["networkType"] as? String ?? "unknown"
        status.wifiConnected = data["wifiConnected"] as? Bool ?? false
        status.wifiStrength = data["wifiStrength"] as? Int ?? -1
        status.wifiSsid = data["wifiSsid"] as? String ?? ""
        status.cellularConnected = data["cellularConnected"] as? Bool ?? false
        status.deviceName = data["deviceName"] as? String ?? "Phone"

        // Parse timestamp
        if let timestamp = data["timestamp"] as? Double {
            status.lastUpdated = Date(timeIntervalSince1970: timestamp / 1000)
        } else {
            status.lastUpdated = Date()
        }

        // Create signature to detect meaningful changes (exclude timestamp)
        let signature = "\(status.batteryLevel)|\(status.isCharging)|\(status.signalStrength)|\(status.wifiConnected)|\(status.networkType)|\(status.wifiSsid)|\(status.deviceName)"

        // ONLY update published property if something meaningful changed
        // This prevents unnecessary SwiftUI view invalidations
        guard signature != lastLoggedSignature else {
            return
        }

        lastLoggedSignature = signature
        phoneStatus = status
    }

    /// Request a status refresh from the phone
    func requestRefresh() {
        guard let userId = currentUserId else { return }

        // Write a refresh request that the phone will pick up
        database.goOnline()

        let requestRef = database.reference()
            .child("users")
            .child(userId)
            .child("status_refresh_request")

        requestRef.setValue([
            "timestamp": ServerValue.timestamp(),
            "requestedBy": "macos"
        ])
    }
}
