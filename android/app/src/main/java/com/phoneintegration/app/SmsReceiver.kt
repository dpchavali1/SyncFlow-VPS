package com.phoneintegration.app

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.phoneintegration.app.data.database.SyncFlowDatabase
import com.phoneintegration.app.data.database.SpamMessage
import com.phoneintegration.app.spam.SpamFilterService
import com.phoneintegration.app.utils.SecureLogger
import com.phoneintegration.app.utils.SpamFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val SMS_RECEIVED_ACTION = "com.phoneintegration.app.SMS_RECEIVED"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_MESSAGE = "message"

        // Track recently processed messages to avoid duplicates
        private val recentMessages = mutableMapOf<String, Long>()
        private const val DUPLICATE_WINDOW_MS = 3000L // 3 seconds
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            SecureLogger.w("SMS_RECEIVER", "Null context or intent")
            return
        }

        try {
            val action = intent.action
            SecureLogger.d("SMS_RECEIVER", "onReceive action=$action")

            // Samsung sends SMS only through SMS_DELIVER, not SMS_RECEIVED
            if (action != Telephony.Sms.Intents.SMS_DELIVER_ACTION &&
                action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                return
            }

            val messages = try {
                Telephony.Sms.Intents.getMessagesFromIntent(intent)
            } catch (e: Exception) {
                SecureLogger.e("SMS_RECEIVER", "Error parsing messages from intent", e)
                return
            }

            if (messages.isNullOrEmpty()) {
                SecureLogger.w("SMS_RECEIVER", "No messages parsed from intent")
                return
            }

            val sender = messages[0].displayOriginatingAddress
            val fullMessage = messages.joinToString(separator = "") { it.messageBody }
            val timestamp = messages[0].timestampMillis

            // SECURITY: Never log phone numbers or message content
            SecureLogger.d("SMS_RECEIVER", "Received SMS from sender")

            // Check for duplicate (Samsung sends both SMS_DELIVER and SMS_RECEIVED)
            val messageKey = "$sender:$fullMessage:$timestamp"
            val now = System.currentTimeMillis()

            synchronized(recentMessages) {
                // Clean up old entries
                recentMessages.entries.removeIf { now - it.value > DUPLICATE_WINDOW_MS }

                // Check if we recently processed this message
                if (recentMessages.containsKey(messageKey)) {
                    SecureLogger.d("SMS_RECEIVER", "Duplicate SMS detected, ignoring")
                    return
                }

                // Mark this message as processed
                recentMessages[messageKey] = now
            }

            // IMPORTANT: Write SMS to database (required for default SMS apps)
            try {
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, sender)
                    put(Telephony.Sms.BODY, fullMessage)
                    put(Telephony.Sms.DATE, timestamp)
                    put(Telephony.Sms.DATE_SENT, timestamp)
                    put(Telephony.Sms.READ, 0) // Mark as unread
                    put(Telephony.Sms.SEEN, 1)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                }

                context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
                SecureLogger.d("SMS_RECEIVER", "SMS written to database")
            } catch (e: Exception) {
                SecureLogger.e("SMS_RECEIVER", "Failed to write SMS to database", e)
            }

            // Get contact name
            val contactHelper = try {
                ContactHelper(context)
            } catch (e: Exception) {
                SecureLogger.e("SMS_RECEIVER", "Error creating ContactHelper", e)
                null
            }
            val contactName = try {
                contactHelper?.getContactName(sender)
            } catch (e: Exception) {
                SecureLogger.e("SMS_RECEIVER", "Error getting contact name", e)
                null
            }

            // Check if contact is blocked or conversation is muted before showing notification
            CoroutineScope(Dispatchers.IO).launch {
                var threadId: Long? = null
                try {
                    val database = SyncFlowDatabase.getInstance(context)

                    // Check if sender is blocked
                    val blockedDao = database.blockedContactDao()
                    val normalizedSender = sender.replace(Regex("[^0-9+]"), "")
                    val isBlocked = blockedDao.isBlocked(normalizedSender)

                    if (isBlocked) {
                        SecureLogger.d("SMS_RECEIVER", "Sender is blocked, skipping notification")
                        return@launch
                    }

                    // Check for spam (if enabled)
                    val preferencesManager = com.phoneintegration.app.data.PreferencesManager(context)
                    val isSpamFilterEnabled = preferencesManager.spamFilterEnabled.value
                    val spamThreshold = preferencesManager.getSpamThreshold()

                    val isFromContact = contactName != null && contactName != sender

                    // Use both basic and advanced spam checking
                    val spamResult = if (isSpamFilterEnabled) {
                        // Basic pattern matching
                        val basicResult = SpamFilter.checkMessage(fullMessage, sender, isFromContact, spamThreshold)

                        // Advanced checking (URL blocklist, ML) - non-blocking
                        val advancedResult = try {
                            val advancedService = SpamFilterService.getInstance(context)
                            advancedService.checkMessage(
                                address = sender,
                                body = fullMessage,
                                isRead = false,
                                messageAgeHours = 0,
                                isFromContact = isFromContact
                            )
                        } catch (e: Exception) {
                            SecureLogger.w("SMS_RECEIVER", "Advanced spam check failed, using basic only")
                            null
                        }

                        // Combine results - use higher confidence
                        if (advancedResult != null && advancedResult.confidence > basicResult.confidence) {
                            SpamFilter.SpamCheckResult(
                                isSpam = advancedResult.isSpam || basicResult.isSpam,
                                confidence = advancedResult.confidence,
                                reasons = advancedResult.reasons.map { it.description } + basicResult.reasons
                            )
                        } else {
                            basicResult
                        }
                    } else {
                        SpamFilter.SpamCheckResult(isSpam = false, confidence = 0f, reasons = emptyList())
                    }

                    if (spamResult.isSpam) {
                        SecureLogger.d("SMS_RECEIVER", "Message detected as spam (confidence: ${spamResult.confidence})")

                        // Get message ID from content provider (most recent message)
                        val smsRepository = SmsRepository(context)
                        val recentMessages = smsRepository.getAllRecentMessages(1)
                        val messageId = recentMessages.firstOrNull()?.id ?: timestamp

                        // Store in spam database
                        val spamMessage = SpamMessage(
                            messageId = messageId,
                            address = sender,
                            body = fullMessage,
                            date = timestamp,
                            contactName = contactName,
                            spamConfidence = spamResult.confidence,
                            spamReasons = spamResult.reasons.joinToString(", "),
                            isUserMarked = false
                        )
                        database.spamMessageDao().insert(spamMessage)
                        // Only sync to desktop if devices are paired
                        if (com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(context)) {
                            com.phoneintegration.app.desktop.DesktopSyncService(context)
                                .syncSpamMessage(spamMessage)
                        }

                        // Show spam notification
                        val notificationHelper = NotificationHelper(context)
                        notificationHelper.showSpamNotification(sender, fullMessage, contactName)
                        return@launch
                    }

                    // Check if conversation is muted
                    val smsRepository = SmsRepository(context)
                    threadId = smsRepository.getThreadIdForAddress(sender)

                    if (threadId != null) {
                        val mutedDao = database.mutedConversationDao()
                        val isMuted = mutedDao.isMuted(threadId)

                        if (isMuted) {
                            SecureLogger.d("SMS_RECEIVER", "Conversation is muted, skipping notification")
                            return@launch
                        }
                    }

                    // Show notification only if not blocked, not spam, and not muted
                    val notificationHelper = NotificationHelper(context)
                    notificationHelper.showSmsNotification(sender, fullMessage, contactName, threadId = threadId)
                    Log.d("SMS_RECEIVER", "Notification shown for message")
                } catch (e: Exception) {
                    // Fallback: show notification if checks fail
                    SecureLogger.e("SMS_RECEIVER", "Error checking blocked/mute status", e)
                    try {
                        val notificationHelper = NotificationHelper(context)
                        notificationHelper.showSmsNotification(sender, fullMessage, contactName, threadId = threadId)
                    } catch (notifError: Exception) {
                        SecureLogger.e("SMS_RECEIVER", "Error showing fallback notification", notifError)
                    }
                }
            }

            // Broadcast locally to update UI & ViewModel
            try {
                val broadcast = Intent(SMS_RECEIVED_ACTION).apply {
                    putExtra(EXTRA_ADDRESS, sender)
                    putExtra(EXTRA_MESSAGE, fullMessage)
                }
                LocalBroadcastManager.getInstance(context).sendBroadcast(broadcast)
            } catch (e: Exception) {
                SecureLogger.e("SMS_RECEIVER", "Error broadcasting SMS received", e)
            }

            // Only sync to desktop if devices are paired (saves battery for Android-only users)
            if (com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(context)) {
                // Immediately sync this message to Firebase for desktop
                // Use WorkManager for guaranteed execution even if app is in background
                try {
                    com.phoneintegration.app.desktop.SmsSyncWorker.syncNow(context)
                } catch (e: Exception) {
                    SecureLogger.e("SMS_RECEIVER", "Error scheduling SMS sync", e)
                }

                // Also try immediate sync in coroutine (faster if app is active)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val smsRepository = SmsRepository(context)
                        val syncService = com.phoneintegration.app.desktop.DesktopSyncService(context)

                        // Get the most recent message (the one we just received)
                        val recentMessages = smsRepository.getAllRecentMessages(1)
                        if (recentMessages.isNotEmpty()) {
                            syncService.syncMessage(recentMessages[0])
                            SecureLogger.d("SMS_RECEIVER", "Message synced to Firebase for desktop")
                        }
                    } catch (e: Exception) {
                        SecureLogger.e("SMS_RECEIVER", "Error syncing message to Firebase", e)
                    }
                }
            }
        } catch (e: Exception) {
            SecureLogger.e("SMS_RECEIVER", "Critical error in SMS receiver", e)
            // Don't crash - just log and continue
        }
    }
}
