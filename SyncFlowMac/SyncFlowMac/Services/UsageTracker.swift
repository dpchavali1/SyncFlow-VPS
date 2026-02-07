//
//  UsageTracker.swift
//  SyncFlowMac
//
//  Tracks per-user upload usage for MMS and file sharing quotas.
//

import Foundation
import Combine
// FirebaseAuth, FirebaseDatabase - using FirebaseStubs.swift

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

    private let database = Database.database()
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

    func isUploadAllowed(
        userId: String,
        bytes: Int64,
        countsTowardStorage: Bool
    ) async -> UsageDecision {
        if bytes <= 0 {
            return UsageDecision(allowed: true, reason: nil)
        }

        let usageRef = database.reference()
            .child("users")
            .child(userId)
            .child("usage")

        let snapshot = try? await usageRef.getData()
        let usage = snapshot?.value as? [String: Any] ?? [:]

        let planRaw = (usage["plan"] as? String)?.lowercased()
        let planExpiresAt = (usage["planExpiresAt"] as? NSNumber)?.int64Value
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)

        let isPaid = isPaidPlan(plan: planRaw, planExpiresAt: planExpiresAt, nowMs: nowMs)

        if !isPaid {
            var trialStartMs = (usage["trialStartedAt"] as? NSNumber)?.int64Value
            if trialStartMs == nil {
                trialStartMs = nowMs
                try? await usageRef.child("trialStartedAt").setValue(ServerValue.timestamp())
            }
            if let start = trialStartMs, nowMs - start > Int64(trialDuration * 1000) {
                return UsageDecision(allowed: false, reason: "trial_expired")
            }
        }

        let monthlyLimit = isPaid ? paidMonthlyBytes : trialMonthlyBytes
        let storageLimit = isPaid ? paidStorageBytes : trialStorageBytes

        let periodKey = currentPeriodKey()
        let monthly = usage["monthly"] as? [String: Any]
        let periodData = monthly?[periodKey] as? [String: Any]
        let uploadBytes = (periodData?["uploadBytes"] as? NSNumber)?.int64Value ?? 0

        if uploadBytes + bytes > monthlyLimit {
            return UsageDecision(allowed: false, reason: "monthly_quota")
        }

        if countsTowardStorage {
            let storageBytes = (usage["storageBytes"] as? NSNumber)?.int64Value ?? 0
            if storageBytes + bytes > storageLimit {
                return UsageDecision(allowed: false, reason: "storage_quota")
            }
        }

        return UsageDecision(allowed: true, reason: nil)
    }

    func recordUpload(
        userId: String,
        bytes: Int64,
        category: UsageCategory,
        countsTowardStorage: Bool
    ) async {
        if bytes <= 0 { return }

        let periodKey = currentPeriodKey()
        let usageRef = database.reference()
            .child("users")
            .child(userId)
            .child("usage")

        var updates: [String: Any] = [
            "monthly/\(periodKey)/uploadBytes": ServerValue.increment(NSNumber(value: bytes)),
            "lastUpdatedAt": ServerValue.timestamp()
        ]

        switch category {
        case .mms:
            updates["monthly/\(periodKey)/mmsBytes"] = ServerValue.increment(NSNumber(value: bytes))
        case .file:
            updates["monthly/\(periodKey)/fileBytes"] = ServerValue.increment(NSNumber(value: bytes))
        }

        if countsTowardStorage {
            updates["storageBytes"] = ServerValue.increment(NSNumber(value: bytes))
        }

        try? await usageRef.updateChildValues(updates)
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

    /// Fetch current usage statistics for display
    func fetchUsageStats(userId: String) async {
        let usageRef = database.reference()
            .child("users")
            .child(userId)
            .child("usage")

        let snapshot = try? await usageRef.getData()
        let usage = snapshot?.value as? [String: Any] ?? [:]

        let planRaw = (usage["plan"] as? String)?.lowercased()
        let planExpiresAt = (usage["planExpiresAt"] as? NSNumber)?.int64Value
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)

        let isPaid = isPaidPlan(plan: planRaw, planExpiresAt: planExpiresAt, nowMs: nowMs)

        let trialStartMs = (usage["trialStartedAt"] as? NSNumber)?.int64Value ?? nowMs
        let trialElapsedMs = nowMs - trialStartMs
        let trialElapsedDays = Int(trialElapsedMs / (24 * 60 * 60 * 1000))
        let trialDaysRemaining = max(0, trialDays - trialElapsedDays)
        let isTrialExpired = !isPaid && trialElapsedDays > trialDays

        let monthlyLimit = isPaid ? paidMonthlyBytes : trialMonthlyBytes
        let storageLimit = isPaid ? paidStorageBytes : trialStorageBytes

        let periodKey = currentPeriodKey()
        let monthly = usage["monthly"] as? [String: Any]
        let periodData = monthly?[periodKey] as? [String: Any]
        let uploadBytes = (periodData?["uploadBytes"] as? NSNumber)?.int64Value ?? 0
        let storageBytes = (usage["storageBytes"] as? NSNumber)?.int64Value ?? 0

        let stats = UsageStats(
            monthlyUploadBytes: uploadBytes,
            monthlyLimitBytes: monthlyLimit,
            storageBytes: storageBytes,
            storageLimitBytes: storageLimit,
            isPaid: isPaid,
            isTrialExpired: isTrialExpired,
            trialDaysRemaining: trialDaysRemaining
        )

        await MainActor.run {
            self.usageStats = stats
            self.showLimitWarning = stats.isMonthlyLimitExceeded || stats.isStorageLimitExceeded
        }
    }

    /// Refresh usage stats - call this after sync operations
    func refreshUsageStats() {
        // Use stored paired user ID, fall back to auth.currentUser
        guard let userId = UserDefaults.standard.string(forKey: "syncflow_user_id") ?? Auth.auth().currentUser?.uid else { return }
        Task {
            await fetchUsageStats(userId: userId)
        }
    }
}
