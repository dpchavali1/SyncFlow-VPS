//
//  SyncFlowColors.swift
//  SyncFlowMac
//
//  Centralized color system for SyncFlow macOS app
//

import SwiftUI
import AppKit

// MARK: - SyncFlow Color Palette

struct SyncFlowColors {

    /// Gets the current color scheme dynamically
    /// This ensures we always check the latest appearance state
    private static var currentColorScheme: ColorScheme {
        let isDark = NSApp.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        return isDark ? .dark : .light
    }

    // ============================================
    // Brand Colors - Modern Tech Palette
    // ============================================
    // Primary: iMessage Blue
    static let primary = Color(hex: "0A84FF")
    static let primaryDark = Color(hex: "007AFF")
    static let primaryLight = Color(hex: "5AC8FA")

    static let primaryContainer = Color(hex: "E7E9FF") // Soft purple background
    static let onPrimary = Color.white
    static let onPrimaryContainer = Color(hex: "1A1B4B") // Dark text on light bg

    // Modern gradient colors for tech-savvy appeal
    static let gradientStart = Color(hex: "667EEA")   // Modern blue
    static let gradientEnd = Color(hex: "764BA2")     // Modern purple

    // Accent colors inspired by modern tech apps
    static let accentPurple = Color(hex: "8B5CF6")     // Bright purple
    static let accentPink = Color(hex: "EC4899")       // Vibrant pink
    static let accentCyan = Color(hex: "06B6D4")       // Modern cyan
    static let accentOrange = Color(hex: "F97316")     // Bright orange

    // Legacy blue colors for compatibility
    static let blue50 = Color(hex: "EEF2FF")           // Very light blue
    static let blue100 = Color(hex: "E0E7FF")          // Light blue
    static let blue200 = Color(hex: "C7D2FE")          // Medium light blue
    static let blue300 = Color(hex: "A5B4FC")          // Medium blue
    static let blue400 = Color(hex: "818CF8")          // Medium dark blue
    static let blue500 = Color(hex: "6366F1")          // Standard blue
    static let blue600 = Color(hex: "4F46E5")          // Dark blue
    static let blue700 = Color(hex: "4338CA")          // Darker blue
    static let blue800 = Color(hex: "3730A3")          // Very dark blue
    static let blue900 = Color(hex: "312E81")          // Darkest blue

    // ============================================
    // Secondary Colors - Modern Tech Accent
    // ============================================
    static let secondary = Color(hex: "7C3AED")        // Modern purple accent
    static let secondaryContainer = Color(hex: "F3E8FF") // Light purple background
    static let onSecondary = Color.white
    static let onSecondaryContainer = Color(hex: "2D1B69") // Dark text on light bg

    // ============================================
    // Semantic Colors - Modern Tech Theme
    // ============================================

    // Success - Vibrant Green (like GitHub)
    static let success = Color(hex: "10B981")         // Modern emerald
    static let successLight = Color(hex: "34D399")     // Bright green
    static let successContainer = Color(hex: "ECFDF5") // Light green bg
    static let onSuccess = Color.white
    static let onSuccessContainer = Color(hex: "064E3B") // Dark green text

    // Warning - Modern Amber (like Figma)
    static let warning = Color(hex: "F59E0B")         // Modern amber
    static let warningLight = Color(hex: "FCD34D")     // Bright yellow
    static let warningContainer = Color(hex: "FFFBEB") // Light yellow bg
    static let onWarning = Color.white
    static let onWarningContainer = Color(hex: "78350F") // Dark amber text

    // Error - Modern Red (like Discord)
    static let error = Color(hex: "EF4444")           // Modern red
    static let errorLight = Color(hex: "F87171")       // Bright red
    static let errorContainer = Color(hex: "FEF2F2")   // Light red bg
    static let onError = Color.white
    static let onErrorContainer = Color(hex: "7F1D1D") // Dark red text

    // Info - Modern Cyan (tech-inspired)
    static let info = Color(hex: "06B6D4")            // Modern cyan
    static let infoLight = Color(hex: "22D3EE")        // Bright cyan
    static let infoContainer = Color(hex: "ECFEFF")    // Light cyan bg
    static let onInfo = Color.white
    static let onInfoContainer = Color(hex: "0C4A6E")  // Dark cyan text

    // ============================================
    // Chat Colors
    // ============================================

    // Sent/received message colors (user-selectable)
    static var sentBubble: Color {
        bubbleFill(isReceived: false)
    }

    static var onSentBubble: Color {
        bubbleTextColor(isReceived: false)
    }

    static var receivedBubble: Color {
        bubbleFill(isReceived: true)
    }

    static var onReceivedBubble: Color {
        bubbleTextColor(isReceived: true)
    }

    static func bubbleTextColor(isReceived: Bool) -> Color {
        if isReceived {
            return customReceivedTextColor() ?? chatTheme.receivedTextColor(for: colorScheme)
        }
        return .white
    }

