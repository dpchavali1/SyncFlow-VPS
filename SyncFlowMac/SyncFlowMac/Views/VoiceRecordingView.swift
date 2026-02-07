//
//  VoiceRecordingView.swift
//  SyncFlowMac
//
//  Voice recording interface for voice messages
//

import SwiftUI

struct VoiceRecordingView: View {
    @ObservedObject var audioRecorder: AudioRecorderService
    let onSend: (URL, TimeInterval) -> Void
    let onCancel: () -> Void

    @State private var animationPhase: CGFloat = 0

    var body: some View {
        HStack(spacing: 16) {
            // Cancel button
            Button(action: {
                audioRecorder.cancelRecording()
                onCancel()
            }) {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 24))
                    .foregroundColor(.red.opacity(0.8))
            }
            .buttonStyle(.plain)
            .help("Cancel recording")

            // Waveform visualization
            HStack(spacing: 3) {
                ForEach(0..<20, id: \.self) { index in
                    WaveformBar(
                        level: audioRecorder.audioLevel,
                        index: index,
                        animationPhase: animationPhase
                    )
                }
            }
            .frame(height: 32)
            .frame(maxWidth: .infinity)

            // Recording indicator and duration
            HStack(spacing: 8) {
                Circle()
                    .fill(Color.red)
                    .frame(width: 10, height: 10)
                    .opacity(animationPhase > 0.5 ? 1 : 0.3)

                Text(audioRecorder.formatDuration(audioRecorder.recordingDuration))
                    .font(.system(.body, design: .monospaced))
                    .foregroundColor(.primary)
                    .frame(minWidth: 60)
            }

            // Send button
            Button(action: {
                if let url = audioRecorder.stopRecording() {
                    onSend(url, audioRecorder.recordingDuration)
                }
            }) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 28))
                    .foregroundColor(.blue)
            }
            .buttonStyle(.plain)
            .help("Send voice message")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color(nsColor: .controlBackgroundColor))
        .onAppear {
            // Start animation
            withAnimation(.linear(duration: 1).repeatForever(autoreverses: true)) {
                animationPhase = 1
            }
        }
    }
}

// MARK: - Waveform Bar

struct WaveformBar: View {
    let level: Float
    let index: Int
    let animationPhase: CGFloat

    var body: some View {
        RoundedRectangle(cornerRadius: 2)
            .fill(Color.blue)
            .frame(width: 4, height: barHeight)
            .animation(.easeInOut(duration: 0.1), value: level)
    }

    private var barHeight: CGFloat {
        // Create a wave-like pattern
        let phase = Double(index) / 20.0 * .pi * 2
        let sine = sin(phase + Double(animationPhase) * .pi * 2)
        let baseHeight: CGFloat = 8
        let audioContribution = CGFloat(level) * 24
        let waveContribution = (CGFloat(sine) + 1) * 4 * CGFloat(level)

        return max(baseHeight, baseHeight + audioContribution + waveContribution)
    }
}

// MARK: - Microphone Button (for ComposeBar)

struct MicrophoneButton: View {
    @ObservedObject var audioRecorder: AudioRecorderService
    let onStartRecording: () -> Void

    @State private var isHovering = false
    @State private var showPermissionAlert = false

    var body: some View {
        Button(action: {
            if audioRecorder.hasPermission {
                onStartRecording()
            } else {
                showPermissionAlert = true
            }
        }) {
            Image(systemName: "mic.fill")
                .font(.title3)
                .foregroundColor(isHovering ? .blue : .secondary)
                .scaleEffect(isHovering ? 1.1 : 1.0)
        }
        .buttonStyle(.plain)
        .help("Record voice message")
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.15)) {
                isHovering = hovering
            }
        }
        .alert("Microphone Access Required", isPresented: $showPermissionAlert) {
            Button("Open Settings") {
                if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone") {
                    NSWorkspace.shared.open(url)
                }
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("SyncFlow needs microphone access to record voice messages. Please enable it in System Settings > Privacy & Security > Microphone.")
        }
    }
}

