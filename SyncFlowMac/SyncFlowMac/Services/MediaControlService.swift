//
//  MediaControlService.swift
//  SyncFlowMac
//
//  Service to control media playback on Android phone from Mac
//

import Foundation
import Combine

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

    private var currentUserId: String?
    private var cancellables = Set<AnyCancellable>()

    private init() {}

    /// Start listening for media status from phone
    func startListening(userId: String) {
        currentUserId = userId

        // Fetch current media status
        Task {
            do {
                let status = try await VPSService.shared.getMediaStatus()
                await MainActor.run { self.applyMediaStatus(status) }
            } catch {
                print("[MediaControl] Error fetching initial status: \(error)")
            }
        }

        // Listen for real-time WebSocket updates
        VPSService.shared.mediaStatusUpdated
            .receive(on: DispatchQueue.main)
            .sink { [weak self] data in
                self?.applyMediaStatus(data)
            }
            .store(in: &cancellables)
    }

    /// Stop listening
    func stopListening() {
        currentUserId = nil
        cancellables.removeAll()
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

    private func applyMediaStatus(_ data: [String: Any]) {
        if let playing = data["isPlaying"] as? Bool { isPlaying = playing }
        if let title = data["trackTitle"] as? String { trackTitle = title }
        if let artist = data["trackArtist"] as? String { trackArtist = artist }
        if let album = data["trackAlbum"] as? String { trackAlbum = album }
        if let appName = data["appName"] as? String { trackAppName = appName }
        if let pkg = data["packageName"] as? String { trackPackageName = pkg }
        if let vol = data["volume"] as? Int { volume = vol }
        if let maxVol = data["maxVolume"] as? Int { maxVolume = maxVol }
        if let perm = data["hasPermission"] as? Bool { hasPhonePermission = perm }
        lastUpdated = Date()
    }

    /// Send command to phone
    private func sendCommand(_ action: String, extraData: [String: Any]? = nil) {
        Task {
            do {
                let vol = extraData?["volume"] as? Int
                try await VPSService.shared.sendMediaCommand(action: action, volume: vol)
            } catch {
                print("[MediaControl] Error sending command \(action): \(error)")
            }
        }
    }

    /// Reduce update frequency for battery saving
    func reduceUpdates() {
        // Reduce the frequency of media control updates
    }
}
