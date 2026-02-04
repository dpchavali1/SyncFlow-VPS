package com.phoneintegration.app.deals.cloud

import com.phoneintegration.app.deals.model.Deal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Serializable
data class CloudDealEntity(
    val title: String,
    val image: String,
    val price: String,
    val url: String,
    val category: String = "Tech",
    val timestamp: Long = 0
)

@Serializable
data class CloudDealsWrapper(
    val deals: List<CloudDealEntity>
)

class CloudDealsService {

    private val json = Json { ignoreUnknownKeys = true }

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
            .let { cleaned ->
                // Ensure it starts with https://
                val withProtocol = if (cleaned.startsWith("http")) cleaned else "https://$cleaned"
                // Validate it's a proper URL
                try {
                    java.net.URL(withProtocol)
                    withProtocol
                } catch (e: Exception) {
                    // If URL is malformed, try to construct a proper Amazon URL
                    if (cleaned.contains("amazon.com") || cleaned.contains("amzn.to")) {
                        // Extract ASIN if possible
                        val asinPattern = Regex("(?:dp|gp/product)/([A-Z0-9]{10})")
                        val asinMatch = asinPattern.find(cleaned)
                        if (asinMatch != null) {
                            "https://www.amazon.com/dp/${asinMatch.groupValues[1]}"
                        } else {
                            "https://www.amazon.com"
                        }
                    } else {
                        "https://www.amazon.com"
                    }
                }
            }
    }

    suspend fun loadFromUrl(url: String): List<Deal> = withContext(Dispatchers.IO) {
        return@withContext try {

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000

            if (conn.responseCode != 200) return@withContext emptyList()

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val wrapper = json.decodeFromString<CloudDealsWrapper>(text)

            wrapper.deals.map {
                Deal(
                    id = it.url.hashCode().toString(),
                    title = it.title,
                    price = it.price,
                    image = it.image,
                    url = cleanUrl(it.url),
                    category = it.category
                )
            }

        } catch (e: Exception) {
            emptyList()
        }
    }
}
