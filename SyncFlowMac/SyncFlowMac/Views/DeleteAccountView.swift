//
//  DeleteAccountView.swift
//  SyncFlowMac
//
//  Account deletion with 30-day grace period
//

import SwiftUI
import Combine
// FirebaseFunctions - using FirebaseStubs.swift

struct DeleteAccountView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var viewModel = DeleteAccountViewModel()

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Button(action: { dismiss() }) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)

                Spacer()

                Text("Delete Account")
                    .font(.headline)

                Spacer()

                // Spacer for symmetry
                Image(systemName: "xmark.circle.fill")
                    .font(.title2)
                    .opacity(0)
            }
            .padding()

            Divider()

            if viewModel.isLoading {
                Spacer()
                ProgressView()
                    .scaleEffect(1.5)
                Spacer()
            } else {
                ScrollView {
                    VStack(spacing: 20) {
                        // Status Card
                        if viewModel.isScheduledForDeletion {
                            scheduledDeletionCard
                        } else {
                            normalStateCard
                        }

                        // What gets deleted
                        deletionInfoCard

                        // Grace period info
                        gracePeriodCard

                        // Recovery code warning
                        recoveryCodeWarning

                        // Delete button (only if not scheduled)
                        if !viewModel.isScheduledForDeletion {
                            deleteButton
                        }
                    }
                    .padding()
                }
            }
        }
        .frame(width: 500, height: 600)
        .alert("Delete Account?", isPresented: $viewModel.showConfirmDialog) {
            Button("Cancel", role: .cancel) { }
            Button("Delete Account", role: .destructive) {
                viewModel.requestDeletion()
            }
        } message: {
            Text("Your account will be scheduled for deletion in 30 days. You can cancel anytime before then.")
        }
        .alert("Keep Your Account?", isPresented: $viewModel.showCancelDialog) {
            Button("Go Back", role: .cancel) { }
            Button("Keep Account") {
                viewModel.cancelDeletion()
            }
        } message: {
            Text("Your account will be restored and all your data will remain intact.")
        }
        .sheet(isPresented: $viewModel.showReasonPicker) {
            reasonPickerSheet
        }
        .sheet(isPresented: $viewModel.showFinalConfirmation) {
            finalConfirmationSheet
        }
    }

    // MARK: - Scheduled Deletion Card

    private var scheduledDeletionCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "clock.fill")
                    .foregroundColor(.red)
                Text("Account Scheduled for Deletion")
                    .font(.headline)
            }

            Text("Your account will be permanently deleted in \(viewModel.daysRemaining) days.")
                .foregroundColor(.secondary)

            if let date = viewModel.scheduledDeletionDate {
                Text("Deletion date: \(date, style: .date)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Button(action: { viewModel.showCancelDialog = true }) {
                HStack {
                    Image(systemName: "arrow.uturn.backward")
                    Text("Cancel Deletion & Keep Account")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(viewModel.isProcessing)
        }
        .padding()
        .background(Color.red.opacity(0.1))
        .cornerRadius(12)
    }

    // MARK: - Normal State Card

    private var normalStateCard: some View {
        VStack(spacing: 12) {
            Image(systemName: "person.crop.circle.badge.minus")
                .font(.system(size: 48))
                .foregroundColor(.red)

            Text("Delete Your Account")
                .font(.title2)
                .fontWeight(.bold)

            Text("We're sorry to see you go. Before you delete your account, please review what will happen:")
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Color(nsColor: .controlBackgroundColor))
        .cornerRadius(12)
    }

    // MARK: - Deletion Info Card

    private var deletionInfoCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("What will be deleted:")
                .font(.headline)

            DeletionInfoRow(icon: "message.fill", text: "All synced messages")
            DeletionInfoRow(icon: "desktopcomputer", text: "All connected devices")
            DeletionInfoRow(icon: "key.fill", text: "Your recovery code")
            DeletionInfoRow(icon: "creditcard.fill", text: "Subscription data")
            DeletionInfoRow(icon: "person.fill", text: "All personal information")
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(nsColor: .controlBackgroundColor))
        .cornerRadius(12)
    }

    // MARK: - Grace Period Card

    private var gracePeriodCard: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "info.circle.fill")
                .foregroundColor(.blue)

            VStack(alignment: .leading, spacing: 4) {
                Text("30-Day Grace Period")
                    .font(.headline)
                Text("Your account won't be deleted immediately. You have 30 days to change your mind and cancel the deletion.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.blue.opacity(0.1))
        .cornerRadius(12)
    }

    // MARK: - Recovery Code Warning

    private var recoveryCodeWarning: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(.orange)

            VStack(alignment: .leading, spacing: 4) {
                Text("Recovery Code Disabled")
                    .font(.headline)
                Text("Once scheduled for deletion, your recovery code will stop working immediately. You won't be able to recover your account on new devices.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.1))
        .cornerRadius(12)
    }

    // MARK: - Delete Button

    private var deleteButton: some View {
        VStack(spacing: 8) {
            Button(action: { viewModel.showReasonPicker = true }) {
                HStack {
                    Image(systemName: "trash.fill")
                    Text("Delete My Account")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
            .disabled(viewModel.isProcessing)

            Text("This will schedule your account for deletion in 30 days.")
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }

    // MARK: - Reason Picker Sheet

    private var reasonPickerSheet: some View {
        VStack(spacing: 20) {
            Text("Why are you leaving?")
                .font(.headline)

            VStack(alignment: .leading, spacing: 8) {
                ForEach(viewModel.deletionReasons, id: \.self) { reason in
                    Button(action: { viewModel.selectedReason = reason }) {
                        HStack {
                            Image(systemName: viewModel.selectedReason == reason ? "checkmark.circle.fill" : "circle")
                                .foregroundColor(viewModel.selectedReason == reason ? .blue : .secondary)
                            Text(reason)
                                .foregroundColor(.primary)
                            Spacer()
                        }
                        .padding(.vertical, 8)
                        .padding(.horizontal, 12)
                        .background(viewModel.selectedReason == reason ? Color.blue.opacity(0.1) : Color.clear)
                        .cornerRadius(8)
                    }
                    .buttonStyle(.plain)
                }
            }

            HStack {
                Button("Cancel") {
                    viewModel.showReasonPicker = false
                }
                .buttonStyle(.bordered)

                Spacer()

                Button("Continue") {
                    viewModel.showReasonPicker = false
                    viewModel.showFinalConfirmation = true
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
                .disabled(viewModel.selectedReason.isEmpty)
            }
        }
        .padding()
        .frame(width: 350)
    }

    // MARK: - Final Confirmation Sheet

    private var finalConfirmationSheet: some View {
        VStack(spacing: 20) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 60))
                .foregroundColor(.red)

            Text("Delete Your Account?")
                .font(.title.bold())

            Text("This will permanently delete:")
                .font(.headline)

            VStack(alignment: .leading, spacing: 8) {
                Label("All synced messages", systemImage: "message.fill")
                Label("All contacts", systemImage: "person.fill")
                Label("All call history", systemImage: "phone.fill")
                Label("Recovery code", systemImage: "key.fill")
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.red.opacity(0.1))
            .cornerRadius(8)

            VStack(alignment: .leading, spacing: 8) {
                Text("Type 'DELETE' to confirm:")
                    .font(.caption)
                    .foregroundColor(.secondary)

                TextField("", text: $viewModel.confirmDeleteText)
                    .textFieldStyle(.roundedBorder)
                    .disableAutocorrection(true)
            }

            HStack {
                Button("Cancel") {
                    viewModel.showFinalConfirmation = false
                    viewModel.confirmDeleteText = ""
                }
                .buttonStyle(.bordered)

                Spacer()

                Button("Delete Account") {
                    viewModel.requestDeletion()
                    viewModel.showFinalConfirmation = false
                    viewModel.confirmDeleteText = ""
                }
                .disabled(viewModel.confirmDeleteText != "DELETE")
                .buttonStyle(.borderedProminent)
                .tint(.red)
            }
        }
        .padding()
        .frame(width: 400)
    }
}

// MARK: - Deletion Info Row

private struct DeletionInfoRow: View {
    let icon: String
    let text: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .frame(width: 20)
                .foregroundColor(.red.opacity(0.7))
            Text(text)
        }
    }
}

