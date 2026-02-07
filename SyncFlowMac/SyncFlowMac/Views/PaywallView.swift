//
//  PaywallView.swift
//  SyncFlowMac
//
//  Subscription paywall UI
//

import SwiftUI
import StoreKit

struct PaywallView: View {
    @StateObject private var subscriptionService = SubscriptionService.shared
    @Environment(\.dismiss) private var dismiss
    @State private var selectedProduct: Product?
    @State private var showError = false
    @State private var isPurchasing = false

    var body: some View {
        VStack(spacing: 0) {
            // Close button
            HStack {
                Spacer()
                Button(action: { dismiss() }) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 24))
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .padding(12)
            }

            // Header
            headerSection

            Divider()

            ScrollView {
                VStack(spacing: 24) {
                    // Features list
                    featuresSection

                    // Pricing options
                    pricingSection

                    // Subscribe button
                    subscribeButton

                    // Restore purchases
                    restoreButton

                    // Terms
                    termsSection
                }
                .padding(24)
            }
        }
        .frame(width: 480, height: 620)
        .background(Color(nsColor: .windowBackgroundColor))
        .alert("Error", isPresented: $showError) {
            Button("OK") { }
        } message: {
            Text(subscriptionService.errorMessage ?? "An error occurred")
        }
        .onChange(of: subscriptionService.errorMessage) { error in
            showError = error != nil
        }
        .onAppear {
            // Select yearly by default (best value)
            if selectedProduct == nil {
                selectedProduct = subscriptionService.product(for: .yearly)
            }
        }
    }

    // MARK: - Header

    private var headerSection: some View {
        VStack(spacing: 12) {
            Image(systemName: "sparkles")
                .font(.system(size: 40))
                .foregroundStyle(
                    LinearGradient(
                        colors: [SyncFlowColors.adaptiveBlue, SyncFlowColors.adaptivePurple],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )

            Text("Upgrade to SyncFlow Pro")
                .font(.title)
                .fontWeight(.bold)

            if case .trial(let days) = subscriptionService.subscriptionStatus {
                Text("\(days) days left in your free trial")
                    .font(.subheadline)
                    .foregroundColor(SyncFlowColors.adaptiveOrange)
            } else if case .expired = subscriptionService.subscriptionStatus {
                Text("Your trial has expired")
                    .font(.subheadline)
                    .foregroundColor(SyncFlowColors.adaptiveRed)
            }
        }
        .padding(.bottom, 20)
        .frame(maxWidth: .infinity)
        .background(
            LinearGradient(
                colors: [SyncFlowColors.adaptiveBlue.opacity(0.1), SyncFlowColors.adaptivePurple.opacity(0.1)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
    }

    // MARK: - Features

    private var featuresSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Premium Features")
                .font(.headline)

            FeatureRow(icon: "message.fill", text: "Unlimited SMS & MMS from Mac")
            FeatureRow(icon: "phone.fill", text: "Make & receive calls from Mac")
            FeatureRow(icon: "photo.fill", text: "Photo sync (Premium only)")
            FeatureRow(icon: "doc.on.clipboard", text: "Universal clipboard sync")
            FeatureRow(icon: "bell.fill", text: "Notification mirroring")
            FeatureRow(icon: "lock.shield.fill", text: "End-to-end encryption")
            FeatureRow(icon: "clock.fill", text: "Scheduled messages")
            FeatureRow(icon: "icloud.fill", text: "3GB uploads/month, 500MB storage")
            FeatureRow(icon: "star.fill", text: "Priority support")
        }
        .padding(16)
        .background(Color(nsColor: .controlBackgroundColor))
        .cornerRadius(12)
    }

    // MARK: - Pricing

    private var pricingSection: some View {
        VStack(spacing: 12) {
            if subscriptionService.isLoading && subscriptionService.products.isEmpty {
                ProgressView()
                    .frame(height: 150)
            } else {
                ForEach(subscriptionService.products, id: \.id) { product in
                    PricingOptionCard(
                        product: product,
                        isSelected: selectedProduct?.id == product.id,
                        isBestValue: product.id == SubscriptionProduct.yearly.rawValue
                    ) {
                        selectedProduct = product
                    }
                }
            }
        }
    }

    // MARK: - Subscribe Button

    private var subscribeButton: some View {
        Button(action: {
            Task {
                await purchaseSelected()
            }
        }) {
            HStack {
                if isPurchasing {
                    ProgressView()
                        .scaleEffect(0.8)
                        .padding(.trailing, 4)
                }
                Text(isPurchasing ? "Processing..." : "Subscribe Now")
                    .fontWeight(.semibold)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(
                LinearGradient(
                    colors: [SyncFlowColors.adaptiveBlue, SyncFlowColors.adaptivePurple],
                    startPoint: .leading,
                    endPoint: .trailing
                )
            )
            .foregroundColor(.white)
            .cornerRadius(10)
        }
        .buttonStyle(.plain)
        .disabled(selectedProduct == nil || isPurchasing)
    }

    // MARK: - Restore Button

    private var restoreButton: some View {
        Button("Restore Purchases") {
            Task {
                await subscriptionService.restorePurchases()
                if subscriptionService.hasActiveSubscription {
                    dismiss()
                }
            }
        }
        .font(.subheadline)
        .foregroundColor(.secondary)
        .buttonStyle(.plain)
    }

    // MARK: - Terms

    private var termsSection: some View {
        VStack(spacing: 8) {
            Text("Subscriptions auto-renew unless cancelled at least 24 hours before the end of the current period. Manage subscriptions in System Settings > Apple ID > Subscriptions.")
                .font(.caption2)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            HStack(spacing: 16) {
                Link("Terms of Service", destination: URL(string: "https://syncflow.app/terms")!)
                Link("Privacy Policy", destination: URL(string: "https://syncflow.app/privacy")!)
            }
            .font(.caption2)
        }
    }

    // MARK: - Actions

    private func purchaseSelected() async {
        guard let product = selectedProduct else { return }

        isPurchasing = true
        do {
            if let _ = try await subscriptionService.purchase(product) {
                dismiss()
            }
        } catch {
            print("Purchase failed: \(error)")
        }
        isPurchasing = false
    }
}

// MARK: - Feature Row

struct FeatureRow: View {
    let icon: String
    let text: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 14))
                .foregroundColor(SyncFlowColors.adaptiveBlue)
                .frame(width: 24)

            Text(text)
                .font(.subheadline)

            Spacer()

            Image(systemName: "checkmark.circle.fill")
                .foregroundColor(SyncFlowColors.adaptiveGreen)
                .font(.system(size: 14))
        }
    }
}

