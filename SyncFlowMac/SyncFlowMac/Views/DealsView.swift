//
//  DealsView.swift
//  SyncFlowMac
//
//  Main deals view with search, filtering, and deal cards
//

import SwiftUI
import AppKit

struct DealsView: View {
    @StateObject private var dealsService = DealsService.shared
    @State private var searchText = ""
    @State private var selectedCategory: DealCategory = .all
    @State private var isHoveringRefresh = false

    private var filteredDeals: [Deal] {
        dealsService.filteredDeals(category: selectedCategory, searchQuery: searchText)
    }

    // Top 3 deals by score for featured section
    private var featuredDeals: [Deal] {
        Array(dealsService.deals.filter { $0.discount >= 30 }.prefix(3))
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            DealsHeader(
                searchText: $searchText,
                isLoading: dealsService.isLoading,
                lastUpdated: dealsService.lastUpdated,
                onRefresh: {
                    Task {
                        await dealsService.fetchDeals(forceRefresh: true)
                    }
                }
            )

            Divider()
                .background(SyncFlowColors.divider)

            // Category Filter
            CategoryFilterBar(selectedCategory: $selectedCategory)

            Divider()
                .background(SyncFlowColors.divider)

            // Deals Content
            if dealsService.isLoading && dealsService.deals.isEmpty {
                DealsLoadingView()
            } else if let error = dealsService.error, dealsService.deals.isEmpty {
                DealsErrorView(message: error) {
                    Task {
                        await dealsService.fetchDeals(forceRefresh: true)
                    }
                }
            } else if filteredDeals.isEmpty {
                DealsEmptyView(searchQuery: searchText, category: selectedCategory)
            } else {
                ScrollView {
                    VStack(spacing: 16) {
                        // Featured Deals Banner (only show when not searching)
                        if searchText.isEmpty && selectedCategory == .all && !featuredDeals.isEmpty {
                            FeaturedDealsBanner(deals: featuredDeals)
                                .padding(.horizontal, 20)
                                .padding(.top, 16)
                        }

                        // Deals Grid
                        DealsGridContent(deals: filteredDeals)
                            .padding(.horizontal, 20)
                            .padding(.bottom, 20)
                    }
                }
            }
        }
        .background(SyncFlowColors.background)
        .task {
            await dealsService.fetchDeals()
        }
    }
}

// MARK: - Featured Deals Banner

struct FeaturedDealsBanner: View {
    let deals: [Deal]
    @State private var currentIndex = 0
    @State private var timer: Timer?

