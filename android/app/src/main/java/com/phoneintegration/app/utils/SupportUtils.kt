package com.phoneintegration.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.phoneintegration.app.BuildConfig

/**
 * Utility for composing support emails with device information.
 * Emails include a subject tag to identify the Android app.
 */
object SupportUtils {

    private const val SUPPORT_EMAIL = "syncflow.contact@gmail.com"
    private const val SUBJECT_PREFIX = "[SyncFlow Android]"

    /**
     * Opens email client with pre-filled support request.
     * Subject: [SyncFlow Android] Support Request - v1.0.0
     */
    fun openSupportEmail(context: Context) {
        val subject = "$SUBJECT_PREFIX Support Request - v${BuildConfig.VERSION_NAME}"
        val body = buildEmailBody()

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
    }

    /**
     * Opens email client for bug report with device info.
     * Subject: [SyncFlow Android] Bug Report - v1.0.0
     */
    fun openBugReport(context: Context, errorDescription: String? = null) {
        val subject = "$SUBJECT_PREFIX Bug Report - v${BuildConfig.VERSION_NAME}"
        val body = buildEmailBody(errorDescription)

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
    }

    /**
     * Opens email client for feature request.
     * Subject: [SyncFlow Android] Feature Request - v1.0.0
     */
    fun openFeatureRequest(context: Context) {
        val subject = "$SUBJECT_PREFIX Feature Request - v${BuildConfig.VERSION_NAME}"
        val body = """
            |Feature Request
            |---------------
            |
            |Please describe the feature you'd like to see:
            |
            |
            |
            |---
            |App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            |Device: ${Build.MANUFACTURER} ${Build.MODEL}
            |Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
        """.trimMargin()

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
    }

    private fun buildEmailBody(errorDescription: String? = null): String {
        return buildString {
            appendLine("Please describe your issue:")
            appendLine()
            appendLine()
            appendLine()

            if (!errorDescription.isNullOrBlank()) {
                appendLine("Error Details:")
                appendLine(errorDescription)
                appendLine()
            }

            appendLine("---")
            appendLine("Device Information (please don't delete):")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Build Type: ${if (BuildConfig.DEBUG) "Debug" else "Release"}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device ID: ${Build.ID}")
        }
    }

    /**
     * Get mailto URI for use in custom implementations.
     */
    fun getSupportMailtoUri(): Uri {
        val subject = "$SUBJECT_PREFIX Support Request - v${BuildConfig.VERSION_NAME}"
        return Uri.parse("mailto:$SUPPORT_EMAIL?subject=${Uri.encode(subject)}")
    }
}
