//
//  Color+Hex.swift
//  SyncFlowMac
//
//  Helpers for converting between hex strings and Color values.
//

import SwiftUI
import AppKit

extension Color {
    /// Initializes a Color from a hex string (e.g. "#FF0000" or "FF0000").
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 3: // RGB (12-bit)
            (a, r, g, b) = (255, (int >> 8) * 17, (int >> 4 & 0xF) * 17, (int & 0xF) * 17)
        case 6: // RGB (24-bit)
            (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8: // ARGB (32-bit)
            (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:
            (a, r, g, b) = (255, 0, 0, 0)
        }

        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue:  Double(b) / 255,
            opacity: Double(a) / 255
        )
    }

    /// Converts the Color to an sRGB hex string (without alpha by default).
    func toHex(includeAlpha: Bool = false) -> String? {
        guard
            let cgColor = self.cgColor,
            let nsColor = NSColor(cgColor: cgColor)?.usingColorSpace(.sRGB)
        else {
            return nil
        }

        let red = UInt8(clamping: Int(round(nsColor.redComponent * 255)))
        let green = UInt8(clamping: Int(round(nsColor.greenComponent * 255)))
        let blue = UInt8(clamping: Int(round(nsColor.blueComponent * 255)))
        let alpha = UInt8(clamping: Int(round(nsColor.alphaComponent * 255)))

        if includeAlpha {
            return String(format: "#%02X%02X%02X%02X", alpha, red, green, blue)
        } else {
            return String(format: "#%02X%02X%02X", red, green, blue)
        }
    }
}