    var body: some View {
        VStack(spacing: 0) {
            // Banner header
            HStack {
                HStack(spacing: 8) {
                    Image(systemName: "flame.fill")
                        .foregroundColor(SyncFlowColors.adaptiveOrange)
                    Text("HOT DEALS")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(SyncFlowColors.adaptiveOrange)
                }

                Spacer()

                // Dots indicator
                HStack(spacing: 6) {
                    ForEach(0..<deals.count, id: \.self) { index in
                        Circle()
                            .fill(index == currentIndex ? Color.white : Color.white.opacity(0.4))
                            .frame(width: 6, height: 6)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 8)

            // Featured deal card
            if !deals.isEmpty {
                FeaturedDealCard(deal: deals[currentIndex])
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
                    .id(currentIndex)
                    .transition(.asymmetric(
                        insertion: .move(edge: .trailing).combined(with: .opacity),
                        removal: .move(edge: .leading).combined(with: .opacity)
                    ))
            }
        }
        .background(
            LinearGradient(
                colors: [
                    SyncFlowColors.featuredGradientStart,
                    SyncFlowColors.featuredGradientEnd
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .cornerRadius(16)
        .shadow(color: .black.opacity(0.2), radius: 10, x: 0, y: 4)
        .onAppear {
            startAutoScroll()
        }
        .onDisappear {
            timer?.invalidate()
        }
    }

    private func startAutoScroll() {
        timer = Timer.scheduledTimer(withTimeInterval: 4.0, repeats: true) { _ in
            withAnimation(.easeInOut(duration: 0.5)) {
                currentIndex = (currentIndex + 1) % max(deals.count, 1)
            }
        }
    }
}

struct FeaturedDealCard: View {
    let deal: Deal
    @State private var image: NSImage?
    @State private var isHovering = false

    var body: some View {
        Button(action: openDeal) {
            HStack(spacing: 14) {
                // Image
                Group {
                    if let image {
                        Image(nsImage: image)
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                    } else {
                        Rectangle()
                            .fill(Color.white.opacity(0.1))
                            .overlay(
                                ProgressView()
                                    .scaleEffect(0.6)
                            )
                    }
                }
                .frame(width: 80, height: 80)
                .cornerRadius(10)
                .clipped()

                // Content
                VStack(alignment: .leading, spacing: 6) {
                    Text(deal.title)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(.white)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)

                    HStack(spacing: 10) {
                        Text(deal.price)
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(SyncFlowColors.adaptiveGreen)

                        if deal.hasDiscount {
                            Text(deal.discountText)
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(SyncFlowColors.adaptiveRed)
                                .cornerRadius(4)
                        }
                    }
                }

                Spacer()

                // Arrow
                Image(systemName: "chevron.right.circle.fill")
                    .font(.system(size: 24))
                    .foregroundColor(.white.opacity(0.6))
            }
            .padding(10)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.white.opacity(isHovering ? 0.15 : 0.08))
            )
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            isHovering = hovering
        }
        .task {
            await loadImage()
        }
    }

    private func loadImage() async {
        guard let url = deal.imageURL else { return }
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            if let nsImage = NSImage(data: data) {
                await MainActor.run {
                    self.image = nsImage
                }
            }
        } catch {
            print("[FeaturedDealCard] Error loading image: \(error)")
        }
    }

    private func openDeal() {
        guard let url = deal.dealURL else { return }

        // Method 1: Try NSWorkspace.shared.open
        var success = NSWorkspace.shared.open(url)

        if !success {
            // Method 2: Try opening with default browser explicitly
            if let browserURL = NSWorkspace.shared.urlForApplication(toOpen: url) {
                NSWorkspace.shared.open([url], withApplicationAt: browserURL, configuration: NSWorkspace.OpenConfiguration()) { _, error in
                    if error != nil {
                        openDealFallback(url)
                    }
                }
                return
            }
        }

        if !success {
            openDealFallback(url)
        }
    }

    private func openDealFallback(_ url: URL) {
        // Method 3: Try shell command as last resort
        let task = Process()
        task.launchPath = "/usr/bin/open"
        task.arguments = [url.absoluteString]
        try? task.run()
        task.waitUntilExit()
    }
}

// MARK: - Header

struct DealsHeader: View {
    @Binding var searchText: String
    let isLoading: Bool
    let lastUpdated: Date?
    let onRefresh: () -> Void
    @State private var isHovering = false

