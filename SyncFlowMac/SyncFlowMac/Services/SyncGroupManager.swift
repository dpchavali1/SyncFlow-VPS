import Foundation
import Combine
// FirebaseDatabase - using FirebaseStubs.swift

/**
 * Manages sync group operations for macOS device
 * Handles pairing, device registration, and sync group recovery
 */
class SyncGroupManager: NSObject, ObservableObject {
    static let shared = SyncGroupManager()

    private let database = Database.database().reference()

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
     * Create a new sync group (called when device initializes)
     * Generates QR code that can be scanned by other devices
     */
    func createSyncGroup(deviceName: String = "macOS", completion: @escaping (Result<String, Error>) -> Void) {
        let newGroupId = "sync_\(UUID().uuidString)"
        let now = Date().timeIntervalSince1970

        let groupData: [String: Any] = [
            "plan": "free",
            "deviceLimit": 3,
            "masterDevice": deviceId,
            "createdAt": now,
            "devices": [
                deviceId: [
                    "deviceType": "macos",
                    "joinedAt": now,
                    "status": "active",
                    "deviceName": deviceName
                ]
            ]
        ]

        database.child("syncGroups").child(newGroupId).setValue(groupData) { [weak self] error, _ in
            if let error = error {
                completion(.failure(error))
            } else {
                self?.syncGroupId = newGroupId
                completion(.success(newGroupId))
            }
        }
    }

    /**
     * Join an existing sync group
     * Call this when user scans QR code from another device or enters group ID manually
     */
    func joinSyncGroup(
        scannedSyncGroupId: String,
        deviceName: String = "macOS",
        completion: @escaping (Result<JoinResult, Error>) -> Void
    ) {
        let groupRef = database.child("syncGroups").child(scannedSyncGroupId)

        groupRef.observeSingleEvent(of: .value) { [weak self] snapshot in
            guard let self = self else { return }

            guard snapshot.exists() else {
                completion(.failure(NSError(domain: "SyncGroup", code: -1, userInfo: [NSLocalizedDescriptionKey: "Sync group not found"])))
                return
            }

            guard let groupData = snapshot.value as? [String: Any] else {
                completion(.failure(NSError(domain: "SyncGroup", code: -2, userInfo: [NSLocalizedDescriptionKey: "Invalid group data"])))
                return
            }

            let plan = groupData["plan"] as? String ?? "free"
            let deviceLimit = plan == "free" ? 3 : 999
            let currentDevices = (groupData["devices"] as? [String: Any])?.count ?? 0

            // Check device limit
            if currentDevices >= deviceLimit {
                let error = NSError(
                    domain: "SyncGroup",
                    code: -3,
                    userInfo: [
                        NSLocalizedDescriptionKey: "Device limit reached: \(currentDevices)/\(deviceLimit). Upgrade to Pro for unlimited devices."
                    ]
                )
                completion(.failure(error))
                return
            }

            // Save locally
            self.syncGroupId = scannedSyncGroupId

            // Register device in Firebase
            let now = Date().timeIntervalSince1970
            let deviceData: [String: Any] = [
                "deviceType": "macos",
                "joinedAt": now,
                "status": "active",
                "deviceName": deviceName
            ]

            groupRef.child("devices").child(self.deviceId).setValue(deviceData) { error, _ in
                if let error = error {
                    completion(.failure(error))
                    return
                }

                // Log to history
                groupRef.child("history").child("\(Int(now * 1000))").setValue([
                    "action": "device_joined",
                    "deviceId": self.deviceId,
                    "deviceType": "macos",
                    "deviceName": deviceName
                ]) { error, _ in
                    if let error = error {
                        completion(.failure(error))
                    } else {
                        completion(.success(JoinResult(
                            success: true,
                            deviceCount: currentDevices + 1,
                            limit: deviceLimit
                        )))
                    }
                }
            }
        }
    }

