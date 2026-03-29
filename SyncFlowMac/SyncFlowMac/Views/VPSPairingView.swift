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

    @State private var statusPulse = false

    var body: some View {
        VStack(spacing: 24) {
            // Header with rounded typography
            Text("Pair with Android Phone")
                .font(SyncFlowTypography.headlineMedium)

            Text("Scan this QR code with your SyncFlow Android app to connect")
                .font(SyncFlowTypography.bodyMedium)
                .foregroundColor(SyncFlowColors.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            // QR Code in glass card
            ZStack {
                if viewModel.isLoading {
                    VStack(spacing: 12) {
                        ProgressView()
                            .scaleEffect(1.5)
                        Text("Generating pairing code...")
                            .font(SyncFlowTypography.bodySmall)
                            .foregroundColor(SyncFlowColors.textSecondary)
                    }
                    .frame(width: 280, height: 280)
                } else if let qrImage = viewModel.qrImage {
                    Image(nsImage: qrImage)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 280, height: 280)
                        .background(Color.white)
                        .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusMd))
                } else if let error = viewModel.errorMessage {
                    SFErrorState(
                        message: error,
                        onRetry: { viewModel.startPairing() }
                    )
                    .frame(width: 280, height: 280)
                }
            }
            .frame(width: 300, height: 300)
            .background(SyncFlowColors.glassBackground)
            .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusPremium))
            .overlay(
                RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusPremium)
                    .strokeBorder(SyncFlowColors.glassBorder, lineWidth: 1)
            )
            .shadow(color: Color.black.opacity(0.08), radius: SyncFlowSpacing.shadowLg, x: 0, y: 4)

            // Status with animated pulse
            HStack(spacing: 8) {
                if viewModel.isPairing {
                    ZStack {
                        Circle()
                            .fill(Color.orange.opacity(0.3))
                            .frame(width: 16, height: 16)
                            .scaleEffect(statusPulse ? 1.5 : 1.0)
                            .opacity(statusPulse ? 0 : 0.6)
                        Circle()
                            .fill(Color.orange)
                            .frame(width: 8, height: 8)
                    }
                    .onAppear {
                        withAnimation(.easeInOut(duration: 1.5).repeatForever(autoreverses: false)) {
                            statusPulse = true
                        }
                    }
                    Text("Waiting for phone to scan...")
                        .foregroundColor(SyncFlowColors.textSecondary)
                } else if viewModel.isPaired {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(SyncFlowColors.success)
                        .transition(.scale.combined(with: .opacity))
                    Text("Connected!")
                        .foregroundColor(SyncFlowColors.success)
                        .fontWeight(.medium)
                }
            }
            .font(SyncFlowTypography.bodySmall)
            .animation(SFAnimations.bouncy, value: viewModel.isPaired)

            // Instructions in glass card
            VStack(alignment: .leading, spacing: 10) {
                InstructionRow(number: 1, text: "Open SyncFlow on your Android phone")
                InstructionRow(number: 2, text: "Go to Settings > Pair Device")
                InstructionRow(number: 3, text: "Tap 'Scan QR Code' and scan this code")
            }
            .padding(SyncFlowSpacing.md)
            .background(SyncFlowColors.glassBackground)
            .clipShape(RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusMd))
            .overlay(
                RoundedRectangle(cornerRadius: SyncFlowSpacing.radiusMd)
                    .strokeBorder(SyncFlowColors.glassBorder, lineWidth: 1)
            )

            Spacer()

            // Start Over button - regenerates QR code and restarts pairing
            SFSecondaryButton(text: "Start Over") {
                viewModel.cancelPairing()
                viewModel.startPairing()
            }
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
                    .fill(
                        LinearGradient(
                            colors: [SyncFlowColors.primary, SyncFlowColors.primary.opacity(0.7)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 24, height: 24)
                    .shadow(color: SyncFlowColors.primary.opacity(0.3), radius: 3, x: 0, y: 1)
                Text("\(number)")
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .foregroundColor(.white)
            }
            Text(text)
                .font(SyncFlowTypography.bodyMedium)
                .foregroundColor(SyncFlowColors.textPrimary)
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
                self.errorMessage = Self.friendlyErrorMessage(from: error)
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
                    self.errorMessage = Self.friendlyErrorMessage(from: error)
                }
            }
        }
    }

    /// Maps raw errors to user-friendly, actionable messages.
    private static func friendlyErrorMessage(from error: Error) -> String {
        let nsError = error as NSError
        let description = error.localizedDescription.lowercased()

        // Network unreachable / no internet
        if nsError.domain == NSURLErrorDomain &&
           (nsError.code == NSURLErrorNotConnectedToInternet ||
            nsError.code == NSURLErrorNetworkConnectionLost ||
            nsError.code == NSURLErrorCannotFindHost ||
            nsError.code == NSURLErrorCannotConnectToHost ||
            nsError.code == NSURLErrorDNSLookupFailed) {
            return "Check your internet connection and try again."
        }

        // Timeout / QR code expired
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorTimedOut ||
           description.contains("timeout") || description.contains("expired") ||
           description.contains("timed out") {
            return "QR code expired. Tap to generate a new one."
        }

        // Server errors (5xx or server-related messages)
        if description.contains("500") || description.contains("502") ||
           description.contains("503") || description.contains("server") ||
           description.contains("internal error") {
            return "SyncFlow servers are temporarily unavailable. Please try again in a moment."
        }

        // Fallback: show original message
        return error.localizedDescription
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
