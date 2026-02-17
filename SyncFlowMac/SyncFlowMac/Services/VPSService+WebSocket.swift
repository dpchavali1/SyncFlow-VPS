/**
 * VPSService+WebSocket — Real-time WebSocket connection management.
 *
 * Handles:
 * - WebSocket connection lifecycle (connect, disconnect, reconnect)
 * - Message receiving and dispatching to Combine publishers
 * - Channel subscriptions (messages, contacts, calls, devices, etc.)
 * - Exponential backoff reconnection (2s, 4s, 8s, …, 30s cap)
 * - TLS fallback to insecure WebSocket in DEBUG builds
 * - WebRTC signaling and SyncFlow call notifications
 * - Active call state forwarding from Android
 *
 * WebSocket Protocol:
 *   Messages are JSON objects with a "type" field indicating the event type.
 *   Data payload is in the "data" field. The client subscribes to per-channel
 *   events after connection, and the server pushes updates in real time.
 */

import Foundation
import Combine

// MARK: - WebRTC Signal Notification Names

extension Notification.Name {
    static let webRTCSignalReceived = Notification.Name("webRTCSignalReceived")
    static let syncFlowCallIncoming = Notification.Name("syncFlowCallIncoming")
    static let syncFlowCallStatusChanged = Notification.Name("syncFlowCallStatusChanged")
    static let vpsPlanDowngraded = Notification.Name("vpsPlanDowngraded")
}

// MARK: - WebSocket Extension

extension VPSService {

    // MARK: - Connect / Disconnect

    /// Opens a WebSocket connection to the VPS server for real-time event streaming.
    /// The token is passed as a query parameter for authentication.
    /// On success, subscribes to all data channels for the current user.
    /// On failure, triggers exponential-backoff reconnection.
    public func connectWebSocket() {
        guard let token = accessToken else {
            #if DEBUG
            print("[VPS Warning] Cannot connect WebSocket - not authenticated")
            #endif
            return
        }

        guard let url = URL(string: "\(activeWsUrl)?token=\(token)") else {
            #if DEBUG
            print("[VPS Error] Invalid WebSocket URL")
            #endif
            return
        }

        // Cancel any pending reconnect
        pendingReconnectWork?.cancel()
        pendingReconnectWork = nil

        webSocketTask?.cancel()
        let task = session.webSocketTask(with: url)
        webSocketTask = task
        task.resume()

        isConnected = true
        reconnectAttempts = 0
        #if DEBUG
        print("[VPS] WebSocket connected")
        #endif

        // Start receiving messages (pass task reference to ignore stale callbacks)
        receiveWebSocketMessage(for: task)

        // Subscribe to user's data
        if let userId = userId {
            subscribeToUser(userId)
        }

        // Start periodic plan refresh (every 30 minutes)
        startPlanRefreshTimer()
    }

    public func disconnectWebSocket() {
        pendingReconnectWork?.cancel()
        pendingReconnectWork = nil
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        isConnected = false
        subscriptions.removeAll()
        reconnectTimer?.invalidate()
        stopPlanRefreshTimer()
        #if DEBUG
        print("[VPS] WebSocket disconnected")
        #endif
    }

    // MARK: - Receive Loop

    func receiveWebSocketMessage(for task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            guard let self = self else { return }
            // Ignore callbacks from old/cancelled tasks
            guard self.webSocketTask === task else { return }

            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleWebSocketMessage(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.handleWebSocketMessage(text)
                    }
                @unknown default:
                    break
                }
                // Continue receiving on the same task
                self.receiveWebSocketMessage(for: task)