// MARK: - Voice Message Preview (for received messages)

struct VoiceMessageBubble: View {
    let duration: TimeInterval
    let audioURL: URL?
    let attachment: MmsAttachment?  // Optional attachment with inline data support
    let isReceived: Bool

    init(duration: TimeInterval, audioURL: URL? = nil, attachment: MmsAttachment? = nil, isReceived: Bool) {
        self.duration = duration
        self.audioURL = audioURL
        self.attachment = attachment
        self.isReceived = isReceived
    }

    @State private var isPlaying = false
    @State private var playbackProgress: Double = 0
    @State private var audioPlayer: AVPlayer?
    @State private var timeObserver: Any?

    /// Get the URL to play, either from direct URL or from attachment (including inline data)
    private var effectiveAudioURL: URL? {
        if let url = audioURL {
            return url
        }
        return attachment?.playableURL
    }

    var body: some View {
        HStack(spacing: 12) {
            // Play/Pause button
            Button(action: togglePlayback) {
                Image(systemName: isPlaying ? "pause.circle.fill" : "play.circle.fill")
                    .font(.system(size: 36))
                    .foregroundColor(isReceived ? .blue : .white)
            }
            .buttonStyle(.plain)
            .disabled(effectiveAudioURL == nil)

            VStack(spacing: 6) {
                // Progress bar
                GeometryReader { geometry in
                    ZStack(alignment: .leading) {
                        // Background track
                        RoundedRectangle(cornerRadius: 2)
                            .fill(isReceived ? Color.gray.opacity(0.3) : Color.white.opacity(0.3))
                            .frame(height: 4)

                        // Progress
                        RoundedRectangle(cornerRadius: 2)
                            .fill(isReceived ? Color.blue : Color.white)
                            .frame(width: geometry.size.width * playbackProgress, height: 4)
                    }
                }
                .frame(height: 4)

                // Duration
                HStack {
                    Text(formatTime(playbackProgress * duration))
                        .font(.caption2)
                    Spacer()
                    Text(formatTime(duration))
                        .font(.caption2)
                }
                .foregroundColor(isReceived ? .secondary : .white.opacity(0.7))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(width: 220)
        .background(isReceived ? Color(nsColor: .controlBackgroundColor) : Color.blue)
        .cornerRadius(16)
        .onDisappear {
            cleanup()
        }
    }

    private func togglePlayback() {
        guard let url = effectiveAudioURL else { return }

        if audioPlayer == nil {
            let player = AVPlayer(url: url)
            audioPlayer = player

            // Add time observer
            let interval = CMTime(seconds: 0.1, preferredTimescale: 600)
            timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { time in
                let currentTime = CMTimeGetSeconds(time)
                playbackProgress = currentTime / duration

                // Reset when finished
                if currentTime >= duration {
                    isPlaying = false
                    playbackProgress = 0
                    player.seek(to: .zero)
                }
            }
        }

        if isPlaying {
            audioPlayer?.pause()
        } else {
            audioPlayer?.play()
        }
        isPlaying.toggle()
    }

    private func cleanup() {
        audioPlayer?.pause()
        if let observer = timeObserver {
            audioPlayer?.removeTimeObserver(observer)
        }
        audioPlayer = nil
        timeObserver = nil
    }

    private func formatTime(_ seconds: TimeInterval) -> String {
        let mins = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return String(format: "%d:%02d", mins, secs)
    }
}

import AVFoundation

// MARK: - Preview

struct VoiceRecordingView_Previews: PreviewProvider {
    static var previews: some View {
        VoiceRecordingView(
            audioRecorder: AudioRecorderService.shared,
            onSend: { _, _ in },
            onCancel: { }
        )
        .frame(width: 500)
        .padding()
    }
}
