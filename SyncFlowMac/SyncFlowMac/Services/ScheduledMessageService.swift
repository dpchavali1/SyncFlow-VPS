//
//  ScheduledMessageService.swift
//  SyncFlowMac
//
//  Service to schedule SMS messages to be sent later via Android
//  Operations stubbed pending VPS implementation.
//

import Foundation
import Combine

class ScheduledMessageService: ObservableObject {
    static let shared = ScheduledMessageService()

    @Published var scheduledMessages: [ScheduledMessage] = []
    @Published var statusMessage: String?

    private var currentUserId: String?
    private var messagesCache: [String: ScheduledMessage] = [:]

    private init() {}

    // MARK: - ScheduledMessage Model

    struct ScheduledMessage: Identifiable, Hashable {
        let id: String
        let recipientNumber: String
        let recipientName: String?
        let message: String
        let scheduledTime: Date
        let createdAt: Date
        let status: Status
        let sentAt: Date?
        let errorMessage: String?

        enum Status: String {
            case pending
            case sent
            case failed
            case cancelled
        }
    }

    /// Start listening for scheduled messages - no-op (VPS not yet implemented)
    func startListening(userId: String) {
        currentUserId = userId
        // No-op: Scheduled message sync via VPS not yet implemented.
    }

    /// Stop listening - clears local state
    func stopListening() {
        currentUserId = nil
        scheduledMessages = []
        messagesCache = [:]
    }

    /// Start listening with bandwidth optimization - no-op (VPS not yet implemented)
    func startListeningOptimized(userId: String) {
        if currentUserId != nil {
            stopListening()
        }
        currentUserId = userId
        messagesCache = [:]
        // No-op: Scheduled message sync via VPS not yet implemented.
    }

    // MARK: - Schedule Messages

    /// Schedule a new message - not implemented via VPS
    func scheduleMessage(
        recipientNumber: String,
        recipientName: String?,
        message: String,
        scheduledTime: Date,
        simSlot: Int? = nil
    ) async throws {
        throw ScheduledMessageError.notImplemented
    }

    /// Cancel a scheduled message - not implemented via VPS
    func cancelMessage(_ messageId: String) async throws {
        throw ScheduledMessageError.notImplemented
    }

    /// Delete a scheduled message - not implemented via VPS
    func deleteMessage(_ messageId: String) async throws {
        throw ScheduledMessageError.notImplemented
    }

    /// Update a scheduled message - not implemented via VPS
    func updateMessage(
        _ messageId: String,
        newMessage: String? = nil,
        newScheduledTime: Date? = nil
    ) async throws {
        throw ScheduledMessageError.notImplemented
    }

    // MARK: - Computed Properties

    /// Get pending messages only
    var pendingMessages: [ScheduledMessage] {
        scheduledMessages.filter { $0.status == .pending }
    }

    /// Get sent messages history
    var sentMessages: [ScheduledMessage] {
        scheduledMessages.filter { $0.status == .sent }
    }

    /// Count of pending messages
    var pendingCount: Int {
        pendingMessages.count
    }

    // MARK: - Private Helpers

    private func parseMessage(id: String, data: [String: Any]) -> ScheduledMessage? {
        guard let recipientNumber = data["recipientNumber"] as? String,
              let message = data["message"] as? String,
              let scheduledTimeMs = data["scheduledTime"] as? Double,
              let statusString = data["status"] as? String else {
            return nil
        }

        let status = ScheduledMessage.Status(rawValue: statusString) ?? .pending
        let scheduledTime = Date(timeIntervalSince1970: scheduledTimeMs / 1000)

        var createdAt = Date()
        if let createdAtMs = data["createdAt"] as? Double {
            createdAt = Date(timeIntervalSince1970: createdAtMs / 1000)
        }

        var sentAt: Date? = nil
        if let sentAtMs = data["sentAt"] as? Double {
            sentAt = Date(timeIntervalSince1970: sentAtMs / 1000)
        }

        return ScheduledMessage(
            id: id,
            recipientNumber: recipientNumber,
            recipientName: data["recipientName"] as? String,
            message: message,
            scheduledTime: scheduledTime,
            createdAt: createdAt,
            status: status,
            sentAt: sentAt,
            errorMessage: data["errorMessage"] as? String
        )
    }

    // MARK: - Errors

    enum ScheduledMessageError: Error, LocalizedError {
        case notAuthenticated
        case invalidData
        case notImplemented

        var errorDescription: String? {
            switch self {
            case .notAuthenticated:
                return "User not authenticated"
            case .invalidData:
                return "Invalid message data"
            case .notImplemented:
                return "Not implemented via VPS"
            }
        }
    }

    /// Pause all scheduled messages temporarily
    func pauseAll() {
        // This would cancel all pending scheduled messages
    }
}
