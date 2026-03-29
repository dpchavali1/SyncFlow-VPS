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

  // CSP allows the app to work even when ad/analytics domains are blocked by corporate firewalls.
  // connect-src uses https: and wss: wildcards so self-hosted server URLs work without CSP changes.
  const cspDirectives = [
    "default-src 'self'",
    "base-uri 'self'",
    "object-src 'none'",
    `script-src ${scriptSrc}`,
    `script-src-elem ${scriptSrc}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob: https: http:",
    "font-src 'self' data:",
    `connect-src 'self' https: wss:${isDev ? ' http: ws:' : ''}`,
    "media-src 'self' blob: https:",
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
