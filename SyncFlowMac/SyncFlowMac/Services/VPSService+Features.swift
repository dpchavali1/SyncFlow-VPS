/**
 * VPSService+Features - E2EE keys, spam, usage, billing, files, photos,
 *                       notifications, account deletion, media, phone status,
 *                       clipboard, DND, hotspot, voicemail, find phone, links,
 *                       plan refresh timer, and E2EE key publishing
 *
 * This is a catch-all extension for the many smaller feature endpoints that
 * don't warrant their own file. Each MARK section groups related API calls.
 */

import Foundation

extension VPSService {

    // MARK: - E2EE Keys (VPS)

    public func getDeviceE2eeKeys(userId: String) async throws -> [String: VPSDeviceE2eeKey] {
        return try await get("/api/e2ee/device-keys/\(userId)")
    }

    /// Request E2EE key sync from a target device (e.g., Android)
    /// This creates a key request that notifies the target via WebSocket
    public func requestE2EEKeySync(targetDevice: String) async throws {
        let body: [String: Any] = ["targetDevice": targetDevice]
        let _: VPSSuccessResponse = try await post("/api/e2ee/key-request", body: body)
        #if DEBUG
        print("[VPS] E2EE key request sent to device: \(targetDevice)")
        #endif
    }

    /// Repair encryption: clears stale server-side E2EE keys and triggers Android
    /// to re-sync all messages encrypted with the current keys.
    public func repairEncryption() async throws {
        let _: VPSSuccessResponse = try await post("/api/e2ee/repair", body: [:])
        #if DEBUG
        print("[VPS] Encryption repair requested")
        #endif
    }

    /// Fetch encrypted key backups from the server
    public func getKeyBackups() async throws -> [[String: Any]] {
        struct BackupResponse: Decodable {
            struct Backup: Decodable {
                let encryptedBackup: String
                let salt: String
                let iterations: Int
                let keyVersion: Int
            }
            let backups: [Backup]
        }
        let response: BackupResponse = try await get("/api/e2ee/key-backup")
        return response.backups.map { backup in
            [
                "encryptedBackup": backup.encryptedBackup,
                "salt": backup.salt,
                "iterations": backup.iterations,
                "keyVersion": backup.keyVersion,
            ] as [String: Any]
        }
    }

    /// Polls VPS for this device's E2EE key until found or timeout.
    /// Uses a push-aware polling strategy: if a WebSocket push arrives during
    /// the wait, the next poll fires immediately instead of sleeping.
    public func waitForDeviceE2eeKey(timeout: TimeInterval = 60, pollInterval: TimeInterval = 3, initialDelay: TimeInterval = 1) async throws -> String? {
        guard let userId = userId, let deviceId = deviceId else {
            #if DEBUG
            print("[VPS E2EE] Cannot poll - userId=\(userId ?? "nil"), deviceId=\(self.deviceId ?? "nil")")
            #endif
            return nil
        }
        let deadline = Date().addingTimeInterval(timeout)
        e2eeKeyPushReceived = false
        #if DEBUG
        print("[VPS E2EE] Polling for key (deviceId=\(deviceId), timeout=\(Int(timeout))s)")
        #endif

        // Short initial delay, but skip if WebSocket already pushed key notification
        if initialDelay > 0 && !e2eeKeyPushReceived {
            // Check every 200ms during initial delay so we can skip early on push
            let delayEnd = Date().addingTimeInterval(initialDelay)
            while Date() < delayEnd && !e2eeKeyPushReceived {
                try await Task.sleep(nanoseconds: 200_000_000) // 200ms
            }
            #if DEBUG
            if e2eeKeyPushReceived {
                print("[VPS E2EE] Key push received during initial delay, polling immediately")
            }
            #endif
        }

        var pollCount = 0
        while Date() < deadline {
            pollCount += 1
            do {
                let keys = try await getDeviceE2eeKeys(userId: userId)
                #if DEBUG
                if pollCount == 1 || pollCount % 5 == 0 {
                    print("[VPS E2EE] Poll #\(pollCount): got \(keys.count) keys, looking for deviceId=\(deviceId)")
                    if !keys.isEmpty {
                        print("[VPS E2EE]   Available deviceIds: \(keys.keys.sorted())")
                    }
                }
                #endif
                if let key = keys[deviceId]?.encryptedKey, !key.isEmpty {
                    #if DEBUG
                    print("[VPS E2EE] Key found after \(pollCount) polls (key length=\(key.count))")
                    #endif
                    return key
                }
            } catch {
                #if DEBUG
                print("[VPS E2EE] Poll #\(pollCount) error: \(error)")
                #endif
                let errorDesc = "\(error)"
                if errorDesc.contains("429") || errorDesc.contains("Too many requests") {
                    try await Task.sleep(nanoseconds: UInt64(10 * 1_000_000_000))
                    continue
                }
                #if DEBUG
                // Log detailed error for debugging
                if let decodingError = error as? DecodingError {
                    print("[VPS E2EE] Decoding error detail: \(decodingError)")
                }
                #endif
            }
            // If push arrived, poll immediately instead of waiting
            if e2eeKeyPushReceived {
                e2eeKeyPushReceived = false
                continue
            }
            // Sleep in short increments so we wake up quickly on push
            let sleepEnd = Date().addingTimeInterval(pollInterval)
            while Date() < sleepEnd && !e2eeKeyPushReceived {
                try await Task.sleep(nanoseconds: 200_000_000) // 200ms
            }
        }

        #if DEBUG
        print("[VPS E2EE] Timed out after \(pollCount) polls")
        #endif
        return nil
    }

