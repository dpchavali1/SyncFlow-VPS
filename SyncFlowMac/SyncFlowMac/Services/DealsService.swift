//
//  DealsService.swift
//  SyncFlowMac
//
//  Service to fetch and cache deals from GitHub
//

import Foundation
import Combine

class DealsService: ObservableObject {
    static let shared = DealsService()

    @Published private(set) var deals: [Deal] = []
    @Published private(set) var isLoading = false
    @Published private(set) var lastUpdated: Date?
    @Published private(set) var error: String?

    private let dealsURLs = [
        "https://raw.githubusercontent.com/dpchavali1/syncflow-deals/main/deals.json",
        "https://raw.githubusercontent.com/dpchavali1/syncflow-deals/main/deals2.json",
        "https://raw.githubusercontent.com/dpchavali1/syncflow-deals/main/deals3.json"
    ]

    private let cacheKey = "cached_deals"
    private let cacheTimestampKey = "cached_deals_timestamp"
    private let cacheDuration: TimeInterval = 30 * 60 // 30 minutes

    private init() {
        loadCachedDeals()
    }

    // MARK: - Public Methods

    /// Fetch deals from GitHub (with cache check)
    func fetchDeals(forceRefresh: Bool = false) async {
        await MainActor.run {
            isLoading = true
            error = nil
        }

        // Check cache first (unless force refresh)
        if !forceRefresh, let cachedTimestamp = UserDefaults.standard.object(forKey: cacheTimestampKey) as? Date {
            let elapsed = Date().timeIntervalSince(cachedTimestamp)
            if elapsed < cacheDuration && !deals.isEmpty {
                await MainActor.run {
                    isLoading = false
                }
                return
            }
        }

        // Try each URL until one succeeds
        for urlString in dealsURLs {
            if let fetchedDeals = await fetchFromURL(urlString) {
                await MainActor.run {
                    self.deals = fetchedDeals.sorted { $0.score > $1.score }
                    self.lastUpdated = Date()
                    self.isLoading = false
                    self.error = nil
                }
                cacheDeals(fetchedDeals)
                return
            }
        }

        // All URLs failed
        await MainActor.run {
            isLoading = false
            if deals.isEmpty {
                error = "Unable to load deals. Please check your internet connection."
            }
        }
    }

    /// Filter deals by category
    func deals(for category: DealCategory) -> [Deal] {
        if category == .all {
            return deals
        }
        return deals.filter { $0.category.lowercased() == category.rawValue.lowercased() }
    }

    /// Search deals by query
    func searchDeals(query: String) -> [Deal] {
        guard !query.isEmpty else { return deals }

        let lowercaseQuery = query.lowercased()
        return deals.filter { deal in
            deal.title.lowercased().contains(lowercaseQuery) ||
            deal.category.lowercased().contains(lowercaseQuery) ||
            deal.price.lowercased().contains(lowercaseQuery)
        }
    }

    /// Filter and search combined
    func filteredDeals(category: DealCategory, searchQuery: String) -> [Deal] {
        var result = deals(for: category)

        if !searchQuery.isEmpty {
            let lowercaseQuery = searchQuery.lowercased()
            result = result.filter { deal in
                deal.title.lowercased().contains(lowercaseQuery) ||
                deal.category.lowercased().contains(lowercaseQuery) ||
                deal.price.lowercased().contains(lowercaseQuery)
            }
        }

        return result
    }

    // MARK: - Private Methods

    private func fetchFromURL(_ urlString: String) async -> [Deal]? {
        guard let url = URL(string: urlString) else { return nil }

        do {
            var request = URLRequest(url: url)
            request.timeoutInterval = 10
            request.cachePolicy = .reloadIgnoringLocalCacheData

            let (data, response) = try await URLSession.shared.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse,
                  (200...299).contains(httpResponse.statusCode) else {
                return nil
            }

            let decoder = JSONDecoder()
            let dealsResponse = try decoder.decode(DealsResponse.self, from: data)
            return dealsResponse.deals

        } catch {
            print("[DealsService] Error fetching from \(urlString): \(error)")
            return nil
        }
    }

    private func loadCachedDeals() {
        guard let data = UserDefaults.standard.data(forKey: cacheKey) else { return }

        do {
            let cachedDeals = try JSONDecoder().decode([Deal].self, from: data)
            deals = cachedDeals.sorted { $0.score > $1.score }

            if let timestamp = UserDefaults.standard.object(forKey: cacheTimestampKey) as? Date {
                lastUpdated = timestamp
            }
        } catch {
            print("[DealsService] Error loading cached deals: \(error)")
        }
    }

    private func cacheDeals(_ deals: [Deal]) {
        do {
            let data = try JSONEncoder().encode(deals)
            UserDefaults.standard.set(data, forKey: cacheKey)
            UserDefaults.standard.set(Date(), forKey: cacheTimestampKey)
        } catch {
            print("[DealsService] Error caching deals: \(error)")
        }
    }
}
