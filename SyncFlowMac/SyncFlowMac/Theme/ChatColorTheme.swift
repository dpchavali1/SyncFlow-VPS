//
//  ChatColorTheme.swift
//  SyncFlowMac
//
//  Defines the available chat color themes for message bubbles.
//

import SwiftUI

enum ChatColorTheme: String, CaseIterable, Identifiable {
    case apple
    case graphite
    case aurora
    case midnight

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .apple: return "Apple Blue"
        case .graphite: return "Graphite Gray"
        case .aurora: return "Aurora Green"
        case .midnight: return "Midnight Glow"
        }
    }

    func sentColor(for scheme: ColorScheme) -> Color {
        switch self {
        case .apple:
            return Color(hex: "0A84FF")
        case .graphite:
            return Color(hex: "8E8E93")
        case .aurora:
            return Color(hex: "30D158")
        case .midnight:
            return Color(hex: "5AC8FA")
        }
    }

    func receivedColor(for scheme: ColorScheme) -> Color {
        switch self {
        case .apple:
            return scheme == .dark ? Color(hex: "2C2C2E") : Color(hex: "F4F4F6")
        case .graphite:
            return scheme == .dark ? Color(hex: "1C1C1E") : Color(hex: "D1D1D6")
        case .aurora:
            return scheme == .dark ? Color(hex: "1C1C1E") : Color(hex: "E7F9EA")
        case .midnight:
            return scheme == .dark ? Color(hex: "111111") : Color(hex: "ECEFFF")
        }
    }

    func receivedTextColor(for scheme: ColorScheme) -> Color {
        return scheme == .dark ? Color(hex: "F8F8F8") : Color(hex: "1C1C1E")
    }

    func gradientColors(isReceived: Bool, scheme: ColorScheme) -> [Color] {
        if isReceived {
            return [receivedColor(for: scheme), receivedColor(for: scheme).opacity(0.8)]
        }
        switch self {
        case .apple:
            return [Color(hex: "0A84FF"), Color(hex: "5AC8FA")]
        case .graphite:
            return [Color(hex: "8E8E93"), Color(hex: "D1D1D6")]
        case .aurora:
            return [Color(hex: "30D158"), Color(hex: "4CD964")]
        case .midnight:
            return [Color(hex: "5AC8FA"), Color(hex: "0A84FF")]
        }
    }
}
