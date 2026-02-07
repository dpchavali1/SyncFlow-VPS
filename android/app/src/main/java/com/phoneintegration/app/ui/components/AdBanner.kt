package com.phoneintegration.app.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.phoneintegration.app.vps.VPSAuthManager

/**
 * Ad banner component that shows ads only to free/trial users.
 * Premium subscribers don't see ads.
 *
 * VPS Backend Only - Uses VPSAuthManager for user info.
 *
 * Test Ad Unit ID: ca-app-pub-3940256099942544/6300978111
 * Replace with production ID before release.
 */
@Composable
fun AdBanner(
    modifier: Modifier = Modifier,
    adUnitId: String = "ca-app-pub-4962910048695842/4209408906" // sfads banner
) {
    val context = LocalContext.current
    var showAd by remember { mutableStateOf(true) } // Default to showing ads
    var isLoading by remember { mutableStateOf(true) }
    var adLoaded by remember { mutableStateOf(false) }

    // Check if user is premium (premium users don't see ads)
    LaunchedEffect(Unit) {
        try {
            val isPremium = isPremiumUser(context)
            showAd = !isPremium
            Log.d("AdBanner", "Premium check: isPremium=$isPremium, showAd=$showAd")
        } catch (e: Exception) {
            Log.e("AdBanner", "Error checking premium status, showing ads by default", e)
            showAd = true
        }
        isLoading = false
    }

    if (!isLoading && showAd) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                factory = { ctx ->
                    AdView(ctx).apply {
                        setAdSize(AdSize.BANNER)
                        this.adUnitId = adUnitId

                        adListener = object : AdListener() {
                            override fun onAdFailedToLoad(error: LoadAdError) {
                                Log.e("AdBanner", "Ad failed to load: ${error.message}, code=${error.code}")
                                adLoaded = false
                            }

                            override fun onAdLoaded() {
                                Log.d("AdBanner", "Ad loaded successfully")
                                adLoaded = true
                            }
                        }

                        loadAd(AdRequest.Builder().build())
                    }
                }
            )

        }
    }
}

/**
 * Compact ad banner for inline placement
 */
@Composable
fun AdBannerCompact(
    modifier: Modifier = Modifier
) {
    AdBanner(
        modifier = modifier.height(50.dp),
        adUnitId = "ca-app-pub-4962910048695842/4209408906" // sfads banner
    )
}

/**
 * Check if the current user has a premium subscription
 * VPS Backend Only - checks local preferences (VPS doesn't have usage tracking yet)
 *
 * In VPS mode, premium status would need to be implemented via:
 * - VPS API endpoint for subscription status
 * - Local SharedPreferences cache of subscription status
 *
 * For now, default to showing ads (free tier behavior)
 */
private suspend fun isPremiumUser(context: Context): Boolean {
    return try {
        val authManager = VPSAuthManager.getInstance(context)
        val currentUser = authManager.getCurrentUser()

        if (currentUser == null) {
            // Not authenticated, show ads
            return false
        }

        // Check if user is admin (admins don't see ads)
        if (currentUser.admin) {
            return true
        }

        // VPS doesn't have subscription tracking yet
        // For now, default to free tier (show ads)
        // TODO: Add subscription endpoint to VPS when needed
        val prefs = context.getSharedPreferences("syncflow_subscription", Context.MODE_PRIVATE)
        val plan = prefs.getString("plan", "free")?.lowercase()
        val planExpiresAt = prefs.getLong("plan_expires_at", 0L)
        val now = System.currentTimeMillis()

        when (plan) {
            "lifetime", "3year" -> true
            "monthly", "yearly", "paid" -> planExpiresAt > now
            else -> false // Free and trial users see ads
        }
    } catch (e: Exception) {
        Log.e("AdBanner", "Error checking premium status", e)
        false // Show ads if we can't determine status
    }
}
