//
//  PerformanceOptimizer.swift
//  SyncFlowMac
//
//  Performance optimization utilities for macOS app
//

import Foundation
import Combine
// FirebaseDatabase - using FirebaseStubs.swift

class PerformanceOptimizer {
    static let shared = PerformanceOptimizer()

    // MARK: - Listener Throttling

    private var throttledUpdates: [String: DispatchWorkItem] = [:]
    private let throttleQueue = DispatchQueue(label: "com.syncflowmac.throttle", qos: .utility)

    /// Throttle updates to prevent excessive UI refreshes
    func throttledUpdate(key: String, interval: TimeInterval = 0.5, action: @escaping () -> Void) {
        let workItem = DispatchWorkItem { action() }

        throttleQueue.async {
            // Cancel existing work item for this key
            self.throttledUpdates[key]?.cancel()
            self.throttledUpdates[key] = workItem

            // Schedule new work item
            DispatchQueue.main.asyncAfter(deadline: .now() + interval, execute: workItem)
        }
    }

    // MARK: - Memory Management

    private var memoryPressureSource: DispatchSourceMemoryPressure?

    init() {
        setupMemoryPressureMonitoring()
    }

    deinit {
        memoryPressureSource?.cancel()
    }

    private func setupMemoryPressureMonitoring() {
        let source = DispatchSource.makeMemoryPressureSource(eventMask: [.warning, .critical], queue: DispatchQueue.global(qos: .userInitiated))
        source.setEventHandler { [weak self, weak source] in
            guard let strongSource = source else { return }
            let pressure = DispatchSource.MemoryPressureEvent(rawValue: strongSource.data)
            self?.handleMemoryPressure(pressure)
        }
        source.resume()
        memoryPressureSource = source
    }

    private func handleMemoryPressure(_ pressure: DispatchSource.MemoryPressureEvent) {
        print("[Performance] Memory pressure: \(pressure.rawValue)")

        switch pressure {
        case .warning:
            // Reduce memory usage
            clearNonEssentialCaches()
        case .critical:
            // Aggressive cleanup
            clearAllCaches()
            NotificationCenter.default.post(name: .memoryPressureCritical, object: nil)
        default:
            break
        }
    }

    private func clearNonEssentialCaches() {
        // Clear attachment caches that can be reloaded
        Task {
            await AttachmentCacheManager.shared.clearOldEntries(olderThan: 3600) // 1 hour
        }
    }

    private func clearAllCaches() {
        // Clear all caches in emergency
        AttachmentCacheManager.shared.clearAll()
    }

    // MARK: - Firebase Listener Optimization

