/**
 * License validation service for SyncFlow self-hosted deployments.
 *
 * License format (v1 - simple):
 *   Base64-encoded JSON string containing license fields + HMAC-SHA256 signature.
 *   The license key env var is: LICENSE_KEY
 *
 * Plans:
 *   - (no key)            "community"          1 user, 2 devices, basic features
 *   - self_hosted          "self_hosted"        5 users, 10 devices, all features
 *   - self_hosted_family   "self_hosted_family" 20 users, 50 devices, all features
 *
 * In the future, Ed25519 signature verification will replace the HMAC approach.
 */

import { createHmac } from 'node:crypto';
import { log } from './logger';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface License {
  licenseId: string;
  email: string;
  plan: 'self_hosted' | 'self_hosted_family';
  issuedAt: string;
  expiresAt: string;
  maxUsers: number;
  maxDevices: number;
  features: string[];
  signature?: string;
}

export interface LicenseStatus {
  valid: boolean;
  plan: string;
  expires: string;
  maxUsers: number;
  maxDevices: number;
  features: string[];
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const COMMUNITY_LIMITS = {
  maxUsers: 1,
  maxDevices: 2,
  features: [
    'messaging',
    'contacts_sync',
    'call_history',
    'clipboard_sync',
    'file_transfer',
  ],
} as const;

const PLAN_DEFAULTS: Record<License['plan'], { maxUsers: number; maxDevices: number; features: string[] }> = {
  self_hosted: {
    maxUsers: 5,
    maxDevices: 10,
    features: [
      'messaging',
      'contacts_sync',
      'call_history',
      'clipboard_sync',
      'file_transfer',
      'e2ee',
      'video_calls',
      'screen_share',
      'photo_sync',
      'voicemail_sync',
      'notification_mirror',
      'scheduled_messages',
      'spam_filter',
      'ai_assistant',
      'dnd_sync',
      'media_control',
    ],
  },
  self_hosted_family: {
    maxUsers: 20,
    maxDevices: 50,
    features: [
      'messaging',
      'contacts_sync',
      'call_history',
      'clipboard_sync',
      'file_transfer',
      'e2ee',
      'video_calls',
      'screen_share',
      'photo_sync',
      'voicemail_sync',
      'notification_mirror',
      'scheduled_messages',
      'spam_filter',
      'ai_assistant',
      'dnd_sync',
      'media_control',
      'multi_phone',
      'priority_support',
    ],
  },
};

// Public key for HMAC verification (in future: Ed25519 public key)
// This is intentionally a fixed value that ships with the source - the secret
// signing key is held server-side at license.syncflow.app.
const HMAC_VERIFY_KEY = 'syncflow-license-v1-public-verification-key';

// Heartbeat interval: once every 24 hours
const HEARTBEAT_INTERVAL_MS = 24 * 60 * 60 * 1000;

// License server endpoint
const LICENSE_SERVER = 'https://license.syncflow.app';

// ---------------------------------------------------------------------------
// Module state
// ---------------------------------------------------------------------------

let cachedLicense: License | null = null;
let licenseValidated = false;
let heartbeatTimer: ReturnType<typeof setInterval> | null = null;

// ---------------------------------------------------------------------------
// Core functions
// ---------------------------------------------------------------------------

/**
 * Decode and validate the LICENSE_KEY environment variable.
 * Returns the parsed License object if valid, null otherwise.
 */
export function validateLicense(): License | null {
  // Support both env var names for flexibility
  const licenseKey = process.env.LICENSE_KEY || process.env.SYNCFLOW_LICENSE_KEY;

  if (!licenseKey) {
    log.info('No LICENSE_KEY set - running in community mode', {
      maxUsers: COMMUNITY_LIMITS.maxUsers,
      maxDevices: COMMUNITY_LIMITS.maxDevices,
    });
    cachedLicense = null;
    licenseValidated = true;
    return null;
  }

  try {
    // Decode base64
    const decoded = Buffer.from(licenseKey, 'base64').toString('utf-8');
    const parsed = JSON.parse(decoded) as License & { signature: string };

    // Extract and remove signature for verification
    const { signature, ...licenseData } = parsed;

    if (!signature) {
      log.warn('License key is missing signature - running in community mode');
      cachedLicense = null;
      licenseValidated = true;
      return null;
    }

    // Verify HMAC signature
    const payload = JSON.stringify(licenseData, Object.keys(licenseData).sort());
    const expectedSig = createHmac('sha256', HMAC_VERIFY_KEY)
      .update(payload)
      .digest('hex');

    if (signature !== expectedSig) {
      log.warn('License key signature verification failed - running in community mode');
      cachedLicense = null;
      licenseValidated = true;
      return null;
    }

    // Validate required fields
    if (!licenseData.licenseId || !licenseData.email || !licenseData.plan || !licenseData.expiresAt) {
      log.warn('License key is missing required fields - running in community mode');
      cachedLicense = null;
      licenseValidated = true;
      return null;
    }

    // Validate plan type
    if (!['self_hosted', 'self_hosted_family'].includes(licenseData.plan)) {
      log.warn('License key has unknown plan type', { plan: licenseData.plan });
      cachedLicense = null;
      licenseValidated = true;
      return null;
    }

    // Check expiration
    const expiresAt = new Date(licenseData.expiresAt);
    if (isNaN(expiresAt.getTime())) {
      log.warn('License key has invalid expiration date');
      cachedLicense = null;
      licenseValidated = true;
      return null;
    }

    if (expiresAt < new Date()) {
      log.warn('License key has expired', {
        licenseId: licenseData.licenseId,
        expiredAt: licenseData.expiresAt,
      });
      cachedLicense = null;
      licenseValidated = true;
      return null;
    }

    // Merge plan defaults with any overrides from the license
    const planDefaults = PLAN_DEFAULTS[licenseData.plan as License['plan']];
    const license: License = {
      ...licenseData,
      plan: licenseData.plan as License['plan'],
      maxUsers: licenseData.maxUsers || planDefaults.maxUsers,
      maxDevices: licenseData.maxDevices || planDefaults.maxDevices,
      features: licenseData.features?.length > 0 ? licenseData.features : planDefaults.features,
    };

    cachedLicense = license;
    licenseValidated = true;

    log.info('License validated successfully', {
      licenseId: license.licenseId,
      plan: license.plan,
      email: license.email,
      maxUsers: license.maxUsers,
      maxDevices: license.maxDevices,
      expiresAt: license.expiresAt,
      featureCount: license.features.length,
    });

    return license;
  } catch (err) {
    log.warn('Failed to parse license key - running in community mode', {
      error: (err as Error).message,
    });
    cachedLicense = null;
    licenseValidated = true;
    return null;
  }
}

/**
 * Check if a valid (non-expired) license is currently loaded.
 */
export function isLicenseValid(): boolean {
  if (!licenseValidated) {
    validateLicense();
  }

  if (!cachedLicense) return false;

  const expiresAt = new Date(cachedLicense.expiresAt);
  return expiresAt > new Date();
}

/**
 * Get the current license status for the /health endpoint and admin UI.
 */
export function getLicenseStatus(): LicenseStatus {
  if (!licenseValidated) {
    validateLicense();
  }

  if (!cachedLicense) {
    return {
      valid: false,
      plan: 'community',
      expires: 'n/a',
      maxUsers: COMMUNITY_LIMITS.maxUsers,
      maxDevices: COMMUNITY_LIMITS.maxDevices,
      features: [...COMMUNITY_LIMITS.features],
    };
  }

  const expiresAt = new Date(cachedLicense.expiresAt);
  const valid = expiresAt > new Date();

  return {
    valid,
    plan: valid ? cachedLicense.plan : 'community',
    expires: cachedLicense.expiresAt,
    maxUsers: valid ? cachedLicense.maxUsers : COMMUNITY_LIMITS.maxUsers,
    maxDevices: valid ? cachedLicense.maxDevices : COMMUNITY_LIMITS.maxDevices,
    features: valid ? cachedLicense.features : [...COMMUNITY_LIMITS.features],
  };
}

/**
 * Get the effective limits (respects license or falls back to community).
 */
export function getEffectiveLimits(): { maxUsers: number; maxDevices: number } {
  const status = getLicenseStatus();
  return { maxUsers: status.maxUsers, maxDevices: status.maxDevices };
}

/**
 * Check if a specific feature is enabled under the current license.
 */
export function isFeatureEnabled(feature: string): boolean {
  const status = getLicenseStatus();
  return status.features.includes(feature);
}

/**
 * Privacy-respecting daily heartbeat to the license server.
 *
 * Sends only:
 *   - licenseId (for lookup)
 *   - current user count (for analytics)
 *   - server version
 *
 * Does NOT send: IP addresses, user data, message counts, or any PII.
 * Heartbeat failure is non-fatal and does not affect operation.
 */
export function startLicenseHeartbeat(): void {
  if (!cachedLicense) {
    log.debug('Skipping license heartbeat - no license key configured');
    return;
  }

  const sendHeartbeat = async () => {
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 10000);

      await fetch(`${LICENSE_SERVER}/api/heartbeat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          licenseId: cachedLicense!.licenseId,
          version: process.env.npm_package_version || '1.0.0',
          plan: cachedLicense!.plan,
          ts: new Date().toISOString(),
        }),
        signal: controller.signal,
      });

      clearTimeout(timeout);
      log.debug('License heartbeat sent successfully');
    } catch {
      // Heartbeat failure is non-fatal - just log at debug level
      log.debug('License heartbeat failed (non-fatal)');
    }
  };

  // Send initial heartbeat after a short delay (don't block startup)
  setTimeout(sendHeartbeat, 30000);

  // Then send every 24 hours
  heartbeatTimer = setInterval(sendHeartbeat, HEARTBEAT_INTERVAL_MS);

  log.info('License heartbeat scheduled (every 24h)');
}

/**
 * Stop the heartbeat timer (for graceful shutdown).
 */
export function stopLicenseHeartbeat(): void {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer);
    heartbeatTimer = null;
  }
}
