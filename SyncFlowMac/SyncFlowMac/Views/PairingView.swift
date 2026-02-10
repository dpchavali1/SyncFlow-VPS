//
//  PairingView.swift
//  SyncFlowMac
//
//  QR code pairing view for connecting to Android phone
//

import SwiftUI
import Combine
import CoreImage.CIFilterBuiltins

struct PairingView: View {
    @EnvironmentObject var appState: AppState

    @State private var pairingSession: PairingSession?
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var pairingStatus: PairingStatusState = .generating
    @State private var timeRemaining: TimeInterval = 0
    @State private var showManualJoin = false
    @State private var manualSyncGroupId = ""
    @State private var cachedQRImage: Image?

    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
    private let qrContext = CIContext()

    enum PairingStatusState {
        case generating
        case waitingForScan
        case success
        case rejected
        case expired
        case error(String)
    }

    var body: some View {
        VStack(spacing: 30) {
            // Header
            VStack(spacing: 10) {
                Image(systemName: "message.and.waveform.fill")
                    .font(.system(size: 80))
                    .foregroundColor(.blue)

                Text("Welcome to SyncFlow")
                    .font(.largeTitle)
                    .fontWeight(.bold)

                Text("Scan this QR code with your Android phone to connect")
                    .font(.body)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
            }

            Divider()
                .padding(.horizontal, 100)

            // Content based on state
            switch pairingStatus {
            case .generating:
                if showManualJoin {
                    // Manual join form
                    VStack(spacing: 20) {
                        Text("Join Existing Sync Group")
                            .font(.headline)

                        Text("Enter the sync group ID or paste the QR code content")
                            .font(.caption)
                            .foregroundColor(.secondary)

                        TextField("Sync Group ID", text: $manualSyncGroupId)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 300)

                        HStack(spacing: 10) {
                            Button(action: {
                                Task {
                                    await joinExistingSyncGroup()
                                }
                            }) {
                                Label("Join", systemImage: "checkmark")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)

                            Button(action: {
                                showManualJoin = false
                                manualSyncGroupId = ""
                            }) {
                                Label("Back", systemImage: "arrow.left")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                    .padding(40)
                } else {
                    VStack(spacing: 20) {
                        ProgressView()
                            .scaleEffect(1.5)
                        Text("Generating QR Code...")
                            .foregroundColor(.secondary)

                        Divider()
                            .padding(.vertical, 10)

                        Button(action: {
                            showManualJoin = true
                        }) {
                            Label("Or join existing sync group", systemImage: "qrcode.viewfinder")
                        }
                        .buttonStyle(.borderless)
                        .foregroundColor(.blue)
                    }
                    .padding(40)
                }

            case .waitingForScan:
                if let session = pairingSession {
                    VStack(spacing: 20) {
                        // QR Code with ready indicator
                        VStack(spacing: 10) {
                            if let qrImage = cachedQRImage {
                                // QR code is ready
                                VStack(spacing: 12) {
                                    qrImage
                                        .interpolation(.none)
                                        .resizable()
                                        .scaledToFit()
                                        .frame(width: 400, height: 400)
                                        .background(Color.white)
                                        .cornerRadius(12)
                                        .shadow(radius: 8)

                                    // Visual indicator that QR is ready
                                    HStack(spacing: 6) {
                                        Image(systemName: "checkmark.circle.fill")
                                            .foregroundColor(.green)
                                            .font(.system(size: 14))
                                        Text("Ready to scan")
                                            .font(.caption)
                                            .foregroundColor(.green)
                                    }
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(Color.green.opacity(0.1))
                                    .cornerRadius(6)
                                }
                            } else {
                                // Still generating QR code
                                VStack(spacing: 10) {
                                    ProgressView()
                                        .frame(height: 300)

                                    HStack(spacing: 6) {
                                        Image(systemName: "hourglass")
                                            .foregroundColor(.blue)
                                            .font(.system(size: 14))
                                        Text("Generating QR code...")
                                            .font(.caption)
                                            .foregroundColor(.blue)
                                    }
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(Color.blue.opacity(0.1))
                                    .cornerRadius(6)
                                }
                            }
                        }

                        // Timer
                        HStack(spacing: 8) {
                            Image(systemName: "clock")
                                .foregroundColor(timeRemaining <= 60 ? .orange : .secondary)
                            Text(formatTime(timeRemaining))
                                .font(.system(.title3, design: .monospaced))
                                .foregroundColor(timeRemaining <= 60 ? .orange : .primary)
                        }

                        // Refresh button
                        Button {
                            Task { await startPairing() }
                        } label: {
                            Label("Generate New QR Code", systemImage: "arrow.clockwise")
                        }
                        .buttonStyle(.borderless)
                        .foregroundColor(.blue)
                    }
                }

            case .success:
                VStack(spacing: 20) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 80))
                        .foregroundColor(.green)

                    Text("Successfully Paired!")
                        .font(.title)
                        .fontWeight(.semibold)

                    Text("Redirecting to messages...")
                        .foregroundColor(.secondary)
                }

            case .rejected:
                VStack(spacing: 20) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 80))
                        .foregroundColor(.orange)