    /// Optimized listener that batches updates
    func createBatchedListener<T>(
        for query: DatabaseQuery,
        transform: @escaping ([DataSnapshot]) -> T,
        update: @escaping (T) -> Void
    ) -> DatabaseHandle {
        var pendingSnapshots: [DataSnapshot] = []
        var updateWorkItem: DispatchWorkItem?

        return query.observe(.value) { snapshot in
            // Collect all child snapshots
            if let children = snapshot.children.allObjects as? [DataSnapshot] {
                pendingSnapshots = children
            }

            // Cancel existing update
            updateWorkItem?.cancel()

            // Schedule batched update
            updateWorkItem = DispatchWorkItem {
                let result = transform(pendingSnapshots)
                DispatchQueue.main.async {
                    update(result)
                }
                pendingSnapshots.removeAll()
            }

            // Execute after short delay to batch rapid updates
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.1, execute: updateWorkItem!)
        }
    }

    // MARK: - Background Processing Optimization

    private let processingQueue = DispatchQueue(label: "com.syncflowmac.processing", qos: .background, attributes: .concurrent)

    /// Process heavy operations in background with priority management
    func processInBackground<T>(
        priority: DispatchQoS = .background,
        operation: @escaping () throws -> T,
        completion: @escaping (Result<T, Error>) -> Void
    ) {
        processingQueue.async(qos: priority) {
            do {
                let result = try operation()
                DispatchQueue.main.async {
                    completion(.success(result))
                }
            } catch {
                DispatchQueue.main.async {
                    completion(.failure(error))
                }
            }
        }
    }

    // MARK: - Battery Awareness

    private var batteryMonitor: Timer?

    func startBatteryMonitoring() {
        // Monitor battery level every 30 seconds
        batteryMonitor = Timer.scheduledTimer(withTimeInterval: 30.0, repeats: true) { [weak self] _ in
            self?.adjustPerformanceForBattery()
        }
    }

    func stopBatteryMonitoring() {
        batteryMonitor?.invalidate()
        batteryMonitor = nil
    }

    private func adjustPerformanceForBattery() {
        let batteryLevel = getBatteryLevel()
        let isCharging = isDeviceCharging()

        // Adjust performance based on battery and charging status
        if batteryLevel < 20 && !isCharging {
            // Low battery, reduce background activity
            reduceBackgroundActivity()
        } else if batteryLevel > 80 || isCharging {
            // Good battery or charging, allow full performance
            resumeFullPerformance()
        }
    }

    private func getBatteryLevel() -> Int {
        let task = Process()
        task.launchPath = "/usr/bin/pmset"
        task.arguments = ["-g", "batt"]

        let pipe = Pipe()
        task.standardOutput = pipe

        do {
            try task.run()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            if let output = String(data: data, encoding: .utf8) {
                // Parse battery percentage from output like "Battery Power: 85%"
                if let range = output.range(of: "\\d+(?=%)", options: .regularExpression),
                   let percentage = Int(output[range]) {
                    return percentage
                }
            }
        } catch {
            print("[Performance] Failed to get battery level: \(error)")
        }

        return 100 // Default to 100% if unable to determine
    }

    private func isDeviceCharging() -> Bool {
        let task = Process()
        task.launchPath = "/usr/bin/pmset"
        task.arguments = ["-g", "batt"]

        let pipe = Pipe()
        task.standardOutput = pipe

        do {
            try task.run()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            if let output = String(data: data, encoding: .utf8) {
                return output.contains("AC Power") || output.contains("charging")
            }
        } catch {
            print("[Performance] Failed to check charging status: \(error)")
        }

        return false
    }

    private func reduceBackgroundActivity() {
        // Reduce Firebase listener frequency
        // Decrease cache sizes
        // Pause non-essential sync operations
        NotificationCenter.default.post(name: .batteryLowModeEnabled, object: nil)
    }

    private func resumeFullPerformance() {
        // Resume normal operations
        NotificationCenter.default.post(name: .batteryNormalModeEnabled, object: nil)
    }

    // MARK: - Cache Size Management

    func optimizeCacheSizes() {
        let memoryInfo = getMemoryInfo()

        // Adjust cache sizes based on available memory
        if memoryInfo.availableMB < 512 {
            // Low memory device
            AttachmentCacheManager.shared.setMaxCacheSize(50 * 1024 * 1024) // 50MB
        } else if memoryInfo.availableMB < 2048 {
            // Medium memory device
            AttachmentCacheManager.shared.setMaxCacheSize(200 * 1024 * 1024) // 200MB
        } else {
            // High memory device
            AttachmentCacheManager.shared.setMaxCacheSize(500 * 1024 * 1024) // 500MB
        }
    }

    private func getMemoryInfo() -> (totalMB: Int, availableMB: Int) {
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
            return (totalMB: total, availableMB: available)
        }

        return (totalMB: 8192, availableMB: 2048) // Default fallback
    }
}

// MARK: - Performance Extensions

extension Notification.Name {
    static let memoryPressureCritical = Notification.Name("com.syncflowmac.memoryPressureCritical")
    static let batteryLowModeEnabled = Notification.Name("com.syncflowmac.batteryLowModeEnabled")
    static let batteryNormalModeEnabled = Notification.Name("com.syncflowmac.batteryNormalModeEnabled")
}