    static func bubbleGradientColors(isReceived: Bool) -> [Color] {
        if let custom = customColor(forReceived: isReceived) {
            return [custom, custom.opacity(0.8)]
        }
        return chatTheme.gradientColors(isReceived: isReceived, scheme: colorScheme)
    }

    static var bubbleGradientEnabled: Bool {
        UserDefaults.standard.bool(forKey: "chat_bubble_gradient_enabled")
    }

    private static var customColorsEnabled: Bool {
        UserDefaults.standard.bool(forKey: "chat_custom_colors_enabled")
    }

    private static func customColor(forReceived: Bool) -> Color? {
        guard customColorsEnabled else { return nil }
        let key = forReceived ? "chat_received_custom_color" : "chat_sent_custom_color"
        guard let hex = UserDefaults.standard.string(forKey: key),
              !hex.isEmpty else {
            return nil
        }
        return Color(hex: hex)
    }

    private static func customReceivedTextColor() -> Color? {
        guard customColorsEnabled,
              let hex = UserDefaults.standard.string(forKey: "chat_received_text_color"),
              !hex.isEmpty else {
            return nil
        }
        return Color(hex: hex)
    }

    // Helper functions
    static func bubbleFill(isReceived: Bool) -> Color {
        if let custom = customColor(forReceived: isReceived) {
            return custom
        }
        if isReceived {
            return chatTheme.receivedColor(for: colorScheme)
        }
        if useSystemAccent {
            return Color(nsColor: .controlAccentColor)
        }
        return chatTheme.sentColor(for: colorScheme)
    }

    // Special Message Types
    static var otpBubble: Color {
        currentColorScheme == .dark
            ? Color(hex: "1E3A5F")
            : Color(hex: "E3F2FD")
    }

    static var transactionBubble: Color {
        currentColorScheme == .dark
            ? Color(hex: "1B3D1E")
            : Color(hex: "E8F5E9")
    }

    static var alertBubble: Color {
        currentColorScheme == .dark
            ? Color(hex: "3D2E1B")
            : Color(hex: "FFF3E0")
    }

    static let otpAccent = Color(hex: "1976D2")
    static let transactionAccent = Color(hex: "2E7D32")
    static let alertAccent = Color(hex: "F57C00")

    // ============================================
    // Surface Colors (Theme Aware)
    // ============================================

    static var background: Color {
        currentColorScheme == .dark
            ? Color(hex: "0B141A")
            : Color(nsColor: .windowBackgroundColor)
    }

    static var surface: Color {
        currentColorScheme == .dark
            ? Color(hex: "111B21")
            : Color(nsColor: .controlBackgroundColor)
    }

    static var surfaceSecondary: Color {
        currentColorScheme == .dark
            ? Color(hex: "202C33")
            : Color(hex: "F8FAFC")
    }

    static var surfaceTertiary: Color {
        currentColorScheme == .dark
            ? Color(hex: "1F2C34")
            : Color(hex: "F1F5F9")
    }

    static var surfaceElevated: Color {
        currentColorScheme == .dark
            ? Color(hex: "1F2C34")
            : Color(hex: "FFFFFF")
    }

    static var conversationBackground: Color {
        if let hex = UserDefaults.standard.string(forKey: "conversation_window_color"),
            !hex.isEmpty {
            return Color(hex: hex)
        }
        // Return theme-appropriate default instead of system windowBackgroundColor
        return currentColorScheme == .dark
            ? Color(hex: "070E13")
            : Color(hex: "F8FAFC")
    }

    static var sidebarBackground: Color {
        currentColorScheme == .dark
            ? Color(hex: "111B21")
            : Color(hex: "FFFFFF")
    }

    static var sidebarRailBackground: Color {
        currentColorScheme == .dark
            ? Color(hex: "0B141A")
            : Color(hex: "F2F4F7")
    }

    static var chatHeaderBackground: Color {
        currentColorScheme == .dark
            ? Color(hex: "202C33")
            : Color(hex: "F2F4F7")
    }

    static var divider: Color {
        currentColorScheme == .dark
            ? Color(hex: "26353D")
            : Color(hex: "E5E7EB")
    }

    // ============================================
    // Text Colors
    // ============================================

    static var textPrimary: Color {
        Color(nsColor: .labelColor)
    }

    static var textSecondary: Color {
        Color(nsColor: .secondaryLabelColor)
    }

    static var textTertiary: Color {
        Color(nsColor: .tertiaryLabelColor)
    }

    static var textDisabled: Color {
        Color(nsColor: .disabledControlTextColor)
    }

    // ============================================
    // Divider & Border Colors
    // ============================================

    static var border: Color {
        currentColorScheme == .dark
            ? Color(hex: "3C4E60")
            : Color(hex: "D0D4D8")
    }

    static var borderFocused: Color {
        primary
    }

    // ============================================
    // Interaction States
    // ============================================

    static var hover: Color {
        currentColorScheme == .dark
            ? Color.white.opacity(0.08)
            : Color.black.opacity(0.04)
    }

    static var pressed: Color {
        currentColorScheme == .dark
            ? Color.white.opacity(0.12)
            : Color.black.opacity(0.08)
    }

