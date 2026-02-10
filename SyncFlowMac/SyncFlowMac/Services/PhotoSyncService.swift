//
//  PhotoSyncService.swift
//  SyncFlowMac
//
//  Synchronizes photos from Android devices to macOS via VPS.
//

import Foundation
import AppKit
import Combine

// MARK: - PhotoSyncService

class PhotoSyncService: ObservableObject {

    // MARK: - Singleton

    static let shared = PhotoSyncService()

    // MARK: - Published Properties

    @Published var recentPhotos: [SyncedPhoto] = []
    @Published var isLoading: Bool = false
    @Published var lastSyncTime: Date?
    @Published var isPremiumFeature: Bool = false

    // MARK: - Private Properties

    private var currentUserId: String?
    private let cacheDirectory: URL

    // MARK: - Initialization

    private init() {
        let cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        cacheDirectory = cacheDir.appendingPathComponent("SyncFlowPhotos", isDirectory: true)

        try? FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
    }

    // MARK: - Subscription Validation

    var hasPremiumAccess: Bool {
        return true
    }

    // MARK: - Sync Control

    func startSync(userId: String) {
        guard hasPremiumAccess else { return }
        currentUserId = userId
        Task {
            await fetchPhotos()
        }
    }

    func stopSync() {
        currentUserId = nil
    }

    // MARK: - Fetch Photos from VPS

    @MainActor
    func fetchPhotos() async {
        guard currentUserId != nil else { return }
        isLoading = true

        do {
            let response = try await VPSService.shared.getPhotos(limit: 50)
            var photos: [SyncedPhoto] = []

            for vpsPhoto in response.photos {
                guard let r2Key = vpsPhoto.r2Key, !r2Key.isEmpty else { continue }

                let photo = SyncedPhoto(
                    id: vpsPhoto.id,
                    fileName: vpsPhoto.fileName ?? "photo.jpg",
                    dateTaken: Date(timeIntervalSince1970: TimeInterval(vpsPhoto.takenAt ?? 0) / 1000),
                    thumbnailUrl: r2Key,
                    width: 0,
                    height: 0,
                    size: vpsPhoto.fileSize ?? 0,
                    mimeType: vpsPhoto.contentType ?? "image/jpeg",
                    syncedAt: vpsPhoto.syncedAt != nil
                        ? Date(timeIntervalSince1970: TimeInterval(vpsPhoto.syncedAt!) / 1000)
                        : Date(),
                    localPath: cacheDirectory.appendingPathComponent("\(vpsPhoto.id).jpg")
                )
                photos.append(photo)
            }

            self.recentPhotos = photos
            self.lastSyncTime = Date()
            self.isLoading = false

            // Download thumbnails in background
            for photo in photos {
                downloadThumbnail(photo: photo)
            }
        } catch {
            print("[PhotoSyncService] Error fetching photos: \(error.localizedDescription)")
            self.isLoading = false
        }
    }

    // MARK: - Thumbnail Download

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

        // Download from R2 via presigned URL
        Task {
            do {
                let downloadUrl = try await VPSService.shared.getPhotoDownloadUrl(r2Key: photo.thumbnailUrl)
                guard let url = URL(string: downloadUrl) else { return }

                let (data, _) = try await URLSession.shared.data(from: url)

                // Save to cache
                try data.write(to: photo.localPath)

                // Update UI
                await MainActor.run { [weak self] in
                    if let index = self?.recentPhotos.firstIndex(where: { $0.id == photo.id }) {
                        self?.recentPhotos[index].isDownloaded = true
                        self?.recentPhotos[index].thumbnail = NSImage(data: data)
                    }
                }
            } catch {
                print("[PhotoSyncService] Error downloading thumbnail for \(photo.id): \(error.localizedDescription)")
            }
        }
    }

    // MARK: - User Actions

    func openPhoto(_ photo: SyncedPhoto) {
        if photo.isDownloaded {
            NSWorkspace.shared.open(photo.localPath)
        }
    }

    func savePhoto(_ photo: SyncedPhoto) {
        guard photo.isDownloaded else { return }

        let downloadsUrl = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
        let destination = downloadsUrl.appendingPathComponent(photo.fileName)

        do {
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.copyItem(at: photo.localPath, to: destination)
            NSWorkspace.shared.selectFile(destination.path, inFileViewerRootedAtPath: "")
        } catch {
            print("[PhotoSyncService] Error saving photo: \(error)")
        }
    }

    // MARK: - Cache Management

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
            print("[PhotoSyncService] Error clearing cache: \(error)")
        }
    }

    // MARK: - Battery Optimization

    func reduceSyncFrequency() {}

    func pauseSync() {
        stopSync()
    }

    func resumeSync() {
        if let userId = currentUserId {
            startSync(userId: userId)
        }
    }
}

// MARK: - SyncedPhoto Model

struct SyncedPhoto: Identifiable {
    let id: String
    let fileName: String
    let dateTaken: Date
    let thumbnailUrl: String     // R2 key
    let width: Int
    let height: Int
    let size: Int64
    let mimeType: String
    let syncedAt: Date
    let localPath: URL

    var isDownloaded: Bool = false
    var thumbnail: NSImage? = nil

    var formattedDate: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: dateTaken)
    }

    var formattedSize: String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: size)
    }
}
