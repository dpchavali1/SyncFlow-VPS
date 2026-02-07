//
//  MemoryOptimizer.swift
//  SyncFlowMac
//
//  Memory management and optimization for macOS app
//

import Foundation
import Combine

class MemoryOptimizer {
    static let shared = MemoryOptimizer()

    // MARK: - Memory Monitoring

    private var memoryMonitor: Timer?
    private var memoryPressureObserver: NSObjectProtocol?
    private var cancellables = Set<AnyCancellable>()

    // Memory thresholds (in MB)
    private let criticalThreshold = 256  // MB
    private let warningThreshold = 512   // MB
    private let normalThreshold = 1024   // MB

    // MARK: - Initialization

    init() {
        setupMemoryMonitoring()
        setupBatteryAwareness()
    }

    deinit {
        memoryMonitor?.invalidate()
        if let observer = memoryPressureObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    private func setupMemoryMonitoring() {
        // Monitor memory usage every 10 seconds
        memoryMonitor = Timer.scheduledTimer(withTimeInterval: 10.0, repeats: true) { [weak self] _ in
            self?.checkMemoryUsage()
        }

        // Note: macOS doesn't have the same memory pressure notification as iOS
        // We'll rely on periodic memory checks instead
    }

    private func setupBatteryAwareness() {
        // Battery awareness is handled by BatteryAwareServiceManager directly
        // No need for notifications in this simplified macOS version
    }

    // MARK: - Memory Monitoring

    private func checkMemoryUsage() {
        let memoryInfo = getMemoryInfo()

        if memoryInfo.availableMB < criticalThreshold {
            print("[Memory] Critical memory usage: \(memoryInfo.usedMB)MB used, \(memoryInfo.availableMB)MB available")
            performCriticalMemoryCleanup()
        } else if memoryInfo.availableMB < warningThreshold {
            print("[Memory] Warning memory usage: \(memoryInfo.usedMB)MB used, \(memoryInfo.availableMB)MB available")
            performWarningMemoryCleanup()
        }
    }

    private func handleMemoryPressure() {
        // Since macOS doesn't have the same memory pressure API,
        // we'll just perform a general cleanup
        print("[Memory] Performing memory cleanup")
        performWarningMemoryCleanup()
    }

    // MARK: - Memory Cleanup

    private func performWarningMemoryCleanup() {
        // Clear non-essential caches
        clearAttachmentCache(olderThan: 1800) // 30 minutes
        clearMessageCache(olderThan: 3600)   // 1 hour

        // Reduce image cache sizes
        reduceImageCacheSizes()

        // Force garbage collection hint
        performGarbageCollection()
    }

    private func performCriticalMemoryCleanup() {
        // Aggressive cleanup
        clearAttachmentCache(olderThan: 300)  // 5 minutes
        clearMessageCache(olderThan: 900)    // 15 minutes

        // Clear all image caches
        clearAllImageCaches()

        // Suspend non-essential services temporarily
        suspendNonEssentialServices()

        // Force garbage collection
        performGarbageCollection()
    }

    private func reduceMemoryUsage() {
        // Called when battery is low - reduce memory footprint
        clearAttachmentCache(olderThan: 600)  // 10 minutes
        clearMessageCache(olderThan: 1800)   // 30 minutes
        reduceImageCacheSizes(by: 0.5)       // Reduce by 50%
    }

    private func minimizeMemoryUsage() {
        // Called when battery is very low - minimize memory usage
        clearAttachmentCache(olderThan: 60)   // 1 minute
        clearMessageCache(olderThan: 300)    // 5 minutes
        reduceImageCacheSizes(by: 0.8)       // Reduce by 80%
    }

    // MARK: - Cache Management

    private func clearAttachmentCache(olderThan seconds: TimeInterval) {
        // Clear attachment cache entries older than specified time
        Task {
            await AttachmentCacheManager.shared.clearOldEntries(olderThan: seconds)
        }
    }

    private func clearMessageCache(olderThan seconds: TimeInterval) {
        // This would clear any cached message data that's not actively displayed
        // For now, we'll post a notification for other components to handle
        NotificationCenter.default.post(
            name: .clearMessageCache,
            object: nil,
            userInfo: ["olderThan": seconds]
        )
    }

    private func reduceImageCacheSizes(by factor: Double = 0.5) {
        // Reduce image cache sizes by the specified factor
        NotificationCenter.default.post(
            name: .reduceImageCacheSizes,
            object: nil,
            userInfo: ["factor": factor]
        )
    }

    private func clearAllImageCaches() {
        // Clear all image caches
        NotificationCenter.default.post(name: .clearAllImageCaches, object: nil)
    }

    private func suspendNonEssentialServices() {
        // Temporarily suspend services that consume memory
        NotificationCenter.default.post(name: .suspendMemoryIntensiveServices, object: nil)
    }

    private func performGarbageCollection() {
        // Hint to the system that now would be a good time to garbage collect
        // In Swift, ARC handles this automatically, but we can suggest to the system
        CFRunLoopPerformBlock(CFRunLoopGetMain(), CFRunLoopMode.commonModes.rawValue) {
            // Allow run loop to process pending releases
        }
    }

    // MARK: - Memory Information

    func getMemoryInfo() -> (totalMB: Int, usedMB: Int, availableMB: Int, pressure: String) {
        var stats = vm_statistics64()
        var size = mach_msg_type_number_t(MemoryLayout<vm_statistics64>.size / MemoryLayout<integer_t>.size)

        let hostPort = mach_host_self()
        let result = withUnsafeMutablePointer(to: &stats) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(size)) {
                host_statistics64(hostPort, HOST_VM_INFO64, $0, &size)
            }
        }

        if result == KERN_SUCCESS {
            let pageSize = Int(vm_kernel_page_size)
            let totalPages = Int(stats.free_count) + Int(stats.active_count) + Int(stats.inactive_count) + Int(stats.wire_count)
            let availablePages = Int(stats.free_count) + Int(stats.inactive_count)
            let total = totalPages * pageSize / (1024 * 1024)
            let available = availablePages * pageSize / (1024 * 1024)
            let used = total - available

            let pressure: String = "normal" // macOS doesn't have the same memory pressure API

            return (totalMB: total, usedMB: used, availableMB: available, pressure: pressure)
        }

        return (totalMB: 0, usedMB: 0, availableMB: 0, pressure: "unknown")
    }

    // MARK: - Public API

    func getCurrentMemoryStats() -> [String: Any] {
        let info = getMemoryInfo()
        return [
            "totalMB": info.totalMB,
            "usedMB": info.usedMB,
            "availableMB": info.availableMB,
            "pressure": info.pressure,
            "usagePercentage": info.totalMB > 0 ? Double(info.usedMB) / Double(info.totalMB) : 0.0
        ]
    }

    func forceMemoryCleanup() {
        performCriticalMemoryCleanup()
    }

    func optimizeForLowMemory() {
        minimizeMemoryUsage()
    }
}

// MARK: - Notification Extensions

extension Notification.Name {
    static let clearMessageCache = Notification.Name("com.syncflowmac.clearMessageCache")
    static let reduceImageCacheSizes = Notification.Name("com.syncflowmac.reduceImageCacheSizes")
    static let clearAllImageCaches = Notification.Name("com.syncflowmac.clearAllImageCaches")
    static let suspendMemoryIntensiveServices = Notification.Name("com.syncflowmac.suspendMemoryIntensiveServices")
}
