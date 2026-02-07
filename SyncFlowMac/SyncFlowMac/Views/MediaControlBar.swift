//
//  MediaControlBar.swift
//  SyncFlowMac
//
//  Compact now-playing bar for phone media controls.
//

import SwiftUI

struct MediaControlBar: View {
    @ObservedObject var mediaService: MediaControlService

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "music.note")
                .foregroundColor(.accentColor)

            VStack(alignment: .leading, spacing: 2) {
                Text(primaryTitle)
                    .font(.subheadline)
                    .lineLimit(1)

                Text(trackSubtitle)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            HStack(spacing: 6) {
                Button(action: { mediaService.previous() }) {
                    Image(systemName: "backward.fill")
                }
                .buttonStyle(.borderless)
                .help("Previous")

                Button(action: { mediaService.playPause() }) {
                    Image(systemName: mediaService.isPlaying ? "pause.fill" : "play.fill")
                }
                .buttonStyle(.borderless)
                .help(mediaService.isPlaying ? "Pause" : "Play")

                Button(action: { mediaService.next() }) {
                    Image(systemName: "forward.fill")
                }
                .buttonStyle(.borderless)
                .help("Next")
            }

            VolumeSlider(
                current: mediaService.volume,
                maxVolume: mediaService.maxVolume,
                onChange: { mediaService.setVolume($0) }
            )
            .frame(width: 120)
        }
        .padding(12)
        .background(Color(nsColor: .controlBackgroundColor))
        .cornerRadius(8)
    }

    private var trackSubtitle: String {
        let appName = appDisplayName
        let artist = mediaService.trackArtist?.trimmingCharacters(in: .whitespacesAndNewlines)
        let album = mediaService.trackAlbum?.trimmingCharacters(in: .whitespacesAndNewlines)
        var parts: [String] = []

        if let appName = appName, !appName.isEmpty {
            parts.append(appName)
        }

        if let artist = artist, !artist.isEmpty {
            if let album = album, !album.isEmpty {
                parts.append("\(artist) • \(album)")
                return parts.joined(separator: " • ")
            }
            parts.append(artist)
            return parts.joined(separator: " • ")
        }

        if let album = album, !album.isEmpty {
            parts.append(album)
            return parts.joined(separator: " • ")
        }

        if !parts.isEmpty {
            return parts.joined(separator: " • ")
        }

        if mediaService.isPlaying {
            if !mediaService.hasPhonePermission {
                return "Settings > Notification Access > Enable SyncFlow"
            }
            return "Playing on phone"
        }
        return "Press Play to start on phone"
    }

    private var primaryTitle: String {
        if let title = mediaService.trackTitle?.trimmingCharacters(in: .whitespacesAndNewlines),
           !title.isEmpty {
            return title
        }
        if let appName = appDisplayName, !appName.isEmpty {
            return appName
        }
        // Show different messages based on state
        if mediaService.isPlaying {
            if !mediaService.hasPhonePermission {
                return "Grant Notification Access on Android"
            }
            return "Playing on phone"
        }
        return "No media playing"
    }

    private var appDisplayName: String? {
        let appName = mediaService.trackAppName?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let appName = appName, !appName.isEmpty {
            return appName
        }
        let packageName = mediaService.trackPackageName?.trimmingCharacters(in: .whitespacesAndNewlines)
        return packageName?.isEmpty == false ? packageName : nil
    }
}

private struct VolumeSlider: View {
    let current: Int
    let maxVolume: Int
    let onChange: (Int) -> Void

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "speaker.wave.1.fill")
                .foregroundColor(.secondary)

            Slider(
                value: Binding(
                    get: { Double(current) },
                    set: { onChange(Int($0)) }
                ),
                in: 0...Double(maxValue)
            )

            Image(systemName: "speaker.wave.3.fill")
                .foregroundColor(.secondary)
        }
        .help("Phone volume")
    }

    private var maxValue: Int {
        return Swift.max(1, maxVolume)
    }
}
