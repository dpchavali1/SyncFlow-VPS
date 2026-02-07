/**
 * Authentication and User Management Utilities
 *
 * Provides functions for user logout, data clearing, and cache management
 */

import { incrementalSyncManager } from './incrementalSync'
import vpsService from './vps'

/**
 * Clear user data and unpair device
 *
 * @param clearCache - If true, clears all cached data. If false, preserves cache
 *                     for potential re-pairing (bandwidth optimization).
 *
 * **Security Note:** When cache is preserved, the user ID is saved for verification.
 * If a different user pairs later, their cache will be cleared automatically to
 * prevent privacy violations.
 */
export async function unpairDevice(clearCache: boolean = false): Promise<void> {
  const currentUserId = localStorage.getItem('syncflow_user_id')
  const deviceId = localStorage.getItem('syncflow_device_id')

  // Remove device from VPS - broadcasts device_removed to Android
  if (deviceId) {
    try {
      await vpsService.removeDevice(deviceId)
      console.log('[Auth] Device removed from VPS')
    } catch (e) {
      console.error('[Auth] Failed to remove device from VPS:', e)
    }
  }

  if (clearCache && currentUserId) {
    console.log('[Auth] Clearing cache as requested')
    await incrementalSyncManager.clearCache(currentUserId)
    localStorage.removeItem('last_paired_user_id')
  } else if (currentUserId) {
    // Save user ID for verification on re-pair (security check)
    localStorage.setItem('last_paired_user_id', currentUserId)
  }

  // Clear pairing state
  localStorage.removeItem('syncflow_user_id')
  localStorage.removeItem('syncflow_device_id')
  localStorage.removeItem('syncflow_sync_group_id')

  console.log('[Auth] Device unpaired')
}

/**
 * Check if user is currently paired
 */
export function isPaired(): boolean {
  return !!localStorage.getItem('syncflow_user_id')
}

/**
 * Get current user ID
 */
export function getCurrentUserId(): string | null {
  return localStorage.getItem('syncflow_user_id')
}

/**
 * Get last paired user ID (for cache verification)
 */
export function getLastPairedUserId(): string | null {
  return localStorage.getItem('last_paired_user_id')
}
