'use client';

import { createContext, useContext, ReactNode } from 'react';
import { usePerformanceOptimization } from '../lib/performance';

interface PerformanceContextType {
  shouldUseLazyLoading: boolean;
  shouldReduceQuality: boolean;
  shouldDisableAnimations: boolean;
  performanceMode: string;
}

const PerformanceContext = createContext<PerformanceContextType>({
  shouldUseLazyLoading: false,
  shouldReduceQuality: false,
  shouldDisableAnimations: false,
  performanceMode: 'normal',
});

export function PerformanceProvider({ children }: { children: ReactNode }) {
  const performanceSettings = usePerformanceOptimization();

  return (
    <PerformanceContext.Provider value={performanceSettings}>
      {children}
    </PerformanceContext.Provider>
  );
}

export function usePerformance() {
  return useContext(PerformanceContext);
}

// Performance Monitor Component (for debugging)
export function PerformanceMonitor() {
  const { performanceMode, shouldDisableAnimations, shouldReduceQuality, shouldUseLazyLoading } = usePerformance();

  // Only show in development
  if (process.env.NODE_ENV === 'production') {
    return null;
  }

  return (
    <div className="fixed bottom-4 right-4 bg-black bg-opacity-75 text-white p-3 rounded-lg text-xs font-mono z-50">
      <div>Mode: {performanceMode}</div>
      <div>Animations: {shouldDisableAnimations ? 'OFF' : 'ON'}</div>
      <div>Quality: {shouldReduceQuality ? 'LOW' : 'HIGH'}</div>
      <div>Lazy: {shouldUseLazyLoading ? 'ON' : 'OFF'}</div>
    </div>
  );
}
