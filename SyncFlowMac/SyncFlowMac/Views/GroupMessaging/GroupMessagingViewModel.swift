//
//  GroupMessagingViewModel.swift
//  SyncFlowMac
//
//  Simple ViewModel for Group Messaging (friends groups)
//

import Foundation
import Combine

@MainActor
class GroupMessagingViewModel: ObservableObject {
    // MARK: - Published Properties

    @Published var groups: [ContactGroup] = []
    @Published var groupContacts: [GroupContact] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var successMessage: String?

    // MARK: - Private Properties

    private var currentGroupId: String?

    private var userId: String? {
        UserDefaults.standard.string(forKey: "syncflow_user_id")
    }

    // MARK: - Initialization

    init() {
        // Group messaging not yet implemented via VPS
    }

    deinit {
        // No listeners to clean up
    }

    // MARK: - Listeners

    func startListening() async {
        // Group messaging listeners not yet implemented via VPS
    }

    func stopListening() {
        // No-op
    }

    // MARK: - Group Management

    func createGroup(name: String) async throws {
        guard userId != nil else {
            throw GroupError.notAuthenticated
        }

        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw GroupError.invalidGroupName
        }

        // Group creation not yet implemented via VPS
        errorMessage = "Group messaging is not yet available"
    }

    func deleteGroup(_ group: ContactGroup) async throws {
        guard userId != nil else {
            throw GroupError.notAuthenticated
        }

        // Group deletion not yet implemented via VPS
        errorMessage = "Group messaging is not yet available"
    }

    func renameGroup(_ group: ContactGroup, to newName: String) async throws {
        guard userId != nil else {
            throw GroupError.notAuthenticated
        }

        guard !newName.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw GroupError.invalidGroupName
        }

        // Group rename not yet implemented via VPS
        errorMessage = "Group messaging is not yet available"
    }

    // MARK: - Contact Management

    func loadGroupContacts(for group: ContactGroup) async throws {
        guard userId != nil else {
            throw GroupError.notAuthenticated
        }

        // Group contacts not yet implemented via VPS
    }

    func stopListeningToContacts() {
        currentGroupId = nil
    }

    func addContactToGroup(groupId: String, name: String, phone: String) async throws {
        guard userId != nil else {
            throw GroupError.notAuthenticated
        }

        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw GroupError.invalidContactName
        }

        guard isValidPhoneNumber(phone) else {
            throw GroupError.invalidPhoneNumber
        }

        // Not yet implemented via VPS
        errorMessage = "Group messaging is not yet available"
    }

    func addContactsToGroup(groupId: String, contacts: [GroupContact]) async throws {
        guard userId != nil else {
            throw GroupError.notAuthenticated
        }

        // Not yet implemented via VPS
        errorMessage = "Group messaging is not yet available"
    }

    func removeContactFromGroup(groupId: String, contactId: String) async throws {
        guard userId != nil else {
            throw GroupError.notAuthenticated
        }

        // Not yet implemented via VPS
        errorMessage = "Group messaging is not yet available"
    }

    // MARK: - Send Group Message

    /// Sends a message to all contacts in a group using VPS
    func sendGroupMessage(group: ContactGroup, message: String) async throws {
        guard userId != nil else {
            throw GroupError.notAuthenticated
        }

        guard !message.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw GroupError.emptyMessage
        }

        guard !groupContacts.isEmpty else {
            throw GroupError.emptyGroup
        }

        isLoading = true
        defer { isLoading = false }

        // Send message to each contact using VPS
        for contact in groupContacts {
            do {
                try await VPSService.shared.sendMessage(
                    address: contact.phone,
                    body: message
                )
            } catch {
                print("[GroupMessaging] Failed to send to \(contact.phone): \(error)")
                // Continue sending to other contacts even if one fails
            }
        }

        successMessage = "Message sent to \(groupContacts.count) contacts"
    }

    // MARK: - Helper Methods

    private func isValidPhoneNumber(_ phone: String) -> Bool {
        let digits = phone.filter { $0.isNumber }
        return digits.count >= 10 && digits.count <= 15
    }

    private func normalizePhoneNumber(_ phone: String) -> String {
        var digits = phone.filter { $0.isNumber }

        if digits.count == 10 {
            digits = "1" + digits
        }

        return "+\(digits)"
    }

    func clearError() {
        errorMessage = nil
    }

    func clearSuccess() {
        successMessage = nil
    }
}

// MARK: - Errors

enum GroupError: LocalizedError {
    case notAuthenticated
    case invalidGroupName
    case invalidContactName
    case invalidPhoneNumber
    case emptyMessage
    case emptyGroup

    var errorDescription: String? {
        switch self {
        case .notAuthenticated:
            return "Please sign in to use Group Messaging"
        case .invalidGroupName:
            return "Please enter a valid group name"
        case .invalidContactName:
            return "Please enter a valid contact name"
        case .invalidPhoneNumber:
            return "Please enter a valid phone number"
        case .emptyMessage:
            return "Please enter a message"
        case .emptyGroup:
            return "This group has no contacts"
        }
    }
}
