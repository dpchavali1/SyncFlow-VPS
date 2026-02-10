//
//  ClipboardSyncService.swift
//  SyncFlowMac
//
//  Bidirectional clipboard synchronization between macOS and Android via VPS.
//

import Foundation
import AppKit
import Combine

class ClipboardSyncService: ObservableObject {

    static let shared = ClipboardSyncService()

    @Published var isEnabled: Bool = true
    @Published var lastSyncedContent: String?
    @Published var lastSyncTime: Date?

    private var currentUserId: String?
    private var clipboardCheckTimer: Timer?
    private var cancellables = Set<AnyCancellable>()

    private var lastKnownContent: String?
    private var lastKnownChangeCount: Int = 0
    private var isUpdatingFromRemote = false

    private let maxClipboardLength = 50000

    private init() {}

    // MARK: - Sync Control

    func startSync(userId: String) {
        guard isEnabled else { return }
        currentUserId = userId

        startMonitoringLocalClipboard()
        startListeningForRemoteClipboard()

        // Fetch current remote clipboard
        Task {
            do {
                let clipboard = try await VPSService.shared.getClipboard()
                if let text = clipboard["text"] as? String,
                   let source = clipboard["source"] as? String,
                   source != "macos",
                   let timestamp = clipboard["timestamp"] as? Double {
                    await MainActor.run {
                        self.updateLocalClipboard(text: text, timestamp: timestamp)
                    }
                }
            } catch {
                print("[Clipboard] Error fetching initial clipboard: \(error)")
            }
        }
    }

    func stopSync() {
        stopMonitoringLocalClipboard()
        cancellables.removeAll()
        currentUserId = nil
    }

    // MARK: - Local Clipboard Monitoring

    private func startMonitoringLocalClipboard() {
        DispatchQueue.main.async { [weak self] in
            self?.lastKnownChangeCount = NSPasteboard.general.changeCount

            self?.clipboardCheckTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                self?.checkLocalClipboard()
            }
            if let timer = self?.clipboardCheckTimer {
                RunLoop.main.add(timer, forMode: .common)
            }
        }
    }

    private func stopMonitoringLocalClipboard() {
        clipboardCheckTimer?.invalidate()
        clipboardCheckTimer = nil
    }

    private func checkLocalClipboard() {
        guard isEnabled, !isUpdatingFromRemote else { return }
        guard currentUserId != nil else { return }

        let pasteboard = NSPasteboard.general
        let currentChangeCount = pasteboard.changeCount

        guard currentChangeCount != lastKnownChangeCount else { return }
        lastKnownChangeCount = currentChangeCount

        guard let text = pasteboard.string(forType: .string) else { return }
        guard !text.isEmpty else { return }
        guard text.count <= maxClipboardLength else { return }
        guard text != lastKnownContent else { return }

        syncClipboard(text: text)
    }

    // MARK: - Remote Clipboard Listener

    private func startListeningForRemoteClipboard() {
        VPSService.shared.clipboardUpdated
            .receive(on: DispatchQueue.main)
            .sink { [weak self] data in
                guard let self = self else { return }
                guard let text = data["text"] as? String else { return }
                let source = data["source"] as? String ?? ""
                // Only apply if from another device
                guard source != "macos" else { return }
                let timestamp = data["timestamp"] as? Double ?? Date().timeIntervalSince1970 * 1000
                self.updateLocalClipboard(text: text, timestamp: timestamp)
            }
            .store(in: &cancellables)
    }

    // MARK: - Outbound Sync (Mac to VPS)

    private func syncClipboard(text: String) {
        lastKnownContent = text
        lastSyncedContent = text
        lastSyncTime = Date()

        Task {
            do {
                try await VPSService.shared.syncClipboard(text: text, source: "macos")
            } catch {
                print("[Clipboard] Error syncing to VPS: \(error)")
            }
        }
    }

    // MARK: - Inbound Sync (Remote to Mac)

    private func updateLocalClipboard(text: String, timestamp: Double) {
        isUpdatingFromRemote = true

        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)

        lastKnownContent = text
        lastKnownChangeCount = pasteboard.changeCount
        lastSyncedContent = text
        lastSyncTime = Date(timeIntervalSince1970: timestamp / 1000)

        showClipboardNotification(text: text)

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            self.isUpdatingFromRemote = false
        }
    }

    // MARK: - User Notifications

    private func showClipboardNotification(text: String) {
        let notification = NSUserNotification()
        notification.title = "Clipboard Synced"
        notification.informativeText = text.count > 50 ? String(text.prefix(50)) + "..." : text
        notification.soundName = nil

        NSUserNotificationCenter.default.deliver(notification)
    }

    // MARK: - Public API

    func syncNow() {
        guard let text = NSPasteboard.general.string(forType: .string),
              !text.isEmpty,
              text.count <= maxClipboardLength else { return }

        syncClipboard(text: text)
    }

    func getCurrentClipboard() -> String? {
        return NSPasteboard.general.string(forType: .string)
    }

    // MARK: - Battery Optimization

    func reduceFrequency() {
        stopMonitoringLocalClipboard()
        if isEnabled && currentUserId != nil {
            startMonitoringLocalClipboardWithInterval(5.0)
        }
    }

    func pauseSync() {
        isEnabled = false
        stopMonitoringLocalClipboard()
    }

    func resumeSync() {
        isEnabled = true
        if let userId = currentUserId {
            startSync(userId: userId)
        }
    }

    private func startMonitoringLocalClipboardWithInterval(_ interval: TimeInterval) {
        DispatchQueue.main.async { [weak self] in
            self?.clipboardCheckTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
                self?.checkLocalClipboard()
            }
            if let timer = self?.clipboardCheckTimer {
                RunLoop.main.add(timer, forMode: .common)
            }
        }
    }
}