    /**
     * Recover sync group on app reinstall
     * Searches Firebase for existing device entry
     */
    func recoverSyncGroup(completion: @escaping (Result<String, Error>) -> Void) {
        database.child("syncGroups").observeSingleEvent(of: .value) { [weak self] snapshot in
            guard let self = self else { return }

            guard snapshot.exists() else {
                completion(.failure(NSError(domain: "SyncGroup", code: -1, userInfo: [NSLocalizedDescriptionKey: "No sync groups found"])))
                return
            }

            // Search for sync group containing this device
            for child in snapshot.children {
                guard let childSnapshot = child as? DataSnapshot else { continue }
                let groupId = childSnapshot.key
                let devices = childSnapshot.childSnapshot(forPath: "devices").value as? [String: Any] ?? [:]

                if devices[self.deviceId] != nil {
                    self.syncGroupId = groupId
                    completion(.success(groupId))
                    return
                }
            }

            completion(.failure(NSError(domain: "SyncGroup", code: -2, userInfo: [NSLocalizedDescriptionKey: "No previous sync group found for this device"])))
        }
    }

    /**
     * Get current sync group information
     */
    func getSyncGroupInfo(completion: @escaping (Result<SyncGroupInfo, Error>) -> Void) {
        guard let groupId = syncGroupId else {
            completion(.failure(NSError(domain: "SyncGroup", code: -1, userInfo: [NSLocalizedDescriptionKey: "Device not paired to any sync group"])))
            return
        }

        database.child("syncGroups").child(groupId).observeSingleEvent(of: .value) { snapshot in
            guard snapshot.exists() else {
                completion(.failure(NSError(domain: "SyncGroup", code: -2, userInfo: [NSLocalizedDescriptionKey: "Sync group no longer exists"])))
                return
            }

            guard let groupData = snapshot.value as? [String: Any] else {
                completion(.failure(NSError(domain: "SyncGroup", code: -3, userInfo: [NSLocalizedDescriptionKey: "Invalid group data"])))
                return
            }

            let plan = groupData["plan"] as? String ?? "free"
            let deviceLimit = plan == "free" ? 3 : 999
            let devices = groupData["devices"] as? [String: Any] ?? [:]

            let devicesList = devices.compactMap { (deviceId, info) -> DeviceInfo? in
                guard let infoDict = info as? [String: Any] else { return nil }
                return DeviceInfo(
                    deviceId: deviceId,
                    deviceType: infoDict["deviceType"] as? String ?? "unknown",
                    joinedAt: infoDict["joinedAt"] as? TimeInterval ?? 0,
                    lastSyncedAt: infoDict["lastSyncedAt"] as? TimeInterval,
                    status: infoDict["status"] as? String ?? "active",
                    deviceName: infoDict["deviceName"] as? String
                )
            }

            completion(.success(SyncGroupInfo(
                plan: plan,
                deviceLimit: deviceLimit,
                deviceCount: devices.count,
                devices: devicesList
            )))
        }
    }

    /**
     * Update device last synced time
     */
    func updateLastSyncTime(completion: @escaping (Result<Bool, Error>) -> Void) {
        guard let groupId = syncGroupId else {
            completion(.failure(NSError(domain: "SyncGroup", code: -1, userInfo: [NSLocalizedDescriptionKey: "Device not paired"])))
            return
        }

        let now = Date().timeIntervalSince1970
        database.child("syncGroups").child(groupId).child("devices").child(deviceId).child("lastSyncedAt").setValue(now) { error, _ in
            if let error = error {
                completion(.failure(error))
            } else {
                completion(.success(true))
            }
        }
    }

    /**
     * Remove device from sync group
     */
    func leaveGroup(completion: @escaping (Result<Bool, Error>) -> Void) {
        guard let groupId = syncGroupId else {
            completion(.failure(NSError(domain: "SyncGroup", code: -1, userInfo: [NSLocalizedDescriptionKey: "Device not paired"])))
            return
        }

        let groupRef = database.child("syncGroups").child(groupId)

        // Remove device
        groupRef.child("devices").child(deviceId).removeValue { error, _ in
            if let error = error {
                completion(.failure(error))
                return
            }

            // Log to history
            groupRef.child("history").child("\(Int(Date().timeIntervalSince1970 * 1000))").setValue([
                "action": "device_removed",
                "deviceId": self.deviceId
            ]) { error, _ in
                if let error = error {
                    completion(.failure(error))
                } else {
                    self.syncGroupId = nil
                    completion(.success(true))
                }
            }
        }
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
