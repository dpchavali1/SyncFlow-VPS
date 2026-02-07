//
//  QuickDropView.swift
//  SyncFlowMac
//
//  Drag-and-drop area to send files to the paired Android device.
//

import SwiftUI
import UniformTypeIdentifiers

struct QuickDropView: View {
    @EnvironmentObject var appState: AppState
    @ObservedObject private var transferService = FileTransferService.shared

    @State private var isDragOver = false
    @State private var showFilePicker = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Quick Drop")
                    .font(.headline)
                Spacer()
                Button("Send File") {
                    showFilePicker = true
                }
                .buttonStyle(.bordered)
                .disabled(!appState.isPaired || isUploading)
            }

            Text(subtitleText)
                .font(.caption)
                .foregroundColor(.secondary)

            if let status = transferService.latestTransfer {
                HStack(spacing: 8) {
                    Image(systemName: statusIcon(for: status))
                        .foregroundColor(statusColor(for: status))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(status.fileName)
                            .font(.caption)
                            .lineLimit(1)
                        Text(statusMessage(for: status))
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }

                if status.state == .uploading {
                    ProgressView(value: status.progress)
                        .progressViewStyle(.linear)
                }
            }
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(Color(nsColor: .controlBackgroundColor))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(isDragOver ? Color.accentColor : Color.secondary.opacity(0.2), lineWidth: isDragOver ? 2 : 1)
        )
        .onDrop(of: [UTType.fileURL.identifier], isTargeted: $isDragOver) { providers in
            handleDrop(providers: providers)
            return true
        }
        .fileImporter(
            isPresented: $showFilePicker,
            allowedContentTypes: [.item],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    transferService.sendFile(url: url)
                }
            case .failure(let error):
                print("QuickDrop file picker error: \(error)")
            }
        }
    }

    private var isUploading: Bool {
        transferService.latestTransfer?.state == .uploading
    }

    private var subtitleText: String {
        if !appState.isPaired {
            return "Pair your phone to send files."
        }
        if isDragOver {
            return "Release to send to your phone."
        }
        return "Drop a file here to send it to your phone."
    }

    private func handleDrop(providers: [NSItemProvider]) {
        guard appState.isPaired else { return }
        for provider in providers {
            if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                    if let data = item as? Data,
                       let url = URL(dataRepresentation: data, relativeTo: nil) {
                        DispatchQueue.main.async {
                            transferService.sendFile(url: url)
                        }
                    } else if let url = item as? URL {
                        DispatchQueue.main.async {
                            transferService.sendFile(url: url)
                        }
                    }
                }
                return
            }
        }
    }

    private func statusMessage(for status: FileTransferService.TransferStatus) -> String {
        switch status.state {
        case .uploading:
            return "Sending to phone..."
        case .downloading:
            return "Receiving from phone..."
        case .sent:
            return "Sent to phone"
        case .received:
            return "Received from phone"
        case .failed:
            return status.error ?? "Failed to send"
        }
    }

    private func statusIcon(for status: FileTransferService.TransferStatus) -> String {
        switch status.state {
        case .uploading:
            return "arrow.up.circle.fill"
        case .downloading:
            return "arrow.down.circle.fill"
        case .sent:
            return "checkmark.circle.fill"
        case .received:
            return "checkmark.circle.fill"
        case .failed:
            return "exclamationmark.triangle.fill"
        }
    }

    private func statusColor(for status: FileTransferService.TransferStatus) -> Color {
        switch status.state {
        case .uploading:
            return .accentColor
        case .downloading:
            return .accentColor
        case .sent:
            return .green
        case .received:
            return .green
        case .failed:
            return .red
        }
    }
}
