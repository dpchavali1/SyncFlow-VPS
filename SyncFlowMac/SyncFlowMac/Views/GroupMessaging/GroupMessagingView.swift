//
//  GroupMessagingView.swift
//  SyncFlowMac
//
//  Simple Group Messaging view for friends groups
//

import SwiftUI

struct GroupMessagingView: View {
    @StateObject private var viewModel = GroupMessagingViewModel()
    @State private var selectedGroup: ContactGroup?
    @State private var showingCompose = false

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Group Messages")
                        .font(.title2)
                        .fontWeight(.semibold)

                    Text("Send messages to friends groups")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                if let group = selectedGroup, group.contactCount > 0 {
                    Button {
                        showingCompose = true
                    } label: {
                        Label("Send to Group", systemImage: "paperplane")
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding()
            .background(Color(nsColor: .windowBackgroundColor))

            Divider()

            // Content
            GroupsListView(viewModel: viewModel, selectedGroup: $selectedGroup)
        }
        .frame(minWidth: 500, minHeight: 400)
        .alert("Error", isPresented: .constant(viewModel.errorMessage != nil)) {
            Button("OK") {
                viewModel.clearError()
            }
        } message: {
            if let error = viewModel.errorMessage {
                Text(error)
            }
        }
        .overlay {
            if let success = viewModel.successMessage {
                VStack {
                    Spacer()
                    HStack {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.green)
                        Text(success)
                    }
                    .padding()
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
                    .padding()
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .onAppear {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                        withAnimation {
                            viewModel.clearSuccess()
                        }
                    }
                }
            }
        }
        .animation(.easeInOut, value: viewModel.successMessage)
        .sheet(isPresented: $showingCompose) {
            if let group = selectedGroup {
                ComposeGroupMessageSheet(viewModel: viewModel, group: group, isPresented: $showingCompose)
            }
        }
    }
}

// MARK: - Groups List View

struct GroupsListView: View {
    @ObservedObject var viewModel: GroupMessagingViewModel
    @Binding var selectedGroup: ContactGroup?
    @State private var showingCreateGroup = false
    @State private var selectedGroupForDetail: ContactGroup?

    var body: some View {
        VStack {
            // Toolbar
            HStack {
                Text("\(viewModel.groups.count) groups")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Spacer()

                Button {
                    showingCreateGroup = true
                } label: {
                    Label("New Group", systemImage: "plus")
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 8)

            if viewModel.groups.isEmpty {
                ContentUnavailableView(
                    "No Groups",
                    systemImage: "person.3",
                    description: Text("Create a group to message friends together")
                )
            } else {
                List(viewModel.groups, selection: $selectedGroup) { group in
                    GroupRowView(group: group, viewModel: viewModel, onTap: {
                        selectedGroupForDetail = group
                    })
                    .tag(group)
                }
            }
        }
        .sheet(isPresented: $showingCreateGroup) {
            CreateGroupSheet(viewModel: viewModel, isPresented: $showingCreateGroup)
        }
        .sheet(item: $selectedGroupForDetail) { group in
            GroupDetailView(viewModel: viewModel, group: group)
        }
    }
}

// MARK: - Group Row View

struct GroupRowView: View {
    let group: ContactGroup
    @ObservedObject var viewModel: GroupMessagingViewModel
    let onTap: () -> Void
    @State private var showingRename = false
    @State private var newName = ""