    // MARK: - Spam

    public func getSpamMessages(limit: Int = 100) async throws -> VPSSpamMessagesResponse {
        return try await get("/api/spam/messages?limit=\(limit)")
    }

    public func syncSpamMessage(address: String, body: String, date: Double, spamScore: Double, spamReason: String) async throws {
        let payload: [String: Any] = [
            "address": address,
            "body": body,
            "date": Int64(date),
            "spamScore": spamScore,
            "spamReason": spamReason
        ]
        let _: VPSGenericResponse = try await post("/api/spam/messages", body: payload)
    }

    public func deleteSpamMessage(messageId: String) async throws {
        let _: VPSGenericResponse = try await delete("/api/spam/messages/\(messageId)")
    }

    public func clearAllSpamMessages() async throws {
        let _: VPSGenericResponse = try await delete("/api/spam/messages")
    }

    public func getWhitelist() async throws -> VPSWhitelistResponse {
        return try await get("/api/spam/whitelist")
    }

    public func addToWhitelist(phoneNumber: String) async throws {
        let _: VPSGenericResponse = try await post("/api/spam/whitelist", body: ["phoneNumber": phoneNumber])
    }

    public func removeFromWhitelist(phoneNumber: String) async throws {
        let _: VPSGenericResponse = try await delete("/api/spam/whitelist/\(phoneNumber)")
    }

    public func getBlocklist() async throws -> VPSBlocklistResponse {
        return try await get("/api/spam/blocklist")
    }

    public func addToBlocklist(phoneNumber: String) async throws {
        let _: VPSGenericResponse = try await post("/api/spam/blocklist", body: ["phoneNumber": phoneNumber])
    }

    public func removeFromBlocklist(phoneNumber: String) async throws {
        let _: VPSGenericResponse = try await delete("/api/spam/blocklist/\(phoneNumber)")
    }

    // MARK: - Usage

    public func getUsage() async throws -> VPSUsageResponse {
        return try await get("/api/usage")
    }

    public func recordUsage(bytes: Int, category: String, countsTowardStorage: Bool = true) async throws {
        let _: VPSGenericResponse = try await post("/api/usage/record", body: [
            "bytes": bytes,
            "category": category,
            "countsTowardStorage": countsTowardStorage,
        ])
    }

    public func resetStorage() async throws {
        let _: VPSGenericResponse = try await post("/api/usage/reset-storage", body: [:])
    }

    // MARK: - Stripe Billing

