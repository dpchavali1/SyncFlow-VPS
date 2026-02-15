import type { Metadata, Viewport } from 'next'
import { Inter } from 'next/font/google'
import Script from 'next/script'
import { Suspense } from 'react'
import { ServiceWorkerRegistration } from '../components/ServiceWorkerRegistration'
import { PerformanceProvider, PerformanceMonitor } from '../components/PerformanceComponents'
import { ErrorBoundary } from '../components/ErrorBoundary'
import SupportChat from '../components/SupportChat'
import AnalyticsTracker from '../components/AnalyticsTracker'
import './globals.css'

const inter = Inter({ subsets: ['latin'] })

// Force all pages to be dynamic (prevents build-time Firebase initialization)
export const dynamic = 'force-dynamic'

export const metadata: Metadata = {
  title: 'SyncFlow - Desktop SMS Integration',
  description: 'Access your phone messages from your desktop',
  manifest: '/manifest.json',
  appleWebApp: {
    statusBarStyle: 'default',
    title: 'SyncFlow',
  },
  other: {
    'mobile-web-app-capable': 'yes',
  },
}

export const viewport: Viewport = {
  themeColor: '#0ea5e9',
  width: 'device-width',
  initialScale: 1,
  maximumScale: 1,
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="en">
      <head>
        <link rel="icon" href="/favicon.png" type="image/png" />
        <link rel="apple-touch-icon" href="/icon-192.png" />
      </head>
      <body className={inter.className}>
        <ErrorBoundary>
          <PerformanceProvider>
            <ServiceWorkerRegistration />
            <Suspense><AnalyticsTracker /></Suspense>
            {children}
            <PerformanceMonitor />
          </PerformanceProvider>
          <SupportChat />
        </ErrorBoundary>
        <Script
          async
          src="https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-4962910048695842"
          crossOrigin="anonymous"
          strategy="afterInteractive"
        />
      </body>
    </html>
  )
}
