//
//  RecordingsView.swift
//  SyncFlowMac
//
//  View for managing call recordings
//

import SwiftUI
import AVFoundation

struct RecordingsView: View {
    @StateObject private var recordingService = CallRecordingService()
    @State private var recordings: [CallRecording] = []
    @State private var selectedRecording: CallRecording?
    @State private var showingDeleteAlert = false
    @State private var recordingToDelete: CallRecording?
    @State private var isPlaying = false
    @State private var audioPlayer: AVAudioPlayer?
    @State private var playbackProgress: Double = 0
    @State private var playbackTimer: Timer?

    var body: some View {
        VStack(spacing: 0) {
            if recordings.isEmpty {
                emptyStateView
            } else {
                recordingsList
            }
        }
        .onAppear {
            loadRecordings()
        }
        .onDisappear {
            stopPlayback()
        }
        .alert("Delete Recording?", isPresented: $showingDeleteAlert) {
            Button("Cancel", role: .cancel) {
                recordingToDelete = nil
            }
            Button("Delete", role: .destructive) {
                if let recording = recordingToDelete {
                    deleteRecording(recording)
                }
            }
        } message: {
            if let recording = recordingToDelete {
                Text("Are you sure you want to delete the recording from \(recording.contactName) on \(recording.formattedDate)?")
            }
        }
    }

    // MARK: - Empty State

    private var emptyStateView: some View {
        VStack(spacing: 16) {
            Image(systemName: "waveform.circle")
                .font(.system(size: 48))
                .foregroundColor(.secondary)

            Text("No Recordings")
                .font(.headline)

            Text("Call recordings will appear here.\nEnable recording during a call to save it.")
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .font(.caption)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }

    // MARK: - Recordings List

    private var recordingsList: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("\(recordings.count) Recording\(recordings.count == 1 ? "" : "s")")
                    .font(.headline)

                Spacer()

                Button(action: loadRecordings) {
                    Image(systemName: "arrow.clockwise")
                }
                .buttonStyle(.borderless)
                .help("Refresh")
            }
            .padding()

            Divider()

            // List
            List(selection: $selectedRecording) {
                ForEach(recordings) { recording in
                    RecordingRow(
                        recording: recording,
                        isSelected: selectedRecording?.id == recording.id,
                        isPlaying: isPlaying && selectedRecording?.id == recording.id,
                        playbackProgress: selectedRecording?.id == recording.id ? playbackProgress : 0,
                        onPlay: { playRecording(recording) },
                        onStop: stopPlayback,
                        onDelete: {
                            recordingToDelete = recording
                            showingDeleteAlert = true
                        },
                        onExport: { exportRecording(recording) },
                        onShowInFinder: { showInFinder(recording) }
                    )
                }
            }
            .listStyle(.inset)
        }
    }

    // MARK: - Actions

    private func loadRecordings() {
        recordings = recordingService.getAllRecordings()
    }

    private func playRecording(_ recording: CallRecording) {
        // Stop current playback if any
        stopPlayback()

        selectedRecording = recording

        do {
            audioPlayer = try AVAudioPlayer(contentsOf: recording.url)
            audioPlayer?.delegate = AudioPlayerDelegate.shared
            AudioPlayerDelegate.shared.onFinish = {
                DispatchQueue.main.async {
                    self.isPlaying = false
                    self.playbackProgress = 0
                }
            }
            audioPlayer?.prepareToPlay()
            audioPlayer?.play()
            isPlaying = true

            // Start progress timer
            playbackTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { _ in
                guard let player = audioPlayer, player.duration > 0 else { return }
                playbackProgress = player.currentTime / player.duration
            }
        } catch {
            print("[Recordings] Failed to play recording: \(error)")
        }
    }

    private func stopPlayback() {
        audioPlayer?.stop()
        audioPlayer = nil
        playbackTimer?.invalidate()
        playbackTimer = nil
        isPlaying = false
        playbackProgress = 0
    }

    private func deleteRecording(_ recording: CallRecording) {
        if selectedRecording?.id == recording.id {
            stopPlayback()
            selectedRecording = nil
        }

        recordingService.deleteRecording(recording)
        recordingToDelete = nil
        loadRecordings()
    }

    private func exportRecording(_ recording: CallRecording) {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.audio]
        panel.nameFieldStringValue = recording.url.lastPathComponent
        panel.canCreateDirectories = true

        if panel.runModal() == .OK, let url = panel.url {
            do {
                try recordingService.exportRecording(recording, to: url)
            } catch {
                print("[Recordings] Export failed: \(error)")
            }
        }
    }

    private func showInFinder(_ recording: CallRecording) {
        NSWorkspace.shared.activateFileViewerSelecting([recording.url])
    }
}

// MARK: - Recording Row

struct RecordingRow: View {
    let recording: CallRecording
    let isSelected: Bool
    let isPlaying: Bool
    let playbackProgress: Double
    let onPlay: () -> Void
    let onStop: () -> Void
    let onDelete: () -> Void
    let onExport: () -> Void
    let onShowInFinder: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                // Play/Stop button
                Button(action: {
                    if isPlaying {
                        onStop()
                    } else {
                        onPlay()
                    }
                }) {
                    Image(systemName: isPlaying ? "stop.fill" : "play.fill")
                        .font(.title2)
                        .foregroundColor(isPlaying ? .red : .blue)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.borderless)

                // Recording info
                VStack(alignment: .leading, spacing: 4) {
                    Text(recording.contactName)
                        .font(.headline)

                    HStack(spacing: 8) {
                        Text(recording.formattedDate)
                            .font(.caption)
                            .foregroundColor(.secondary)

                        Text("•")
                            .foregroundColor(.secondary)

                        Text(recording.formattedDuration)
                            .font(.caption)
                            .foregroundColor(.secondary)

                        Text("•")
                            .foregroundColor(.secondary)

                        Text(recording.formattedSize)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }

                Spacer()

                // Action buttons
                HStack(spacing: 8) {
                    Button(action: onShowInFinder) {
                        Image(systemName: "folder")
                    }
                    .buttonStyle(.borderless)
                    .help("Show in Finder")

                    Button(action: onExport) {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .buttonStyle(.borderless)
                    .help("Export")

                    Button(action: onDelete) {
                        Image(systemName: "trash")
                            .foregroundColor(.red)
                    }
                    .buttonStyle(.borderless)
                    .help("Delete")
                }
            }

            // Playback progress bar
            if isPlaying || playbackProgress > 0 {
                GeometryReader { geometry in
                    ZStack(alignment: .leading) {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(Color.secondary.opacity(0.2))
                            .frame(height: 4)

                        RoundedRectangle(cornerRadius: 2)
                            .fill(Color.blue)
                            .frame(width: geometry.size.width * playbackProgress, height: 4)
                    }
                }
                .frame(height: 4)
            }
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

// MARK: - Audio Player Delegate

class AudioPlayerDelegate: NSObject, AVAudioPlayerDelegate {
    static let shared = AudioPlayerDelegate()
    var onFinish: (() -> Void)?

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        onFinish?()
    }
}