// MARK: - Pricing Option Card

struct PricingOptionCard: View {
    let product: Product
    let isSelected: Bool
    let isBestValue: Bool
    let onSelect: () -> Void

    private var isThreeYear: Bool {
        product.id == SubscriptionProduct.threeYear.rawValue
    }

    private var isYearly: Bool {
        product.id == SubscriptionProduct.yearly.rawValue
    }

    private var periodText: String {
        if isThreeYear {
            return "3-year plan"
        } else if isYearly {
            return "per year"
        } else {
            return "per month"
        }
    }

    private var savingsText: String? {
        if isYearly {
            return "Save 33%"
        } else if isThreeYear {
            return "Best Value"
        }
        return nil
    }

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 16) {
                // Selection indicator
                ZStack {
                    Circle()
                        .stroke(isSelected ? SyncFlowColors.adaptiveBlue : SyncFlowColors.adaptiveGray.opacity(0.3), lineWidth: 2)
                        .frame(width: 22, height: 22)

                    if isSelected {
                        Circle()
                            .fill(SyncFlowColors.adaptiveBlue)
                            .frame(width: 14, height: 14)
                    }
                }

                // Plan details
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(product.displayName)
                            .font(.headline)

                        if let savings = savingsText {
                            Text(savings)
                                .font(.caption)
                                .fontWeight(.semibold)
                                .foregroundColor(.white)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 2)
                                .background(
                                    isBestValue ? SyncFlowColors.adaptiveOrange : SyncFlowColors.adaptiveGreen
                                )
                                .cornerRadius(4)
                        }
                    }

                    Text(periodText)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                // Price
                Text(product.displayPrice)
                    .font(.title2)
                    .fontWeight(.bold)
                    .foregroundColor(isSelected ? SyncFlowColors.adaptiveBlue : .primary)
            }
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(isSelected ? SyncFlowColors.adaptiveBlue.opacity(0.1) : Color(nsColor: .controlBackgroundColor))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isSelected ? SyncFlowColors.adaptiveBlue : Color.clear, lineWidth: 2)
            )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Subscription Status Banner

struct SubscriptionStatusBanner: View {
    @ObservedObject var subscriptionService = SubscriptionService.shared
    @State private var showPaywall = false

    var body: some View {
        Group {
            switch subscriptionService.subscriptionStatus {
            case .trial(let days):
                trialBanner(daysRemaining: days)
            case .expired:
                expiredBanner
            case .notSubscribed:
                notSubscribedBanner
            default:
                EmptyView()
            }
        }
        .sheet(isPresented: $showPaywall) {
            PaywallView()
        }
    }

    private func trialBanner(daysRemaining: Int) -> some View {
        HStack {
            Image(systemName: "clock.fill")
                .foregroundColor(SyncFlowColors.adaptiveOrange)

            Text("\(daysRemaining) days left in trial")
                .font(.subheadline)

            Spacer()

            Button("Upgrade") {
                showPaywall = true
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)
        }
        .padding(12)
        .background(SyncFlowColors.adaptiveOrange.opacity(0.1))
        .cornerRadius(8)
    }

    private var expiredBanner: some View {
        HStack {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(SyncFlowColors.adaptiveRed)

            Text("Trial expired - Subscribe to continue")
                .font(.subheadline)

            Spacer()

            Button("Subscribe") {
                showPaywall = true
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)
        }
        .padding(12)
        .background(SyncFlowColors.adaptiveRed.opacity(0.1))
        .cornerRadius(8)
    }

    private var notSubscribedBanner: some View {
        HStack {
            Image(systemName: "sparkles")
                .foregroundColor(SyncFlowColors.adaptiveBlue)

            Text("Start your free 7-day trial")
                .font(.subheadline)

            Spacer()

            Button("Start Trial") {
                subscriptionService.startTrialIfNeeded()
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)
        }
        .padding(12)
        .background(SyncFlowColors.adaptiveBlue.opacity(0.1))
        .cornerRadius(8)
    }
}

// MARK: - Preview

#Preview {
    PaywallView()
}
