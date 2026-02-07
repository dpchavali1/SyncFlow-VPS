package com.phoneintegration.app.desktop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.*

/**
 * Service that receives shared links from macOS/desktop and opens them
 * in the phone's browser.
 *
 * VPS Backend Only - Uses VPS API instead of Firebase.
 */
class LinkSharingService(context: Context) {
    private val context: Context = context.applicationContext
    private val vpsClient = VPSClient.getInstance(context)

    private var linkPollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processedLinks = mutableSetOf<String>()

    companion object {
        private const val TAG = "LinkSharingService"
        private const val POLL_INTERVAL_MS = 2000L
    }

    /**
     * Start listening for shared links
     */
    fun startListening() {
        Log.d(TAG, "Starting link sharing service")
        listenForSharedLinks()
    }

    /**
     * Stop listening for shared links
     */
    fun stopListening() {
        Log.d(TAG, "Stopping link sharing service")
        linkPollingJob?.cancel()
        linkPollingJob = null
        scope.cancel()
    }

    /**
     * Listen for shared links from other devices via polling
     */
    private fun listenForSharedLinks() {
        linkPollingJob?.cancel()
        linkPollingJob = scope.launch {
            while (isActive) {
                try {
                    if (!vpsClient.isAuthenticated) {
                        delay(POLL_INTERVAL_MS)
                        continue
                    }

                    val links = vpsClient.getSharedLinks()
                    for (link in links) {
                        // Skip if already processed locally
                        if (processedLinks.contains(link.id)) continue

                        // Check if already opened
                        if (link.status == "opened") {
                            processedLinks.add(link.id)
                            continue
                        }

                        // Check if link is recent (within last 60 seconds)
                        val now = System.currentTimeMillis()
                        if (now - link.timestamp > 60000) {
                            Log.d(TAG, "Ignoring old link: ${link.id}")
                            processedLinks.add(link.id)
                            continue
                        }

                        Log.d(TAG, "Received shared link: ${link.url}")

                        // Open the link in browser
                        openLinkInBrowser(link.url, link.title)

                        // Mark as opened
                        updateLinkStatus(link.id, "opened")
                        processedLinks.add(link.id)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling shared links", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        Log.d(TAG, "Link polling started")
    }

    /**
     * Open link in browser
     */
    private fun openLinkInBrowser(url: String, title: String?) {
        try {
            // Ensure URL has a scheme
            val processedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(processedUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

            // Show toast
            MainScope().launch {
                val displayText = title?.let { "Opening: $it" } ?: "Opening link from Mac"
                Toast.makeText(context, displayText, Toast.LENGTH_SHORT).show()
            }

            Log.d(TAG, "Opened link in browser: $processedUrl")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening link: ${e.message}")

            MainScope().launch {
                Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Update the link status in VPS
     */
    private fun updateLinkStatus(linkId: String, status: String) {
        scope.launch {
            try {
                if (!vpsClient.isAuthenticated) return@launch

                vpsClient.updateSharedLinkStatus(linkId, status)
                Log.d(TAG, "Updated link $linkId status to $status")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating link status", e)
            }
        }
    }
}