// MARK: - View Model

class DeleteAccountViewModel: ObservableObject {
    @Published var isLoading = true
    @Published var isProcessing = false
    @Published var isScheduledForDeletion = false
    @Published var scheduledDeletionDate: Date?
    @Published var daysRemaining = 0
    @Published var showConfirmDialog = false
    @Published var showCancelDialog = false
    @Published var showReasonPicker = false
    @Published var selectedReason = ""
    @Published var errorMessage: String?
    @Published var confirmDeleteText = ""
    @Published var showFinalConfirmation = false

    let deletionReasons = [
        "I don't use the app anymore",
        "I'm switching to another app",
        "Privacy concerns",
        "Too many bugs or issues",
        "Missing features I need",
        "Other"
    ]

    private lazy var functions = Functions.functions()

    init() {
        checkDeletionStatus()
    }

    func checkDeletionStatus() {
        isLoading = true

        functions.httpsCallable("getAccountDeletionStatus").call { [weak self] result, error in
            DispatchQueue.main.async {
                self?.isLoading = false

                if let data = result?.data as? [String: Any] {
                    self?.isScheduledForDeletion = data["isScheduledForDeletion"] as? Bool ?? false

                    if let timestamp = data["scheduledDeletionAt"] as? Double {
                        self?.scheduledDeletionDate = Date(timeIntervalSince1970: timestamp / 1000)
                    }

                    self?.daysRemaining = data["daysRemaining"] as? Int ?? 0
                }
            }
        }
    }

    func requestDeletion() {
        isProcessing = true

        let data: [String: Any] = ["reason": selectedReason]

        functions.httpsCallable("requestAccountDeletion").call(data) { [weak self] result, error in
            DispatchQueue.main.async {
                self?.isProcessing = false

                if let error = error {
                    self?.errorMessage = error.localizedDescription
                    return
                }

                if let data = result?.data as? [String: Any],
                   let success = data["success"] as? Bool, success {

                    if let timestamp = data["scheduledDeletionAt"] as? Double {
                        self?.scheduledDeletionDate = Date(timeIntervalSince1970: timestamp / 1000)
                    }

                    self?.daysRemaining = 30
                    self?.isScheduledForDeletion = true
                }
            }
        }
    }

    func cancelDeletion() {
        isProcessing = true

        functions.httpsCallable("cancelAccountDeletion").call { [weak self] result, error in
            DispatchQueue.main.async {
                self?.isProcessing = false

                if let error = error {
                    self?.errorMessage = error.localizedDescription
                    return
                }

                if let data = result?.data as? [String: Any],
                   let success = data["success"] as? Bool, success {
                    self?.isScheduledForDeletion = false
                    self?.scheduledDeletionDate = nil
                }
            }
        }
    }
}

#Preview {
    DeleteAccountView()
}
