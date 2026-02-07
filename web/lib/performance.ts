'use client';

import { useEffect, useState, useCallback } from 'react';

type NetworkInformation = {
  effectiveType?: string;
  downlink?: number;
  rtt?: number;
  saveData?: boolean;
  type?: string;
  addEventListener?: (type: string, listener: EventListenerOrEventListenerObject) => void;
  removeEventListener?: (type: string, listener: EventListenerOrEventListenerObject) => void;
};

// Performance monitoring hook
export function usePerformanceMonitor() {
  const [metrics, setMetrics] = useState({
    fcp: 0, // First Contentful Paint
    lcp: 0, // Largest Contentful Paint
    fid: 0, // First Input Delay
    cls: 0, // Cumulative Layout Shift
    ttfb: 0, // Time to First Byte
  });

  useEffect(() => {
    // Web Vitals monitoring
    if (typeof window !== 'undefined' && 'web-vitals' in window) {
      import('web-vitals').then(({ getCLS, getFID, getFCP, getLCP, getTTFB }) => {
        getCLS((metric) => {
          setMetrics(prev => ({ ...prev, cls: metric.value }));
        });

        getFID((metric) => {
          setMetrics(prev => ({ ...prev, fid: metric.value }));
        });

        getFCP((metric) => {
          setMetrics(prev => ({ ...prev, fcp: metric.value }));
        });

        getLCP((metric) => {
          setMetrics(prev => ({ ...prev, lcp: metric.value }));
        });

        getTTFB((metric) => {
          setMetrics(prev => ({ ...prev, ttfb: metric.value }));
        });
      });
    }
  }, []);

  return metrics;
}

// Memory usage monitoring hook
export function useMemoryMonitor() {
  const [memoryInfo, setMemoryInfo] = useState({
    used: 0,
    total: 0,
    limit: 0,
    usage: 0,
  });

  const updateMemoryInfo = useCallback(() => {
    if ('memory' in performance) {
      const memory = (performance as any).memory;
      setMemoryInfo({
        used: memory.usedJSHeapSize,
        total: memory.totalJSHeapSize,
        limit: memory.jsHeapSizeLimit,
        usage: memory.usedJSHeapSize / memory.jsHeapSizeLimit,
      });
    }
  }, []);

  useEffect(() => {
    updateMemoryInfo();
    const interval = setInterval(updateMemoryInfo, 10000); // Update every 10 seconds
    return () => clearInterval(interval);
  }, [updateMemoryInfo]);

  return memoryInfo;
}

// Network status monitoring hook
export function useNetworkMonitor() {
  const [networkStatus, setNetworkStatus] = useState({
    online: true,
    connection: null as NetworkInformation | null,
  });

  useEffect(() => {
    const updateOnlineStatus = () => {
      setNetworkStatus(prev => ({ ...prev, online: navigator.onLine }));
    };

    const updateConnectionInfo = () => {
      if ('connection' in navigator) {
        const connection = (navigator as any).connection;
        setNetworkStatus(prev => ({ ...prev, connection }));
      }
    };

    // Initial status
    updateOnlineStatus();
    updateConnectionInfo();

    // Event listeners
    window.addEventListener('online', updateOnlineStatus);
    window.addEventListener('offline', updateOnlineStatus);

    if ('connection' in navigator) {
      (navigator as any).connection.addEventListener('change', updateConnectionInfo);
    }

    return () => {
      window.removeEventListener('online', updateOnlineStatus);
      window.removeEventListener('offline', updateOnlineStatus);

      if ('connection' in navigator) {
        (navigator as any).connection.removeEventListener('change', updateConnectionInfo);
      }
    };
  }, []);

  return networkStatus;
}

// Battery monitoring hook
export function useBatteryMonitor() {
  const [battery, setBattery] = useState({
    charging: true,
    chargingTime: 0,
    dischargingTime: Infinity,
    level: 1,
  });

  useEffect(() => {
    if ('getBattery' in navigator) {
      (navigator as any).getBattery().then((batteryManager: any) => {
        const updateBatteryInfo = () => {
          setBattery({
            charging: batteryManager.charging,
            chargingTime: batteryManager.chargingTime,
            dischargingTime: batteryManager.dischargingTime,
            level: batteryManager.level,
          });
        };

        updateBatteryInfo();

        batteryManager.addEventListener('chargingchange', updateBatteryInfo);
        batteryManager.addEventListener('chargingtimechange', updateBatteryInfo);
        batteryManager.addEventListener('dischargingtimechange', updateBatteryInfo);
        batteryManager.addEventListener('levelchange', updateBatteryInfo);
      });
    }
  }, []);

  return battery;
}

// Performance optimization hook
export function usePerformanceOptimization() {
  const memory = useMemoryMonitor();
  const network = useNetworkMonitor();
  const battery = useBatteryMonitor();

  // Adaptive loading based on conditions
  const shouldUseLazyLoading = memory.usage > 0.7 || battery.level < 0.2;
  const shouldReduceQuality = battery.level < 0.15 || network.connection?.effectiveType === 'slow-2g';
  const shouldDisableAnimations = battery.level < 0.1 || memory.usage > 0.8;

  return {
    shouldUseLazyLoading,
    shouldReduceQuality,
    shouldDisableAnimations,
    performanceMode: battery.level < 0.2 ? 'low-power' :
                     memory.usage > 0.7 ? 'memory-conserving' :
                     network.connection?.effectiveType === 'slow-2g' ? 'low-bandwidth' : 'normal',
  };
}

// Cache management utilities
export class WebCacheManager {
  private static instance: WebCacheManager;
  private cache: Map<string, any> = new Map();
  private maxSize = 50; // Maximum cache entries

  static getInstance(): WebCacheManager {
    if (!WebCacheManager.instance) {
      WebCacheManager.instance = new WebCacheManager();
    }
    return WebCacheManager.instance;
  }

  set(key: string, value: any, ttl?: number) {
    // Remove oldest entries if cache is full
    if (this.cache.size >= this.maxSize) {
      const firstKey = this.cache.keys().next().value;
      if (firstKey !== undefined) {
        this.cache.delete(firstKey);
      }
    }

    this.cache.set(key, {
      value,
      timestamp: Date.now(),
      ttl,
    });
  }

  get(key: string): any {
    const entry = this.cache.get(key);
    if (!entry) return null;

    // Check TTL
    if (entry.ttl && Date.now() - entry.timestamp > entry.ttl) {
      this.cache.delete(key);
      return null;
    }

    return entry.value;
  }

  clear() {
    this.cache.clear();
  }

  // Memory pressure cleanup
  cleanup() {
    // Remove expired entries
    const now = Date.now();
    for (const [key, entry] of this.cache.entries()) {
      if (entry.ttl && now - entry.timestamp > entry.ttl) {
        this.cache.delete(key);
      }
    }

    // If still high memory usage, clear oldest 50%
    if (this.cache.size > this.maxSize * 0.8) {
      const entriesToRemove = Math.floor(this.cache.size * 0.5);
      let removed = 0;
      for (const key of this.cache.keys()) {
        if (removed >= entriesToRemove) break;
        this.cache.delete(key);
        removed++;
      }
    }
  }
}
