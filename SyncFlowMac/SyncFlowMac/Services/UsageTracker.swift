//
//  UsageTracker.swift
//  SyncFlowMac
//
//  Tracks per-user upload usage for MMS and file sharing quotas.
//  Uses local-only checks.
//

import Foundation
import Combine

enum UsageCategory {
    case mms
    case file
}

struct UsageDecision {
    let allowed: Bool
    let reason: String?
}

struct UsageStats {
    let monthlyUploadBytes: Int64
    let monthlyLimitBytes: Int64
    let storageBytes: Int64
    let storageLimitBytes: Int64
    let isPaid: Bool
    let isTrialExpired: Bool
    let trialDaysRemaining: Int

    var monthlyUsagePercent: Double {
        guard monthlyLimitBytes > 0 else { return 0 }
        return Double(monthlyUploadBytes) / Double(monthlyLimitBytes) * 100
    }

    var storageUsagePercent: Double {
        guard storageLimitBytes > 0 else { return 0 }
        return Double(storageBytes) / Double(storageLimitBytes) * 100
    }

    var isMonthlyLimitExceeded: Bool {
        monthlyUploadBytes >= monthlyLimitBytes
    }

    var isMonthlyLimitNear: Bool {
        monthlyUsagePercent >= 80 && !isMonthlyLimitExceeded
    }

    var isStorageLimitExceeded: Bool {
        storageBytes >= storageLimitBytes
    }

    var formattedMonthlyUsage: String {
        "\(formatBytes(monthlyUploadBytes)) / \(formatBytes(monthlyLimitBytes))"
    }

    var formattedStorageUsage: String {
        "\(formatBytes(storageBytes)) / \(formatBytes(storageLimitBytes))"
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let gb = Double(bytes) / (1024 * 1024 * 1024)
        let mb = Double(bytes) / (1024 * 1024)
        let kb = Double(bytes) / 1024

        if gb >= 1 {
            return String(format: "%.1f GB", gb)
        } else if mb >= 1 {
            return String(format: "%.1f MB", mb)
        } else if kb >= 1 {
            return String(format: "%.1f KB", kb)
        } else {
            return "\(bytes) B"
        }
    }
}

class UsageTracker: ObservableObject {
    static let shared = UsageTracker()

    private let trialDuration: TimeInterval = 7 * 24 * 60 * 60 // 7 days trial
    private let trialDays = 7

    // Trial/Free tier: 500MB upload/month, 100MB storage
    private let trialMonthlyBytes: Int64 = 500 * 1024 * 1024
    private let trialStorageBytes: Int64 = 100 * 1024 * 1024

    // Paid tier: 10GB upload/month, 2GB storage
    private let paidMonthlyBytes: Int64 = 10 * 1024 * 1024 * 1024
    private let paidStorageBytes: Int64 = 2 * 1024 * 1024 * 1024

    @Published var usageStats: UsageStats?
    @Published var showLimitWarning: Bool = false

    private init() {}

    /// Check if upload is allowed based on current usage from VPS server.
    func isUploadAllowed(
        userId: String,
        bytes: Int64,
        countsTowardStorage: Bool
    ) async -> UsageDecision {
        do {
            let response = try await VPSService.shared.getUsage()
            let usage = response.usage
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let isPaid = isPaidPlan(plan: usage.plan, planExpiresAt: usage.planExpiresAt, nowMs: nowMs)
            let monthlyLimit = isPaid ? paidMonthlyBytes : trialMonthlyBytes
            let storageLimit = isPaid ? paidStorageBytes : trialStorageBytes
            let currentMonthly = usage.monthlyUploadBytes ?? 0
            let currentStorage = usage.storageBytes ?? 0

            if currentMonthly + bytes > monthlyLimit {
                return UsageDecision(allowed: false, reason: "Monthly upload limit exceeded")
            }
            if countsTowardStorage && currentStorage + bytes > storageLimit {
                return UsageDecision(allowed: false, reason: "Storage limit exceeded")
            }
            return UsageDecision(allowed: true, reason: nil)
        } catch {
            // On error, allow the upload (don't block on transient failures)
            return UsageDecision(allowed: true, reason: nil)
        }
    }

