//
//  SpamMessage.swift
//  SyncFlowMac
//
//  Model for spam messages synced from Android.
//

import Foundation

struct SpamMessage: Identifiable, Hashable {
    let id: String
    let address: String
    let body: String
    let date: Double
    let contactName: String?
    let spamConfidence: Double
    let spamReasons: String?
    let detectedAt: Double
    let isUserMarked: Bool
    let isRead: Bool

    var timestamp: Date {
        return Date(timeIntervalSince1970: date / 1000.0)
    }
}
