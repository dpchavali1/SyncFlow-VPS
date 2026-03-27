//
//  PhotoSyncService.swift
//  SyncFlowMac
//
//  Photo sync feature has been removed. This stub is kept to prevent
//  compilation errors from any remaining references.
//

import Foundation
import Combine
import AppKit

// MARK: - PhotoSyncService (Stub)

class PhotoSyncService: ObservableObject {

    static let shared = PhotoSyncService()

    @Published var recentPhotos: [SyncedPhoto] = []
    @Published var isLoading: Bool = false
    @Published var lastSyncTime: Date?
    @Published var isPremiumFeature: Bool = false

    private init() {}

    var hasPremiumAccess: Bool { false }

    func startSync(userId: String) {}
    func stopSync() {}
    func openPhoto(_ photo: SyncedPhoto) {}
    func savePhoto(_ photo: SyncedPhoto) {}
    func clearCache() {}
    func reduceSyncFrequency() {}
    func pauseSync() {}
    func resumeSync() {}

    @MainActor
    func fetchPhotos() async {}

    @MainActor
    func deletePhoto(_ photo: SyncedPhoto) async {}
}

// MARK: - SyncedPhoto Model (Stub)

struct SyncedPhoto: Identifiable {
    let id: String
    let fileName: String
    let dateTaken: Date
    let thumbnailUrl: String
    let width: Int
    let height: Int
    let size: Int64
    let mimeType: String
    let syncedAt: Date
    let localPath: URL

    var isDownloaded: Bool = false
    var thumbnail: NSImage? = nil

    var formattedDate: String { "" }
    var formattedSize: String { "" }
}
