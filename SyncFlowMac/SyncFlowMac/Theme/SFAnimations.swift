//
//  SFAnimations.swift
//  SyncFlowMac
//
//  Centralized animation presets for consistent motion throughout the app.
//  Replaces ad-hoc animation values with named, reusable constants.
//

import SwiftUI

// MARK: - Animation Presets

struct SFAnimations {

    // ============================================
    // Spring Presets
    // ============================================

    /// Quick, responsive spring for UI feedback (tab switches, selections)
    static let snappy = Animation.spring(response: 0.3, dampingFraction: 0.7)

    /// Smooth, relaxed spring for content transitions
    static let gentle = Animation.spring(response: 0.5, dampingFraction: 0.8)

    /// Playful spring with visible bounce (success states, celebrations)
    static let bouncy = Animation.spring(response: 0.4, dampingFraction: 0.6)

    /// Ultra-fast spring for micro-interactions (chip select, badge pop)
    static let micro = Animation.spring(response: 0.2, dampingFraction: 0.8)

    // ============================================
    // Timing Presets
    // ============================================

    /// Fast easeOut for quick feedback (0.15s)
    static let fast = Animation.easeOut(duration: 0.15)

    /// Standard easeInOut for general transitions (0.25s)
    static let normal = Animation.easeInOut(duration: 0.25)

    /// Slower easeInOut for deliberate transitions (0.35s)
    static let slow = Animation.easeInOut(duration: 0.35)

    // ============================================
    // Hover Timing
    // ============================================

    /// Hover enter — fast response (0.12s)
    static let hoverIn = Animation.easeOut(duration: 0.12)

    /// Hover exit — slightly faster to feel snappy (0.08s)
    static let hoverOut = Animation.easeIn(duration: 0.08)

    // ============================================
    // Content Transitions
    // ============================================

    /// Content appearing (views entering, lists populating)
    static let contentAppear = Animation.spring(response: 0.4, dampingFraction: 0.75)

    /// Content disappearing (views exiting)
    static let contentDisappear = Animation.easeOut(duration: 0.2)

    // ============================================
    // Scale Constants
    // ============================================

    /// Scale when button/element is pressed
    static let pressScale: CGFloat = 0.96

    /// Scale when hovering over an interactive element
    static let hoverScale: CGFloat = 1.02

    /// Y offset to create "lift" effect on hover (negative = upward)
    static let hoverLift: CGFloat = -2
}

// MARK: - Hover Lift Modifier

/// Applies a subtle upward lift and optional scale on hover.
/// Use on cards, buttons, and interactive elements for spatial feedback.
struct HoverLiftModifier: ViewModifier {
    @State private var isHovered = false
    var liftAmount: CGFloat
    var scaleAmount: CGFloat

    func body(content: Content) -> some View {
        content
            .offset(y: isHovered ? liftAmount : 0)
            .scaleEffect(isHovered ? scaleAmount : 1.0)
            .onHover { hovering in
                withAnimation(hovering ? SFAnimations.hoverIn : SFAnimations.hoverOut) {
                    isHovered = hovering
                }
            }
    }
}

// MARK: - Press Scale Modifier

/// Applies a subtle scale-down effect on press for tactile feedback.
struct PressScaleModifier: ViewModifier {
    @State private var isPressed = false

    func body(content: Content) -> some View {
        content
            .scaleEffect(isPressed ? SFAnimations.pressScale : 1.0)
            .animation(SFAnimations.micro, value: isPressed)
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in isPressed = true }
                    .onEnded { _ in isPressed = false }
            )
    }
}

// MARK: - Floating Animation Modifier

/// Creates a gentle floating/bobbing motion for empty states and decorative elements.
struct FloatingModifier: ViewModifier {
    @State private var isFloating = false
    var amplitude: CGFloat

    func body(content: Content) -> some View {
        content
            .offset(y: isFloating ? -amplitude : 0)
            .animation(
                Animation.easeInOut(duration: 2.5).repeatForever(autoreverses: true),
                value: isFloating
            )
            .onAppear { isFloating = true }
    }
}

// MARK: - View Extensions

extension View {
    /// Applies hover lift effect with configurable parameters.
    func hoverLift(lift: CGFloat = SFAnimations.hoverLift, scale: CGFloat = 1.0) -> some View {
        modifier(HoverLiftModifier(liftAmount: lift, scaleAmount: scale))
    }

    /// Applies press-down scale effect for tactile feedback.
    func pressScale() -> some View {
        modifier(PressScaleModifier())
    }

    /// Applies gentle floating animation (for empty states, decorative elements).
    func floatingAnimation(amplitude: CGFloat = 8) -> some View {
        modifier(FloatingModifier(amplitude: amplitude))
    }
}
