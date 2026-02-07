//
//  PreferencesService.swift
//  SyncFlowMac
//
//  Created by SyncFlow Team
//  Copyright (c) SyncFlow. All rights reserved.
//
//  ============================================================================
//  PURPOSE
//  ============================================================================
//  PreferencesService manages all user preferences stored locally on the Mac.
//  Unlike Firebase-synced data, these preferences are device-local and persist
//  across app restarts via UserDefaults.
//
//  Key Responsibilities:
//  - Conversation organization: pinned, archived, blocked states
//  - Message read status tracking (local cache)
//  - Avatar color assignment for contacts
//  - Message templates for quick replies
//  - Conversation labels/tags
//  - Subscription/plan management
//
//  ============================================================================
//  ARCHITECTURE
//  ============================================================================
//  PreferencesService uses the Singleton pattern for global access. All data
//  is stored in UserDefaults with user-scoped keys to support multiple accounts.
//
//  Key Scoping:
//  - Base keys (e.g., "pinnedConversations") are scoped per user
//  - Format: "{baseKey}_{userId}" (e.g., "pinnedConversations_abc123")
//  - Migration from legacy unscoped keys happens automatically
//
//  Thread Safety:
//  - UserDefaults is thread-safe for reads and writes
//  - No additional synchronization needed
//
//  ============================================================================
//  DATA PERSISTENCE
//  ============================================================================
//  All data is stored in UserDefaults (~/Library/Preferences/com.syncflow.*.plist)
//  - Survives app updates
//  - Does NOT sync to iCloud (device-local only)
//  - Does NOT sync to Firebase (faster local operations)
//

import Foundation

// MARK: - PreferencesService

/// Service for managing local user preferences stored in UserDefaults.
///
/// PreferencesService provides a type-safe API for storing and retrieving
/// user preferences that are local to this Mac device. This includes
/// conversation organization (pin/archive/block), read status, and UI customizations.
///
/// ## Usage
/// ```swift
/// let prefs = PreferencesService.shared
///
/// // Pin a conversation
/// prefs.setPinned("+15551234567", pinned: true)
///
/// // Check if pinned
/// if prefs.isPinned("+15551234567") {
///     // Show pin indicator
/// }
/// ```
///
/// ## Multi-Account Support
/// Preferences are scoped to the current user ID, allowing multiple accounts
/// to have independent settings on the same Mac.
class PreferencesService {

    // MARK: - Singleton

    /// Shared singleton instance for app-wide preference access.
    static let shared = PreferencesService()

    // MARK: - Storage

    /// UserDefaults instance for data persistence.
    private let defaults = UserDefaults.standard

    // MARK: - Storage Keys

    /// Key for tracking which user owns the legacy (unscoped) preferences.
    private let preferenceOwnerKey = "preferences_owner_user_id"

    /// Base key for pinned conversation addresses.
    private let pinnedKey = "pinnedConversations"

    /// Base key for archived conversation addresses.
    private let archivedKey = "archivedConversations"

    /// Base key for blocked phone numbers.
    private let blockedKey = "blockedNumbers"

    /// Base key for read message IDs (local cache).
    private let readMessagesKey = "readMessages"

    /// Base key for contact avatar color assignments.
    private let avatarColorsKey = "avatarColors"

    /// Base key for saved message templates.
    private let templatesKey = "messageTemplates"

    /// Base key for message emoji reactions.
    private let reactionsKey = "messageReactions"

    /// Base key for custom conversation labels.
    private let labelsKey = "conversationLabels"

    /// Base key for label-to-conversation assignments.
    private let labelAssignmentsKey = "labelAssignments"

    // MARK: - Initialization

    /// Private initializer enforcing singleton pattern.
    private init() {}

    // MARK: - User Scoping