    var body: some View {
        HStack(spacing: 16) {
            // Title with icon
            HStack(spacing: 10) {
                Image(systemName: "tag.fill")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(
                        LinearGradient(
                            colors: [SyncFlowColors.accentOrange, SyncFlowColors.accentPink],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )

                Text("SyncFlow Deals")
                    .font(.title2)
                    .fontWeight(.bold)
            }

            Spacer()

            // Last updated
            if let lastUpdated {
                Text("Updated \(lastUpdated, style: .relative) ago")
                    .font(.caption)
                    .foregroundColor(SyncFlowColors.textSecondary)
            }

            // Search field
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(SyncFlowColors.textSecondary)
                    .font(.system(size: 13))

                TextField("Search deals...", text: $searchText)
                    .textFieldStyle(.plain)
                    .font(.system(size: 13))

                if !searchText.isEmpty {
                    Button(action: { searchText = "" }) {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(SyncFlowColors.textTertiary)
                            .font(.system(size: 12))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(SyncFlowColors.surfaceSecondary)
            .cornerRadius(8)
            .frame(width: 200)

            // Refresh button
            Button(action: onRefresh) {
                Group {
                    if isLoading {
                        ProgressView()
                            .scaleEffect(0.7)
                            .frame(width: 16, height: 16)
                    } else {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 14, weight: .medium))
                    }
                }
                .foregroundColor(isHovering ? SyncFlowColors.primary : SyncFlowColors.textSecondary)
                .frame(width: 32, height: 32)
                .background(isHovering ? SyncFlowColors.primary.opacity(0.1) : Color.clear)
                .cornerRadius(8)
            }
            .buttonStyle(.plain)
            .disabled(isLoading)
            .onHover { hovering in
                isHovering = hovering
            }
            .help("Refresh deals")
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(SyncFlowColors.surface)
    }
}

// MARK: - Category Filter Bar

struct CategoryFilterBar: View {
    @Binding var selectedCategory: DealCategory

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(DealCategory.allCases, id: \.self) { category in
                    CategoryChip(
                        category: category,
                        isSelected: selectedCategory == category,
                        onTap: { selectedCategory = category }
                    )
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
        }
        .background(SyncFlowColors.surface)
    }
}

struct CategoryChip: View {
    let category: DealCategory
    let isSelected: Bool
    let onTap: () -> Void
    @State private var isHovering = false

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 6) {
                Image(systemName: category.icon)
                    .font(.system(size: 12))

                Text(category.rawValue)
                    .font(.system(size: 12, weight: isSelected ? .semibold : .medium))
            }
            .foregroundColor(isSelected ? .white : SyncFlowColors.textPrimary)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(
                Group {
                    if isSelected {
                        LinearGradient(
                            colors: [SyncFlowColors.primary, SyncFlowColors.accentPurple],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    } else if isHovering {
                        SyncFlowColors.surfaceSecondary
                    } else {
                        SyncFlowColors.surfaceTertiary
                    }
                }
            )
            .cornerRadius(16)
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            isHovering = hovering
        }
    }
}

// MARK: - Deals Grid Content

struct DealsGridContent: View {
    let deals: [Deal]

    private let columns = [
        GridItem(.adaptive(minimum: 280, maximum: 350), spacing: 16)
    ]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 16) {
            ForEach(deals) { deal in
                DealCard(deal: deal)
            }
        }
        .padding(.top, 4)
    }
}

// MARK: - Deal Card

struct DealCard: View {
    let deal: Deal
    @State private var isHovering = false
    @State private var image: NSImage?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Image with overlay
            ZStack(alignment: .topLeading) {
                // Image
                Group {
                    if let image {
                        Image(nsImage: image)
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                    } else {
                        Rectangle()
                            .fill(SyncFlowColors.surfaceSecondary)
                            .overlay(
                                Image(systemName: "photo")
                                    .font(.system(size: 30))
                                    .foregroundColor(SyncFlowColors.textTertiary)
                            )
                    }
                }
                .frame(height: 160)
                .clipped()

                // Discount badge
                if deal.hasDiscount {
                    Text(deal.discountText)
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(
                            LinearGradient(
                                colors: [SyncFlowColors.adaptiveRed, SyncFlowColors.adaptiveRed.opacity(0.8)],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .cornerRadius(4)
                        .padding(10)
                }

                // Category badge
                HStack {
                    Spacer()
                    Text(deal.category)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundColor(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color.black.opacity(0.6))
                        .cornerRadius(4)
                        .padding(10)
                }
            }

            // Content
            VStack(alignment: .leading, spacing: 8) {
                // Title
                Text(deal.title)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(SyncFlowColors.textPrimary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)

                // Price row
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(deal.price)
                        .font(.system(size: 18, weight: .bold))
                        .foregroundColor(SyncFlowColors.success)

                    if deal.hasDiscount {
                        Text("Save \(deal.discount)%")
                            .font(.system(size: 11))
                            .foregroundColor(SyncFlowColors.error)
                    }

                    Spacer()

                    // Score indicator
                    HStack(spacing: 2) {
                        ForEach(0..<5, id: \.self) { index in
                            Image(systemName: index < deal.score ? "star.fill" : "star")
                                .font(.system(size: 9))
                                .foregroundColor(index < deal.score ? SyncFlowColors.adaptiveOrange : SyncFlowColors.textTertiary)
                        }
                    }
                }

                // View Deal button
                Button(action: openDeal) {
                    HStack {
                        Text("View Deal")
                            .font(.system(size: 12, weight: .semibold))
                        Image(systemName: "arrow.up.right")
                            .font(.system(size: 10, weight: .semibold))
                    }
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(
                        LinearGradient(
                            colors: [SyncFlowColors.primary, SyncFlowColors.accentPurple],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .cornerRadius(8)
                }
                .buttonStyle(.plain)
            }
            .padding(12)
        }
        .background(SyncFlowColors.surfaceElevated)
        .cornerRadius(12)
        .shadow(color: .black.opacity(isHovering ? 0.15 : 0.08), radius: isHovering ? 12 : 6, x: 0, y: isHovering ? 6 : 3)
        .scaleEffect(isHovering ? 1.02 : 1.0)
        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: isHovering)
        .contentShape(Rectangle())
        .onTapGesture {
            openDeal()
        }
        .onHover { hovering in
            isHovering = hovering
        }
        .task {
            await loadImage()
        }
    }

