//
//  FirebaseService.swift
//  SyncFlowMac
//
//  Created by SyncFlow Team
//  Copyright (c) SyncFlow. All rights reserved.
//
//  ============================================================================
//  PURPOSE
//  ============================================================================
//  FirebaseService is the central hub for all Firebase Realtime Database operations
//  in the SyncFlow macOS client. It provides:
//
//  - Device pairing: QR code-based pairing between macOS and Android devices
//  - Message synchronization: Real-time SMS/MMS message sync from Android
//  - Contact management: Two-way contact sync between devices
//  - Call functionality: Remote call initiation and call history sync
//  - Spam filtering: Spam message detection and whitelist/blocklist management
//  - End-to-end encryption (E2EE): Secure message transmission
//
//  ============================================================================
//  ARCHITECTURE
//  ============================================================================
//  This service implements the Singleton pattern (FirebaseService.shared) to ensure
//  a single point of access for all Firebase operations across the app.
//
//  Data Flow:
//  1. Android app syncs data to Firebase Realtime Database
//  2. FirebaseService listens for changes via DatabaseHandle observers
//  3. Data is decrypted (if E2EE enabled) and transformed to model objects
//  4. Updates are dispatched to the main thread for UI consumption
//
//  Key Design Decisions:
//  - Uses DatabaseHandle for real-time listeners (call removeObserver when done)
//  - Background thread processing for large datasets to keep UI responsive
//  - Supports both V1 (legacy) and V2 (device limits) pairing protocols
//  - Graceful fallbacks when E2EE keys are unavailable
//
//  ============================================================================
//  DEPENDENCIES
//  ============================================================================
//  - FirebaseDatabase: Real-time data synchronization
//  - FirebaseAuth: Anonymous and custom token authentication
//  - FirebaseFunctions: Cloud function invocation for secure operations
//  - E2EEManager: End-to-end encryption for message content
//  - SyncGroupManager: Multi-device sync group coordination
//
//  ============================================================================
//  FIREBASE DATABASE STRUCTURE (abbreviated)
//  ============================================================================
//  users/
//    {userId}/
//      messages/          - Synced SMS/MMS messages
//      outgoing_messages/ - Messages queued for sending
//      contacts/          - Contact book sync
//      call_history/      - Phone call records
//      devices/           - Paired device registry
//      spam_messages/     - Detected spam messages
//      read_receipts/     - Message read status
//      message_reactions/ - Emoji reactions to messages
//  pairing_requests/      - V2 pairing session tokens
//  pending_pairings/      - V1 legacy pairing tokens
//  phone_to_uid/          - Phone number to user ID mapping
//

import Foundation
import IOKit
import CryptoKit
// Firebase modules - using FirebaseStubs.swift for compile compatibility
// Actual functionality now handled by VPSService.swift

// MARK: - FirebaseService

/// Central service for all Firebase Realtime Database operations.
///
/// FirebaseService manages the entire data synchronization layer between the macOS
/// client and the Android companion app via Firebase. It handles authentication,
/// real-time data listeners, message encryption/decryption, and device pairing.
///
/// ## Usage
/// ```swift
/// // Access the singleton instance
/// let firebase = FirebaseService.shared
///
/// // Start listening for messages
/// let handle = firebase.listenToMessages(userId: userId) { messages in
///     // Handle updated messages
/// }
///
/// // Clean up when done
/// firebase.removeMessageListener(userId: userId, handle: handle)
/// ```
///
/// ## Thread Safety
/// - All completion handlers are dispatched to the main thread
/// - Heavy data processing occurs on background queues
/// - Always call remove*Listener methods when views disappear
class FirebaseService {

    // MARK: - Singleton

    /// Shared singleton instance for app-wide Firebase access.
    static let shared = FirebaseService()

    // MARK: - Firebase Operation Timeout Utility