    /// Creates a Stripe checkout session and returns the checkout URL
    public func createCheckoutSession(plan: String) async throws -> String {
        let response: VPSCheckoutResponse = try await post("/api/usage/subscription/checkout", body: ["plan": plan])
        return response.url
    }

    /// Gets the Stripe billing portal URL for managing subscriptions
    public func getBillingPortalUrl() async throws -> String {
        let response: VPSPortalResponse = try await get("/api/usage/subscription/portal")
        return response.url
    }

    /// Cancels the Stripe subscription at end of billing period
    public func cancelStripeSubscription() async throws {
        let _: VPSCancelResponse = try await post("/api/usage/subscription/cancel", body: [:])
    }

    /// Syncs subscription from Stripe checkout into the database (for when webhooks aren't available)
    public func syncSubscription() async throws -> VPSSubscriptionSyncResponse {
        return try await post("/api/usage/subscription/sync", body: [:])
    }

    /// Gets subscription status from server
    public func getSubscriptionStatus() async throws -> VPSSubscriptionStatus {
        return try await get("/api/usage/subscription")
    }

    /// Public wrapper to refresh plan from server on demand
    public func refreshPlanNow() async {
        await refreshPlanFromServer()
    }

    // MARK: - File Transfers

    public func getDownloadUrl(r2Key: String) async throws -> String {
        return try await getFileDownloadUrl(fileKey: r2Key)
    }

    public func getFileDownloadUrl(fileKey: String) async throws -> String {
        let response: [String: String] = try await post("/api/file-transfers/download-url", body: ["fileKey": fileKey])
        guard let url = response["downloadUrl"] else {
            throw VPSError.invalidResponse
        }
        return url
    }

    public func getFileTransfers(limit: Int = 50) async throws -> VPSFileTransfersResponse {
        return try await get("/api/file-transfers?limit=\(limit)")
    }

    public func getFileUploadUrl(fileName: String, contentType: String, fileSize: Int64) async throws -> VPSUploadUrlResponse {
        let body: [String: Any] = [
            "fileName": fileName,
            "contentType": contentType,
            "fileSize": fileSize,
            "transferType": "files"
        ]
        return try await post("/api/file-transfers/upload-url", body: body)
    }

    public func confirmFileUpload(fileKey: String, fileSize: Int64) async throws {
        let body: [String: Any] = [
            "fileKey": fileKey,
            "fileSize": fileSize,
            "transferType": "files"
        ]
        let _: VPSGenericResponse = try await post("/api/file-transfers/confirm-upload", body: body)
    }

    public func createFileTransfer(id: String, fileName: String, fileSize: Int64, contentType: String, r2Key: String, source: String) async throws {
        let body: [String: Any] = [
            "id": id,
            "fileName": fileName,
            "fileSize": fileSize,
            "contentType": contentType,
            "r2Key": r2Key,
            "source": source,
            "status": "pending"
        ]
        let _: VPSGenericResponse = try await post("/api/file-transfers", body: body)
    }

    public func updateFileTransferStatus(id: String, status: String, error: String? = nil) async throws {
        var body: [String: Any] = ["status": status]
        if let error = error {
            body["error"] = error
        }
        let _: VPSGenericResponse = try await put("/api/file-transfers/\(id)/status", body: body)
    }

    public func deleteFileTransfer(id: String) async throws {
        let _: VPSGenericResponse = try await delete("/api/file-transfers/\(id)")
    }

    public func deleteR2File(fileKey: String) async throws {
        let _: VPSGenericResponse = try await post("/api/file-transfers/delete-file", body: ["fileKey": fileKey])
    }

    // MARK: - Photos

    public struct VPSPhoto: Codable {
        let id: String
        let fileName: String?
        let storageUrl: String?
        let r2Key: String?
        let fileSize: Int64?
        let contentType: String?
        let metadata: [String: AnyCodableValue]?
        let takenAt: Int64?
        let syncedAt: Int64?
    }

    public struct VPSPhotosResponse: Codable {
        let photos: [VPSPhoto]
    }

    public func getPhotos(limit: Int = 50, before: Int64? = nil) async throws -> VPSPhotosResponse {
        var path = "/api/photos?limit=\(limit)"
        if let before = before {
            path += "&before=\(before)"
        }
        return try await get(path)
    }

