//
//  CallRecordingService.swift
//  SyncFlowMac
//
//  Records audio during SyncFlow calls
//

import Foundation
import AVFoundation
import Combine

class CallRecordingService: NSObject, ObservableObject {

    // MARK: - Published State

    @Published var isRecording = false
    @Published var recordingDuration: TimeInterval = 0
    @Published var currentRecordingURL: URL?

    // MARK: - Private Properties

    private var audioEngine: AVAudioEngine?
    private var audioFile: AVAudioFile?
    private var recordingTimer: Timer?
    private var startTime: Date?

    private let fileManager = FileManager.default

    // MARK: - Recording Directory

    private var recordingsDirectory: URL {
        let appSupport = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let recordingsDir = appSupport.appendingPathComponent("SyncFlow/CallRecordings")

        // Create directory if it doesn't exist
        if !fileManager.fileExists(atPath: recordingsDir.path) {
            try? fileManager.createDirectory(at: recordingsDir, withIntermediateDirectories: true)
        }

        return recordingsDir
    }

    // MARK: - Start Recording

    func startRecording(callId: String, contactName: String) throws {
        guard !isRecording else { return }

        // Create audio engine
        audioEngine = AVAudioEngine()
        guard let engine = audioEngine else {
            throw CallRecordingError.engineCreationFailed
        }

        let inputNode = engine.inputNode
        let format = inputNode.outputFormat(forBus: 0)

        // Ensure format is valid
        guard format.sampleRate > 0 && format.channelCount > 0 else {
            throw CallRecordingError.invalidAudioFormat
        }

        // Create output file
        let timestamp = ISO8601DateFormatter().string(from: Date())
            .replacingOccurrences(of: ":", with: "-")
        let sanitizedName = contactName.replacingOccurrences(of: "[^a-zA-Z0-9]", with: "_", options: .regularExpression)
        let fileName = "call_\(sanitizedName)_\(timestamp).m4a"
        let fileURL = recordingsDirectory.appendingPathComponent(fileName)

        // Create audio file with AAC encoding
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: format.sampleRate,
            AVNumberOfChannelsKey: format.channelCount,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
            AVEncoderBitRateKey: 128000
        ]

        audioFile = try AVAudioFile(forWriting: fileURL, settings: settings)

