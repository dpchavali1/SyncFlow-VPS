/**
 * SyncFlow VPS Client - macOS/Swift
 *
 * Usage:
 *   let client = SyncFlowApiClient(baseUrl: "https://api.sfweb.app")
 *   try await client.authenticateAnonymous()
 *   let messages = try await client.getMessages()
 */

import Foundation

// MARK: - Models

public struct TokenPair: Codable {
    public let accessToken: String
    public let refreshToken: String
}

public struct User: Codable {
    public let userId: String
    public let deviceId: String
    public let admin: Bool?
}

public struct AuthResponse: Codable {
    public let userId: String
    public let deviceId: String
    public let accessToken: String
    public let refreshToken: String
}

public struct PairingRequest: Codable {
    public let pairingToken: String
    public let deviceId: String
    public let tempUserId: String
    public let accessToken: String
    public let refreshToken: String
}

public struct PairingStatus: Codable {
    public let status: String
    public let deviceName: String?
    public let approved: Bool
}

public struct Message: Codable {
    public let id: String
    public let threadId: Int?
    public let address: String
    public let contactName: String?
    public let body: String?
    public let date: Int64
    public let type: Int
    public let read: Bool
    public let isMms: Bool
    public let encrypted: Bool?
}

public struct MessagesResponse: Codable {
    public let messages: [Message]
    public let hasMore: Bool
}

public struct SyncResponse: Codable {
    public let synced: Int
    public let skipped: Int
    public let total: Int?
}

public struct Contact: Codable {
    public let id: String
    public let displayName: String?
    public let phoneNumbers: [String]?
    public let emails: [String]?
    public let photoThumbnail: String?
}

public struct ContactsResponse: Codable {
    public let contacts: [Contact]
}

public struct CallHistoryEntry: Codable {
    public let id: String
    public let phoneNumber: String
    public let contactName: String?
    public let callType: String
    public let callDate: Int64
    public let duration: Int
    public let simSubscriptionId: Int?
}

public struct CallsResponse: Codable {
    public let calls: [CallHistoryEntry]
    public let hasMore: Bool
}

public struct Device: Codable {
    public let id: String
    public let name: String?
    public let deviceType: String
    public let pairedAt: String
    public let lastSeen: String
    public let isCurrent: Bool
}

public struct DevicesResponse: Codable {
    public let devices: [Device]
}

public struct OutgoingMessage: Codable {
    public let id: String
    public let address: String
    public let body: String
    public let timestamp: Int64
    public let simSubscriptionId: Int?
}

public struct CallRequest: Codable {
    public let id: String
    public let phoneNumber: String
    public let status: String
    public let requestedAt: Int64
    public let simSubscriptionId: Int?
}

// MARK: - WebSocket Delegate

public protocol SyncFlowWebSocketDelegate: AnyObject {
    func onConnected()
    func onDisconnected()
    func onError(_ error: Error)
    func onMessageAdded(_ message: Message)
    func onMessageUpdated(_ message: Message)
    func onMessageDeleted(_ messageId: String)
    func onContactAdded(_ contact: Contact)
    func onContactUpdated(_ contact: Contact)
    func onContactDeleted(_ contactId: String)
    func onCallAdded(_ call: CallHistoryEntry)
    func onOutgoingMessage(_ message: OutgoingMessage)
    func onCallRequest(_ request: CallRequest)
}

// MARK: - API Error

public enum SyncFlowError: Error {
    case notAuthenticated
    case invalidResponse
    case httpError(Int, String?)
    case networkError(Error)
}

// MARK: - API Client

public class SyncFlowApiClient: NSObject {
    private let baseUrl: String
    private let wsUrl: String
    private let session: URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    private var accessToken: String?
    private var refreshToken: String?
    public private(set) var userId: String?
    public private(set) var deviceId: String?

    private var webSocketTask: URLSessionWebSocketTask?
    public weak var webSocketDelegate: SyncFlowWebSocketDelegate?
    private var subscriptions: Set<String> = []

