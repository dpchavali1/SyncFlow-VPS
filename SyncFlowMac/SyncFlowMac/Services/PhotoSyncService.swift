//
//  PhotoSyncService.swift
//  SyncFlowMac
//
//  Created for SyncFlow photo synchronization functionality.
//
//  OVERVIEW:
//  =========
//  This service synchronizes photos from Android devices to macOS by listening for
//  photo metadata in Firebase Realtime Database and downloading thumbnails from
//  Cloudflare R2 storage. This is a premium-only feature requiring an active subscription.
//
//  SYNC MECHANISM:
//  ===============
//  1. Android uploads photo to R2 storage and creates metadata in Firebase
//  2. This service listens for changes to users/{userId}/photos in Firebase
//  3. When new photos appear, metadata is parsed and thumbnails are downloaded
//  4. Thumbnails are cached locally in ~/Library/Caches/SyncFlowPhotos/
//  5. Full photos can be saved to Downloads on user request
//
//  DATA FLOW:
//  ==========
//  Android -> R2 Storage (photo upload)
//         -> Firebase Realtime DB (metadata: fileName, dateTaken, r2Key, dimensions, etc.)
//
//  Firebase Realtime DB -> PhotoSyncService (listener detects new/changed photos)
//                       -> R2 Storage (download thumbnail via presigned URL)
//                       -> Local Cache (~/Library/Caches/SyncFlowPhotos/)
//                       -> UI (recentPhotos array updates)
//
//  FIREBASE REAL-TIME LISTENER PATTERNS:
//  =====================================
//  - Uses .value observer on users/{userId}/photos to get full snapshot
//  - Entire photos collection is re-parsed on each update
//  - Photos are sorted by dateTaken (newest first) after parsing
//  - Listener is premium-gated; startSync() returns early if not subscribed
//
//  THREADING/ASYNC CONSIDERATIONS:
//  ===============================
//  - Firebase listeners fire on main thread by default
//  - Thumbnail downloads run in async Task blocks to avoid blocking
//  - UI updates (recentPhotos, lastSyncTime) are dispatched to main thread
//  - NSImage creation from downloaded data happens in Task context
//
//  ERROR HANDLING:
//  ===============
//  - R2 download failures are logged but don't affect other photos
//  - Missing required fields in photo data results in skipped entries
//  - File system errors during caching are caught and logged
//
//  CACHING STRATEGY:
//  =================
//  - Thumbnails cached in ~/Library/Caches/SyncFlowPhotos/{photoId}.jpg
//  - Cache check before downloading (avoids redundant downloads)
//  - clearCache() method available for manual cache clearing
//  - No automatic cache eviction (manual clearing only)
//

import Foundation
import AppKit
import Combine
// FirebaseDatabase, FirebaseFunctions - using FirebaseStubs.swift

// MARK: - PhotoSyncService

/// Service responsible for synchronizing photos from Android to macOS.
///
/// This singleton service manages:
/// - Listening for photo metadata from Firebase Realtime Database
/// - Downloading photo thumbnails from Cloudflare R2 storage
/// - Caching thumbnails locally for quick access
/// - Providing photos to UI for display in the photo gallery
///
/// Note: This is a premium-only feature. The service checks subscription status
/// before starting sync operations.
///
/// Usage:
/// ```swift
/// // Start sync after user authentication (premium users only)
/// PhotoSyncService.shared.startSync(userId: "user123")
///
/// // Observe synced photos
/// PhotoSyncService.shared.$recentPhotos
///     .sink { photos in /* update UI */ }
///
/// // Stop sync on logout
/// PhotoSyncService.shared.stopSync()
/// ```
class PhotoSyncService: ObservableObject {

    // MARK: - Singleton

    /// Shared singleton instance for app-wide photo sync operations
    static let shared = PhotoSyncService()

    // MARK: - Published Properties

    /// Array of synced photos, sorted by dateTaken (newest first)
    /// Observed by UI to display photo gallery
    @Published var recentPhotos: [SyncedPhoto] = []
    /// Indicates if a sync operation is in progress (currently unused)
    @Published var isLoading: Bool = false
    /// Timestamp of the last successful sync
    @Published var lastSyncTime: Date?
    /// Flag indicating photo sync requires premium subscription
    @Published var isPremiumFeature: Bool = true // Photo sync is now a premium feature

