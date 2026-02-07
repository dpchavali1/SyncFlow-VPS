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
        console.log('[PWA] Skipping service worker registration on admin route:', pathname);

        // Unregister any existing service workers on admin routes
        navigator.serviceWorker.getRegistrations().then((registrations) => {
          registrations.forEach((registration) => {
            console.log('[PWA] Unregistering service worker on admin route');
            registration.unregister();
          });
        });

        return;
      }

      // Register service worker ONLY on non-admin routes
      navigator.serviceWorker
        .register('/sw.js', { scope: '/' })
        .then((registration) => {
          console.log('[PWA] Service worker registered:', registration);

          // Handle updates
          registration.addEventListener('updatefound', () => {
            const newWorker = registration.installing;
            if (newWorker) {
              newWorker.addEventListener('statechange', () => {
                if (newWorker.state === 'installed' && navigator.serviceWorker.controller) {
                  // New version available
                  console.log('[PWA] New version available');
                  // You could show a notification to the user here
                }
              });
            }
          });

          // Request notification permission
          if ('Notification' in window && Notification.permission === 'default') {
            Notification.requestPermission().then((permission) => {
              console.log('[PWA] Notification permission:', permission);
            });
          }
        })
        .catch((error) => {
          console.error('[PWA] Service worker registration failed:', error);
        });

      // Handle PWA install prompt
      let deferredPrompt: any;

      window.addEventListener('beforeinstallprompt', (e) => {
        // Prevent the mini-infobar from appearing on mobile
        e.preventDefault();
        // Stash the event so it can be triggered later
        deferredPrompt = e;
        console.log('[PWA] Install prompt saved');
      });

      // Make deferredPrompt available globally for manual install trigger
      (window as any).deferredPrompt = deferredPrompt;
    }
  }, [pathname]);

  return null;
}