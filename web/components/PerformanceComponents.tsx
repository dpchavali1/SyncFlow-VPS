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

// Optimized Image Component
interface OptimizedImageProps {
  src: string;
  alt: string;
  width?: number;
  height?: number;
  className?: string;
  priority?: boolean;
}

export function OptimizedImage({
  src,
  alt,
  width,
  height,
  className,
  priority = false
}: OptimizedImageProps) {
  const { shouldReduceQuality, shouldUseLazyLoading } = usePerformance();

  // Reduce quality for low-power devices
  const quality = shouldReduceQuality ? 75 : 90;

  // Use lazy loading unless priority is set
  const loading = priority ? 'eager' : shouldUseLazyLoading ? 'lazy' : 'eager';

  return (
    <img
      src={src}
      alt={alt}
      width={width}
      height={height}
      className={className}
      loading={loading}
      decoding="async"
      style={{
        imageRendering: shouldReduceQuality ? 'auto' : 'auto',
      }}
    />
  );
}

// Optimized Animation Component
interface OptimizedAnimationProps {
  children: ReactNode;
  className?: string;
  disabled?: boolean;
}

export function OptimizedAnimation({
  children,
  className,
  disabled
}: OptimizedAnimationProps) {
  const { shouldDisableAnimations } = usePerformance();

  if (shouldDisableAnimations || disabled) {
    return (
      <div className={className}>
        {children}
      </div>
    );
  }

  return (
    <div className={className}>
      {children}
    </div>
  );
}

// Virtualized List Component for better performance with large lists
interface VirtualizedListProps<T> {
  items: T[];
  itemHeight: number;
  containerHeight: number;
  renderItem: (item: T, index: number) => ReactNode;
  className?: string;
}

export function VirtualizedList<T>({
  items,
  itemHeight,
  containerHeight,
  renderItem,
  className = ''
}: VirtualizedListProps<T>) {
  const { shouldUseLazyLoading } = usePerformance();

  // Simple virtualization - only render visible items
  const visibleItems = shouldUseLazyLoading ? Math.min(items.length, 20) : items.length;

  return (
    <div
      className={`overflow-auto ${className}`}
      style={{ height: containerHeight }}
    >
      <div style={{ height: items.length * itemHeight, position: 'relative' }}>
        {items.slice(0, visibleItems).map((item, index) => (
          <div
            key={index}
            style={{
              position: 'absolute',
              top: index * itemHeight,
              height: itemHeight,
              width: '100%',
            }}
          >
            {renderItem(item, index)}
          </div>
        ))}
        {shouldUseLazyLoading && visibleItems < items.length && (
          <div className="p-4 text-center text-gray-500">
            And {items.length - visibleItems} more items...
          </div>
        )}
      </div>
    </div>
  );
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