                    Text("Pairing Declined")
                        .font(.title)
                        .fontWeight(.semibold)

                    Text("The pairing request was declined on your phone.")
                        .foregroundColor(.secondary)

                    Button {
                        Task { await startPairing() }
                    } label: {
                        Label("Try Again", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(.borderedProminent)
                }

            case .expired:
                VStack(spacing: 20) {
                    Image(systemName: "clock.badge.exclamationmark")
                        .font(.system(size: 80))
                        .foregroundColor(.red)

                    Text("Session Expired")
                        .font(.title)
                        .fontWeight(.semibold)

                    Text("The pairing session has expired. Please try again.")
                        .foregroundColor(.secondary)

                    Button {
                        Task { await startPairing() }
                    } label: {
                        Label("Generate New QR Code", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(.borderedProminent)
                }

            case .error(let message):
                VStack(spacing: 20) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 80))
                        .foregroundColor(.red)

                    Text("Pairing Failed")
                        .font(.title)
                        .fontWeight(.semibold)

                    Text(message)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)

                    Button {
                        Task { await startPairing() }
                    } label: {
                        Label("Try Again", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(.borderedProminent)
                }
            }

            // Instructions (only show when waiting for scan)
            if case .waitingForScan = pairingStatus {
                VStack(alignment: .leading, spacing: 10) {
                    Text("How to pair:")
                        .font(.headline)

                    HStack(alignment: .top) {
                        Text("1.")
                        Text("Open SyncFlow app on your Android phone")
                    }

                    HStack(alignment: .top) {
                        Text("2.")
                        Text("Go to Settings → Desktop Integration")
                    }

                    HStack(alignment: .top) {
                        Text("3.")
                        Text("Tap \"Scan Desktop QR Code\"")
                    }

                    HStack(alignment: .top) {
                        Text("4.")
                        Text("Point your camera at this QR code")
                    }

                    HStack(alignment: .top) {
                        Text("5.")
                        Text("Approve the pairing request on your phone")
                    }
                }
                .font(.body)
                .foregroundColor(.secondary)
                .padding()
                .background(Color.secondary.opacity(0.1))
                .cornerRadius(10)
                .frame(width: 500)
            }

