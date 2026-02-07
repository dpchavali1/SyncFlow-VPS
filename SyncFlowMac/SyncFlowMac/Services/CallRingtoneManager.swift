//
//  CallRingtoneManager.swift
//  SyncFlowMac
//
//  Manages playing ringtone sounds for incoming calls
//

import Foundation
import AVFoundation
import AppKit
import Combine

class CallRingtoneManager: ObservableObject {

    static let shared = CallRingtoneManager()

    @Published var isPlaying = false

    private var audioPlayer: AVAudioPlayer?
    private var vibrationTimer: Timer?

    private init() {
        print("CallRingtoneManager: Initialized")
    }

    // MARK: - Public Methods

    /// Start playing the ringtone for an incoming call
    func startRinging() {
        guard !isPlaying else {
            print("CallRingtoneManager: Already ringing")
            return
        }

        print("CallRingtoneManager: Starting ringtone")

        // Try to play custom ringtone first, fallback to system sound
        if !playCustomRingtone() {
            playSystemRingtone()
        }

        isPlaying = true
    }

    /// Stop the ringtone
    func stopRinging() {
        guard isPlaying else { return }

        print("CallRingtoneManager: Stopping ringtone")

        audioPlayer?.stop()
        audioPlayer = nil

        vibrationTimer?.invalidate()
        vibrationTimer = nil

        isPlaying = false
    }

    // MARK: - Private Methods

    private func playCustomRingtone() -> Bool {
        // Try to load a custom ringtone from the app bundle
        guard let ringtoneURL = Bundle.main.url(forResource: "ringtone", withExtension: "mp3") ??
                                Bundle.main.url(forResource: "ringtone", withExtension: "m4a") ??
                                Bundle.main.url(forResource: "ringtone", withExtension: "wav") else {
            print("CallRingtoneManager: No custom ringtone found in bundle")
            return false
        }

        do {
            audioPlayer = try AVAudioPlayer(contentsOf: ringtoneURL)
            audioPlayer?.numberOfLoops = -1 // Loop indefinitely
            audioPlayer?.volume = 0.8
            audioPlayer?.prepareToPlay()
            audioPlayer?.play()
            print("CallRingtoneManager: Playing custom ringtone")
            return true
        } catch {
            print("CallRingtoneManager: Error playing custom ringtone: \(error)")
            return false
        }
    }

    private func playSystemRingtone() {
        print("CallRingtoneManager: Playing system ringtone")

        // Use system sound as fallback - play repeatedly
        playSystemSoundOnce()

        // Set up a timer to repeat the system sound
        vibrationTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.playSystemSoundOnce()
        }
    }

    private func playSystemSoundOnce() {
        // Play the system "Glass" or "Ping" sound
        // You can also use NSSound for more options
        if let sound = NSSound(named: "Glass") {
            sound.play()
        } else if let sound = NSSound(named: "Ping") {
            sound.play()
        } else {
            // Fallback to system beep
            NSSound.beep()
        }
    }

    deinit {
        stopRinging()
    }
}