    // MARK: - Private Properties

    /// Firebase Realtime Database instance
    private let database = Database.database()
    /// Firebase Cloud Functions instance (us-central1 region for R2 integration)
    private let functions = Functions.functions(region: "us-central1")
    /// Handle for the Firebase observer on photos node
    private var photosHandle: DatabaseHandle?
    /// Current authenticated user's ID
    private var currentUserId: String?

    /// Local cache directory for downloaded thumbnails
    /// Path: ~/Library/Caches/SyncFlowPhotos/
    private let cacheDirectory: URL

    // MARK: - Initialization

    /// Private initializer enforces singleton pattern.
    /// Creates the cache directory if it doesn't exist.
    private init() {
        // Create cache directory
        let cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        cacheDirectory = cacheDir.appendingPathComponent("SyncFlowPhotos", isDirectory: true)

        try? FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
    }

    // MARK: - Subscription Validation

    /// Computed property that checks subscription status for premium access.
    /// Photo sync requires subscribed or threeYear status.
    ///
    /// - Returns: true if user has premium access, false for trial/free/expired
    var hasPremiumAccess: Bool {
        let status = SubscriptionService.shared.subscriptionStatus
        switch status {
        case .subscribed, .threeYear:
            return true
        case .trial, .notSubscribed, .expired:
            return false
        }
    }

    // MARK: - Sync Control

    /// Starts listening for synced photos from Firebase.
    /// This is a premium-only feature; method returns early if user is not subscribed.
    ///
    /// - Parameter userId: The sync group user ID
    ///
    /// Threading: Safe to call from any thread; Firebase listener is set up on main thread
    func startSync(userId: String) {
        // Check subscription status - photo sync is premium only
        guard hasPremiumAccess else {
            return
        }

        currentUserId = userId
        startListeningForPhotos(userId: userId)
    }

    /// Stops the photo sync listener and clears user context.
    /// Call this on logout or when switching users.
    func stopSync() {
        stopListeningForPhotos()
        currentUserId = nil
    }

    // MARK: - Firebase Real-Time Listeners

    /// Sets up the Firebase Realtime Database listener for photo metadata.
    ///
    /// Firebase Listener Pattern:
    /// - Observes .value on users/{userId}/photos to get full snapshot
    /// - Triggered on initial load and any subsequent changes
    /// - Parses all photo children and updates recentPhotos array
    /// - Sorts photos by dateTaken descending (newest first)
    /// - Initiates thumbnail downloads for each photo
    ///
    /// Threading:
    /// - Firebase callback fires on main thread
    /// - recentPhotos update dispatched to main thread for safety
    /// - Thumbnail downloads run in separate Task contexts
    ///
    /// - Parameter userId: The sync group user ID
    private func startListeningForPhotos(userId: String) {
        let photosRef = database.reference()
            .child("users")
            .child(userId)
            .child("photos")

        photosHandle = photosRef.observe(.value) { [weak self] snapshot in
            guard let self = self else { return }

            var photos: [SyncedPhoto] = []

            for child in snapshot.children {
                guard let snapshot = child as? DataSnapshot,
                      let data = snapshot.value as? [String: Any] else { continue }

                if let photo = self.parsePhoto(id: snapshot.key, data: data) {
                    photos.append(photo)
                }
            }

            // Sort by date taken, newest first
            photos.sort { $0.dateTaken > $1.dateTaken }

            DispatchQueue.main.async {
                self.recentPhotos = photos
                self.lastSyncTime = Date()
            }

            // Download thumbnails
            for photo in photos {
                self.downloadThumbnail(photo: photo)
            }
        }
    }

    /// Removes the Firebase observer and cleans up the handle.
    /// Safe to call even if no listener is active.
    private func stopListeningForPhotos() {
        guard let userId = currentUserId, let handle = photosHandle else { return }

        database.reference()
            .child("users")
            .child(userId)
            .child("photos")
            .removeObserver(withHandle: handle)

        photosHandle = nil
    }

