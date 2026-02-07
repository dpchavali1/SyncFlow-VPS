//
//  ScheduledMessagesView.swift
//  SyncFlowMac
//
//  View to manage scheduled messages - view, edit, cancel scheduled SMS
//

import SwiftUI

struct ScheduledMessagesView: View {
    @EnvironmentObject var appState: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var selectedMessage: ScheduledMessageService.ScheduledMessage?
    @State private var showEditSheet = false
    @State private var showDeleteConfirmation = false
    @State private var messageToDelete: ScheduledMessageService.ScheduledMessage?

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("Scheduled Messages")
                    .font(.headline)

                Spacer()

                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }
            .padding()

            Divider()

            if appState.scheduledMessageService.scheduledMessages.isEmpty {
                emptyStateView
            } else {
                messagesList
            }
        }
        .frame(width: 500, height: 400)
        .sheet(isPresented: $showEditSheet) {
            if let message = selectedMessage {
                EditScheduledMessageSheet(message: message, isPresented: $showEditSheet)
                    .environmentObject(appState)
            }
        }
        .alert("Cancel Scheduled Message?", isPresented: $showDeleteConfirmation) {
            Button("Keep", role: .cancel) {
                messageToDelete = nil
            }
            Button("Cancel Message", role: .destructive) {
                if let message = messageToDelete {
                    Task {
                        try? await appState.scheduledMessageService.cancelMessage(message.id)
                    }
                }
                messageToDelete = nil
            }
        } message: {
            if let message = messageToDelete {
                Text("This will cancel the message to \(message.recipientName ?? message.recipientNumber) scheduled for \(formatDate(message.scheduledTime)).")
            }
        }
    }

    private var emptyStateView: some View {
        VStack(spacing: 16) {
            Spacer()

            Image(systemName: "clock.badge.questionmark")
                .font(.system(size: 48))
                .foregroundColor(.secondary)

            Text("No Scheduled Messages")
                .font(.headline)
                .foregroundColor(.secondary)

            Text("Schedule a message by clicking the clock icon\nin the compose bar when writing a message.")
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var messagesList: some View {
        List {
            // Pending messages section
            if !appState.scheduledMessageService.pendingMessages.isEmpty {
                Section("Pending") {
                    ForEach(appState.scheduledMessageService.pendingMessages) { message in
                        ScheduledMessageRow(message: message)
                            .contextMenu {
                                Button {
                                    selectedMessage = message
                                    showEditSheet = true
                                } label: {
                                    Label("Edit", systemImage: "pencil")
                                }

                                Button(role: .destructive) {
                                    messageToDelete = message
                                    showDeleteConfirmation = true
                                } label: {
                                    Label("Cancel", systemImage: "xmark.circle")
                                }
                            }
                    }
                }
            }

            // Sent messages section
            if !appState.scheduledMessageService.sentMessages.isEmpty {
                Section("Sent") {
                    ForEach(appState.scheduledMessageService.sentMessages) { message in
                        ScheduledMessageRow(message: message)
                            .contextMenu {
                                Button(role: .destructive) {
                                    Task {
                                        try? await appState.scheduledMessageService.deleteMessage(message.id)
                                    }
                                } label: {
                                    Label("Remove from History", systemImage: "trash")
                                }
                            }
                    }
                }
            }

            // Failed messages section
            let failedMessages = appState.scheduledMessageService.scheduledMessages.filter { $0.status == .failed }
            if !failedMessages.isEmpty {
                Section("Failed") {
                    ForEach(failedMessages) { message in
                        ScheduledMessageRow(message: message)
                            .contextMenu {
                                Button(role: .destructive) {
                                    Task {
                                        try? await appState.scheduledMessageService.deleteMessage(message.id)
                                    }
                                } label: {
                                    Label("Remove", systemImage: "trash")
                                }
                            }
                    }
                }
            }
        }
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}

struct ScheduledMessageRow: View {
    let message: ScheduledMessageService.ScheduledMessage

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            // Status icon
            statusIcon
                .frame(width: 24, height: 24)

            VStack(alignment: .leading, spacing: 4) {
                // Recipient
                Text(message.recipientName ?? message.recipientNumber)
                    .font(.headline)

                // Message preview
                Text(message.message)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .lineLimit(2)

                // Time info
                HStack(spacing: 8) {
                    Image(systemName: "clock")
                        .font(.caption)

                    if message.status == .sent, let sentAt = message.sentAt {
                        Text("Sent \(formatDate(sentAt))")
                            .font(.caption)
                    } else if message.status == .failed {
                        Text("Failed: \(message.errorMessage ?? "Unknown error")")
                            .font(.caption)
                            .foregroundColor(.red)
                    } else {
                        Text(formatDate(message.scheduledTime))
                            .font(.caption)
                    }
                }
                .foregroundColor(.secondary)
            }

            Spacer()
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch message.status {
        case .pending:
            Image(systemName: "clock.fill")
                .foregroundColor(.orange)
        case .sent:
            Image(systemName: "checkmark.circle.fill")
                .foregroundColor(.green)
        case .failed:
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundColor(.red)
        case .cancelled:
            Image(systemName: "xmark.circle.fill")
                .foregroundColor(.secondary)
        }
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()

        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            formatter.dateFormat = "h:mm a 'Today'"
        } else if calendar.isDateInTomorrow(date) {
            formatter.dateFormat = "h:mm a 'Tomorrow'"
        } else {
            formatter.dateStyle = .short
            formatter.timeStyle = .short
        }

        return formatter.string(from: date)
    }
}

