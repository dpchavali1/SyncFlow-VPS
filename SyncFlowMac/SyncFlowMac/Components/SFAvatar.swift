//
//  SFAvatar.swift
//  SyncFlowMac
//
//  Reusable avatar component for SyncFlow macOS app
//

import SwiftUI

// MARK: - Avatar Size

enum SFAvatarSize {
    case small      // 28pt
    case medium     // 36pt
    case large      // 44pt
    case extraLarge // 56pt
    case huge       // 72pt

    var size: CGFloat {
        switch self {
        case .small: return 28
        case .medium: return 36
        case .large: return 44
        case .extraLarge: return 56
        case .huge: return 72
        }
    }

    var fontSize: CGFloat {
        switch self {
        case .small: return 11
        case .medium: return 14
        case .large: return 17
        case .extraLarge: return 22
        case .huge: return 28
        }
    }

    var badgeSize: CGFloat {
        switch self {
        case .small: return 8
        case .medium: return 10
        case .large: return 12
        case .extraLarge: return 14
        case .huge: return 16
        }
    }
}

// MARK: - Online Status

enum SFOnlineStatus {
    case online
    case away
    case busy
    case offline
    case none

    var color: Color {
        switch self {
        case .online: return SyncFlowColors.online
        case .away: return SyncFlowColors.away
        case .busy: return SyncFlowColors.busy
        case .offline: return SyncFlowColors.offline
        case .none: return .clear
        }
    }
}

// MARK: - SFAvatar

struct SFAvatar: View {
    let name: String
    var imageURL: URL? = nil
    var size: SFAvatarSize = .medium
    var status: SFOnlineStatus = .none
    var backgroundColor: Color = SyncFlowColors.primaryContainer
    var textColor: Color = SyncFlowColors.primary

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            // Main avatar
            if let imageURL = imageURL {
                AsyncImage(url: imageURL) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                    case .failure:
                        initialsView
                    case .empty:
                        initialsView
                            .overlay(
                                ProgressView()
                                    .scaleEffect(0.5)
                            )
                    @unknown default:
                        initialsView
                    }
                }
                .frame(width: size.size, height: size.size)
                .clipShape(Circle())
            } else {
                initialsView
            }

            // Status indicator
            if status != .none {
                Circle()
                    .fill(Color(nsColor: .windowBackgroundColor))
                    .frame(width: size.badgeSize + 4, height: size.badgeSize + 4)
                    .overlay(
                        Circle()
                            .fill(status.color)
                            .frame(width: size.badgeSize, height: size.badgeSize)
                    )
                    .offset(x: 2, y: 2)
            }
        }
    }

    private var initialsView: some View {
        Circle()
            .fill(backgroundColor)
            .frame(width: size.size, height: size.size)
            .overlay(
                Text(initials)
                    .font(.system(size: size.fontSize, weight: .semibold))
                    .foregroundColor(textColor)
            )
    }

    private var initials: String {
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanName.isEmpty else { return "?" }

        let words = cleanName.split(separator: " ")
        if words.count >= 2 {
            return String(words[0].prefix(1) + words[1].prefix(1)).uppercased()
        } else if let firstWord = words.first {
            return String(firstWord.prefix(2)).uppercased()
        }
        return "?"
    }
}

// MARK: - Group Avatar

struct SFGroupAvatar: View {
    let names: [String]
    var size: SFAvatarSize = .medium
    var maxVisible: Int = 3

    var body: some View {
        let displayNames = Array(names.prefix(maxVisible))
        let overflow = names.count - maxVisible

        ZStack {
            if displayNames.isEmpty {
                SFAvatar(name: "Group", size: size)
            } else if displayNames.count == 1 {
                SFAvatar(name: displayNames[0], size: size)
            } else {
                // Stacked avatars
                ForEach(Array(displayNames.enumerated()), id: \.offset) { index, name in
                    SFAvatar(
                        name: name,
                        size: smallerSize
                    )
                    .offset(x: CGFloat(index * 12))
                }

                // Overflow indicator
                if overflow > 0 {
                    Circle()
                        .fill(SyncFlowColors.primary)
                        .frame(width: 20, height: 20)
                        .overlay(
                            Text("+\(overflow)")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(.white)
                        )
                        .offset(x: CGFloat(displayNames.count * 12 + 4))
                }
            }
        }
        .frame(width: size.size, height: size.size)
    }

    private var smallerSize: SFAvatarSize {
        switch size {
        case .small: return .small
        case .medium: return .small
        case .large: return .medium
        case .extraLarge: return .large
        case .huge: return .extraLarge
        }
    }
}

// MARK: - Avatar with Badge

struct SFAvatarWithBadge: View {
    let name: String
    var imageURL: URL? = nil
    var size: SFAvatarSize = .medium
    var badgeCount: Int = 0
    var status: SFOnlineStatus = .none

    var body: some View {
        ZStack(alignment: .topTrailing) {
            SFAvatar(
                name: name,
                imageURL: imageURL,
                size: size,
                status: status
            )

            if badgeCount > 0 {
                SFBadge(count: badgeCount)
                    .offset(x: 4, y: -4)
            }
        }
    }
}

// MARK: - Preview

#if DEBUG
struct SFAvatar_Previews: PreviewProvider {
    static var previews: some View {
        VStack(spacing: 20) {
            HStack(spacing: 20) {
                SFAvatar(name: "John Doe", size: .small)
                SFAvatar(name: "Jane Smith", size: .medium)
                SFAvatar(name: "Bob Wilson", size: .large)
                SFAvatar(name: "Alice Brown", size: .extraLarge)
            }

            HStack(spacing: 20) {
                SFAvatar(name: "Online User", size: .large, status: .online)
                SFAvatar(name: "Away User", size: .large, status: .away)
                SFAvatar(name: "Busy User", size: .large, status: .busy)
                SFAvatar(name: "Offline User", size: .large, status: .offline)
            }

            SFGroupAvatar(names: ["Alice", "Bob", "Charlie", "David", "Eve"], size: .large)
        }
        .padding()
    }
}
#endif
