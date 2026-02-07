//
//  NotificationSoundService.swift
//  SyncFlowMac
//
//  Manages custom notification sounds per contact
//

import Foundation
import AVFoundation
import AppKit
import Combine

class NotificationSoundService: ObservableObject {
    static let shared = NotificationSoundService()

    // MARK: - Built-in Sounds

    static let builtInSounds: [NotificationSound] = [
        NotificationSound(id: "default", name: "Default", systemSound: "Tink"),
        NotificationSound(id: "ding", name: "Ding", systemSound: "Glass"),
        NotificationSound(id: "pop", name: "Pop", systemSound: "Pop"),
        NotificationSound(id: "chime", name: "Chime", systemSound: "Blow"),
        NotificationSound(id: "ping", name: "Ping", systemSound: "Ping"),
        NotificationSound(id: "subtle", name: "Subtle", systemSound: "Morse"),
        NotificationSound(id: "alert", name: "Alert", systemSound: "Sosumi"),
        NotificationSound(id: "bubble", name: "Bubble", systemSound: "Bottle"),
        NotificationSound(id: "funk", name: "Funk", systemSound: "Funk"),
        NotificationSound(id: "hero", name: "Hero", systemSound: "Hero"),
        NotificationSound(id: "purr", name: "Purr", systemSound: "Purr"),
        NotificationSound(id: "submarine", name: "Submarine", systemSound: "Submarine")
    ]

    // MARK: - Published State

    @Published var contactSounds: [String: String] = [:] // contactAddress -> soundId
    @Published var defaultSoundId: String = "default"

    // MARK: - Private Properties

    private var audioPlayer: AVAudioPlayer?
    private let fileManager = FileManager.default

    // MARK: - Initialization

    private init() {
        loadSettings()
    }

    // MARK: - Settings Persistence

    private var settingsURL: URL {
        let appSupport = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let settingsDir = appSupport.appendingPathComponent("SyncFlow")

        if !fileManager.fileExists(atPath: settingsDir.path) {
            try? fileManager.createDirectory(at: settingsDir, withIntermediateDirectories: true)
        }

        return settingsDir.appendingPathComponent("notification_sounds.json")
    }

    private func loadSettings() {
        guard let data = try? Data(contentsOf: settingsURL),
              let settings = try? JSONDecoder().decode(NotificationSoundSettings.self, from: data) else {
            return
        }

        contactSounds = settings.contactSounds
        defaultSoundId = settings.defaultSoundId
    }

    private func saveSettings() {
        let settings = NotificationSoundSettings(
            contactSounds: contactSounds,
            defaultSoundId: defaultSoundId
        )

        guard let data = try? JSONEncoder().encode(settings) else { return }
        try? data.write(to: settingsURL)
    }

    // MARK: - Sound Management

    /// Set custom sound for a contact
    func setSound(_ soundId: String, for contactAddress: String) {
        contactSounds[contactAddress] = soundId
        saveSettings()
    }

    /// Remove custom sound for a contact (use default)
    func removeSound(for contactAddress: String) {
        contactSounds.removeValue(forKey: contactAddress)
        saveSettings()
    }

    /// Get sound for a contact
    func getSound(for contactAddress: String) -> NotificationSound {
        let soundId = contactSounds[contactAddress] ?? defaultSoundId
        return Self.builtInSounds.first { $0.id == soundId } ?? Self.builtInSounds[0]
    }

    /// Set default sound
    func setDefaultSound(_ soundId: String) {
        defaultSoundId = soundId
        saveSettings()
    }

    // MARK: - Play Sound

    /// Play notification sound for a contact
    func playSound(for contactAddress: String) {
        let sound = getSound(for: contactAddress)
        playSound(sound)
    }

    /// Play a specific sound
    func playSound(_ sound: NotificationSound) {
        // Try system sound first
        if let soundURL = getSoundURL(for: sound.systemSound) {
            do {
                audioPlayer = try AVAudioPlayer(contentsOf: soundURL)
                audioPlayer?.play()
            } catch {
                print("[NotificationSound] Error playing sound: \(error)")
                // Fallback to NSSound
                NSSound(named: NSSound.Name(sound.systemSound))?.play()
            }
        } else {
            // Fallback to NSSound
            NSSound(named: NSSound.Name(sound.systemSound))?.play()
        }
    }

    /// Preview a sound by ID
    func previewSound(id: String) {
        guard let sound = Self.builtInSounds.first(where: { $0.id == id }) else { return }
        playSound(sound)
    }

