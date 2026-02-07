//
//  GroupMessagingViewModel.swift
//  SyncFlowMac
//
//  Simple ViewModel for Group Messaging (friends groups)
//

import Foundation
// FirebaseDatabase - using FirebaseStubs.swift
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

    private let database = Database.database()
    private var groupsHandle: DatabaseHandle?
    private var contactsHandle: DatabaseHandle?
    private var currentGroupId: String?

    private var userId: String? {
        FirebaseService.shared.getCurrentUser()
    }

    // MARK: - Initialization

    init() {
        Task {
            await startListening()
        }
    }

    deinit {
        let db = database
        let uid = FirebaseService.shared.getCurrentUser()
        let gHandle = groupsHandle
        let cHandle = contactsHandle
        let cGroupId = currentGroupId

        if let userId = uid {
            if let handle = gHandle {
                db.reference().child("users").child(userId).child("friend_groups").removeObserver(withHandle: handle)
            }
            if let handle = cHandle, let groupId = cGroupId {
                db.reference().child("users").child(userId).child("friend_groups").child(groupId).child("contacts").removeObserver(withHandle: handle)
            }
        }
    }

    // MARK: - Listeners

    func startListening() async {
        guard let userId = userId else { return }

        let groupsRef = database.reference()
            .child("users")
            .child(userId)
            .child("friend_groups")

        groupsHandle = groupsRef.observe(.value) { [weak self] snapshot in
            Task { @MainActor in
                guard let self = self else { return }

                guard snapshot.exists(),
                      let groupsDict = snapshot.value as? [String: [String: Any]] else {
                    self.groups = []
                    return
                }

                self.groups = groupsDict.compactMap { (id, data) in
                    ContactGroup.from(data, id: id)
                }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            }
        }
    }

    func stopListening() {
        guard let userId = userId else { return }

        if let handle = groupsHandle {
            database.reference()
                .child("users")
                .child(userId)
                .child("friend_groups")
                .removeObserver(withHandle: handle)
        }

        stopListeningToContacts()
    }

    // MARK: - Group Management

    func createGroup(name: String) async throws {
        guard let userId = userId else {
            throw GroupError.notAuthenticated
        }

        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw GroupError.invalidGroupName
        }

        isLoading = true
        defer { isLoading = false }

        let groupRef = database.reference()
            .child("users")
            .child(userId)
            .child("friend_groups")
            .childByAutoId()

        let groupData: [String: Any] = [
            "name": name.trimmingCharacters(in: .whitespaces),
            "created_at": ServerValue.timestamp(),
            "contact_count": 0
        ]

        try await groupRef.setValue(groupData)
        successMessage = "Group '\(name)' created"
    }

    func deleteGroup(_ group: ContactGroup) async throws {
        guard let userId = userId else {
            throw GroupError.notAuthenticated
        }

        isLoading = true
        defer { isLoading = false }

        let groupRef = database.reference()
            .child("users")
            .child(userId)
            .child("friend_groups")
            .child(group.id)

        try await groupRef.removeValue()
        successMessage = "Group deleted"
    }

    func renameGroup(_ group: ContactGroup, to newName: String) async throws {
        guard let userId = userId else {
            throw GroupError.notAuthenticated
        }

        guard !newName.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw GroupError.invalidGroupName
        }

        isLoading = true
        defer { isLoading = false }

        let groupRef = database.reference()
            .child("users")
            .child(userId)
            .child("friend_groups")
            .child(group.id)
            .child("name")

        try await groupRef.setValue(newName.trimmingCharacters(in: .whitespaces))
        successMessage = "Group renamed"
    }

    // MARK: - Contact Management

    func loadGroupContacts(for group: ContactGroup) async throws {
        guard let userId = userId else {
            throw GroupError.notAuthenticated
        }

        if currentGroupId != group.id {
            stopListeningToContacts()
            currentGroupId = group.id
        }

        isLoading = true

        let contactsRef = database.reference()
            .child("users")
            .child(userId)
            .child("friend_groups")
            .child(group.id)
            .child("contacts")

        contactsHandle = contactsRef.observe(.value) { [weak self] snapshot in
            Task { @MainActor in
                guard let self = self else { return }
                self.isLoading = false

                guard snapshot.exists(),
                      let contactsDict = snapshot.value as? [String: [String: Any]] else {
                    self.groupContacts = []
                    return
                }

                self.groupContacts = contactsDict.compactMap { (id, data) in
                    GroupContact.from(data, id: id)
                }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            }
        }
    }

    func stopListeningToContacts() {
        guard let userId = userId, let groupId = currentGroupId, let handle = contactsHandle else { return }

        database.reference()
            .child("users")
            .child(userId)
            .child("friend_groups")
            .child(groupId)
            .child("contacts")
            .removeObserver(withHandle: handle)

        contactsHandle = nil
        currentGroupId = nil
    }

    func addContactToGroup(groupId: String, name: String, phone: String) async throws {
        guard let userId = userId else {
            throw GroupError.notAuthenticated
        }

        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw GroupError.invalidContactName
        }

        guard isValidPhoneNumber(phone) else {
            throw GroupError.invalidPhoneNumber
        }

        isLoading = true
        defer { isLoading = false }

        let normalizedPhone = normalizePhoneNumber(phone)
        let contactId = normalizedPhone.replacingOccurrences(of: "+", with: "")

        let contactRef = database.reference()
            .child("users")
            .child(userId)
            .child("friend_groups")
            .child(groupId)
            .child("contacts")
            .child(contactId)

        let contactData: [String: Any] = [
            "name": name.trimmingCharacters(in: .whitespaces),
            "phone": normalizedPhone,
            "added_at": ServerValue.timestamp()
        ]

        try await contactRef.setValue(contactData)
        try await updateContactCount(groupId: groupId)
        successMessage = "Contact added"
    }

    func addContactsToGroup(groupId: String, contacts: [GroupContact]) async throws {
        guard let userId = userId else {
            throw GroupError.notAuthenticated
        }

        isLoading = true
        defer { isLoading = false }

        var updates: [String: Any] = [:]

        for contact in contacts {
            let normalizedPhone = normalizePhoneNumber(contact.phone)
            let contactId = normalizedPhone.replacingOccurrences(of: "+", with: "")
            let path = "users/\(userId)/friend_groups/\(groupId)/contacts/\(contactId)"

            updates[path] = [
                "name": contact.name,
                "phone": normalizedPhone,
                "added_at": ServerValue.timestamp()
            ]
        }

        try await database.reference().updateChildValues(updates)
        try await updateContactCount(groupId: groupId)
        successMessage = "\(contacts.count) contacts added"
    }

    func removeContactFromGroup(groupId: String, contactId: String) async throws {
        guard let userId = userId else {
            throw GroupError.notAuthenticated
        }

        isLoading = true
        defer { isLoading = false }

        let contactRef = database.reference()
            .child("users")
            .child(userId)
            .child("friend_groups")
            .child(groupId)
            .child("contacts")
            .child(contactId)

        try await contactRef.removeValue()
        try await updateContactCount(groupId: groupId)
        successMessage = "Contact removed"
    }

    private func updateContactCount(groupId: String) async throws {
        guard let userId = userId else { return }

        let contactsRef = database.reference()
            .child("users")
            .child(userId)
            .child("friend_groups")
            .child(groupId)
            .child("contacts")

        let snapshot = try await contactsRef.getData()
        let count = Int(snapshot.childrenCount)

        let countRef = database.reference()
            .child("users")
            .child(userId)
            .child("friend_groups")
            .child(groupId)
            .child("contact_count")

        try await countRef.setValue(count)
    }

    // MARK: - Send Group Message

    /// Sends a message to all contacts in a group using existing SMS infrastructure
    func sendGroupMessage(group: ContactGroup, message: String) async throws {
        guard let userId = userId else {
            throw GroupError.notAuthenticated
        }

        guard !message.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw GroupError.emptyMessage
        }

        // Load contacts if not already loaded
        let contactsRef = database.reference()
            .child("users")
            .child(userId)
            .child("friend_groups")
            .child(group.id)
            .child("contacts")

        let snapshot = try await contactsRef.getData()

        guard snapshot.exists(),
              let contactsDict = snapshot.value as? [String: [String: Any]] else {
            throw GroupError.emptyGroup
        }

        let contacts = contactsDict.compactMap { (id, data) -> GroupContact? in
            GroupContact.from(data, id: id)
        }

        guard !contacts.isEmpty else {
            throw GroupError.emptyGroup
        }

        isLoading = true
        defer { isLoading = false }

        // Send message to each contact using FirebaseService
        for contact in contacts {
            do {
                try await FirebaseService.shared.sendMessage(
                    userId: userId,
                    to: contact.phone,
                    body: message
                )
            } catch {
                print("[GroupMessaging] Failed to send to \(contact.phone): \(error)")
                // Continue sending to other contacts even if one fails
            }
        }

        successMessage = "Message sent to \(contacts.count) contacts"
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
