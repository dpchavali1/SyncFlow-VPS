/**
 * =============================================================================
 * COST & PERFORMANCE MONITORING
 * =============================================================================
 *
 * Tracks API calls, bandwidth usage, cache effectiveness, and estimates
 * Firebase costs for the admin dashboard.
 *
 * FEATURES:
 * - API call counting per endpoint
 * - Bandwidth usage tracking (data downloaded)
 * - Cache hit/miss tracking
 * - Real-time cost estimation
 * - Performance metrics (response times)
 * - Session-based and persistent tracking
 */

// Pricing constants (Firebase Realtime Database - Spark/Blaze plans)
const FIREBASE_PRICING = {
  // Database reads (per 1,000 operations)
  READ_COST_PER_1000: 0.06, // $0.06 per 1,000 reads

  // Bandwidth (per GB)
  BANDWIDTH_COST_PER_GB: 0.15, // $0.15 per GB downloaded (after 10GB free tier)

  // Cloud Functions (invocations + compute time)
  FUNCTION_INVOCATION_COST_PER_MILLION: 0.40, // $0.40 per million invocations (after 2M free)
  FUNCTION_COMPUTE_COST_PER_GB_SECOND: 0.0000025, // $0.0000025 per GB-second
};

// Estimate average function memory and execution time
const AVG_FUNCTION_MEMORY_GB = 0.5; // 512MB
const AVG_FUNCTION_EXECUTION_TIME_MS = 800; // 800ms average

export interface ApiCallRecord {
  endpoint: string;
  timestamp: number;
  responseSize: number; // bytes
  responseTime: number; // milliseconds
  cached: boolean;
  success: boolean;
}

export interface CacheStats {
  hits: number;
  misses: number;
  hitRate: number;
}

export interface CostEstimate {
  apiCalls: number;
  bandwidthMB: number;
  estimatedReads: number;
  costBreakdown: {
    databaseReads: number;
    bandwidth: number;
    cloudFunctions: number;
    total: number;
  };
}

export interface PerformanceMetrics {
  avgResponseTime: number;
  maxResponseTime: number;
  minResponseTime: number;
  p95ResponseTime: number;
}

class CostMonitor {
  private apiCalls: Map<string, ApiCallRecord[]> = new Map();
  private cacheHits: number = 0;
  private cacheMisses: number = 0;
  private sessionStartTime: number = Date.now();

  // Persistent storage key
  private readonly STORAGE_KEY = 'syncflow_monitoring_data';

  constructor() {
    this.loadFromStorage();
  }

  /**
   * Track an API call
   */
  trackApiCall(
    endpoint: string,
    responseSize: number,
    responseTime: number,
    cached: boolean = false,
    success: boolean = true
  ): void {
    const record: ApiCallRecord = {
      endpoint,
      timestamp: Date.now(),
      responseSize,
      responseTime,
      cached,
      success,
    };

    if (!this.apiCalls.has(endpoint)) {
      this.apiCalls.set(endpoint, []);
    }

    this.apiCalls.get(endpoint)!.push(record);

    // Track cache stats
    if (cached) {
      this.cacheHits++;
    } else {
      this.cacheMisses++;
    }

    // Persist to localStorage
    this.saveToStorage();

    console.log(`[Monitor] ${endpoint}: ${responseSize} bytes, ${responseTime}ms, cached: ${cached}`);
  }

  /**
   * Get cache statistics
   */
  getCacheStats(): CacheStats {
    const total = this.cacheHits + this.cacheMisses;
    return {
      hits: this.cacheHits,
      misses: this.cacheMisses,
      hitRate: total > 0 ? (this.cacheHits / total) * 100 : 0,
    };
  }

  /**
   * Get total API calls
   */
  getTotalApiCalls(): number {
    let total = 0;
    for (const records of this.apiCalls.values()) {
      total += records.length;
    }
    return total;
  }

  /**
   * Get API calls by endpoint
   */
  getApiCallsByEndpoint(): Map<string, number> {
    const result = new Map<string, number>();
    for (const [endpoint, records] of this.apiCalls.entries()) {
      result.set(endpoint, records.length);
    }
    return result;
  }

