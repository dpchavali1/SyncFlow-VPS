import { NextRequest, NextResponse } from 'next/server'
import { Resend } from 'resend'

const resend = new Resend(process.env.RESEND_API_KEY!)
const ADMIN_EMAIL = process.env.ADMIN_EMAIL || 'syncflow.contact@gmail.com'

// Generate detailed cleanup report (text format)
function generateCleanupReport(cleanupStats: any, userId?: string): string {
  const timestamp = new Date().toLocaleString()
  const totalCleaned = Object.values(cleanupStats).reduce((sum: number, count: any) => sum + count, 0)

  return `
SYNCFLOW AUTO CLEANUP REPORT
============================

Report Generated: ${timestamp}
Cleaned By: ${userId || 'System Admin'}

CLEANUP STATISTICS
==================
Total Records Cleaned: ${totalCleaned}

Detailed Breakdown:
• Stale Outgoing Messages: ${cleanupStats.outgoingMessages || 0}
• Expired Pairings: ${cleanupStats.pendingPairings || 0}
• Old Call Requests: ${cleanupStats.callRequests || 0}
• Old Spam Messages: ${cleanupStats.spamMessages || 0}
• Old Read Receipts: ${cleanupStats.readReceipts || 0}
• Inactive Devices: ${cleanupStats.oldDevices || 0}
• Old Notifications: ${cleanupStats.oldNotifications || 0}
• Stale Typing Indicators: ${cleanupStats.staleTypingIndicators || 0}
• Expired Sessions: ${cleanupStats.expiredSessions || 0}
• Old File Transfers: ${cleanupStats.oldFileTransfers || 0}
• Abandoned Pairings: ${cleanupStats.abandonedPairings || 0}
• Orphaned Media: ${cleanupStats.orphanedMedia || 0}

IMPACT ANALYSIS
===============
Storage Freed: Approximately ${Math.round(totalCleaned * 0.001)} MB
Database Optimization: ${totalCleaned} records removed
Performance Improvement: Enhanced system efficiency

COST SAVINGS PROJECTION
=======================
Monthly Database Cost Reduction: $${(totalCleaned * 0.000001).toFixed(4)}
Annual Savings: $${(totalCleaned * 0.000001 * 12).toFixed(2)}

NEXT CLEANUP SCHEDULED
======================
Auto cleanup runs every 24 hours or on-demand via admin panel.

---
SyncFlow Admin System
Automated Maintenance Report
`
}

export async function POST(request: NextRequest) {
  try {
    const { cleanupStats, userId, type = 'MANUAL' } = await request.json()

    console.log('Server: Processing cleanup report request')
    console.log('Server: Cleanup type:', type)
    console.log('Server: RESEND_API_KEY available:', !!process.env.RESEND_API_KEY)
    console.log('Server: RESEND_API_KEY length:', process.env.RESEND_API_KEY?.length)
    console.log('Server: ADMIN_EMAIL:', process.env.ADMIN_EMAIL)

    if (!process.env.RESEND_API_KEY) {
      console.log('Server: RESEND_API_KEY not configured')
      return NextResponse.json({
        success: false,
        error: 'Email service not configured on server'
      })
    }

    // Test the API key by trying to get domains
    try {
      console.log('Server: Testing Resend API key...')
      const testResult = await resend.domains.list()
      console.log('Server: API key test successful, response:', testResult)
    } catch (testError) {
      console.error('Server: API key test failed:', testError)
      return NextResponse.json({
        success: false,
        error: `Invalid API key: ${testError}`
      }, { status: 500 })
    }

    const report = generateCleanupReport(cleanupStats, userId)

    console.log('Server: Sending email via Resend...')

    const emailData = {
      from: 'SyncFlow Admin <noreply@resend.dev>',
      to: [ADMIN_EMAIL],
      subject: `[${type}] SyncFlow Cleanup Report - ${new Date().toLocaleDateString()}`,
      text: report,
      html: report.replace(/\n/g, '<br>').replace(/=/g, '<hr style="border: none; border-top: 1px solid #ccc; margin: 10px 0;">')
    }

    const result = await resend.emails.send(emailData)

    console.log('Server: Full Resend response:', result)
    console.log('Server: Email sent to:', ADMIN_EMAIL)

    // Check if the response indicates success
    if (result.error) {
      console.error('Server: Resend returned error:', result.error)
      return NextResponse.json({
        success: false,
        error: `Resend API error: ${result.error.message || 'Unknown error'}`
      }, { status: 500 })
    }

    return NextResponse.json({
      success: true,
      messageId: result.data?.id || 'sent'
    })

  } catch (error) {
    console.error('Server: Email sending failed:', error)
    return NextResponse.json({
      success: false,
      error: `Email sending failed: ${error}`
    }, { status: 500 })
  }
}