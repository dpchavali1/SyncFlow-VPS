//
//  GroupDetailView.swift
//  SyncFlowMac
//
//  View for managing contacts within a group
//

import SwiftUI

struct GroupDetailView: View {
    @ObservedObject var viewModel: GroupMessagingViewModel
    let group: ContactGroup
    @Environment(\.dismiss) private var dismiss

    @State private var showingAddContact = false
    @State private var searchText = ""

    var filteredContacts: [GroupContact] {
        if searchText.isEmpty {
            return viewModel.groupContacts
        }
        return viewModel.groupContacts.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) ||
            $0.phone.contains(searchText)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(group.name)
                        .font(.title2)
                        .fontWeight(.semibold)

                    Text("\(viewModel.groupContacts.count) contacts")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                Button("Done") {
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)
            }
            .padding()

            Divider()

            // Toolbar
            HStack {
                // Search field
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.secondary)
                    TextField("Search contacts", text: $searchText)
                        .textFieldStyle(.plain)
                }
                .padding(8)
                .background(Color(nsColor: .controlBackgroundColor))
                .cornerRadius(8)

                Spacer()

                Button {
                    showingAddContact = true
                } label: {
                    Label("Add Contact", systemImage: "plus")
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()

            // Contact list
            if viewModel.groupContacts.isEmpty {
                ContentUnavailableView(
                    "No Contacts",
                    systemImage: "person.crop.circle.badge.plus",
                    description: Text("Add friends to this group")
                )
            } else {
                List {
                    ForEach(filteredContacts) { contact in
                        ContactRowView(contact: contact) {
                            Task {
                                try? await viewModel.removeContactFromGroup(
                                    groupId: group.id,
                                    contactId: contact.id
                                )
                            }
                        }
                    }
                }
            }
        }
        .frame(width: 450, height: 500)
        .onAppear {
            Task {
                try? await viewModel.loadGroupContacts(for: group)
            }
        }
        .onDisappear {
            viewModel.stopListeningToContacts()
        }
        .sheet(isPresented: $showingAddContact) {
            AddContactSheet(viewModel: viewModel, groupId: group.id, isPresented: $showingAddContact)
        }
    }
}

// MARK: - Contact Row

struct ContactRowView: View {
    let contact: GroupContact
    let onDelete: () -> Void

    var body: some View {
        HStack {
            // Avatar
            Circle()
                .fill(Color.accentColor.opacity(0.2))
                .frame(width: 36, height: 36)
                .overlay {
                    Text(contact.name.prefix(1).uppercased())
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(.accentColor)
                }

            VStack(alignment: .leading, spacing: 2) {
                Text(contact.name)
                    .fontWeight(.medium)

                Text(contact.formattedPhone)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Button {
                onDelete()
            } label: {
                Image(systemName: "trash")
                    .foregroundColor(.red)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Add Contact Sheet

struct AddContactSheet: View {
    @ObservedObject var viewModel: GroupMessagingViewModel
    let groupId: String
    @Binding var isPresented: Bool

    @State private var name = ""
    @State private var phone = ""
    @State private var addedCount = 0

    var body: some View {
        VStack(spacing: 20) {
            Text("Add Contact")
                .font(.title2)
                .fontWeight(.semibold)

            if addedCount > 0 {
                Text("\(addedCount) contact\(addedCount == 1 ? "" : "s") added")
                    .font(.caption)
                    .foregroundColor(.green)
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Name")
                    .font(.caption)
                    .foregroundColor(.secondary)
                TextField("John Doe", text: $name)
                    .textFieldStyle(.roundedBorder)
            }
            .frame(width: 300)

            VStack(alignment: .leading, spacing: 8) {
                Text("Phone Number")
                    .font(.caption)
                    .foregroundColor(.secondary)
                TextField("+1 (555) 123-4567", text: $phone)
                    .textFieldStyle(.roundedBorder)
            }
            .frame(width: 300)

            HStack(spacing: 12) {
                Button("Done") {
                    isPresented = false
                }
                .keyboardShortcut(.cancelAction)

                Button("Add & Close") {
                    Task {
                        do {
                            try await viewModel.addContactToGroup(
                                groupId: groupId,
                                name: name,
                                phone: phone
                            )
                            isPresented = false
                        } catch {
                            viewModel.errorMessage = error.localizedDescription
                        }
                    }
                }
                .disabled(name.isEmpty || phone.isEmpty)

                Button("Add Another") {
                    Task {
                        do {
                            try await viewModel.addContactToGroup(
                                groupId: groupId,
                                name: name,
                                phone: phone
                            )
                            addedCount += 1
                            name = ""
                            phone = ""
                        } catch {
                            viewModel.errorMessage = error.localizedDescription
                        }
                    }
                }
                .keyboardShortcut(.defaultAction)
                .buttonStyle(.borderedProminent)
                .disabled(name.isEmpty || phone.isEmpty)
            }
        }
        .padding(30)
        .frame(width: 400)
    }
}

// MARK: - Preview

#Preview {
    GroupDetailView(
        viewModel: GroupMessagingViewModel(),
        group: ContactGroup(
            id: "test",
            name: "Family",
            createdAt: Date().timeIntervalSince1970 * 1000,
            contactCount: 5,
            contacts: []
        )
    )
}
