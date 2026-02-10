import { Resend } from 'resend';
import { config } from '../config';

const resend = config.email.resendApiKey
  ? new Resend(config.email.resendApiKey)
  : null;

async function sendMail(subject: string, html: string) {
  if (!resend || !config.email.adminEmail) {
    console.log(`[Email] Skipping (not configured): ${subject}`);
    return;
  }
  try {
    const result = await resend.emails.send({
      from: 'SyncFlow Admin <noreply@resend.dev>',
      to: [config.email.adminEmail],
      subject,
      html,
    });
    if (result.error) {
      console.error(`[Email] Resend error for "${subject}":`, result.error);
    } else {
      console.log(`[Email] Sent: ${subject} (id: ${result.data?.id})`);
    }
  } catch (error) {
    console.error(`[Email] Failed to send "${subject}":`, error);
  }
}

export async function sendDeletionRequestEmail(
  userId: string,
  email: string | null,
  reason: string,
  scheduledDate: Date
) {
  await sendMail(
    `[SyncFlow] Account Deletion Requested`,
    `<h2>Account Deletion Request</h2>
     <p><strong>User ID:</strong> ${userId}</p>
     <p><strong>Email:</strong> ${email || 'N/A'}</p>
     <p><strong>Reason:</strong> ${reason}</p>
     <p><strong>Scheduled for:</strong> ${scheduledDate.toUTCString()}</p>
     <p>The account will be permanently deleted on the scheduled date unless cancelled.</p>`
  );
}

export async function sendDeletionCancelledEmail(
  userId: string,
  email: string | null
) {
  await sendMail(
    `[SyncFlow] Account Deletion Cancelled`,
    `<h2>Account Deletion Cancelled</h2>
     <p><strong>User ID:</strong> ${userId}</p>
     <p><strong>Email:</strong> ${email || 'N/A'}</p>
     <p>The user has cancelled their account deletion request.</p>`
  );
}

export async function sendCleanupReportEmail(
  results: Record<string, number>,
  e2eeResults: Record<string, number>,
  durationMs: number
) {
  const totalCleaned = Object.values(results).reduce((a, b) => a + b, 0);
  const totalE2ee = Object.values(e2eeResults).reduce((a, b) => a + b, 0);
  const timestamp = new Date().toLocaleString();

  const breakdownRows = Object.entries(results)
    .map(([key, val]) => `<tr><td style="padding:4px 12px;color:#94a3b8">${key}</td><td style="padding:4px 12px;font-weight:600">${val}</td></tr>`)
    .join('');

  const e2eeRows = Object.entries(e2eeResults)
    .filter(([_, val]) => val > 0)
    .map(([key, val]) => `<tr><td style="padding:4px 12px;color:#94a3b8">${key}</td><td style="padding:4px 12px;font-weight:600">${val}</td></tr>`)
    .join('');

  await sendMail(
    `[AUTO] SyncFlow VPS Cleanup Report - ${new Date().toLocaleDateString()}`,
    `<div style="font-family:sans-serif;max-width:600px;margin:0 auto;color:#e2e8f0;background:#1e293b;padding:24px;border-radius:12px">
      <h2 style="color:#60a5fa;margin-top:0">SyncFlow VPS Daily Cleanup Report</h2>
      <p style="color:#94a3b8">Report Generated: ${timestamp}</p>
      <p style="color:#94a3b8">Duration: ${(durationMs / 1000).toFixed(1)}s</p>

      <h3 style="color:#f8fafc;border-bottom:1px solid #334155;padding-bottom:8px">General Cleanup: ${totalCleaned} items removed</h3>
      <table style="width:100%;border-collapse:collapse">${breakdownRows}</table>

      <h3 style="color:#f8fafc;border-bottom:1px solid #334155;padding-bottom:8px;margin-top:20px">E2EE Key Cleanup: ${totalE2ee} items removed</h3>
      ${e2eeRows ? `<table style="width:100%;border-collapse:collapse">${e2eeRows}</table>` : '<p style="color:#94a3b8">All E2EE keys are clean</p>'}

      <hr style="border:none;border-top:1px solid #334155;margin:20px 0">
      <p style="color:#64748b;font-size:12px">Auto cleanup runs daily at 3 AM UTC.<br>SyncFlow VPS Admin System</p>
    </div>`
  );
}