    /// The currently authenticated user's ID, used for preference scoping.
    private var currentUserId: String? {
        defaults.string(forKey: "syncflow_user_id")?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Generates a user-scoped storage key from a base key.
    ///
    /// - Parameter base: The base preference key (e.g., "pinnedConversations")
    /// - Returns: Scoped key (e.g., "pinnedConversations_userId123")
    private func scopedKey(for base: String) -> String {
        guard let userId = currentUserId, !userId.isEmpty else {
            return base // Fall back to unscoped key if no user
        }
        return "\(base)_\(userId)"
    }

    // MARK: - Generic Storage Helpers

    /// Reads a string array preference with automatic user scoping and migration.
    ///
    /// Handles migration from legacy unscoped keys to user-scoped keys.
    ///
    /// - Parameter base: The base preference key
    /// - Returns: Array of strings (empty array if not found)
    private func readStringArray(for base: String) -> [String] {
        let key = scopedKey(for: base)

        // For scoped keys, check for existing data
        if key != base {
            if let stored = defaults.stringArray(forKey: key) {
                return stored
            }
            // Migrate legacy data if this user owns it
            if defaults.string(forKey: preferenceOwnerKey) == currentUserId,
               let legacy = defaults.stringArray(forKey: base) {
                // Move legacy data to scoped key
                defaults.set(legacy, forKey: key)
                defaults.removeObject(forKey: base)
                return legacy
            }
            return []
        }

        return defaults.stringArray(forKey: base) ?? []
    }

    /// Writes a string array preference with automatic user scoping.
    ///
    /// - Parameters:
    ///   - values: Array of strings to store
    ///   - base: The base preference key
    private func writeStringArray(_ values: [String], for base: String) {
        let key = scopedKey(for: base)
        defaults.set(values, forKey: key)
        // Track ownership for migration purposes
        if key != base {
            defaults.set(currentUserId, forKey: preferenceOwnerKey)
        }
    }

    // MARK: - Pinned Conversations

    /// Checks if a conversation is pinned.
    ///
    /// - Parameter address: The phone number or contact address
    /// - Returns: true if the conversation is pinned
    func isPinned(_ address: String) -> Bool {
        let pinned = readStringArray(for: pinnedKey)
        return pinned.contains(address)
    }

    /// Sets the pinned state for a conversation.
    ///
    /// Pinned conversations appear at the top of the conversation list.
    ///
    /// - Parameters:
    ///   - address: The phone number or contact address
    ///   - pinned: true to pin, false to unpin
    func setPinned(_ address: String, pinned: Bool) {
        var pinnedList = readStringArray(for: pinnedKey)

        if pinned {
            if !pinnedList.contains(address) {
                pinnedList.append(address)
            }
        } else {
            pinnedList.removeAll { $0 == address }
        }

        writeStringArray(pinnedList, for: pinnedKey)
    }

    /// Gets all pinned conversation addresses.
    ///
    /// - Returns: Array of pinned phone numbers/addresses
    func getAllPinned() -> [String] {
        return readStringArray(for: pinnedKey)
    }

    // MARK: - Archived Conversations

    /// Checks if a conversation is archived.
    ///
    /// - Parameter address: The phone number or contact address
    /// - Returns: true if the conversation is archived
    func isArchived(_ address: String) -> Bool {
        let archived = readStringArray(for: archivedKey)
        return archived.contains(address)
    }

    /// Sets the archived state for a conversation.
    ///
    /// Archived conversations are hidden from the main list but remain accessible
    /// via the "Archived" filter.
    ///
    /// - Parameters:
    ///   - address: The phone number or contact address
    ///   - archived: true to archive, false to unarchive
    func setArchived(_ address: String, archived: Bool) {
        var archivedList = readStringArray(for: archivedKey)

        if archived {
            if !archivedList.contains(address) {
                archivedList.append(address)
            }
        } else {
            archivedList.removeAll { $0 == address }
        }

        writeStringArray(archivedList, for: archivedKey)
    }

    /// Gets all archived conversation addresses.
    ///
    /// - Returns: Array of archived phone numbers/addresses
    func getAllArchived() -> [String] {
        return readStringArray(for: archivedKey)
    }

    // MARK: - Blocked Numbers

    /// Checks if a number is blocked.
    ///
    /// - Parameter address: The phone number or contact address
    /// - Returns: true if the number is blocked
    func isBlocked(_ address: String) -> Bool {
        let blocked = readStringArray(for: blockedKey)
        return blocked.contains(address)
    }

    /// Sets the blocked state for a number.
    ///
    /// Blocked numbers are completely hidden from the conversation list
    /// and do not trigger notifications.
    ///
    /// - Parameters:
    ///   - address: The phone number or contact address
    ///   - blocked: true to block, false to unblock
    func setBlocked(_ address: String, blocked: Bool) {
        var blockedList = readStringArray(for: blockedKey)

        if blocked {
            if !blockedList.contains(address) {
                blockedList.append(address)
            }
        } else {
            blockedList.removeAll { $0 == address }
        }

        writeStringArray(blockedList, for: blockedKey)
    }

    /// Gets all blocked numbers.
    ///
    /// - Returns: Array of blocked phone numbers/addresses
    func getAllBlocked() -> [String] {
        return readStringArray(for: blockedKey)
    }

    // MARK: - Read Messages

    /// Marks a single message as read locally.
    ///
    /// This is a local cache to track what the user has seen on this Mac.
    /// Firebase read receipts provide cross-device read status.
    ///
    /// - Parameter messageId: The message ID to mark as read
    func markMessageAsRead(_ messageId: String) {
        var readMessages = readStringArray(for: readMessagesKey)
        if !readMessages.contains(messageId) {
            readMessages.append(messageId)
            writeStringArray(readMessages, for: readMessagesKey)
        }
    }

    /// Checks if a message has been marked as read locally.
    ///
    /// - Parameter messageId: The message ID to check
    /// - Returns: true if marked as read on this device
    func isMessageRead(_ messageId: String) -> Bool {
        let readMessages = readStringArray(for: readMessagesKey)
        return readMessages.contains(messageId)
    }

    /// Marks all messages in a conversation as read.
    ///
    /// - Parameters:
    ///   - address: The conversation address (for logging/context)
    ///   - messageIds: Array of message IDs to mark as read
    func markConversationAsRead(_ address: String, messageIds: [String]) {
        var readMessages = readStringArray(for: readMessagesKey)
        for id in messageIds where !readMessages.contains(id) {
            readMessages.append(id)
        }
        writeStringArray(readMessages, for: readMessagesKey)
    }

    // MARK: - Avatar Colors

    /// Gets the avatar color assigned to a contact.
    ///
    /// If no color is assigned, generates and stores a random color from the palette.
    ///
    /// - Parameter address: The phone number or contact address
    /// - Returns: Hex color string (e.g., "#2196F3")
    func getAvatarColor(for address: String) -> String {
        let colors = defaults.dictionary(forKey: avatarColorsKey) as? [String: String] ?? [:]
        return colors[address] ?? generateRandomColor()
    }

    /// Bulk reads all preferences for efficient batch processing.
    ///
    /// This method avoids multiple UserDefaults reads when building conversations,
    /// improving performance for large message sets.
    ///
    /// - Returns: Tuple containing Sets for O(1) lookup and avatar color dictionary
    func getAllPreferenceSets() -> (pinned: Set<String>, archived: Set<String>, blocked: Set<String>, avatarColors: [String: String]) {
        let pinned = Set(readStringArray(for: pinnedKey))
        let archived = Set(readStringArray(for: archivedKey))
        let blocked = Set(readStringArray(for: blockedKey))
        let colors = defaults.dictionary(forKey: avatarColorsKey) as? [String: String] ?? [:]
        return (pinned, archived, blocked, colors)
    }

    /// Generates a random color from the Material Design palette.
    ///
    /// - Returns: Random hex color string
    private func generateRandomColor() -> String {
        let colors = [
            "#4CAF50", "#2196F3", "#9C27B0", "#FF9800",
            "#F44336", "#009688", "#3F51B5", "#FF5722",
            "#795548", "#607D8B", "#E91E63", "#00BCD4"
        ]
        return colors.randomElement() ?? "#2196F3"
    }

    // MARK: - Message Templates

    /// A saved message template for quick replies.
    ///
    /// Templates allow users to save frequently used responses
    /// and insert them quickly when composing messages.
    struct MessageTemplate: Codable, Identifiable {
        /// Unique identifier for the template.
        let id: String
        /// User-defined name for the template (e.g., "Away Message").
        let name: String
        /// The actual message content to insert.
        let content: String
        /// When the template was created.
        let createdAt: Date
    }

    /// Gets all saved message templates.
    ///
    /// - Returns: Array of templates, empty if none saved
    func getTemplates() -> [MessageTemplate] {
        guard let data = defaults.data(forKey: templatesKey) else {
            return []
        }

        do {
            return try JSONDecoder().decode([MessageTemplate].self, from: data)
        } catch {
            print("❌ Error decoding templates: \(error)")
            return []
        }
    }

    func saveTemplate(name: String, content: String) {
        var templates = getTemplates()
        let template = MessageTemplate(
            id: UUID().uuidString,
            name: name,
            content: content,
            createdAt: Date()
        )
        templates.append(template)

        if let data = try? JSONEncoder().encode(templates) {
            defaults.set(data, forKey: templatesKey)
        }
    }

    func updateTemplate(id: String, name: String, content: String) {
        var templates = getTemplates()
        if let index = templates.firstIndex(where: { $0.id == id }) {
            templates[index] = MessageTemplate(
                id: id,
                name: name,
                content: content,
                createdAt: templates[index].createdAt
            )
            if let data = try? JSONEncoder().encode(templates) {
                defaults.set(data, forKey: templatesKey)
            }
        }
    }

    func deleteTemplate(id: String) {
        var templates = getTemplates()
        templates.removeAll { $0.id == id }
        if let data = try? JSONEncoder().encode(templates) {
            defaults.set(data, forKey: templatesKey)
        }
    }

    // MARK: - Message Reactions

    func getReaction(for messageId: String) -> String? {
        let reactions = defaults.dictionary(forKey: reactionsKey) as? [String: String] ?? [:]
        return reactions[messageId]
    }

    func setReaction(_ reaction: String?, for messageId: String) {
        var reactions = defaults.dictionary(forKey: reactionsKey) as? [String: String] ?? [:]
        if let reaction = reaction, !reaction.isEmpty {
            reactions[messageId] = reaction
        } else {
            reactions.removeValue(forKey: messageId)
        }
        defaults.set(reactions, forKey: reactionsKey)
    }

    // MARK: - Conversation Labels

    struct ConversationLabel: Codable, Identifiable, Equatable {
        let id: String
        var name: String
        var color: String
        var icon: String
        let createdAt: Date

        static let defaultLabels: [ConversationLabel] = [
            ConversationLabel(id: "work", name: "Work", color: "#2196F3", icon: "briefcase.fill", createdAt: Date()),
            ConversationLabel(id: "personal", name: "Personal", color: "#4CAF50", icon: "person.fill", createdAt: Date()),
            ConversationLabel(id: "family", name: "Family", color: "#E91E63", icon: "heart.fill", createdAt: Date()),
            ConversationLabel(id: "important", name: "Important", color: "#FF9800", icon: "star.fill", createdAt: Date())
        ]

        static let availableColors: [String] = [
            "#2196F3", "#4CAF50", "#E91E63", "#FF9800",
            "#9C27B0", "#00BCD4", "#F44336", "#795548",
            "#607D8B", "#3F51B5", "#009688", "#FF5722"
        ]

        static let availableIcons: [String] = [
            "briefcase.fill", "person.fill", "heart.fill", "star.fill",
            "house.fill", "tag.fill", "folder.fill", "flag.fill",
            "bell.fill", "bookmark.fill", "cart.fill", "creditcard.fill",
            "airplane", "car.fill", "graduationcap.fill", "gamecontroller.fill"
        ]
    }

    /// Get all labels
    func getLabels() -> [ConversationLabel] {
        guard let data = defaults.data(forKey: labelsKey) else {
            // Return default labels on first run
            let defaultLabels = ConversationLabel.defaultLabels
            if let encoded = try? JSONEncoder().encode(defaultLabels) {
                defaults.set(encoded, forKey: labelsKey)
            }
            return defaultLabels
        }

        do {
            return try JSONDecoder().decode([ConversationLabel].self, from: data)
        } catch {
            print("Error decoding labels: \(error)")
            return ConversationLabel.defaultLabels
        }
    }

    /// Create a new label
    func createLabel(name: String, color: String, icon: String) -> ConversationLabel {
        var labels = getLabels()
        let label = ConversationLabel(
            id: UUID().uuidString,
            name: name,
            color: color,
            icon: icon,
            createdAt: Date()
        )
        labels.append(label)
        saveLabels(labels)
        return label
    }

    /// Update a label
    func updateLabel(_ label: ConversationLabel) {
        var labels = getLabels()
        if let index = labels.firstIndex(where: { $0.id == label.id }) {
            labels[index] = label
            saveLabels(labels)
        }
    }

    /// Delete a label
    func deleteLabel(id: String) {
        var labels = getLabels()
        labels.removeAll { $0.id == id }
        saveLabels(labels)

        // Remove all assignments for this label
        var assignments = getLabelAssignments()
        for (address, labelIds) in assignments {
            var updatedIds = labelIds
            updatedIds.removeAll { $0 == id }
            if updatedIds.isEmpty {
                assignments.removeValue(forKey: address)
            } else {
                assignments[address] = updatedIds
            }
        }
        saveLabelAssignments(assignments)
    }

    private func saveLabels(_ labels: [ConversationLabel]) {
        if let data = try? JSONEncoder().encode(labels) {
            defaults.set(data, forKey: labelsKey)
        }
    }

    // MARK: - Label Assignments

    /// Get all label assignments (address -> [labelId])
    func getLabelAssignments() -> [String: [String]] {
        guard let data = defaults.data(forKey: labelAssignmentsKey),
              let assignments = try? JSONDecoder().decode([String: [String]].self, from: data) else {
            return [:]
        }
        return assignments
    }

    /// Get labels for a conversation
    func getLabels(for address: String) -> [ConversationLabel] {
        let assignments = getLabelAssignments()
        let labelIds = assignments[address] ?? []
        let allLabels = getLabels()
        return allLabels.filter { labelIds.contains($0.id) }
    }

    /// Add a label to a conversation
    func addLabel(_ labelId: String, to address: String) {
        var assignments = getLabelAssignments()
        var labelIds = assignments[address] ?? []
        if !labelIds.contains(labelId) {
            labelIds.append(labelId)
            assignments[address] = labelIds
            saveLabelAssignments(assignments)
        }
    }

    /// Remove a label from a conversation
    func removeLabel(_ labelId: String, from address: String) {
        var assignments = getLabelAssignments()
        var labelIds = assignments[address] ?? []
        labelIds.removeAll { $0 == labelId }
        if labelIds.isEmpty {
            assignments.removeValue(forKey: address)
        } else {
            assignments[address] = labelIds
        }
        saveLabelAssignments(assignments)
    }

    /// Toggle a label for a conversation
    func toggleLabel(_ labelId: String, for address: String) {
        let assignments = getLabelAssignments()
        let labelIds = assignments[address] ?? []
        if labelIds.contains(labelId) {
            removeLabel(labelId, from: address)
        } else {
            addLabel(labelId, to: address)
        }
    }

    /// Get all conversations with a specific label
    func getConversations(with labelId: String) -> [String] {
        let assignments = getLabelAssignments()
        return assignments.compactMap { address, labelIds in
            labelIds.contains(labelId) ? address : nil
        }
    }

    private func saveLabelAssignments(_ assignments: [String: [String]]) {
        if let data = try? JSONEncoder().encode(assignments) {
            defaults.set(data, forKey: labelAssignmentsKey)
        }
    }

    // MARK: - Subscription/Plan Management

    /// Storage key for the user's subscription plan.
    private let userPlanKey = "user_plan"

    /// Storage key for plan expiration timestamp (milliseconds).
    private let planExpiresAtKey = "plan_expires_at"

    /// Storage key for free trial expiration timestamp (milliseconds).
    private let freeTrialExpiresAtKey = "free_trial_expires_at"

    /// The user's current subscription plan.
    ///
    /// Possible values: "free", "monthly", "yearly", "lifetime", "3year"
    var userPlan: String {
        get {
            defaults.string(forKey: userPlanKey) ?? "free"
        }
        set {
            defaults.set(newValue, forKey: userPlanKey)
        }
    }

    /// Plan expiration timestamp in milliseconds since epoch.
    /// Zero means no expiration (or free plan).
    var planExpiresAt: Int64 {
        get {
            defaults.object(forKey: planExpiresAtKey) as? Int64 ?? 0
        }
        set {
            defaults.set(newValue, forKey: planExpiresAtKey)
        }
    }

    /// Free trial expiration timestamp in milliseconds since epoch.
    var freeTrialExpiresAt: Int64 {
        get {
            defaults.object(forKey: freeTrialExpiresAtKey) as? Int64 ?? 0
        }
        set {
            defaults.set(newValue, forKey: freeTrialExpiresAtKey)
        }
    }

    /// Checks if the user has an active paid subscription.
    ///
    /// - Returns: true if user has monthly, yearly, lifetime, or 3-year plan that hasn't expired
    func isPaidUser() -> Bool {
        let plan = userPlan.lowercased()
        let isPaid = ["monthly", "yearly", "lifetime", "3year"].contains(plan)
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // Check if plan has expired
        if isPaid && planExpiresAt > 0 && planExpiresAt < now {
            return false // Plan expired, treat as free
        }
        return isPaid
    }

    /// Checks if the free trial is still active.
    ///
    /// Automatically initializes the 7-day trial on first call if not set.
    ///
    /// - Returns: true if within the 7-day free trial period
    func isFreeTrial() -> Bool {
        if isPaidUser() { return false }

        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // Initialize trial on first use (7 days from now)
        if freeTrialExpiresAt == 0 {
            let trialExpiry = now + (7 * 24 * 60 * 60 * 1000)
            setFreeTrialExpiry(trialExpiry)
            return true
        }

        return freeTrialExpiresAt > now
    }

    /// Gets the number of days remaining in the free trial.
    ///
    /// - Returns: Number of days remaining, or 0 if trial expired or user is paid
    func getTrialDaysRemaining() -> Int {
        if isPaidUser() { return 0 }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let remaining = (freeTrialExpiresAt - now) / (24 * 60 * 60 * 1000)
        return max(0, Int(remaining))
    }

    /// Checks if user is limited to SMS-only features (no MMS/advanced features).
    ///
    /// - Returns: true if user is on free plan (not paid)
    func isSmsOnlyUser() -> Bool {
        return !isPaidUser()
    }

    /// Updates the user's subscription plan.
    ///
    /// - Parameters:
    ///   - plan: Plan identifier ("free", "monthly", "yearly", "lifetime", "3year")
    ///   - expiresAt: Expiration timestamp in milliseconds (0 for no expiration)
    func setUserPlan(_ plan: String, expiresAt: Int64 = 0) {
        userPlan = plan
        planExpiresAt = expiresAt
    }

    /// Sets the free trial expiration time.
    ///
    /// - Parameter expiryTime: Expiration timestamp in milliseconds since epoch
    func setFreeTrialExpiry(_ expiryTime: Int64) {
        freeTrialExpiresAt = expiryTime
    }
}
