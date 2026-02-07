//
//  AudioRecorderService.swift
//  SyncFlowMac
//
//  Handles audio recording for voice messages
//

import Foundation
import AVFoundation
import AppKit
import Combine

class AudioRecorderService: NSObject, ObservableObject {
    static let shared = AudioRecorderService()

    @Published var isRecording = false
    @Published var recordingDuration: TimeInterval = 0
    @Published var audioLevel: Float = 0
    @Published var recordingURL: URL?
    @Published var hasPermission = false

    private var audioRecorder: AVAudioRecorder?
    private var recordingTimer: Timer?
    private var levelTimer: Timer?
    private var startTime: Date?

    private let fileManager = FileManager.default

    override init() {
        super.init()
        checkPermission()
    }

    // MARK: - Permission

    func checkPermission() {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            hasPermission = true
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { [weak self] granted in
                DispatchQueue.main.async {
                    self?.hasPermission = granted
                }
            }
        case .denied, .restricted:
            hasPermission = false
        @unknown default:
            hasPermission = false
        }
    }

    func requestPermission() {
        AVCaptureDevice.requestAccess(for: .audio) { [weak self] granted in
            DispatchQueue.main.async {
                self?.hasPermission = granted
            }
        }
    }

    // MARK: - Recording

    func startRecording() {
        guard hasPermission else {
            requestPermission()
            return
        }

        // Create temp file for recording
        let tempDir = fileManager.temporaryDirectory
        let fileName = "voice_message_\(Int(Date().timeIntervalSince1970)).m4a"
        let fileURL = tempDir.appendingPathComponent(fileName)

        // Audio settings for high quality voice
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44100.0,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
            AVEncoderBitRateKey: 128000
        ]

        do {
            audioRecorder = try AVAudioRecorder(url: fileURL, settings: settings)
            audioRecorder?.delegate = self
            audioRecorder?.isMeteringEnabled = true
            audioRecorder?.prepareToRecord()
            audioRecorder?.record()

            recordingURL = fileURL
            isRecording = true
            startTime = Date()
            recordingDuration = 0

            // Start timer to update duration
            recordingTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                guard let self = self, let start = self.startTime else { return }
                self.recordingDuration = Date().timeIntervalSince(start)
            }

            // Start timer to update audio level
            levelTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
                guard let self = self, let recorder = self.audioRecorder else { return }
                recorder.updateMeters()
                let level = recorder.averagePower(forChannel: 0)
                // Convert dB to 0-1 range
                let normalizedLevel = max(0, (level + 60) / 60)
                self.audioLevel = normalizedLevel
            }

            print("[AudioRecorder] Started recording to: \(fileURL.path)")
        } catch {
            print("[AudioRecorder] Failed to start recording: \(error)")
        }
    }

    func stopRecording() -> URL? {
        recordingTimer?.invalidate()
        recordingTimer = nil
        levelTimer?.invalidate()
        levelTimer = nil

        audioRecorder?.stop()
        isRecording = false
        audioLevel = 0

        let url = recordingURL
        print("[AudioRecorder] Stopped recording. Duration: \(recordingDuration)s")

        return url
    }

    func cancelRecording() {
        recordingTimer?.invalidate()
        recordingTimer = nil
        levelTimer?.invalidate()
        levelTimer = nil

        audioRecorder?.stop()
        isRecording = false
        audioLevel = 0
        recordingDuration = 0

        // Delete the recording file
        if let url = recordingURL {
            try? fileManager.removeItem(at: url)
        }
        recordingURL = nil

        print("[AudioRecorder] Recording cancelled")
    }

    // MARK: - Playback Preview

    func getRecordingData() -> Data? {
        guard let url = recordingURL else { return nil }
        return try? Data(contentsOf: url)
    }

    func getRecordingDuration(for url: URL) -> TimeInterval? {
        let asset = AVURLAsset(url: url)
        return asset.duration.seconds
    }

    // MARK: - Helpers

    func formatDuration(_ duration: TimeInterval) -> String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        let milliseconds = Int((duration.truncatingRemainder(dividingBy: 1)) * 10)
        return String(format: "%d:%02d.%d", minutes, seconds, milliseconds)
    }

    func cleanup() {
        if let url = recordingURL {
            try? fileManager.removeItem(at: url)
        }
        recordingURL = nil
        recordingDuration = 0
    }
}

// MARK: - AVAudioRecorderDelegate

extension AudioRecorderService: AVAudioRecorderDelegate {
    func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        if !flag {
            print("[AudioRecorder] Recording finished unsuccessfully")
            cleanup()
        }
    }

    func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) {
        print("[AudioRecorder] Encoding error: \(error?.localizedDescription ?? "unknown")")
        cleanup()
    }
}
