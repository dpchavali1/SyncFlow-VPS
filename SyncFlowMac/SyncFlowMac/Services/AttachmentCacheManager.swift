//
//  AttachmentCacheManager.swift
//  SyncFlowMac
//
//  Caches downloaded attachments to disk for faster loading
//

import Foundation
import AppKit
import CryptoKit

class AttachmentCacheManager {
    static let shared = AttachmentCacheManager()

    private let fileManager = FileManager.default
    private let cacheDirectory: URL
    private var cacheSizeLimit: Int64 = 500 * 1024 * 1024 // 500 MB max cache
    private let maxCacheAge: TimeInterval = 7 * 24 * 60 * 60 // 7 days
    private let cacheVersion = 2 // Increment when cache key format changes

    // In-memory cache for recently accessed items
    private var memoryCache = NSCache<NSString, NSData>()

    private init() {
        // Create cache directory
        let cachesDir = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first!
        cacheDirectory = cachesDir.appendingPathComponent("SyncFlowAttachments", isDirectory: true)

        try? fileManager.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)

        // Configure memory cache
        memoryCache.countLimit = 50 // Max 50 items in memory
        memoryCache.totalCostLimit = 100 * 1024 * 1024 // 100 MB

        // Check if cache version changed and clear if needed
        let versionKey = "attachmentCacheVersion"
        let storedVersion = UserDefaults.standard.integer(forKey: versionKey)
        if storedVersion != cacheVersion {
            clearCache()
            UserDefaults.standard.set(cacheVersion, forKey: versionKey)
            print("[Cache] Cleared cache due to version change (v\(storedVersion) -> v\(cacheVersion))")
        } else {
            // Clean old cache entries on init
            Task {
                await cleanOldCache()
            }
        }
    }

    // MARK: - Cache Key Generation

    private func cacheKey(for urlString: String) -> String {
        // Use SHA256 hash for unique, fixed-length cache keys
        // This prevents collisions that could occur with truncated base64
        guard let data = urlString.data(using: .utf8) else {
            return "fallback_\(urlString.hashValue)"
        }
        let hash = SHA256.hash(data: data)
        let hashString = hash.compactMap { String(format: "%02x", $0) }.joined()
        return hashString
    }

    private func cacheURL(for urlString: String) -> URL {
        let key = cacheKey(for: urlString)
        return cacheDirectory.appendingPathComponent(key)
    }

    // MARK: - Public API

    /// Get cached data for a URL (checks memory first, then disk)
    func getCachedData(for urlString: String) -> Data? {
        let key = cacheKey(for: urlString) as NSString

        // Check memory cache first
        if let data = memoryCache.object(forKey: key) {
            return data as Data
        }

        // Check disk cache
        let fileURL = cacheURL(for: urlString)
        if fileManager.fileExists(atPath: fileURL.path) {
            if let data = try? Data(contentsOf: fileURL) {
                // Store in memory cache for faster subsequent access
                memoryCache.setObject(data as NSData, forKey: key, cost: data.count)
                return data
            }
        }

        return nil
    }

    /// Cache data to both memory and disk
    func cacheData(_ data: Data, for urlString: String) {
        let key = cacheKey(for: urlString) as NSString
        let fileURL = cacheURL(for: urlString)

        // Store in memory cache
        memoryCache.setObject(data as NSData, forKey: key, cost: data.count)

        // Store on disk asynchronously
        Task {
            do {
                try data.write(to: fileURL)
            } catch {
                print("Failed to cache attachment: \(error)")
            }
        }
    }

    /// Load data from URL with caching
    func loadData(from urlString: String) async throws -> Data {
        // Check cache first
        if let cachedData = getCachedData(for: urlString) {
            return cachedData
        }

        // Download if not cached
        guard let url = URL(string: urlString) else {
            throw URLError(.badURL)
        }
        let (data, _) = try await URLSession.shared.data(from: url)

        // Cache the downloaded data
        cacheData(data, for: urlString)

        return data
    }

    /// Load and cache an image
    func loadImage(from urlString: String, decrypt: Bool = true) async throws -> NSImage? {
        var data = try await loadData(from: urlString)

        // Decrypt if needed
        if decrypt {
            if let decrypted = try? E2EEManager.shared.decryptData(data) {
                data = decrypted
            }
        }

        return NSImage(data: data)
    }

    // MARK: - Cache Management

    /// Clean cache entries older than maxCacheAge (or override)
    func cleanOldCache(olderThan maxAgeOverride: TimeInterval? = nil) async {
        do {
            let files = try fileManager.contentsOfDirectory(
                at: cacheDirectory,
                includingPropertiesForKeys: [.creationDateKey, .fileSizeKey]
            )

            let now = Date()
            var totalSize: Int64 = 0
            var filesToDelete: [URL] = []

            for fileURL in files {
                let attributes = try fileURL.resourceValues(forKeys: [.creationDateKey, .fileSizeKey])

                if let creationDate = attributes.creationDate {
                    let ageLimit = maxAgeOverride ?? maxCacheAge
                    let age = now.timeIntervalSince(creationDate)
                    if age > ageLimit {
                        filesToDelete.append(fileURL)
                        continue
                    }
                }

                if let size = attributes.fileSize {
                    totalSize += Int64(size)
                }
            }

            // Delete old files
            for fileURL in filesToDelete {
                try? fileManager.removeItem(at: fileURL)
            }

            // If still over max size, delete oldest files
            if totalSize > cacheSizeLimit {
                await trimCacheToSize(cacheSizeLimit)
            }

            if !filesToDelete.isEmpty {
                print("[Cache] Cleaned \(filesToDelete.count) old files")
            }
        } catch {
            print("[Cache] Error cleaning cache: \(error)")
        }
    }

    /// Trim cache to specified size by removing oldest files
    private func trimCacheToSize(_ targetSize: Int64) async {
        do {
            let files = try fileManager.contentsOfDirectory(
                at: cacheDirectory,
                includingPropertiesForKeys: [.creationDateKey, .fileSizeKey]
            )

            // Sort by creation date (oldest first)
            let sortedFiles = try files.sorted { url1, url2 in
                let date1 = try url1.resourceValues(forKeys: [.creationDateKey]).creationDate ?? Date.distantPast
                let date2 = try url2.resourceValues(forKeys: [.creationDateKey]).creationDate ?? Date.distantPast
                return date1 < date2
            }

            var currentSize: Int64 = 0
            for fileURL in sortedFiles {
                if let size = try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                    currentSize += Int64(size)
                }
            }

            // Delete oldest files until under target size
            for fileURL in sortedFiles {
                if currentSize <= targetSize {
                    break
                }

                if let size = try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                    try? fileManager.removeItem(at: fileURL)
                    currentSize -= Int64(size)
                }
            }
        } catch {
            print("[Cache] Error trimming cache: \(error)")
        }
    }

    /// Clear all cached data
    func clearCache() {
        memoryCache.removeAllObjects()

        do {
            let files = try fileManager.contentsOfDirectory(at: cacheDirectory, includingPropertiesForKeys: nil)
            for file in files {
                try? fileManager.removeItem(at: file)
            }
            print("[Cache] Cache cleared")
        } catch {
            print("[Cache] Error clearing cache: \(error)")
        }
    }

    /// Clear everything in memory and disk cache
    func clearAll() {
        clearCache()
    }

    /// Remove cache entries older than the specified duration
    func clearOldEntries(olderThan seconds: TimeInterval) async {
        await cleanOldCache(olderThan: seconds)
    }

    /// Set a new max cache size and trim the cache to it
    func setMaxCacheSize(_ size: Int) {
        let sanitized = Int64(max(size, 1))
        cacheSizeLimit = sanitized
        Task {
            await trimCacheToSize(sanitized)
        }
    }

    /// Get current cache size
    func getCacheSize() -> Int64 {
        do {
            let files = try fileManager.contentsOfDirectory(
                at: cacheDirectory,
                includingPropertiesForKeys: [.fileSizeKey]
            )

            var totalSize: Int64 = 0
            for fileURL in files {
                if let size = try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                    totalSize += Int64(size)
                }
            }
            return totalSize
        } catch {
            return 0
        }
    }

    /// Format cache size for display
    func formattedCacheSize() -> String {
        let size = getCacheSize()
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: size)
    }
}
