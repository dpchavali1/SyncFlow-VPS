/**
 * SyncFlow Service - macOS Application Integration
 *
 * High-level service that wraps SyncFlowApiClient with:
 * - Token persistence (Keychain)
 * - Auto-reconnection
 * - Combine publishers for state management
 */

import Foundation
import Combine
import Security

// MARK: - Keychain Helper

private class KeychainHelper {
    static let service = "com.syncflow.vps"

    static func save(_ value: String, forKey key: String) {
        let data = value.data(using: .utf8)!

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecValueData as String: data
        ]

        SecItemDelete(query as CFDictionary)
        SecItemAdd(query as CFDictionary, nil)
    }

    static func get(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        if status == errSecSuccess, let data = result as? Data {
            return String(data: data, encoding: .utf8)
        }
        return nil
    }

    static func delete(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }

    static func clear() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service
        ]
        SecItemDelete(query as CFDictionary)
    }
}

// MARK: - State

public struct SyncFlowState {
    public var isAuthenticated: Bool = false
    public var isConnected: Bool = false
    public var isPaired: Bool = false
    public var userId: String?
    public var deviceId: String?
    public var messages: [Message] = []
    public var contacts: [Contact] = []
    public var calls: [CallHistoryEntry] = []
    public var devices: [Device] = []
}

// MARK: - Service

public class SyncFlowService: ObservableObject, SyncFlowWebSocketDelegate {
    public static let shared = SyncFlowService()

    private let client: SyncFlowApiClient
    private var reconnectTask: Task<Void, Never>?

    @Published public private(set) var state = SyncFlowState()

    // Event publishers
    public let messageReceived = PassthroughSubject<Message, Never>()
    public let connectionChanged = PassthroughSubject<Bool, Never>()

    private init(apiUrl: String = "http://5.78.188.206") {
        self.client = SyncFlowApiClient(baseUrl: apiUrl)
        self.client.webSocketDelegate = self
    }

    // MARK: - Initialization

    @MainActor
    public func initialize() async -> Bool {
        guard let accessToken = KeychainHelper.get("accessToken"),
              let refreshToken = KeychainHelper.get("refreshToken") else {
            return false
        }

        client.setTokens(accessToken: accessToken, refreshToken: refreshToken)
        state.userId = KeychainHelper.get("userId")
        state.deviceId = KeychainHelper.get("deviceId")

        do {
            _ = try await client.getCurrentUser()
            state.isAuthenticated = true
            state.isPaired = true
            connectWebSocket()
            return true
        } catch {
            clearSession()
            return false
        }
    }

    // MARK: - Authentication

    @MainActor
    public func authenticateAnonymous() async throws -> User {
        let user = try await client.authenticateAnonymous()
        saveSession(user: user)
        state.isAuthenticated = true
        state.userId = user.userId
        state.deviceId = user.deviceId
        return user
    }

    @MainActor
    public func initiatePairing(deviceName: String) async throws -> String {
        let result = try await client.initiatePairing(deviceName: deviceName, deviceType: "macos")
        state.deviceId = result.deviceId
        return result.pairingToken
    }

    public func checkPairingStatus(token: String) async throws -> Bool {
        let status = try await client.checkPairingStatus(token: token)
        return status.approved
    }

    @MainActor
    public func redeemPairing(token: String) async throws -> User {
        let user = try await client.redeemPairing(token: token, deviceName: nil, deviceType: "macos")
        saveSession(user: user)
        state.isAuthenticated = true
        state.isPaired = true
        state.userId = user.userId
        state.deviceId = user.deviceId
        connectWebSocket()
        return user
    }

    @MainActor
    public func logout() {
        client.disconnectWebSocket()
        clearSession()
        state = SyncFlowState()
    }

    private func saveSession(user: User) {
        guard let tokens = client.getTokens() else { return }
        KeychainHelper.save(tokens.accessToken, forKey: "accessToken")
        KeychainHelper.save(tokens.refreshToken, forKey: "refreshToken")
        KeychainHelper.save(user.userId, forKey: "userId")
        KeychainHelper.save(user.deviceId, forKey: "deviceId")
    }

    private func clearSession() {
        KeychainHelper.clear()
    }

    // MARK: - Messages

    @MainActor
    public func loadMessages(limit: Int = 100, before: Int64? = nil) async throws -> [Message] {
        let response = try await client.getMessages(limit: limit, before: before)
        let existingIds = Set(state.messages.map { $0.id })
        let newMessages = response.messages.filter { !existingIds.contains($0.id) }
        state.messages = (state.messages + newMessages).sorted { $0.date > $1.date }
        return response.messages
    }

    public func syncMessages(_ messages: [[String: Any]]) async throws -> SyncResponse {
        return try await client.syncMessages(messages)
    }

    public func sendMessage(address: String, body: String, simSubscriptionId: Int? = nil) async throws {
        _ = try await client.sendMessage(address: address, body: body, simSubscriptionId: simSubscriptionId)
    }

