import Foundation
import Combine

/**
 * Manages sync group operations for macOS device
 * Handles pairing, device registration, and sync group recovery
 *
 * Operations stubbed pending VPS implementation.
 */
class SyncGroupManager: NSObject, ObservableObject {
    static let shared = SyncGroupManager()

    @Published var syncGroupId: String? {
        didSet {
            if let id = syncGroupId {
                UserDefaults.standard.set(id, forKey: "sync_group_id")
            } else {
                UserDefaults.standard.removeObject(forKey: "sync_group_id")
            }
        }
    }

    var deviceId: String {
        if let existing = UserDefaults.standard.string(forKey: "device_id") {
            return existing
        }

        // Try to get from hardware UUID
        let newId = getOrCreateDeviceId()
        UserDefaults.standard.set(newId, forKey: "device_id")
        return newId
    }

    /// Check if device is part of a sync group
    var isPaired: Bool {
        return syncGroupId != nil
    }

    override init() {
        super.init()
        // Restore sync group ID from UserDefaults on init
        self.syncGroupId = UserDefaults.standard.string(forKey: "sync_group_id")
    }

    /**
     * Get or create hardware-based device ID
     * Uses IOKit to get hardware UUID if available
     */
    private func getOrCreateDeviceId() -> String {
        // Try UserDefaults first
        if let existing = UserDefaults.standard.string(forKey: "SyncFlowMacDeviceId") {
            return existing
        }

        // Try to get hardware UUID
        let id: String
        if let hwUuid = getMacHardwareUUID() {
            id = "mac_\(hwUuid.prefix(16))"
        } else {
            id = "mac_\(UUID().uuidString)"
        }

        UserDefaults.standard.set(id, forKey: "SyncFlowMacDeviceId")
        return id
    }

    /**
     * Get macOS hardware UUID
     */
    private func getMacHardwareUUID() -> String? {
        let task = Process()
        task.launchPath = "/usr/bin/ioreg"
        task.arguments = ["-rd1", "-c", "IOPlatformExpertDevice"]

        let pipe = Pipe()
        task.standardOutput = pipe
        task.launch()

        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        guard let output = String(data: data, encoding: .utf8) else { return nil }

        // Parse for IOPlatformUUID
        if let range = output.range(of: "IOPlatformUUID") {
            let substring = output[range.upperBound...]
            if let start = substring.firstIndex(of: "\""),
               let end = substring[substring.index(after: start)...].firstIndex(of: "\"") {
                return String(substring[substring.index(after: start)..<end])
            }
        }

        return nil
    }

    /**
     * Create a new sync group - not implemented via VPS
     */
    func createSyncGroup(deviceName: String = "macOS", completion: @escaping (Result<String, Error>) -> Void) {
        completion(.failure(NSError(domain: "SyncGroup", code: -100, userInfo: [NSLocalizedDescriptionKey: "Not implemented via VPS"])))
    }

    /**
     * Join an existing sync group - not implemented via VPS
     */
    func joinSyncGroup(
        scannedSyncGroupId: String,
        deviceName: String = "macOS",
        completion: @escaping (Result<JoinResult, Error>) -> Void
    ) {
        completion(.failure(NSError(domain: "SyncGroup", code: -100, userInfo: [NSLocalizedDescriptionKey: "Not implemented via VPS"])))
    }

    /**
     * Recover sync group on app reinstall - not implemented via VPS
     */
    func recoverSyncGroup(completion: @escaping (Result<String, Error>) -> Void) {
        completion(.failure(NSError(domain: "SyncGroup", code: -100, userInfo: [NSLocalizedDescriptionKey: "Not implemented via VPS"])))
    }

    /**
     * Get current sync group information - not implemented via VPS
     */
    func getSyncGroupInfo(completion: @escaping (Result<SyncGroupInfo, Error>) -> Void) {
        guard syncGroupId != nil else {
            completion(.failure(NSError(domain: "SyncGroup", code: -1, userInfo: [NSLocalizedDescriptionKey: "Device not paired to any sync group"])))
            return
        }

        completion(.failure(NSError(domain: "SyncGroup", code: -100, userInfo: [NSLocalizedDescriptionKey: "Not implemented via VPS"])))
    }

    /**
     * Update device last synced time - no-op (VPS not yet implemented)
     */
    func updateLastSyncTime(completion: @escaping (Result<Bool, Error>) -> Void) {
        guard syncGroupId != nil else {
            completion(.failure(NSError(domain: "SyncGroup", code: -1, userInfo: [NSLocalizedDescriptionKey: "Device not paired"])))
            return
        }

        // No-op: VPS sync time tracking not yet implemented.
        completion(.success(true))
    }

    /**
     * Remove device from sync group - not implemented via VPS
     */
    func leaveGroup(completion: @escaping (Result<Bool, Error>) -> Void) {
        guard syncGroupId != nil else {
            completion(.failure(NSError(domain: "SyncGroup", code: -1, userInfo: [NSLocalizedDescriptionKey: "Device not paired"])))
            return
        }

        // Clear local state
        self.syncGroupId = nil
        completion(.success(true))
    }

    /**
     * Generate QR code content for this sync group
     * Returns the sync group ID that can be scanned
     */
    func getQRCodeContent() -> String? {
        return syncGroupId
    }

    // MARK: - Data Models

    struct JoinResult {
        let success: Bool
        let deviceCount: Int
        let limit: Int
    }

    struct SyncGroupInfo {
        let plan: String
        let deviceLimit: Int
        let deviceCount: Int
        let devices: [DeviceInfo]
    }

    struct DeviceInfo {
        let deviceId: String
        let deviceType: String
        let joinedAt: TimeInterval
        let lastSyncedAt: TimeInterval?
        let status: String
        let deviceName: String?
    }
}
