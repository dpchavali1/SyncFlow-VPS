//
//  MessageStore+VPSSync.swift
//  SyncFlowMac
//
//  VPS listening, polling, message sync from server, and VPS message conversion.
//  Handles WebSocket subscriptions, polling fallback, initial message fetch,
//  and VPSMessage -> Message conversion including E2EE decryption.
//

import Foundation
import Combine

// MARK: - VPS Sync

extension MessageStore {

    // MARK: - Start VPS Listening

    /// Starts listening for messages using VPS backend.
    /// This method:
    /// 1. Fetches initial messages from VPS REST API
    /// 2. Subscribes to WebSocket events for real-time updates
    func startListeningVPS(userId: String) {
        // Cancel any existing VPS subscriptions and pending rebuilds
        vpsCancellables.removeAll()
        pendingRebuildWork?.cancel()
        pendingRebuildWork = nil
        pendingMessageBuffer.removeAll()
        pendingNotificationBuffer.removeAll()

        // Subscribe to VPS WebSocket events for real-time updates.
        // Messages are coalesced: instead of rebuilding conversations on every
        // single incoming message (O(N) per message during sync bursts), we
        // buffer messages and rebuild once after 200ms of silence or when the
        // buffer reaches 50 messages.
        VPSService.shared.messageAdded
            .receive(on: DispatchQueue.main)
            .sink { [weak self] vpsMessage in
                guard let self = self else { return }
                let message = self.convertVPSMessage(vpsMessage)

                // Skip if message already exists (by ID)
                guard !self.messages.contains(where: { $0.id == message.id }) else { return }

                // Skip sent messages that match an existing message by content
                // (prevents duplicates from Android sync echoing back a message
                // the Mac already has under a different ID)
                if message.type == 2 {
                    let normalizedAddr = self.normalizePhoneNumber(message.address)
                    let trimmedBody = message.body.trimmingCharacters(in: .whitespacesAndNewlines)
                    let maxDeltaMs = 60.0 * 1000.0 // 1 minute window
                    let isDuplicate = self.messages.contains { existing in
                        existing.type == 2 &&
                        self.normalizePhoneNumber(existing.address) == normalizedAddr &&
                        existing.body.trimmingCharacters(in: .whitespacesAndNewlines) == trimmedBody &&
                        existing.isMms == message.isMms &&
                        abs(existing.date - message.date) <= maxDeltaMs
                    }
                    if isDuplicate {
                        // Replace pending version with confirmed version if present
                        if let pendingIdx = self.messages.firstIndex(where: {
                            $0.id.hasPrefix("pending_") &&
                            $0.type == 2 &&
                            self.normalizePhoneNumber($0.address) == normalizedAddr &&
                            $0.body.trimmingCharacters(in: .whitespacesAndNewlines) == trimmedBody
                        }) {
                            let pendingId = self.messages[pendingIdx].id
                            self.messages[pendingIdx] = message
                            self.updateConversations(from: self.messages)
                            self.pendingOutgoingQueue.sync {
                                _ = self.pendingOutgoingMessages.removeValue(forKey: pendingId)
                            }
                        }
                        return
                    }
                }

                // Add message to the messages array immediately (for dedup checks
                // on subsequent messages in the same burst) but defer the expensive
                // conversation rebuild.
                self.messages.append(message)
                self.pendingMessageBuffer.append(message)

                // Track incoming messages that need notifications after rebuild
                if message.isReceived {
                    self.pendingNotificationBuffer.append(message)
                }

                // If buffer is full, rebuild immediately
                if self.pendingMessageBuffer.count >= self.rebuildBufferLimit {
                    self.flushPendingRebuild()
                    return
                }

                // Cancel any previously scheduled rebuild and schedule a new one
                self.pendingRebuildWork?.cancel()
                let work = DispatchWorkItem { [weak self] in
                    self?.flushPendingRebuild()
                }
                self.pendingRebuildWork = work
                DispatchQueue.main.asyncAfter(
                    deadline: .now() + self.rebuildDebounceDelay,
                    execute: work
                )
            }
            .store(in: &vpsCancellables)

        VPSService.shared.messageUpdated
            .receive(on: DispatchQueue.main)
            .sink { [weak self] vpsMessage in
                guard let self = self else { return }
                let message = self.convertVPSMessage(vpsMessage)

                // Skip duplicate updates — avoid re-processing when nothing changed
                // Compare by body + date + attachment count; deep attachment comparison
                // fails because attachments are re-parsed from JSON each time
                if let existing = self.messages.first(where: { $0.id == message.id }),
                   existing.body == message.body,
                   existing.date == message.date,
                   existing.attachments?.count == message.attachments?.count {
                    return
                }

                var currentMessages = self.messages
                if let index = currentMessages.firstIndex(where: { $0.id == message.id }) {
                    currentMessages[index] = message
                } else {
                    currentMessages.append(message)
                }

                let processedMessages = self.applyReadStatus(to: currentMessages)
                let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processedMessages)
                let newConversations = self.buildConversations(from: mergeResult.mergedMessages)

                self.messages = mergeResult.mergedMessages
                self.conversations = newConversations
            }
            .store(in: &vpsCancellables)

