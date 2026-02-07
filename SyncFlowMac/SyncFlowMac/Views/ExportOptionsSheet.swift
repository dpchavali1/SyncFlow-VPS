//
//  ExportOptionsSheet.swift
//  SyncFlowMac
//
//  UI sheet for selecting export format and options
//

import SwiftUI
import AppKit
import UniformTypeIdentifiers

struct ExportOptionsSheet: View {
    let conversation: Conversation
    let messages: [Message]
    @Binding var isPresented: Bool

    @State private var selectedFormat: ExportService.ExportFormat = .pdf
    @State private var includeTimestamps = true
    @State private var includeAttachmentInfo = true
    @State private var filterByDate = false
    @State private var startDate = Calendar.current.date(byAdding: .month, value: -1, to: Date())!
    @State private var endDate = Date()
    @State private var isExporting = false
    @State private var exportError: String?
    @State private var showError = false

    private var filteredMessageCount: Int {
        if filterByDate {
            return messages.filter { msg in
                let msgDate = Date(timeIntervalSince1970: msg.date / 1000)
                return msgDate >= startDate && msgDate <= endDate
            }.count
        }
        return messages.count
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Export Conversation")
                        .font(.title2)
                        .fontWeight(.semibold)

                    Text("\(conversation.displayName) \u{2022} \(filteredMessageCount) message\(filteredMessageCount == 1 ? "" : "s")")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }

                Spacer()

                Button(action: { isPresented = false }) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }
            .padding()

            Divider()

            // Options
            VStack(alignment: .leading, spacing: 20) {
                // Format picker
                VStack(alignment: .leading, spacing: 8) {
                    Text("Format")
                        .font(.headline)

                    Picker("Format", selection: $selectedFormat) {
                        ForEach(ExportService.ExportFormat.allCases) { format in
                            Text(format.rawValue).tag(format)
                        }
                    }
                    .pickerStyle(.segmented)
                    .frame(maxWidth: 250)
                }

                // Options toggles
                VStack(alignment: .leading, spacing: 12) {
                    Toggle(isOn: $includeTimestamps) {
                        HStack {
                            Image(systemName: "clock")
                                .foregroundColor(.blue)
                            Text("Include timestamps")
                        }
                    }
                    .toggleStyle(.checkbox)

                    Toggle(isOn: $includeAttachmentInfo) {
                        HStack {
                            Image(systemName: "paperclip")
                                .foregroundColor(.blue)
                            Text("Include attachment info")
                        }
                    }
                    .toggleStyle(.checkbox)
                }

                // Date range filter
                VStack(alignment: .leading, spacing: 12) {
                    Toggle(isOn: $filterByDate) {
                        HStack {
                            Image(systemName: "calendar")
                                .foregroundColor(.blue)
                            Text("Filter by date range")
                        }
                    }
                    .toggleStyle(.checkbox)

                    if filterByDate {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("From")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                DatePicker("", selection: $startDate, displayedComponents: .date)
                                    .labelsHidden()
                                    .datePickerStyle(.field)
                            }

                            VStack(alignment: .leading, spacing: 4) {
                                Text("To")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                DatePicker("", selection: $endDate, displayedComponents: .date)
                                    .labelsHidden()
                                    .datePickerStyle(.field)
                            }
                        }
                        .padding(.leading, 24)
                    }
                }

                Spacer()
            }
            .padding()

            Divider()

            // Footer with buttons
            HStack {
                if isExporting {
                    ProgressView()
                        .scaleEffect(0.8)
                    Text("Exporting...")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                Button("Cancel") {
                    isPresented = false
                }
                .keyboardShortcut(.escape, modifiers: [])

                Button("Export") {
                    performExport()
                }
                .keyboardShortcut(.return, modifiers: [])
                .buttonStyle(.borderedProminent)
                .disabled(isExporting || filteredMessageCount == 0)
            }
            .padding()
        }
        .frame(width: 400, height: 380)
        .alert("Export Failed", isPresented: $showError) {
            Button("OK") {
                showError = false
            }
        } message: {
            if let error = exportError {
                Text(error)
            }
        }
    }

    private func performExport() {
        isExporting = true

        Task {
            do {
                let dateRange: ClosedRange<Date>? = filterByDate ? startDate...endDate : nil

                let options = ExportService.ExportOptions(
                    format: selectedFormat,
                    includeTimestamps: includeTimestamps,
                    includeAttachmentInfo: includeAttachmentInfo,
                    dateRange: dateRange
                )

                let tempFileURL = try await ExportService.shared.exportConversation(
                    conversation: conversation,
                    messages: messages,
                    options: options
                )

                await MainActor.run {
                    isExporting = false

                    // Show save dialog
                    let savePanel = NSSavePanel()
                    savePanel.title = "Save Exported Conversation"
                    savePanel.nameFieldStringValue = tempFileURL.lastPathComponent
                    if let contentType = UTType(filenameExtension: selectedFormat.fileExtension) {
                        savePanel.allowedContentTypes = [contentType]
                    }
                    savePanel.canCreateDirectories = true

                    savePanel.begin { response in
                        if response == .OK, let destinationURL = savePanel.url {
                            do {
                                // Remove existing file if it exists
                                if FileManager.default.fileExists(atPath: destinationURL.path) {
                                    try FileManager.default.removeItem(at: destinationURL)
                                }

                                // Copy temp file to destination
                                try FileManager.default.copyItem(at: tempFileURL, to: destinationURL)

                                // Clean up temp file
                                try? FileManager.default.removeItem(at: tempFileURL)

                                // Close sheet
                                isPresented = false

                                // Optionally reveal in Finder
                                NSWorkspace.shared.selectFile(destinationURL.path, inFileViewerRootedAtPath: destinationURL.deletingLastPathComponent().path)
                            } catch {
                                exportError = error.localizedDescription
                                showError = true
                            }
                        } else {
                            // Clean up temp file if cancelled
                            try? FileManager.default.removeItem(at: tempFileURL)
                        }
                    }
                }
            } catch {
                await MainActor.run {
                    isExporting = false
                    exportError = error.localizedDescription
                    showError = true
                }
            }
        }
    }
}
