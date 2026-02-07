//
//  Deal.swift
//  SyncFlowMac
//
//  Data model for SyncFlow Deals
//

import Foundation

struct Deal: Identifiable, Codable, Equatable {
    let id: String
    let title: String
    let price: String
    let image: String
    let url: String
    let category: String
    let timestamp: Int
    let score: Int
    let discount: Int
    let rating: String?
    let reviews: String?

    var imageURL: URL? {
        URL(string: image)
    }

    var dealURL: URL? {
        // Clean up URL encoding issues
        let cleanedUrl = url
            .replacingOccurrences(of: "&amp%3B", with: "&")
            .replacingOccurrences(of: "&amp;", with: "&")
        return URL(string: cleanedUrl)
    }

    var formattedDate: String {
        let date = Date(timeIntervalSince1970: TimeInterval(timestamp))
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    var hasDiscount: Bool {
        discount > 0
    }

    var discountText: String {
        "\(discount)% OFF"
    }
}

struct DealsResponse: Codable {
    let deals: [Deal]
}

// MARK: - Deal Categories

enum DealCategory: String, CaseIterable {
    case all = "All"
    case tech = "Tech"
    case gaming = "Gaming"
    case home = "Home"
    case fitness = "Fitness"
    case accessories = "Accessories"
    case gifts = "Gifts"
    case baby = "Baby"
    case beauty = "Beauty"
    case pets = "Pets"
    case general = "General"

    var icon: String {
        switch self {
        case .all: return "square.grid.2x2"
        case .tech: return "desktopcomputer"
        case .gaming: return "gamecontroller"
        case .home: return "house"
        case .fitness: return "figure.run"
        case .accessories: return "applewatch"
        case .gifts: return "gift"
        case .baby: return "figure.and.child.holdinghands"
        case .beauty: return "sparkles"
        case .pets: return "pawprint"
        case .general: return "tag"
        }
    }

    static func from(_ string: String) -> DealCategory {
        DealCategory(rawValue: string) ?? .general
    }
}