    /// Wraps async Firebase operations with a timeout to prevent indefinite hangs.
    ///
    /// **Problem:** Firebase operations (getData, setValue, etc.) can hang indefinitely
    /// if the network is slow, disconnected, or Firebase is unresponsive. This causes
    /// the app to freeze and triggers ANR (Application Not Responding) issues.
    ///
    /// **Solution:** Wrap all Firebase operations with this timeout wrapper. If the
    /// operation doesn't complete within the specified timeout, it's cancelled and
    /// an error is thrown.
    ///
    /// ## Usage
    /// ```swift
    /// let snapshot = try await withTimeout(5.0) {
    ///     try await database.reference().child("path").getData()
    /// }
    /// ```
    ///
    /// ## Timeout Values
    /// - Read operations (getData): 5 seconds
    /// - Write operations (setValue, updateChildValues): 10 seconds
    /// - Critical operations (pairing, key sync): 30-60 seconds
    ///
    /// ## Error Handling
    /// If timeout occurs, throws `FirebaseServiceError.timeout`
    ///
    /// - Parameters:
    ///   - seconds: Timeout duration in seconds
    ///   - operation: Async operation to execute with timeout
    /// - Returns: Result of the operation if completed within timeout
    /// - Throws: FirebaseServiceError.timeout if operation exceeds timeout duration
    private func withTimeout<T>(
        _ seconds: TimeInterval,
        operation: @escaping () async throws -> T
    ) async throws -> T {
        return try await withThrowingTaskGroup(of: T.self) { group in
            // Task 1: Execute the actual operation
            group.addTask {
                return try await operation()
            }

            // Task 2: Timeout timer
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                throw FirebaseServiceError.timeout(seconds: seconds)
            }

            // Wait for first task to complete (either operation or timeout)
            guard let result = try await group.next() else {
                throw FirebaseServiceError.timeout(seconds: seconds)
            }

            // Cancel the other task (if operation finished, cancel timeout; if timeout, cancel operation)
            group.cancelAll()

            return result
        }
    }

    /// Firebase service errors
    enum FirebaseServiceError: LocalizedError {
        case timeout(seconds: TimeInterval)
        case networkUnavailable
        case operationCancelled

        var errorDescription: String? {
            switch self {
            case .timeout(let seconds):
                return "Firebase operation timed out after \(Int(seconds)) seconds. Please check your internet connection."
            case .networkUnavailable:
                return "Network unavailable. Please check your internet connection."
            case .operationCancelled:
                return "Operation was cancelled."
            }
        }
    }

    // MARK: - Firebase References

    /// Firebase Realtime Database instance.
    /// Marked fileprivate to allow access from extensions while preventing external modification.
    fileprivate let database = Database.database()

    /// Firebase Authentication instance for user identity management.
    private let auth = Auth.auth()

    /// Firebase Cloud Functions client for secure server-side operations.
    /// Region is set to us-central1 to match the deployed functions.
    private let functions = Functions.functions(region: "us-central1")

    private func isE2eeEnabled() -> Bool {
        let defaults = UserDefaults.standard
        if defaults.object(forKey: "e2ee_enabled") == nil {
            defaults.set(true, forKey: "e2ee_enabled")
            return true
        }
        return defaults.bool(forKey: "e2ee_enabled")
    }

    // MARK: - Connection Management

    /// Ensures Firebase database connection is active before write operations.
    ///
    /// Firebase may go offline due to network changes or power saving. This method
    /// forces the connection back online before critical write operations like
    /// sending messages or updating device status.
    ///
    /// - Note: Called automatically by all write methods (send*, update*, delete*, etc.)
    private func ensureOnline() {
        database.goOnline()
    }

    // MARK: - Sync Group Management

    /// Manager for multi-device sync group coordination.
    /// Handles grouping multiple devices (macOS, web, etc.) under a single sync umbrella.
    let syncGroupManager = SyncGroupManager.shared

    /// Current sync group identifier, persisted to UserDefaults on change.
    /// Sync groups allow multiple secondary devices to share access to the same Android phone's data.
    private var _syncGroupId: String? {
        didSet {
            if let id = _syncGroupId {
                UserDefaults.standard.set(id, forKey: "sync_group_id")
            }
        }
    }

    // MARK: - Initialization

    /// Private initializer enforcing singleton pattern.
    /// Restores sync group ID from UserDefaults on app launch.
    private init() {
        // Restore sync group ID on init to maintain pairing state across app restarts
        self._syncGroupId = syncGroupManager.syncGroupId
    }

    // MARK: - Authentication

    /// Signs in anonymously to Firebase Auth.
    ///
    /// Anonymous authentication is used during the initial pairing flow before
    /// the user's identity is established. After successful pairing, this is
    /// replaced with a custom token from the Android device's user ID.
    ///
    /// - Returns: The Firebase UID for the anonymous user
    /// - Throws: `FirebaseError.authFailed` if authentication fails
    ///
    /// - Note: Also initializes E2EE keys after successful sign-in
    func signInAnonymously() async throws -> String {
        let result = try await auth.signInAnonymously()
        guard let uid = result.user.uid as String? else {
            throw FirebaseError.authFailed
        }
        // Initialize encryption keys for secure message transmission
        try? await E2EEManager.shared.initializeKeys()
        return uid
    }

    /// Gets the current authenticated user's ID.
    ///
    /// Prioritizes the stored paired user ID over the Firebase Auth current user
    /// to handle cases where the auth session expires but the pairing is still valid.
    /// This prevents data from being written to the wrong location.
    ///
    /// - Returns: The user ID string, or nil if not authenticated
    func getCurrentUser() -> String? {
        // Prefer the stored paired user ID over the current auth user
        // This prevents data being written to wrong location when auth session expires
        // and app falls back to anonymous sign-in
        if let storedUserId = UserDefaults.standard.string(forKey: "syncflow_user_id"),
           !storedUserId.isEmpty {
            return storedUserId
        }
        return auth.currentUser?.uid
    }

    /// Gets the unique device identifier for this Mac.
    ///
    /// Returns the paired device ID if available, otherwise generates a new
    /// persistent device ID based on hardware UUID.
    ///
    /// - Returns: A unique device identifier string
    func getDeviceId() -> String {
        if let pairedDeviceId = UserDefaults.standard.string(forKey: "syncflow_device_id"),
           !pairedDeviceId.isEmpty {
            return pairedDeviceId
        }
        return getOrCreateDeviceId()
    }

    /// The current sync group identifier, if the device is part of a sync group.
    var syncGroupId: String? {
        _syncGroupId
    }

    // MARK: - Sync Group Pairing

    /// Initiates sync group pairing for first-time setup or re-pairing.
    ///
    /// Sync groups allow multiple secondary devices to share access to the same
    /// Android phone's data. This method creates a new sync group if one doesn't exist.
    ///
    /// - Parameter completion: Callback with the sync group ID on success or error
    ///
    /// ## Pairing Flow
    /// 1. Check if sync group already exists (return immediately if so)
    /// 2. Ensure anonymous authentication
    /// 3. Create new sync group via SyncGroupManager
    /// 4. Store sync group ID for persistence
    func initiateSyncGroupPairing(completion: @escaping (Result<String, Error>) -> Void) {
        // Check if already has sync group
        if let existing = syncGroupManager.syncGroupId {
            completion(.success(existing))
            return
        }

        Task {
            do {
                if auth.currentUser == nil {
                    _ = try await signInAnonymously()
                }

                // Create new sync group
                syncGroupManager.createSyncGroup(deviceName: "macOS") { result in
                    switch result {
                    case .success(let groupId):
                        self._syncGroupId = groupId
                        completion(.success(groupId))

                    case .failure(let error):
                        completion(.failure(error))
                    }
                }
            } catch {
                completion(.failure(error))
            }
        }
    }

    /// Determines if an address is an RCS (Rich Communication Services) address.
    ///
    /// RCS messages are filtered out because they use Google's messaging infrastructure
    /// rather than traditional SMS/MMS. SyncFlow focuses on SMS/MMS synchronization.
    ///
    /// - Parameter address: The message address to check
    /// - Returns: true if the address appears to be an RCS address
    fileprivate func isRcsAddress(_ address: String) -> Bool {
        let lower = address.lowercased()
        return lower.contains("@rcs") ||
            lower.contains("rcs.google") ||
            lower.contains("rcs.goog") ||
            lower.hasPrefix("rcs:") ||
            lower.hasPrefix("rcs://")
    }

    // MARK: - Pairing

    /// Pairs this Mac with an Android device using a pairing token.
    ///
    /// This is the legacy pairing method using a 6-digit token entered manually.
    /// The newer QR code pairing flow (initiatePairing) is preferred.
    ///
    /// - Parameters:
    ///   - token: The 6-digit pairing token from the Android app
    ///   - deviceName: Display name for this Mac device
    /// - Returns: The paired user's Firebase UID
    /// - Throws: FirebaseError if pairing fails
    func pairWithToken(_ token: String, deviceName: String) async throws -> String {
        do {
            let result = try await functions
                .httpsCallable("redeemPairingToken")
                .call([
                    "token": token,
                    "deviceName": deviceName,
                    "deviceType": "macos"
                ])

        guard let data = result.data as? [String: Any],
              let customToken = data["customToken"] as? String,
              let pairedUid = data["pairedUid"] as? String,
              let deviceId = data["deviceId"] as? String else {
            throw FirebaseError.invalidTokenData
        }

        _ = try await auth.signIn(withCustomToken: customToken)
        UserDefaults.standard.set(deviceId, forKey: "syncflow_device_id")
        try? await E2EEManager.shared.initializeKeys()

        return pairedUid
        } catch {
            let nsError = error as NSError
            if nsError.domain == FunctionsErrorDomain,
               let code = FunctionsErrorCode(rawValue: nsError.code) {
                let detail = nsError.userInfo[FunctionsErrorDetailsKey] ?? ""
                throw NSError(
                    domain: nsError.domain,
                    code: nsError.code,
                    userInfo: [
                        NSLocalizedDescriptionKey: "Pairing failed (\(code)): \(detail)"
                    ]
                )
            }
            throw error
        }
    }

    // MARK: - QR Code Pairing V2 (Uses persistent device IDs)

    /// Initiates a new pairing session for QR code-based device pairing.
    ///
    /// This is the primary pairing method for new users. It generates a QR code
    /// that the Android app scans to establish the connection.
    ///
    /// - Parameters:
    ///   - deviceName: Optional custom name for this Mac (defaults to system hostname)
    ///   - syncGroupId: Optional existing sync group to join
    /// - Returns: A PairingSession containing the QR code payload and expiration time
    /// - Throws: FirebaseError on failure
    ///
    /// ## Pairing Protocol Versions
    /// - **V2 (preferred)**: Uses persistent device IDs with device limit enforcement
    /// - **V1 (fallback)**: Legacy system for backwards compatibility with older Android versions
    ///
    /// The method automatically tries V2 first and falls back to V1 if needed.
    func initiatePairing(deviceName: String? = nil, syncGroupId: String? = nil) async throws -> PairingSession {
        // Get persistent device ID from DeviceIdentifier (Keychain-backed)
        let deviceId = DeviceIdentifier.shared.getDeviceId()
        let macDeviceName = deviceName ?? Host.current().localizedName ?? "Mac"
        let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "2.0.0"

        // Try V2 pairing first (new system with device limit enforcement)
        do {
            return try await initiatePairingV2(
                deviceId: deviceId,
                deviceName: macDeviceName,
                appVersion: appVersion
            )
        } catch {
            print("[Firebase] V2 pairing failed, falling back to V1: \(error)")
            // Fall back to V1 for backwards compatibility
            return try await initiatePairingV1(
                deviceName: macDeviceName,
                syncGroupId: syncGroupId
            )
        }
    }

    /// V2 Pairing - Uses new Cloud Function with device limit enforcement
    private func initiatePairingV2(deviceId: String, deviceName: String, appVersion: String) async throws -> PairingSession {
        // For pairing, we need a fresh anonymous session to listen for approval
        // Custom tokens from previous pairings may have expired, causing permission errors
        // So we sign out and sign in fresh to ensure we have valid credentials
        if let currentUser = auth.currentUser {
            // If signed in with a device token (from previous pairing), sign out first
            // Device tokens expire and cause permission_denied errors
            if currentUser.uid.hasPrefix("device_") {
                try? auth.signOut()
            }
        }

        // Sign in anonymously to get fresh credentials for listening
        if auth.currentUser == nil {
            _ = try await auth.signInAnonymously()
        }

        let payload: [String: Any] = [
            "deviceId": deviceId,
            "deviceName": deviceName,
            "deviceType": "macos",
            "appVersion": appVersion
        ]


        let result = try await functions
            .httpsCallable("initiatePairingV2")
            .call(payload)

        guard let data = result.data as? [String: Any],
              let success = data["success"] as? Bool, success,
              let token = data["token"] as? String,
              let qrPayload = data["qrPayload"] as? String,
              let expiresAt = data["expiresAt"] as? Double else {
            print("[Firebase] V2 pairing invalid response: \(result.data)")
            throw FirebaseError.invalidTokenData
        }

        // Initialize E2EE keys if not already done (ensures we have a keypair for key exchange)
        try? await E2EEManager.shared.initializeKeys()

        // Append macOS public key to QR payload for direct E2EE key exchange
        // Format: originalQrPayload|macPublicKeyX963Base64
        // Android will parse this, and after pairing approval, encrypt E2EE keys using this public key
        var enhancedQrPayload = qrPayload
        if let macPublicKey = E2EEManager.shared.getMyPublicKeyX963Base64() {
            enhancedQrPayload = "\(qrPayload)|\(macPublicKey)"
            print("[Firebase] Added macOS public key to QR payload for direct E2EE key exchange")
        } else {
            print("[Firebase] Warning: Could not get macOS public key for QR payload")
        }

        return PairingSession(
            token: token,
            qrPayload: enhancedQrPayload,
            expiresAt: expiresAt,
            version: 2
        )
    }

    /// V1 Pairing - Legacy system for backwards compatibility
    private func initiatePairingV1(deviceName: String, syncGroupId: String?) async throws -> PairingSession {
        // Ensure we are authenticated so the pairing session is scoped to this requester.
        if auth.currentUser == nil {
            _ = try await auth.signInAnonymously()
        }

        var payload: [String: Any] = [
            "deviceName": deviceName,
            "platform": "macos",
            "appVersion": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0"
        ]
        if let syncGroupId = syncGroupId, !syncGroupId.isEmpty {
            payload["syncGroupId"] = syncGroupId
        }

        print("[Firebase] Initiating V1 pairing with payload: \(payload)")

        let result = try await functions
            .httpsCallable("initiatePairing")
            .call(payload)

        print("[Firebase] V1 pairing result: \(result.data)")

        guard let data = result.data as? [String: Any],
              let token = data["token"] as? String,
              let qrPayload = data["qrPayload"] as? String,
              let expiresAt = data["expiresAt"] as? Double else {
            print("[Firebase] Invalid token data received: \(result.data)")
            throw FirebaseError.invalidTokenData
        }

        // Initialize E2EE keys if not already done
        try? await E2EEManager.shared.initializeKeys()

        // Append macOS public key to QR payload for direct E2EE key exchange
        var enhancedQrPayload = qrPayload
        if let macPublicKey = E2EEManager.shared.getMyPublicKeyX963Base64() {
            enhancedQrPayload = "\(qrPayload)|\(macPublicKey)"
            print("[Firebase] V1: Added macOS public key to QR payload")
        }

        return PairingSession(
            token: token,
            qrPayload: enhancedQrPayload,
            expiresAt: expiresAt,
            version: 1
        )
    }

    /// Listen for pairing approval after Android user scans QR and approves
    /// Uses the pairing version to listen on the correct path:
    /// - Version 2: pairing_requests (new system)
    /// - Version 1: pending_pairings (legacy system)
    func listenForPairingApproval(token: String, version: Int = 2, completion: @escaping (PairingStatus) -> Void) -> DatabaseHandle {
        let path = version == 2 ? "pairing_requests" : "pending_pairings"
        return listenForPairingApprovalOnPath(token: token, path: path, completion: completion)
    }

    /// Pairing listener - listens on the specified path
    private func listenForPairingApprovalOnPath(token: String, path: String, completion: @escaping (PairingStatus) -> Void) -> DatabaseHandle {
        let pairingRef = database.reference()
            .child(path)
            .child(token)

        let handle = pairingRef.observe(.value) { [weak self] snapshot in
            guard let self = self else { return }


            guard snapshot.exists(),
                  let data = snapshot.value as? [String: Any] else {
                // No data yet - still pending or expired
                print("[Firebase] No pairing data found at \(path)/\(token.prefix(8))")
                return
            }


            let now = Date().timeIntervalSince1970 * 1000
            if let expiresAt = data["expiresAt"] as? Double, now > expiresAt {
                completion(.expired)
                return
            }

            guard let status = data["status"] as? String else {
                completion(.pending)
                return
            }

            switch status {
            case "approved":
                guard let customToken = data["customToken"] as? String,
                      let pairedUid = data["pairedUid"] as? String else {
                    completion(.expired)
                    return
                }

                // Sign in with the custom token
                Task {
                    do {
                        _ = try await self.auth.signIn(withCustomToken: customToken)

                        // Save device ID from DeviceIdentifier (persistent)
                        let deviceId = data["deviceId"] as? String ?? DeviceIdentifier.shared.getDeviceId()
                        UserDefaults.standard.set(deviceId, forKey: "syncflow_device_id")

                        try? await E2EEManager.shared.initializeKeys()

                        await MainActor.run {
                            completion(.approved(pairedUid: pairedUid, deviceId: deviceId))
                        }
                    } catch {
                        print("[Firebase] Failed to sign in with custom token: \(error)")
                        await MainActor.run {
                            completion(.expired)
                        }
                    }
                }

            case "rejected":
                completion(.rejected)

            default:
                completion(.pending)
            }
        }

        return handle
    }

    /// Check V1 pairing path as fallback
    private func checkV1PairingPath(token: String, completion: @escaping (PairingStatus) -> Void) {
        let v1Ref = database.reference()
            .child("pending_pairings")
            .child(token)

        v1Ref.observeSingleEvent(of: .value) { [weak self] snapshot in
            guard let self = self else { return }

            guard snapshot.exists(),
                  let data = snapshot.value as? [String: Any] else {
                completion(.expired)
                return
            }

            // Handle V1 format
            let now = Date().timeIntervalSince1970 * 1000
            if let expiresAt = data["expiresAt"] as? Double, now > expiresAt {
                completion(.expired)
                return
            }

            guard let status = data["status"] as? String else {
                completion(.pending)
                return
            }

            switch status {
            case "approved":
                guard let customToken = data["customToken"] as? String,
                      let pairedUid = data["pairedUid"] as? String else {
                    completion(.expired)
                    return
                }

                Task {
                    do {
                        _ = try await self.auth.signIn(withCustomToken: customToken)
                        if let deviceId = data["deviceId"] as? String {
                            UserDefaults.standard.set(deviceId, forKey: "syncflow_device_id")
                        }
                        try? await E2EEManager.shared.initializeKeys()

                        await MainActor.run {
                            completion(.approved(pairedUid: pairedUid, deviceId: data["deviceId"] as? String))
                        }
                    } catch {
                        print("[Firebase] V2 Failed to sign in: \(error)")
                        await MainActor.run {
                            completion(.expired)
                        }
                    }
                }

            case "rejected":
                completion(.rejected)

            default:
                completion(.pending)
            }
        }
    }

    /// Remove pairing approval listener (handles both V1 and V2 paths)
    func removePairingApprovalListener(token: String, handle: DatabaseHandle) {
        // Remove from V2 path
        database.reference()
            .child("pairing_requests")
            .child(token)
            .removeObserver(withHandle: handle)

        // Also try to remove from V1 path (in case it exists there)
        database.reference()
            .child("pending_pairings")
            .child(token)
            .removeObserver(withHandle: handle)
    }

    func unregisterDevice(deviceId: String, completion: @escaping (Error?) -> Void) {
        let data: [String: Any] = ["deviceId": deviceId]

        functions.httpsCallable("unregisterDevice").call(data) { result, error in
            if let error = error {
                print("Failed to unregister device \(deviceId): \(error)")
                completion(error)
            } else {
                print("Successfully unregistered device: \(deviceId)")
                completion(nil)
            }
        }
    }

    /// Get or create a persistent device ID for this Mac
    private func getOrCreateDeviceId() -> String {
        let key = "SyncFlowMacDeviceId"

        // Try to get existing device ID from UserDefaults
        if let existingId = UserDefaults.standard.string(forKey: key), !existingId.isEmpty {
            return existingId
        }

        // Try to get hardware UUID
        if let hardwareUUID = getHardwareUUID() {
            // Use a hash of the hardware UUID for privacy
            let deviceId = hashedDeviceId(from: hardwareUUID)
            UserDefaults.standard.set(deviceId, forKey: key)
            return deviceId
        }

        // Fallback: generate a UUID and store it
        let newId = "mac_\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16))"
        UserDefaults.standard.set(newId, forKey: key)
        return newId
    }

    /// Get the Mac's hardware UUID
    private func getHardwareUUID() -> String? {
        let platformExpert = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("IOPlatformExpertDevice"))
        defer { IOObjectRelease(platformExpert) }

        guard platformExpert != 0,
              let serialNumberAsCFString = IORegistryEntryCreateCFProperty(platformExpert, kIOPlatformUUIDKey as CFString, kCFAllocatorDefault, 0)?.takeUnretainedValue() as? String else {
            return nil
        }

        return serialNumberAsCFString.replacingOccurrences(of: "-", with: "")
    }

    private func hashedDeviceId(from hardwareUUID: String) -> String {
        let digest = SHA256.hash(data: Data(hardwareUUID.utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return "mac_\(hex.prefix(16))"
    }

    /// Clean up old device entries for this Mac (by matching device name)
    private func cleanupOldDeviceEntries(userId: String, currentDeviceId: String, deviceName: String) async throws {
        let devicesRef = database.reference()
            .child("users")
            .child(userId)
            .child("devices")

        let snapshot = try await devicesRef.getData()

        guard let devicesDict = snapshot.value as? [String: Any] else {
            return
        }

        // Find and remove old entries with the same device name but different ID
        for (deviceId, value) in devicesDict {
            guard let deviceData = value as? [String: Any],
                  let name = deviceData["name"] as? String,
                  let platform = deviceData["platform"] as? String ?? deviceData["type"] as? String else {
                continue
            }

            // Remove old macOS entries with the same name (or generic "Desktop" name)
            let isMacDevice = platform.lowercased().contains("mac") || platform == "macos"
            let isSameName = name == deviceName || name == "Desktop" || name == Host.current().localizedName

            if deviceId != currentDeviceId && isMacDevice && isSameName {
                print("[Firebase] Removing old device entry: \(deviceId) (\(name))")
                try await devicesRef.child(deviceId).removeValue()
            }
        }
    }

    // MARK: - Messages

    /// Starts listening for real-time message updates from Firebase.
    ///
    /// This is the primary method for receiving SMS/MMS messages synced from the Android device.
    /// Messages are processed on a background thread and delivered to the completion handler
    /// on the main thread.
    ///
    /// - Parameters:
    ///   - userId: The Firebase user ID to listen for messages
    ///   - startTime: Optional timestamp (milliseconds) to filter messages after this time
    ///   - limit: Maximum number of messages to load (default 3000, prevents memory issues)
    ///   - completion: Callback invoked with the updated message array on every change
    /// - Returns: DatabaseHandle to remove the listener when done
    ///
    /// ## Message Processing
    /// 1. Raw Firebase data is received as dictionary
    /// 2. E2EE encrypted messages are decrypted using device's private key
    /// 3. MMS attachments are parsed and linked
    /// 4. Messages are sorted by date (newest first)
    /// 5. Results are dispatched to main thread
    ///
    /// ## Important
    /// Always call `removeMessageListener(userId:handle:)` when the view disappears
    /// to prevent memory leaks and unnecessary network traffic.
    ///
    /// ```swift
    /// // Start listening
    /// let handle = firebaseService.listenToMessages(userId: userId) { messages in
    ///     self.messages = messages
    /// }
    ///
    /// // Stop listening
    /// firebaseService.removeMessageListener(userId: userId, handle: handle)
    /// ```
    func listenToMessages(userId: String, startTime: Double? = nil, limit: Int = 200, completion: @escaping ([Message]) -> Void) -> DatabaseHandle {
        let messagesRef = database.reference()
            .child("users")
            .child(userId)
            .child("messages")

        // BANDWIDTH OPTIMIZATION: Limit to last 200 messages (about 1 week of active use)
        // Older messages can be loaded on-demand via pagination
        // This reduces initial download from 50+ MB to ~2-3 MB

        // Default to last 30 days if no startTime specified
        let effectiveStartTime = startTime ?? (Date().timeIntervalSince1970 * 1000 - 30 * 24 * 60 * 60 * 1000)

        var query = messagesRef.queryOrdered(byChild: "date")
            .queryStarting(atValue: effectiveStartTime)
            .queryLimited(toLast: UInt(limit))

        print("[Firebase] Message query: last \(limit) messages from \(Date(timeIntervalSince1970: effectiveStartTime/1000))")
        print("[Firebase] BANDWIDTH OPTIMIZATION: Loading only recent messages, older messages available on-demand")

        let handle = query.observe(.value) { [weak self] snapshot in
            guard let self = self else { return }

            guard snapshot.exists(),
                  let messagesDict = snapshot.value as? [String: Any] else {
                DispatchQueue.main.async {
                    completion([])
                }
                return
            }

            // Process messages on background thread to avoid blocking UI
            DispatchQueue.global(qos: .userInitiated).async {
                var messages: [Message] = []
                let deviceId = self.getDeviceId()

                for (key, value) in messagesDict {
                    guard let messageData = value as? [String: Any],
                          let address = messageData["address"] as? String,
                          let body = messageData["body"] as? String,
                          let date = self.normalizeMessageDate(messageData) ,
                          let type = messageData["type"] as? Int else {
                        continue
                    }

                    if self.isRcsAddress(address) {
                        continue
                    }

                    let contactName = messageData["contactName"] as? String
                    let isEncrypted = messageData["encrypted"] as? Bool ?? false
                    let isMms = messageData["isMms"] as? Bool ?? false
                    let isReadFromSync = messageData["read"] as? Bool

                    // Decrypt message body if encrypted
                    var decryptedBody = body
                    var decryptionFailed = false
                    if isEncrypted {
                        // NEW (v3): Try e2ee_envelope first (single envelope for all devices)
                        if let e2eeEnvelope = messageData["e2ee_envelope"] as? String,
                           let nonceBase64 = messageData["nonce"] as? String,
                           let nonceData = Data(base64Encoded: nonceBase64),
                           let ciphertextData = Data(base64Encoded: body) {
                            do {
                                let dataKey = try E2EEManager.shared.decryptDataKey(from: e2eeEnvelope)
                                decryptedBody = try E2EEManager.shared.decryptMessageBody(
                                    dataKey: dataKey,
                                    ciphertextWithTag: ciphertextData,
                                    nonce: nonceData
                                )
                            } catch {
                                decryptionFailed = true
                            }
                        }
                        // OLD (v2): Fall back to keyMap for backward compatibility
                        else if let keyMap = messageData["keyMap"] as? [String: Any],
                                let nonceBase64 = messageData["nonce"] as? String,
                                let deviceEnvelope = keyMap[deviceId] as? String,
                                let nonceData = Data(base64Encoded: nonceBase64),
                                let ciphertextData = Data(base64Encoded: body) {
                            do {
                                let dataKey = try E2EEManager.shared.decryptDataKey(from: deviceEnvelope)
                                decryptedBody = try E2EEManager.shared.decryptMessageBody(
                                    dataKey: dataKey,
                                    ciphertextWithTag: ciphertextData,
                                    nonce: nonceData
                                )
                            } catch {
                                decryptionFailed = true
                            }
                        }
                        // LEGACY (v1): Direct encryption (no envelope)
                        else {
                            do {
                                decryptedBody = try E2EEManager.shared.decryptMessage(body)
                            } catch {
                                decryptionFailed = true
                            }
                        }

                        // If decryption failed, show a user-friendly message instead of garbled text
                        if decryptionFailed {
                            decryptedBody = "[🔒 Encrypted message - sync keys to decrypt]"
                        }
                    }

                    // Parse MMS attachments if present (support array or dictionary payloads)
                    var attachments: [MmsAttachment]? = nil
                    if isMms, let attachmentsData = self.extractAttachmentList(from: messageData["attachments"]) {
                        attachments = attachmentsData.compactMap { attachData in
                            guard let id = self.parseAttachmentId(attachData["id"]),
                                  let contentType = attachData["contentType"] as? String else {
                                return nil
                            }
                            let attachType = (attachData["type"] as? String) ?? self.inferAttachmentType(from: contentType)
                            let encrypted: Bool?
                            if let encryptedBool = attachData["encrypted"] as? Bool {
                                encrypted = encryptedBool
                            } else if let encryptedString = attachData["encrypted"] as? String {
                                encrypted = (encryptedString as NSString).boolValue
                            } else {
                                encrypted = nil
                            }
                            return MmsAttachment(
                                id: id,
                                contentType: contentType,
                                fileName: attachData["fileName"] as? String,
                                url: attachData["url"] as? String,
                                type: attachType,
                                encrypted: encrypted,
                                inlineData: attachData["inlineData"] as? String,
                                isInline: attachData["isInline"] as? Bool
                            )
                        }
                    }

                    var message = Message(
                        id: key,
                        address: address,
                        body: decryptedBody,
                        date: date,
                        type: type,
                        contactName: contactName,
                        isMms: isMms,
                        attachments: attachments,
                        isEncrypted: isEncrypted
                    )

                    // Set read status from Android sync data (if available)
                    if let isReadFromSync = isReadFromSync {
                        message.isRead = isReadFromSync
                    }

                    messages.append(message)
                }

                // Sort by date (newest first)
                messages.sort { $0.date > $1.date }

                // Return to main thread for UI update
                DispatchQueue.main.async {
                    completion(messages)
                }
            }
        }

        return handle
    }

    private func parseAttachmentId(_ rawId: Any?) -> String? {
        if let idString = rawId as? String, !idString.isEmpty {
            return idString
        }
        if let number = rawId as? NSNumber {
            return number.stringValue
        }
        if let intValue = rawId as? Int {
            return String(intValue)
        }
        if let int64Value = rawId as? Int64 {
            return String(int64Value)
        }
        if let doubleValue = rawId as? Double {
            return String(Int64(doubleValue))
        }
        return nil
    }

    private func inferAttachmentType(from contentType: String) -> String {
        if contentType.hasPrefix("image/") { return "image" }
        if contentType.hasPrefix("video/") { return "video" }
        if contentType.hasPrefix("audio/") { return "audio" }
        if contentType.contains("vcard") { return "vcard" }
        return "file"
    }

    private func extractAttachmentList(from raw: Any?) -> [[String: Any]]? {
        if let list = raw as? [[String: Any]], !list.isEmpty {
            return list
        }
        if let dict = raw as? [String: Any], !dict.isEmpty {
            let sorted = dict.sorted { lhs, rhs in
                let leftIndex = Int(lhs.key) ?? Int.max
                let rightIndex = Int(rhs.key) ?? Int.max
                if leftIndex != rightIndex {
                    return leftIndex < rightIndex
                }
                return lhs.key < rhs.key
            }
            let normalized = sorted.compactMap { $0.value as? [String: Any] }
            return normalized.isEmpty ? nil : normalized
        }
        return nil
    }

    private func normalizeMessageDate(_ messageData: [String: Any]) -> Double? {
        let rawDate = coerceToDouble(messageData["date"])
        let rawTimestamp = coerceToDouble(messageData["timestamp"])
        guard let value = rawDate ?? rawTimestamp, value > 0 else {
            return nil
        }
        // Heuristic: seconds vs milliseconds since epoch
        return value < 1_000_000_000_000 ? value * 1000.0 : value
    }

    private func coerceToDouble(_ value: Any?) -> Double? {
        switch value {
        case let number as NSNumber:
            return number.doubleValue
        case let doubleValue as Double:
            return doubleValue
        case let intValue as Int:
            return Double(intValue)
        case let int64Value as Int64:
            return Double(int64Value)
        case let stringValue as String:
            return Double(stringValue)
        default:
            return nil
        }
    }

    func removeMessageListener(userId: String, handle: DatabaseHandle) {
        let messagesRef = database.reference()
            .child("users")
            .child(userId)
            .child("messages")

        messagesRef.removeObserver(withHandle: handle)
    }

    // Load messages in a specific time range (for pagination)
    func loadMessagesInTimeRange(userId: String, startTime: Double, endTime: Double) async throws -> [Message] {
        let messagesRef = database.reference()
            .child("users")
            .child(userId)
            .child("messages")

        print("[Firebase] Loading messages from \(Date(timeIntervalSince1970: startTime/1000)) to \(Date(timeIntervalSince1970: endTime/1000))")

        let snapshot = try await messagesRef
            .queryOrdered(byChild: "date")
            .queryStarting(atValue: startTime)
            .queryEnding(atValue: endTime)
            .getData()

        guard snapshot.exists(),
              let messagesDict = snapshot.value as? [String: Any] else {
            return []
        }

        var messages: [Message] = []
        let deviceId = getDeviceId()

        for (key, value) in messagesDict {
            guard let messageData = value as? [String: Any],
                  let address = messageData["address"] as? String,
                  let body = messageData["body"] as? String,
                  let date = normalizeMessageDate(messageData),
                  let type = messageData["type"] as? Int else {
                continue
            }

            if isRcsAddress(address) {
                continue
            }

            let contactName = messageData["contactName"] as? String
            let isEncrypted = messageData["encrypted"] as? Bool ?? false
            let isMms = messageData["isMms"] as? Bool ?? false
            let isReadFromSync = messageData["read"] as? Bool

            // Decrypt message body if encrypted
            var decryptedBody = body
            var decryptionFailed = false
            if isEncrypted {
                // NEW (v3): Try e2ee_envelope first (single envelope for all devices)
                if let e2eeEnvelope = messageData["e2ee_envelope"] as? String,
                   let nonceBase64 = messageData["nonce"] as? String,
                   let nonceData = Data(base64Encoded: nonceBase64),
                   let ciphertextData = Data(base64Encoded: body) {
                    do {
                        let dataKey = try E2EEManager.shared.decryptDataKey(from: e2eeEnvelope)
                        decryptedBody = try E2EEManager.shared.decryptMessageBody(
                            dataKey: dataKey,
                            ciphertextWithTag: ciphertextData,
                            nonce: nonceData
                        )
                    } catch {
                        decryptionFailed = true
                    }
                }
                // OLD (v2): Fall back to keyMap for backward compatibility
                else if let keyMap = messageData["keyMap"] as? [String: Any],
                        let nonceBase64 = messageData["nonce"] as? String,
                        let deviceEnvelope = keyMap[deviceId] as? String,
                        let nonceData = Data(base64Encoded: nonceBase64),
                        let ciphertextData = Data(base64Encoded: body) {
                    do {
                        let dataKey = try E2EEManager.shared.decryptDataKey(from: deviceEnvelope)
                        decryptedBody = try E2EEManager.shared.decryptMessageBody(
                            dataKey: dataKey,
                            ciphertextWithTag: ciphertextData,
                            nonce: nonceData
                        )
                    } catch {
                        decryptionFailed = true
                    }
                }
                // LEGACY (v1): Direct encryption (no envelope)
                else {
                    do {
                        decryptedBody = try E2EEManager.shared.decryptMessage(body)
                    } catch {
                        decryptionFailed = true
                    }
                }

                if decryptionFailed {
                    decryptedBody = "[🔒 Encrypted message - sync keys to decrypt]"
                }
            }

            // Parse MMS attachments if present
            var attachments: [MmsAttachment]? = nil
            if isMms, let attachmentsData = extractAttachmentList(from: messageData["attachments"]) {
                attachments = attachmentsData.compactMap { attachData in
                    guard let id = parseAttachmentId(attachData["id"]),
                          let contentType = attachData["contentType"] as? String else {
                        return nil
                    }
                    let attachType = (attachData["type"] as? String) ?? inferAttachmentType(from: contentType)
                    let encrypted: Bool?
                    if let encryptedBool = attachData["encrypted"] as? Bool {
                        encrypted = encryptedBool
                    } else if let encryptedString = attachData["encrypted"] as? String {
                        encrypted = (encryptedString as NSString).boolValue
                    } else {
                        encrypted = nil
                    }
                    return MmsAttachment(
                        id: id,
                        contentType: contentType,
                        fileName: attachData["fileName"] as? String,
                        url: attachData["url"] as? String,
                        type: attachType,
                        encrypted: encrypted,
                        inlineData: attachData["inlineData"] as? String,
                        isInline: attachData["isInline"] as? Bool
                    )
                }
            }

            var message = Message(
                id: key,
                address: address,
                body: decryptedBody,
                date: date,
                type: type,
                contactName: contactName,
                isMms: isMms,
                attachments: attachments,
                isEncrypted: isEncrypted
            )

            if let isReadFromSync = isReadFromSync {
                message.isRead = isReadFromSync
            }

            messages.append(message)
        }

        print("[Firebase] Loaded \(messages.count) messages in time range")
        return messages
    }

    // MARK: - Spam Messages

    func listenToSpamMessages(userId: String, completion: @escaping ([SpamMessage]) -> Void) -> DatabaseHandle {
        let spamRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_messages")

        // BANDWIDTH OPTIMIZATION: Limit spam messages to last 100 (most recent spam)
        // Older spam can be cleaned up by Cloud Functions
        let handle = spamRef.queryOrdered(byChild: "date")
            .queryLimited(toLast: 100)
            .observe(.value) { snapshot in
            guard snapshot.exists(),
                  let spamDict = snapshot.value as? [String: Any] else {
                DispatchQueue.main.async {
                    completion([])
                }
                return
            }

            DispatchQueue.global(qos: .userInitiated).async {
                var messages: [SpamMessage] = []

                for (key, value) in spamDict {
                    guard let data = value as? [String: Any] else { continue }
                    let address = data["address"] as? String ?? ""
                    let body = data["body"] as? String ?? ""
                    let date = data["date"] as? Double ?? 0
                    let contactName = data["contactName"] as? String
                    let spamConfidence = data["spamConfidence"] as? Double ?? 0.5
                    let spamReasons = data["spamReasons"] as? String
                    let detectedAt = data["detectedAt"] as? Double ?? 0
                    let isUserMarked = data["isUserMarked"] as? Bool ?? false
                    let isRead = data["isRead"] as? Bool ?? false

                    messages.append(
                        SpamMessage(
                            id: key,
                            address: address,
                            body: body,
                            date: date,
                            contactName: contactName,
                            spamConfidence: spamConfidence,
                            spamReasons: spamReasons,
                            detectedAt: detectedAt,
                            isUserMarked: isUserMarked,
                            isRead: isRead
                        )
                    )
                }

                messages.sort { $0.date > $1.date }
                DispatchQueue.main.async {
                    completion(messages)
                }
            }
        }

        return handle
    }

    func removeSpamMessagesListener(userId: String, handle: DatabaseHandle) {
        let spamRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_messages")
        spamRef.removeObserver(withHandle: handle)
    }

    // MARK: - Optimized Spam Messages Listener (Bandwidth Optimized)

    /// Listen to spam messages with bandwidth optimization (delta-only sync)
    /// Uses child events instead of value events to reduce bandwidth by ~95%
    ///
    /// - Parameters:
    ///   - userId: User ID
    ///   - onAdded: Called when a new spam message is added
    ///   - onChanged: Called when a spam message is updated
    ///   - onRemoved: Called when a spam message is removed
    /// - Returns: Tuple of handles for cleanup
    func listenToSpamMessagesOptimized(
        userId: String,
        onAdded: @escaping (SpamMessage) -> Void,
        onChanged: @escaping (SpamMessage) -> Void,
        onRemoved: @escaping (String) -> Void
    ) -> (added: DatabaseHandle, changed: DatabaseHandle, removed: DatabaseHandle) {
        let spamRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_messages")
            .queryOrdered(byChild: "date")
            .queryLimited(toLast: 100)

        let parseSpamMessage: (DataSnapshot) -> SpamMessage? = { snapshot in
            guard let data = snapshot.value as? [String: Any] else { return nil }
            return SpamMessage(
                id: snapshot.key,
                address: data["address"] as? String ?? "",
                body: data["body"] as? String ?? "",
                date: data["date"] as? Double ?? 0,
                contactName: data["contactName"] as? String,
                spamConfidence: data["spamConfidence"] as? Double ?? 0.5,
                spamReasons: data["spamReasons"] as? String,
                detectedAt: data["detectedAt"] as? Double ?? 0,
                isUserMarked: data["isUserMarked"] as? Bool ?? false,
                isRead: data["isRead"] as? Bool ?? false
            )
        }

        let addedHandle = spamRef.observe(.childAdded) { snapshot in
            if let msg = parseSpamMessage(snapshot) {
                DispatchQueue.main.async { onAdded(msg) }
            }
        }

        let changedHandle = spamRef.observe(.childChanged) { snapshot in
            if let msg = parseSpamMessage(snapshot) {
                DispatchQueue.main.async { onChanged(msg) }
            }
        }

        let removedHandle = spamRef.observe(.childRemoved) { snapshot in
            DispatchQueue.main.async { onRemoved(snapshot.key) }
        }

        return (addedHandle, changedHandle, removedHandle)
    }

    func removeSpamMessagesOptimizedListeners(userId: String, handles: (added: DatabaseHandle, changed: DatabaseHandle, removed: DatabaseHandle)) {
        let spamRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_messages")
        spamRef.removeObserver(withHandle: handles.added)
        spamRef.removeObserver(withHandle: handles.changed)
        spamRef.removeObserver(withHandle: handles.removed)
    }

    func deleteSpamMessage(userId: String, messageId: String) async throws {
        ensureOnline()
        let spamRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_messages")
            .child(messageId)
        try await spamRef.removeValue()
    }

    func clearAllSpamMessages(userId: String) async throws {
        ensureOnline()
        let spamRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_messages")
        try await spamRef.removeValue()
    }

    func markMessageAsSpam(userId: String, message: Message) async throws {
        ensureOnline()
        let messageId = Int64(message.id) ?? Int64(message.date)
        let spamRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_messages")
            .child(String(messageId))

        let payload: [String: Any] = [
            "messageId": messageId,
            "address": message.address,
            "body": message.body,
            "date": message.date,
            "contactName": message.contactName as Any,
            "spamConfidence": 1.0,
            "spamReasons": "Marked by user",
            "detectedAt": ServerValue.timestamp(),
            "isUserMarked": true,
            "isRead": true,
            "originalMessageId": message.id
        ]

        try await spamRef.setValue(payload)

        // Also add to blocklist for future detection
        try await addToBlocklist(userId: userId, address: message.address)
    }

    // MARK: - Spam Whitelist/Blocklist

    /// Add an address to the whitelist (marked as "not spam")
    func addToWhitelist(userId: String, address: String) async throws {
        ensureOnline()
        let whitelistRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_whitelist")

        // Get current whitelist
        let snapshot = try await whitelistRef.getData()
        var addresses = (snapshot.value as? [String]) ?? []

        // Normalize and add if not present
        let normalized = normalizeAddress(address)
        if !addresses.contains(normalized) {
            addresses.append(normalized)
            try await whitelistRef.setValue(addresses)
        }

        // Also remove from blocklist
        try await removeFromBlocklist(userId: userId, address: address)
    }

    /// Remove an address from the whitelist
    func removeFromWhitelist(userId: String, address: String) async throws {
        ensureOnline()
        let whitelistRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_whitelist")

        let snapshot = try await whitelistRef.getData()
        var addresses = (snapshot.value as? [String]) ?? []

        let normalized = normalizeAddress(address)
        addresses.removeAll { $0 == normalized }
        try await whitelistRef.setValue(addresses)
    }

    /// Add an address to the blocklist (always spam)
    func addToBlocklist(userId: String, address: String) async throws {
        ensureOnline()
        let blocklistRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_blocklist")

        let snapshot = try await blocklistRef.getData()
        var addresses = (snapshot.value as? [String]) ?? []

        let normalized = normalizeAddress(address)
        if !addresses.contains(normalized) {
            addresses.append(normalized)
            try await blocklistRef.setValue(addresses)
        }

        // Also remove from whitelist
        try await removeFromWhitelist(userId: userId, address: address)
    }

    /// Remove an address from the blocklist
    func removeFromBlocklist(userId: String, address: String) async throws {
        ensureOnline()
        let blocklistRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_blocklist")

        let snapshot = try await blocklistRef.getData()
        var addresses = (snapshot.value as? [String]) ?? []

        let normalized = normalizeAddress(address)
        addresses.removeAll { $0 == normalized }
        try await blocklistRef.setValue(addresses)
    }

    /// Normalize address for consistent matching
    private func normalizeAddress(_ address: String) -> String {
        // Keep last 10 digits for phone numbers
        let digits = address.filter { $0.isNumber || $0 == "+" }
        if digits.count >= 10 {
            return String(digits.suffix(10))
        }
        return address.lowercased().trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Send Message

    /// Sends an SMS message through the paired Android device.
    ///
    /// The message is queued in Firebase and picked up by the Android app,
    /// which then sends it via the device's native SMS capability.
    ///
    /// - Parameters:
    ///   - userId: The Firebase user ID
    ///   - address: The recipient's phone number
    ///   - body: The message text content
    /// - Throws: Error if message queueing fails
    ///
    /// ## Encryption
    /// If E2EE is initialized, the message body is encrypted before transmission.
    /// The Android app decrypts it before sending via SMS.
    ///
    /// ## Delivery Flow
    /// 1. Message queued to `outgoing_messages` in Firebase
    /// 2. Android app receives the queued message
    /// 3. Android sends SMS via native API
    /// 4. Message is deleted from queue (or marked failed)
    /// 5. Sent message appears in `messages` via sync
    func sendMessage(userId: String, to address: String, body: String) async throws {
        ensureOnline()

        let outgoingRef = database.reference()
            .child("users")
            .child(userId)
            .child("outgoing_messages")
            .childByAutoId()

        // Encrypt message body if E2EE is initialized
        var messageBody = body
        var isEncrypted = false

        if E2EEManager.shared.isInitialized && isE2eeEnabled() {
            do {
                messageBody = try await E2EEManager.shared.encryptMessage(body, for: userId)
                isEncrypted = true
                print("[Firebase] Message encrypted for sending")
            } catch {
                print("[Firebase] Failed to encrypt message, sending plaintext: \(error)")
            }
        }

        try await outgoingRef.setValue([
            "address": address,
            "body": messageBody,
            "timestamp": ServerValue.timestamp(),
            "status": "pending",
            "encrypted": isEncrypted
        ])
    }

    // MARK: - Send MMS Message

    /// Sends an MMS message with attachment through the paired Android device.
    ///
    /// Attachments are uploaded to Cloudflare R2 storage (or sent inline for small files),
    /// then the Android app downloads and sends via MMS.
    ///
    /// - Parameters:
    ///   - userId: The Firebase user ID
    ///   - address: The recipient's phone number
    ///   - body: Optional message text content
    ///   - attachmentData: Raw data of the attachment (image, video, etc.)
    ///   - fileName: Display name for the attachment
    ///   - contentType: MIME type (e.g., "image/jpeg", "video/mp4")
    ///   - attachmentType: Category ("image", "video", "audio", "file")
    /// - Throws: FirebaseError.quotaExceeded if storage limits reached
    ///
    /// ## Storage Strategy
    /// - Files > 500KB: Uploaded to R2 cloud storage via presigned URL
    /// - Files < 500KB: Fallback to inline base64 if R2 upload fails
    ///
    /// ## Usage Tracking
    /// MMS uploads count toward the user's storage quota (free vs paid tier).
    func sendMmsMessage(
        userId: String,
        to address: String,
        body: String,
        attachmentData: Data,
        fileName: String,
        contentType: String,
        attachmentType: String
    ) async throws {
        ensureOnline()

        // Generate unique attachment ID
        let attachmentId = UUID().uuidString

        print("[Firebase] Preparing MMS upload to R2: \(fileName)")

        // Encrypt attachment data if E2EE is available
        var dataToUpload = attachmentData
        var isEncrypted = false

        if E2EEManager.shared.isInitialized && isE2eeEnabled() {
            do {
                dataToUpload = try await E2EEManager.shared.encryptData(attachmentData, for: userId)
                isEncrypted = true
                print("[Firebase] MMS attachment encrypted for sending")
            } catch {
                if case E2EEError.recipientKeyNotFound = error {
                    print("[Firebase] No recipient key available, sending attachment unencrypted")
                } else {
                    print("[Firebase] Failed to encrypt attachment, sending unencrypted: \(error)")
                }
            }
        }

        let usageDecision = await UsageTracker.shared.isUploadAllowed(
            userId: userId,
            bytes: Int64(dataToUpload.count),
            countsTowardStorage: true
        )
        if !usageDecision.allowed {
            throw FirebaseError.quotaExceeded(reason: usageDecision.reason ?? "quota")
        }

        // Upload to R2 via Cloud Functions
        var r2Key: String? = nil
        var useInlineData = false

        do {
            print("[Firebase] Getting R2 upload URL...")

            // Step 1: Get presigned upload URL from R2
            let uploadUrlResult = try await functions.httpsCallable("getR2UploadUrl").call([
                "fileName": fileName,
                "contentType": contentType,
                "fileSize": dataToUpload.count,
                "transferType": "mms",
                "messageId": attachmentId
            ])

            guard let response = uploadUrlResult.data as? [String: Any],
                  let uploadUrl = response["uploadUrl"] as? String,
                  let returnedR2Key = response["r2Key"] as? String,
                  let fileId = response["fileId"] as? String else {
                throw FirebaseError.r2Error("Failed to get R2 upload URL")
            }

            print("[Firebase] Got R2 upload URL, uploading \(dataToUpload.count) bytes...")

            // Step 2: Upload directly to R2 via presigned URL
            try await uploadToR2(url: uploadUrl, data: dataToUpload, contentType: contentType)

            print("[Firebase] R2 upload completed, confirming...")

            // Step 3: Confirm upload
            _ = try await functions.httpsCallable("confirmR2Upload").call([
                "fileId": fileId,
                "r2Key": returnedR2Key,
                "fileName": fileName,
                "fileSize": dataToUpload.count,
                "contentType": contentType,
                "transferType": "mms"
            ])

            r2Key = returnedR2Key
            print("[Firebase] R2 upload confirmed: \(returnedR2Key)")

        } catch {
            print("[Firebase] R2 upload failed: \(error)")

            // For small files, fall back to inline base64 encoding
            if dataToUpload.count < 500_000 {
                print("[Firebase] Using inline base64 for small file (\(dataToUpload.count) bytes)")
                useInlineData = true
            } else {
                throw error
            }
        }

        // Create the attachment metadata
        var attachment: [String: Any] = [
            "id": attachmentId,
            "contentType": contentType,
            "fileName": fileName,
            "type": attachmentType,
            "encrypted": isEncrypted,
            "size": attachmentData.count
        ]

        if let key = r2Key {
            attachment["r2Key"] = key
        } else if useInlineData {
            // For small files when upload fails, include inline data
            attachment["inlineData"] = dataToUpload.base64EncodedString()
            attachment["isInline"] = true
        }

        let countsTowardStorage = r2Key != nil
        await UsageTracker.shared.recordUpload(
            userId: userId,
            bytes: Int64(dataToUpload.count),
            category: .mms,
            countsTowardStorage: countsTowardStorage
        )

        // Encrypt message body if E2EE is available
        var messageBody = body
        var bodyEncrypted = false

        if E2EEManager.shared.isInitialized && isE2eeEnabled() && !body.isEmpty {
            do {
                messageBody = try await E2EEManager.shared.encryptMessage(body, for: userId)
                bodyEncrypted = true
                print("[Firebase] MMS body encrypted for sending")
            } catch {
                print("[Firebase] Failed to encrypt MMS body, sending plaintext: \(error)")
            }
        }

        // Create outgoing MMS message
        let outgoingRef = database.reference()
            .child("users")
            .child(userId)
            .child("outgoing_messages")
            .childByAutoId()

        try await outgoingRef.setValue([
            "address": address,
            "body": messageBody,
            "timestamp": ServerValue.timestamp(),
            "status": "pending",
            "encrypted": bodyEncrypted,
            "isMms": true,
            "attachments": [attachment]
        ])

        print("[Firebase] MMS message sent with attachment: \(fileName)")
    }

    /// Upload data directly to R2 via presigned URL
    private func uploadToR2(url: String, data: Data, contentType: String) async throws {
        guard let uploadURL = URL(string: url) else {
            throw FirebaseError.r2Error("Invalid R2 upload URL")
        }

        var request = URLRequest(url: uploadURL)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.setValue("\(data.count)", forHTTPHeaderField: "Content-Length")
        request.timeoutInterval = 60

        let (_, response) = try await URLSession.shared.upload(for: request, from: data)

        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode) else {
            let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
            throw FirebaseError.r2Error("R2 upload failed with status: \(statusCode)")
        }
    }

    // MARK: - Delivery Tracking

    enum DeliveryStatus: String {
        case pending
        case sending
        case sent
        case failed
        case delivered
    }

    struct DeliveryResult {
        let messageId: String
        let status: DeliveryStatus
        let error: String?
    }

    /// Default delivery timeout in seconds
    private let deliveryTimeoutSeconds: TimeInterval = 60

    /// Send message and return message key for tracking
    func sendMessageWithKey(userId: String, to address: String, body: String) async throws -> String? {
        ensureOnline()

        let outgoingRef = database.reference()
            .child("users")
            .child(userId)
            .child("outgoing_messages")
            .childByAutoId()

        // Encrypt message body if E2EE is initialized
        var messageBody = body
        var isEncrypted = false

        if E2EEManager.shared.isInitialized && isE2eeEnabled() {
            do {
                messageBody = try await E2EEManager.shared.encryptMessage(body, for: userId)
                isEncrypted = true
                print("[Firebase] Message encrypted for sending")
            } catch {
                print("[Firebase] Failed to encrypt message, sending plaintext: \(error)")
            }
        }

        try await outgoingRef.setValue([
            "address": address,
            "body": messageBody,
            "timestamp": ServerValue.timestamp(),
            "status": "pending",
            "encrypted": isEncrypted,
            "createdAt": Date().timeIntervalSince1970 * 1000
        ])

        return outgoingRef.key
    }

    /// Wait for message delivery with timeout
    func waitForDelivery(userId: String, messageId: String, timeout: TimeInterval? = nil) async -> DeliveryResult {
        let timeoutDuration = timeout ?? deliveryTimeoutSeconds
        let messageRef = database.reference()
            .child("users")
            .child(userId)
            .child("outgoing_messages")
            .child(messageId)

        return await withCheckedContinuation { continuation in
            var handle: DatabaseHandle?
            var resumed = false
            var timeoutTask: Task<Void, Never>?

            // Set up timeout
            timeoutTask = Task {
                try? await Task.sleep(nanoseconds: UInt64(timeoutDuration * 1_000_000_000))
                guard !resumed else { return }
                resumed = true

                if let h = handle {
                    messageRef.removeObserver(withHandle: h)
                }

                continuation.resume(returning: DeliveryResult(
                    messageId: messageId,
                    status: .failed,
                    error: "Delivery timeout - phone may be offline or not running the app"
                ))
            }

            // Listen for status changes
            handle = messageRef.observe(.value) { snapshot in
                guard !resumed else { return }

                if !snapshot.exists() {
                    // Message was deleted - means it was processed successfully
                    resumed = true
                    timeoutTask?.cancel()
                    if let h = handle {
                        messageRef.removeObserver(withHandle: h)
                    }
                    continuation.resume(returning: DeliveryResult(
                        messageId: messageId,
                        status: .sent,
                        error: nil
                    ))
                    return
                }

                guard let data = snapshot.value as? [String: Any],
                      let statusString = data["status"] as? String else {
                    return
                }

                let status = DeliveryStatus(rawValue: statusString) ?? .pending

                switch status {
                case .sent, .delivered:
                    resumed = true
                    timeoutTask?.cancel()
                    if let h = handle {
                        messageRef.removeObserver(withHandle: h)
                    }
                    continuation.resume(returning: DeliveryResult(
                        messageId: messageId,
                        status: status,
                        error: nil
                    ))

                case .failed:
                    resumed = true
                    timeoutTask?.cancel()
                    if let h = handle {
                        messageRef.removeObserver(withHandle: h)
                    }
                    let errorMsg = data["error"] as? String ?? "Message delivery failed"
                    continuation.resume(returning: DeliveryResult(
                        messageId: messageId,
                        status: .failed,
                        error: errorMsg
                    ))

                case .pending, .sending:
                    // Keep waiting
                    break
                }
            }
        }
    }

    /// Send message with full delivery tracking
    func sendMessageWithDeliveryTracking(
        userId: String,
        to address: String,
        body: String,
        onStatusChange: ((DeliveryStatus) -> Void)? = nil
    ) async throws -> DeliveryResult {
        guard let messageId = try await sendMessageWithKey(userId: userId, to: address, body: body) else {
            return DeliveryResult(messageId: "", status: .failed, error: "Failed to queue message")
        }

        onStatusChange?(.pending)

        let result = await waitForDelivery(userId: userId, messageId: messageId)
        onStatusChange?(result.status)

        return result
    }

    // MARK: - Call Functionality

    /// Request Android to make a phone call with optional SIM selection
    func requestCall(userId: String, to phoneNumber: String, contactName: String? = nil, simSubscriptionId: Int? = nil) async throws {
        ensureOnline()

        let callRequestRef = database.reference()
            .child("users")
            .child(userId)
            .child("call_requests")
            .childByAutoId()

        var requestData: [String: Any] = [
            "phoneNumber": phoneNumber,
            "requestedAt": ServerValue.timestamp(),
            "status": "pending"
        ]

        if let name = contactName {
            requestData["contactName"] = name
        }

        if let simId = simSubscriptionId {
            requestData["simSubscriptionId"] = simId
        }

        try await callRequestRef.setValue(requestData)
    }

    /// Get available SIM cards from Android device
    func getAvailableSims(userId: String) async throws -> [SimInfo] {
        let simsRef = database.reference()
            .child("users")
            .child(userId)
            .child("sims")

        let snapshot = try await simsRef.getData()

        guard snapshot.exists(),
              let simsArray = snapshot.value as? [[String: Any]] else {
            return []
        }

        return simsArray.compactMap { simData in
            guard let subscriptionId = simData["subscriptionId"] as? Int,
                  let displayName = simData["displayName"] as? String,
                  let carrierName = simData["carrierName"] as? String else {
                return nil
            }

            return SimInfo(
                subscriptionId: subscriptionId,
                slotIndex: simData["slotIndex"] as? Int ?? 0,
                displayName: displayName,
                carrierName: carrierName,
                phoneNumber: simData["phoneNumber"] as? String,
                isEmbedded: simData["isEmbedded"] as? Bool ?? false,
                isActive: simData["isActive"] as? Bool ?? true
            )
        }
    }

    func watchCurrentDeviceStatus(userId: String, callback: @escaping (Bool) -> Void) -> (DatabaseReference, DatabaseHandle) {
        let deviceId = getDeviceId()
        let deviceRef = database.reference()
            .child("users")
            .child(userId)
            .child("devices")
            .child(deviceId)

        let handle = deviceRef.observe(.value) { snapshot in
            let data = snapshot.value as? [String: Any]
            let isPaired = data?["isPaired"] as? Bool ?? snapshot.exists()
            DispatchQueue.main.async {
                callback(isPaired)
            }
        }

        return (deviceRef, handle)
    }

    /// Listen for call status updates
    func observeCallRequest(requestId: String, userId: String, completion: @escaping (CallRequestStatus) -> Void) -> DatabaseHandle {
        let requestRef = database.reference()
            .child("users")
            .child(userId)
            .child("call_requests")
            .child(requestId)

        let handle = requestRef.observe(.value) { snapshot in
            guard snapshot.exists(),
                  let data = snapshot.value as? [String: Any],
                  let status = data["status"] as? String else {
                completion(.failed(error: "Request not found"))
                return
            }

            switch status {
            case "pending":
                completion(.pending)
            case "calling":
                completion(.calling)
            case "completed":
                completion(.completed)
            case "failed":
                let error = data["error"] as? String ?? "Unknown error"
                completion(.failed(error: error))
            default:
                completion(.failed(error: "Unknown status: \(status)"))
            }
        }

        return handle
    }

    /// Stop observing a call request
    func removeCallRequestObserver(requestId: String, userId: String, handle: DatabaseHandle) {
        let requestRef = database.reference()
            .child("users")
            .child(userId)
            .child("call_requests")
            .child(requestId)

        requestRef.removeObserver(withHandle: handle)
    }

    // MARK: - Contacts

    /// Get all contacts from Firebase
    func getContacts(userId: String) async throws -> [Contact] {
        let contactsRef = database.reference()
            .child("users")
            .child(userId)
            .child("contacts")

        let snapshot = try await contactsRef.getData()

        guard snapshot.exists(),
              let contactsDict = snapshot.value as? [String: [String: Any]] else {
            return []
        }

        var contacts: [Contact] = []

        for (contactId, contactData) in contactsDict {
            if let contact = Contact.from(contactData, id: contactId) {
                contacts.append(contact)
            }
        }

        // Sort by display name
        contacts.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }

        return contacts
    }

    /// Listen for contacts changes
    func listenToContacts(userId: String, completion: @escaping ([Contact]) -> Void) -> DatabaseHandle {
        let contactsRef = database.reference()
            .child("users")
            .child(userId)
            .child("contacts")

        let handle = contactsRef.observe(.value) { snapshot in
            guard snapshot.exists() else {
                DispatchQueue.main.async { completion([]) }
                return
            }

            guard let contactsDict = snapshot.value as? [String: [String: Any]] else {
                print("[Firebase] Failed to parse contacts as dictionary. Raw value type: \(type(of: snapshot.value))")
                DispatchQueue.main.async { completion([]) }
                return
            }

            // Process contacts on background thread to avoid blocking UI
            DispatchQueue.global(qos: .userInitiated).async {
                var contacts: [Contact] = []
                contacts.reserveCapacity(contactsDict.count)
                var parseFailCount = 0

                for (contactId, contactData) in contactsDict {
                    if let contact = Contact.from(contactData, id: contactId) {
                        contacts.append(contact)
                    } else {
                        parseFailCount += 1
                    }
                }

                if parseFailCount > 0 {
                    print("[Firebase] Parsed \(contacts.count) contacts, \(parseFailCount) failed")
                }

                // Sort by display name
                contacts.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }

                DispatchQueue.main.async {
                    completion(contacts)
                }
            }
        }

        return handle
    }

    /// Remove contacts listener
    func removeContactsListener(userId: String, handle: DatabaseHandle) {
        let contactsRef = database.reference()
            .child("users")
            .child(userId)
            .child("contacts")

        contactsRef.removeObserver(withHandle: handle)
    }

    // MARK: - Optimized Contacts Listener (Bandwidth Optimized)

    /// Listen to contacts with bandwidth optimization (delta-only sync)
    /// Uses child events instead of value events to reduce bandwidth by ~95%
    ///
    /// - Parameters:
    ///   - userId: User ID
    ///   - onAdded: Called when a new contact is added
    ///   - onChanged: Called when a contact is updated
    ///   - onRemoved: Called when a contact is removed
    /// - Returns: Tuple of handles for cleanup
    func listenToContactsOptimized(
        userId: String,
        onAdded: @escaping (Contact) -> Void,
        onChanged: @escaping (Contact) -> Void,
        onRemoved: @escaping (String) -> Void
    ) -> (added: DatabaseHandle, changed: DatabaseHandle, removed: DatabaseHandle) {
        let contactsRef = database.reference()
            .child("users")
            .child(userId)
            .child("contacts")

        let addedHandle = contactsRef.observe(.childAdded) { snapshot in
            guard let data = snapshot.value as? [String: Any],
                  let contact = Contact.from(data, id: snapshot.key) else { return }
            DispatchQueue.main.async { onAdded(contact) }
        }

        let changedHandle = contactsRef.observe(.childChanged) { snapshot in
            guard let data = snapshot.value as? [String: Any],
                  let contact = Contact.from(data, id: snapshot.key) else { return }
            DispatchQueue.main.async { onChanged(contact) }
        }

        let removedHandle = contactsRef.observe(.childRemoved) { snapshot in
            DispatchQueue.main.async { onRemoved(snapshot.key) }
        }

        return (addedHandle, changedHandle, removedHandle)
    }

    func removeContactsOptimizedListeners(userId: String, handles: (added: DatabaseHandle, changed: DatabaseHandle, removed: DatabaseHandle)) {
        let contactsRef = database.reference()
            .child("users")
            .child(userId)
            .child("contacts")
        contactsRef.removeObserver(withHandle: handles.added)
        contactsRef.removeObserver(withHandle: handles.changed)
        contactsRef.removeObserver(withHandle: handles.removed)
    }

    // MARK: - Contact Management (macOS / Web edits)

    /// Create or overwrite a contact entry on behalf of the macOS client.
    func createContact(
        userId: String,
        displayName: String,
        phoneNumber: String,
        phoneType: String = "Mobile",
        email: String? = nil,
        notes: String? = nil,
        photoBase64: String? = nil,
        source: String = "macos"
    ) async throws -> String {
        ensureOnline()
        let contactId = PhoneNumberNormalizer.shared.getDeduplicationKey(phoneNumber: phoneNumber, displayName: displayName)
        let contactRef = database.reference()
            .child("users")
            .child(userId)
            .child("contacts")
            .child(contactId)

        let payload = buildContactPayload(
            existingData: nil,
            displayName: displayName,
            phoneNumber: phoneNumber,
            phoneType: phoneType,
            email: email,
            notes: notes,
            photoBase64: photoBase64,
            source: source
        )

        try await contactRef.setValue(payload)
        print("[Firebase] Contact created from \(source): \(displayName) (\(contactId))")
        return contactId
    }

    /// Update an existing contact entry (from macOS/web)
    func updateContact(
        userId: String,
        contactId: String,
        displayName: String,
        phoneNumber: String,
        phoneType: String = "Mobile",
        email: String? = nil,
        notes: String? = nil,
        photoBase64: String? = nil,
        source: String = "macos"
    ) async throws {
        ensureOnline()
        let contactRef = database.reference()
            .child("users")
            .child(userId)
            .child("contacts")
            .child(contactId)

        let snapshot = try await contactRef.getData()
        let existingData = snapshot.value as? [String: Any]

        let payload = buildContactPayload(
            existingData: existingData,
            displayName: displayName,
            phoneNumber: phoneNumber,
            phoneType: phoneType,
            email: email,
            notes: notes,
            photoBase64: photoBase64,
            source: source
        )

        try await contactRef.setValue(payload)
        print("[Firebase] Contact updated from \(source): \(displayName) (\(contactId))")
    }

    /// Delete a contact from the universal contacts list
    func deleteContact(userId: String, contactId: String) async throws {
        ensureOnline()
        let contactRef = database.reference()
            .child("users")
            .child(userId)
            .child("contacts")
            .child(contactId)

        try await contactRef.removeValue()
    }

    private func buildContactPayload(
        existingData: [String: Any]?,
        displayName: String,
        phoneNumber: String,
        phoneType: String,
        email: String?,
        notes: String?,
        photoBase64: String?,
        source: String
    ) -> [String: Any] {
        var payload = existingData ?? [:]
        payload["displayName"] = displayName

        if let notes = notes, !notes.isEmpty {
            payload["notes"] = notes
        } else {
            payload.removeValue(forKey: "notes")
        }

        if let email = email, !email.isEmpty {
            let normalizedEmail = email.lowercased()
            payload["emails"] = [
                normalizedEmail: [
                    "address": email,
                    "type": "primary",
                    "isPrimary": true
                ]
            ]
        } else if payload["emails"] != nil {
            payload.removeValue(forKey: "emails")
        }

        let normalizedNumber = PhoneNumberNormalizer.shared.normalize(phoneNumber)
        let phoneKey = normalizedNumber.isEmpty ? phoneNumber : normalizedNumber
        payload["phoneNumbers"] = [
            phoneKey: [
                "number": phoneNumber,
                "normalizedNumber": normalizedNumber,
                "type": phoneType,
                "label": phoneType,
                "isPrimary": true
            ]
        ]

        var photoMap = payload["photo"] as? [String: Any] ?? [:]
        if let photoBase64 = photoBase64 {
            if photoBase64.isEmpty {
                photoMap.removeValue(forKey: "thumbnailBase64")
                photoMap.removeValue(forKey: "hash")
            } else {
                photoMap["thumbnailBase64"] = photoBase64
                photoMap["hash"] = sha1(photoBase64)
            }
            photoMap["updatedAt"] = ServerValue.timestamp()
        }
        payload["photo"] = photoMap

        var sources = payload["sources"] as? [String: Bool] ?? [:]
        if sources["android"] == nil {
            sources["android"] = false
        }
        sources[source] = true
        payload["sources"] = sources

        let existingSync = payload["sync"] as? [String: Any]
        let existingVersion = (existingSync?["version"] as? Int) ?? Int((existingSync?["version"] as? Double) ?? 0)
        let version = existingVersion + 1
        var sync: [String: Any] = [
            "lastUpdatedAt": ServerValue.timestamp(),
            "lastUpdatedBy": source,
            "version": version,
            "pendingAndroidSync": true,
            "desktopOnly": true
        ]
        if let lastSyncedAt = existingSync?["lastSyncedAt"] {
            sync["lastSyncedAt"] = lastSyncedAt
        }
        payload["sync"] = sync

        return payload
    }

    private func sha1(_ string: String) -> String {
        let digest = Insecure.SHA1.hash(data: Data(string.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Call History

    /// Listen for call history changes
    /// @deprecated Use listenToCallHistoryOptimized() for better bandwidth efficiency
    func listenToCallHistory(userId: String, limit: Int = 500, completion: @escaping ([CallHistoryEntry]) -> Void) -> DatabaseHandle {
        // BANDWIDTH WARNING: This downloads ALL call history on every change
        // Use listenToCallHistoryOptimized() instead for delta-only sync
        print("[Firebase] WARNING: Using non-optimized call history listener - consider using listenToCallHistoryOptimized()")

        let callHistoryRef = database.reference()
            .child("users")
            .child(userId)
            .child("call_history")

        print("[Firebase] Starting call history listener for user: \(userId), limit: \(limit)")

        // Limit call history to most recent entries to prevent memory issues
        let query = callHistoryRef.queryOrderedByKey().queryLimited(toLast: UInt(limit))

        let handle = query.observe(.value) { snapshot in
            print("[Firebase] Call history snapshot received: exists=\(snapshot.exists()), childrenCount=\(snapshot.childrenCount)")

            guard snapshot.exists() else {
                print("[Firebase] No call history data found for user: \(userId)")
                DispatchQueue.main.async { completion([]) }
                return
            }

            guard let callsDict = snapshot.value as? [String: [String: Any]] else {
                print("[Firebase] Failed to parse call history as dictionary. Raw value type: \(type(of: snapshot.value))")
                DispatchQueue.main.async { completion([]) }
                return
            }

            // Process call history on background thread to avoid blocking UI
            DispatchQueue.global(qos: .userInitiated).async {
                var calls: [CallHistoryEntry] = []
                calls.reserveCapacity(callsDict.count)
                var parseFailCount = 0
                var sampleFailure: (String, [String])? = nil

                for (callId, callData) in callsDict {
                    if let call = CallHistoryEntry.from(callData, id: callId) {
                        calls.append(call)
                    } else {
                        parseFailCount += 1
                        // Only capture one sample for debugging
                        if sampleFailure == nil {
                            sampleFailure = (callId, callData.keys.sorted())
                        }
                    }
                }

                if parseFailCount > 0, let sample = sampleFailure {
                    print("[Firebase] Parsed \(calls.count) call history entries, \(parseFailCount) failed. Sample: \(sample.0), keys: \(sample.1)")
                } else {
                    print("[Firebase] Parsed \(calls.count) call history entries")
                }

                // Sort by date (newest first)
                calls.sort { $0.callDate > $1.callDate }

                DispatchQueue.main.async {
                    completion(calls)
                }
            }
        }

        return handle
    }

    // MARK: - Optimized Call History Listener (Bandwidth Optimized)

    /// Listen to call history with bandwidth optimization (delta-only sync)
    /// Uses child events instead of value events to reduce bandwidth by ~95%
    ///
    /// - Parameters:
    ///   - userId: User ID
    ///   - limit: Maximum number of call history entries to track (default: 200)
    ///   - onAdded: Called when a new call is added
    ///   - onChanged: Called when a call is updated
    ///   - onRemoved: Called when a call is removed
    /// - Returns: Tuple of handles for cleanup
    func listenToCallHistoryOptimized(
        userId: String,
        limit: Int = 200,
        onAdded: @escaping (CallHistoryEntry) -> Void,
        onChanged: @escaping (CallHistoryEntry) -> Void,
        onRemoved: @escaping (String) -> Void
    ) -> (added: DatabaseHandle, changed: DatabaseHandle, removed: DatabaseHandle) {
        let callHistoryRef = database.reference()
            .child("users")
            .child(userId)
            .child("call_history")
            .queryOrderedByKey()
            .queryLimited(toLast: UInt(limit))

        print("[Firebase] Starting OPTIMIZED call history listener for user: \(userId), limit: \(limit)")

        let parseCallHistory: (DataSnapshot) -> CallHistoryEntry? = { snapshot in
            guard let data = snapshot.value as? [String: Any] else { return nil }
            return CallHistoryEntry.from(data, id: snapshot.key)
        }

        let addedHandle = callHistoryRef.observe(.childAdded) { snapshot in
            if let call = parseCallHistory(snapshot) {
                DispatchQueue.main.async { onAdded(call) }
            }
        }

        let changedHandle = callHistoryRef.observe(.childChanged) { snapshot in
            if let call = parseCallHistory(snapshot) {
                DispatchQueue.main.async { onChanged(call) }
            }
        }

        let removedHandle = callHistoryRef.observe(.childRemoved) { snapshot in
            DispatchQueue.main.async { onRemoved(snapshot.key) }
        }

        return (addedHandle, changedHandle, removedHandle)
    }

    func removeCallHistoryOptimizedListeners(userId: String, handles: (added: DatabaseHandle, changed: DatabaseHandle, removed: DatabaseHandle)) {
        let callHistoryRef = database.reference()
            .child("users")
            .child(userId)
            .child("call_history")
        callHistoryRef.removeObserver(withHandle: handles.added)
        callHistoryRef.removeObserver(withHandle: handles.changed)
        callHistoryRef.removeObserver(withHandle: handles.removed)
        print("[Firebase] Removed OPTIMIZED call history listeners")
    }

    /// Listen for active/incoming calls
    func listenToActiveCalls(userId: String, completion: @escaping ([ActiveCall]) -> Void) -> DatabaseHandle {
        let activeCallsRef = database.reference()
            .child("users")
            .child(userId)
            .child("active_calls")

        let handle = activeCallsRef.observe(.value) { snapshot in
            guard snapshot.exists(),
                  let callsDict = snapshot.value as? [String: [String: Any]] else {
                completion([])
                return
            }

            var calls: [ActiveCall] = []
            let currentTime = Date().timeIntervalSince1970 * 1000 // Convert to milliseconds
            let maxAge: Double = 30000 // 30 seconds - calls older than this are stale

            for (callId, callData) in callsDict {
                // Check if call is stale (older than 30 seconds)
                if let timestamp = callData["timestamp"] as? Double {
                    let age = currentTime - timestamp
                    if age > maxAge {
                        continue
                    }
                }

                if let call = ActiveCall.from(callData, id: callId) {
                    calls.append(call)
                }
            }

            // Sort by timestamp, most recent first
            calls.sort { $0.timestamp > $1.timestamp }
            completion(calls)
        }

        return handle
    }

    /// Remove active calls listener
    func removeActiveCallsListener(userId: String, handle: DatabaseHandle) {
        let activeCallsRef = database.reference()
            .child("users")
            .child(userId)
            .child("active_calls")

        activeCallsRef.removeObserver(withHandle: handle)
    }

    /// Send command to answer/reject call
    func sendCallCommand(userId: String, callId: String, command: String) async throws {
        ensureOnline()

        let commandRef = database.reference()
            .child("users")
            .child(userId)
            .child("call_commands")
            .childByAutoId()

        let commandData: [String: Any] = [
            "command": command,
            "callId": callId,
            "processed": false,
            "timestamp": ServerValue.timestamp()
        ]

        try await commandRef.setValue(commandData)
    }

    /// Make an outgoing call
    func makeCall(userId: String, phoneNumber: String, simSubscriptionId: Int? = nil) async throws {
        ensureOnline()

        let callRequestRef = database.reference()
            .child("users")
            .child(userId)
            .child("call_requests")
            .childByAutoId()

        var requestData: [String: Any] = [
            "phoneNumber": phoneNumber,
            "requestedAt": ServerValue.timestamp(),
            "status": "pending"
        ]

        if let simId = simSubscriptionId {
            requestData["simSubscriptionId"] = simId
        }

        try await callRequestRef.setValue(requestData)
    }

    // MARK: - SyncFlow Calls

    /// Listen for incoming SyncFlow calls
    func listenForIncomingSyncFlowCalls(userId: String, completion: @escaping (SyncFlowCall) -> Void) -> DatabaseHandle {
        let callsRef = database.reference()
            .child("users")
            .child(userId)
            .child("syncflow_calls")

        let handle = callsRef.queryOrdered(byChild: "status").queryEqual(toValue: "ringing")
            .observe(.childAdded) { snapshot in
                guard let callData = snapshot.value as? [String: Any],
                      let call = SyncFlowCall.from(id: snapshot.key, dict: callData) else {
                    return
                }
                completion(call)
            }

        return handle
    }

    // MARK: - Message Reactions

    func listenToMessageReactions(userId: String, completion: @escaping ([String: String]) -> Void) -> DatabaseHandle {
        let reactionsRef = database.reference()
            .child("users")
            .child(userId)
            .child("message_reactions")

        let handle = reactionsRef.observe(.value) { snapshot in
            guard snapshot.exists() else {
                completion([:])
                return
            }

            var reactions: [String: String] = [:]
            snapshot.children.forEach { child in
                guard let childSnapshot = child as? DataSnapshot else { return }
                let messageId = childSnapshot.key
                let reaction = (childSnapshot.childSnapshot(forPath: "reaction").value as? String)
                    ?? (childSnapshot.value as? String)
                if let reaction = reaction {
                    reactions[messageId] = reaction
                }
            }

            completion(reactions)
        }

        return handle
    }

    func removeMessageReactionsListener(userId: String, handle: DatabaseHandle) {
        let reactionsRef = database.reference()
            .child("users")
            .child(userId)
            .child("message_reactions")

        reactionsRef.removeObserver(withHandle: handle)
    }

    func setMessageReaction(userId: String, messageId: String, reaction: String?) async throws {
        ensureOnline()

        let reactionRef = database.reference()
            .child("users")
            .child(userId)
            .child("message_reactions")
            .child(messageId)

        if let reaction = reaction, !reaction.isEmpty {
            let payload: [String: Any] = [
                "reaction": reaction,
                "updatedAt": ServerValue.timestamp(),
                "updatedBy": "macos"
            ]
            try await reactionRef.setValue(payload)
        } else {
            try await reactionRef.removeValue()
        }
    }

    // MARK: - Read Receipts

    func listenToReadReceipts(userId: String, completion: @escaping ([String: ReadReceipt]) -> Void) -> DatabaseHandle {
        let receiptsRef = database.reference()
            .child("users")
            .child(userId)
            .child("read_receipts")

        let handle = receiptsRef.observe(.value) { snapshot in
            guard snapshot.exists() else {
                completion([:])
                return
            }

            var receipts: [String: ReadReceipt] = [:]
            snapshot.children.forEach { child in
                guard let childSnapshot = child as? DataSnapshot else { return }
                let key = childSnapshot.key

                let messageId = (childSnapshot.childSnapshot(forPath: "messageId").value as? String)
                    ?? (childSnapshot.childSnapshot(forPath: "messageId").value as? Int64).map { String($0) }
                    ?? key

                let readAt = (childSnapshot.childSnapshot(forPath: "readAt").value as? Double)
                    ?? (childSnapshot.childSnapshot(forPath: "readAt").value as? Int64).map { Double($0) }
                    ?? 0
                let readBy = childSnapshot.childSnapshot(forPath: "readBy").value as? String ?? "unknown"
                let readDeviceName = childSnapshot.childSnapshot(forPath: "readDeviceName").value as? String
                let conversationAddress = childSnapshot.childSnapshot(forPath: "conversationAddress").value as? String ?? ""
                let sourceId = childSnapshot.childSnapshot(forPath: "sourceId").value as? Int64
                let sourceType = childSnapshot.childSnapshot(forPath: "sourceType").value as? String

                receipts[messageId] = ReadReceipt(
                    id: messageId,
                    readAt: readAt,
                    readBy: readBy,
                    readDeviceName: readDeviceName,
                    conversationAddress: conversationAddress,
                    sourceId: sourceId,
                    sourceType: sourceType
                )
            }

            completion(receipts)
        }

        return handle
    }

    func removeReadReceiptsListener(userId: String, handle: DatabaseHandle) {
        let receiptsRef = database.reference()
            .child("users")
            .child(userId)
            .child("read_receipts")

        receiptsRef.removeObserver(withHandle: handle)
    }

    func markMessagesRead(
        userId: String,
        messageIds: [String],
        conversationAddress: String,
        readBy: String,
        readDeviceName: String?
    ) async throws {
        guard !messageIds.isEmpty else { return }
        ensureOnline()

        var updates: [String: Any] = [:]
        for messageId in messageIds {
            updates["users/\(userId)/read_receipts/\(messageId)"] = [
                "messageId": messageId,
                "readAt": ServerValue.timestamp(),
                "readBy": readBy,
                "readDeviceName": readDeviceName as Any,
                "conversationAddress": conversationAddress
            ]
        }

        try await database.reference().updateChildValues(updates)
    }

    /// Delete a message from Firebase
    func deleteMessage(userId: String, messageId: String) async throws {
        ensureOnline()

        // Delete the message from Firebase
        let messageRef = database.reference()
            .child("users")
            .child(userId)
            .child("messages")
            .child(messageId)

        try await messageRef.removeValue()

        // Also delete any reactions for this message
        let reactionRef = database.reference()
            .child("users")
            .child(userId)
            .child("message_reactions")
            .child(messageId)

        try await reactionRef.removeValue()

        print("[Firebase] Message \(messageId) deleted successfully")
    }

    /// Delete multiple messages from Firebase (and related metadata)
    func deleteMessages(userId: String, messageIds: [String]) async throws {
        guard !messageIds.isEmpty else { return }
        ensureOnline()

        var updates: [String: Any] = [:]
        for messageId in messageIds {
            updates["users/\(userId)/messages/\(messageId)"] = NSNull()
            updates["users/\(userId)/message_reactions/\(messageId)"] = NSNull()
            updates["users/\(userId)/read_receipts/\(messageId)"] = NSNull()
        }

        try await database.reference().updateChildValues(updates)
        print("[Firebase] Deleted \(messageIds.count) message(s) successfully")
    }

    /// Get paired devices
    func getPairedDevices(userId: String) async throws -> [SyncFlowDevice] {
        let devicesRef = database.reference()
            .child("users")
            .child(userId)
            .child("devices")

        let snapshot = try await devicesRef.getData()
        guard let devicesData = snapshot.value as? [String: [String: Any]] else {
            return []
        }

        return devicesData.compactMap { (id, dict) in
            SyncFlowDevice.from(id: id, dict: dict)
        }.filter { $0.isAndroid && $0.online }
    }

    // MARK: - E2EE Key Recovery

    /// Check for E2EE key mismatch with the shared sync group keypair.
    /// Returns (mismatchDetected, message).
    ///
    /// NEW BEHAVIOR (v3 - Shared Sync Group Keypair):
    /// All devices in a sync group share the same keypair (generated by Android).
    /// This function checks if the local macOS keypair matches the shared sync group keypair in Firebase.
    func checkE2eeKeyStatus(userId: String, deviceId: String) async -> (Bool, String?) {
        try? await E2EEManager.shared.initializeKeys()
        guard let localPublicKeyX963 = E2EEManager.shared.getMyPublicKeyX963Base64() else {
            return (false, nil)
        }

        var mismatchDetected = false
        var message: String?

        // Check against shared sync group keypair (NEW v3 architecture)
        do {
            let syncGroupKeypairRef = database.reference()
                .child("users")
                .child(userId)
                .child("syncGroupKeypair")

            let syncGroupSnapshot = try await withTimeout(5.0) {
                try await syncGroupKeypairRef.getData()
            }

            if syncGroupSnapshot.exists(),
               let keypairData = syncGroupSnapshot.value as? [String: Any],
               let sharedPublicKey = keypairData["publicKeyX963"] as? String,
               !sharedPublicKey.isEmpty {

                // Compare local key with shared sync group key
                if sharedPublicKey != localPublicKeyX963 {
                    mismatchDetected = true
                    message = "Encryption keys changed. Keep your phone online to refresh keys."
                    print("[E2EE] Key mismatch detected: local key doesn't match shared sync group key")
                } else {
                    // Keys match - all good!
                    print("[E2EE] ✅ Local keypair matches shared sync group keypair")
                }
            } else {
                // No shared sync group keypair exists yet (fresh pairing or old account)
                // This is not an error - keys will be synced during first message sync
                print("[E2EE] No shared sync group keypair found in Firebase (will be created on first sync)")
            }
        } catch {
            print("[E2EE] Failed to check sync group keypair: \(error.localizedDescription)")
            // Don't show error banner for network issues
        }

        return (mismatchDetected, message)
    }

    /// Request key sync from the paired phone for this device.
    func requestE2eeKeySync(userId: String, deviceId: String) async throws {
        guard let localPublicKeyX963 = E2EEManager.shared.getMyPublicKeyX963Base64() else {
            throw NSError(domain: "E2EE", code: 1, userInfo: [NSLocalizedDescriptionKey: "E2EE not initialized"])
        }

        let requestRef = database.reference()
            .child("users")
            .child(userId)
            .child("e2ee_key_requests")
            .child(deviceId)

        let payload: [String: Any] = [
            "requesterDeviceId": deviceId,
            "requesterPlatform": "macos",
            "requesterPublicKeyX963": localPublicKeyX963,
            "requestedAt": ServerValue.timestamp(),
            "status": "pending"
        ]

        try await requestRef.setValue(payload)
    }

    /// Request a keyMap backfill so older encrypted messages can decrypt on this device.
    func requestE2eeKeyBackfill(userId: String, deviceId: String) async throws {
        guard let localPublicKeyX963 = E2EEManager.shared.getMyPublicKeyX963Base64() else {
            throw NSError(domain: "E2EE", code: 7, userInfo: [NSLocalizedDescriptionKey: "E2EE not initialized"])
        }

        let requestRef = database.reference()
            .child("users")
            .child(userId)
            .child("e2ee_key_backfill_requests")
            .child(deviceId)

        let payload: [String: Any] = [
            "requesterDeviceId": deviceId,
            "requesterPlatform": "macos",
            "requesterPublicKeyX963": localPublicKeyX963,
            "requestedAt": ServerValue.timestamp(),
            "status": "pending"
        ]

        try await requestRef.setValue(payload)
    }

    /// Wait for key sync response, import keys, and return success.
    ///
    /// NEW BEHAVIOR (v3 - Shared Sync Group Keypair):
    /// Receives the Android device's sync group keypair and imports it, replacing the
    /// macOS device's locally generated keypair. This allows immediate decryption of
    /// all existing messages without needing backfill.
    ///
    /// OLD BEHAVIOR (v2 - Per-Device Keys):
    /// Received a re-encrypted key backup specific to this device.
    func waitForE2eeKeySyncResponse(userId: String, deviceId: String, timeout: TimeInterval = 30) async throws -> Bool {
        let responseRef = database.reference()
            .child("users")
            .child(userId)
            .child("e2ee_key_responses")
            .child(deviceId)

        return try await withCheckedThrowingContinuation { continuation in
            var resolved = false
            var handle: DatabaseHandle = 0
            handle = responseRef.observe(.value) { snapshot in
                guard snapshot.exists(),
                      let data = snapshot.value as? [String: Any],
                      let status = data["status"] as? String else {
                    return
                }

                if status == "ready",
                   let envelope = data["encryptedPrivateKeyEnvelope"] as? String {
                    do {
                        // Check key version to determine import method
                        let keyVersion = data["keyVersion"] as? Int ?? 2

                        if keyVersion >= 3,
                           let syncGroupPublicKeyX963 = data["syncGroupPublicKeyX963"] as? String {
                            // V3: Import sync group keypair (shared across all devices)
                            let encryptedPrivateKey = try E2EEManager.shared.decryptDataKey(from: envelope)
                            let privateKeyPKCS8Base64 = encryptedPrivateKey.base64EncodedString()

                            try E2EEManager.shared.importSyncGroupKeypair(
                                privateKeyPKCS8Base64: privateKeyPKCS8Base64,
                                publicKeyX963Base64: syncGroupPublicKeyX963
                            )

                            print("[E2EE] Imported sync group keypair (v3) - all messages immediately accessible")
                        } else {
                            // V2: Import device-specific key (legacy)
                            let rawKey = try E2EEManager.shared.decryptDataKey(from: envelope)
                            try E2EEManager.shared.importPrivateKey(rawData: rawKey)

                            print("[E2EE] Imported device-specific key (v2) - may need backfill")
                        }

                        Task {
                            try? await E2EEManager.shared.publishDevicePublicKey()
                        }
                        responseRef.removeObserver(withHandle: handle)
                        responseRef.removeValue()
                        resolved = true
                        continuation.resume(returning: true)
                    } catch {
                        responseRef.removeObserver(withHandle: handle)
                        responseRef.removeValue()
                        resolved = true
                        continuation.resume(throwing: error)
                    }
                } else if status == "error" {
                    let errorMessage = data["error"] as? String ?? "Key sync failed"
                    responseRef.removeObserver(withHandle: handle)
                    responseRef.removeValue()
                    resolved = true
                    continuation.resume(throwing: NSError(domain: "E2EE", code: 2, userInfo: [NSLocalizedDescriptionKey: errorMessage]))
                }
            }

            DispatchQueue.main.asyncAfter(deadline: .now() + timeout) {
                if resolved { return }
                responseRef.removeObserver(withHandle: handle)
                continuation.resume(throwing: NSError(domain: "E2EE", code: 3, userInfo: [NSLocalizedDescriptionKey: "Key sync timed out"]))
            }
        }
    }

    /// Create a key backup for this device encrypted to the paired Android device.
    private func createE2eeKeyBackup(userId: String, deviceId: String) async throws {
        guard let privateKeyRaw = E2EEManager.shared.getMyPrivateKeyRaw(),
              let publicKeyX963 = E2EEManager.shared.getMyPublicKeyX963Base64() else {
            throw NSError(domain: "E2EE", code: 4, userInfo: [NSLocalizedDescriptionKey: "E2EE not initialized"])
        }

        guard let androidDeviceId = try await getAndroidDeviceId(userId: userId) else {
            throw NSError(domain: "E2EE", code: 5, userInfo: [NSLocalizedDescriptionKey: "No Android device found"])
        }

        let androidKeyRef = database.reference()
            .child("e2ee_keys")
            .child(userId)
            .child(androidDeviceId)
            .child("publicKeyX963")
        let androidKeySnapshot = try await androidKeyRef.getData()
        guard let androidPublicKeyX963 = androidKeySnapshot.value as? String else {
            throw NSError(domain: "E2EE", code: 6, userInfo: [NSLocalizedDescriptionKey: "Android device key missing"])
        }

        let envelope = try E2EEManager.shared.encryptDataKeyForDevice(
            publicKeyX963Base64: androidPublicKeyX963,
            data: privateKeyRaw
        )

        let backupRef = database.reference()
            .child("users")
            .child(userId)
            .child("e2ee_key_backups")
            .child(deviceId)

        let payload: [String: Any] = [
            "publicKeyX963": publicKeyX963,
            "encryptedPrivateKeyEnvelope": envelope,
            "platform": "macos",
            "keyVersion": 2,
            "createdAt": ServerValue.timestamp()
        ]

        try await backupRef.setValue(payload)
    }

    private func getAndroidDeviceId(userId: String) async throws -> String? {
        let devicesRef = database.reference()
            .child("users")
            .child(userId)
            .child("devices")

        let snapshot = try await devicesRef.getData()
        guard let devicesData = snapshot.value as? [String: [String: Any]] else {
            return nil
        }

        return devicesData.compactMap { (id, dict) in
            SyncFlowDevice.from(id: id, dict: dict)
        }.first(where: { $0.isAndroid })?.id
    }

    // MARK: - Sync History Requests

    /// Request a message history sync from the paired phone.
    func requestHistorySync(userId: String, days: Int = 3650) async throws {
        let requestId = UUID().uuidString
        let requestRef = database.reference()
            .child("users")
            .child(userId)
            .child("sync_requests")
            .child(requestId)

        let deviceId = UserDefaults.standard.string(forKey: "syncflow_device_id") ?? "macos"

        let payload: [String: Any] = [
            "days": days,
            "requestedBy": "macos:\(deviceId)",
            "requestedAt": ServerValue.timestamp(),
            "status": "pending"
        ]

        try await requestRef.setValue(payload)
    }

    // MARK: - Find My Phone

    /// Ring the phone to help locate it
    func ringPhone(userId: String) async throws {
        ensureOnline()

        let findRef = database.reference()
            .child("users")
            .child(userId)
            .child("find_my_phone")
            .childByAutoId()

        let requestData: [String: Any] = [
            "action": "ring",
            "timestamp": ServerValue.timestamp(),
            "source": "macos",
            "status": "pending"
        ]

        try await findRef.setValue(requestData)
    }

    /// Stop ringing the phone
    func stopRingingPhone(userId: String) async throws {
        ensureOnline()

        let findRef = database.reference()
            .child("users")
            .child(userId)
            .child("find_my_phone")
            .childByAutoId()

        let requestData: [String: Any] = [
            "action": "stop",
            "timestamp": ServerValue.timestamp(),
            "source": "macos",
            "status": "pending"
        ]

        try await findRef.setValue(requestData)
    }

    // MARK: - Link Sharing

    /// Send a URL to the phone to open in browser
    func sendLink(userId: String, url: String, title: String? = nil) async throws {
        ensureOnline()

        let linkRef = database.reference()
            .child("users")
            .child(userId)
            .child("shared_links")
            .childByAutoId()

        var linkData: [String: Any] = [
            "url": url,
            "timestamp": ServerValue.timestamp(),
            "source": "macos",
            "status": "pending"
        ]

        if let title = title {
            linkData["title"] = title
        }

        try await linkRef.setValue(linkData)
    }
}

extension FirebaseService {
    /// Optimized message listener with batched updates for macOS
    func listenToMessagesOptimized(
        userId: String,
        completion: @escaping ([Message]) -> Void
    ) -> DatabaseHandle {
        let messagesRef = database.reference()
            .child("users")
            .child(userId)
            .child("messages")

        return PerformanceOptimizer.shared.createBatchedListener(
            for: messagesRef.queryOrdered(byChild: "date"),
            transform: { [weak self] snapshots in
                guard let self = self else { return [] }
                return self.processMessageSnapshots(snapshots)
            },
            update: completion
        )
    }

    private func processMessageSnapshots(_ snapshots: [DataSnapshot]) -> [Message] {
        var messages: [Message] = []

        for snapshot in snapshots {
            guard snapshot.exists(),
                  let messageData = snapshot.value as? [String: Any],
                  let address = messageData["address"] as? String,
                  let body = messageData["body"] as? String,
                  let date = normalizeMessageDate(messageData),
                  let type = messageData["type"] as? Int else {
                continue
            }

            if isRcsAddress(address) { continue }

            let contactName = messageData["contactName"] as? String
            let isEncrypted = messageData["encrypted"] as? Bool ?? false
            let isMms = messageData["isMms"] as? Bool ?? false

            let message = Message(
                id: snapshot.key,
                address: address,
                body: isEncrypted ? "[🔒 Encrypted message]" : body,
                date: date,
                type: type,
                contactName: contactName,
                isMms: isMms,
                attachments: nil,
                isEncrypted: isEncrypted
            )

            messages.append(message)
        }

        messages.sort { $0.date > $1.date }
        return messages
    }

    // MARK: - Sync History Requests
}

// MARK: - Pairing Session

struct PairingSession {
    let token: String
    let qrPayload: String
    let expiresAt: Double
    let version: Int  // 1 = legacy, 2 = new V2 with device limits

    init(token: String, qrPayload: String, expiresAt: Double, version: Int = 1) {
        self.token = token
        self.qrPayload = qrPayload
        self.expiresAt = expiresAt
        self.version = version
    }

    var expiresAtDate: Date {
        Date(timeIntervalSince1970: expiresAt / 1000)
    }

    var timeRemaining: TimeInterval {
        max(0, expiresAtDate.timeIntervalSinceNow)
    }

    var isExpired: Bool {
        timeRemaining <= 0
    }

    /// Firebase path where this pairing session is stored
    var databasePath: String {
        switch version {
        case 2:
            return "pairing_requests/\(token)"
        default:
            return "pending_pairings/\(token)"
        }
    }
}

// MARK: - Pairing Status

enum PairingStatus {
    case pending
    case approved(pairedUid: String, deviceId: String?)
    case rejected
    case expired
}

// MARK: - SIM Info

struct SimInfo: Identifiable, Hashable {
    let subscriptionId: Int
    let slotIndex: Int
    let displayName: String
    let carrierName: String
    let phoneNumber: String?
    let isEmbedded: Bool
    let isActive: Bool

    var id: Int { subscriptionId }

    var formattedDisplayName: String {
        var name = displayName
        if let number = phoneNumber, !number.isEmpty {
            name += " (\(number))"
        } else {
            name += " - \(carrierName)"
        }
        if isEmbedded {
            name += " [eSIM]"
        }
        return name
    }
}

// MARK: - Call Request Status

enum CallRequestStatus {
    case pending
    case calling
    case completed
    case failed(error: String)

    var description: String {
        switch self {
        case .pending:
            return "Sending request to phone..."
        case .calling:
            return "Phone is dialing..."
        case .completed:
            return "Call initiated successfully"
        case .failed(let error):
            return "Failed: \(error)"
        }
    }
}

// MARK: - Errors

enum FirebaseError: LocalizedError {
    case authFailed
    case invalidToken
    case invalidTokenData
    case tokenExpired
    case sendFailed
    case quotaExceeded(reason: String)
    case r2Error(String)

    var errorDescription: String? {
        switch self {
        case .authFailed:
            return "Failed to authenticate with Firebase"
        case .invalidToken:
            return "Invalid or expired pairing token"
        case .invalidTokenData:
            return "Invalid token data"
        case .tokenExpired:
            return "Pairing token has expired (5 minutes)"
        case .sendFailed:
            return "Failed to send message"
        case .quotaExceeded(let reason):
            switch reason {
            case "trial_expired":
                return "Free trial expired. Upgrade to keep sending MMS."
            case "monthly_quota":
                return "Monthly upload limit reached. Try again next month or upgrade."
            case "storage_quota":
                return "Storage limit reached. Free up space or upgrade your plan."
            default:
                 return "Upload limit reached. Please try again later."
             }
        case .r2Error(let message):
            return "R2 storage error: \(message)"
         }
     }
}
