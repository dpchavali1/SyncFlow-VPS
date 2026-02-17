/**
 * VPSService+Messages - Messages, contacts, call history, and WebRTC signaling
 *
 * Contains:
 * - SMS/MMS message CRUD (fetch, send, sync, mark read, delete)
 * - Contact retrieval and sync requests
 * - Call history retrieval and call requests
 * - Call commands (answer/reject/end)
 * - SyncFlow Calls (TURN credentials, create, status update)
 * - WebRTC signaling (send/get/delete signals)
 */

import Foundation

extension VPSService {

    // MARK: - Messages

    public func getMessages(limit: Int = 100, before: Int64? = nil, after: Double? = nil, threadId: Int? = nil) async throws -> VPSMessagesResponse {
        var path = "/api/messages?limit=\(limit)"
        if let before = before {
            path += "&before=\(before)"
        }
        if let after = after {
            path += "&after=\(Int64(after))"
        }
        if let threadId = threadId {
            path += "&threadId=\(threadId)"
        }
        return try await get(path)
    }

    public func sendMessage(address: String, body: String, simSubscriptionId: Int? = nil) async throws -> String {
        var requestBody: [String: Any] = [
            "address": address,
            "body": body
        ]
        if let simId = simSubscriptionId {
            requestBody["simSubscriptionId"] = simId
        }

        let response: [String: String] = try await post("/api/messages/send", body: requestBody)
        return response["id"] ?? "out_\(Int(Date().timeIntervalSince1970 * 1000))"
    }

    public func syncSentMessage(_ message: [String: Any]) async throws {
        let body: [String: Any] = ["messages": [message]]
        let _: VPSGenericResponse = try await post("/api/messages/sync", body: body)
    }

    public func markMessageRead(messageId: String) async throws {
        let _: [String: Bool] = try await put("/api/messages/\(messageId)/read", body: nil)
    }

    public func deleteMessages(messageIds: [String]) async throws {
        let body: [String: Any] = ["messageIds": messageIds]
        let _: VPSGenericResponse = try await request("DELETE", "/api/messages", body: body)
    }

    @discardableResult
    public func sendMmsMessage(address: String, body: String, attachments: [[String: String]]) async throws -> String {
        let requestBody: [String: Any] = [
            "address": address,
            "body": body,
            "isMms": true,
            "attachments": attachments
        ]
        let response: [String: String] = try await post("/api/messages/send", body: requestBody)
        return response["id"] ?? "out_\(Int(Date().timeIntervalSince1970 * 1000))"
    }

    // MARK: - Contacts

    public func getContacts(search: String? = nil, limit: Int = 500) async throws -> VPSContactsResponse {
        var path = "/api/contacts?limit=\(limit)"
        if let search = search {
            path += "&search=\(search.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? search)"
        }
        return try await get(path)
    }

    // Request Android to push contacts to server
    public func requestContactsSync() async throws {
        let _: VPSSuccessResponse = try await post("/api/contacts/request-sync", body: [:] as [String: String])
    }

    // Request Android to push call history to server
    public func requestCallsSync() async throws {
        let _: VPSSuccessResponse = try await post("/api/calls/request-sync", body: [:] as [String: String])
    }

    // MARK: - Call History

    public func getCallHistory(limit: Int = 100, before: Int64? = nil) async throws -> VPSCallsResponse {
        var path = "/api/calls?limit=\(limit)"
        if let before = before {
            path += "&before=\(before)"
        }
        return try await get(path)
    }

    public func requestCall(phoneNumber: String, simSubscriptionId: Int? = nil) async throws {
        var body: [String: Any] = ["phoneNumber": PhoneNumberNormalizer.shared.toE164(phoneNumber)]
        if let simId = simSubscriptionId {
            body["simSubscriptionId"] = simId
        }
        let _: [String: String] = try await post("/api/calls/request", body: body)
    }

    /// Send a call command (answer/reject/end) to the Android device via the server
    public func sendCallCommand(callId: String, command: String, phoneNumber: String? = nil) async throws {
        var body: [String: Any] = [
            "callId": callId,
            "command": command
        ]
        if let phone = phoneNumber {
            body["phoneNumber"] = phone
        }
        let _: EmptyCodable = try await post("/api/calls/commands", body: body)
    }

    // MARK: - SyncFlow Calls & WebRTC Signaling

    public func getTurnCredentials() async throws -> [String: Any] {
        guard let url = URL(string: baseUrl + "/api/calls/turn-credentials") else {
            throw VPSError.invalidResponse
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode) else {
            let msg = String(data: data, encoding: .utf8) ?? "Unknown error"
            throw VPSError.httpError((response as? HTTPURLResponse)?.statusCode ?? 500, msg)
        }
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw VPSError.invalidResponse
        }
        return json
    }

    public struct CreateCallResponse: Codable {
        let callId: String
        let status: String
    }

    public func createSyncFlowCall(calleeId: String, calleeName: String, callType: String) async throws -> [String: String] {
        let body: [String: Any] = [
            "calleeId": PhoneNumberNormalizer.shared.toE164(calleeId),
            "calleeName": calleeName,
            "callerName": Host.current().localizedName ?? "Mac",
            "callerPlatform": "macos",
            "callType": callType
        ]
        let response: CreateCallResponse = try await post("/api/calls/syncflow", body: body)
        return ["callId": response.callId, "status": response.status]
    }

    public func updateSyncFlowCallStatus(callId: String, status: String) async throws {
        let _: [String: Bool] = try await put("/api/calls/syncflow/\(callId)/status", body: ["status": status])
    }

    public func sendSignal(callId: String, signalType: String, signalData: [String: Any], toDevice: String? = nil) async throws {
        var body: [String: Any] = [
            "callId": callId,
            "signalType": signalType,
            "signalData": signalData
        ]
        if let toDevice = toDevice {
            body["toDevice"] = toDevice
        }
        let _: [String: Bool] = try await post("/api/calls/signaling", body: body)
    }

    public func getSignals(callId: String) async throws -> [[String: Any]] {
        struct SignalsResponse: Codable {
            let signals: [[String: AnyCodableValue]]
        }
        let response: SignalsResponse = try await get("/api/calls/signaling/\(callId)")
        return response.signals.map { signal in
            var dict: [String: Any] = [:]
            for (key, value) in signal {
                dict[key] = value.anyValue
            }
            return dict
        }
    }

    public func deleteSignals(callId: String) async throws {
        let _: [String: Bool] = try await delete("/api/calls/signaling/\(callId)")
    }
}

// EmptyCodable used by sendCallCommand
private struct EmptyCodable: Codable {}
