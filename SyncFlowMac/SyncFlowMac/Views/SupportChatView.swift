//
//  SupportChatView.swift
//  SyncFlowMac
//
//  Support chatbot
//

import SwiftUI
import Combine

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

    var syncGroupUserId: String?

    private let baseUrl = ProcessInfo.processInfo.environment["SYNCFLOW_VPS_URL"] ?? "https://api.sfweb.app"

    let quickQuestions = [
        "How does account recovery work?",
        "Show my sync status",
        "How many messages synced?",
        "Show my devices"
    ]

    func sendMessage(_ text: String? = nil) {
        let msgText = (text ?? inputText).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !msgText.isEmpty, !isLoading else { return }

        let userMessage = SupportChatMessage(role: "user", content: msgText, timestamp: Date())
        messages.append(userMessage)
        inputText = ""
        isLoading = true

        let history = messages.suffix(6).map { msg in
            ["role": msg.role, "content": msg.content]
        }

        var payload: [String: Any] = [
            "message": msgText,
            "conversationHistory": history
        ]

        if let userId = syncGroupUserId, !userId.isEmpty {
            payload["syncGroupUserId"] = userId
        }

        Task {
            do {
                guard let url = URL(string: "\(baseUrl)/api/support-chat") else { throw URLError(.badURL) }
                var request = URLRequest(url: url)
                request.httpMethod = "POST"
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                if let authHeader = VPSService.shared.authorizationHeader {
                    request.setValue(authHeader, forHTTPHeaderField: "Authorization")
                }
                request.httpBody = try JSONSerialization.data(withJSONObject: payload)

                let (data, _) = try await URLSession.shared.data(for: request)
                if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let success = json["success"] as? Bool,
                   success,
                   let response = json["response"] as? String {
                    await MainActor.run {
                        self.isLoading = false
                        self.messages.append(SupportChatMessage(
                            role: "assistant",
                            content: response,
                            timestamp: Date()
                        ))
                    }
                } else {
                    await showError()
                }
            } catch {
                print("Chat error: \(error.localizedDescription)")
                await showError()
            }
        }
    }

    @MainActor
    private func showError() {
        isLoading = false
        messages.append(SupportChatMessage(
            role: "assistant",
            content: "Sorry, I couldn't process your question. Please try again or contact syncflow.contact@gmail.com",
            timestamp: Date()
        ))
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
                                viewModel.sendMessage(question)
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

                Button(action: { viewModel.sendMessage() }) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title)
                        .foregroundColor(viewModel.inputText.isEmpty || viewModel.isLoading ? .gray : .blue)
                }
                .buttonStyle(.plain)
                .disabled(viewModel.inputText.isEmpty || viewModel.isLoading)
            }
            .padding()
        }
        .frame(minWidth: 350, idealWidth: 450, maxWidth: 700, minHeight: 400, idealHeight: 550, maxHeight: 800)
        .onAppear {
            // Pass the actual sync group userId to the viewModel
            viewModel.syncGroupUserId = appState.userId
        }
    }
}

// MARK: - Markdown Text Helper

/// Parses basic markdown (**bold**, *italic*) into an AttributedString for SwiftUI rendering.
private func parseMarkdownText(_ text: String) -> AttributedString {
    // Try SwiftUI's built-in markdown parser first
    if let attributed = try? AttributedString(
        markdown: text,
        options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
    ) {
        return attributed
    }
    // Fallback to plain text
    return AttributedString(text)
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
                Group {
                    if isUser {
                        Text(message.content)
                    } else {
                        Text(parseMarkdownText(message.content))
                    }
                }
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
