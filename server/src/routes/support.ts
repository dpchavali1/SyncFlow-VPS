import { Router, Request, Response } from 'express';
import { query, queryOne } from '../services/database';
import { optionalAuth } from '../middleware/auth';

const router = Router();

// POST /chat - Handle support chat message (web client)
router.post('/chat', optionalAuth, handleChat);
// POST / - Handle support chat message (Mac client hits /api/support-chat)
router.post('/', optionalAuth, handleChat);

async function handleChat(req: Request, res: Response) {
  try {
    const { message, syncGroupUserId } = req.body;

    if (!message || typeof message !== 'string') {
      res.status(400).json({ error: 'Message is required' });
      return;
    }

    const msg = message.toLowerCase().trim();
    // Prefer JWT-authenticated userId, fallback to body param
    const userId = req.userId || syncGroupUserId || null;
    const queryType = detectUserQueryType(msg);

    let response: string;
    switch (queryType) {
      case 'recovery_code':
        response = handleRecoveryCodeQuery();
        break;
      case 'user_id':
        response = handleUserIdQuery(userId);
        break;
      case 'data_usage':
        response = await handleDataUsageQuery(userId);
        break;
      case 'subscription':
        response = await handleSubscriptionQuery(userId);
        break;
      case 'account_info':
        response = await handleAccountInfoQuery(userId);
        break;
      case 'device_info':
        response = await handleDeviceInfoQuery(userId);
        break;
      case 'sync_status':
        response = await handleSyncStatusQuery(userId);
        break;
      case 'message_stats':
        response = await handleMessageStatsQuery(userId);
        break;
      case 'unpair_device':
        response = await handleUnpairDeviceQuery(userId, message);
        break;
      case 'reset_sync':
        response = handleResetSyncQuery();
        break;
      case 'delete_account':
        response = handleDeleteAccountQuery();
        break;
      case 'regenerate_recovery':
        response = handleRegenerateRecoveryQuery();
        break;
      case 'billing_history':
        response = await handleBillingHistoryQuery(userId);
        break;
      case 'cancel_subscription':
        response = handleCancelSubscriptionQuery();
        break;
      case 'spam_settings':
        response = handleSpamSettingsQuery();
        break;
      case 'spam_stats':
        response = await handleSpamStatsQuery(userId);
        break;
      case 'clipboard':
        response = handleClipboardQuery();
        break;
      case 'dnd':
        response = handleDndQuery();
        break;
      case 'media_control':
        response = handleMediaControlQuery();
        break;
      case 'hotspot':
        response = handleHotspotQuery();
        break;
      case 'find_phone':
        response = handleFindPhoneQuery();
        break;
      case 'shared_links':
        response = handleSharedLinksQuery();
        break;
      case 'phone_status':
        response = handlePhoneStatusQuery();
        break;
      case 'read_receipts':
        response = handleReadReceiptsQuery();
        break;
      case 'typing_indicators':
        response = handleTypingIndicatorsQuery();
        break;
      case 'scheduled_messages':
        response = handleScheduledMessagesQuery();
        break;
      case 'notifications':
        response = handleNotificationsQuery();
        break;
      case 'voicemail':
        response = handleVoicemailQuery();
        break;
      case 'delivery_status':
        response = handleDeliveryStatusQuery();
        break;
      case 'photo_sync':
        response = handlePhotoSyncQuery();
        break;
      case 'help':
        response = getHelpResponse();
        break;
      default:
        response = getGeneralResponse(msg);
        break;
    }

    res.json({ success: true, response });
  } catch (error) {
    console.error('[Support] Chat error:', error);
    res.json({
      success: false,
      response: "I'm sorry, I encountered an error processing your request. Please try again or contact support at syncflow.contact@gmail.com",
    });
  }
}

// ---------------------------------------------------------------------------
// QUERY TYPE DETECTION
// ---------------------------------------------------------------------------

