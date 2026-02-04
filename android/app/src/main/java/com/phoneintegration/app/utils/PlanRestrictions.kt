package com.phoneintegration.app.utils

import android.content.Context
import android.widget.Toast
import com.phoneintegration.app.data.PreferencesManager

/**
 * Utility class to enforce plan-based feature restrictions
 * Free tier: SMS only
 * Paid tier: All features
 */
class PlanRestrictions(private val context: Context, private val preferencesManager: PreferencesManager) {

    fun checkFeatureAccess(feature: Feature): FeatureAccessResult {
        if (preferencesManager.isPaidUser()) {
            return FeatureAccessResult(allowed = true)
        }

        // Check if free trial is still active
        val isTrialActive = preferencesManager.isFreeTrial()
        val trialDaysLeft = preferencesManager.getTrialDaysRemaining()

        // Free tier: SMS & MMS always works (native features)
        if (feature in listOf(Feature.SMS_SEND, Feature.SMS_RECEIVE, Feature.PHONE_CALL, Feature.MMS_SEND, Feature.MMS_RECEIVE)) {
            return FeatureAccessResult(allowed = true)
        }

        // Trial features: Allowed during 7-day trial
        val isTrialFeature = feature in listOf(
            Feature.CALL_SYNC,
            Feature.VOICE_NOTE,
            Feature.MEDIA_ATTACHMENT,
            Feature.FILE_TRANSFER,
            Feature.END_TO_END_ENCRYPTION,
            Feature.SCHEDULED_MESSAGES,
            Feature.DESKTOP_SYNC,
            Feature.AI_ASSISTANT,
            Feature.ADVANCED_SEARCH,
            Feature.VIDEO_CALL_WEBRTC
        )

        if (isTrialActive && isTrialFeature) {
            return FeatureAccessResult(
                allowed = true,
                message = "⏰ 7-day free trial active (${trialDaysLeft} days remaining)"
            )
        }

        // Trial expired or always restricted features
        return when (feature) {
            Feature.SMS_SEND, Feature.SMS_RECEIVE, Feature.MMS_SEND, Feature.MMS_RECEIVE, Feature.PHONE_CALL -> FeatureAccessResult(allowed = true)

            Feature.CALL_SYNC -> FeatureAccessResult(
                allowed = false,
                message = if (isTrialActive)
                    "📞 Call sync trial expires in ${trialDaysLeft} days - Upgrade to continue"
                else
                    "📞 Call synchronization is available in Paid plans only",
                title = "Upgrade to Paid"
            )
            Feature.VOICE_NOTE -> FeatureAccessResult(
                allowed = false,
                message = "🎙️ Voice notes are available in Paid plans only",
                title = "Upgrade to Paid"
            )
            Feature.MEDIA_ATTACHMENT -> FeatureAccessResult(
                allowed = false,
                message = "📸 Media attachments (photos, videos) are available in Paid plans only",
                title = "Upgrade to Paid"
            )
            Feature.FILE_TRANSFER -> FeatureAccessResult(
                allowed = false,
                message = "📁 File transfers are available in Paid plans only",
                title = "Upgrade to Paid"
            )
            Feature.END_TO_END_ENCRYPTION -> FeatureAccessResult(
                allowed = false,
                message = "🔐 End-to-end encryption is available in Paid plans only",
                title = "Upgrade to Paid"
            )
            Feature.SCHEDULED_MESSAGES -> FeatureAccessResult(
                allowed = false,
                message = "⏰ Scheduled messages are available in Paid plans only",
                title = "Upgrade to Paid"
            )
            Feature.DESKTOP_SYNC -> FeatureAccessResult(
                allowed = false,
                message = "💻 Desktop synchronization is available in Paid plans only",
                title = "Upgrade to Paid"
            )
            Feature.AI_ASSISTANT -> FeatureAccessResult(
                allowed = false,
                message = "🤖 AI Assistant is available in Paid plans only",
                title = "Upgrade to Paid"
            )
            Feature.ADVANCED_SEARCH -> FeatureAccessResult(
                allowed = false,
                message = "🔍 Advanced search is available in Paid plans only",
                title = "Upgrade to Paid"
            )
            Feature.VIDEO_CALL_WEBRTC -> FeatureAccessResult(
                allowed = false,
                message = if (isTrialActive)
                    "📹 Video calls trial expires in ${trialDaysLeft} days - Upgrade to continue"
                else
                    "📹 Video calls are available in Paid plans only",
                title = "Upgrade to Paid"
            )
        }
    }

    fun showFeatureLockedToast(feature: Feature) {
        val result = checkFeatureAccess(feature)
        if (!result.allowed) {
            Toast.makeText(
                context,
                "${result.title}\n${result.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    enum class Feature {
        SMS_SEND,                    // Always allowed (native SMS)
        SMS_RECEIVE,                 // Always allowed (native SMS)
        MMS_SEND,                    // Always allowed (native MMS) - aggressively deleted
        MMS_RECEIVE,                 // Always allowed (native MMS) - aggressively deleted
        PHONE_CALL,                  // Always allowed (native phone calls via cell)
        CALL_SYNC,                   // 7-day trial → paid only
        VOICE_NOTE,                  // 7-day trial → paid only
        MEDIA_ATTACHMENT,            // 7-day trial → paid only
        FILE_TRANSFER,               // 7-day trial → paid only
        END_TO_END_ENCRYPTION,       // 7-day trial → paid only
        SCHEDULED_MESSAGES,          // 7-day trial → paid only
        DESKTOP_SYNC,                // 7-day trial → paid only
        AI_ASSISTANT,                // 7-day trial → paid only
        ADVANCED_SEARCH,             // 7-day trial → paid only (optional)
        VIDEO_CALL_WEBRTC            // 7-day trial → paid only
    }

    data class FeatureAccessResult(
        val allowed: Boolean,
        val message: String = "",
        val title: String = "Feature Locked"
    )
}
