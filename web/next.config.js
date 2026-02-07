/** @type {import('next').NextConfig} */
// Content Security Policy - Allow 'unsafe-eval' in development for Next.js hot reloading
const isDev = process.env.NODE_ENV === 'development'
const scriptSrc = isDev
  ? "'self' 'unsafe-inline' 'unsafe-eval' https://pagead2.googlesyndication.com https://www.googletagservices.com https://www.google.com"
  : "'self' 'unsafe-inline' https://pagead2.googlesyndication.com https://www.googletagservices.com https://www.google.com"

const contentSecurityPolicy = [
  "default-src 'self'",
  "base-uri 'self'",
  "object-src 'none'",
  `script-src ${scriptSrc}`,
  `script-src-elem ${scriptSrc}`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https://firebasestorage.googleapis.com https://*.googleusercontent.com",
  "font-src 'self' data:",
  "connect-src 'self' https://*.firebaseio.com wss://*.firebaseio.com https://*.googleapis.com https://firebasestorage.googleapis.com https://identitytoolkit.googleapis.com https://securetoken.googleapis.com https://www.googleapis.com https://*.cloudfunctions.net http://api.sfweb.app https://api.sfweb.app ws://api.sfweb.app wss://api.sfweb.app http://5.78.188.206 ws://5.78.188.206:3001 http://localhost:4000 ws://localhost:4001",
  "media-src 'self' blob: https://firebasestorage.googleapis.com",
  "worker-src 'self' blob:",
  "frame-ancestors 'self'",
  "form-action 'self'",
  "manifest-src 'self'",
].join('; ')

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

  // Security headers
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
            key: 'Content-Security-Policy',
            value: contentSecurityPolicy,
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
    optimizePackageImports: ['lucide-react', 'date-fns', 'firebase', 'zustand'],
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
