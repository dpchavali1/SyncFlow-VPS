import Foundation

/// IncrementalSyncManager - Legacy bandwidth optimization
/// Now a no-op since VPS WebSocket handles real-time sync.
/// Cache methods are preserved for local message caching.
class IncrementalSyncManager {
    static let shared = IncrementalSyncManager()

    private let userDefaults = UserDefaults.standard
    private let syncStatePrefix = "sync_state_"
    private let cachePrefix = "cache_"

    /// Message delta events (added/changed/removed)
    enum MessageDelta {
        case added(Message)
        case changed(Message)
        case removed(String)
    }

    /// Sync state for tracking last sync timestamp
    struct SyncState: Codable {
        let userId: String
        let dataType: String
        let lastSyncTimestamp: Double
        let itemCount: Int
        let lastSyncDate: String
    }

    private init() {}

    // MARK: - Cache Methods (still useful for local caching)

    func getCachedMessages(userId: String) -> [Message] {
        let cacheKey = "\(cachePrefix)\(userId)_messages"

        guard let data = userDefaults.data(forKey: cacheKey) else {
            return []
        }

        do {
            let messages = try JSONDecoder().decode([Message].self, from: data)
            return messages
        } catch {
            print("[IncrementalSync] Error loading cached messages: \(error)")
            return []
        }
    }

    func getLastSyncTimestamp(userId: String, dataType: String = "messages") -> Double {
        let stateKey = "\(syncStatePrefix)\(userId)_\(dataType)"
        return userDefaults.double(forKey: stateKey)
    }

    func clearCache(userId: String) {
        let stateKey = "\(syncStatePrefix)\(userId)_messages"
        let cacheKey = "\(cachePrefix)\(userId)_messages"

        userDefaults.removeObject(forKey: stateKey)
        userDefaults.removeObject(forKey: cacheKey)
    }

    func getStats(userId: String) -> [String: Any] {
        let syncTimestamp = getLastSyncTimestamp(userId: userId)
        let cachedMessages = getCachedMessages(userId: userId)

        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        let lastSyncDate = syncTimestamp > 0 ? dateFormatter.string(from: Date(timeIntervalSince1970: syncTimestamp / 1000)) : "Never synced"

        return [
            "lastSyncTimestamp": syncTimestamp,
            "cachedMessageCount": cachedMessages.count,
            "lastSyncDate": lastSyncDate
        ]
    }
}
