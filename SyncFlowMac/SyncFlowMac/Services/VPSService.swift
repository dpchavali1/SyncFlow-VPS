/**
 * VPSService — Central VPS Communication Layer (Core)
 *
 * This singleton service manages all communication with the SyncFlow VPS server,
 * providing both REST API and WebSocket connectivity. It serves as the network
 * backbone for the entire macOS application.
 *
 * This file contains:
 * - Class definition, properties, and singleton initializer
 * - Keychain-backed token management (access + refresh tokens)
 * - HTTP request helpers (GET, POST, PUT, DELETE with auto-retry)
 * - Automatic token refresh on 401 responses
 * - Rate-limit retry with backoff on 429 responses
 *
 * Extension files provide additional functionality:
 * - VPSService+Auth.swift       — Pairing, login, QR code generation
 * - VPSService+WebSocket.swift  — Real-time WebSocket connection & message dispatch
 * - VPSService+Messages.swift   — Messages, contacts, calls, WebRTC signaling
 * - VPSService+Devices.swift    — Device management
 * - VPSService+Features.swift   — E2EE, spam, billing, files, photos, and more
 *
 * Model types are in VPSModels.swift.
 */

import Foundation
import Combine

// MARK: - VPS Service

public class VPSService: NSObject, ObservableObject {

    public static let shared = VPSService()

    // ── Configuration ───────────────────────────────────────────────────────
    let baseUrl: String
    private let wsUrl: String
    let wsUrlFallback: String?
    var activeWsUrl: String
    var didFallbackToInsecureWebSocket = false
    let session: URLSession
    let decoder = JSONDecoder()
    let encoder = JSONEncoder()

    // ── Keychain keys ───────────────────────────────────────────────────────
    private let keychainAccessToken = "com.syncflow.vps.accessToken"
    private let keychainRefreshToken = "com.syncflow.vps.refreshToken"
    private let keychainUserId = "com.syncflow.vps.userId"
    private let keychainDeviceId = "com.syncflow.vps.deviceId"

    // ── Authentication state ────────────────────────────────────────────────
    private(set) var accessToken: String?
    private(set) var refreshToken: String?
    @Published public private(set) var userId: String?
    @Published public private(set) var deviceId: String?
    @Published public private(set) var isAuthenticated: Bool = false

    /// Returns "Bearer <token>" if authenticated, nil otherwise.
    public var authorizationHeader: String? {
        guard let token = accessToken else { return nil }
        return "Bearer \(token)"
    }
    @Published public var isConnected: Bool = false

    // ── WebSocket state ─────────────────────────────────────────────────────
    var webSocketTask: URLSessionWebSocketTask?
    var subscriptions: Set<String> = []
    var reconnectTimer: Timer?
    var reconnectAttempts = 0
    let maxReconnectAttempts = 100
    var pendingReconnectWork: DispatchWorkItem?

    // ── Plan refresh timer (checks subscription expiry every 30 minutes) ────
    var planRefreshTimer: Timer?

    // ── Publishers for real-time updates ────────────────────────────────────
    public let messageAdded = PassthroughSubject<VPSMessage, Never>()
    public let messageUpdated = PassthroughSubject<VPSMessage, Never>()
    public let messageDeleted = PassthroughSubject<String, Never>()
    public let contactAdded = PassthroughSubject<VPSContact, Never>()
    public let contactUpdated = PassthroughSubject<VPSContact, Never>()
    public let contactDeleted = PassthroughSubject<String, Never>()
    public let callAdded = PassthroughSubject<VPSCallHistoryEntry, Never>()
    public let contactsSynced = PassthroughSubject<Int, Never>()  // count of synced contacts
    public let callsSynced = PassthroughSubject<Int, Never>()     // count of synced calls
    public let deviceRemoved = PassthroughSubject<String, Never>()

    // ── Publishers for service sync ────────────────────────────────────────
    public let mediaStatusUpdated = PassthroughSubject<[String: Any], Never>()
    public let phoneStatusUpdated = PassthroughSubject<[String: Any], Never>()
    public let clipboardUpdated = PassthroughSubject<[String: Any], Never>()
    public let dndStatusUpdated = PassthroughSubject<[String: Any], Never>()
    public let hotspotStatusUpdated = PassthroughSubject<[String: Any], Never>()
    public let voicemailUpdated = PassthroughSubject<[String: Any], Never>()
    public let spamUpdated = PassthroughSubject<[String: Any], Never>()
    public let deliveryStatusChanged = PassthroughSubject<(String, String), Never>() // (messageId, deliveryStatus)
    public let activeCallUpdated = PassthroughSubject<[String: Any], Never>() // Phone call state from Android

    /// Set to true when server pushes e2ee_key_available for this device
    public var e2eeKeyPushReceived = false

    // MARK: - Initializer

    private override init() {
        // Default to VPS server
        self.baseUrl = ProcessInfo.processInfo.environment["SYNCFLOW_VPS_URL"] ?? "https://api.sfweb.app"
        if let override = ProcessInfo.processInfo.environment["SYNCFLOW_VPS_WS_URL"], !override.isEmpty {
            self.wsUrl = override
        } else {
            // WebSocket runs on the same port as HTTP, through nginx reverse proxy
            self.wsUrl = self.baseUrl.replacingOccurrences(of: "https://", with: "wss://")
                                     .replacingOccurrences(of: "http://", with: "ws://")
        }
        #if DEBUG
        if wsUrl.hasPrefix("wss://") {
            self.wsUrlFallback = wsUrl.replacingOccurrences(of: "wss://", with: "ws://")
        } else {
            self.wsUrlFallback = nil
        }
        #else
        self.wsUrlFallback = nil
        #endif
        self.activeWsUrl = wsUrl
        self.session = URLSession.shared

        super.init()

        // Restore tokens from Keychain
        restoreTokens()
    }