  /**
   * Get total bandwidth used (in bytes)
   */
  getTotalBandwidth(): number {
    let total = 0;
    for (const records of this.apiCalls.values()) {
      for (const record of records) {
        if (!record.cached) {
          // Only count non-cached calls (cached calls use no bandwidth)
          total += record.responseSize;
        }
      }
    }
    return total;
  }

  /**
   * Get bandwidth by endpoint
   */
  getBandwidthByEndpoint(): Map<string, number> {
    const result = new Map<string, number>();
    for (const [endpoint, records] of this.apiCalls.entries()) {
      let bandwidth = 0;
      for (const record of records) {
        if (!record.cached) {
          bandwidth += record.responseSize;
        }
      }
      result.set(endpoint, bandwidth);
    }
    return result;
  }

  /**
   * Get performance metrics
   */
  getPerformanceMetrics(): PerformanceMetrics {
    const allTimes: number[] = [];
    for (const records of this.apiCalls.values()) {
      for (const record of records) {
        allTimes.push(record.responseTime);
      }
    }

    if (allTimes.length === 0) {
      return {
        avgResponseTime: 0,
        maxResponseTime: 0,
        minResponseTime: 0,
        p95ResponseTime: 0,
      };
    }

    allTimes.sort((a, b) => a - b);

    const avg = allTimes.reduce((a, b) => a + b, 0) / allTimes.length;
    const max = allTimes[allTimes.length - 1];
    const min = allTimes[0];
    const p95Index = Math.floor(allTimes.length * 0.95);
    const p95 = allTimes[p95Index];

    return {
      avgResponseTime: Math.round(avg),
      maxResponseTime: max,
      minResponseTime: min,
      p95ResponseTime: p95,
    };
  }

  /**
   * Estimate costs based on usage
   */
  estimateCosts(): CostEstimate {
    const totalCalls = this.getTotalApiCalls();
    const bandwidthBytes = this.getTotalBandwidth();
    const bandwidthMB = bandwidthBytes / (1024 * 1024);
    const bandwidthGB = bandwidthBytes / (1024 * 1024 * 1024);

    // Estimate database reads
    // Rough estimate: each API call results in 10-50 database reads on average
    // Optimized endpoints: ~10 reads, unoptimized: ~50 reads
    // We'll use 15 as average (weighted toward optimized)
    const estimatedReads = totalCalls * 15;

    // Calculate costs
    const databaseReadCost = (estimatedReads / 1000) * FIREBASE_PRICING.READ_COST_PER_1000;

    // Bandwidth cost (subtract free tier of 10GB)
    const billableBandwidthGB = Math.max(0, bandwidthGB - 10);
    const bandwidthCost = billableBandwidthGB * FIREBASE_PRICING.BANDWIDTH_COST_PER_GB;

    // Cloud Functions cost
    const invocationCost =
      Math.max(0, totalCalls - 2000000) / 1000000 * FIREBASE_PRICING.FUNCTION_INVOCATION_COST_PER_MILLION;

    const computeSeconds = (totalCalls * AVG_FUNCTION_EXECUTION_TIME_MS) / 1000;
    const computeGBSeconds = computeSeconds * AVG_FUNCTION_MEMORY_GB;
    const computeCost = computeGBSeconds * FIREBASE_PRICING.FUNCTION_COMPUTE_COST_PER_GB_SECOND;

    const cloudFunctionsCost = invocationCost + computeCost;

    return {
      apiCalls: totalCalls,
      bandwidthMB: Math.round(bandwidthMB * 100) / 100,
      estimatedReads,
      costBreakdown: {
        databaseReads: Math.round(databaseReadCost * 1000) / 1000,
        bandwidth: Math.round(bandwidthCost * 1000) / 1000,
        cloudFunctions: Math.round(cloudFunctionsCost * 1000) / 1000,
        total: Math.round((databaseReadCost + bandwidthCost + cloudFunctionsCost) * 1000) / 1000,
      },
    };
  }

  /**
   * Get session duration in minutes
   */
  getSessionDuration(): number {
    return Math.round((Date.now() - this.sessionStartTime) / 60000);
  }