    public init(baseUrl: String = "http://5.78.188.206") {
        self.baseUrl = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        self.wsUrl = baseUrl.replacingOccurrences(of: "http", with: "ws") + ":3001"
        self.session = URLSession.shared
        super.init()
    }

    // MARK: - Authentication

    public func authenticateAnonymous() async throws -> User {
        let response: AuthResponse = try await post("/api/auth/anonymous", body: [:] as [String: String])
        setTokens(accessToken: response.accessToken, refreshToken: response.refreshToken)
        userId = response.userId
        deviceId = response.deviceId
        return User(userId: response.userId, deviceId: response.deviceId, admin: false)
    }

    public func initiatePairing(deviceName: String, deviceType: String) async throws -> PairingRequest {
        let body = ["deviceName": deviceName, "deviceType": deviceType]
        let response: PairingRequest = try await post("/api/auth/pair/initiate", body: body)
        setTokens(accessToken: response.accessToken, refreshToken: response.refreshToken)
        deviceId = response.deviceId
        return response
    }

    public func checkPairingStatus(token: String) async throws -> PairingStatus {
        return try await get("/api/auth/pair/status/\(token)")
    }

    public func completePairing(token: String) async throws {
        let _: [String: Bool] = try await post("/api/auth/pair/complete", body: ["token": token])
    }

    public func redeemPairing(token: String, deviceName: String? = nil, deviceType: String? = nil) async throws -> User {
        var body: [String: String] = ["token": token]
        if let name = deviceName { body["deviceName"] = name }
        if let type = deviceType { body["deviceType"] = type }

        let response: AuthResponse = try await post("/api/auth/pair/redeem", body: body)
        setTokens(accessToken: response.accessToken, refreshToken: response.refreshToken)
        userId = response.userId
        deviceId = response.deviceId
        return User(userId: response.userId, deviceId: response.deviceId, admin: false)
    }

    public func refreshAccessToken() async throws {
        guard let refresh = refreshToken else {
            throw SyncFlowError.notAuthenticated
        }

        let body = ["refreshToken": refresh]
        let response: [String: String] = try await post("/api/auth/refresh", body: body, skipAuth: true)
        accessToken = response["accessToken"]
    }

    public func getCurrentUser() async throws -> User {
        return try await get("/api/auth/me")
    }