    public func getPhotoDownloadUrl(r2Key: String) async throws -> String {
        let response: [String: String] = try await post("/api/photos/download-url", body: ["r2Key": r2Key])
        guard let url = response["downloadUrl"] else {
            throw VPSError.invalidResponse
        }
        return url
    }

    public func deletePhoto(photoId: String, r2Key: String? = nil) async throws {
        var body: [String: String] = ["photoId": photoId]
        if let r2Key = r2Key {
            body["r2Key"] = r2Key
        }
        let _: [String: Bool] = try await post("/api/photos/delete", body: body)
    }

    // MARK: - Notifications

    public struct VPSNotification: Codable {
        let id: String
        let appPackage: String
        let appName: String?
        let title: String?
        let body: String?
        let timestamp: Int64
        let isRead: Bool?
    }

    public struct VPSNotificationsResponse: Codable {
        let notifications: [VPSNotification]
    }

    public func getNotifications(limit: Int = 50, since: Int64? = nil) async throws -> VPSNotificationsResponse {
        var path = "/api/notifications/mirror?limit=\(limit)"
        if let since = since {
            path += "&since=\(since)"
        }
        return try await get(path)
    }

    public func deleteNotification(id: String) async throws {
        let _: VPSGenericResponse = try await delete("/api/notifications/mirror/\(id)")
    }

    public func clearAllNotifications() async throws {
        let _: VPSGenericResponse = try await delete("/api/notifications/mirror?all=true")
    }

    // MARK: - Account Deletion

    public struct AccountDeletionStatus: Decodable {
        let scheduled: Bool
        let scheduledDate: Int64?
        let reason: String?
        let requestedAt: Int64?
        let daysRemaining: Int
        let isScheduledForDeletion: Bool?
        let scheduledDeletionAt: Int64?
    }

    public func getAccountDeletionStatus() async throws -> AccountDeletionStatus {
        return try await get("/api/account/deletion-status")
    }

    public func requestAccountDeletion(reason: String) async throws {
        let _: VPSGenericResponse = try await post("/api/account/delete", body: ["reason": reason])
    }

    public func cancelAccountDeletion() async throws {
        let _: VPSGenericResponse = try await post("/api/account/cancel-deletion", body: [:])
    }

    // MARK: - Media Control

