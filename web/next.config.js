/** @type {import('next').NextConfig} */
// CSP is now handled dynamically via middleware.ts with per-request nonce
// to allow Next.js inline scripts without 'unsafe-inline'

const nextConfig = {
  output: 'standalone',
  reactStrictMode: true,

  // Image optimization
  images: {
    remotePatterns: [
      {
        protocol: 'https',
        hostname: 'firebasestorage.googleapis.com',
      },
      {
        protocol: 'https',
        hostname: '*.googleusercontent.com',
      },
    ],
    formats: ['image/avif', 'image/webp'],
  },

  // Production optimizations
  poweredByHeader: false,
  compress: true,

  // Security headers (CSP moved to middleware for nonce support)
  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          {
            key: 'X-DNS-Prefetch-Control',
            value: 'on',
          },
          {
            key: 'Strict-Transport-Security',
            value: 'max-age=63072000; includeSubDomains; preload',
          },
          {
            key: 'X-Frame-Options',
            value: 'SAMEORIGIN',
          },
          {
            key: 'X-Content-Type-Options',
            value: 'nosniff',
          },
          {
            key: 'X-XSS-Protection',
            value: '1; mode=block',
          },
          {
            key: 'Referrer-Policy',
            value: 'strict-origin-when-cross-origin',
          },
          {
            key: 'Permissions-Policy',
            value: 'camera=(self), microphone=(self), geolocation=()',
          },
        ],
      },
    ]
  },

  // Redirects for SEO
  async redirects() {
    return [
      {
        source: '/home',
        destination: '/',
        permanent: true,
      },
    ]
  },

  // Environment variables validation
  env: {
    NEXT_PUBLIC_APP_VERSION: process.env.npm_package_version || '1.0.0',
    NEXT_PUBLIC_SUPPORT_EMAIL: 'syncflow.contact@gmail.com',
    NEXT_PUBLIC_SUPPORT_EMAIL_SUBJECT: '[SyncFlow Web] Support Request',
    NEXT_PUBLIC_PRIVACY_URL: 'https://syncflow.app/privacy',
    NEXT_PUBLIC_TERMS_URL: 'https://syncflow.app/terms',
  },

  // Disable linting during build for now
  eslint: {
    ignoreDuringBuilds: false,
  },
  typescript: {
    ignoreBuildErrors: false,
  },

  // Experimental features for production
  experimental: {
    optimizePackageImports: ['lucide-react', 'date-fns', 'firebase', 'zustand', 'framer-motion'],
    // optimizeCss: true, // Disabled due to critters dependency issues
  },

  // Performance optimizations
  webpack: (config, { dev, isServer }) => {
    // Production optimizations
    if (!dev && !isServer) {
      // Enable webpack optimizations
      config.optimization = {
        ...config.optimization,
        splitChunks: {
          chunks: 'all',
          cacheGroups: {
            vendor: {
              test: /[\\/]node_modules[\\/]/,
              name: 'vendors',
              chunks: 'all',
            },
            firebase: {
              test: /[\\/]node_modules[\\/]firebase[\\/]/,
              name: 'firebase',
              chunks: 'all',
              priority: 10,
            },
          },
        },
      };
    }

    // Resolve web-vitals for client-side only
    if (!isServer) {
      config.resolve.fallback = {
        ...config.resolve.fallback,
        fs: false,
        path: false,
      };
    }

    return config;
  },
}

module.exports = nextConfig
