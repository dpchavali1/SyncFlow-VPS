package com.phoneintegration.app.ui.components

import android.app.Activity
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
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.phoneintegration.app.vps.VPSAuthManager

/**
 * Ad banner component that shows ads only to free/trial users.
 * Premium subscribers don't see ads.
 * Requests GDPR consent via UMP SDK before loading personalized ads.
 */
@Composable
fun AdBanner(
    modifier: Modifier = Modifier,
    adUnitId: String = "ca-app-pub-4962910048695842/4209408906" // sfads banner
) {
    val context = LocalContext.current
    var showAd by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(true) }
    var adLoaded by remember { mutableStateOf(false) }
    var consentReady by remember { mutableStateOf(false) }

    // Check premium status and request consent
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

        // Request UMP consent if showing ads
        if (showAd) {
            requestConsent(context) { ready ->
                consentReady = ready
            }
        }
    }

    if (!isLoading && showAd && consentReady) {
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
 * Request GDPR consent using the Google UMP SDK.
 * Shows consent form for EU users; auto-consents for others.
 */
private fun requestConsent(context: Context, onResult: (Boolean) -> Unit) {
    val params = ConsentRequestParameters.Builder()
        .setTagForUnderAgeOfConsent(false)
        .build()

    val consentInfo = UserMessagingPlatform.getConsentInformation(context)
    consentInfo.requestConsentInfoUpdate(
        context as? Activity ?: return onResult(true),
        params,
        {
            // Consent info updated — show form if required
            if (consentInfo.isConsentFormAvailable) {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                    context as Activity
                ) { formError ->
                    if (formError != null) {
                        Log.e("AdBanner", "Consent form error: ${formError.message}")
                    }
                    // Can load ads regardless — UMP handles non-personalized ads for declined consent
                    onResult(consentInfo.canRequestAds())
                }
            } else {
                // No form needed (non-EU user or already consented)
                onResult(consentInfo.canRequestAds())
            }
        },
        { requestError ->
            Log.e("AdBanner", "Consent request failed: ${requestError.message}")
            // Load ads anyway with non-personalized fallback
            onResult(true)
        }
    )
}

/**
 * Check if the current user has a premium subscription
 */
private suspend fun isPremiumUser(context: Context): Boolean {
    return try {
        val authManager = VPSAuthManager.getInstance(context)
        val currentUser = authManager.getCurrentUser()

        if (currentUser == null) {
            return false
        }

        if (currentUser.admin) {
            return true
        }

        val prefs = context.getSharedPreferences("syncflow_subscription", Context.MODE_PRIVATE)
        val plan = prefs.getString("plan", "free")?.lowercase()
        val planExpiresAt = prefs.getLong("plan_expires_at", 0L)
        val now = System.currentTimeMillis()

        when (plan) {
            "lifetime", "3year" -> true
            "monthly", "yearly", "paid" -> planExpiresAt > now
            else -> false
        }
    } catch (e: Exception) {
        Log.e("AdBanner", "Error checking premium status", e)
        false
    }
}
