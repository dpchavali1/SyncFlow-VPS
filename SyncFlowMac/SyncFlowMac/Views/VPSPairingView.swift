/**
 * VPS Pairing View
 *
 * Displays a QR code for pairing with Android device via VPS server.
 * VPS-based pairing flow.
 */

import SwiftUI
import Combine
import CoreImage.CIFilterBuiltins

struct VPSPairingView: View {
    @StateObject private var viewModel = VPSPairingViewModel()

    var body: some View {
        VStack(spacing: 24) {
            // Header
            Text("Pair with Android Phone")
                .font(.title)
                .fontWeight(.bold)

            Text("Scan this QR code with your SyncFlow Android app to connect")
                .font(.body)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            // QR Code
            ZStack {
                if viewModel.isLoading {
                    ProgressView()
                        .scaleEffect(1.5)
                        .frame(width: 280, height: 280)
                } else if let qrImage = viewModel.qrImage {
                    Image(nsImage: qrImage)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 280, height: 280)
                        .background(Color.white)
                        .cornerRadius(8)
                } else if let error = viewModel.errorMessage {
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.system(size: 40))
                            .foregroundColor(.orange)
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                        Button("Retry") {
                            viewModel.startPairing()
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .frame(width: 280, height: 280)
                }
            }
            .frame(width: 300, height: 300)
            .background(Color(NSColor.controlBackgroundColor))
            .cornerRadius(12)

            // Status
            HStack(spacing: 8) {
                if viewModel.isPairing {
                    Circle()
                        .fill(Color.orange)
                        .frame(width: 8, height: 8)
                    Text("Waiting for phone to scan...")
                        .foregroundColor(.secondary)
                } else if viewModel.isPaired {
                    Circle()
                        .fill(Color.green)
                        .frame(width: 8, height: 8)
                    Text("Connected!")
                        .foregroundColor(.green)
                }
            }
            .font(.caption)

            // Instructions
            VStack(alignment: .leading, spacing: 8) {
                InstructionRow(number: 1, text: "Open SyncFlow on your Android phone")
                InstructionRow(number: 2, text: "Go to Settings > Pair Device")
                InstructionRow(number: 3, text: "Tap 'Scan QR Code' and scan this code")
            }
            .padding()
            .background(Color(NSColor.controlBackgroundColor))
            .cornerRadius(8)

            Spacer()

            // Cancel button
            Button("Cancel") {
                viewModel.cancelPairing()
            }
            .buttonStyle(.bordered)
        }
        .padding(24)
        .frame(width: 450, height: 650)
        .onAppear {
            viewModel.startPairing()
        }
        .onDisappear {
            viewModel.cancelPairing()
        }
    }
}

struct InstructionRow: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle()
                    .fill(Color.accentColor)
                    .frame(width: 24, height: 24)
                Text("\(number)")
                    .font(.caption)
                    .fontWeight(.bold)
                    .foregroundColor(.white)
            }
            Text(text)
                .font(.body)
        }
    }
}

// MARK: - ViewModel

@MainActor
class VPSPairingViewModel: ObservableObject {
    @Published var isLoading = false
    @Published var isPairing = false
    @Published var isPaired = false
    @Published var errorMessage: String?
    @Published var qrImage: NSImage?

    private var pairingToken: String?
    private var pollingTask: Task<Void, Never>?

    func startPairing() {
        isLoading = true
        errorMessage = nil
        isPairing = false
        isPaired = false

        Task {
            do {
                let (token, qrData) = try await VPSService.shared.generatePairingQRData()
                self.pairingToken = token

                // Generate QR code image
                if let image = generateQRCode(from: qrData) {
                    self.qrImage = image
                    self.isLoading = false
                    self.isPairing = true

                    // Start polling for approval
                    startPollingForApproval(token: token)
                } else {
                    throw NSError(domain: "", code: 0, userInfo: [NSLocalizedDescriptionKey: "Failed to generate QR code"])
                }
            } catch {
                self.isLoading = false
                self.errorMessage = error.localizedDescription
            }
        }
    }

    func cancelPairing() {
        pollingTask?.cancel()
        pollingTask = nil
        isPairing = false
    }

    private func startPollingForApproval(token: String) {
        pollingTask?.cancel()
        pollingTask = Task {
            do {
                let _ = try await VPSService.shared.waitForPairingApproval(token: token)
                self.isPairing = false
                self.isPaired = true

                // Notify that pairing completed
                NotificationCenter.default.post(name: .vpsPairingCompleted, object: nil)

            } catch {
                if !Task.isCancelled {
                    self.isPairing = false
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    private func generateQRCode(from string: String) -> NSImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()

        guard let data = string.data(using: .utf8) else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("H", forKey: "inputCorrectionLevel")

        guard let outputImage = filter.outputImage else { return nil }

        // Scale up the QR code for better quality
        let scale = CGAffineTransform(scaleX: 10, y: 10)
        let scaledImage = outputImage.transformed(by: scale)

        guard let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) else {
            return nil
        }

        return NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
    }
}

// MARK: - Notifications

extension Notification.Name {
    static let vpsPairingCompleted = Notification.Name("vpsPairingCompleted")
}

#Preview {
    VPSPairingView()
}
