//
//  MessageStore+SendMessage.swift
//  SyncFlowMac
//
//  Send SMS and MMS functionality, including optimistic UI updates,
//  pending message creation, and encrypted sent message sync.
//

import Foundation

// MARK: - Send Message

extension MessageStore {

    // MARK: - Send SMS

    /// Sends an SMS message through the paired Android device.
    ///
    /// Implements optimistic UI update:
    /// 1. Creates a pending message with temporary ID
    /// 2. Immediately adds to UI for instant feedback
    /// 3. Sends to VPS for Android to deliver
    /// 4. Matches with confirmed message when sync returns
    /// 5. Removes pending message once confirmed
    ///
    /// - Parameters:
    ///   - userId: The user ID
    ///   - address: Recipient phone number
    ///   - body: Message text content
    /// - Throws: Error if send fails (rolls back optimistic update)
    func sendMessage(userId: String, to address: String, body: String) async throws {
        guard !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return
        }

        let pendingMessage = createPendingOutgoingMessage(to: address, body: body)

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.messages.append(pendingMessage)
            self.updateConversations(from: self.messages)
        }

        pendingOutgoingQueue.sync {
            pendingOutgoingMessages[pendingMessage.id] = pendingMessage
        }

        do {
            let outgoingId = try await VPSService.shared.sendMessage(address: address, body: body)

            // Immediately sync an encrypted copy to user_messages so the message
            // is never stored as plain text on the server
            syncEncryptedSentMessage(id: outgoingId, address: address, body: body, isMms: false)
        } catch {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.messages.removeAll { $0.id == pendingMessage.id }
                self.updateConversations(from: self.messages)
            }

            _ = pendingOutgoingQueue.sync {
                pendingOutgoingMessages.removeValue(forKey: pendingMessage.id)
            }

            DispatchQueue.main.async {
                self.error = error
            }
            throw error
        }
    }

    // MARK: - Send MMS

    func sendMmsMessage(userId: String, to address: String, body: String, attachment: SelectedAttachment) async throws {
        do {
            // Upload attachment to R2 via VPS presigned URL
            let uploadResponse = try await VPSService.shared.getFileUploadUrl(
                fileName: attachment.fileName,
                contentType: attachment.contentType,
                fileSize: Int64(attachment.data.count)
            )

            // PUT file data to presigned URL
            guard let uploadUrl = URL(string: uploadResponse.uploadUrl) else {
                throw VPSError.invalidResponse
            }
            var uploadRequest = URLRequest(url: uploadUrl)
            uploadRequest.httpMethod = "PUT"
            uploadRequest.setValue(attachment.contentType, forHTTPHeaderField: "Content-Type")
            uploadRequest.httpBody = attachment.data
            let (_, uploadHttpResponse) = try await URLSession.shared.data(for: uploadRequest)
            guard let httpResp = uploadHttpResponse as? HTTPURLResponse, (200...299).contains(httpResp.statusCode) else {
                throw VPSError.invalidResponse
            }

            // Confirm upload
            try await VPSService.shared.confirmFileUpload(fileKey: uploadResponse.fileKey, fileSize: Int64(attachment.data.count))

            // Send MMS message via VPS
            let outgoingId = try await VPSService.shared.sendMmsMessage(
                address: address,
                body: body,
                attachments: [[
                    "fileKey": uploadResponse.fileKey,
                    "contentType": attachment.contentType,
                    "fileName": attachment.fileName
                ]]
            )

            // Immediately sync an encrypted copy to user_messages
            syncEncryptedSentMessage(id: outgoingId, address: address, body: body, isMms: true)
        } catch {
            DispatchQueue.main.async {
                self.error = error
            }
            throw error
        }
    }

    // MARK: - Pending Message Helpers

    func createPendingOutgoingMessage(to address: String, body: String) -> Message {
        let normalizedAddress = normalizePhoneNumber(address)
        let contactName = conversations.first { normalizePhoneNumber($0.address) == normalizedAddress }?.contactName

        return Message(
            id: "pending_\(UUID().uuidString)",
            address: address,
            body: body,
            date: Date().timeIntervalSince1970 * 1000.0,
            type: 2,
            contactName: contactName,
            isRead: true,
            isMms: false,
            attachments: nil,
            e2eeFailed: false,
            e2eeFailureReason: nil
        )
    }

    // MARK: - Encrypted Sent Message Sync

    /// Encrypt the sent message body and sync to user_messages so the server
    /// never holds plain text. When Android later syncs the real sent message
    /// (with the SMS provider ID), the Mac deduplicates via pending-message matching.
    func syncEncryptedSentMessage(id: String, address: String, body: String, isMms: Bool) {
        Task {
            guard let encrypted = E2EEManager.shared.encryptForSync(body) else {
                return
            }

            do {
                let syncPayload: [String: Any] = [
                    "id": id,
                    "threadId": 0,
                    "address": address,
                    "body": "",
                    "date": Int(Date().timeIntervalSince1970 * 1000),
                    "type": 2,
                    "read": true,
                    "isMms": isMms,
                    "encrypted": true,
                    "encryptedBody": encrypted.encryptedBody,
                    "encryptedNonce": encrypted.encryptedNonce,
                    "keyMap": encrypted.keyMap
                ]

                try await VPSService.shared.syncSentMessage(syncPayload)
            } catch {
                #if DEBUG
                print("[MessageStore] Failed to sync encrypted sent message: \(error.localizedDescription)")
                #endif
            }
        }
    }
}
