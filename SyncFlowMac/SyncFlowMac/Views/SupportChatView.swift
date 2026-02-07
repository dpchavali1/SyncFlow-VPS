//
//  SupportChatView.swift
//  SyncFlowMac
//
//  AI-powered support chatbot using Gemini
//

import SwiftUI
import Combine
// FirebaseFunctions - using FirebaseStubs.swift

struct SupportChatMessage: Identifiable, Equatable {
    let id = UUID()
    let role: String // "user" or "assistant"
    let content: String
    let timestamp: Date
}

class SupportChatViewModel: ObservableObject {
    @Published var messages: [SupportChatMessage] = [
        SupportChatMessage(
            role: "assistant",
            content: "Hi! I'm SyncFlow's AI assistant. How can I help you today?",
            timestamp: Date()
        )
    ]
    @Published var inputText: String = ""
    @Published var isLoading: Bool = false

    private lazy var functions = Functions.functions()

    // The actual sync group user ID (not the anonymous auth ID)
    var syncGroupUserId: String?

    let quickQuestions = [
        "What's my recovery code?",
        "Show my sync status",
        "How many messages synced?",
        "Show my devices"
    ]

    func sendMessage() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isLoading else { return }

        let userMessage = SupportChatMessage(role: "user", content: text, timestamp: Date())
        messages.append(userMessage)
        inputText = ""
        isLoading = true

        // Prepare conversation history
        let history = messages.suffix(6).map { msg in
            ["role": msg.role, "content": msg.content]
        }

        // Pass the actual sync group userId, not the anonymous auth ID
        var data: [String: Any] = [
            "message": text,
            "conversationHistory": history
        ]

        // Include the real user ID if available
        if let userId = syncGroupUserId, !userId.isEmpty {
            data["syncGroupUserId"] = userId
        }

        functions.httpsCallable("supportChat").call(data) { [weak self] result, error in
            DispatchQueue.main.async {
                self?.isLoading = false

                if let error = error {
                    print("Chat error: \(error.localizedDescription)")
                    self?.messages.append(SupportChatMessage(
                        role: "assistant",
                        content: "Sorry, I couldn't process your question. Please try again or contact syncflow.contact@gmail.com",
                        timestamp: Date()
                    ))
                    return
                }

                if let data = result?.data as? [String: Any],
                   let success = data["success"] as? Bool,
                   success,
                   let response = data["response"] as? String {
                    self?.messages.append(SupportChatMessage(
                        role: "assistant",
                        content: response,
                        timestamp: Date()
                    ))
                }
            }
        }
    }
}

struct SupportChatView: View {
    @StateObject private var viewModel = SupportChatViewModel()
    @EnvironmentObject var appState: AppState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Image(systemName: "sparkles")
                    .font(.title2)
                    .foregroundColor(.blue)

                VStack(alignment: .leading, spacing: 2) {
                    Text("SyncFlow Support")
                        .font(.headline)
                    Text("AI Assistant")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                Button(action: { dismiss() }) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }
            .padding()
            .background(Color(nsColor: .windowBackgroundColor))

            Divider()

            // Messages
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(viewModel.messages) { message in
                            SupportMessageBubble(message: message)
                                .id(message.id)
                        }

                        if viewModel.isLoading {
                            HStack {
                                SupportTypingIndicator()
                                Spacer()
                            }
                            .padding(.horizontal)
                        }
                    }
                    .padding()
                }
                .onChange(of: viewModel.messages.count) { _ in
                    if let lastMessage = viewModel.messages.last {
                        withAnimation {
                            proxy.scrollTo(lastMessage.id, anchor: .bottom)
                        }
                    }
                }
            }

            // Quick Questions (show only at start)
            if viewModel.messages.count == 1 {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Quick questions:")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    SupportFlowLayout(spacing: 8) {
                        ForEach(viewModel.quickQuestions, id: \.self) { question in
                            Button(action: {
                                viewModel.inputText = question
                            }) {
                                Text(question)
                                    .font(.caption)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(Color.secondary.opacity(0.1))
                                    .cornerRadius(16)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(.horizontal)
                .padding(.bottom, 8)
            }

            Divider()

            // Input
            HStack(spacing: 12) {
                TextField("Ask a question...", text: $viewModel.inputText)
                    .textFieldStyle(.plain)
                    .padding(10)
                    .background(Color.secondary.opacity(0.1))
                    .cornerRadius(20)
                    .onSubmit {
                        viewModel.sendMessage()
                    }
                    .disabled(viewModel.isLoading)

                Button(action: viewModel.sendMessage) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title)
                        .foregroundColor(viewModel.inputText.isEmpty || viewModel.isLoading ? .gray : .blue)
                }
                .buttonStyle(.plain)
                .disabled(viewModel.inputText.isEmpty || viewModel.isLoading)
            }
            .padding()
        }
        .frame(width: 400, height: 500)
        .onAppear {
            // Pass the actual sync group userId to the viewModel
            viewModel.syncGroupUserId = appState.userId
        }
    }
}

