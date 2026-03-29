//
//  DealNotificationService.swift
//  SyncFlowMac
//
//  Handles scheduled deal notifications on macOS.
//  Uses UNCalendarNotificationTrigger for reliable delivery even when
//  the app is in background or subject to App Nap.
//

import Foundation
import UserNotifications
import AppKit

// MARK: - Deal Notification Service

class DealNotificationService {
    static let shared = DealNotificationService()

    private let priceHistory = PriceHistoryStore()
    private let affiliateTag = "syncflow-20"
    private let notificationPrefix = "deal_scheduled_"

    private init() {}

    // MARK: - Scheduling

    /// Call once at app startup to schedule deal notifications for today.
    /// Uses UNCalendarNotificationTrigger so notifications fire reliably
    /// even when the app is napped or in the background.
    /// Only schedules if user has opted in via the "deal_notifications_enabled" preference.
    func scheduleDailyNotifications() {
        // Only schedule if user has opted in
        guard UserDefaults.standard.bool(forKey: "deal_notifications_enabled") else {
            cancelAll()
            return
        }

        // Remove previously scheduled deal notifications
        cancelAll()

        // Fetch deals first, then schedule with content
        Task {
            await DealsService.shared.fetchDeals()
            let deals = DealsService.shared.deals

            // Check for price drops
            let priceDrop = priceHistory.detectPriceDrop(deals: deals)
            priceHistory.updatePrices(deals: deals)

            let times = notificationTimesForToday()

            #if DEBUG
            let formatter = DateFormatter()
            formatter.dateFormat = "h:mm a"
            print("[DealNotify] Scheduling \(times.count) notifications for today")
            #endif

            for (index, time) in times.enumerated() {
                // First notification gets price drop if available
                if index == 0, let drop = priceDrop {
                    scheduleSystemNotification(
                        at: time,
                        title: "Price Dropped!",
                        body: "\(drop.deal.title) \u{2014} was \(drop.oldPrice), now \(drop.deal.price)",
                        dealURL: buildAffiliateURL(drop.deal.url),
                        dealTitle: drop.deal.title,
                        identifier: "\(notificationPrefix)\(index)"
                    )
                } else if let deal = deals.randomElement() {
                    scheduleSystemNotification(
                        at: time,
                        title: "New Deal!",
                        body: "\(deal.title) \u{2014} \(deal.price)",
                        dealURL: buildAffiliateURL(deal.url),
                        dealTitle: deal.title,
                        identifier: "\(notificationPrefix)\(index)"
                    )
                }

                #if DEBUG
                print("[DealNotify] Scheduled notification at \(formatter.string(from: time))")
                #endif
            }

            // Schedule a reschedule trigger at midnight for the next day
            scheduleMidnightReschedule()
        }
    }

    /// Cancel all pending deal notifications
    func cancelAll() {
        let center = UNUserNotificationCenter.current()
        center.getPendingNotificationRequests { requests in
            let dealIds = requests
                .filter { $0.identifier.hasPrefix(self.notificationPrefix) || $0.identifier == "deal_midnight_reschedule" }
                .map { $0.identifier }
            center.removePendingNotificationRequests(withIdentifiers: dealIds)
        }
    }

    // MARK: - Notification Times

    private func notificationTimesForToday() -> [Date] {
        let now = Date()
        let timezone = TimeZone(identifier: "America/Detroit") ?? .current
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timezone

        var times: [Date] = []
        let weekday = cal.component(.weekday, from: now)
        let isWeekend = weekday == 1 || weekday == 7

        if HolidayCalendar.isHoliday(now) {
            // Holiday: 3 fixed times
            for (hour, minute) in [(10, 30), (14, 0), (18, 0)] {
                if let date = cal.date(bySettingHour: hour, minute: minute, second: 0, of: now),
                   date > now {
                    times.append(date)
                }
            }
        } else if isWeekend {
            // Weekend: 4 fixed times
            for (hour, minute) in [(10, 0), (13, 0), (16, 0), (19, 0)] {
                if let date = cal.date(bySettingHour: hour, minute: minute, second: 0, of: now),
                   date > now {
                    times.append(date)
                }
            }
        } else {
            // Weekday: 4 random times within windows
            let windows = [(9, 11), (12, 14), (15, 17), (18, 20)]
            for (startHour, endHour) in windows {
                let randomHour = Int.random(in: startHour..<endHour)
                let randomMinute = Int.random(in: 0...59)
                if let date = cal.date(bySettingHour: randomHour, minute: randomMinute, second: 0, of: now),
                   date > now {
                    times.append(date)
                }
            }
        }

        return times
    }

    // MARK: - System Notification Scheduling

    private func scheduleSystemNotification(
        at date: Date,
        title: String,
        body: String,
        dealURL: String,
        dealTitle: String,
        identifier: String
    ) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.categoryIdentifier = "DEAL_CATEGORY"
        content.userInfo = [
            "type": "deal",
            "dealURL": dealURL,
            "dealTitle": dealTitle
        ]

        let timezone = TimeZone(identifier: "America/Detroit") ?? .current
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timezone