    // MARK: - Token Management

    private func restoreTokens() {
        accessToken = loadFromKeychain(keychainAccessToken)
        refreshToken = loadFromKeychain(keychainRefreshToken)
        userId = loadFromKeychain(keychainUserId)
        deviceId = loadFromKeychain(keychainDeviceId)

        isAuthenticated = accessToken != nil && userId != nil

        if isAuthenticated {
            Logger.info("Restored authentication for user \(userId ?? "unknown")", category: .vps)
        }

        // Connect WebSocket if we have valid tokens
        if isAuthenticated {
            connectWebSocket()
        }
    }

    func saveTokens(accessToken: String, refreshToken: String, userId: String, deviceId: String) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.userId = userId
        self.deviceId = deviceId
        self.isAuthenticated = true

        saveToKeychain(keychainAccessToken, value: accessToken)
        saveToKeychain(keychainRefreshToken, value: refreshToken)
        saveToKeychain(keychainUserId, value: userId)
        saveToKeychain(keychainDeviceId, value: deviceId)
    }

    public func clearTokens() {
        accessToken = nil
        refreshToken = nil
        userId = nil
        deviceId = nil
        isAuthenticated = false

        deleteFromKeychain(keychainAccessToken)
        deleteFromKeychain(keychainRefreshToken)
        deleteFromKeychain(keychainUserId)
        deleteFromKeychain(keychainDeviceId)

        disconnectWebSocket()
    }

    private func loadFromKeychain(_ key: String) -> String? {
        guard let data = KeychainHelper.shared.load(key: key),
              let string = String(data: data, encoding: .utf8) else {
            return nil
        }
        return string
    }

    private func saveToKeychain(_ key: String, value: String) {
        guard let data = value.data(using: .utf8) else { return }
        _ = KeychainHelper.shared.save(key: key, data: data)
    }

    private func deleteFromKeychain(_ key: String) {
        _ = KeychainHelper.shared.delete(key: key)
    }

    // MARK: - HTTP Helpers

    /// Core HTTP request method with automatic token refresh (401) and rate-limit retry (429).
    /// All extension files use the convenience wrappers (get/post/put/delete) below.
    func request<T: Decodable>(
        _ method: String,
        _ path: String,
        body: [String: Any]? = nil,
        skipAuth: Bool = false,
        retryCount: Int = 0
    ) async throws -> T {
        guard let url = URL(string: baseUrl + path) else {
            throw VPSError.invalidResponse
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if !skipAuth {
            guard let token = accessToken else {
                throw VPSError.notAuthenticated
            }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if let body = body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }

        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw VPSError.invalidResponse
        }

        if httpResponse.statusCode == 401 && !skipAuth {
            guard retryCount < 1 else {
                throw VPSError.httpError(401, "Unauthorized after token refresh")
            }
            // Try to refresh token
            try await refreshAccessToken()
            return try await self.request(method, path, body: body, skipAuth: false, retryCount: retryCount + 1)
        }

        if httpResponse.statusCode == 429 {
            if retryCount >= 3 {
                let errorMessage = String(data: data, encoding: .utf8)
                throw VPSError.httpError(httpResponse.statusCode, errorMessage)
            }

            var retryAfterSeconds: Double = 1
            if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let retryAfter = json["retryAfter"] as? Double {
                retryAfterSeconds = max(0.5, retryAfter)
            } else if let retryHeader = httpResponse.value(forHTTPHeaderField: "Retry-After"),
                      let retry = Double(retryHeader) {
                retryAfterSeconds = max(0.5, retry)
            }

            Logger.warning("Rate limited (429). Retrying in \(retryAfterSeconds)s (attempt \(retryCount + 1))", category: .vps)
            try await Task.sleep(nanoseconds: UInt64(retryAfterSeconds * 1_000_000_000))
            return try await self.request(method, path, body: body, skipAuth: skipAuth, retryCount: retryCount + 1)
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            let errorMessage = String(data: data, encoding: .utf8)
            throw VPSError.httpError(httpResponse.statusCode, errorMessage)
        }

        return try decoder.decode(T.self, from: data)
    }

    /// Convenience: GET request with auto-decode
    func get<T: Decodable>(_ path: String) async throws -> T {
        return try await request("GET", path)
    }

    /// Convenience: POST request with JSON body
    func post<T: Decodable>(_ path: String, body: [String: Any], skipAuth: Bool = false) async throws -> T {
        return try await request("POST", path, body: body, skipAuth: skipAuth)
    }

    /// Convenience: PUT request with optional JSON body
    func put<T: Decodable>(_ path: String, body: [String: Any]?) async throws -> T {
        return try await request("PUT", path, body: body)
    }

    /// Convenience: DELETE request
    func delete<T: Decodable>(_ path: String) async throws -> T {
        return try await request("DELETE", path)
    }

    // MARK: - Token Refresh

    func refreshAccessToken() async throws {
        guard let currentRefreshToken = refreshToken else {
            throw VPSError.notAuthenticated
        }

        let body: [String: Any] = ["refreshToken": currentRefreshToken]
        let response: VPSTokenPair = try await request("POST", "/api/auth/refresh", body: body, skipAuth: true)

        accessToken = response.accessToken
        refreshToken = response.refreshToken

        saveToKeychain(keychainAccessToken, value: response.accessToken)
        saveToKeychain(keychainRefreshToken, value: response.refreshToken)
    }
}
