//
//  FileTransferService.swift
//  SyncFlowMac
//
//  Created for SyncFlow cross-platform file transfer functionality.
//
//  OVERVIEW:
//  =========
//  This service handles bidirectional file transfers between macOS and Android devices
//  using Cloudflare R2 for cloud storage and VPS REST API for coordination.
//
//  SYNC MECHANISM:
//  ===============
//  1. Mac to Android (Upload):
//     - User initiates file upload via sendFile(url:)
//     - Service requests a presigned upload URL from R2 via VPS API
//     - File is uploaded directly to R2 using the presigned URL
//     - Upload is confirmed via VPS API to record usage metrics
//     - Transfer record is created via VPS API
//     - Android device polls for this record and downloads the file
//
//  2. Android to Mac (Download):
//     - Service polls GET /api/file-transfers every 5 seconds
//     - When a transfer with source="android" and status="pending" is found,
//       handleIncomingTransfer() is called
//     - Service requests presigned download URL from R2 via VPS API
//     - File is downloaded and saved to ~/Downloads/SyncFlow/
//     - Status is updated via VPS API, and the R2 file is deleted after successful download
//
//  POLLING PATTERN:
//  ================
//  - Uses Timer.scheduledTimer polling GET /api/file-transfers every 5 seconds
//  - Polling is started when user is configured and stopped on user change or logout
//  - Only processes transfers where source="android" and status="pending"
//  - Includes a 5-minute recency check to ignore stale transfer records
//
//  THREADING/ASYNC CONSIDERATIONS:
//  ===============================
//  - Polling timer fires on the main thread
//  - Async Task blocks are used for download/upload operations to avoid blocking
//  - UI state updates (latestTransfer, incomingFiles) are dispatched to main thread
//  - processingFileIds Set prevents duplicate processing of the same transfer
//
//  ERROR HANDLING:
//  ===============
//  - File size validation occurs before upload/download
//  - Network errors are caught and reflected in TransferStatus
//  - Failed transfers update status via VPS API with error message
//  - User notifications are shown for both success and failure states
//
//  RETRY/CONFLICT RESOLUTION:
//  ==========================
//  - No automatic retry mechanism implemented; failures require user re-initiation
//  - Duplicate file name conflicts are resolved by appending " (n)" suffix
//  - processingFileIds Set prevents concurrent processing of the same transfer
//

import Foundation
import Combine
import UniformTypeIdentifiers
import UserNotifications

// MARK: - FileTransferService

/// Service responsible for bidirectional file transfers between macOS and Android.
///
/// This singleton service manages:
/// - Uploading files from Mac to Android via Cloudflare R2
/// - Downloading files from Android to Mac via Cloudflare R2
/// - Transfer progress tracking and status updates
/// - File size limit enforcement based on subscription tier
///
/// Usage:
/// ```swift
/// // Configure with user ID after authentication
/// FileTransferService.shared.configure(userId: "user123")
///
/// // Send a file to Android
/// FileTransferService.shared.sendFile(url: fileURL)
///
/// // Observe transfer status
/// FileTransferService.shared.$latestTransfer
///     .sink { status in /* handle status updates */ }
/// ```
class FileTransferService: ObservableObject {

    // MARK: - Singleton

    /// Shared singleton instance for app-wide file transfer operations
    static let shared = FileTransferService()

    // MARK: - Types

    /// Represents the current state of a file transfer operation.
    /// Used to track progress and display appropriate UI feedback.
    enum TransferState: String {
        case uploading      // File is being uploaded to R2
        case downloading    // File is being downloaded from R2
        case sent           // Upload completed successfully
        case received       // Download completed successfully
        case failed         // Transfer failed with error
    }

    /// Tracks the status of an active or completed transfer.
    /// Published via latestTransfer for UI observation.
    struct TransferStatus: Identifiable {
        let id: String
        let fileName: String
        let state: TransferState
        let progress: Double
        let timestamp: Date
        let error: String?
    }

    /// Represents a file transfer initiated from Android that is pending download.
    /// Parsed from VPS API transfer records.
    struct IncomingFile: Identifiable {
        let id: String          // Transfer record ID
        let fileName: String    // Original file name from Android
        let fileSize: Int64     // File size in bytes
        let contentType: String // MIME type (e.g., "image/jpeg")
        let r2Key: String       // R2 storage key for download
        let timestamp: Date     // When the transfer was initiated
    }

