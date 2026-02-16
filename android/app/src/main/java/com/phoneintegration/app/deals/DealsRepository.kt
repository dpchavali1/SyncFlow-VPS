package com.phoneintegration.app.deals

import android.content.Context
import com.phoneintegration.app.deals.cloud.CloudDealsService
import com.phoneintegration.app.deals.model.Deal
import com.phoneintegration.app.deals.local.LocalDealsLoader
import com.phoneintegration.app.deals.storage.DealCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.random.Random

class DealsRepository(private val context: Context) {

    private val cloud = CloudDealsService()
    private val local = LocalDealsLoader(context)
    private val cache = DealCache(context)

    private val dealSources: List<String>
        get() {
            val baseUrl = "https://raw.githubusercontent.com/dpchavali1/syncflow-deals/main"
            return if (isIndianUser()) {
                listOf("$baseUrl/deals-in.json")
            } else {
                listOf("$baseUrl/deals.json", "$baseUrl/deals2.json", "$baseUrl/deals3.json")
            }
        }

    private fun isIndianUser(): Boolean {
        val phone = com.phoneintegration.app.ui.components.getRegisteredPhoneNumber(context)
        return phone?.startsWith("+91") == true
    }

    // ---------------------------------------------------------
    // LOAD DEALS (Multiple Sources + Cache + Personalization)
    // ---------------------------------------------------------
    suspend fun getDeals(forceRefresh: Boolean = false): List<Deal> = withContext(Dispatchers.IO) {

        if (!forceRefresh) {
            // 1) Use cache first
            val cached = cache.loadDeals()
            if (cached.isNotEmpty()) {
                // Clean URLs in case cached deals have malformed URLs
                val cleanedCached = cached.map { deal ->
                    deal.copy(url = cleanUrl(deal.url))
                }
                return@withContext cleanedCached.sortedByDescending { it.timestamp }
            }
        }

        // 2) Try loading from primary source
        val cloudDeals = cloud.loadFromUrl(dealSources.first())

        if (cloudDeals.isNotEmpty()) {
            cache.saveDeals(cloudDeals)
            return@withContext cloudDeals.sortedByDescending { it.timestamp }
        }

        // 3) Local fallback
        return@withContext local.loadFromAssets().sortedByDescending { it.timestamp }
    }

    /**
     * Extract price from deal price string
     */
    private fun extractPrice(priceText: String): Double? {
        return priceText.replace("$", "").replace(",", "").toDoubleOrNull()
    }

    /**
     * Clean URL by removing HTML artifacts and malformed tags
     */
    private fun cleanUrl(url: String): String {
        return url
            // Remove HTML closing tags
            .replace("</a></span>", "")
            .replace("</span>", "")
            .replace("</a>", "")
            .replace("[link]", "")
            // Remove HTML entities that might be malformed
            .replace("\"", "")
            .replace(">", "")
            // Clean up any remaining artifacts
            .replace(Regex("(\\?|&)amp%3B.*"), "") // Remove malformed amp entities
            .replace(Regex("(\\?|&)amp;.*"), "") // Remove amp entities
            .trim()
            // Ensure it starts with https://
            .let { if (it.startsWith("http")) it else "https://$it" }
            // Validate it's a proper URL
            .let { cleaned ->
                try {
                    java.net.URL(cleaned)
                    cleaned
                } catch (e: Exception) {
                    // If URL is malformed, try to construct a proper Amazon URL
                    val amazonDomain = if (isIndianUser()) "amazon.in" else "amazon.com"
                    if (cleaned.contains("amazon.com") || cleaned.contains("amazon.in") || cleaned.contains("amzn.to")) {
                        val asinPattern = Regex("(?:dp|gp/product)/([A-Z0-9]{10})")
                        val asinMatch = asinPattern.find(cleaned)
                        if (asinMatch != null) {
                            "https://www.$amazonDomain/dp/${asinMatch.groupValues[1]}"
                        } else {
                            "https://www.$amazonDomain"
                        }
                    } else {
                        "https://www.$amazonDomain"
                    }
                }
            }
    }



    // ---------------------------------------------------------
    // GET DEALS BY CATEGORY
    // ---------------------------------------------------------
    suspend fun getDealsByCategory(category: String): List<Deal> = withContext(Dispatchers.IO) {
        val allDeals = getDeals()
        return@withContext allDeals.filter { it.category.equals(category, ignoreCase = true) }
    }

    // ---------------------------------------------------------
    // SEARCH DEALS
    // ---------------------------------------------------------
    suspend fun searchDeals(query: String): List<Deal> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getDeals()

        val lowerQuery = query.lowercase()
        val allDeals = getDeals()

        return@withContext allDeals.filter { deal ->
            deal.title.lowercase().contains(lowerQuery) ||
            deal.category.lowercase().contains(lowerQuery) ||
            deal.price.contains(query) // Allow searching by price
        }
    }



    // ---------------------------------------------------------
    // Wrapper used by SmsViewModel.refreshDeals()
    // ---------------------------------------------------------
    suspend fun refreshDeals(): Boolean = withContext(Dispatchers.IO) {
        try {
            val fresh = cloud.loadFromUrl(dealSources.first())
            if (fresh.isNotEmpty()) {
                cache.saveDeals(fresh)
                return@withContext true
            }
            return@withContext false
        } catch (e: Exception) {
            false
        }
    }
}