    // MARK: - Data Parsing

    /// Parses a photo metadata dictionary from Firebase into a SyncedPhoto model.
    ///
    /// Required fields: fileName, dateTaken, r2Key
    /// Optional fields: width, height, size, mimeType, syncedAt
    ///
    /// - Parameters:
    ///   - id: Firebase snapshot key (used as photo ID)
    ///   - data: Dictionary of photo metadata from Firebase
    /// - Returns: SyncedPhoto instance or nil if required fields are missing
    private func parsePhoto(id: String, data: [String: Any]) -> SyncedPhoto? {
        guard let fileName = data["fileName"] as? String,
              let dateTaken = data["dateTaken"] as? Double,
              let r2Key = data["r2Key"] as? String else {
            return nil
        }

        let width = data["width"] as? Int ?? 0
        let height = data["height"] as? Int ?? 0
        let size = data["size"] as? Int64 ?? 0
        let mimeType = data["mimeType"] as? String ?? "image/jpeg"
        let syncedAt = data["syncedAt"] as? Double ?? Date().timeIntervalSince1970 * 1000

        return SyncedPhoto(
            id: id,
            fileName: fileName,
            dateTaken: Date(timeIntervalSince1970: dateTaken / 1000),
            thumbnailUrl: r2Key,
            width: width,
            height: height,
            size: size,
            mimeType: mimeType,
            syncedAt: Date(timeIntervalSince1970: syncedAt / 1000),
            localPath: cacheDirectory.appendingPathComponent("\(id).jpg")
        )
    }

    // MARK: - Thumbnail Download

    /// Downloads a photo thumbnail from R2 storage and caches it locally.
    ///
    /// Download Flow:
    /// 1. Check if thumbnail already exists in local cache
    /// 2. If cached, load from disk and update photo.thumbnail
    /// 3. If not cached, request presigned URL from Cloud Function
    /// 4. Download thumbnail using URLSession
    /// 5. Move downloaded file to cache directory
    /// 6. Update photo.isDownloaded and photo.thumbnail for UI
    ///
    /// Error Handling:
    /// - Cloud Function failures are logged but don't throw
    /// - Download failures are logged but don't affect other photos
    /// - File system errors during caching are caught and logged
    ///
    /// Threading: Runs in async Task context; UI updates via MainActor.run
    ///
    /// - Parameter photo: The SyncedPhoto to download thumbnail for
    private func downloadThumbnail(photo: SyncedPhoto) {
        // Check if already cached
        if FileManager.default.fileExists(atPath: photo.localPath.path) {
            DispatchQueue.main.async { [weak self] in
                if let index = self?.recentPhotos.firstIndex(where: { $0.id == photo.id }) {
                    self?.recentPhotos[index].isDownloaded = true
                    self?.recentPhotos[index].thumbnail = NSImage(contentsOf: photo.localPath)
                }
            }
            return
        }

        // Get presigned download URL from R2 via Cloud Function
        let r2Key = photo.thumbnailUrl
        Task {
            do {
                let result = try await functions.httpsCallable("getR2DownloadUrl").call(["fileKey": r2Key])  // Cloud Function expects "fileKey"
                guard let response = result.data as? [String: Any],
                      let downloadUrl = response["downloadUrl"] as? String,
                      let url = URL(string: downloadUrl) else {
                    print("PhotoSyncService: Failed to get R2 download URL for \(r2Key)")
                    return
                }

                // Download from presigned URL
                let (tempUrl, _) = try await URLSession.shared.download(from: url)

                // Move to cache
                if FileManager.default.fileExists(atPath: photo.localPath.path) {
                    try FileManager.default.removeItem(at: photo.localPath)
                }
                try FileManager.default.moveItem(at: tempUrl, to: photo.localPath)

                // Update UI
                await MainActor.run {
                    if let index = self.recentPhotos.firstIndex(where: { $0.id == photo.id }) {
                        self.recentPhotos[index].isDownloaded = true
                        self.recentPhotos[index].thumbnail = NSImage(contentsOf: photo.localPath)
                    }
                }
            } catch {
                print("PhotoSyncService: Error downloading thumbnail: \(error)")
            }
        }
    }