        // Install tap on input node to capture audio
        inputNode.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, time in
            do {
                try self?.audioFile?.write(from: buffer)
            } catch {
                print("[CallRecording] Error writing audio buffer: \(error)")
            }
        }

        // Start engine
        try engine.start()

        currentRecordingURL = fileURL
        isRecording = true
        startTime = Date()
        recordingDuration = 0

        // Start duration timer
        recordingTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            guard let self = self, let start = self.startTime else { return }
            self.recordingDuration = Date().timeIntervalSince(start)
        }

        print("[CallRecording] Started recording to: \(fileURL.path)")
    }

    // MARK: - Stop Recording

    func stopRecording() -> CallRecording? {
        guard isRecording else { return nil }

        // Stop timer
        recordingTimer?.invalidate()
        recordingTimer = nil

        // Remove tap and stop engine
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioEngine?.stop()
        audioEngine = nil

        // Close audio file
        audioFile = nil

        let duration = recordingDuration
        let url = currentRecordingURL

        isRecording = false
        currentRecordingURL = nil
        recordingDuration = 0
        startTime = nil

        guard let recordingURL = url else { return nil }

        print("[CallRecording] Stopped recording. Duration: \(duration)s")

        // Create recording metadata
        let recording = CallRecording(
            id: UUID().uuidString,
            url: recordingURL,
            date: Date(),
            duration: duration,
            contactName: extractContactName(from: recordingURL),
            fileSize: getFileSize(at: recordingURL)
        )

        // Save metadata
        saveRecordingMetadata(recording)

        return recording
    }

    // MARK: - Cancel Recording

    func cancelRecording() {
        guard isRecording else { return }

        // Stop timer
        recordingTimer?.invalidate()
        recordingTimer = nil

        // Remove tap and stop engine
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioEngine?.stop()
        audioEngine = nil
        audioFile = nil

        // Delete the partial recording file
        if let url = currentRecordingURL {
            try? fileManager.removeItem(at: url)
        }

        isRecording = false
        currentRecordingURL = nil
        recordingDuration = 0
        startTime = nil

        print("[CallRecording] Recording cancelled")
    }

    // MARK: - Get All Recordings

    func getAllRecordings() -> [CallRecording] {
        return loadRecordingsMetadata()
    }

    // MARK: - Delete Recording

    func deleteRecording(_ recording: CallRecording) {
        // Delete file
        try? fileManager.removeItem(at: recording.url)

        // Update metadata
        var recordings = loadRecordingsMetadata()
        recordings.removeAll { $0.id == recording.id }
        saveRecordingsMetadata(recordings)

        print("[CallRecording] Deleted recording: \(recording.url.lastPathComponent)")
    }

    // MARK: - Export Recording

    func exportRecording(_ recording: CallRecording, to destination: URL) throws {
        try fileManager.copyItem(at: recording.url, to: destination)
        print("[CallRecording] Exported to: \(destination.path)")
    }

    // MARK: - Helpers

    private func extractContactName(from url: URL) -> String {
        let fileName = url.deletingPathExtension().lastPathComponent
        let parts = fileName.components(separatedBy: "_")
        if parts.count >= 2 {
            return parts[1]
        }
        return "Unknown"
    }

    private func getFileSize(at url: URL) -> Int64 {
        guard let attrs = try? fileManager.attributesOfItem(atPath: url.path),
              let size = attrs[.size] as? Int64 else {
            return 0
        }
        return size
    }

    // MARK: - Metadata Persistence

    private var metadataURL: URL {
        recordingsDirectory.appendingPathComponent("recordings_metadata.json")
    }

    private func loadRecordingsMetadata() -> [CallRecording] {
        guard let data = try? Data(contentsOf: metadataURL),
              let recordings = try? JSONDecoder().decode([CallRecording].self, from: data) else {
            return []
        }

        // Filter out recordings where file no longer exists
        return recordings.filter { fileManager.fileExists(atPath: $0.url.path) }
    }

    private func saveRecordingsMetadata(_ recordings: [CallRecording]) {
        guard let data = try? JSONEncoder().encode(recordings) else { return }
        try? data.write(to: metadataURL)
    }

    private func saveRecordingMetadata(_ recording: CallRecording) {
        var recordings = loadRecordingsMetadata()
        recordings.insert(recording, at: 0)
        saveRecordingsMetadata(recordings)
    }

    // MARK: - Format Duration

    func formatDuration(_ duration: TimeInterval) -> String {
        let hours = Int(duration) / 3600
        let minutes = (Int(duration) % 3600) / 60
        let seconds = Int(duration) % 60

        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            return String(format: "%d:%02d", minutes, seconds)
        }
    }
}

// MARK: - Call Recording Model

struct CallRecording: Identifiable, Codable, Hashable {
    let id: String
    let url: URL
    let date: Date
    let duration: TimeInterval
    let contactName: String
    let fileSize: Int64

    var formattedDuration: String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }

    var formattedSize: String {
        let bytes = fileSize
        if bytes < 1024 {
            return "\(bytes) B"
        } else if bytes < 1024 * 1024 {
            return String(format: "%.1f KB", Double(bytes) / 1024)
        } else {
            return String(format: "%.1f MB", Double(bytes) / (1024 * 1024))
        }
    }

    var formattedDate: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}

// MARK: - Errors

enum CallRecordingError: LocalizedError {
    case engineCreationFailed
    case invalidAudioFormat
    case recordingInProgress
    case noActiveRecording

    var errorDescription: String? {
        switch self {
        case .engineCreationFailed:
            return "Failed to create audio engine"
        case .invalidAudioFormat:
            return "Invalid audio format"
        case .recordingInProgress:
            return "Recording already in progress"
        case .noActiveRecording:
            return "No active recording"
        }
    }
}
