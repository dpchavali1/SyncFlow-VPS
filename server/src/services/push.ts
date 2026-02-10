import * as admin from 'firebase-admin';
import { config } from '../config';
import { query } from './database';

let fcmInitialized = false;

export function initializeFCM(): void {
  if (!config.fcm.serviceAccountPath) {
    console.warn('[FCM] No service account path configured — push notifications disabled');
    return;
  }

  try {
    admin.initializeApp({
      credential: admin.credential.cert(config.fcm.serviceAccountPath),
    });
    fcmInitialized = true;
    console.log('[FCM] Firebase Admin initialized');
  } catch (error) {
    console.error('[FCM] Failed to initialize Firebase Admin:', error);
  }
}

export function isFCMInitialized(): boolean {
  return fcmInitialized;
}

export async function sendOutgoingMessageNotification(
  userId: string,
  messageId: string,
  excludeDeviceId?: string | null
): Promise<void> {
  if (!fcmInitialized) {
    return;
  }

  const tokens = await query<{ token: string; platform: string; device_id: string | null }>(
    `SELECT token, platform, device_id
     FROM fcm_tokens
     WHERE uid = $1
       AND platform = 'android'
       AND ($2::text IS NULL OR device_id IS NULL OR device_id <> $2)`,
    [userId, excludeDeviceId ?? null]
  );

  if (tokens.length === 0) {
    return;
  }

  for (const { token } of tokens) {
    try {
      await admin.messaging().send({
        data: {
          type: 'outgoing_message',
          messageId,
        },
        android: {
          priority: 'high',
        },
        token,
      });
      console.log(`[FCM] Sent outgoing_message notification to token ${token.substring(0, 8)}...`);
    } catch (error: any) {
      if (
        error?.code === 'messaging/registration-token-not-registered' ||
        error?.code === 'messaging/invalid-registration-token'
      ) {
        await query(`DELETE FROM fcm_tokens WHERE token = $1`, [token]);
      } else {
        console.error(`[FCM] Failed to send outgoing_message to token ${token.substring(0, 8)}...:`, error);
      }
    }
  }
}

export async function sendCallRequestNotification(
  userId: string,
  requestId: string,
  phoneNumber: string,
  excludeDeviceId?: string | null
): Promise<void> {
  if (!fcmInitialized) {
    return;
  }

  const tokens = await query<{ token: string; platform: string; device_id: string | null }>(
    `SELECT token, platform, device_id
     FROM fcm_tokens
     WHERE uid = $1
       AND platform = 'android'
       AND ($2::text IS NULL OR device_id IS NULL OR device_id <> $2)`,
    [userId, excludeDeviceId ?? null]
  );

  if (tokens.length === 0) {
    return;
  }

  for (const { token } of tokens) {
    try {
      await admin.messaging().send({
        data: {
          type: 'call_request',
          requestId,
          phoneNumber,
        },
        android: {
          priority: 'high',
        },
        token,
      });
      console.log(`[FCM] Sent call_request notification to token ${token.substring(0, 8)}...`);
    } catch (error: any) {
      if (
        error?.code === 'messaging/registration-token-not-registered' ||
        error?.code === 'messaging/invalid-registration-token'
      ) {
        await query(`DELETE FROM fcm_tokens WHERE token = $1`, [token]);
      } else {
        console.error(`[FCM] Failed to send call_request to token ${token.substring(0, 8)}...:`, error);
      }
    }
  }
}

export async function sendCallNotification(
  userId: string,
  payload: { callId: string; callerName: string; callType: string },
  excludeDeviceId?: string | null
): Promise<void> {
  if (!fcmInitialized) {
    console.warn('[FCM] Not initialized — skipping push notification');
    return;
  }

  const tokens = await query<{ token: string; platform: string; device_id: string | null }>(
    `SELECT token, platform, device_id
     FROM fcm_tokens
     WHERE uid = $1
       AND ($2::text IS NULL OR device_id IS NULL OR device_id <> $2)`,
    [userId, excludeDeviceId ?? null]
  );

  if (tokens.length === 0) {
    console.log(`[FCM] No tokens found for user ${userId}`);
    return;
  }

  for (const { token } of tokens) {
    try {
      await admin.messaging().send({
        data: {
          type: 'syncflow_call',
          callId: payload.callId,
          callerName: payload.callerName,
          callType: payload.callType,
        },
        android: {
          priority: 'high',
        },
        token,
      });
      console.log(`[FCM] Sent call notification to token ${token.substring(0, 8)}...`);
    } catch (error: any) {
      if (
        error?.code === 'messaging/registration-token-not-registered' ||
        error?.code === 'messaging/invalid-registration-token'
      ) {
        console.log(`[FCM] Removing stale token ${token.substring(0, 8)}...`);
        await query(`DELETE FROM fcm_tokens WHERE token = $1`, [token]);
      } else {
        console.error(`[FCM] Failed to send to token ${token.substring(0, 8)}...:`, error);
      }
    }
  }
}
