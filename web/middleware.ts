import { NextResponse } from 'next/server'
import type { NextRequest } from 'next/server'

/**
 * Middleware that generates a unique nonce per request for Content Security Policy.
 * This allows Next.js inline scripts (hydration, chunk loading) to execute
 * without requiring 'unsafe-inline' in the CSP.
 */
export function middleware(request: NextRequest) {
  const nonce = Buffer.from(crypto.randomUUID()).toString('base64')

  const isDev = process.env.NODE_ENV === 'development'

  const scriptSrc = isDev
    ? `'self' 'unsafe-inline' 'unsafe-eval' 'nonce-${nonce}' https://pagead2.googlesyndication.com https://www.googletagservices.com https://www.google.com`
    : `'self' 'nonce-${nonce}' https://pagead2.googlesyndication.com https://www.googletagservices.com https://www.google.com`

  const cspDirectives = [
    "default-src 'self'",
    "base-uri 'self'",
    "object-src 'none'",
    `script-src ${scriptSrc}`,
    `script-src-elem ${scriptSrc}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob: https://firebasestorage.googleapis.com https://*.googleusercontent.com https://pagead2.googlesyndication.com https://*.doubleclick.net https://*.google.com https://*.r2.cloudflarestorage.com",
    "font-src 'self' data:",
    `connect-src 'self' https://*.firebaseio.com wss://*.firebaseio.com https://*.googleapis.com https://firebasestorage.googleapis.com https://identitytoolkit.googleapis.com https://securetoken.googleapis.com https://www.googleapis.com https://*.cloudfunctions.net https://api.sfweb.app wss://api.sfweb.app https://pagead2.googlesyndication.com https://*.google.com https://*.doubleclick.net https://*.r2.cloudflarestorage.com${isDev ? ' http://localhost:4000 ws://localhost:4001 http://5.78.188.206 ws://5.78.188.206:3001' : ''}`,
    "media-src 'self' blob: https://firebasestorage.googleapis.com",
    "worker-src 'self' blob:",
    "frame-src 'self' https://pagead2.googlesyndication.com https://*.doubleclick.net https://www.google.com https://tpc.googlesyndication.com",
    "frame-ancestors 'self'",
    "form-action 'self'",
    "manifest-src 'self'",
  ].join('; ')

  // Set nonce in request headers so layout.tsx can read it
  const requestHeaders = new Headers(request.headers)
  requestHeaders.set('x-nonce', nonce)

  const response = NextResponse.next({
    request: { headers: requestHeaders },
  })

  // Set CSP header on the response
  response.headers.set('Content-Security-Policy', cspDirectives)

  return response
}

export const config = {
  matcher: [
    // Match all pages but skip static files and API routes
    {
      source: '/((?!api|_next/static|_next/image|favicon\\.png|icon-.*\\.png|manifest\\.json|sw\\.js|offline\\.html).*)',
      missing: [
        { type: 'header', key: 'next-router-prefetch' },
        { type: 'header', key: 'purpose', value: 'prefetch' },
      ],
    },
  ],
}
