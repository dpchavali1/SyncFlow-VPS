// Service Worker for PWA functionality and offline support
// This file should be placed in the public directory

const CACHE_NAME = 'syncflow-v1.0.0';
const STATIC_CACHE = 'syncflow-static-v1.0.0';
const DYNAMIC_CACHE = 'syncflow-dynamic-v1.0.0';
const API_CACHE = 'syncflow-api-v1.0.0';

// Static assets to cache (only assets guaranteed to exist at these exact paths)
const STATIC_ASSETS = [
  '/manifest.json',
  '/favicon.ico',
  '/icon-192.png',
  '/icon-512.png',
];

// API endpoints to cache
const API_ENDPOINTS = [
  '/api/messages',
  '/api/contacts',
  '/api/user/status',
];

// Install event - cache static assets
self.addEventListener('install', (event) => {
  console.log('[SW] Installing service worker');
  event.waitUntil(
    caches.open(STATIC_CACHE).then((cache) => {
      console.log('[SW] Caching static assets');
      return cache.addAll(STATIC_ASSETS);
    }).catch((error) => {
      console.error('[SW] Failed to cache static assets:', error);
    })
  );
  self.skipWaiting();
});

// Activate event - clean up old caches
self.addEventListener('activate', (event) => {
  console.log('[SW] Activating service worker');
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      return Promise.all(
        cacheNames.map((cacheName) => {
          if (cacheName !== STATIC_CACHE && cacheName !== DYNAMIC_CACHE && cacheName !== API_CACHE) {
            console.log('[SW] Deleting old cache:', cacheName);
            return caches.delete(cacheName);
          }
        })
      );
    })
  );
  self.clients.claim();
});

// Fetch event - handle requests
self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // Skip non-GET requests
  if (request.method !== 'GET') return;

  // Skip Chrome extension requests
  if (url.protocol === 'chrome-extension:') return;

  // Skip third-party requests (ads, analytics, etc.) — let the browser handle them directly
  if (url.origin !== self.location.origin) return;

  // Handle API requests
  if (url.pathname.startsWith('/api/')) {
    event.respondWith(handleApiRequest(request));
    return;
  }

  // Handle static assets and pages
  event.respondWith(
    caches.match(request)
      .then((cachedResponse) => {
        if (cachedResponse) {
          return cachedResponse;
        }

        return fetch(request).then((response) => {
          // Cache successful responses
          if (response.status === 200 && response.type === 'basic') {
            const responseClone = response.clone();
            caches.open(DYNAMIC_CACHE).then((cache) => {
              cache.put(request, responseClone);
            });
          }
          return response;
        });
      })
      .catch(() => {
        // Return offline fallback for navigation requests
        if (request.mode === 'navigate') {
          return caches.match('/offline.html') || new Response('Offline', { status: 503 });
        }
      })
  );
});

// Handle API requests with background sync
async function handleApiRequest(request) {
  const url = new URL(request.url);

  // Try network first for API calls
  try {
    const response = await fetch(request);
    if (response.status === 200) {
      // Cache successful API responses briefly
      const cache = await caches.open(API_CACHE);
      const responseClone = response.clone();
      cache.put(request, responseClone);
    }
    return response;
  } catch (error) {
    // Network failed, try cache
    const cachedResponse = await caches.match(request);
    if (cachedResponse) {
      return cachedResponse;
    }

    // If no cache and it's a critical API, queue for retry
    if (url.pathname.includes('/messages') || url.pathname.includes('/send')) {
      self.registration.sync.register('background-sync-messages');
    }

    throw error;
  }
}

// Handle Firebase requests with special caching
async function handleFirebaseRequest(request) {
  // For Firebase requests, use a stale-while-revalidate strategy
  const cache = await caches.open('firebase-cache-v1');
  const cachedResponse = await cache.match(request);

  const fetchPromise = fetch(request).then((response) => {
    // Cache successful Firebase responses
    if (response.status === 200) {
      const responseClone = response.clone();
      cache.put(request, responseClone);
    }
    return response;
  });

  // Return cached version immediately if available, then update in background
  return cachedResponse || fetchPromise;
}

// Background sync for failed requests
self.addEventListener('sync', (event) => {
  console.log('[SW] Background sync triggered:', event.tag);

  if (event.tag === 'background-sync-messages') {
    event.waitUntil(retryFailedRequests());
  }
});

// Push notifications
self.addEventListener('push', (event) => {
  console.log('[SW] Push received:', event);

  if (event.data) {
    const data = event.data.json();
    const options = {
      body: data.body,
      icon: '/icon-192.png',
      badge: '/icon-192.png',
      vibrate: [200, 100, 200],
      data: data,
      actions: [
        {
          action: 'view',
          title: 'View Message',
        },
        {
          action: 'reply',
          title: 'Reply',
        },
      ],
    };

    event.waitUntil(
      self.registration.showNotification(data.title || 'SyncFlow', options)
    );
  }
});

// Notification click handling
self.addEventListener('notificationclick', (event) => {
  console.log('[SW] Notification clicked:', event);
  event.notification.close();

  if (event.action === 'reply') {
    // Open app and focus on reply
    event.waitUntil(
      clients.openWindow('/messages')
    );
  } else {
    // Default action - open app
    event.waitUntil(
      clients.openWindow('/')
    );
  }
});

// Periodic background sync for message updates
self.addEventListener('periodicsync', (event) => {
  console.log('[SW] Periodic sync:', event.tag);

  if (event.tag === 'message-sync') {
    event.waitUntil(syncMessages());
  }
});

// Retry failed requests
async function retryFailedRequests() {
  // Implementation would check for stored failed requests and retry them
  console.log('[SW] Retrying failed requests');
}

// Sync messages in background
async function syncMessages() {
  // Implementation would fetch new messages in background
  console.log('[SW] Background message sync');
}

// Message event handling (for communication with the app)
self.addEventListener('message', (event) => {
  console.log('[SW] Message received:', event.data);

  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }

  if (event.data && event.data.type === 'GET_VERSION') {
    event.ports[0].postMessage({ version: '1.0.0' });
  }
});