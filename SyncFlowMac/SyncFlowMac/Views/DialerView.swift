//
//  DialerView.swift
//  SyncFlowMac
//
//  Dial pad for making outgoing calls
//

import SwiftUI

struct DialerView: View {
    @EnvironmentObject var appState: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var phoneNumber: String = ""
    @State private var isCallInProgress = false

    private let dialPadButtons: [[DialButton]] = [
        [.digit("1", ""), .digit("2", "ABC"), .digit("3", "DEF")],
        [.digit("4", "GHI"), .digit("5", "JKL"), .digit("6", "MNO")],
        [.digit("7", "PQRS"), .digit("8", "TUV"), .digit("9", "WXYZ")],
        [.special("*"), .digit("0", "+"), .special("#")]
    ]

    var body: some View {
        VStack(spacing: 20) {
            // Header
            HStack {
                Text("Make a Call")
                    .font(.title2)
                    .fontWeight(.semibold)
                Spacer()
                Button(action: { dismiss() }) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal)

            // Phone number display
            HStack {
                Text(phoneNumber.isEmpty ? "Enter number" : formattedPhoneNumber)
                    .font(.system(size: 32, weight: .light, design: .rounded))
                    .foregroundColor(phoneNumber.isEmpty ? .secondary : .primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)

                if !phoneNumber.isEmpty {
                    Button(action: { deleteLastDigit() }) {
                        Image(systemName: "delete.left.fill")
                            .font(.title2)
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .frame(height: 50)
            .padding(.horizontal, 30)

            // Dial pad
            VStack(spacing: 12) {
                ForEach(dialPadButtons, id: \.self) { row in
                    HStack(spacing: 20) {
                        ForEach(row) { button in
                            DialPadButton(button: button) {
                                addDigit(button.value)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 20)

            // Call button
            Button(action: makeCall) {
                HStack(spacing: 10) {
                    Image(systemName: "phone.fill")
                        .font(.title2)
                    Text("Call")
                        .font(.headline)
                }
                .foregroundColor(.white)
                .frame(width: 120, height: 50)
                .background(phoneNumber.isEmpty ? Color.gray : Color.green)
                .clipShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(phoneNumber.isEmpty || isCallInProgress)
            .padding(.top, 10)

            Spacer()
        }
        .padding(.vertical, 20)
        .frame(width: 320, height: 480)
    }

    private var formattedPhoneNumber: String {
        // Simple formatting for display
        let digits = phoneNumber.filter { $0.isNumber || $0 == "+" }
        if digits.count <= 3 {
            return digits
        } else if digits.count <= 6 {
            let index = digits.index(digits.startIndex, offsetBy: 3)
            return "\(digits[..<index]) \(digits[index...])"
        } else if digits.count <= 10 {
            let index1 = digits.index(digits.startIndex, offsetBy: 3)
            let index2 = digits.index(digits.startIndex, offsetBy: 6)
            return "\(digits[..<index1]) \(digits[index1..<index2]) \(digits[index2...])"
        } else {
            return digits
        }
    }

    private func addDigit(_ digit: String) {
        if phoneNumber.count < 15 {
            phoneNumber += digit
        }
    }

    private func deleteLastDigit() {
        if !phoneNumber.isEmpty {
            phoneNumber.removeLast()
        }
    }

    private func makeCall() {
        guard !phoneNumber.isEmpty else { return }

        isCallInProgress = true
        appState.makeCall(to: phoneNumber)

        // Dismiss after a short delay
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            dismiss()
        }
    }
}

// MARK: - Dial Button Model

enum DialButton: Identifiable, Hashable {
    case digit(String, String)  // number, letters
    case special(String)

    var id: String { value }

    var value: String {
        switch self {
        case .digit(let num, _): return num
        case .special(let char): return char
        }
    }

    var letters: String? {
        switch self {
        case .digit(_, let letters): return letters.isEmpty ? nil : letters
        case .special: return nil
        }
    }
}

// MARK: - Dial Pad Button View

struct DialPadButton: View {
    let button: DialButton
    let action: () -> Void

    @State private var isPressed = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Text(button.value)
                    .font(.system(size: 28, weight: .light, design: .rounded))

                if let letters = button.letters {
                    Text(letters)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundColor(.secondary)
                }
            }
            .frame(width: 70, height: 70)
            .background(
                Circle()
                    .fill(Color.secondary.opacity(isPressed ? 0.3 : 0.1))
            )
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            isPressed = hovering
        }
    }
}

// MARK: - Preview

struct DialerView_Previews: PreviewProvider {
    static var previews: some View {
        DialerView()
            .environmentObject(AppState())
    }
}
