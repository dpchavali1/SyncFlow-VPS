//
//  LinkPreviewService.swift
//  SyncFlowMac
//
//  Fetches link previews (Open Graph metadata) for URLs
//

import Foundation
import AppKit

struct LinkPreview: Identifiable {
    let id = UUID()
    let url: URL
    var title: String?
    var description: String?
    var imageURL: URL?
    var siteName: String?
    var favicon: NSImage?
}

class LinkPreviewService {
    static let shared = LinkPreviewService()

    private var cache = [String: LinkPreview]()
    private let urlSession: URLSession

    private init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 10
        config.timeoutIntervalForResource = 15
        urlSession = URLSession(configuration: config)
    }

    // MARK: - URL Detection

    /// Extract URLs from text
    static func extractURLs(from text: String) -> [URL] {
        let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let matches = detector?.matches(in: text, options: [], range: NSRange(location: 0, length: text.utf16.count)) ?? []

        return matches.compactMap { match in
            guard let range = Range(match.range, in: text) else { return nil }
            let urlString = String(text[range])
            return URL(string: urlString)
        }
    }

    // MARK: - Preview Fetching

    /// Fetch link preview for a URL
    func fetchPreview(for url: URL) async -> LinkPreview? {
        // Check cache first
        if let cached = cache[url.absoluteString] {
            return cached
        }

        // Only fetch for http/https URLs
        guard let scheme = url.scheme, ["http", "https"].contains(scheme.lowercased()) else {
            return nil
        }

        do {
            var request = URLRequest(url: url)
            request.setValue("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15", forHTTPHeaderField: "User-Agent")

            let (data, response) = try await urlSession.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200,
                  let html = String(data: data, encoding: .utf8) else {
                return nil
            }

            var preview = LinkPreview(url: url)

            // Parse Open Graph tags
            preview.title = extractMetaContent(from: html, property: "og:title")
                ?? extractMetaContent(from: html, name: "title")
                ?? extractTitle(from: html)

            preview.description = extractMetaContent(from: html, property: "og:description")
                ?? extractMetaContent(from: html, name: "description")

            if let imageURLString = extractMetaContent(from: html, property: "og:image"),
               let imageURL = URL(string: imageURLString, relativeTo: url) {
                preview.imageURL = imageURL.absoluteURL
            }

            preview.siteName = extractMetaContent(from: html, property: "og:site_name")
                ?? url.host

            // Cache the result
            cache[url.absoluteString] = preview

            return preview
        } catch {
            // Don't log cancelled errors - they're expected when switching conversations
            let nsError = error as NSError
            if nsError.code != NSURLErrorCancelled {
                print("[LinkPreview] Error fetching \(url): \(error)")
            }
            return nil
        }
    }

    // MARK: - HTML Parsing

    private func extractMetaContent(from html: String, property: String) -> String? {
        let pattern = "<meta[^>]+property=[\"']\(property)[\"'][^>]+content=[\"']([^\"']+)[\"']"
        let altPattern = "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']\(property)[\"']"

        if let match = html.range(of: pattern, options: .regularExpression) {
            let matchString = String(html[match])
            return extractContentValue(from: matchString)
        }

        if let match = html.range(of: altPattern, options: .regularExpression) {
            let matchString = String(html[match])
            return extractContentValue(from: matchString)
        }

        return nil
    }

    private func extractMetaContent(from html: String, name: String) -> String? {
        let pattern = "<meta[^>]+name=[\"']\(name)[\"'][^>]+content=[\"']([^\"']+)[\"']"
        let altPattern = "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+name=[\"']\(name)[\"']"

        if let match = html.range(of: pattern, options: [.regularExpression, .caseInsensitive]) {
            let matchString = String(html[match])
            return extractContentValue(from: matchString)
        }

        if let match = html.range(of: altPattern, options: [.regularExpression, .caseInsensitive]) {
            let matchString = String(html[match])
            return extractContentValue(from: matchString)
        }

        return nil
    }

    private func extractContentValue(from tag: String) -> String? {
        let pattern = "content=[\"']([^\"']+)[\"']"
        guard let match = tag.range(of: pattern, options: .regularExpression) else {
            return nil
        }
        let matchString = String(tag[match])
        return matchString
            .replacingOccurrences(of: "content=", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
            .decodingHTMLEntities()
    }

    private func extractTitle(from html: String) -> String? {
        let pattern = "<title[^>]*>([^<]+)</title>"
        guard let match = html.range(of: pattern, options: [.regularExpression, .caseInsensitive]) else {
            return nil
        }
        let matchString = String(html[match])
        return matchString
            .replacingOccurrences(of: "<title>", with: "", options: .caseInsensitive)
            .replacingOccurrences(of: "</title>", with: "", options: .caseInsensitive)
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .decodingHTMLEntities()
    }

    /// Clear cache
    func clearCache() {
        cache.removeAll()
    }
}

// MARK: - HTML Entity Decoding

extension String {
    func decodingHTMLEntities() -> String {
        var result = self
        let entities = [
            "&amp;": "&",
            "&lt;": "<",
            "&gt;": ">",
            "&quot;": "\"",
            "&apos;": "'",
            "&#39;": "'",
            "&nbsp;": " "
        ]
        for (entity, char) in entities {
            result = result.replacingOccurrences(of: entity, with: char)
        }
        return result
    }
}