function detectUserQueryType(msg: string): string {
  // Regenerate recovery code (check before recovery_code)
  if ((msg.includes('regenerate') || msg.includes('new') || msg.includes('reset')) &&
    msg.includes('recovery') && (msg.includes('code') || msg.includes('key'))) {
    return 'regenerate_recovery';
  }

  // Recovery code
  if (msg.includes('recovery') && (msg.includes('code') || msg.includes('key'))) return 'recovery_code';
  if (msg.includes('backup code') || msg.includes('restore code')) return 'recovery_code';

  // User ID
  if (msg.includes('user id') || msg.includes('userid') || msg.includes('my id') || msg.includes('account id')) return 'user_id';

  // Sync status
  if (msg.includes('sync') && (msg.includes('status') || msg.includes('last') || msg.includes('when'))) return 'sync_status';
  if (msg.includes('last sync') || msg.includes('sync error') || msg.includes('sync fail')) return 'sync_status';

  // Reset sync
  if ((msg.includes('reset') || msg.includes('clear')) && msg.includes('sync')) return 'reset_sync';

  // Message stats
  if (msg.includes('message') && (msg.includes('count') || msg.includes('total') || msg.includes('how many') || msg.includes('stats'))) return 'message_stats';
  if (msg.includes('how many messages')) return 'message_stats';

  // Unpair device
  if (msg.includes('unpair')) return 'unpair_device';
  if ((msg.includes('remove') || msg.includes('disconnect')) && msg.includes('device')) return 'unpair_device';

  // Delete account
  if (msg.includes('delete') && (msg.includes('account') || msg.includes('my data'))) return 'delete_account';

  // Billing
  if (msg.includes('billing') || msg.includes('payment') || msg.includes('invoice') || msg.includes('receipt')) return 'billing_history';

  // Cancel subscription
  if (msg.includes('cancel') && (msg.includes('subscription') || msg.includes('plan') || msg.includes('pro'))) return 'cancel_subscription';

  // Spam settings
  if (msg.includes('spam') && (msg.includes('setting') || msg.includes('filter') || msg.includes('config'))) return 'spam_settings';

  // Spam stats
  if (msg.includes('spam') && (msg.includes('blocked') || msg.includes('count') || msg.includes('how many') || msg.includes('stats'))) return 'spam_stats';

  // Clipboard sync
  if (msg.includes('clipboard') || msg.includes('copy paste') || msg.includes('copy/paste') || msg.includes('copy and paste')) return 'clipboard';

  // DND sync
  if (msg.includes('dnd') || msg.includes('do not disturb') || msg.includes('silent') || msg.includes('focus mode')) return 'dnd';

  // Media control
  if (msg.includes('media') || msg.includes('music') || msg.includes('playback') || msg.includes('play pause') || msg.includes('volume control')) return 'media_control';

  // Hotspot
  if (msg.includes('hotspot') || msg.includes('tethering') || msg.includes('mobile hotspot')) return 'hotspot';

  // Find phone
  if (msg.includes('find') && msg.includes('phone')) return 'find_phone';
  if (msg.includes('ring') && (msg.includes('phone') || msg.includes('device'))) return 'find_phone';
  if (msg.includes('locate') && msg.includes('phone')) return 'find_phone';

  // Shared links
  if (msg.includes('share') && (msg.includes('link') || msg.includes('url') || msg.includes('webpage'))) return 'shared_links';
  if (msg.includes('send') && (msg.includes('link') || msg.includes('url'))) return 'shared_links';

  // Phone status / battery
  if (msg.includes('battery') || msg.includes('signal') || msg.includes('charging')) return 'phone_status';
  if (msg.includes('phone') && (msg.includes('status') || msg.includes('info'))) return 'phone_status';

  // Read receipts
  if (msg.includes('read receipt') || msg.includes('read status')) return 'read_receipts';
  if (msg.includes('mark') && msg.includes('read')) return 'read_receipts';

  // Typing indicators
  if (msg.includes('typing') && (msg.includes('indicator') || msg.includes('bubble') || msg.includes('status'))) return 'typing_indicators';

  // Scheduled messages
  if (msg.includes('schedule') && msg.includes('message')) return 'scheduled_messages';
  if (msg.includes('send later') || msg.includes('delayed') || msg.includes('timed message')) return 'scheduled_messages';

  // Notification mirroring
  if (msg.includes('notification') && (msg.includes('mirror') || msg.includes('sync') || msg.includes('show') || msg.includes('see'))) return 'notifications';
  if (msg.includes('phone notification')) return 'notifications';

  // Voicemail
  if (msg.includes('voicemail') || msg.includes('voice mail') || msg.includes('transcri')) return 'voicemail';

  // Delivery status
  if (msg.includes('delivery') || msg.includes('delivered') || msg.includes('checkmark') || msg.includes('check mark')) return 'delivery_status';
  if (msg.includes('sent') && (msg.includes('status') || msg.includes('confirm'))) return 'delivery_status';

  // Photo sync (specific — before general data usage)
  if (msg.includes('photo') && (msg.includes('sync') || msg.includes('gallery') || msg.includes('backup'))) return 'photo_sync';

  // Data usage
  if (msg.includes('data') && (msg.includes('usage') || msg.includes('used') || msg.includes('storage'))) return 'data_usage';
  if (msg.includes('how much') && (msg.includes('data') || msg.includes('storage') || msg.includes('space'))) return 'data_usage';
  if (msg.includes('storage') || msg.includes('quota')) return 'data_usage';

  // Subscription
  if (msg.includes('subscription') || msg.includes('plan') || msg.includes('premium') || msg.includes('pro')) return 'subscription';
  if (msg.includes('trial') || msg.includes('expire') || msg.includes('renew')) return 'subscription';

  // Account info
  if (msg.includes('account') && (msg.includes('info') || msg.includes('details') || msg.includes('status'))) return 'account_info';
  if (msg.includes('my account')) return 'account_info';

  // Device info
  if (msg.includes('device') && (msg.includes('info') || msg.includes('list') || msg.includes('connected'))) return 'device_info';
  if (msg.includes('paired device') || msg.includes('my device') || msg.includes('show') && msg.includes('device')) return 'device_info';

  // Help
  if (msg.includes('help') || msg.includes('what can you') || msg.includes('how do i')) return 'help';

  return 'general';
}

// ---------------------------------------------------------------------------
// QUERY HANDLERS
// ---------------------------------------------------------------------------

function handleRecoveryCodeQuery(): string {
  return `**Recovery Code**\n\nYour recovery code is automatically backed up to your Google account for security.\n\n**How recovery works:**\n- When you reinstall SyncFlow or set up a new device, your account is automatically recovered from Google\n- No need to manually enter a recovery code — just sign in with the same Google account\n\n**If automatic recovery fails:**\n- Make sure you're using the same Google account\n- Check that Google backup is enabled on your Android device\n- Contact syncflow.contact@gmail.com for assistance`;
}

function handleUserIdQuery(userId: string | null): string {
  if (!userId) {
    return 'To view your user ID, you need to be signed in. Your user ID is shown in Settings > Usage & Limits.';
  }
  return `Your user ID is:\n\n**${userId}**\n\nThis is your unique account identifier. You may need this when contacting support.`;
}

