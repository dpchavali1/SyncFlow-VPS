//
//  SFMaterialView.swift
//  SyncFlowMac
//
//  NSViewRepresentable wrapper for NSVisualEffectView.
//  Provides native macOS vibrancy/blur materials for SwiftUI views.
//

import SwiftUI
import AppKit

// MARK: - Visual Effect Background

/// A SwiftUI wrapper around NSVisualEffectView for native macOS vibrancy.
/// Provides finer control than SwiftUI's built-in .ultraThinMaterial etc.
struct VisualEffectBackground: NSViewRepresentable {
    var material: NSVisualEffectView.Material
    var blendingMode: NSVisualEffectView.BlendingMode
    var state: NSVisualEffectView.State
    var isEmphasized: Bool

    init(
        material: NSVisualEffectView.Material = .sidebar,
        blendingMode: NSVisualEffectView.BlendingMode = .behindWindow,
        state: NSVisualEffectView.State = .active,
        isEmphasized: Bool = false
    ) {
        self.material = material
        self.blendingMode = blendingMode
        self.state = state
        self.isEmphasized = isEmphasized
    }

    func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = material
        view.blendingMode = blendingMode
        view.state = state
        view.isEmphasized = isEmphasized
        return view
    }

    func updateNSView(_ nsView: NSVisualEffectView, context: Context) {
        nsView.material = material
        nsView.blendingMode = blendingMode
        nsView.state = state
        nsView.isEmphasized = isEmphasized
    }
}

// MARK: - View Extension

extension View {
    /// Applies a native macOS material background using NSVisualEffectView.
    ///
    /// - Parameters:
    ///   - material: The visual effect material (e.g., `.sidebar`, `.headerView`, `.underWindowBackground`)
    ///   - blendingMode: How the material blends (`.behindWindow` for window-level blur, `.withinWindow` for in-content blur)
    func materialBackground(
        _ material: NSVisualEffectView.Material = .sidebar,
        blendingMode: NSVisualEffectView.BlendingMode = .behindWindow
    ) -> some View {
        self.background(
            VisualEffectBackground(material: material, blendingMode: blendingMode)
        )
    }
}