struct EditScheduledMessageSheet: View {
    let message: ScheduledMessageService.ScheduledMessage
    @Binding var isPresented: Bool
    @EnvironmentObject var appState: AppState

    @State private var editedMessage: String
    @State private var editedDate: Date
    @State private var isSaving = false

    init(message: ScheduledMessageService.ScheduledMessage, isPresented: Binding<Bool>) {
        self.message = message
        self._isPresented = isPresented
        self._editedMessage = State(initialValue: message.message)
        self._editedDate = State(initialValue: message.scheduledTime)
    }

    var body: some View {
        VStack(spacing: 16) {
            Text("Edit Scheduled Message")
                .font(.headline)

            VStack(alignment: .leading, spacing: 8) {
                Text("To: \(message.recipientName ?? message.recipientNumber)")
                    .font(.subheadline)
                    .foregroundColor(.secondary)

                TextEditor(text: $editedMessage)
                    .frame(height: 100)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color.secondary.opacity(0.3), lineWidth: 1)
                    )

                DatePicker("Send at:", selection: $editedDate, in: Date()...)
                    .datePickerStyle(.compact)
            }

            HStack {
                Button("Cancel") {
                    isPresented = false
                }
                .keyboardShortcut(.escape)

                Spacer()

                Button("Save") {
                    saveChanges()
                }
                .keyboardShortcut(.return)
                .disabled(isSaving || editedMessage.isEmpty)
            }
        }
        .padding()
        .frame(width: 400)
    }

    private func saveChanges() {
        isSaving = true
        Task {
            do {
                try await appState.scheduledMessageService.updateMessage(
                    message.id,
                    newMessage: editedMessage != message.message ? editedMessage : nil,
                    newScheduledTime: editedDate != message.scheduledTime ? editedDate : nil
                )
                await MainActor.run {
                    isPresented = false
                }
            } catch {
                print("Error updating scheduled message: \(error)")
            }
            await MainActor.run {
                isSaving = false
            }
        }
    }
}

// MARK: - Schedule Message Sheet (for composing new scheduled messages)

struct ScheduleMessageSheet: View {
    let recipientNumber: String
    let recipientName: String?
    @Binding var messageText: String
    @Binding var isPresented: Bool
    let onSchedule: (Date) -> Void

    @State private var scheduledDate = Date().addingTimeInterval(3600) // Default 1 hour from now
    @State private var isScheduling = false

    var body: some View {
        VStack(spacing: 16) {
            Text("Schedule Message")
                .font(.headline)

            VStack(alignment: .leading, spacing: 8) {
                Text("To: \(recipientName ?? recipientNumber)")
                    .font(.subheadline)
                    .foregroundColor(.secondary)

                Text("Message:")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Text(messageText)
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.secondary.opacity(0.1))
                    .cornerRadius(8)
                    .lineLimit(3)

                DatePicker("Send at:", selection: $scheduledDate, in: Date()...)
                    .datePickerStyle(.graphical)
            }

            HStack {
                Button("Cancel") {
                    isPresented = false
                }
                .keyboardShortcut(.escape)

                Spacer()

                Button("Schedule") {
                    onSchedule(scheduledDate)
                    isPresented = false
                }
                .keyboardShortcut(.return)
                .disabled(isScheduling || messageText.isEmpty)
            }
        }
        .padding()
        .frame(width: 350, height: 450)
    }
}

// Preview disabled - requires AppState environment object
