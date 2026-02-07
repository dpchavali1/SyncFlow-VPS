//
//  SyncFlowTypography.swift
//  SyncFlowMac
//
//  Typography system for SyncFlow macOS app
//

import SwiftUI

// MARK: - SyncFlow Typography

struct SyncFlowTypography {

    // ============================================
    // Display Styles - Large headlines
    // ============================================

    static let displayLarge = Font.system(size: 57, weight: .regular, design: .default)
    static let displayMedium = Font.system(size: 45, weight: .regular, design: .default)
    static let displaySmall = Font.system(size: 36, weight: .regular, design: .default)

    // ============================================
    // Headline Styles - Screen headers
    // ============================================

    static let headlineLarge = Font.system(size: 32, weight: .semibold, design: .default)
    static let headlineMedium = Font.system(size: 28, weight: .semibold, design: .default)
    static let headlineSmall = Font.system(size: 24, weight: .semibold, design: .default)

    // ============================================
    // Title Styles - Section headers
    // ============================================

    static let titleLarge = Font.system(size: 22, weight: .medium, design: .default)
    static let titleMedium = Font.system(size: 16, weight: .medium, design: .default)
    static let titleSmall = Font.system(size: 14, weight: .medium, design: .default)

    // ============================================
    // Body Styles - Primary content
    // ============================================

    static let bodyLarge = Font.system(size: 16, weight: .regular, design: .default)
    static let bodyMedium = Font.system(size: 14, weight: .regular, design: .default)
    static let bodySmall = Font.system(size: 12, weight: .regular, design: .default)

    // ============================================
    // Label Styles - Buttons, chips
    // ============================================

    static let labelLarge = Font.system(size: 14, weight: .medium, design: .default)
    static let labelMedium = Font.system(size: 12, weight: .medium, design: .default)
    static let labelSmall = Font.system(size: 11, weight: .medium, design: .default)

    // ============================================
    // App-Specific Styles
    // ============================================

    // Conversation list - Contact name
    static let conversationTitle = Font.system(size: 15, weight: .semibold, design: .default)

    // Conversation list - Preview text
    static let conversationPreview = Font.system(size: 13, weight: .regular, design: .default)

    // Message bubble text
    static let messageBody = Font.system(size: 14, weight: .regular, design: .default)

    // Timestamp
    static let timestamp = Font.system(size: 11, weight: .regular, design: .default)

    // Badge/counter
    static let badge = Font.system(size: 10, weight: .bold, design: .rounded)

    // Section header (uppercased)
    static let sectionHeader = Font.system(size: 12, weight: .semibold, design: .default)

    // Menu item
    static let menuItem = Font.system(size: 13, weight: .regular, design: .default)

    // Button (compact)
    static let buttonSmall = Font.system(size: 12, weight: .medium, design: .default)

    // Input placeholder
    static let inputPlaceholder = Font.system(size: 14, weight: .regular, design: .default)

    // Code/OTP
    static let code = Font.system(size: 16, weight: .medium, design: .monospaced)

    // Call timer
    static let callTimer = Font.system(size: 48, weight: .light, design: .rounded)

    // Dial pad
    static let dialPadNumber = Font.system(size: 32, weight: .regular, design: .rounded)
    static let dialPadLetters = Font.system(size: 10, weight: .regular, design: .default)
}

// MARK: - Text Style Modifiers

extension View {
    func textStyle(_ font: Font, color: Color = SyncFlowColors.textPrimary) -> some View {
        self
            .font(font)
            .foregroundColor(color)
    }

    // Convenience modifiers
    func displayLarge() -> some View {
        self.font(SyncFlowTypography.displayLarge)
    }

    func headlineLarge() -> some View {
        self.font(SyncFlowTypography.headlineLarge)
    }

    func headlineMedium() -> some View {
        self.font(SyncFlowTypography.headlineMedium)
    }

    func titleLarge() -> some View {
        self.font(SyncFlowTypography.titleLarge)
    }

    func titleMedium() -> some View {
        self.font(SyncFlowTypography.titleMedium)
    }

    func bodyLarge() -> some View {
        self.font(SyncFlowTypography.bodyLarge)
    }

    func bodyMedium() -> some View {
        self.font(SyncFlowTypography.bodyMedium)
    }

    func bodySmall() -> some View {
        self.font(SyncFlowTypography.bodySmall)
    }

    func labelMedium() -> some View {
        self.font(SyncFlowTypography.labelMedium)
    }

    func conversationTitle() -> some View {
        self.font(SyncFlowTypography.conversationTitle)
    }

    func conversationPreview() -> some View {
        self.font(SyncFlowTypography.conversationPreview)
    }

    func messageBody() -> some View {
        self.font(SyncFlowTypography.messageBody)
    }

    func timestamp() -> some View {
        self.font(SyncFlowTypography.timestamp)
    }
}

// MARK: - Text Styling Helpers

struct StyledText: View {
    let text: String
    let style: Font
    let color: Color

    init(_ text: String, style: Font, color: Color = SyncFlowColors.textPrimary) {
        self.text = text
        self.style = style
        self.color = color
    }

    var body: some View {
        Text(text)
            .font(style)
            .foregroundColor(color)
    }
}
