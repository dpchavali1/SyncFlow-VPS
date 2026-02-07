//
//  IncomingCallView.swift
//  SyncFlowMac
//
//  View for displaying incoming call notifications with modern design
//

import SwiftUI

struct IncomingCallView: View {
    let call: ActiveCall
    let onAnswer: () -> Void
    let onReject: () -> Void

    @EnvironmentObject var appState: AppState
    @State private var animateRing = false

    var body: some View {
        VStack(spacing: 24) {
            // Animated phone icon with pulsing effect
            ZStack {
                // Pulsing rings
                ForEach(0..<3) { index in
                    Circle()
                        .stroke(Color.green.opacity(0.3), lineWidth: 2)
                        .frame(width: 120, height: 120)
                        .scaleEffect(animateRing ? 1.5 + CGFloat(index) * 0.3 : 1.0)
                        .opacity(animateRing ? 0.0 : 0.8)
                        .animation(
                            Animation.easeOut(duration: 1.5)
                                .repeatForever(autoreverses: false)
                                .delay(Double(index) * 0.3),
                            value: animateRing
                        )
                }

                // Phone icon
                Image(systemName: "phone.fill")
                    .font(.system(size: 60))
                    .foregroundColor(.white)
                    .frame(width: 120, height: 120)
                    .background(
                        LinearGradient(
                            colors: [Color.green, Color.green.opacity(0.8)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .clipShape(Circle())
                    .shadow(color: .green.opacity(0.4), radius: 20, x: 0, y: 10)
            }
            .padding(.top, 40)
            .onAppear {
                animateRing = true
            }

            // Contact/number info with better typography
            VStack(spacing: 10) {
                Text(call.displayName)
                    .font(.system(size: 28, weight: .semibold, design: .rounded))
                    .foregroundColor(.primary)

                if call.contactName != nil {
                    Text(call.formattedPhoneNumber)
                        .font(.system(size: 18, weight: .regular, design: .rounded))
                        .foregroundColor(.secondary)
                }

                Text("Incoming Call")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.secondary)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 6)
                    .background(Color.secondary.opacity(0.1))
                    .clipShape(Capsule())
                    .padding(.top, 4)
            }

            Spacer()

            // Modern action buttons with haptic feedback feel
            HStack(spacing: 60) {
                // Reject button - Modern design
                Button(action: onReject) {
                    VStack(spacing: 10) {
                        ZStack {
                            Circle()
                                .fill(
                                    LinearGradient(
                                        colors: [Color.red, Color.red.opacity(0.8)],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                                .frame(width: 75, height: 75)
                                .shadow(color: .red.opacity(0.3), radius: 15, x: 0, y: 8)

                            Image(systemName: "phone.down.fill")
                                .font(.system(size: 32, weight: .medium))
                                .foregroundColor(.white)
                        }

                        Text("Decline")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(.secondary)
                    }
                }
                .buttonStyle(PlainButtonStyle())
                .scaleEffect(1.0)
                .animation(.spring(response: 0.3, dampingFraction: 0.6), value: UUID())
                .help("Reject call")

                // Answer button - Modern design
                Button(action: onAnswer) {
                    VStack(spacing: 10) {
                        ZStack {
                            Circle()
                                .fill(
                                    LinearGradient(
                                        colors: [Color.green, Color.green.opacity(0.8)],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                                .frame(width: 75, height: 75)
                                .shadow(color: .green.opacity(0.3), radius: 15, x: 0, y: 8)

                            Image(systemName: "phone.fill")
                                .font(.system(size: 32, weight: .medium))
                                .foregroundColor(.white)
                        }

                        Text("Accept")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(.secondary)
                    }
                }
                .buttonStyle(PlainButtonStyle())
                .scaleEffect(1.0)
                .animation(.spring(response: 0.3, dampingFraction: 0.6), value: UUID())
                .help("Answer call")
            }
            .padding(.bottom, 40)
        }
        .frame(width: 400, height: 520)
        .background(
            RoundedRectangle(cornerRadius: 24)
                .fill(Color(nsColor: .windowBackgroundColor))
                .shadow(color: Color.black.opacity(0.2), radius: 30, x: 0, y: 15)
        )
    }
}

struct IncomingCallView_Previews: PreviewProvider {
    static var previews: some View {
        IncomingCallView(
            call: ActiveCall(
                id: "123",
                phoneNumber: "+19032867804",
                contactName: "John Doe",
                callState: .ringing,
                timestamp: Date()
            ),
            onAnswer: {},
            onReject: {}
        )
    }
}