  /**
   * Get detailed statistics for display
   */
  getDetailedStats() {
    const cacheStats = this.getCacheStats();
    const costEstimate = this.estimateCosts();
    const perfMetrics = this.getPerformanceMetrics();
    const byEndpoint = this.getApiCallsByEndpoint();
    const bandwidthByEndpoint = this.getBandwidthByEndpoint();

    return {
      session: {
        duration: this.getSessionDuration(),
        startTime: new Date(this.sessionStartTime).toISOString(),
      },
      apiCalls: {
        total: this.getTotalApiCalls(),
        byEndpoint: Object.fromEntries(byEndpoint),
      },
      bandwidth: {
        totalMB: costEstimate.bandwidthMB,
        byEndpoint: Object.fromEntries(
          Array.from(bandwidthByEndpoint.entries()).map(([endpoint, bytes]) => [
            endpoint,
            Math.round((bytes / (1024 * 1024)) * 100) / 100,
          ])
        ),
      },
      cache: cacheStats,
      performance: perfMetrics,
      costs: costEstimate,
    };
  }

  /**
   * Reset all monitoring data
   */
  reset(): void {
    this.apiCalls.clear();
    this.cacheHits = 0;
    this.cacheMisses = 0;
    this.sessionStartTime = Date.now();
    this.saveToStorage();
    console.log('[Monitor] All data reset');
  }

  /**
   * Export data as JSON
   */
  exportData(): string {
    return JSON.stringify(
      {
        session: {
          startTime: this.sessionStartTime,
          duration: this.getSessionDuration(),
        },
        apiCalls: Array.from(this.apiCalls.entries()).map(([endpoint, records]) => ({
          endpoint,
          records,
        })),
        cacheStats: this.getCacheStats(),
        costs: this.estimateCosts(),
      },
      null,
      2
    );
  }

  /**
   * Save to localStorage (browser-only)
   */
  private saveToStorage(): void {
    // Skip if running on server (Next.js SSR)
    if (typeof window === 'undefined') return;

    try {
      const data = {
        apiCalls: Array.from(this.apiCalls.entries()),
        cacheHits: this.cacheHits,
        cacheMisses: this.cacheMisses,
        sessionStartTime: this.sessionStartTime,
      };
      localStorage.setItem(this.STORAGE_KEY, JSON.stringify(data));
    } catch (error) {
      console.error('[Monitor] Failed to save to localStorage:', error);
    }
  }

  /**
   * Load from localStorage (browser-only)
   */
  private loadFromStorage(): void {
    // Skip if running on server (Next.js SSR)
    if (typeof window === 'undefined') return;

    try {
      const stored = localStorage.getItem(this.STORAGE_KEY);
      if (stored) {
        const data = JSON.parse(stored);
        this.apiCalls = new Map(data.apiCalls || []);
        this.cacheHits = data.cacheHits || 0;
        this.cacheMisses = data.cacheMisses || 0;
        this.sessionStartTime = data.sessionStartTime || Date.now();
        console.log('[Monitor] Loaded data from localStorage');
      }
    } catch (error) {
      console.error('[Monitor] Failed to load from localStorage:', error);
    }
  }
}

// Global monitor instance
export const costMonitor = new CostMonitor();

/**
 * Wrapper to automatically track API calls
 */
export async function trackApiCall<T>(
  endpoint: string,
  fn: () => Promise<T>,
  cached: boolean = false
): Promise<T> {
  const startTime = Date.now();
  let success = true;
  let responseSize = 0;

  try {
    const result = await fn();

    // Estimate response size (rough approximation)
    responseSize = new Blob([JSON.stringify(result)]).size;

    return result;
  } catch (error) {
    success = false;
    throw error;
  } finally {
    const responseTime = Date.now() - startTime;
    costMonitor.trackApiCall(endpoint, responseSize, responseTime, cached, success);
  }
}

/**
 * USAGE EXAMPLES:
 *
 * // In firebase.ts, wrap callable functions:
 * export const getSystemCleanupOverview = async () => {
 *   const cached = cacheManager.get('systemOverview');
 *   if (cached) {
 *     costMonitor.trackApiCall('getSystemCleanupOverview', 0, 0, true, true);
 *     return cached;
 *   }
 *
 *   return trackApiCall('getSystemCleanupOverview', async () => {
 *     const callable = httpsCallable(functions, 'getSystemCleanupOverview');
 *     const result = await callable();
 *     return result.data;
 *   });
 * };
 *
 * // View statistics:
 * const stats = costMonitor.getDetailedStats();
 * console.log('Current costs:', stats.costs);
 * console.log('Cache hit rate:', stats.cache.hitRate);
 */