    static var selected: Color {
        primary.opacity(0.12)
    }

    // ============================================
    // Status Colors - Modern Tech Theme
    // ============================================

    static let online = Color(hex: "10B981")      // Modern green
    static let offline = Color(hex: "64748B")     // Modern gray
    static let away = Color(hex: "F59E0B")        // Modern amber
    static let busy = Color(hex: "EF4444")        // Modern red
    static let typing = Color(hex: "8B5CF6")      // Modern purple

    // ============================================
    // Call-Specific Colors - Modern
    // ============================================

    static let callAccept = Color(hex: "10B981")   // Modern green
    static let callReject = Color(hex: "EF4444")   // Modern red
    static let callMuted = Color(hex: "F59E0B")    // Modern amber
    static let callBackground = Color(hex: "0F0F23") // Deep dark blue
    static let callRecording = Color(hex: "F97316") // Modern orange

    // ============================================
    // Additional Semantic Colors (Dark/Light Adaptive)
    // ============================================

    // Promotion badge color
    static let promotionAccent = Color(hex: "7B1FA2") // Purple

    // SIM badge colors - work well in both modes
    static let simSlot1 = Color(hex: "1976D2")  // Blue
    static let simSlot2 = Color(hex: "7B1FA2")  // Purple
    static let simSlot3 = Color(hex: "00796B")  // Teal

    // Error variants for button states
    static let errorDark = Color(hex: "B91C1C") // Darker red for hover

    // ============================================
    // Adaptive Standard Colors (Replace hardcoded)
    // These provide proper dark/light mode support
    // ============================================

    /// Blue color that adapts to dark/light mode
    static var adaptiveBlue: Color {
        currentColorScheme == .dark ? Color(hex: "60A5FA") : Color(hex: "2563EB")
    }

    /// Green color that adapts to dark/light mode
    static var adaptiveGreen: Color {
        currentColorScheme == .dark ? Color(hex: "34D399") : Color(hex: "059669")
    }

    /// Red color that adapts to dark/light mode
    static var adaptiveRed: Color {
        currentColorScheme == .dark ? Color(hex: "F87171") : Color(hex: "DC2626")
    }

    /// Orange color that adapts to dark/light mode
    static var adaptiveOrange: Color {
        currentColorScheme == .dark ? Color(hex: "FBBF24") : Color(hex: "D97706")
    }

    /// Purple color that adapts to dark/light mode
    static var adaptivePurple: Color {
        currentColorScheme == .dark ? Color(hex: "A78BFA") : Color(hex: "7C3AED")
    }

    /// Indigo color that adapts to dark/light mode
    static var adaptiveIndigo: Color {
        currentColorScheme == .dark ? Color(hex: "818CF8") : Color(hex: "4F46E5")
    }

    /// Cyan color that adapts to dark/light mode
    static var adaptiveCyan: Color {
        currentColorScheme == .dark ? Color(hex: "22D3EE") : Color(hex: "0891B2")
    }

    /// Teal color that adapts to dark/light mode
    static var adaptiveTeal: Color {
        currentColorScheme == .dark ? Color(hex: "2DD4BF") : Color(hex: "0D9488")
    }

    /// Gray color that adapts to dark/light mode
    static var adaptiveGray: Color {
        currentColorScheme == .dark ? Color(hex: "9CA3AF") : Color(hex: "6B7280")
    }

    // ============================================
    // Featured/Banner Gradient Colors
    // ============================================

    /// Dark gradient start for featured banners
    static var featuredGradientStart: Color {
        currentColorScheme == .dark
            ? Color(hex: "1E1B4B")  // Dark purple
            : Color(hex: "312E81")  // Indigo
    }

    /// Dark gradient end for featured banners
    static var featuredGradientEnd: Color {
        currentColorScheme == .dark
            ? Color(hex: "3D1E36")  // Dark rose
            : Color(hex: "4C1D95")  // Purple
    }

    // ============================================
    // Helper
    // ============================================

    private static var useSystemAccent: Bool {
        UserDefaults.standard.bool(forKey: "chat_use_system_accent")
    }

    private static var chatTheme: ChatColorTheme {
        let raw = UserDefaults.standard.string(forKey: "chat_color_theme") ?? ChatColorTheme.apple.rawValue
        return ChatColorTheme(rawValue: raw) ?? .apple
    }

    private static var colorScheme: ColorScheme {
        NSApp.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? .dark : .light
    }
}

// Note: Color(hex:) extension is defined in ConversationListView.swift
// We use that existing extension throughout the app

// MARK: - Convenience Modifiers

extension View {
    func syncFlowBackground() -> some View {
        self.background(SyncFlowColors.background)
    }

    func syncFlowSurface() -> some View {
        self.background(SyncFlowColors.surface)
    }

    func syncFlowCard() -> some View {
        self
            .background(SyncFlowColors.surfaceElevated)
            .cornerRadius(SyncFlowSpacing.radiusMd)
            .shadow(color: .black.opacity(0.08), radius: 4, x: 0, y: 2)
    }
}
