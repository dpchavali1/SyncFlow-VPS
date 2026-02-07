//
//  MediaControlService.swift
//  SyncFlowMac
//
//  Service to control media playback on Android phone from Mac
//

import Foundation
import Combine
// FirebaseDatabase - using FirebaseStubs.swift

class MediaControlService: ObservableObject {
    static let shared = MediaControlService()

    @Published var isPlaying: Bool = false
    @Published var trackTitle: String?
    @Published var trackArtist: String?
    @Published var trackAlbum: String?
    @Published var trackAppName: String?
    @Published var trackPackageName: String?
    @Published var volume: Int = 0
    @Published var maxVolume: Int = 15
    @Published var hasPhonePermission: Bool = false
    @Published var lastUpdated: Date?

    private let database = Database.database()
    private var statusHandle: DatabaseHandle?
    private var currentUserId: String?

    private init() {}

    /// Start listening for media status from phone
    func startListening(userId: String) {
        if statusHandle != nil {
            stopListening()
        }

        currentUserId = userId

        let statusRef = database.reference()
            .child("users")
            .child(userId)
            .child("media_status")

        statusHandle = statusRef.observe(.value) { [weak self] snapshot in
            guard let self = self else { return }

            guard let data = snapshot.value as? [String: Any] else {
                DispatchQueue.main.async {
                    self.isPlaying = false
                    self.trackTitle = nil
                    self.trackArtist = nil
                    self.trackAlbum = nil
                    self.trackAppName = nil
                    self.trackPackageName = nil
                }
                return
            }

            DispatchQueue.main.async {
                self.isPlaying = data["isPlaying"] as? Bool ?? false
                self.trackTitle = data["title"] as? String
                self.trackArtist = data["artist"] as? String
                self.trackAlbum = data["album"] as? String
                self.trackAppName = data["appName"] as? String
                self.trackPackageName = data["packageName"] as? String
                self.volume = data["volume"] as? Int ?? 0
                self.maxVolume = data["maxVolume"] as? Int ?? 15
                self.hasPhonePermission = data["hasPermission"] as? Bool ?? false

                if let timestamp = data["timestamp"] as? Double {
                    self.lastUpdated = Date(timeIntervalSince1970: timestamp / 1000)
                }
            }
        }
    }

    /// Stop listening
    func stopListening() {
        guard let userId = currentUserId, let handle = statusHandle else { return }

        database.reference()
            .child("users")
            .child(userId)
            .child("media_status")
            .removeObserver(withHandle: handle)

        statusHandle = nil
        currentUserId = nil
    }

    // MARK: - Playback Controls

    /// Play media
    func play() {
        sendCommand("play")
    }

    /// Pause media
    func pause() {
        sendCommand("pause")
    }

    /// Toggle play/pause
    func playPause() {
        sendCommand("play_pause")
    }

    /// Skip to next track
    func next() {
        sendCommand("next")
    }

    /// Skip to previous track
    func previous() {
        sendCommand("previous")
    }

    /// Stop playback
    func stop() {
        sendCommand("stop")
    }

    // MARK: - Volume Controls

    /// Increase volume
    func volumeUp() {
        sendCommand("volume_up")
    }

    /// Decrease volume
    func volumeDown() {
        sendCommand("volume_down")
    }

    /// Toggle mute
    func toggleMute() {
        sendCommand("volume_mute")
    }

    /// Set specific volume level
    func setVolume(_ level: Int) {
        sendCommand("set_volume", extraData: ["volume": level])
    }

    // MARK: - Computed Properties

    /// Volume as percentage (0-100)
    var volumePercentage: Double {
        guard maxVolume > 0 else { return 0 }
        return Double(volume) / Double(maxVolume) * 100
    }

    /// Track info display string
    var trackInfo: String? {
        if let title = trackTitle {
            if let artist = trackArtist {
                return "\(title) - \(artist)"
            }
            return title
        }
        return nil
    }

    /// Whether media is currently available
    var hasActiveMedia: Bool {
        return trackTitle != nil || trackAppName != nil || isPlaying
    }

    // MARK: - Private

    /// Send command to phone
    private func sendCommand(_ action: String, extraData: [String: Any]? = nil) {
        guard let userId = currentUserId else {
            return
        }

        // Ensure Firebase is online before writing
        database.goOnline()

        let commandRef = database.reference()
            .child("users")
            .child(userId)
            .child("media_command")

        var commandData: [String: Any] = [
            "action": action,
            "timestamp": ServerValue.timestamp()
        ]

        if let extra = extraData {
            for (key, value) in extra {
                commandData[key] = value
            }
        }

        commandRef.setValue(commandData) { error, _ in
            if let error = error {
                print("MediaControlService: Error sending command: \(error)")
            } else {
            }
        }
    }

    /// Reduce update frequency for battery saving
    func reduceUpdates() {
        // Reduce the frequency of media control updates
    }


}
