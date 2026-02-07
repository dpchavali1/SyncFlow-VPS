//
//  Logger.swift
//  SyncFlowMac
//
//  Production-safe logging utility
//

import Foundation
import os.log

/// Production-safe logger that uses os_log for structured logging
/// Logs are automatically stripped in release builds by the system
enum Logger {
    private static let subsystem = Bundle.main.bundleIdentifier ?? "com.syncflow.mac"

    // Log categories
    private static let general = OSLog(subsystem: subsystem, category: "general")
    private static let firebase = OSLog(subsystem: subsystem, category: "firebase")
    private static let calls = OSLog(subsystem: subsystem, category: "calls")
    private static let sync = OSLog(subsystem: subsystem, category: "sync")
    private static let webrtc = OSLog(subsystem: subsystem, category: "webrtc")

    enum Category {
        case general
        case firebase
        case calls
        case sync
        case webrtc

        var log: OSLog {
            switch self {
            case .general: return Logger.general
            case .firebase: return Logger.firebase
            case .calls: return Logger.calls
            case .sync: return Logger.sync
            case .webrtc: return Logger.webrtc
            }
        }
    }

    /// Debug log - only visible in debug builds
    static func debug(_ message: String, category: Category = .general) {
        #if DEBUG
        os_log(.debug, log: category.log, "%{public}@", message)
        #endif
    }

    /// Info log - general information
    static func info(_ message: String, category: Category = .general) {
        #if DEBUG
        os_log(.info, log: category.log, "%{public}@", message)
        #endif
    }

    /// Warning log - potential issues
    static func warning(_ message: String, category: Category = .general) {
        os_log(.default, log: category.log, "⚠️ %{public}@", message)
    }

    /// Error log - always logged
    static func error(_ message: String, error: Error? = nil, category: Category = .general) {
        if let error = error {
            os_log(.error, log: category.log, "❌ %{public}@: %{public}@", message, error.localizedDescription)
        } else {
            os_log(.error, log: category.log, "❌ %{public}@", message)
        }
    }

    /// Fault log - critical errors
    static func fault(_ message: String, category: Category = .general) {
        os_log(.fault, log: category.log, "💥 %{public}@", message)
    }
}

// MARK: - App Configuration

enum AppConfig {
    #if DEBUG
    static let isDebug = true
    #else
    static let isDebug = false
    #endif

    static let appVersion: String = {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }()

    static let buildNumber: String = {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
    }()

    static let supportEmail: String = {
        Bundle.main.infoDictionary?["SupportEmail"] as? String ?? "syncflow.contact@gmail.com"
    }()

    static let supportEmailSubject: String = {
        Bundle.main.infoDictionary?["SupportEmailSubject"] as? String ?? "[SyncFlow Mac] Support Request"
    }()

    /// Returns a mailto URL with pre-filled subject and app version info
    static var supportMailtoURL: URL? {
        let subject = "\(supportEmailSubject) - v\(appVersion)"
        let encodedSubject = subject.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? subject
        return URL(string: "mailto:\(supportEmail)?subject=\(encodedSubject)")
    }

    static let privacyPolicyURL: URL? = {
        guard let urlString = Bundle.main.infoDictionary?["PrivacyPolicyURL"] as? String else { return nil }
        return URL(string: urlString)
    }()

    static let termsOfServiceURL: URL? = {
        guard let urlString = Bundle.main.infoDictionary?["TermsOfServiceURL"] as? String else { return nil }
        return URL(string: urlString)
    }()
}
