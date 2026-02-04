package com.phoneintegration.app.desktop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Service that receives shared links from macOS/desktop and opens them
 * in the phone's browser.
 */
class LinkSharingService(context: Context) {
    private val context: Context = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    private var linkListenerHandle: ChildEventListener? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "LinkSharingService"
        private const val SHARED_LINKS_PATH = "shared_links"
        private const val USERS_PATH = "users"
    }

    /**
     * Start listening for shared links
     */
    fun startListening() {
        Log.d(TAG, "Starting link sharing service")
        database.goOnline()
        listenForSharedLinks()
    }

    /**
     * Stop listening for shared links
     */
    fun stopListening() {
        Log.d(TAG, "Stopping link sharing service")
        removeLinkListener()
        scope.cancel()
    }

    /**
     * Listen for shared links from other devices
     */
    private fun listenForSharedLinks() {
        scope.launch {
            try {
                val currentUser = auth.currentUser ?: return@launch
                val userId = currentUser.uid

                val linksRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(SHARED_LINKS_PATH)

                linkListenerHandle = object : ChildEventListener {
                    override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                        handleSharedLink(snapshot)
                    }

                    override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                        // Don't re-process changed links
                    }

                    override fun onChildRemoved(snapshot: DataSnapshot) {}
                    override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Link listener cancelled: ${error.message}")
                    }
                }

                linksRef.addChildEventListener(linkListenerHandle!!)
                Log.d(TAG, "Link listener registered")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting link listener", e)
            }
        }
    }

    private fun removeLinkListener() {
        scope.launch {
            try {
                val userId = auth.currentUser?.uid ?: return@launch
                linkListenerHandle?.let { listener ->
                    database.reference
                        .child(USERS_PATH)
                        .child(userId)
                        .child(SHARED_LINKS_PATH)
                        .removeEventListener(listener)
                }
                linkListenerHandle = null
            } catch (e: Exception) {
                Log.e(TAG, "Error removing link listener", e)
            }
        }
    }

    /**
     * Handle incoming shared link
     */
    private fun handleSharedLink(snapshot: DataSnapshot) {
        val linkId = snapshot.key ?: return
        val url = snapshot.child("url").value as? String ?: return
        val title = snapshot.child("title").value as? String
        val status = snapshot.child("status").value as? String
        val timestamp = snapshot.child("timestamp").value as? Long ?: 0

        // Check if already processed
        if (status == "opened") {
            return
        }

        // Check if link is recent (within last 60 seconds)
        val now = System.currentTimeMillis()
        if (now - timestamp > 60000) {
            Log.d(TAG, "Ignoring old link: $linkId")
            return
        }

        Log.d(TAG, "Received shared link: $url")

        // Open the link in browser
        openLinkInBrowser(url, title)

        // Mark as opened
        updateLinkStatus(linkId, "opened")
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
     * Update the link status in Firebase
     */
    private fun updateLinkStatus(linkId: String, status: String) {
        scope.launch {
            try {
                val userId = auth.currentUser?.uid ?: return@launch

                database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(SHARED_LINKS_PATH)
                    .child(linkId)
                    .child("status")
                    .setValue(status)
                    .await()

                Log.d(TAG, "Updated link $linkId status to $status")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating link status", e)
            }
        }
    }
}
