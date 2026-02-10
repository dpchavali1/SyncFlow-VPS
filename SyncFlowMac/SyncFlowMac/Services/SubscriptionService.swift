//
//  SubscriptionService.swift
//  SyncFlowMac
//
//  Handles in-app purchases and subscriptions using StoreKit 2
//  syncUsagePlan() uses local data only.
//

import Foundation
import StoreKit
import Combine

// MARK: - Product Identifiers

enum SubscriptionProduct: String, CaseIterable {
    case monthly = "com.syncflow.subscription.monthly"
    case yearly = "com.syncflow.subscription.yearly"
    case threeYear = "com.syncflow.3year"

    var displayName: String {
        switch self {
        case .monthly: return "Monthly"
        case .yearly: return "Yearly"
        case .threeYear: return "3-Year"
        }
    }

    var description: String {
        switch self {
        case .monthly: return "$4.99/month"
        case .yearly: return "$39.99/year (Save 33%)"
        case .threeYear: return "$79.99 for 3 years"
        }
    }
}

// MARK: - Subscription Status

enum SubscriptionStatus: Equatable {
    case notSubscribed
    case trial(daysRemaining: Int)
    case subscribed(plan: String, expiresAt: Date?)
    case threeYear(expiresAt: Date?)
    case expired

    var isActive: Bool {
        switch self {
        case .notSubscribed, .expired:
            return false
        case .trial, .subscribed, .threeYear:
            return true
        }
    }

    var displayText: String {
        switch self {
        case .notSubscribed:
            return "Not Subscribed"
        case .trial(let days):
            return "Trial: \(days) days remaining"
        case .subscribed(let plan, let expires):
            if let expires = expires {
                let formatter = DateFormatter()
                formatter.dateStyle = .medium
                return "\(plan) (expires \(formatter.string(from: expires)))"
            }
            return plan
        case .threeYear(let expires):
            if let expires = expires {
                let formatter = DateFormatter()
                formatter.dateStyle = .medium
                return "3-Year Plan (expires \(formatter.string(from: expires)))"
            }
            return "3-Year Plan"
        case .expired:
            return "Subscription Expired"
        }
    }
}

// MARK: - Subscription Service

@MainActor
class SubscriptionService: ObservableObject {
    static let shared = SubscriptionService()

    @Published var products: [Product] = []
    @Published var purchasedProductIDs: Set<String> = []
    @Published var subscriptionStatus: SubscriptionStatus = .notSubscribed
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?

    private var updateListenerTask: Task<Void, Error>?
    private let trialStartKey = "syncflow_trial_start_date"
    private let trialDuration: TimeInterval = 7 * 24 * 60 * 60 // 7 days trial (reduced from 30)

    private init() {
        updateListenerTask = listenForTransactions()

        Task {
            await loadProducts()
            await updateSubscriptionStatus()
        }
    }

    deinit {
        updateListenerTask?.cancel()
    }

    // MARK: - Trial Management

    var trialStartDate: Date? {
        get {
            UserDefaults.standard.object(forKey: trialStartKey) as? Date
        }
        set {
            UserDefaults.standard.set(newValue, forKey: trialStartKey)
        }
    }

    func startTrialIfNeeded() {
        if trialStartDate == nil {
            trialStartDate = Date()
        }
    }

    var trialDaysRemaining: Int {
        guard let startDate = trialStartDate else {
            return 7 // Full trial if not started (7 days)
        }

        let endDate = startDate.addingTimeInterval(trialDuration)
        let remaining = endDate.timeIntervalSince(Date())
        return max(0, Int(remaining / (24 * 60 * 60)))
    }

    var isTrialActive: Bool {
        return trialDaysRemaining > 0
    }

    var isTrialExpired: Bool {
        guard trialStartDate != nil else { return false }
        return trialDaysRemaining <= 0
    }

    /// Returns true if user has an active paid subscription (not trial)
    var isPremium: Bool {
        // First check StoreKit subscription
        switch subscriptionStatus {
        case .subscribed, .threeYear:
            return true
        case .trial, .expired, .notSubscribed:
            break // Fall through to check local plan
        }

        // Fallback to local plan from PreferencesService (for testing tab)
        return PreferencesService.shared.isPaidUser()
    }

    // MARK: - Load Products

    func loadProducts() async {
        isLoading = true
        errorMessage = nil

        do {
            let productIDs = SubscriptionProduct.allCases.map { $0.rawValue }
            let storeProducts = try await Product.products(for: productIDs)

            // Sort: monthly, yearly, 3-year
            products = storeProducts.sorted { p1, p2 in
                let order: [String: Int] = [
                    SubscriptionProduct.monthly.rawValue: 0,
                    SubscriptionProduct.yearly.rawValue: 1,
                    SubscriptionProduct.threeYear.rawValue: 2
                ]
                return (order[p1.id] ?? 99) < (order[p2.id] ?? 99)
            }

        } catch {
            print("SubscriptionService: Failed to load products: \(error)")
            errorMessage = "Failed to load subscription options"
        }

        isLoading = false
    }

    // MARK: - Purchase

    func purchase(_ product: Product) async throws -> Transaction? {
        isLoading = true
        errorMessage = nil

        do {
            let result = try await product.purchase()

            switch result {
            case .success(let verification):
                let transaction = try checkVerified(verification)
                await transaction.finish()
                await updateSubscriptionStatus()
                isLoading = false
                return transaction

            case .userCancelled:
                isLoading = false
                return nil

            case .pending:
                isLoading = false
                errorMessage = "Purchase is pending approval"
                return nil

            @unknown default:
                isLoading = false
                return nil
            }
        } catch {
            isLoading = false
            errorMessage = "Purchase failed: \(error.localizedDescription)"
            throw error
        }
    }