    public func getMediaStatus() async throws -> [String: Any] {
        guard let url = URL(string: baseUrl + "/api/media/status") else { throw VPSError.invalidResponse }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = accessToken { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            throw VPSError.httpError((response as? HTTPURLResponse)?.statusCode ?? 500, nil)
        }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    public func sendMediaCommand(action: String, volume: Int? = nil) async throws {
        var body: [String: Any] = ["action": action]
        if let volume = volume { body["volume"] = volume }
        let _: VPSGenericResponse = try await post("/api/media/commands", body: body)
    }

    // MARK: - Phone Status

    public func getPhoneStatus() async throws -> [String: Any] {
        guard let url = URL(string: baseUrl + "/api/phone-status") else { throw VPSError.invalidResponse }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = accessToken { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            throw VPSError.httpError((response as? HTTPURLResponse)?.statusCode ?? 500, nil)
        }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    public func requestPhoneStatusRefresh() async throws {
        let _: VPSGenericResponse = try await post("/api/phone-status/refresh", body: [:])
    }

    // MARK: - Clipboard Sync

    public func getClipboard() async throws -> [String: Any] {
        guard let url = URL(string: baseUrl + "/api/clipboard") else { throw VPSError.invalidResponse }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = accessToken { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            throw VPSError.httpError((response as? HTTPURLResponse)?.statusCode ?? 500, nil)
        }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    public func syncClipboard(text: String, source: String = "macos") async throws {
        let body: [String: Any] = [
            "text": text,
            "timestamp": Int64(Date().timeIntervalSince1970 * 1000),
            "source": source,
            "type": "text"
        ]
        let _: VPSGenericResponse = try await post("/api/clipboard", body: body)
    }

    // MARK: - DND Sync

    public func getDndStatus() async throws -> [String: Any] {
        guard let url = URL(string: baseUrl + "/api/dnd/status") else { throw VPSError.invalidResponse }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = accessToken { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            throw VPSError.httpError((response as? HTTPURLResponse)?.statusCode ?? 500, nil)
        }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    public func sendDndCommand(action: String) async throws {
        let _: VPSGenericResponse = try await post("/api/dnd/commands", body: ["action": action])
    }

    // MARK: - Hotspot Control

    public func getHotspotStatus() async throws -> [String: Any] {
        guard let url = URL(string: baseUrl + "/api/hotspot/status") else { throw VPSError.invalidResponse }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = accessToken { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            throw VPSError.httpError((response as? HTTPURLResponse)?.statusCode ?? 500, nil)
        }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    public func sendHotspotCommand(action: String) async throws {
        let _: VPSGenericResponse = try await post("/api/hotspot/commands", body: ["action": action])
    }

    // MARK: - Voicemail Sync

    public func getVoicemails() async throws -> [[String: Any]] {
        guard let url = URL(string: baseUrl + "/api/voicemails") else { throw VPSError.invalidResponse }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = accessToken { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            throw VPSError.httpError((response as? HTTPURLResponse)?.statusCode ?? 500, nil)
        }
        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let voicemails = json["voicemails"] as? [[String: Any]] {
            return voicemails
        }
        return []
    }

    public func markVoicemailRead(voicemailId: String) async throws {
        let _: VPSGenericResponse = try await put("/api/voicemails/\(voicemailId)/read", body: ["isRead": true])
    }

    public func deleteVoicemail(voicemailId: String) async throws {
        let _: VPSGenericResponse = try await delete("/api/voicemails/\(voicemailId)")
    }

    // MARK: - Find My Phone

    public func sendFindPhoneRequest(action: String) async throws {
        let _: VPSGenericResponse = try await post("/api/find-phone/request", body: ["action": action])
    }

    // MARK: - Link Sharing

    public func shareLink(url: String, title: String? = nil) async throws {
        var body: [String: Any] = ["url": url]
        if let title = title { body["title"] = title }
        let _: VPSGenericResponse = try await post("/api/links/share", body: body)
    }

    // MARK: - Plan Refresh Timer

    func startPlanRefreshTimer() {
        stopPlanRefreshTimer()
        DispatchQueue.main.async { [weak self] in
            self?.planRefreshTimer = Timer.scheduledTimer(withTimeInterval: 30 * 60, repeats: true) { [weak self] _ in
                Task { [weak self] in
                    await self?.refreshPlanFromServer()
                }
            }
        }
    }

    func stopPlanRefreshTimer() {
        planRefreshTimer?.invalidate()
        planRefreshTimer = nil
    }

    func refreshPlanFromServer() async {
        do {
            let response = try await getUsage()
            let usage = response.usage
            guard !usage.plan.isEmpty else { return }

            let oldPlan = PreferencesService.shared.userPlan
            PreferencesService.shared.setUserPlan(usage.plan, expiresAt: usage.planExpiresAt ?? 0)

            // Notify UI if plan was downgraded to free
            let wasPaid = oldPlan != "free"
            let isNowFree = usage.plan == "free"
            if wasPaid && isNowFree {
                #if DEBUG
                print("[VPS] Plan downgraded from \(oldPlan) to free — notifying UI")
                #endif
                await MainActor.run {
                    NotificationCenter.default.post(name: .vpsPlanDowngraded, object: nil)
                }
            }
        } catch {
            #if DEBUG
            print("[VPS] Plan refresh failed: \(error.localizedDescription)")
            #endif
        }
    }

    // MARK: - E2EE Key Publishing

    public func publishE2eePublicKey(publicKeyX963Base64: String) async throws {
        let body: [String: Any] = [
            "publicKey": publicKeyX963Base64,
            "keyType": "ecdh_p256",
            "version": 2
        ]
        let _: VPSSuccessResponse = try await post("/api/e2ee/public-key", body: body)
    }
}