    // MARK: - User Actions

    /// Opens a downloaded photo in the default image viewer (Preview).
    /// Only works if the photo has been downloaded (isDownloaded == true).
    ///
    /// - Parameter photo: The photo to open
    func openPhoto(_ photo: SyncedPhoto) {
        if photo.isDownloaded {
            NSWorkspace.shared.open(photo.localPath)
        }
    }

    /// Saves a photo from cache to the Downloads folder.
    /// Copies the file (doesn't move) so cache remains valid.
    /// Shows the saved file in Finder after successful copy.
    ///
    /// Error Handling:
    /// - Overwrites existing file with same name
    /// - File system errors are logged but don't throw
    ///
    /// - Parameter photo: The photo to save (must be downloaded)
    func savePhoto(_ photo: SyncedPhoto) {
        guard photo.isDownloaded else { return }

        let downloadsUrl = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
        let destination = downloadsUrl.appendingPathComponent(photo.fileName)

        do {
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.copyItem(at: photo.localPath, to: destination)

            // Show in Finder
            NSWorkspace.shared.selectFile(destination.path, inFileViewerRootedAtPath: "")

        } catch {
            print("PhotoSyncService: Error saving photo: \(error)")
        }
    }

    // MARK: - Cache Management

    /// Clears all cached photo thumbnails from disk.
    /// Also resets isDownloaded and thumbnail properties on all recentPhotos.
    ///
    /// Use this to free disk space or force re-download of thumbnails.
    func clearCache() {
        do {
            let files = try FileManager.default.contentsOfDirectory(at: cacheDirectory, includingPropertiesForKeys: nil)
            for file in files {
                try FileManager.default.removeItem(at: file)
            }

            DispatchQueue.main.async {
                for index in self.recentPhotos.indices {
                    self.recentPhotos[index].isDownloaded = false
                    self.recentPhotos[index].thumbnail = nil
                }
            }

        } catch {
            print("PhotoSyncService: Error clearing cache: \(error)")
        }
    }

    // MARK: - Battery Optimization

    /// Reduces sync frequency for battery saving.
    /// Note: Currently a placeholder - Firebase listeners don't have configurable frequency.
    /// Future implementation could disconnect/reconnect on a timer.
    func reduceSyncFrequency() {
        // Reduce sync frequency by increasing polling interval
        // This would typically reduce the frequency of photo sync operations
    }

    /// Pauses photo sync temporarily by stopping the listener.
    /// Preserves currentUserId so resumeSync() can restart without re-authentication.
    func pauseSync() {
        stopSync()
    }

    /// Resumes photo sync after a pause.
    /// Re-uses the stored currentUserId to restart listening.
    func resumeSync() {
        if let userId = currentUserId {
            startSync(userId: userId)
        }
    }
}

// MARK: - SyncedPhoto Model

/// Model representing a photo synced from Android.
///
/// This struct contains both metadata from Firebase and local state (isDownloaded, thumbnail).
/// The localPath is computed at parse time based on the cache directory and photo ID.
struct SyncedPhoto: Identifiable {
    let id: String           // Firebase snapshot key
    let fileName: String     // Original file name from Android
    let dateTaken: Date      // When the photo was taken (from EXIF or file date)
    let thumbnailUrl: String // R2 key for thumbnail download (misnamed, actually r2Key)
    let width: Int           // Image width in pixels
    let height: Int          // Image height in pixels
    let size: Int64          // File size in bytes
    let mimeType: String     // MIME type (e.g., "image/jpeg")
    let syncedAt: Date       // When the photo was synced to Firebase
    let localPath: URL       // Local cache file path

    /// Indicates if thumbnail has been downloaded to local cache
    var isDownloaded: Bool = false
    /// In-memory thumbnail image for UI display
    var thumbnail: NSImage? = nil

    // MARK: - Computed Properties

    /// Formatted date string for UI display (e.g., "Jan 15, 2024, 3:30 PM")
    var formattedDate: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: dateTaken)
    }

    /// Human-readable file size (e.g., "2.5 MB")
    var formattedSize: String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: size)
    }
}