            case .failure(let error):
                // Ignore errors from old tasks that were replaced
                guard self.webSocketTask === task else { return }
                #if DEBUG
                print("[VPS Error] WebSocket error: \(error.localizedDescription)")
                #endif
                self.isConnected = false
                if self.maybeFallbackToInsecureWebSocket(error: error) {
                    self.connectWebSocket()
                } else {
                    self.scheduleReconnect()
                }
            }
        }
    }

    // MARK: - Message Dispatch

    /// Parses incoming WebSocket JSON and dispatches events to the appropriate Combine publisher.
    func handleWebSocketMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else {
            return
        }

        switch type {
        case "message_added":
            if let messageData = json["data"] as? [String: Any],
               let jsonData = try? JSONSerialization.data(withJSONObject: messageData),
               let message = try? decoder.decode(VPSMessage.self, from: jsonData) {
                DispatchQueue.main.async {
                    self.messageAdded.send(message)
                }
            }

        case "message_updated":
            if let messageData = json["data"] as? [String: Any],
               let jsonData = try? JSONSerialization.data(withJSONObject: messageData),
               let message = try? decoder.decode(VPSMessage.self, from: jsonData) {
                DispatchQueue.main.async {
                    self.messageUpdated.send(message)
                }
            }

        case "message_deleted":
            if let messageId = json["messageId"] as? String {
                DispatchQueue.main.async {
                    self.messageDeleted.send(messageId)
                }
            }

        case "messages_deleted":
            if let data = json["data"] as? [String: Any],
               let messageIds = data["messageIds"] as? [String] {
                DispatchQueue.main.async {
                    for id in messageIds {
                        self.messageDeleted.send(id)
                    }
                }
            }

        case "contact_added":
            if let contactData = json["data"] as? [String: Any],
               let jsonData = try? JSONSerialization.data(withJSONObject: contactData),
               let contact = try? decoder.decode(VPSContact.self, from: jsonData) {
                DispatchQueue.main.async {
                    self.contactAdded.send(contact)
                }
            }

        case "call_added":
            if let callData = json["data"] as? [String: Any],
               let jsonData = try? JSONSerialization.data(withJSONObject: callData),
               let call = try? decoder.decode(VPSCallHistoryEntry.self, from: jsonData) {
                DispatchQueue.main.async {
                    self.callAdded.send(call)
                }
            }

        case "contacts_synced":
            if let data = json["data"] as? [String: Any],
               let synced = data["synced"] as? Int {
                DispatchQueue.main.async {
                    self.contactsSynced.send(synced)
                }
            }

        case "calls_synced":
            if let data = json["data"] as? [String: Any],
               let synced = data["synced"] as? Int {
                DispatchQueue.main.async {
                    self.callsSynced.send(synced)
                }
            }

        case "messages_synced":
            // Handle batch of synced messages from Android
            if let data = json["data"] as? [String: Any],
               let messagesArray = data["messages"] as? [[String: Any]] {
                for messageData in messagesArray {
                    if let jsonData = try? JSONSerialization.data(withJSONObject: messageData),
                       let message = try? decoder.decode(VPSMessage.self, from: jsonData) {
                        DispatchQueue.main.async {
                            self.messageAdded.send(message)
                        }
                    }
                }
            }

        case "e2ee_key_available":
            if let data = json["data"] as? [String: Any],
               let targetDeviceId = data["deviceId"] as? String,
               targetDeviceId == self.deviceId {
                #if DEBUG
                print("[VPS E2EE] Key push notification received for this device")
                #endif
                self.e2eeKeyPushReceived = true
            }

        case "device_removed":
            if let data = json["data"] as? [String: Any] {
                let removedDeviceId = (data["id"] as? String) ?? (data["deviceId"] as? String) ?? ""
                if removedDeviceId == self.deviceId || removedDeviceId.isEmpty {
                    #if DEBUG
                    print("[VPS] Remote unpair detected")
                    #endif
                    DispatchQueue.main.async {
                        self.deviceRemoved.send(removedDeviceId)
                    }
                }
            } else {
                #if DEBUG
                print("[VPS] Remote unpair detected")
                #endif
                DispatchQueue.main.async {
                    self.deviceRemoved.send("")
                }
            }

        case "webrtc_signal":
            if let signalData = json["data"] as? [String: Any] ?? json["signal"] as? [String: Any],
               let callId = signalData["callId"] as? String,
               let signalType = signalData["signalType"] as? String {
                let fromDevice = signalData["fromDevice"] as? String ?? ""
                let signalPayload = signalData["signalData"] ?? signalData["data"] ?? [:]
                DispatchQueue.main.async {
                    NotificationCenter.default.post(
                        name: .webRTCSignalReceived,
                        object: nil,
                        userInfo: [
                            "callId": callId,
                            "signalType": signalType,
                            "signalData": signalPayload,
                            "fromDevice": fromDevice
                        ]
                    )
                }
            }

        case "syncflow_call_incoming":
            if let callData = json["data"] as? [String: Any],
               let callId = callData["callId"] as? String ?? callData["id"] as? String {
                let callerId = callData["callerId"] as? String ?? ""
                let callerName = callData["callerName"] as? String ?? "Unknown"
                let callerPlatform = callData["callerPlatform"] as? String ?? "android"
                let callType = callData["callType"] as? String ?? "audio"
                DispatchQueue.main.async {
                    NotificationCenter.default.post(
                        name: .syncFlowCallIncoming,
                        object: nil,
                        userInfo: [
                            "callId": callId,
                            "callerId": callerId,
                            "callerName": callerName,
                            "callerPlatform": callerPlatform,
                            "callType": callType
                        ]
                    )
                }
            }

        case "syncflow_call_status":
            if let statusData = json["data"] as? [String: Any],
               let callId = statusData["callId"] as? String ?? statusData["id"] as? String,
               let status = statusData["status"] as? String {
                DispatchQueue.main.async {
                    NotificationCenter.default.post(
                        name: .syncFlowCallStatusChanged,
                        object: nil,
                        userInfo: [
                            "callId": callId,
                            "status": status
                        ]
                    )
                }
            }

        case "media_status_updated":
            if let data = json["data"] as? [String: Any] {
                DispatchQueue.main.async { self.mediaStatusUpdated.send(data) }
            }

        case "phone_status_updated":
            if let data = json["data"] as? [String: Any] {
                DispatchQueue.main.async { self.phoneStatusUpdated.send(data) }
            }

        case "clipboard_updated":
            if let data = json["data"] as? [String: Any] {
                DispatchQueue.main.async { self.clipboardUpdated.send(data) }
            }

        case "dnd_status_updated":
            if let data = json["data"] as? [String: Any] {
                DispatchQueue.main.async { self.dndStatusUpdated.send(data) }
            }

        case "hotspot_status_updated":
            if let data = json["data"] as? [String: Any] {
                DispatchQueue.main.async { self.hotspotStatusUpdated.send(data) }
            }

        case "voicemail_added", "voicemail_updated", "voicemail_deleted":
            var data = json["data"] as? [String: Any] ?? [:]
            data["eventType"] = type
            DispatchQueue.main.async { self.voicemailUpdated.send(data) }

        case "spam_updated":
            let data = json["data"] as? [String: Any] ?? [:]
            DispatchQueue.main.async { self.spamUpdated.send(data) }

        case "outgoing_status_changed":
            if let data = json["data"] as? [String: Any],
               let id = data["id"] as? String,
               let status = data["status"] as? String {
                DispatchQueue.main.async {
                    self.deliveryStatusChanged.send((id, status))
                }
            }

        case "delivery_status_changed":
            if let data = json["data"] as? [String: Any],
               let id = data["id"] as? String,
               let deliveryStatus = data["deliveryStatus"] as? String {
                DispatchQueue.main.async {
                    self.deliveryStatusChanged.send((id, deliveryStatus))
                }
            }

        case "active_call":
            #if DEBUG
            print("[VPS DEBUG] Received active_call WebSocket message")
            #endif
            if let data = json["data"] as? [String: Any] {
                #if DEBUG
                print("[VPS DEBUG] active_call data: state=\(data["state"] ?? "nil"), phone=\(data["phoneNumber"] ?? "nil"), contact=\(data["contactName"] ?? "nil")")
                #endif
                DispatchQueue.main.async {
                    #if DEBUG
                    print("[VPS DEBUG] Publishing activeCallUpdated")
                    #endif
                    self.activeCallUpdated.send(data)
                }
            } else {
                #if DEBUG
                print("[VPS DEBUG] active_call: failed to parse data from json")
                #endif
            }

        case "pong":
            // Heartbeat response — no action needed
            break

        default:
            break // Unknown message type — ignore silently
        }
    }

    // MARK: - Channel Subscriptions

    func subscribeToUser(_ userId: String) {
        // Subscribe to each channel individually (server expects single channel per message)
        let channels = ["messages", "contacts", "calls", "devices", "media", "phone_status", "clipboard", "dnd", "hotspot", "voicemails", "spam"]

        for channel in channels {
            let subscription: [String: Any] = [
                "type": "subscribe",
                "channel": channel
            ]

            if let data = try? JSONSerialization.data(withJSONObject: subscription),
               let text = String(data: data, encoding: .utf8) {
                webSocketTask?.send(.string(text)) { error in
                    if let error = error {
                        #if DEBUG
                        print("[VPS Error] Failed to subscribe to \(channel): \(error.localizedDescription)")
                        #endif
                    } else {
                        self.subscriptions.insert(channel)
                        #if DEBUG
                        print("[VPS] Subscribed to \(channel) channel")
                        #endif
                    }
                }
            }
        }
    }

    // MARK: - Reconnection

    /// Schedules a WebSocket reconnection with exponential backoff.
    /// Delay doubles each attempt (2s, 4s, 8s, 16s) capped at 30s.
    func scheduleReconnect() {
        reconnectAttempts += 1

        guard reconnectAttempts <= maxReconnectAttempts else {
            #if DEBUG
            print("[VPS] WebSocket max reconnect attempts (\(maxReconnectAttempts)) reached, giving up")
            #endif
            return
        }

        // Exponential backoff: 2, 4, 8, 16, 30, 30, 30... seconds (capped at 30s)
        let delay = min(Double(1 << min(reconnectAttempts, 5)), 30.0)

        #if DEBUG
        if reconnectAttempts <= 3 || reconnectAttempts % 10 == 0 {
            print("[VPS] WebSocket reconnect in \(Int(delay))s (attempt \(reconnectAttempts))")
        }
        #endif

        pendingReconnectWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.connectWebSocket()
        }
        pendingReconnectWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    // MARK: - TLS Fallback (DEBUG only)

    func maybeFallbackToInsecureWebSocket(error: Error) -> Bool {
        #if DEBUG
        guard !didFallbackToInsecureWebSocket, let fallback = wsUrlFallback else {
            return false
        }

        let nsError = error as NSError
        let urlError = URLError.Code(rawValue: nsError.code)
        let shouldFallback: Bool

        switch urlError {
        case .secureConnectionFailed,
             .serverCertificateUntrusted,
             .serverCertificateHasBadDate,
             .serverCertificateNotYetValid,
             .serverCertificateHasUnknownRoot,
             .clientCertificateRejected,
             .clientCertificateRequired:
            shouldFallback = true
        default:
            shouldFallback = false
        }

        guard shouldFallback else { return false }
        didFallbackToInsecureWebSocket = true
        activeWsUrl = fallback
        print("[VPS] WebSocket TLS failed; falling back to insecure WebSocket: \(fallback)")
        return true
        #else
        return false
        #endif
    }
}