    // MARK: - Transfer Limits

    // Tiered limits (no daily limits - R2 has free egress)
    /// Maximum file size for free tier users (50MB)
    static let maxFileSizeFree: Int64 = 50 * 1024 * 1024      // 50MB for free users
    /// Maximum file size for pro/paid tier users (1GB)
    static let maxFileSizePro: Int64 = 1024 * 1024 * 1024     // 1GB for pro users

    /// Encapsulates the current user's transfer limits based on subscription
    struct TransferLimits {
        let maxFileSize: Int64  // Maximum allowed file size in bytes
        let isPro: Bool         // Whether user has pro subscription
    }

    // MARK: - Published Properties

    /// The most recent transfer status, observed by UI for progress/status display
    @Published private(set) var latestTransfer: TransferStatus?
    /// List of files pending download from Android (currently unused but available for UI)
    @Published private(set) var incomingFiles: [IncomingFile] = []
    /// Current user's transfer limits based on subscription tier
    @Published private(set) var transferLimits: TransferLimits?

    // MARK: - Private Properties

    /// VPS service for API calls
    private let vpsService = VPSService.shared
    /// Legacy max file size (use tiered limits instead via transferLimits)
    private let maxFileSize: Int64 = 50 * 1024 * 1024  // Legacy, use tiered limits

    /// Current authenticated user's ID (sync group user ID)
    private var currentUserId: String?
    /// Timer for polling file transfers from VPS
    private var pollingTimer: Timer?
    /// Set of file IDs currently being processed to prevent duplicate downloads
    /// This is important because polling may return the same transfer multiple times
    private var processingFileIds: Set<String> = []

    // MARK: - Initialization

    /// Private initializer enforces singleton pattern
    private init() {}

    // MARK: - Transfer Limit Validation

    /// Refreshes the user's transfer limits by checking their subscription status.
    /// Called before each transfer to ensure limits are current.
    ///
    /// Threading: Uses MainActor.run to update published property on main thread.
    func refreshLimits() async {
        let isPro = await checkIsPro()
        let maxSize = isPro ? Self.maxFileSizePro : Self.maxFileSizeFree

        await MainActor.run {
            self.transferLimits = TransferLimits(
                maxFileSize: maxSize,
                isPro: isPro
            )
        }
    }

    /// Checks VPS to determine if user has a pro subscription.
    /// Uses GET /api/usage to check the user's plan.
    ///
    /// - Returns: true if user has any non-free plan, false otherwise
    /// - Note: Defaults to true on connectivity errors to allow transfers
    private func checkIsPro() async -> Bool {
        guard currentUserId != nil else { return false }

        do {
            let usageResponse = try await vpsService.getUsage()
            return usageResponse.usage.plan != "free"
        } catch {
            print("[FileTransfer] Error checking pro status: \(error)")
            // Default to allowing transfers if we can't check (connectivity issues)
            return true
        }
    }

    /// Validates whether a file of the given size can be transferred.
    /// Refreshes limits before checking to ensure current subscription status.
    ///
    /// - Parameter fileSize: Size of the file in bytes
    /// - Returns: Tuple indicating if transfer is allowed and reason if not
    func canTransfer(fileSize: Int64) async -> (allowed: Bool, reason: String?) {
        await refreshLimits()

        guard let limits = transferLimits else {
            return (false, "Unable to check limits")
        }

        if fileSize > limits.maxFileSize {
            let maxMB = limits.maxFileSize / (1024 * 1024)
            let upgradeHint = limits.isPro ? "" : " (Upgrade to Pro for 1GB)"
            return (false, "File too large. Max size: \(maxMB)MB\(upgradeHint)")
        }

        return (true, nil)
    }

    // MARK: - Configuration

    /// Configures the service for a specific user.
    /// Call this after user authentication or when sync group changes.
    ///
    /// - Parameter userId: The sync group user ID, or nil to stop listening
    ///
    /// Behavior:
    /// - If userId changes, stops existing polling before starting new one
    /// - If userId is nil, only stops polling (logout scenario)
    /// - If userId is the same, no action is taken
    func configure(userId: String?) {
        // Stop existing polling if user changed
        if currentUserId != userId {
            stopListening()
            processingFileIds.removeAll()
        }
        currentUserId = userId

        // Start polling for incoming files from Android
        if userId != nil {
            startListening()
        }
    }

