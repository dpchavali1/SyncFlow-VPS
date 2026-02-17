/**
 * VPSService+Devices - Device management
 *
 * Contains:
 * - Device listing
 * - Available SIM card retrieval
 * - Paired device listing
 * - Device removal
 */

import Foundation

extension VPSService {

    // MARK: - Devices

    public func getDevices() async throws -> VPSDevicesResponse {
        return try await get("/api/devices")
    }

    func getAvailableSims(userId: String) async throws -> [SimInfo] {
        struct SimsResponse: Codable {
            let sims: [VPSSim]
        }
        struct VPSSim: Codable {
            let id: String
            let subscriptionId: Int?
            let displayName: String?
            let carrierName: String?
            let phoneNumber: String?
            let isDefault: Bool?
        }
        let response: SimsResponse = try await get("/api/devices/sims")
        let simInfos = response.sims.map { sim in
            SimInfo(
                subscriptionId: sim.subscriptionId ?? 0,
                slotIndex: 0,
                displayName: sim.displayName ?? "SIM",
                carrierName: sim.carrierName ?? "",
                phoneNumber: sim.phoneNumber,
                isEmbedded: false,
                isActive: true
            )
        }
        // Store primary phone number for region-based features (e.g. deals)
        if let primaryPhone = simInfos.first?.phoneNumber {
            UserDefaults.standard.set(primaryPhone, forKey: "registered_phone_number")
        }
        return simInfos
    }

    func getPairedDevices(userId: String) async throws -> [SyncFlowDevice] {
        let response = try await getDevices()
        return response.devices.compactMap { device in
            SyncFlowDevice(
                id: device.id,
                name: device.name ?? "Unknown Device",
                platform: device.deviceType,
                online: true,
                lastSeen: Date()
            )
        }
    }

    public func removeDevice(deviceId: String) async throws {
        let _: [String: Bool] = try await delete("/api/devices/\(deviceId)")
    }
}
