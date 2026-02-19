//
//  DealNotificationService.swift
//  SyncFlowMac
//
//  Handles scheduled deal notifications on macOS
//  Mirrors the Android DealNotificationScheduler / DealNotificationManager behavior
//

import Foundation
import UserNotifications
import AppKit

// MARK: - Deal Notification Service

class DealNotificationService {
    static let shared = DealNotificationService()

    private let priceHistory = PriceHistoryStore()
    private var scheduledTimers: [Timer] = []
    private var midnightTimer: Timer?
    private let affiliateTag = "syncflow-20"

    private init() {}

    // MARK: - Scheduling

    /// Call once at app startup to schedule deal notifications for today
    func scheduleDailyNotifications() {
        cancelAll()
        scheduleNotificationsForToday()
        scheduleMidnightReschedule()

        #if DEBUG
        print("[DealNotify] Scheduled deal notifications for today")
        #endif
    }

    /// Cancel all pending deal notification timers
    func cancelAll() {
        scheduledTimers.forEach { $0.invalidate() }
        scheduledTimers.removeAll()
        midnightTimer?.invalidate()
        midnightTimer = nil
    }

    private func scheduleNotificationsForToday() {
        let now = Date()
        let calendar = Calendar.current
        let timezone = TimeZone(identifier: "America/Detroit") ?? .current
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timezone

        let windows: [(Int, Int)]  // (startHour, endHour)
        let weekday = cal.component(.weekday, from: now)
        let isWeekend = weekday == 1 || weekday == 7

        if HolidayCalendar.isHoliday(now) {
            // Holiday: 3 notifications at fixed times
            let holidayTimes = [(10, 30), (14, 0), (18, 0)]
            for (hour, minute) in holidayTimes {
                if let fireDate = cal.date(bySettingHour: hour, minute: minute, second: 0, of: now),
                   fireDate > now {
                    scheduleNotification(at: fireDate)
                }
            }
            return
        } else if isWeekend {
            // Weekend: 4 fixed times
            let weekendTimes = [(10, 0), (13, 0), (16, 0), (19, 0)]
            for (hour, minute) in weekendTimes {
                if let fireDate = cal.date(bySettingHour: hour, minute: minute, second: 0, of: now),
                   fireDate > now {
                    scheduleNotification(at: fireDate)
                }
            }
            return
        } else {
            // Weekday: 4 random times within windows
            windows = [(9, 11), (12, 14), (15, 17), (18, 20)]
        }

        for (startHour, endHour) in windows {
            let randomMinute = Int.random(in: 0...59)
            let randomHour = Int.random(in: startHour..<endHour)

            if let fireDate = cal.date(bySettingHour: randomHour, minute: randomMinute, second: 0, of: now),
               fireDate > now {
                scheduleNotification(at: fireDate)
            }
        }
    }

    private func scheduleNotification(at date: Date) {
        let interval = date.timeIntervalSinceNow
        guard interval > 0 else { return }

        let timer = Timer.scheduledTimer(withTimeInterval: interval, repeats: false) { [weak self] _ in
            self?.sendDealNotification()
        }
        RunLoop.main.add(timer, forMode: .common)
        scheduledTimers.append(timer)

        #if DEBUG
        let formatter = DateFormatter()
        formatter.dateFormat = "h:mm a"
        print("[DealNotify] Scheduled notification at \(formatter.string(from: date))")
        #endif
    }

    private func scheduleMidnightReschedule() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/Detroit") ?? .current

        guard let tomorrow = cal.date(byAdding: .day, value: 1, to: Date()),
              let midnight = cal.date(bySettingHour: 0, minute: 1, second: 0, of: tomorrow) else {
            return
        }

        let interval = midnight.timeIntervalSinceNow
        guard interval > 0 else { return }

        midnightTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: false) { [weak self] _ in
            self?.scheduleDailyNotifications()
        }
        if let timer = midnightTimer {
            RunLoop.main.add(timer, forMode: .common)
        }
    }

    // MARK: - Send Notification

    private func sendDealNotification() {
        Task {
            // Fetch fresh deals
            await DealsService.shared.fetchDeals()
            let deals = DealsService.shared.deals

            guard !deals.isEmpty else {
                #if DEBUG
                print("[DealNotify] No deals available for notification")
                #endif
                return
            }

            // Check for price drops first (priority)
            if let priceDrop = priceHistory.detectPriceDrop(deals: deals) {
                showPriceDropNotification(deal: priceDrop.deal, oldPrice: priceDrop.oldPrice)
                priceHistory.updatePrices(deals: deals)
                return
            }

            // Update price history
            priceHistory.updatePrices(deals: deals)

            // Pick a random deal
            let deal = deals.randomElement()!
            showDealNotification(deal: deal)
        }
    }

    private func showDealNotification(deal: Deal) {
        let content = UNMutableNotificationContent()
        content.title = "New Deal!"
        content.body = "\(deal.title) \u{2014} \(deal.price)"
        content.sound = .default
        content.categoryIdentifier = "DEAL_CATEGORY"

        let dealURL = buildAffiliateURL(deal.url)
        content.userInfo = [
            "type": "deal",
            "dealURL": dealURL,
            "dealTitle": deal.title
        ]

        let request = UNNotificationRequest(
            identifier: "deal_\(deal.id)_\(Int(Date().timeIntervalSince1970))",
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request) { error in
            #if DEBUG
            if let error = error {
                print("[DealNotify] Error showing notification: \(error)")
            } else {
                print("[DealNotify] Showed deal notification: \(deal.title)")
            }
            #endif
        }
    }

    private func showPriceDropNotification(deal: Deal, oldPrice: String) {
        let content = UNMutableNotificationContent()
        content.title = "Price Dropped!"
        content.body = "\(deal.title) \u{2014} was \(oldPrice), now \(deal.price)"
        content.sound = .default
        content.categoryIdentifier = "DEAL_CATEGORY"

        let dealURL = buildAffiliateURL(deal.url)
        content.userInfo = [
            "type": "deal",
            "dealURL": dealURL,
            "dealTitle": deal.title
        ]

        let request = UNNotificationRequest(
            identifier: "pricedrop_\(deal.id)_\(Int(Date().timeIntervalSince1970))",
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request) { error in
            #if DEBUG
            if let error = error {
                print("[DealNotify] Error showing price drop notification: \(error)")
            } else {
                print("[DealNotify] Showed price drop notification: \(deal.title)")
            }
            #endif
        }
    }

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
        let year = cal.component(.year, from: date)

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