    public func setTokens(accessToken: String, refreshToken: String) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
    }

    public func getTokens() -> TokenPair? {
        guard let access = accessToken, let refresh = refreshToken else { return nil }
        return TokenPair(accessToken: access, refreshToken: refresh)
    }

    // MARK: - Messages

    public func getMessages(limit: Int = 100, before: Int64? = nil, after: Int64? = nil, threadId: Int? = nil) async throws -> MessagesResponse {
        var params = ["limit": "\(limit)"]
        if let before = before { params["before"] = "\(before)" }
        if let after = after { params["after"] = "\(after)" }
        if let threadId = threadId { params["threadId"] = "\(threadId)" }

        let query = params.map { "\($0.key)=\($0.value)" }.joined(separator: "&")
        return try await get("/api/messages?\(query)")
    }

    public func syncMessages(_ messages: [[String: Any]]) async throws -> SyncResponse {
        return try await post("/api/messages/sync", body: ["messages": messages])
    }

    public func sendMessage(address: String, body: String, simSubscriptionId: Int? = nil) async throws -> [String: String] {
        var requestBody: [String: Any] = ["address": address, "body": body]
        if let sim = simSubscriptionId { requestBody["simSubscriptionId"] = sim }
        return try await post("/api/messages/send", body: requestBody)
    }

    public func getOutgoingMessages() async throws -> [OutgoingMessage] {
        let response: [String: [OutgoingMessage]] = try await get("/api/messages/outgoing")
        return response["messages"] ?? []
    }

    public func updateOutgoingStatus(id: String, status: String, error: String? = nil) async throws {
        var body: [String: String] = ["status": status]
        if let error = error { body["error"] = error }
        let _: [String: Bool] = try await put("/api/messages/outgoing/\(id)/status", body: body)
    }

    public func markMessageRead(id: String) async throws {
        let _: [String: Bool] = try await put("/api/messages/\(id)/read", body: [:] as [String: String])
    }

    // MARK: - Contacts

    public func getContacts(search: String? = nil, limit: Int = 500) async throws -> ContactsResponse {
        var params = ["limit": "\(limit)"]
        if let search = search { params["search"] = search }

        let query = params.map { "\($0.key)=\($0.value)" }.joined(separator: "&")
        return try await get("/api/contacts?\(query)")
    }

    public func syncContacts(_ contacts: [[String: Any]]) async throws -> SyncResponse {
        return try await post("/api/contacts/sync", body: ["contacts": contacts])
    }

    public func getContact(id: String) async throws -> Contact {
        return try await get("/api/contacts/\(id)")
    }

    // MARK: - Call History

    public func getCallHistory(limit: Int = 100, before: Int64? = nil, after: Int64? = nil, type: String? = nil) async throws -> CallsResponse {
        var params = ["limit": "\(limit)"]
        if let before = before { params["before"] = "\(before)" }
        if let after = after { params["after"] = "\(after)" }
        if let type = type { params["type"] = type }

        let query = params.map { "\($0.key)=\($0.value)" }.joined(separator: "&")
        return try await get("/api/calls?\(query)")
    }

    public func syncCallHistory(_ calls: [[String: Any]]) async throws -> SyncResponse {
        return try await post("/api/calls/sync", body: ["calls": calls])
    }

    public func requestCall(phoneNumber: String, simSubscriptionId: Int? = nil) async throws -> [String: String] {
        var body: [String: Any] = ["phoneNumber": phoneNumber]
        if let sim = simSubscriptionId { body["simSubscriptionId"] = sim }
        return try await post("/api/calls/request", body: body)
    }

    public func getCallRequests() async throws -> [CallRequest] {
        let response: [String: [CallRequest]] = try await get("/api/calls/requests")
        return response["requests"] ?? []
    }

    public func updateCallRequestStatus(id: String, status: String) async throws {
        let _: [String: Bool] = try await put("/api/calls/requests/\(id)/status", body: ["status": status])
    }

    // MARK: - Devices

    public func getDevices() async throws -> DevicesResponse {
        return try await get("/api/devices")
    }

    public func updateDevice(id: String, name: String? = nil, fcmToken: String? = nil) async throws {
        var body: [String: String] = [:]
        if let name = name { body["name"] = name }
        if let token = fcmToken { body["fcmToken"] = token }
        let _: [String: Bool] = try await put("/api/devices/\(id)", body: body)
    }

    public func removeDevice(id: String) async throws {
        try await delete("/api/devices/\(id)")
    }

    // MARK: - WebSocket

    public func connectWebSocket() {
        guard let token = accessToken else {
            webSocketDelegate?.onError(SyncFlowError.notAuthenticated)
            return
        }

        guard let url = URL(string: "\(wsUrl)?token=\(token)") else { return }

        webSocketTask = session.webSocketTask(with: url)
        webSocketTask?.resume()

        webSocketDelegate?.onConnected()
        receiveMessage()

        // Resubscribe
        for channel in subscriptions {
            sendWebSocketMessage(["type": "subscribe", "channel": channel])
        }
    }

    public func disconnectWebSocket() {
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
    }

    public func subscribe(_ channel: String) {
        subscriptions.insert(channel)
        sendWebSocketMessage(["type": "subscribe", "channel": channel])
    }

    public func unsubscribe(_ channel: String) {
        subscriptions.remove(channel)
        sendWebSocketMessage(["type": "unsubscribe", "channel": channel])
    }

    private func sendWebSocketMessage(_ message: [String: String]) {
        guard let data = try? JSONSerialization.data(withJSONObject: message),
              let string = String(data: data, encoding: .utf8) else { return }

        webSocketTask?.send(.string(string)) { _ in }
    }

    private func receiveMessage() {
        webSocketTask?.receive { [weak self] result in
            switch result {
            case .success(let message):
                if case .string(let text) = message {
                    self?.handleWebSocketMessage(text)
                }
                self?.receiveMessage()

            case .failure(let error):
                self?.webSocketDelegate?.onError(error)
                self?.webSocketDelegate?.onDisconnected()
            }
        }
    }

    private func handleWebSocketMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else { return }

        let messageData = json["data"]

        switch type {
        case "message_added":
            if let msg = decodeFromAny(Message.self, from: messageData) {
                webSocketDelegate?.onMessageAdded(msg)
            }
        case "message_updated":
            if let msg = decodeFromAny(Message.self, from: messageData) {
                webSocketDelegate?.onMessageUpdated(msg)
            }
        case "message_deleted":
            if let data = messageData as? [String: Any], let id = data["id"] as? String {
                webSocketDelegate?.onMessageDeleted(id)
            }
        case "contact_added":
            if let contact = decodeFromAny(Contact.self, from: messageData) {
                webSocketDelegate?.onContactAdded(contact)
            }
        case "contact_updated":
            if let contact = decodeFromAny(Contact.self, from: messageData) {
                webSocketDelegate?.onContactUpdated(contact)
            }
        case "contact_deleted":
            if let data = messageData as? [String: Any], let id = data["id"] as? String {
                webSocketDelegate?.onContactDeleted(id)
            }
        case "call_added":
            if let call = decodeFromAny(CallHistoryEntry.self, from: messageData) {
                webSocketDelegate?.onCallAdded(call)
            }
        case "outgoing_message":
            if let msg = decodeFromAny(OutgoingMessage.self, from: messageData) {
                webSocketDelegate?.onOutgoingMessage(msg)
            }
        case "call_request":
            if let req = decodeFromAny(CallRequest.self, from: messageData) {
                webSocketDelegate?.onCallRequest(req)
            }
        default:
            break
        }
    }

    private func decodeFromAny<T: Decodable>(_ type: T.Type, from value: Any?) -> T? {
        guard let value = value,
              let data = try? JSONSerialization.data(withJSONObject: value) else { return nil }
        return try? decoder.decode(type, from: data)
    }

    // MARK: - HTTP Helpers

    private func get<T: Decodable>(_ path: String) async throws -> T {
        var request = URLRequest(url: URL(string: "\(baseUrl)\(path)")!)
        request.httpMethod = "GET"
        if let token = accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return try await executeRequest(request)
    }

    private func post<T: Decodable>(_ path: String, body: Any, skipAuth: Bool = false) async throws -> T {
        var request = URLRequest(url: URL(string: "\(baseUrl)\(path)")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !skipAuth, let token = accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await executeRequest(request)
    }

    private func put<T: Decodable>(_ path: String, body: Any) async throws -> T {
        var request = URLRequest(url: URL(string: "\(baseUrl)\(path)")!)
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await executeRequest(request)
    }

    private func delete(_ path: String) async throws {
        var request = URLRequest(url: URL(string: "\(baseUrl)\(path)")!)
        request.httpMethod = "DELETE"
        if let token = accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let (_, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode) else {
            throw SyncFlowError.httpError((response as? HTTPURLResponse)?.statusCode ?? 0, nil)
        }
    }

    private func executeRequest<T: Decodable>(_ request: URLRequest) async throws -> T {
        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw SyncFlowError.invalidResponse
        }

        if httpResponse.statusCode == 401, refreshToken != nil {
            try await refreshAccessToken()
            var retryRequest = request
            retryRequest.setValue("Bearer \(accessToken!)", forHTTPHeaderField: "Authorization")
            let (retryData, retryResponse) = try await session.data(for: retryRequest)

            guard let retryHttpResponse = retryResponse as? HTTPURLResponse,
                  (200...299).contains(retryHttpResponse.statusCode) else {
                throw SyncFlowError.httpError((retryResponse as? HTTPURLResponse)?.statusCode ?? 0, nil)
            }

            return try decoder.decode(T.self, from: retryData)
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            let errorBody = String(data: data, encoding: .utf8)
            throw SyncFlowError.httpError(httpResponse.statusCode, errorBody)
        }

        return try decoder.decode(T.self, from: data)
    }
}