        VPSService.shared.messageDeleted
            .receive(on: DispatchQueue.main)
            .sink { [weak self] messageId in
                guard let self = self else { return }

                var currentMessages = self.messages
                currentMessages.removeAll { $0.id == messageId }

                let processedMessages = self.applyReadStatus(to: currentMessages)
                let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processedMessages)
                let newConversations = self.buildConversations(from: mergeResult.mergedMessages)

                self.messages = mergeResult.mergedMessages
                self.conversations = newConversations

                // Per-message logging removed to reduce log noise
            }
            .store(in: &vpsCancellables)

        // Listen for delivery status changes (sent -> delivered)
        VPSService.shared.deliveryStatusChanged
            .receive(on: DispatchQueue.main)
            .sink { [weak self] (messageId, deliveryStatus) in
                guard let self = self else { return }
                if let index = self.messages.firstIndex(where: { $0.id == messageId }) {
                    self.messages[index].deliveryStatus = deliveryStatus
                }
            }
            .store(in: &vpsCancellables)

        // Fetch initial messages from VPS
        Task {
            do {
                let response = try await VPSService.shared.getMessages(limit: 500)

                let fetchedMessages = response.messages.map { self.convertVPSMessage($0) }

                #if DEBUG
                // Log decryption summary (not per-message)
                let encrypted = fetchedMessages.filter { $0.isEncrypted == true }
                let failed = encrypted.filter { $0.e2eeFailed == true }
                if !encrypted.isEmpty {
                    print("[MessageStore VPS] Decryption: \(encrypted.count - failed.count)/\(encrypted.count) messages decrypted, \(failed.count) failed, e2eeInitialized=\(E2EEManager.shared.isInitialized)")
                    if !failed.isEmpty, let first = failed.first {
                        print("[MessageStore VPS] First failed msg: \(first.id), reason=\(first.e2eeFailureReason ?? "unknown")")
                    }
                }
                #endif

                await MainActor.run {
                    let processedMessages = self.applyReadStatus(to: fetchedMessages)
                    let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processedMessages)
                    let newConversations = self.buildConversations(from: mergeResult.mergedMessages)

                    self.messages = mergeResult.mergedMessages
                    self.conversations = newConversations
                    self.isLoading = false
                    self.canLoadMore = response.hasMore

                    #if DEBUG
                    print("[MessageStore VPS] Loaded \(fetchedMessages.count) messages, \(newConversations.count) conversations")
                    #endif
                }
            } catch {
                #if DEBUG
                print("[MessageStore VPS] Error loading messages: \(error.localizedDescription)")
                #endif
                await MainActor.run {
                    self.error = error
                    self.isLoading = false
                }
            }
        }

        // Start contacts sync for VPS mode
        startListeningForContactsVPS(userId: userId)

        // Load spam messages from VPS and listen for real-time updates via WebSocket
        spamListenerUserId = userId
        loadSpamMessagesFromVPS()
        startSpamWebSocketListener()

        // Start polling fallback — only fetches when WebSocket is disconnected
        startPollingFallback()
    }

    // MARK: - Manual Sync

    /// Manually triggers a full re-sync of messages, contacts, and spam.
    /// Called when the user taps the Sync Now button.
    func syncNow() {
        guard let userId = currentUserId else { return }

        #if DEBUG
        print("[MessageStore] Manual sync triggered")
        #endif

        // Re-fetch messages from VPS
        Task {
            do {
                let response = try await VPSService.shared.getMessages(limit: 500)
                let fetchedMessages = response.messages.map { self.convertVPSMessage($0) }

                await MainActor.run {
                    let processedMessages = self.applyReadStatus(to: fetchedMessages)
                    let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processedMessages)
                    let newConversations = self.buildConversations(from: mergeResult.mergedMessages)

                    self.messages = mergeResult.mergedMessages
                    self.conversations = newConversations

                    #if DEBUG
                    print("[MessageStore] Manual sync: loaded \(fetchedMessages.count) messages, \(newConversations.count) conversations")
                    #endif
                }
            } catch {
                #if DEBUG
                print("[MessageStore] Manual sync error: \(error.localizedDescription)")
                #endif
            }
        }

        // Re-sync contacts
        startListeningForContactsVPS(userId: userId)

        // Refresh spam
        loadSpamMessagesFromVPS()
    }

    // MARK: - Polling Fallback

    /// Polling fallback that fetches new messages periodically.
    /// Only polls when WebSocket is disconnected (every 30 seconds).
    /// When WebSocket is healthy, polling is skipped entirely to avoid
    /// redundant network requests and battery drain.
    func startPollingFallback() {
        pollFallbackTask?.cancel()
        pollFallbackTask = Task { [weak self] in
            while !Task.isCancelled {
                let isWsConnected = VPSService.shared.isConnected
                let sleepSeconds: UInt64 = isWsConnected ? 120 : 30
                try? await Task.sleep(nanoseconds: sleepSeconds * 1_000_000_000)
                guard !Task.isCancelled else { break }
                guard let self = self else { break }

                // Skip polling when WebSocket is connected — real-time updates are sufficient
                guard !VPSService.shared.isConnected else { continue }

                #if DEBUG
                print("[MessageStore] WebSocket disconnected — polling for new messages")
                #endif

                do {
                    // Use the newest message date as the "after" parameter for incremental fetch
                    let afterTimestamp = self.lastPolledMessageDate > 0
                        ? self.lastPolledMessageDate
                        : (self.messages.map { $0.date }.max() ?? 0)

                    let response = try await VPSService.shared.getMessages(limit: 100, after: afterTimestamp)
                    let newMessages = response.messages.map { self.convertVPSMessage($0) }

                    if !newMessages.isEmpty {
                        // Track the newest date for next poll
                        if let newest = newMessages.map({ $0.date }).max() {
                            self.lastPolledMessageDate = newest
                        }

                        await MainActor.run {
                            var currentMessages = self.messages

                            // Merge new messages, skip duplicates
                            let existingIds = Set(currentMessages.map { $0.id })
                            let unique = newMessages.filter { !existingIds.contains($0.id) }

                            if !unique.isEmpty {
                                currentMessages.append(contentsOf: unique)
                                let processed = self.applyReadStatus(to: currentMessages)
                                let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processed)
                                let convos = self.buildConversations(from: mergeResult.mergedMessages)

                                self.messages = mergeResult.mergedMessages
                                self.conversations = convos

                                #if DEBUG
                                print("[MessageStore] Poll fallback: added \(unique.count) new messages")
                                #endif

                                // Notify for new incoming messages
                                for msg in unique where msg.isReceived {
                                    self.notificationService.showMessageNotification(
                                        from: msg.address,
                                        contactName: msg.contactName,
                                        body: msg.body,
                                        messageId: msg.id
                                    )
                                }
                            }
                        }
                    }
                } catch {
                    #if DEBUG
                    print("[MessageStore] Poll fallback error: \(error.localizedDescription)")
                    #endif
                }
            }
        }
    }

    // MARK: - Coalesced Rebuild

    /// Flushes the pending message buffer and rebuilds conversations once.
    /// Called either when the debounce timer fires (200ms of silence) or
    /// when the buffer reaches 50 messages, whichever comes first.
    func flushPendingRebuild() {
        pendingRebuildWork?.cancel()
        pendingRebuildWork = nil

        guard !pendingMessageBuffer.isEmpty else { return }

        let bufferedCount = pendingMessageBuffer.count
        let notificationsToSend = pendingNotificationBuffer

        // Clear buffers before rebuild
        pendingMessageBuffer.removeAll()
        pendingNotificationBuffer.removeAll()

        // Rebuild conversations from the full messages array (which already
        // contains the buffered messages — they were appended on arrival).
        let processedMessages = self.applyReadStatus(to: self.messages)
        let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processedMessages)
        let newConversations = self.buildConversations(from: mergeResult.mergedMessages)

        self.messages = mergeResult.mergedMessages
        self.conversations = newConversations

        // Send notifications for all buffered incoming messages
        for message in notificationsToSend {
            self.notificationService.showMessageNotification(
                from: message.address,
                contactName: message.contactName,
                body: message.body,
                messageId: message.id
            )
        }

        #if DEBUG
        if bufferedCount > 1 {
            print("[MessageStore VPS] Coalesced rebuild: \(bufferedCount) messages in single pass")
        }
        #endif
    }

    // MARK: - VPS Message Conversion

    /// Converts a VPSMessage to the local Message type
    func convertVPSMessage(_ vpsMessage: VPSMessage) -> Message {
        // Convert MMS parts to MmsAttachment array if present
        var attachments: [MmsAttachment]? = nil
        if let parts = vpsMessage.mmsParts {
            // MMS parts conversion
            attachments = parts.compactMap { part -> MmsAttachment? in
                let contentType = (part["contentType"] as? String)
                    ?? (part["content_type"] as? String)
                    ?? (part["mimeType"] as? String)
                    ?? (part["mime_type"] as? String)
                    ?? "application/octet-stream"

                let normalizedContentType = contentType.lowercased()
                let type: String
                if normalizedContentType.hasPrefix("image/") {
                    type = "image"
                } else if normalizedContentType.hasPrefix("video/") {
                    type = "video"
                } else if normalizedContentType.hasPrefix("audio/") {
                    type = "audio"
                } else if normalizedContentType.contains("vcard") {
                    type = "vcard"
                } else {
                    type = "file"
                }

                let fileName = (part["fileName"] as? String)
                    ?? (part["file_name"] as? String)
                    ?? (part["name"] as? String)

                let url = (part["url"] as? String)
                    ?? (part["downloadUrl"] as? String)
                    ?? (part["download_url"] as? String)

                let r2Key = (part["r2Key"] as? String)
                    ?? (part["fileKey"] as? String)
                    ?? (part["r2_key"] as? String)

                let inlineData = (part["data"] as? String)
                    ?? (part["inlineData"] as? String)
                    ?? (part["inline_data"] as? String)

                let encrypted: Bool?
                if let flag = part["encrypted"] as? Bool {
                    encrypted = flag
                } else if let flag = part["encrypted"] as? NSNumber {
                    encrypted = flag.boolValue
                } else {
                    encrypted = nil
                }

                let attachment = MmsAttachment(
                    id: (part["id"] as? String) ?? (part["partId"] as? String) ?? UUID().uuidString,
                    contentType: contentType,
                    fileName: fileName,
                    url: url,
                    r2Key: r2Key,
                    type: type,
                    encrypted: encrypted,
                    inlineData: inlineData,
                    isInline: inlineData != nil
                )
                return attachment
            }
        }

        var body = vpsMessage.body ?? ""
        var decryptionFailed = false
        var failureReason: String? = nil

        if vpsMessage.encrypted == true {
            if !E2EEManager.shared.isInitialized {
                decryptionFailed = true
                failureReason = "E2EE not initialized"
                body = "[🔒 Encrypted message - E2EE keys not loaded]"
            } else if let encryptedBody = vpsMessage.encryptedBody,
               let encryptedNonce = vpsMessage.encryptedNonce,
               let envelope = vpsMessage.keyMap?["syncGroup"] ?? vpsMessage.keyMap?[VPSService.shared.deviceId ?? ""] {
                guard let ciphertextData = Data(base64Encoded: encryptedBody),
                      let nonceData = Data(base64Encoded: encryptedNonce) else {
                    let hasAttachments = !(attachments?.isEmpty ?? true)
                    return Message(
                        id: vpsMessage.id, address: vpsMessage.address,
                        body: "[🔒 Encrypted message - invalid data]",
                        date: Double(vpsMessage.date), type: vpsMessage.type,
                        contactName: vpsMessage.contactName, isRead: vpsMessage.read,
                        isMms: vpsMessage.isMms || hasAttachments, attachments: attachments,
                        e2eeFailed: true, e2eeFailureReason: "Invalid base64",
                        isEncrypted: vpsMessage.encrypted
                    )
                }
                do {
                    let dataKey = try E2EEManager.shared.decryptDataKey(from: envelope)
                    body = try E2EEManager.shared.decryptMessageBody(
                        dataKey: dataKey,
                        ciphertextWithTag: ciphertextData,
                        nonce: nonceData
                    )
                } catch {
                    decryptionFailed = true
                    failureReason = "Key mismatch: \(error.localizedDescription)"
                    body = "[🔒 Encrypted message - sync keys to decrypt]"
                }
            } else {
                decryptionFailed = true
                let hasBody = vpsMessage.encryptedBody != nil
                let hasNonce = vpsMessage.encryptedNonce != nil
                let hasKeyMap = vpsMessage.keyMap != nil
                failureReason = "Missing data: body=\(hasBody), nonce=\(hasNonce), keyMap=\(hasKeyMap)"
                body = "[🔒 Encrypted message - missing encryption data]"
            }
        }

        let hasAttachments = !(attachments?.isEmpty ?? true)


        return Message(
            id: vpsMessage.id,
            address: vpsMessage.address,
            body: body,
            date: Double(vpsMessage.date),
            type: vpsMessage.type,
            contactName: vpsMessage.contactName,
            isRead: vpsMessage.read,
            isMms: vpsMessage.isMms || hasAttachments,
            attachments: attachments,
            e2eeFailed: decryptionFailed,
            e2eeFailureReason: failureReason,
            isEncrypted: vpsMessage.encrypted,
            deliveryStatus: vpsMessage.deliveryStatus
        )
    }
}