    @MainActor
    public func markAsRead(messageId: String) async throws {
        try await client.markMessageRead(id: messageId)
        if let index = state.messages.firstIndex(where: { $0.id == messageId }) {
            // Create updated message with read = true
            let oldMsg = state.messages[index]
            let updatedMsg = Message(
                id: oldMsg.id,
                threadId: oldMsg.threadId,
                address: oldMsg.address,
                contactName: oldMsg.contactName,
                body: oldMsg.body,
                date: oldMsg.date,
                type: oldMsg.type,
                read: true,
                isMms: oldMsg.isMms,
                encrypted: oldMsg.encrypted
            )
            state.messages[index] = updatedMsg
        }
    }

    // MARK: - Contacts

    @MainActor
    public func loadContacts() async throws -> [Contact] {
        let response = try await client.getContacts()
        state.contacts = response.contacts
        return response.contacts
    }

    public func syncContacts(_ contacts: [[String: Any]]) async throws -> SyncResponse {
        return try await client.syncContacts(contacts)
    }

    public func searchContacts(query: String) async throws -> [Contact] {
        let response = try await client.getContacts(search: query)
        return response.contacts
    }

    // MARK: - Call History

    @MainActor
    public func loadCallHistory(limit: Int = 100, before: Int64? = nil) async throws -> [CallHistoryEntry] {
        let response = try await client.getCallHistory(limit: limit, before: before)
        let existingIds = Set(state.calls.map { $0.id })
        let newCalls = response.calls.filter { !existingIds.contains($0.id) }
        state.calls = (state.calls + newCalls).sorted { $0.callDate > $1.callDate }
        return response.calls
    }

    public func syncCallHistory(_ calls: [[String: Any]]) async throws -> SyncResponse {
        return try await client.syncCallHistory(calls)
    }

    public func makeCall(phoneNumber: String, simSubscriptionId: Int? = nil) async throws {
        _ = try await client.requestCall(phoneNumber: phoneNumber, simSubscriptionId: simSubscriptionId)
    }

    // MARK: - Devices

    @MainActor
    public func loadDevices() async throws -> [Device] {
        let response = try await client.getDevices()
        state.devices = response.devices
        return response.devices
    }

    @MainActor
    public func removeDevice(id: String) async throws {
        try await client.removeDevice(id: id)
        state.devices = state.devices.filter { $0.id != id }
    }

    // MARK: - WebSocket

    private func connectWebSocket() {
        client.connectWebSocket()
        client.subscribe("messages")
        client.subscribe("contacts")
        client.subscribe("calls")
        client.subscribe("devices")
    }

    private func scheduleReconnect() {
        reconnectTask?.cancel()
        reconnectTask = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000) // 3 seconds
            if state.isAuthenticated && !Task.isCancelled {
                connectWebSocket()
            }
        }
    }

    // MARK: - WebSocket Delegate

    public func onConnected() {
        DispatchQueue.main.async {
            self.state.isConnected = true
            self.connectionChanged.send(true)
        }
    }

    public func onDisconnected() {
        DispatchQueue.main.async {
            self.state.isConnected = false
            self.connectionChanged.send(false)
        }
        scheduleReconnect()
    }

    public func onError(_ error: Error) {
        print("WebSocket error: \(error)")
    }

    public func onMessageAdded(_ message: Message) {
        DispatchQueue.main.async {
            if !self.state.messages.contains(where: { $0.id == message.id }) {
                self.state.messages.insert(message, at: 0)
                self.messageReceived.send(message)
            }
        }
    }

    public func onMessageUpdated(_ message: Message) {
        DispatchQueue.main.async {
            if let index = self.state.messages.firstIndex(where: { $0.id == message.id }) {
                self.state.messages[index] = message
            }
        }
    }

    public func onMessageDeleted(_ messageId: String) {
        DispatchQueue.main.async {
            self.state.messages.removeAll { $0.id == messageId }
        }
    }

    public func onContactAdded(_ contact: Contact) {
        DispatchQueue.main.async {
            if !self.state.contacts.contains(where: { $0.id == contact.id }) {
                self.state.contacts.append(contact)
            }
        }
    }

    public func onContactUpdated(_ contact: Contact) {
        DispatchQueue.main.async {
            if let index = self.state.contacts.firstIndex(where: { $0.id == contact.id }) {
                self.state.contacts[index] = contact
            }
        }
    }

    public func onContactDeleted(_ contactId: String) {
        DispatchQueue.main.async {
            self.state.contacts.removeAll { $0.id == contactId }
        }
    }

    public func onCallAdded(_ call: CallHistoryEntry) {
        DispatchQueue.main.async {
            if !self.state.calls.contains(where: { $0.id == call.id }) {
                self.state.calls.insert(call, at: 0)
            }
        }
    }

    public func onOutgoingMessage(_ message: OutgoingMessage) {
        // Handle on Android side
    }

    public func onCallRequest(_ request: CallRequest) {
        // Handle on Android side
    }
}