// MARK: - Support Chat Message Bubble

private struct SupportMessageBubble: View {
    let message: SupportChatMessage
    @State private var isHovering = false
    @State private var showCopied = false

    var isUser: Bool {
        message.role == "user"
    }

    var body: some View {
        HStack {
            if isUser { Spacer(minLength: 60) }

            VStack(alignment: isUser ? .trailing : .leading, spacing: 4) {
                Text(message.content)
                    .textSelection(.enabled)
                    .padding(12)
                    .background(isUser ? Color.blue : Color.secondary.opacity(0.1))
                    .foregroundColor(isUser ? .white : .primary)
                    .cornerRadius(16)

                // Copy button for assistant messages
                if !isUser {
                    Button(action: copyToClipboard) {
                        HStack(spacing: 4) {
                            Image(systemName: showCopied ? "checkmark" : "doc.on.doc")
                                .font(.caption2)
                            Text(showCopied ? "Copied!" : "Copy")
                                .font(.caption2)
                        }
                        .foregroundColor(.secondary)
                        .opacity(isHovering || showCopied ? 1 : 0)
                    }
                    .buttonStyle(.plain)
                }
            }
            .onHover { hovering in
                isHovering = hovering
            }

            if !isUser { Spacer(minLength: 60) }
        }
    }

    private func copyToClipboard() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(message.content, forType: .string)
        showCopied = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            showCopied = false
        }
    }
}

// MARK: - Support Typing Indicator

private struct SupportTypingIndicator: View {
    @State private var animationOffset: CGFloat = 0

    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(Color.secondary)
                    .frame(width: 8, height: 8)
                    .offset(y: animationOffset)
                    .animation(
                        Animation.easeInOut(duration: 0.5)
                            .repeatForever()
                            .delay(Double(index) * 0.15),
                        value: animationOffset
                    )
            }
        }
        .padding(12)
        .background(Color.secondary.opacity(0.1))
        .cornerRadius(16)
        .onAppear {
            animationOffset = -5
        }
    }
}

// MARK: - Support Flow Layout

private struct SupportFlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let result = SupportFlowResult(in: proposal.width ?? 0, subviews: subviews, spacing: spacing)
        return result.size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = SupportFlowResult(in: bounds.width, subviews: subviews, spacing: spacing)
        for (index, subview) in subviews.enumerated() {
            subview.place(at: CGPoint(x: bounds.minX + result.positions[index].x,
                                      y: bounds.minY + result.positions[index].y),
                         proposal: .unspecified)
        }
    }

    struct SupportFlowResult {
        var size: CGSize = .zero
        var positions: [CGPoint] = []

        init(in maxWidth: CGFloat, subviews: Subviews, spacing: CGFloat) {
            var x: CGFloat = 0
            var y: CGFloat = 0
            var rowHeight: CGFloat = 0

            for subview in subviews {
                let size = subview.sizeThatFits(.unspecified)

                if x + size.width > maxWidth && x > 0 {
                    x = 0
                    y += rowHeight + spacing
                    rowHeight = 0
                }

                positions.append(CGPoint(x: x, y: y))
                rowHeight = max(rowHeight, size.height)
                x += size.width + spacing
            }

            self.size = CGSize(width: maxWidth, height: y + rowHeight)
        }
    }
}

#Preview {
    SupportChatView()
}