    // MARK: - VPS REST Polling (Android to Mac)

    /// Starts polling for incoming file transfers from Android via VPS API.
    ///
    /// Polling Pattern:
    /// - Polls GET /api/file-transfers every 5 seconds
    /// - Filters for source="android" and status="pending"
    /// - Only one polling timer active at a time
    ///
    /// Threading: Timer fires on main RunLoop
    private func startListening() {
        guard currentUserId != nil else { return }
        guard pollingTimer == nil else { return }

        // Poll immediately on start
        pollForTransfers()

        // Then poll every 5 seconds
        pollingTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: true) { [weak self] _ in
            self?.pollForTransfers()
        }

        print("[FileTransfer] Started polling for incoming files")
    }

    /// Stops the polling timer.
    /// Safe to call even if no timer is active.
    private func stopListening() {
        pollingTimer?.invalidate()
        pollingTimer = nil
        print("[FileTransfer] Stopped polling for incoming files")
    }

    /// Polls VPS API for pending file transfers and processes new ones.
    private func pollForTransfers() {
        Task {
            do {
                let response = try await vpsService.getFileTransfers()

                // Log pending incoming transfers for debugging
                let incoming = response.transfers.filter { $0.source != "macos" && $0.status == "pending" }
                if !incoming.isEmpty {
                    print("[FileTransfer] Found \(incoming.count) incoming pending transfers: \(incoming.map { "\($0.id) from \($0.source)" })")
                }

                for transfer in response.transfers {
                    // Only process files not from macOS that are pending
                    guard transfer.source != nil, transfer.source != "macos",
                          transfer.status == "pending" else { continue }

                    // Prevent duplicate processing
                    guard !processingFileIds.contains(transfer.id) else { continue }

                    guard let r2Key = transfer.r2Key else { continue }

                    let timestamp = transfer.timestamp ?? (Date().timeIntervalSince1970 * 1000)

                    // Check if file is recent (within last 5 minutes)
                    let fileDate = Date(timeIntervalSince1970: timestamp / 1000)
                    guard Date().timeIntervalSince(fileDate) < 300 else {
                        // Clean up stale transfers so they stop appearing in polls
                        if !processingFileIds.contains(transfer.id) {
                            processingFileIds.insert(transfer.id)
                            print("[FileTransfer] Cleaning up stale transfer: \(transfer.id)")
                            Task {
                                try? await vpsService.updateFileTransferStatus(id: transfer.id, status: "failed", error: "Expired")
                                try? await vpsService.deleteFileTransfer(id: transfer.id)
                                processingFileIds.remove(transfer.id)
                            }
                        }
                        continue
                    }

                    print("[FileTransfer] Incoming file from Android: \(transfer.fileName) (\(transfer.fileSize) bytes)")

                    processingFileIds.insert(transfer.id)

                    // Download in background
                    Task {
                        await downloadIncomingFile(
                            fileId: transfer.id,
                            fileName: transfer.fileName,
                            fileSize: transfer.fileSize,
                            contentType: transfer.contentType ?? "application/octet-stream",
                            r2Key: r2Key
                        )
                        processingFileIds.remove(transfer.id)
                    }
                }
            } catch {
                print("[FileTransfer] Polling error: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Download Logic

    /// Downloads a file from R2 storage to the local Downloads folder.
    ///
    /// Download Flow:
    /// 1. Validate file size against maxFileSize limit
    /// 2. Update VPS status to "downloading"
    /// 3. Get presigned download URL from R2 via VPS API
    /// 4. Download file to temp directory using URLSession
    /// 5. Move file to ~/Downloads/SyncFlow/ with duplicate name handling
    /// 6. Update VPS status to "downloaded"
    /// 7. Delete file from R2 to free storage
    /// 8. Clean up transfer record after 5 second delay
    ///
    /// Error Handling:
    /// - File size exceeded: Updates status to "failed" via VPS
    /// - Download failures: Caught and reflected in status with error message
    /// - Notifications shown for both success and failure
    ///
    /// Threading: Runs in async Task context, UI updates via DispatchQueue.main
    private func downloadIncomingFile(
        fileId: String,
        fileName: String,
        fileSize: Int64,
        contentType: String,
        r2Key: String
    ) async {
        guard currentUserId != nil else { return }

        // Check file size
        if fileSize > maxFileSize {
            print("[FileTransfer] File too large: \(fileSize) bytes")
            await updateIncomingStatus(fileId: fileId, status: "failed", error: "File too large")
            return
        }

        updateStatus(id: fileId, fileName: fileName, state: .downloading, progress: 0, error: nil)
        showNotification(title: "Downloading file", body: fileName)

        // Update status to downloading
        await updateIncomingStatus(fileId: fileId, status: "downloading")

        do {
            // Get presigned download URL from R2 via VPS
            let downloadUrlString = try await vpsService.getFileDownloadUrl(fileKey: r2Key)

            guard let downloadUrl = URL(string: downloadUrlString) else {
                throw NSError(domain: "FileTransfer", code: 2, userInfo: [NSLocalizedDescriptionKey: "Invalid download URL"])
            }

            // Download to temp file
            let tempDir = FileManager.default.temporaryDirectory
            let tempFile = tempDir.appendingPathComponent("download_\(fileName)")

            // Download using URLSession
            let (localUrl, response) = try await URLSession.shared.download(from: downloadUrl)

            guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
                throw NSError(domain: "FileTransfer", code: 4, userInfo: [NSLocalizedDescriptionKey: "Download failed"])
            }

            // Move from temp download location to our temp file
            try? FileManager.default.removeItem(at: tempFile)
            try FileManager.default.moveItem(at: localUrl, to: tempFile)

            updateStatus(id: fileId, fileName: fileName, state: .downloading, progress: 0.9, error: nil)

            // Move to Downloads folder
            let downloadsURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
            let syncFlowDir = downloadsURL.appendingPathComponent("SyncFlow")

            try? FileManager.default.createDirectory(at: syncFlowDir, withIntermediateDirectories: true)

            var destURL = syncFlowDir.appendingPathComponent(fileName)

            // Handle duplicate file names
            var counter = 1
            let fileExtension = destURL.pathExtension
            let baseName = destURL.deletingPathExtension().lastPathComponent
            while FileManager.default.fileExists(atPath: destURL.path) {
                let newName = "\(baseName) (\(counter)).\(fileExtension)"
                destURL = syncFlowDir.appendingPathComponent(newName)
                counter += 1
            }

            try FileManager.default.moveItem(at: tempFile, to: destURL)

            print("[FileTransfer] File saved to: \(destURL.path)")

            // Update status to downloaded
            await updateIncomingStatus(fileId: fileId, status: "downloaded")
            updateStatus(id: fileId, fileName: fileName, state: .received, progress: 1, error: nil)
            showNotification(title: "File received", body: "\(fileName) saved to Downloads/SyncFlow")

            // Delete file from R2 after successful download
            try? await vpsService.deleteR2File(fileKey: r2Key)

            // Clean up transfer record after delay
            try? await Task.sleep(nanoseconds: 5_000_000_000) // 5 seconds
            try? await vpsService.deleteFileTransfer(id: fileId)

        } catch {
            print("[FileTransfer] Download error: \(error)")
            await updateIncomingStatus(fileId: fileId, status: "failed", error: error.localizedDescription)
            updateStatus(id: fileId, fileName: fileName, state: .failed, error: error.localizedDescription)
            showNotification(title: "Download failed", body: fileName)
        }
    }

    // MARK: - VPS Status Updates

    /// Updates the status of an incoming transfer via VPS API.
    /// Used to coordinate state between devices (e.g., "downloading", "downloaded", "failed").
    private func updateIncomingStatus(fileId: String, status: String, error: String? = nil) async {
        try? await vpsService.updateFileTransferStatus(id: fileId, status: status, error: error)
    }

    // MARK: - User Notifications

    /// Displays a local macOS notification to the user.
    /// Used to notify of transfer progress, completion, or failure.
    private func showNotification(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request)
    }

    // MARK: - Upload Logic (Mac to Android)

    /// Public entry point for sending a file to Android.
    /// Initiates an async upload task.
    ///
    /// - Parameter url: Local file URL to upload
    func sendFile(url: URL) {
        Task {
            await uploadFile(url: url)
        }
    }

    /// Uploads a file to R2 storage and creates a transfer record via VPS API.
    ///
    /// Upload Flow:
    /// 1. Validate user is paired and authenticated
    /// 2. Validate file exists and get file size
    /// 3. Check file size against tiered limits (canTransfer)
    /// 4. Get presigned upload URL from R2 via VPS API
    /// 5. Upload file directly to R2 using PUT request
    /// 6. Confirm upload via VPS API (records usage metrics)
    /// 7. Create transfer record via VPS API with source="macos", status="pending"
    /// 8. Android device polls for this record and initiates download
    private func uploadFile(url: URL) async {
        guard currentUserId != nil else {
            updateStatus(id: UUID().uuidString, fileName: url.lastPathComponent, state: .failed, error: "Not paired")
            return
        }

        guard vpsService.isAuthenticated else {
            updateStatus(id: UUID().uuidString, fileName: url.lastPathComponent, state: .failed, error: "Not authenticated")
            return
        }

        let fileId = UUID().uuidString
        let fileName = url.lastPathComponent

        let fileSize = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
        if fileSize <= 0 {
            updateStatus(id: fileId, fileName: fileName, state: .failed, error: "Invalid file")
            return
        }

        // Check tiered limits (file size)
        let transferCheck = await canTransfer(fileSize: fileSize)
        if !transferCheck.allowed {
            updateStatus(id: fileId, fileName: fileName, state: .failed, error: transferCheck.reason)
            return
        }

        let contentType = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"

        updateStatus(id: fileId, fileName: fileName, state: .uploading, progress: 0, error: nil)

        do {
            // Step 1: Get presigned upload URL from R2 via VPS
            let uploadResponse = try await vpsService.getFileUploadUrl(
                fileName: fileName,
                contentType: contentType,
                fileSize: fileSize
            )

            guard let uploadUrl = URL(string: uploadResponse.uploadUrl) else {
                throw NSError(domain: "FileTransfer", code: 10, userInfo: [NSLocalizedDescriptionKey: "Invalid upload URL"])
            }
            let r2Key = uploadResponse.fileKey

            updateStatus(id: fileId, fileName: fileName, state: .uploading, progress: 0.1, error: nil)

            // Step 2: Upload file directly to R2 using presigned URL
            let fileData = try Data(contentsOf: url)

            var request = URLRequest(url: uploadUrl)
            request.httpMethod = "PUT"
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
            request.setValue("\(fileSize)", forHTTPHeaderField: "Content-Length")

            let (_, response) = try await URLSession.shared.upload(for: request, from: fileData)

            guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
                throw NSError(domain: "FileTransfer", code: 11, userInfo: [NSLocalizedDescriptionKey: "Upload to R2 failed"])
            }

            updateStatus(id: fileId, fileName: fileName, state: .uploading, progress: 0.8, error: nil)

            // Step 3: Confirm upload to record usage
            try await vpsService.confirmFileUpload(fileKey: r2Key, fileSize: fileSize)
            print("[FileTransfer] Upload confirmed for r2Key: \(r2Key)")

            updateStatus(id: fileId, fileName: fileName, state: .uploading, progress: 0.9, error: nil)

            // Step 4: Create transfer record via VPS API
            try await vpsService.createFileTransfer(
                id: fileId,
                fileName: fileName,
                fileSize: fileSize,
                contentType: contentType,
                r2Key: r2Key,
                source: "macos"
            )
            print("[FileTransfer] Transfer record created: \(fileName), source=macos, r2Key=\(r2Key)")

            updateStatus(id: fileId, fileName: fileName, state: .sent, progress: 1, error: nil)

            // Refresh limits after upload
            await refreshLimits()

        } catch {
            updateStatus(id: fileId, fileName: fileName, state: .failed, error: error.localizedDescription)
        }
    }

    // MARK: - UI State Management

    /// Updates the published latestTransfer property for UI observation.
    /// Dispatches to main thread to ensure safe UI updates.
    private func updateStatus(
        id: String,
        fileName: String,
        state: TransferState,
        progress: Double = 0,
        error: String?
    ) {
        let timestamp = (latestTransfer?.id == id ? latestTransfer?.timestamp : Date()) ?? Date()
        DispatchQueue.main.async {
            self.latestTransfer = TransferStatus(
                id: id,
                fileName: fileName,
                state: state,
                progress: progress,
                timestamp: timestamp,
                error: error
            )
        }
    }

}