    /// Record upload usage to VPS server.
    func recordUpload(
        userId: String,
        bytes: Int64,
        category: UsageCategory,
        countsTowardStorage: Bool
    ) async {
        do {
            let categoryStr = category == .mms ? "mms" : "file"
            try await VPSService.shared.recordUsage(
                bytes: Int(bytes),
                category: categoryStr,
                countsTowardStorage: countsTowardStorage
            )
        } catch {
            print("[UsageTracker] Failed to record upload: \(error.localizedDescription)")
        }
    }

    private func currentPeriodKey() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMM"
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter.string(from: Date())
    }

    private func isPaidPlan(plan: String?, planExpiresAt: Int64?, nowMs: Int64) -> Bool {
        guard let normalized = plan else { return false }
        if normalized == "lifetime" || normalized == "3year" {
            return true
        }
        if normalized == "monthly" || normalized == "yearly" || normalized == "paid" {
            return planExpiresAt.map { $0 > nowMs } ?? true
        }
        return false
    }

    /// Fetches usage statistics from the VPS server.
    func fetchUsageStats(userId: String) async {
        do {
            let response = try await VPSService.shared.getUsage()
            let usage = response.usage
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let isPaid = isPaidPlan(plan: usage.plan, planExpiresAt: usage.planExpiresAt, nowMs: nowMs)

            let trialStartMs = usage.trialStartedAt ?? nowMs
            let trialElapsedMs = nowMs - trialStartMs
            let trialElapsedDays = Int(trialElapsedMs / (24 * 60 * 60 * 1000))
            let trialDaysRemaining = max(0, trialDays - trialElapsedDays)
            let isTrialExpired = !isPaid && trialElapsedDays > trialDays

            let monthlyLimit = isPaid ? paidMonthlyBytes : trialMonthlyBytes
            let storageLimit = isPaid ? paidStorageBytes : trialStorageBytes

            let stats = UsageStats(
                monthlyUploadBytes: usage.monthlyUploadBytes ?? 0,
                monthlyLimitBytes: monthlyLimit,
                storageBytes: usage.storageBytes ?? 0,
                storageLimitBytes: storageLimit,
                isPaid: isPaid,
                isTrialExpired: isTrialExpired,
                trialDaysRemaining: trialDaysRemaining
            )

            await MainActor.run {
                self.usageStats = stats
                self.showLimitWarning = stats.isMonthlyLimitExceeded || stats.isStorageLimitExceeded
            }
        } catch {
            print("[UsageTracker] Failed to fetch usage: \(error.localizedDescription)")
            // Fall back to local defaults so UI doesn't break
            let prefs = PreferencesService.shared
            let plan = prefs.userPlan.lowercased()
            let planExpiresAt: Int64? = prefs.planExpiresAt
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let isPaid = isPaidPlan(plan: plan, planExpiresAt: planExpiresAt, nowMs: nowMs)
            let monthlyLimit = isPaid ? paidMonthlyBytes : trialMonthlyBytes
            let storageLimit = isPaid ? paidStorageBytes : trialStorageBytes

            let stats = UsageStats(
                monthlyUploadBytes: 0,
                monthlyLimitBytes: monthlyLimit,
                storageBytes: 0,
                storageLimitBytes: storageLimit,
                isPaid: isPaid,
                isTrialExpired: false,
                trialDaysRemaining: trialDays
            )

            await MainActor.run {
                self.usageStats = stats
            }
        }
    }

    /// Refresh usage stats - call this after sync operations
    func refreshUsageStats() {
        guard let userId = UserDefaults.standard.string(forKey: "syncflow_user_id") else { return }
        Task {
            await fetchUsageStats(userId: userId)
        }
    }
}