        let components = cal.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)

        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)

        UNUserNotificationCenter.current().add(request) { error in
            #if DEBUG
            if let error = error {
                print("[DealNotify] Error scheduling notification: \(error)")
            }
            #endif
        }
    }

    // MARK: - Midnight Reschedule

    private func scheduleMidnightReschedule() {
        // Schedule a silent local notification at 12:01 AM to trigger rescheduling
        // When the app receives it via the delegate, it will call scheduleDailyNotifications()
        let timezone = TimeZone(identifier: "America/Detroit") ?? .current
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timezone

        guard let tomorrow = cal.date(byAdding: .day, value: 1, to: Date()),
              let midnight = cal.date(bySettingHour: 0, minute: 1, second: 0, of: tomorrow) else {
            return
        }

        let content = UNMutableNotificationContent()
        content.userInfo = ["type": "deal_reschedule"]
        // No sound, no banner — silent trigger

        let components = cal.dateComponents([.year, .month, .day, .hour, .minute], from: midnight)
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)

        let request = UNNotificationRequest(
            identifier: "deal_midnight_reschedule",
            content: content,
            trigger: trigger
        )

        UNUserNotificationCenter.current().add(request) { error in
            #if DEBUG
            if let error = error {
                print("[DealNotify] Error scheduling midnight reschedule: \(error)")
            } else {
                print("[DealNotify] Scheduled midnight reschedule")
            }
            #endif
        }
    }

    // MARK: - Affiliate URL

    private func buildAffiliateURL(_ urlString: String) -> String {
        let cleaned = urlString
            .replacingOccurrences(of: "&amp%3B", with: "&")
            .replacingOccurrences(of: "&amp;", with: "&")

        if cleaned.contains("amazon.com") {
            if cleaned.contains("tag=") {
                return cleaned
            }
            let separator = cleaned.contains("?") ? "&" : "?"
            return "\(cleaned)\(separator)tag=\(affiliateTag)"
        }
        return cleaned
    }
}

// MARK: - Price History Store

class PriceHistoryStore {
    private let defaults = UserDefaults.standard
    private let key = "deal_price_history"
    private let minimumDrop: Double = 5.0  // $5 minimum drop to trigger notification

    struct PriceDrop {
        let deal: Deal
        let oldPrice: String
    }

    func detectPriceDrop(deals: [Deal]) -> PriceDrop? {
        let history = loadHistory()
        var bestDrop: (deal: Deal, oldPrice: String, dropAmount: Double)?

        for deal in deals {
            guard let currentPrice = parsePrice(deal.price),
                  let oldPriceValue = history[deal.id],
                  let oldPriceParsed = parsePrice(oldPriceValue) else {
                continue
            }

            let drop = oldPriceParsed - currentPrice
            if drop >= minimumDrop {
                if bestDrop == nil || drop > bestDrop!.dropAmount {
                    bestDrop = (deal, oldPriceValue, drop)
                }
            }
        }

        if let best = bestDrop {
            return PriceDrop(deal: best.deal, oldPrice: best.oldPrice)
        }
        return nil
    }

    func updatePrices(deals: [Deal]) {
        var history = loadHistory()
        for deal in deals {
            history[deal.id] = deal.price
        }
        saveHistory(history)
    }

    func clearHistory() {
        defaults.removeObject(forKey: key)
    }

    private func loadHistory() -> [String: String] {
        defaults.dictionary(forKey: key) as? [String: String] ?? [:]
    }

    private func saveHistory(_ history: [String: String]) {
        defaults.set(history, forKey: key)
    }

    private func parsePrice(_ priceString: String) -> Double? {
        let cleaned = priceString
            .replacingOccurrences(of: "$", with: "")
            .replacingOccurrences(of: ",", with: "")
            .trimmingCharacters(in: .whitespaces)
        return Double(cleaned)
    }
}

// MARK: - Holiday Calendar

struct HolidayCalendar {
    /// US federal holidays
    static func isHoliday(_ date: Date) -> Bool {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/Detroit") ?? .current

        let month = cal.component(.month, from: date)
        let day = cal.component(.day, from: date)
        let weekday = cal.component(.weekday, from: date)
        let weekOfMonth = cal.component(.weekOfMonth, from: date)

        // New Year's Day - January 1
        if month == 1 && day == 1 { return true }

        // MLK Day - 3rd Monday of January
        if month == 1 && weekday == 2 && weekOfMonth == 3 { return true }

        // Presidents' Day - 3rd Monday of February
        if month == 2 && weekday == 2 && weekOfMonth == 3 { return true }

        // Memorial Day - Last Monday of May
        if month == 5 && weekday == 2 {
            if let lastDay = cal.range(of: .day, in: .month, for: date)?.upperBound {
                if day > (lastDay - 7) { return true }
            }
        }

        // Juneteenth - June 19
        if month == 6 && day == 19 { return true }

        // Independence Day - July 4
        if month == 7 && day == 4 { return true }

        // Labor Day - 1st Monday of September
        if month == 9 && weekday == 2 && weekOfMonth == 1 { return true }

        // Thanksgiving - 4th Thursday of November
        if month == 11 && weekday == 5 && weekOfMonth == 4 { return true }

        // Christmas - December 25
        if month == 12 && day == 25 { return true }

        return false
    }
}