    // MARK: - Restore Purchases

    func restorePurchases() async {
        isLoading = true
        errorMessage = nil

        do {
            try await AppStore.sync()
            await updateSubscriptionStatus()
        } catch {
            errorMessage = "Failed to restore purchases"
            print("SubscriptionService: Restore failed: \(error)")
        }

        isLoading = false
    }

    // MARK: - Update Status

    func updateSubscriptionStatus() async {
        // Check local data for admin-assigned plan (Testing tab)
        await syncUsagePlan()

        // If local data already set a valid paid status, don't override with StoreKit
        if case .subscribed = subscriptionStatus {
            return
        }
        if case .threeYear = subscriptionStatus {
            return
        }


        // FALLBACK: Check for StoreKit subscriptions
        var hasActiveSubscription = false
        var activePlan: String?
        var expirationDate: Date?
        var hasThreeYear = false
        var threeYearExpiration: Date?

        // Check for active transactions
        for await result in Transaction.currentEntitlements {
            do {
                let transaction = try checkVerified(result)
                purchasedProductIDs.insert(transaction.productID)

                if transaction.productID == SubscriptionProduct.threeYear.rawValue {
                    hasThreeYear = true
                    threeYearExpiration = transaction.expirationDate
                } else if transaction.productID == SubscriptionProduct.yearly.rawValue {
                    hasActiveSubscription = true
                    activePlan = "Yearly"
                    expirationDate = transaction.expirationDate
                } else if transaction.productID == SubscriptionProduct.monthly.rawValue {
                    if activePlan != "Yearly" { // Yearly takes precedence
                        hasActiveSubscription = true
                        activePlan = "Monthly"
                        expirationDate = transaction.expirationDate
                    }
                }
            } catch {
            }
        }

        // Determine status from StoreKit
        if hasThreeYear {
            subscriptionStatus = .threeYear(expiresAt: threeYearExpiration)
        } else if hasActiveSubscription, let plan = activePlan {
            subscriptionStatus = .subscribed(plan: plan, expiresAt: expirationDate)
        } else if isTrialActive {
            subscriptionStatus = .trial(daysRemaining: trialDaysRemaining)
        } else if isTrialExpired {
            subscriptionStatus = .expired
        } else {
            // New user - start trial
            startTrialIfNeeded()
            subscriptionStatus = .trial(daysRemaining: trialDaysRemaining)
        }

    }

    // MARK: - Transaction Listener

    private func listenForTransactions() -> Task<Void, Error> {
        return Task.detached {
            for await result in Transaction.updates {
                do {
                    let transaction = try self.checkVerified(result)
                    await self.updateSubscriptionStatus()
                    await transaction.finish()
                } catch {
                }
            }
        }
    }

    // MARK: - Verification

    private nonisolated func checkVerified<T>(_ result: VerificationResult<T>) throws -> T {
        switch result {
        case .unverified:
            throw StoreError.verificationFailed
        case .verified(let safe):
            return safe
        }
    }

    // MARK: - Helpers

    func product(for identifier: SubscriptionProduct) -> Product? {
        return products.first { $0.id == identifier.rawValue }
    }

    var hasActiveSubscription: Bool {
        return subscriptionStatus.isActive
    }

    /// Checks local PreferencesService data for admin-assigned plans.
    /// Uses only locally cached plan data.
    private func syncUsagePlan() async {
        guard let userId = UserDefaults.standard.string(forKey: "syncflow_user_id"),
              !userId.isEmpty else {
            return
        }

        // Check local plan data from PreferencesService
        let prefs = PreferencesService.shared
        let plan = prefs.userPlan
        let planExpiresAt = prefs.planExpiresAt
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)

        // If local data has a valid paid plan that hasn't expired, use it
        if ["monthly", "yearly", "lifetime", "3year"].contains(plan.lowercased()) {
            if plan.lowercased() == "lifetime" || plan.lowercased() == "3year" ||
               (planExpiresAt > 0 && planExpiresAt > nowMs) {
                // Valid paid plan from local data
                if plan.lowercased() == "lifetime" || plan.lowercased() == "3year" {
                    let expiryDate = planExpiresAt > 0 ? Date(timeIntervalSince1970: TimeInterval(planExpiresAt) / 1000) : nil
                    self.subscriptionStatus = .threeYear(expiresAt: expiryDate)
                } else {
                    let expiryDate = Date(timeIntervalSince1970: TimeInterval(planExpiresAt) / 1000)
                    self.subscriptionStatus = .subscribed(plan: plan, expiresAt: expiryDate)
                }
                return
            }
        }

        // No valid local plan found; StoreKit check will follow in updateSubscriptionStatus()
    }

    private func updateLocalPlanData(plan: String, expiresAt: Int64) {
        let prefs = PreferencesService.shared
        prefs.setUserPlan(plan, expiresAt: expiresAt)
    }
}

// MARK: - Store Errors

enum StoreError: LocalizedError {
    case verificationFailed
    case purchaseFailed
    case productNotFound

    var errorDescription: String? {
        switch self {
        case .verificationFailed:
            return "Transaction verification failed"
        case .purchaseFailed:
            return "Purchase could not be completed"
        case .productNotFound:
            return "Product not found"
        }
    }
}
