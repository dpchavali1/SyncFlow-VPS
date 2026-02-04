package com.phoneintegration.app.deals.amazon

import com.phoneintegration.app.deals.model.Deal
import kotlinx.coroutines.delay

class AmazonApiService(
    val accessKey: String,
    val secretKey: String,
    val affiliateTag: String
) {
    /**
     * Placeholder Amazon search.
     * Amazon PA-API access requires 10 sales → so we disable real API calls.
     *
     * This function returns empty list quickly (no network call).
     */
    suspend fun searchDeals(): List<Deal> {
        // tiny delay so UI doesn't feel "instant-fake"
        delay(50)
        return emptyList()
    }
}
