//
//  ColorSchemeObserver.swift
//  SyncFlowMac
//
//  Observes system appearance changes and notifies SwiftUI views to update colors
//

import SwiftUI
import AppKit
import Combine

// MARK: - ColorSchemeObserver

/// An observable object that monitors system appearance changes.
/// Views that depend on dark/light mode colors should observe this object
/// to ensure they update when the system appearance changes.
///
/// Usage:
/// ```swift
/// @StateObject private var colorObserver = ColorSchemeObserver.shared
/// // ... in body, use colorObserver.colorScheme to get current scheme
/// // or just observing it will trigger view updates when appearance changes
/// ```
final class ColorSchemeObserver: ObservableObject {

    /// Singleton instance
    static let shared = ColorSchemeObserver()

    /// Current color scheme (published to trigger view updates)
    @Published private(set) var colorScheme: ColorScheme = .light

    /// Update counter to force view refresh (incremented on each appearance change)
    @Published private(set) var updateTrigger: Int = 0

    private var observer: NSObjectProtocol?

    private init() {
        // Set initial value
        updateColorScheme()

        // Observe appearance changes
        observer = DistributedNotificationCenter.default().addObserver(
            forName: NSNotification.Name("AppleInterfaceThemeChangedNotification"),
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.updateColorScheme()
        }

        // Also observe app activation (appearance might have changed while app was in background)
        NotificationCenter.default.addObserver(
            forName: NSApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.updateColorScheme()
        }
    }

    deinit {
        if let observer = observer {
            DistributedNotificationCenter.default().removeObserver(observer)
        }
    }

    /// Updates the color scheme based on current system appearance
    private func updateColorScheme() {
        let isDark = NSApp.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        let newScheme: ColorScheme = isDark ? .dark : .light

        // Only publish if changed
        if newScheme != colorScheme {
            colorScheme = newScheme
            updateTrigger += 1

            // Force all windows to update
            DispatchQueue.main.async {
                for window in NSApp.windows {
                    window.contentView?.needsDisplay = true
                    // Invalidate the entire view hierarchy
                    if let hostingView = window.contentView as? NSHostingView<AnyView> {
                        hostingView.needsLayout = true
                    }
                }
            }
        }
    }

    /// Manually trigger a color update check
    func checkAppearance() {
        updateColorScheme()
    }
}

// MARK: - View Extension for Color Scheme Observation

extension View {
    /// Observes color scheme changes and forces view updates.
    /// Apply this modifier to root views that need to respond to appearance changes.
    func observeColorScheme() -> some View {
        self.modifier(ColorSchemeObserverModifier())
    }
}

/// View modifier that observes color scheme changes
struct ColorSchemeObserverModifier: ViewModifier {
    @StateObject private var colorObserver = ColorSchemeObserver.shared
    @Environment(\.colorScheme) private var envColorScheme

    func body(content: Content) -> some View {
        content
            // Use the update trigger to force re-render
            .id(colorObserver.updateTrigger)
            // Also react to SwiftUI's environment color scheme
            .onChange(of: envColorScheme) { _, _ in
                // When SwiftUI detects a change, ensure our observer is in sync
                colorObserver.checkAppearance()
            }
    }
}