    var body: some View {
        HStack {
            Image(systemName: "person.3.fill")
                .foregroundColor(.accentColor)
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 2) {
                Text(group.name)
                    .fontWeight(.medium)

                Text("\(group.contactCount) contacts")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Button {
                onTap()
            } label: {
                Image(systemName: "info.circle")
                    .foregroundColor(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .contextMenu {
            Button("View Contacts") {
                onTap()
            }

            Button("Rename") {
                newName = group.name
                showingRename = true
            }

            Divider()

            Button("Delete", role: .destructive) {
                Task {
                    try? await viewModel.deleteGroup(group)
                }
            }
        }
        .sheet(isPresented: $showingRename) {
            RenameGroupSheet(viewModel: viewModel, group: group, newName: $newName, isPresented: $showingRename)
        }
    }
}

// MARK: - Create Group Sheet

struct CreateGroupSheet: View {
    @ObservedObject var viewModel: GroupMessagingViewModel
    @Binding var isPresented: Bool
    @State private var groupName = ""

    var body: some View {
        VStack(spacing: 20) {
            Text("Create New Group")
                .font(.title2)
                .fontWeight(.semibold)

            TextField("Group Name (e.g., Family, Work Friends)", text: $groupName)
                .textFieldStyle(.roundedBorder)
                .frame(width: 300)

            HStack {
                Button("Cancel") {
                    isPresented = false
                }
                .keyboardShortcut(.cancelAction)

                Button("Create") {
                    Task {
                        do {
                            try await viewModel.createGroup(name: groupName)
                            isPresented = false
                        } catch {
                            viewModel.errorMessage = error.localizedDescription
                        }
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(groupName.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(30)
        .frame(width: 400)
    }
}

// MARK: - Rename Group Sheet

struct RenameGroupSheet: View {
    @ObservedObject var viewModel: GroupMessagingViewModel
    let group: ContactGroup
    @Binding var newName: String
    @Binding var isPresented: Bool

    var body: some View {
        VStack(spacing: 20) {
            Text("Rename Group")
                .font(.title2)
                .fontWeight(.semibold)

            TextField("Group Name", text: $newName)
                .textFieldStyle(.roundedBorder)
                .frame(width: 300)

            HStack {
                Button("Cancel") {
                    isPresented = false
                }
                .keyboardShortcut(.cancelAction)

                Button("Save") {
                    Task {
                        do {
                            try await viewModel.renameGroup(group, to: newName)
                            isPresented = false
                        } catch {
                            viewModel.errorMessage = error.localizedDescription
                        }
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(30)
        .frame(width: 400)
    }
}

// MARK: - Compose Group Message Sheet

struct ComposeGroupMessageSheet: View {
    @ObservedObject var viewModel: GroupMessagingViewModel
    let group: ContactGroup
    @Binding var isPresented: Bool
    @State private var messageText = ""
    @State private var isSending = false

    var body: some View {
        VStack(spacing: 20) {
            Text("Send to \(group.name)")
                .font(.title2)
                .fontWeight(.semibold)

            Text("This will send a message to all \(group.contactCount) contacts in this group")
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            TextEditor(text: $messageText)
                .font(.body)
                .frame(width: 350, height: 120)
                .border(Color.secondary.opacity(0.3))

            Text("\(messageText.count) characters")
                .font(.caption)
                .foregroundColor(.secondary)

            HStack {
                Button("Cancel") {
                    isPresented = false
                }
                .keyboardShortcut(.cancelAction)

                Button {
                    sendMessage()
                } label: {
                    if isSending {
                        ProgressView()
                            .scaleEffect(0.7)
                            .frame(width: 16, height: 16)
                        Text("Sending...")
                    } else {
                        Label("Send to \(group.contactCount) contacts", systemImage: "paperplane")
                    }
                }
                .keyboardShortcut(.defaultAction)
                .buttonStyle(.borderedProminent)
                .disabled(messageText.trimmingCharacters(in: .whitespaces).isEmpty || isSending)
            }
        }
        .padding(30)
        .frame(width: 450)
    }

    private func sendMessage() {
        isSending = true

        Task {
            do {
                try await viewModel.sendGroupMessage(group: group, message: messageText)
                isPresented = false
            } catch {
                viewModel.errorMessage = error.localizedDescription
            }
            isSending = false
        }
    }
}

// MARK: - Preview

#Preview {
    GroupMessagingView()
}
