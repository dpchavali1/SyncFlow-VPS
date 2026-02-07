import { NextRequest, NextResponse } from 'next/server'

/**
 * Automatic Daily Cleanup API Endpoint
 * Called by Vercel Cron (https://vercel.com/docs/cron-jobs)
 *
 * Vercel Cron sends GET requests to this endpoint
 * Schedule: 0 2 * * * (2 AM UTC daily)
 *
 * POST is available for manual triggers
 *
 * Calls VPS server POST /api/admin/cleanup/auto via server-to-server auth
 */

// Force dynamic to prevent build-time analysis
export const dynamic = 'force-dynamic'

const VPS_BASE_URL = process.env.NEXT_PUBLIC_VPS_URL || 'https://api.sfweb.app'
const ADMIN_API_TOKEN = process.env.ADMIN_API_TOKEN || ''

async function runCleanup(): Promise<NextResponse> {
  const startTime = Date.now()
  const timestamp = new Date().toISOString()

  console.log(`[AUTO] Starting automatic cleanup at ${timestamp}`)

  if (!ADMIN_API_TOKEN) {
    console.error('[AUTO] ADMIN_API_TOKEN not configured')
    return NextResponse.json(
      { success: false, error: 'ADMIN_API_TOKEN not configured', timestamp },
      { status: 500 }
    )
  }

  try {
    const response = await fetch(`${VPS_BASE_URL}/api/admin/cleanup/auto`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${ADMIN_API_TOKEN}`,
      },
    })

    if (!response.ok) {
      const errorText = await response.text()
      throw new Error(`VPS cleanup failed (${response.status}): ${errorText}`)
    }

    const cleanupResults = await response.json()

    const duration = Date.now() - startTime
    console.log(`[AUTO] Cleanup completed in ${duration}ms:`, cleanupResults)

    return NextResponse.json(
      {
        success: true,
        timestamp,
        type: 'AUTO',
        results: cleanupResults,
        durationMs: duration,
        message: `Automatic cleanup completed successfully in ${(duration / 1000).toFixed(1)}s`,
      },
      { status: 200 }
    )
  } catch (error: any) {
    const duration = Date.now() - startTime
    console.error(`[AUTO] Error in auto cleanup endpoint (${duration}ms):`, error)

    return NextResponse.json(
      {
        success: false,
        error: 'Cleanup failed',
        details: error.message,
        timestamp,
        durationMs: duration,
      },
      { status: 500 }
    )
  }
}

export async function POST(request: NextRequest) {
  return runCleanup()
}

// Vercel Cron calls GET
export async function GET(request: NextRequest) {
  return runCleanup()
}
