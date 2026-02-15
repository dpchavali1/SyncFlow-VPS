'use client';

import { useEffect } from 'react';
import { usePathname } from 'next/navigation';
import { migrateE2EEKeysToIndexedDB } from '../lib/e2ee';

export function ServiceWorkerRegistration() {
  const pathname = usePathname();

  useEffect(() => {
    // SECURITY FIX: Migrate E2EE keys from localStorage to IndexedDB
    // This runs once on app startup to move keys to more secure storage
    if (typeof window !== 'undefined') {
      migrateE2EEKeysToIndexedDB().catch((error) => {
        console.error('[Security] Failed to migrate E2EE keys:', error);
      });
    }

    if (typeof window !== 'undefined' && 'serviceWorker' in navigator) {
      // CRITICAL: Do NOT register service worker on admin routes
      // Service worker intercepts Firebase requests and maintains background connections
      // This causes phantom pairing sessions and excessive bandwidth usage
      const isAdminRoute = pathname?.startsWith('/admin');

      if (isAdminRoute) {
        // Unregister any existing service workers on admin routes
        navigator.serviceWorker.getRegistrations().then((registrations) => {
          registrations.forEach((registration) => {
            registration.unregister();
          });
        });

        return;
      }

      // Register service worker ONLY on non-admin routes
      navigator.serviceWorker
        .register('/sw.js', { scope: '/' })
        .then((registration) => {
          // Handle updates
          registration.addEventListener('updatefound', () => {
            const newWorker = registration.installing;
            if (newWorker) {
              newWorker.addEventListener('statechange', () => {
                if (newWorker.state === 'installed' && navigator.serviceWorker.controller) {
                  // New version available - could show a notification to the user
                }
              });
            }
          });

          // Request notification permission
          if ('Notification' in window && Notification.permission === 'default') {
            Notification.requestPermission();
          }
        })
        .catch((error) => {
          console.error('[PWA] Service worker registration failed:', error);
        });

      // Handle PWA install prompt
      window.addEventListener('beforeinstallprompt', (e) => {
        // Stash the event so it can be triggered later via manual install button
        (window as any).deferredPrompt = e;
      });
    }
  }, [pathname]);

  return null;
}