async function handleDataUsageQuery(userId: string | null): Promise<string> {
  if (!userId) return 'To view your data usage, you need to be signed in.';

  try {
    const [usage, subscription] = await Promise.all([
      queryOne('SELECT storage_bytes, bandwidth_bytes_month, updated_at FROM user_usage WHERE user_id = $1', [userId]),
      queryOne('SELECT plan, status FROM user_subscriptions WHERE user_id = $1', [userId]),
    ]);

    const storageBytes = parseInt(usage?.storage_bytes || '0');
    const monthlyBytes = parseInt(usage?.bandwidth_bytes_month || '0');
    const plan = subscription?.plan || 'free';
    const isPaid = plan !== 'free' && subscription?.status === 'active';
    const storageLimit = isPaid ? 2 * 1024 * 1024 * 1024 : 100 * 1024 * 1024;
    const monthlyLimit = isPaid ? 10 * 1024 * 1024 * 1024 : 500 * 1024 * 1024;

    let response = `**Your Data Usage**\n\n`;
    response += `**Storage Used:** ${formatBytes(storageBytes)} / ${formatBytes(storageLimit)}\n`;
    response += `**Monthly Uploads:** ${formatBytes(monthlyBytes)} / ${formatBytes(monthlyLimit)}\n`;
    response += `**Plan:** ${plan.charAt(0).toUpperCase() + plan.slice(1)}\n`;

    if (usage?.updated_at) {
      response += `\n*Last updated: ${new Date(usage.updated_at).toLocaleString()}*`;
    }

    if (storageBytes > storageLimit * 0.8) {
      response += `\n\n**Note:** You're approaching your storage limit. Consider clearing MMS & photo data in Settings > Usage & Limits, or upgrade to Pro for 2GB storage.`;
    }

    return response;
  } catch (error) {
    console.error('[Support] Error fetching usage:', error);
    return 'I had trouble retrieving your usage data. Please try again later.';
  }
}

