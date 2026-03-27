/**
 * License enforcement middleware for SyncFlow self-hosted deployments.
 *
 * Checks user and device counts against the current license limits.
 * Returns 403 with a helpful upgrade message when limits are exceeded.
 *
 * Usage:
 *   - Apply `checkUserLimit` to user-creation routes (auth.ts)
 *   - Apply `checkDeviceLimit` to device-registration routes (devices.ts)
 *   - Apply `checkFeature('feature_name')` to feature-gated routes
 */

import { Request, Response, NextFunction } from 'express';
import { getEffectiveLimits, isFeatureEnabled, getLicenseStatus } from '../services/license';
import { query } from '../services/database';
import { log } from '../services/logger';

// ---------------------------------------------------------------------------
// User limit check - apply to user creation endpoints
// ---------------------------------------------------------------------------

export async function checkUserLimit(
  req: Request,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const { maxUsers } = getEffectiveLimits();

    // Count current active users (exclude soft-deleted accounts)
    const result = await query<{ count: string }>(
      `SELECT COUNT(*) as count FROM users WHERE deletion_requested_at IS NULL`
    );

    const currentUsers = parseInt(result[0]?.count || '0', 10);

    if (currentUsers >= maxUsers) {
      const status = getLicenseStatus();
      log.warn('User limit reached', {
        currentUsers,
        maxUsers,
        plan: status.plan,
      });

      res.status(403).json({
        error: 'User limit reached',
        message: status.plan === 'community'
          ? `Community mode supports ${maxUsers} user(s). Add a license key to unlock more users. Visit https://syncflow.app/pricing for details.`
          : `Your ${status.plan} license supports up to ${maxUsers} users. Contact support@syncflow.app to upgrade.`,
        currentUsers,
        maxUsers,
        plan: status.plan,
      });
      return;
    }

    next();
  } catch (err) {
    // Fail open - don't block user creation if the check itself errors
    log.error('License user limit check failed (allowing request)', {
      error: (err as Error).message,
    });
    next();
  }
}

// ---------------------------------------------------------------------------
// Device limit check - apply to device registration endpoints
// ---------------------------------------------------------------------------

export async function checkDeviceLimit(
  req: Request,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const { maxDevices } = getEffectiveLimits();

    // Count total devices across all users
    const result = await query<{ count: string }>(
      `SELECT COUNT(*) as count FROM user_devices`
    );

    const currentDevices = parseInt(result[0]?.count || '0', 10);

    if (currentDevices >= maxDevices) {
      const status = getLicenseStatus();
      log.warn('Device limit reached', {
        currentDevices,
        maxDevices,
        plan: status.plan,
      });

      res.status(403).json({
        error: 'Device limit reached',
        message: status.plan === 'community'
          ? `Community mode supports ${maxDevices} device(s). Add a license key to unlock more devices. Visit https://syncflow.app/pricing for details.`
          : `Your ${status.plan} license supports up to ${maxDevices} devices. Contact support@syncflow.app to upgrade.`,
        currentDevices,
        maxDevices,
        plan: status.plan,
      });
      return;
    }

    next();
  } catch (err) {
    // Fail open - don't block device registration if the check itself errors
    log.error('License device limit check failed (allowing request)', {
      error: (err as Error).message,
    });
    next();
  }
}

// ---------------------------------------------------------------------------
// Feature gate - apply to specific feature endpoints
// ---------------------------------------------------------------------------

export function checkFeature(feature: string) {
  return (req: Request, res: Response, next: NextFunction): void => {
    if (!isFeatureEnabled(feature)) {
      const status = getLicenseStatus();

      res.status(403).json({
        error: 'Feature not available',
        message: status.plan === 'community'
          ? `The "${feature}" feature requires a license. Visit https://syncflow.app/pricing for details.`
          : `The "${feature}" feature is not included in your ${status.plan} plan. Contact support@syncflow.app to upgrade.`,
        feature,
        plan: status.plan,
      });
      return;
    }

    next();
  };
}