    private func loadImage() async {
        guard let url = deal.imageURL else { return }

        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            if let nsImage = NSImage(data: data) {
                await MainActor.run {
                    self.image = nsImage
                }
            }
        } catch {
            print("[DealCard] Error loading image: \(error)")
        }
    }

    private func openDeal() {
        guard let url = deal.dealURL else { return }

        // Method 1: Try NSWorkspace.shared.open
        var success = NSWorkspace.shared.open(url)

        if !success {
            // Method 2: Try opening with default browser explicitly
            if let browserURL = NSWorkspace.shared.urlForApplication(toOpen: url) {
                NSWorkspace.shared.open([url], withApplicationAt: browserURL, configuration: NSWorkspace.OpenConfiguration()) { _, error in
                    if error != nil {
                        openDealFallback(url)
                    }
                }
                return
            }
        }

        if !success {
            openDealFallback(url)
        }
    }

    private func openDealFallback(_ url: URL) {
        // Method 3: Try shell command as last resort
        let task = Process()
        task.launchPath = "/usr/bin/open"
        task.arguments = [url.absoluteString]
        try? task.run()
        task.waitUntilExit()
    }
}

// MARK: - Loading View

struct DealsLoadingView: View {
    var body: some View {
        VStack(spacing: 20) {
            ProgressView()
                .scaleEffect(1.5)

            Text("Loading deals...")
                .font(.headline)
                .foregroundColor(SyncFlowColors.textSecondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Error View

struct DealsErrorView: View {
    let message: String
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 50))
                .foregroundColor(SyncFlowColors.textTertiary)

            Text("Unable to Load Deals")
                .font(.title3)
                .fontWeight(.semibold)

            Text(message)
                .font(.body)
                .foregroundColor(SyncFlowColors.textSecondary)
                .multilineTextAlignment(.center)

            Button(action: onRetry) {
                HStack {
                    Image(systemName: "arrow.clockwise")
                    Text("Try Again")
                }
                .font(.system(size: 14, weight: .medium))
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Empty View

struct DealsEmptyView: View {
    let searchQuery: String
    let category: DealCategory

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: searchQuery.isEmpty ? "tag.slash" : "magnifyingglass")
                .font(.system(size: 50))
                .foregroundColor(SyncFlowColors.textTertiary)

            if !searchQuery.isEmpty {
                Text("No deals found for \"\(searchQuery)\"")
                    .font(.title3)
                    .fontWeight(.semibold)

                Text("Try a different search term or category")
                    .font(.body)
                    .foregroundColor(SyncFlowColors.textSecondary)
            } else if category != .all {
                Text("No \(category.rawValue) deals available")
                    .font(.title3)
                    .fontWeight(.semibold)

                Text("Check back later or browse other categories")
                    .font(.body)
                    .foregroundColor(SyncFlowColors.textSecondary)
            } else {
                Text("No deals available")
                    .font(.title3)
                    .fontWeight(.semibold)

                Text("Check back later for new deals")
                    .font(.body)
                    .foregroundColor(SyncFlowColors.textSecondary)
            }
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Preview

#Preview {
    DealsView()
        .frame(width: 800, height: 600)
}