    // MARK: - Helpers

    private func getSoundURL(for soundName: String) -> URL? {
        // Check system sounds directory
        let systemSounds = URL(fileURLWithPath: "/System/Library/Sounds")
        let extensions = ["aiff", "wav", "mp3", "caf"]

        for ext in extensions {
            let soundURL = systemSounds.appendingPathComponent("\(soundName).\(ext)")
            if fileManager.fileExists(atPath: soundURL.path) {
                return soundURL
            }
        }

        return nil
    }

    /// Get all available system sounds
    static func getAvailableSystemSounds() -> [String] {
        let systemSounds = URL(fileURLWithPath: "/System/Library/Sounds")
        let extensions = ["aiff", "wav", "mp3", "caf"]

        guard let contents = try? FileManager.default.contentsOfDirectory(at: systemSounds, includingPropertiesForKeys: nil) else {
            return []
        }

        return contents
            .filter { url in extensions.contains(url.pathExtension.lowercased()) }
            .map { $0.deletingPathExtension().lastPathComponent }
            .sorted()
    }
}

// MARK: - Models

struct NotificationSound: Identifiable, Codable, Equatable {
    let id: String
    let name: String
    let systemSound: String
}

struct NotificationSoundSettings: Codable {
    let contactSounds: [String: String]
    let defaultSoundId: String
}

// MARK: - Sound Picker View

import SwiftUI

struct NotificationSoundPicker: View {
    @ObservedObject var soundService: NotificationSoundService
    let contactAddress: String?
    let contactName: String?

    @Environment(\.dismiss) private var dismiss

    @State private var selectedSoundId: String = "default"

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    if let name = contactName {
                        Text("Sound for \(name)")
                            .font(.title3)
                            .fontWeight(.semibold)
                    } else {
                        Text("Default Notification Sound")
                            .font(.title3)
                            .fontWeight(.semibold)
                    }

                    if let address = contactAddress {
                        Text(address)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }

                Spacer()

                Button("Done") {
                    saveSelection()
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()

            Divider()

            // Sound List
            List(NotificationSoundService.builtInSounds, selection: $selectedSoundId) { sound in
                SoundRow(
                    sound: sound,
                    isSelected: selectedSoundId == sound.id,
                    onSelect: {
                        selectedSoundId = sound.id
                        soundService.previewSound(id: sound.id)
                    }
                )
            }
        }
        .frame(width: 350, height: 450)
        .onAppear {
            if let address = contactAddress {
                selectedSoundId = soundService.contactSounds[address] ?? soundService.defaultSoundId
            } else {
                selectedSoundId = soundService.defaultSoundId
            }
        }
    }

    private func saveSelection() {
        if let address = contactAddress {
            if selectedSoundId == soundService.defaultSoundId {
                soundService.removeSound(for: address)
            } else {
                soundService.setSound(selectedSoundId, for: address)
            }
        } else {
            soundService.setDefaultSound(selectedSoundId)
        }
    }
}

struct SoundRow: View {
    let sound: NotificationSound
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack {
                Image(systemName: "speaker.wave.2.fill")
                    .foregroundColor(isSelected ? .accentColor : .secondary)

                Text(sound.name)
                    .foregroundColor(.primary)

                Spacer()

                if isSelected {
                    Image(systemName: "checkmark")
                        .foregroundColor(.accentColor)
                        .fontWeight(.semibold)
                }
            }
            .padding(.vertical, 8)
            .padding(.horizontal, 4)
            .background(isSelected ? Color.accentColor.opacity(0.1) : Color.clear)
            .cornerRadius(8)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Contact Sound Badge

struct ContactSoundBadge: View {
    let contactAddress: String
    @ObservedObject var soundService: NotificationSoundService

    @State private var showPicker = false

    var body: some View {
        Button(action: { showPicker = true }) {
            HStack(spacing: 4) {
                Image(systemName: "speaker.wave.2")
                    .font(.caption)

                if let customSound = soundService.contactSounds[contactAddress],
                   customSound != soundService.defaultSoundId {
                    Text(soundService.getSound(for: contactAddress).name)
                        .font(.caption)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Color.secondary.opacity(0.2))
            .cornerRadius(12)
        }
        .buttonStyle(.plain)
        .help("Custom notification sound")
        .sheet(isPresented: $showPicker) {
            NotificationSoundPicker(
                soundService: soundService,
                contactAddress: contactAddress,
                contactName: nil
            )
        }
    }
}