async function handleSubscriptionQuery(userId: string | null): Promise<string> {
  if (!userId) return 'To view your subscription, you need to be signed in.';

  try {
    const subscription = await queryOne(
      'SELECT plan, status, started_at, expires_at FROM user_subscriptions WHERE user_id = $1',
      [userId]
    );

    const plan = subscription?.plan || 'free';

    if (plan === 'free' || !subscription) {
      let response = `**Your Subscription**\n\n`;
      response += `**Current Plan:** Free\n`;
      response += `**Amount:** $0.00/month\n`;
      response += `**Status:** Active\n\n`;
      response += `**What's included:**\n`;
      response += `- 2 devices\n`;
      response += `- 100MB storage\n`;
      response += `- 500MB/month uploads\n`;
      response += `- 50MB max file size\n`;
      response += `- SMS/MMS sync, calls, contacts, photo sync\n\n`;
      response += `**Upgrade to Pro** for 10 devices, 2GB storage, 10GB/month uploads, media control, and more.`;
      return response;
    }

    let amount = '';
    let cycle = '';
    if (plan === 'monthly') { amount = '$4.99'; cycle = '/month'; }
    else if (plan === 'yearly') { amount = '$39.99'; cycle = '/year'; }
    else if (plan === 'lifetime') { amount = '$79.99'; cycle = ' (one-time)'; }
    else { amount = plan; cycle = ''; }

    const status = subscription.status || 'unknown';

    let response = `**Your Subscription**\n\n`;
    response += `**Current Plan:** Pro ${plan.charAt(0).toUpperCase() + plan.slice(1)}\n`;
    response += `**Amount:** ${amount}${cycle}\n`;
    response += `**Status:** ${status.charAt(0).toUpperCase() + status.slice(1)}\n`;

    if (subscription.started_at) {
      response += `**Started:** ${new Date(subscription.started_at).toLocaleDateString()}\n`;
    }
    if (subscription.expires_at) {
      const expiryDate = new Date(subscription.expires_at);
      const now = new Date();
      if (expiryDate > now) {
        const daysLeft = Math.ceil((expiryDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
        response += `**Next Renewal:** ${expiryDate.toLocaleDateString()} (${daysLeft} days)\n`;
      } else {
        response += `**Expired On:** ${expiryDate.toLocaleDateString()}\n`;
      }
    }

    response += `\n**What's included:**\n`;
    response += `- 10 devices\n`;
    response += `- 2GB storage\n`;
    response += `- 10GB/month uploads\n`;
    response += `- 1GB max file size\n`;
    response += `- Media control\n`;

    return response;
  } catch (error) {
    console.error('[Support] Error fetching subscription:', error);
    return 'I had trouble retrieving your subscription details. Please try again later.';
  }
}

async function handleAccountInfoQuery(userId: string | null): Promise<string> {
  if (!userId) return 'To view your account info, you need to be signed in.';

  try {
    const [msgCount, contactCount, deviceCount, subscription, usage] = await Promise.all([
      queryOne('SELECT COUNT(*) as count FROM user_messages WHERE user_id = $1', [userId]),
      queryOne('SELECT COUNT(*) as count FROM user_contacts WHERE user_id = $1', [userId]),
      queryOne('SELECT COUNT(*) as count FROM user_devices WHERE user_id = $1', [userId]),
      queryOne('SELECT plan, status FROM user_subscriptions WHERE user_id = $1', [userId]),
      queryOne('SELECT storage_bytes FROM user_usage WHERE user_id = $1', [userId]),
    ]);

    const plan = subscription?.plan || 'free';
    const storageMB = (parseInt(usage?.storage_bytes || '0') / (1024 * 1024)).toFixed(2);

    let response = `**Your Account Summary**\n\n`;
    response += `**User ID:** ${userId}\n`;
    response += `**Plan:** ${plan.charAt(0).toUpperCase() + plan.slice(1)}\n`;
    response += `**Connected Devices:** ${deviceCount?.count || 0}\n`;
    response += `**Messages Synced:** ${parseInt(msgCount?.count || '0').toLocaleString()}\n`;
    response += `**Contacts Synced:** ${parseInt(contactCount?.count || '0').toLocaleString()}\n`;
    response += `**Storage Used:** ${storageMB} MB\n`;

    return response;
  } catch (error) {
    console.error('[Support] Error fetching account info:', error);
    return 'I had trouble retrieving your account information. Please try again later.';
  }
}

async function handleDeviceInfoQuery(userId: string | null): Promise<string> {
  if (!userId) return 'To view your devices, you need to be signed in.';

  try {
    const devices = await query<{ id: string; device_name: string; device_type: string; paired_at: any; last_seen: any }>(
      'SELECT id, device_name, device_type, paired_at, last_seen FROM user_devices WHERE user_id = $1 ORDER BY paired_at DESC',
      [userId]
    );

    if (devices.length === 0) {
      return "You don't have any devices connected yet. Open SyncFlow on your devices to connect them.";
    }

    let response = `**Your Connected Devices (${devices.length})**\n\n`;
    devices.forEach((device, index) => {
      const name = device.device_name || 'Unknown Device';
      const type = device.device_type || 'unknown';
      const lastSeen = device.last_seen ? new Date(device.last_seen).toLocaleString() : 'unknown';
      response += `${index + 1}. **${name}**\n`;
      response += `   - Type: ${type}\n`;
      response += `   - Last active: ${lastSeen}\n\n`;
    });

    return response;
  } catch (error) {
    console.error('[Support] Error fetching devices:', error);
    return 'I had trouble retrieving your device information. Please try again later.';
  }
}

async function handleSyncStatusQuery(userId: string | null): Promise<string> {
  if (!userId) return 'To view your sync status, you need to be signed in.';

  try {
    const [devices, recentMsg] = await Promise.all([
      query<{ device_name: string; device_type: string; last_seen: any }>(
        'SELECT device_name, device_type, last_seen FROM user_devices WHERE user_id = $1',
        [userId]
      ),
      queryOne(
        'SELECT date, created_at FROM user_messages WHERE user_id = $1 ORDER BY date DESC LIMIT 1',
        [userId]
      ),
    ]);

    let response = `**Your Sync Status**\n\n`;

    if (devices.length > 0) {
      response += `**Connected Devices:**\n`;
      devices.forEach((device) => {
        const name = device.device_name || 'Unknown';
        const lastSeen = device.last_seen ? new Date(device.last_seen).toLocaleString() : 'Never';
        response += `- **${name}** (${device.device_type || 'unknown'}): Last seen ${lastSeen}\n`;
      });
    } else {
      response += `No devices connected.\n`;
    }

    if (recentMsg?.date) {
      const lastMsgDate = new Date(parseInt(recentMsg.date)).toLocaleString();
      response += `\n**Last Message Synced:** ${lastMsgDate}`;
    }

    response += `\n\n*If sync seems stuck, try force-closing and reopening the Android app.*`;

    return response;
  } catch (error) {
    console.error('[Support] Error fetching sync status:', error);
    return 'I had trouble retrieving your sync status. Please try again later.';
  }
}

async function handleMessageStatsQuery(userId: string | null): Promise<string> {
  if (!userId) return 'To view your message statistics, you need to be signed in.';

  try {
    const [totalCount, smsCount, mmsCount, sentCount, recentMsg] = await Promise.all([
      queryOne('SELECT COUNT(*) as count FROM user_messages WHERE user_id = $1', [userId]),
      queryOne('SELECT COUNT(*) as count FROM user_messages WHERE user_id = $1 AND (is_mms = false OR is_mms IS NULL)', [userId]),
      queryOne('SELECT COUNT(*) as count FROM user_messages WHERE user_id = $1 AND is_mms = true', [userId]),
      queryOne('SELECT COUNT(*) as count FROM user_messages WHERE user_id = $1 AND type = 2', [userId]),
      queryOne('SELECT date FROM user_messages WHERE user_id = $1 ORDER BY date DESC LIMIT 1', [userId]),
    ]);

    let response = `**Your Message Statistics**\n\n`;
    response += `**Total Messages:** ${parseInt(totalCount?.count || '0').toLocaleString()}\n`;
    response += `**SMS Messages:** ${parseInt(smsCount?.count || '0').toLocaleString()}\n`;
    response += `**MMS Messages:** ${parseInt(mmsCount?.count || '0').toLocaleString()}\n`;
    response += `**Sent Messages:** ${parseInt(sentCount?.count || '0').toLocaleString()}\n`;

    if (recentMsg?.date) {
      response += `\n**Last Message:** ${new Date(parseInt(recentMsg.date)).toLocaleString()}`;
    }

    return response;
  } catch (error) {
    console.error('[Support] Error fetching message stats:', error);
    return 'I had trouble retrieving your message statistics. Please try again later.';
  }
}

async function handleUnpairDeviceQuery(userId: string | null, originalMessage: string): Promise<string> {
  if (!userId) return 'To manage your devices, you need to be signed in.';

  try {
    const devices = await query<{ id: string; device_name: string; device_type: string }>(
      'SELECT id, device_name, device_type FROM user_devices WHERE user_id = $1 ORDER BY paired_at DESC',
      [userId]
    );

    if (devices.length === 0) {
      return "You don't have any devices connected to unpair.";
    }

    const msg = originalMessage.toLowerCase();

    // Try to match device by name or number
    let matchedDevice: { id: string; device_name: string; device_type: string } | null = null;
    devices.forEach((device, index) => {
      const name = (device.device_name || '').toLowerCase();
      if ((name && msg.includes(name)) || msg.includes(`device ${index + 1}`) || msg.includes(`#${index + 1}`)) {
        matchedDevice = device;
      }
    });

    if (matchedDevice) {
      const md = matchedDevice as { id: string; device_name: string; device_type: string };
      await query('DELETE FROM user_devices WHERE id = $1 AND user_id = $2', [md.id, userId]);
      return `**Device Unpaired Successfully**\n\n"${md.device_name || 'Device'}" has been removed from your account.\n\nIf you want to use this device again, you'll need to re-pair it.`;
    }

    // Show device list for user to choose
    let response = `**Which device would you like to unpair?**\n\n`;
    devices.forEach((device, index) => {
      response += `${index + 1}. **${device.device_name || 'Unknown'}** (${device.device_type || 'unknown'})\n`;
    });
    response += `\nReply with "unpair device [name]" or "unpair device #[number]" to remove a device.`;

    return response;
  } catch (error) {
    console.error('[Support] Error handling unpair:', error);
    return 'I had trouble processing your request. Please try again later.';
  }
}

function handleResetSyncQuery(): string {
  return `**Reset Sync**\n\nTo perform a fresh sync:\n\n1. Open SyncFlow on your **Android device**\n2. Go to Settings > Sync Message History\n3. Select the time range (e.g., "All messages")\n4. Tap Sync\n\nThis will re-sync all messages from your phone to the server.\n\n**If issues persist:**\n- Force close and reopen the Android app\n- Check that the app has SMS permissions\n- Make sure battery optimization isn't killing the app\n- Try unpairing and re-pairing your devices`;
}

function handleDeleteAccountQuery(): string {
  return `**Delete Your Account**\n\nTo delete your SyncFlow account and all associated data:\n\n**On Android:**\n1. Open SyncFlow > Settings\n2. Scroll down and tap "Delete Account"\n3. Confirm the deletion\n\n**On Mac:**\n1. Open SyncFlow > Settings > General\n2. Scroll to the bottom and click "Delete Account"\n\n**What gets deleted:**\n- All synced messages\n- All connected devices\n- Subscription data\n- All personal information\n\n**Note:** There is a 30-day grace period — you can cancel the deletion within that time.\n\n**Important:** Cancel any active subscriptions first.\n\nFor immediate deletion, contact syncflow.contact@gmail.com`;
}

function handleRegenerateRecoveryQuery(): string {
  return `**Account Recovery**\n\nSyncFlow now uses automatic Google account-based recovery. There's no need to manually manage recovery codes.\n\n**How it works:**\n- Your account credentials are securely backed up to your Google account\n- When you reinstall or set up a new device, recovery happens automatically\n- Just sign in with the same Google account you used originally\n\n**If you're having trouble recovering:**\n- Ensure you're using the same Google account\n- Check that Google backup is enabled in your Android Settings\n- Contact syncflow.contact@gmail.com for help`;
}

async function handleBillingHistoryQuery(userId: string | null): Promise<string> {
  if (!userId) return 'To view your billing history, you need to be signed in.';

  try {
    const subscription = await queryOne(
      'SELECT plan, status, started_at, expires_at, stripe_customer_id FROM user_subscriptions WHERE user_id = $1',
      [userId]
    );

    const plan = subscription?.plan || 'free';
    const status = subscription?.status || 'none';

    if (plan === 'free' || !subscription) {
      return `**Billing & Transactions**\n\n**Current Plan:** Free\n**Amount:** $0.00/month\n\nNo transactions on file. You're on the free plan.\n\nUpgrade to Pro at Settings > Subscription.`;
    }

    // Determine price from plan type
    let amount = '';
    let cycle = '';
    if (plan === 'monthly') { amount = '$4.99'; cycle = '/month'; }
    else if (plan === 'yearly') { amount = '$39.99'; cycle = '/year'; }
    else if (plan === 'lifetime') { amount = '$79.99'; cycle = ' (one-time)'; }
    else { amount = plan; cycle = ''; }

    let response = `**Billing & Transactions**\n\n`;
    response += `**Current Plan:** Pro ${plan.charAt(0).toUpperCase() + plan.slice(1)}\n`;
    response += `**Status:** ${status.charAt(0).toUpperCase() + status.slice(1)}\n`;
    response += `**Amount:** ${amount}${cycle}\n\n`;

    response += `**Transaction History:**\n`;

    if (subscription.started_at) {
      const startDate = new Date(subscription.started_at);
      response += `- ${startDate.toLocaleDateString()} — Pro ${plan} subscription started — ${amount}\n`;
    }

    if (subscription.expires_at) {
      const expiryDate = new Date(subscription.expires_at);
      const now = new Date();
      if (expiryDate > now) {
        const daysLeft = Math.ceil((expiryDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
        response += `- ${expiryDate.toLocaleDateString()} — Next renewal — ${amount} (in ${daysLeft} days)\n`;
      } else {
        response += `- ${expiryDate.toLocaleDateString()} — Subscription expired\n`;
      }
    }

    response += `\nManage your subscription in Settings > Subscription.`;

    return response;
  } catch (error) {
    console.error('[Support] Error fetching billing:', error);
    return 'I had trouble retrieving your billing information. Please try again later.';
  }
}

function handleCancelSubscriptionQuery(): string {
  return `**Cancel Your Subscription**\n\nSyncFlow subscriptions can be cancelled from:\n\n**On Mac:**\n1. Open SyncFlow > Settings > Subscription\n2. Click "Manage Subscription"\n3. Follow the prompts to cancel via the App Store\n\n**On the Web:**\n1. Go to sfweb.app\n2. Navigate to Settings > Subscription\n3. Manage or cancel your subscription\n\n**What happens after cancellation:**\n- You keep Pro features until your current period ends\n- After that, you'll be downgraded to the free plan\n- Your data and messages remain intact\n- You can resubscribe anytime\n\n*Need help? Contact syncflow.contact@gmail.com*`;
}

function handleSpamSettingsQuery(): string {
  return `**Spam Filter Settings**\n\nSyncFlow includes built-in spam detection for your messages.\n\n**How to manage spam:**\n- **View spam:** Open the Spam folder from the conversation list\n- **Restore a message:** Open the spam folder > select a message > tap "Restore"\n- **Delete spam:** Select messages in spam folder > tap "Delete"\n\n**Spam detection:**\n- Automatic AI-based spam detection\n- Shows confidence scores and detection reasons\n- Messages are filtered automatically\n\n**Settings:** Open SyncFlow > navigate to the Spam folder from the message list.`;
}

async function handleSpamStatsQuery(userId: string | null): Promise<string> {
  if (!userId) return 'To view your spam statistics, you need to be signed in.';

  try {
    const [totalCount, recentCount] = await Promise.all([
      queryOne('SELECT COUNT(*) as count FROM user_spam_messages WHERE user_id = $1', [userId]),
      queryOne(
        'SELECT COUNT(*) as count FROM user_spam_messages WHERE user_id = $1 AND detected_at > $2',
        [userId, Date.now() - 30 * 24 * 60 * 60 * 1000]
      ),
    ]);

    let response = `**Your Spam Statistics**\n\n`;
    response += `**Total Spam Blocked:** ${parseInt(totalCount?.count || '0').toLocaleString()} messages\n`;
    response += `**Last 30 Days:** ${parseInt(recentCount?.count || '0').toLocaleString()} spam messages blocked\n`;

    response += `\n*View and manage spam in the Messages > Spam folder.*`;

    return response;
  } catch (error) {
    console.error('[Support] Error fetching spam stats:', error);
    return 'I had trouble retrieving your spam statistics. Please try again later.';
  }
}

function handleClipboardQuery(): string {
  return `**Clipboard Sync**\n\nSyncFlow automatically syncs your clipboard between all paired devices.\n\n**How it works:**\n- Copy text on your Android phone → it appears on Mac instantly\n- Copy text on Mac → it's available on your phone\n- Supports text content\n\n**Enable/Disable on Mac:**\n- Use the menu bar: Phone > Clipboard Sync\n\n**Privacy:** Clipboard data is encrypted in transit and never stored on the server.`;
}

function handleDndQuery(): string {
  return `**Do Not Disturb Sync**\n\nSyncFlow syncs your phone's DND status to your Mac.\n\n**How it works:**\n- When DND is on your Android phone, Mac shows a moon icon\n- You can toggle your phone's DND from Mac\n\n**How to use on Mac:**\n- Menu bar: Phone > Enable/Disable Phone DND\n\n**Note:** Your phone must have DND access permission granted to SyncFlow.`;
}

function handleMediaControlQuery(): string {
  return `**Media Control**\n\nControl your phone's music playback from Mac.\n\n**Features:**\n- Play/Pause\n- Skip forward/backward\n- Volume control\n- See currently playing track info\n\n**How to use on Mac:**\n- Menu bar: Phone > Media Control for playback controls\n- Toggle the Media Bar with Cmd+Shift+M to show controls in the main window\n\n**Requirements:**\n- Android: Grant notification access to SyncFlow\n- Pro plan required for this feature\n\n**Note:** Media control is available on Mac only, not on the web app.`;
}

function handleHotspotQuery(): string {
  return `**Hotspot Control**\n\nToggle your phone's WiFi hotspot remotely from Mac.\n\n**How to use on Mac:**\n- Menu bar: Phone > Enable/Disable Hotspot\n\n**Requirements:**\n- Android 11+ recommended\n- Grant hotspot permissions to SyncFlow on Android\n- Your phone must be connected to the server\n\n**Note:** Hotspot control is available on Mac only, not on the web app. Some Android devices may require additional permissions or manufacturer-specific settings.`;
}

function handleFindPhoneQuery(): string {
  return `**Find My Phone**\n\nMake your Android phone ring at full volume from Mac to help locate it.\n\n**How to use on Mac:**\n- Menu bar: Phone > Find My Phone\n\n**How it works:**\n- Sends a ring command to your phone via the server\n- Phone rings at maximum volume even if on silent\n- Ring stops when you dismiss it on the phone\n\n**Requirements:** Phone must be connected to the internet and SyncFlow must be running.`;
}

function handleSharedLinksQuery(): string {
  return `**Share Links Between Devices**\n\nSend URLs and links between your paired devices instantly.\n\n**How to use:**\n- **Android to Mac:** Use Android's Share menu > SyncFlow\n- **Mac to Android:** Copy a URL and use the share function\n\n**Features:**\n- Links open automatically on the receiving device (configurable)\n- Notification shown on the receiving device\n- Works with any URL\n\n**Note:** Link sharing is available between Android and Mac. The web app does not currently support link sharing.`;
}

function handlePhoneStatusQuery(): string {
  return `**Phone Status**\n\nSee your Android phone's status on Mac in real-time.\n\n**Information displayed:**\n- Battery level and charging status\n- Signal strength (cellular)\n- WiFi connection status\n- Network type (4G/5G/WiFi)\n\n**Where to find it:**\n- Mac: Battery and signal icons are shown in the sidebar near your phone name\n\n**Note:** Status updates in real-time via WebSocket. If status seems stuck, check that the Android app is running. Phone status is available on Mac only, not on the web app.`;
}

function handleReadReceiptsQuery(): string {
  return `**Read Receipts**\n\nSyncFlow syncs message read status across YOUR devices.\n\n**How it works:**\n- Read a message on your phone → it's marked as read on Mac/Web\n- Read a message on Mac/Web → it's marked as read on your phone\n- This syncs YOUR read status, not whether the other person read your message\n\n**Note:** SyncFlow cannot tell you if the recipient read your SMS/MMS — that would require RCS or iMessage. Read receipt sync is about keeping your own unread count consistent across devices.`;
}

function handleTypingIndicatorsQuery(): string {
  return `**Typing Indicators**\n\nSee when someone is typing in a conversation, synced across your devices.\n\n**How it works:**\n- If you're typing on Mac, your other devices show the typing indicator\n- Typing status is shared in real-time via WebSocket\n\n**Note:** This shows typing activity from YOUR devices to your other devices. It does NOT show if the other person is typing (that requires carrier support).`;
}

function handleScheduledMessagesQuery(): string {
  return `**Scheduled Messages**\n\nSchedule SMS/MMS messages to be sent at a specific time.\n\n**How to use on Mac/Android:**\n1. Open a conversation\n2. Type your message\n3. Tap the schedule icon (clock) next to the Send button\n4. Pick a date and time\n5. Confirm — the message will be sent automatically\n\n**Manage scheduled messages:**\n- View pending scheduled messages in the conversation\n- Cancel or edit before the scheduled time\n\n**Note:** Your Android phone must be connected to the server at the scheduled time for the message to be sent. Scheduled messages are not yet available on the web app.`;
}

function handleNotificationsQuery(): string {
  return `**Notification Mirroring**\n\nSee your Android phone's notifications on Mac.\n\n**Features:**\n- All app notifications mirrored in real-time\n- Notification actions supported where available\n- Dismiss notifications from Mac\n\n**Setup on Android:**\n1. Open SyncFlow > Settings > Pair Device\n2. Enable "Notification Mirroring"\n3. Grant notification access when prompted (opens Android system settings)\n4. Enable SyncFlow in the notification access list\n\n**Privacy:** Notifications are encrypted in transit.\n\n**Note:** Notification mirroring is available on Mac only, not on the web app.`;
}

function handleVoicemailQuery(): string {
  return `**Voicemail Sync**\n\nAccess your voicemails on Mac/Web.\n\n**Features:**\n- Voicemails synced from your Android phone\n- Transcriptions available (when supported by carrier)\n- Play voicemails directly on Mac/Web\n- See caller info and timestamp\n\n**Requirements:**\n- Visual voicemail must be supported by your carrier\n- Grant voicemail permissions to SyncFlow on Android\n\n**Note:** Voicemail access depends on your carrier's support for visual voicemail APIs.`;
}

function handleDeliveryStatusQuery(): string {
  return `**Delivery Status & Checkmarks**\n\nSyncFlow shows delivery status for messages you send:\n\n**Status indicators:**\n- **Clock icon** = Sending (message queued)\n- **Single checkmark** ✓ = Sent (carrier accepted the message)\n- **Double checkmark** ✓✓ = Delivered (carrier confirmed delivery to recipient)\n- **Exclamation mark** ! = Failed (message could not be sent)\n\n**How it works:**\n- When you send SMS from Mac/Web, the message goes to your Android phone\n- Android sends it via your carrier\n- Carrier provides delivery confirmation\n- Status updates in real-time on all your devices\n\n**Note:** MMS delivery reports are carrier-dependent and may not always be available. SMS delivery tracking is more reliable.`;
}

function handlePhotoSyncQuery(): string {
  return `**Photo Sync**\n\nSync photos from your Android phone to Mac/Web.\n\n**Features:**\n- Recent photos automatically synced\n- View photo gallery on Mac/Web\n- Download photos to your computer\n- Photos stored securely in cloud storage\n\n**Available to all users:**\n- Free: Limited by 100MB storage quota\n- Pro: 2GB storage quota\n\n**How it works:**\n- Photo sync runs automatically when your Android phone is connected\n- Photos appear in the Photo Gallery on Mac/Web\n\n**Manage storage:** Clear synced photos in Settings > Usage & Limits > Clear MMS & Photo Data.`;
}

function getHelpResponse(): string {
  return `**Hi! I'm the SyncFlow Support Assistant.**\n\nI can help you with your account. Here's what you can ask:\n\n**Account & Security:**\n- "What's my user ID?" / "How does account recovery work?"\n- "Show my account info"\n\n**Messages & Sync:**\n- "How many messages synced?" / "Sync status"\n- "Schedule a message" / "Delivery status checkmarks"\n- "Read receipts" / "Typing indicators"\n\n**Devices & Features:**\n- "Show my devices" / "Unpair a device"\n- "Clipboard sync" / "Find my phone"\n- "Notification mirroring" / "Do Not Disturb sync"\n- "Media control" / "Hotspot control"\n- "Share links" / "Phone battery status"\n\n**Photos & Storage:**\n- "Photo sync" / "Voicemail sync"\n- "How much data have I used?" / "Storage quota"\n\n**Subscription & Billing:**\n- "What's my plan?" / "Show billing history"\n- "Cancel subscription"\n\n**Spam:**\n- "Spam settings" / "How many spam blocked?"\n\n**Troubleshooting:**\n- "Messages not syncing" / "Reset my sync"\n- "How do I pair?" / "Delete my account"\n\nJust type your question!`;
}

function getGeneralResponse(msg: string): string {
  // Unpair
  if (msg.includes('unpair') || (msg.includes('remove') && msg.includes('device'))) {
    return `**How to Unpair a Device:**\n\nYou can unpair devices in two ways:\n\n**Option 1 - Ask me:**\nSay "unpair device [device name]" and I'll remove it for you.\nFirst, ask "show my devices" to see your connected devices.\n\n**Option 2 - Manual:**\n- On Android: Settings > Pair Device > tap X next to the device`;
  }

  // Pairing
  if (msg.includes('pair') || msg.includes('connect') || msg.includes('link') || msg.includes('qr')) {
    return `**How to Pair Devices:**\n\n1. Open SyncFlow on your Mac or go to sfweb.app\n2. A QR code will be displayed on the pairing screen\n3. On your Android phone: Open SyncFlow > Settings > Pair Device\n4. Tap "Scan QR Code" and scan the code shown on Mac/Web\n5. Confirm the pairing on both devices\n\nYou can pair multiple Mac computers and web browsers to the same phone.`;
  }

  // Sync issues
  if (msg.includes('not working') || msg.includes('messages not') || (msg.includes('sync') && msg.includes('issue'))) {
    return `**Troubleshooting Sync Issues:**\n\n1. **Check connection:** Make sure both devices have internet\n2. **Check permissions:** On Android, ensure SMS permissions are granted\n3. **Force sync:** On Android, go to Settings > Sync Message History\n4. **Battery optimization:** Make sure SyncFlow isn't killed by battery saver\n5. **Restart:** Force close and reopen the Android app\n\nIf issues persist, try unpairing and re-pairing your devices.`;
  }

  // Pricing
  if (msg.includes('pro') || msg.includes('premium') || msg.includes('upgrade') || msg.includes('price') || msg.includes('cost')) {
    return `**SyncFlow Plans & Pricing**\n\n**Free — $0.00**\n- 2 devices, 100MB storage, 500MB/month uploads\n- SMS/MMS sync, calls, contacts, file transfer, photo sync\n\n**Pro Monthly — $4.99/month**\n- 10 devices, 2GB storage, 10GB/month uploads\n- 1GB max file size, media control\n\n**Pro Yearly — $39.99/year** (save 33%)\n- Same features as Pro Monthly\n\n**Pro 3-Year — $79.99 one-time** (best value)\n- Same features, pay once for 3 years\n\nUpgrade in Settings > Subscription.`;
  }

  // File transfer
  if (msg.includes('file') || msg.includes('transfer') || msg.includes('send photo') || msg.includes('send video')) {
    return `**File Transfer:**\n\n**From Mac to Android:**\n- Use the Quick Drop panel — drag and drop files or click "Send File"\n\n**From Android to Mac:**\n- Go to Settings > Send Files to Mac\n\n**From Web:**\n- Attach images when sending MMS messages\n\n**Limits:**\n- Free: 50MB per file\n- Pro: 1GB per file\n\nFiles are saved to Downloads folder on both devices.`;
  }

  // Calls
  if (msg.includes('call') || msg.includes('phone') || msg.includes('dial')) {
    return `**Making Calls:**\n\n**From Mac:**\n- Click the phone icon next to any contact or conversation\n- The call is placed through your Android phone\n\n**SyncFlow Calls (Video/Audio):**\n- Audio and video calls between SyncFlow users only\n- Both users must have SyncFlow installed and be signed in\n- Works via WebRTC for high-quality peer-to-peer calling\n\n**Note:** Your Android phone must be connected for regular phone calls to work.`;
  }

  // MMS
  if (msg.includes('mms') || msg.includes('group') || msg.includes('picture') || msg.includes('image')) {
    return `**MMS & Group Messages:**\n\n- Full MMS support (images, videos, audio)\n- Group MMS conversations supported\n- View all participants in group chats\n- Send media from Mac/Web\n\n**Note:** MMS images sync from Android via cloud storage. If images aren't showing, check your storage quota in Settings > Usage & Limits.`;
  }

  // E2EE
  if (msg.includes('encrypt') || msg.includes('e2ee') || msg.includes('security') || msg.includes('privacy')) {
    return `**End-to-End Encryption (E2EE)**\n\n- Messages are encrypted before leaving your device\n- Only your paired devices can decrypt them\n- Server cannot read encrypted messages\n- E2EE is enabled by default for all messages\n\n**Key sync is automatic:**\n- Keys are synced during device pairing\n- Keys are also synced automatically on app startup\n- No manual action needed\n\n**If you see "Encrypted message - sync keys to decrypt":**\n- Make sure your Android phone is online and SyncFlow is running\n- Restart the Mac/Web app — keys will sync automatically\n- If the issue persists, try unpairing and re-pairing your device`;
  }

  // Platform
  if (msg.includes('ios') || msg.includes('iphone') || msg.includes('windows') || msg.includes('pc')) {
    return `**Platform Support:**\n\n- **Android phone** (required - source of messages)\n- **macOS app** (standalone download)\n- **Web app** (sfweb.app)\n\niPhone doesn't allow third-party SMS access, so there's no iOS app.\nFor Windows, use the web app at sfweb.app — it works on any device with a browser!`;
  }

  // Default
  return `I can help with:\n\n**Account & Data:**\n- "What's my user ID?" / "Recovery code"\n- "My subscription" / "Data usage" / "My devices"\n\n**Features:**\n- "Clipboard sync" / "Find my phone" / "Notifications"\n- "Schedule message" / "Delivery status" / "Photo sync"\n- "Media control" / "DND sync" / "Hotspot" / "Voicemail"\n- "Pair devices" / "File transfer" / "Share links"\n\n**Troubleshooting:**\n- "Messages not syncing" / "Reset sync" / "E2EE encryption"\n\nType **"help"** for the full list, or contact syncflow.contact@gmail.com!`;
}

// ---------------------------------------------------------------------------
// HELPERS
// ---------------------------------------------------------------------------

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const gb = bytes / (1024 * 1024 * 1024);
  const mb = bytes / (1024 * 1024);
  if (gb >= 1) return `${gb.toFixed(1)} GB`;
  if (mb >= 1) return `${mb.toFixed(1)} MB`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}

export default router;