            Spacer()
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(nsColor: .windowBackgroundColor))
        .task {
            await startPairing()
        }
        .onDisappear {
            cleanupListener()
        }
        .onReceive(timer) { _ in
            updateTimer()
        }
    }

    // MARK: - QR Code Generation (Enhanced for V2)

    /// Generate high-quality QR code with maximum error correction
    /// Size: 300x300 for better scanning reliability
    private func generateQRCodeImage(for string: String) {
        DispatchQueue.global(qos: .userInitiated).async {
            let filter = CIFilter.qrCodeGenerator()
            filter.message = Data(string.utf8)
            // Use "H" (high) error correction for maximum reliability
            filter.correctionLevel = "H"

            var resultImage: Image?
            if let outputImage = filter.outputImage {
                // Scale to 300x300 for better scanning
                let scale = 15.0
                let scaledImage = outputImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

                if let cgImage = self.qrContext.createCGImage(scaledImage, from: scaledImage.extent) {
                    resultImage = Image(nsImage: NSImage(cgImage: cgImage, size: NSSize(width: 300, height: 300)))
                }
            }

            DispatchQueue.main.async {
                self.cachedQRImage = resultImage ?? Image(systemName: "qrcode")
            }
        }
    }

    // MARK: - Timer

    private func formatTime(_ seconds: TimeInterval) -> String {
        let mins = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return String(format: "%d:%02d", mins, secs)
    }

    private func updateTimer() {
        guard let session = pairingSession else { return }

        timeRemaining = session.timeRemaining

        if timeRemaining <= 0 && pairingStatus == .waitingForScan {
            cleanupListener()
            pairingStatus = .expired
        }
    }

    // MARK: - Pairing Logic

    private func startPairing() async {
        cleanupListener()
        pairingStatus = .generating
        pairingSession = nil
        cachedQRImage = nil
        errorMessage = nil

        // Use VPS Service for pairing in VPS mode
        let vpsService = VPSService.shared

        do {
            // Generate pairing QR code via VPS
            let (token, qrData) = try await vpsService.generatePairingQRData()

            // Create a pairing session with the VPS data
            let session = PairingSession(
                token: token,
                qrPayload: qrData,
                expiresAt: Date().addingTimeInterval(300).timeIntervalSince1970 * 1000, // 5 minutes
                version: 2
            )

            await MainActor.run {
                self.pairingSession = session
                self.timeRemaining = 300 // 5 minutes
                self.pairingStatus = .waitingForScan
                // Generate QR code on background thread for better performance
                self.generateQRCodeImage(for: session.qrPayload)
            }

            // Poll for pairing approval via VPS
            Task {
                do {
                    let user = try await vpsService.waitForPairingApproval(token: token, timeout: 300)
                    await MainActor.run {
                        self.handlePairingStatus(.approved(pairedUid: user.userId, deviceId: user.deviceId))
                    }
                } catch VPSError.pairingExpired {
                    await MainActor.run {
                        self.pairingStatus = .expired
                    }
                } catch {
                    await MainActor.run {
                        self.pairingStatus = .error(error.localizedDescription)
                    }
                }
            }
        } catch {
            await MainActor.run {
                self.pairingStatus = .error(error.localizedDescription)
            }
        }
    }

    private func joinExistingSyncGroup() async {
        guard !manualSyncGroupId.trimmingCharacters(in: .whitespaces).isEmpty else {
            errorMessage = "Please enter a sync group ID"
            return
        }

        pairingStatus = .generating
        cachedQRImage = nil
        errorMessage = nil
        showManualJoin = false

        // Use VPS pairing flow - manual join uses the same QR-based flow
        await startPairing()
    }

    private func handlePairingStatus(_ status: PairingStatus) {
        switch status {
        case .pending:
            // Still waiting
            break

        case .approved(let pairedUid, let deviceId):
            print("[Pairing] ✅ Pairing approved!")
            print("[Pairing]   pairedUid from approval: \(pairedUid)")
            print("[Pairing]   deviceId from approval: \(deviceId ?? "nil")")
            cleanupListener()
            pairingStatus = .success

            // Fetch E2EE keys that Android pushed during pairing
            Task {
                await fetchE2EEKeysFromAndroid(userId: pairedUid, deviceId: deviceId)
            }

            // Redirect after brief delay
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                appState.setPaired(userId: pairedUid)
            }

        case .rejected:
            cleanupListener()
            pairingStatus = .rejected

        case .expired:
            cleanupListener()
            pairingStatus = .expired
        }
    }

    /// Fetch E2EE keys via VPS after pairing approval
    /// E2EE key sync is handled by AppState.attemptAutoE2eeKeySyncVPS which will
    /// be triggered automatically when setPaired() is called
    private func fetchE2EEKeysFromAndroid(userId: String, deviceId: String?) async {
        print("[Pairing] E2EE key sync will be triggered by setPaired() via VPS")
        // No action needed here - setPaired() in AppState triggers attemptAutoE2eeKeySyncVPS
    }

    private func cleanupListener() {
        // VPS pairing uses polling via waitForPairingApproval, no listener to clean up
    }
}

extension PairingView.PairingStatusState: Equatable {
    static func == (lhs: PairingView.PairingStatusState, rhs: PairingView.PairingStatusState) -> Bool {
        switch (lhs, rhs) {
        case (.generating, .generating),
             (.waitingForScan, .waitingForScan),
             (.success, .success),
             (.rejected, .rejected),
             (.expired, .expired):
            return true
        case (.error(let a), .error(let b)):
            return a == b
        default:
            return false
        }
    }
}
