package com.phoneintegration.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

/**
 * Service for fetching GIFs from Tenor API.
 * Uses Tenor's free tier API (requires API key from Google Cloud Console).
 */
class TenorApiService {

    companion object {
        private const val TAG = "TenorApiService"

        // Tenor API base URL
        private const val BASE_URL = "https://tenor.googleapis.com/v2"

        // Default API key for development (replace with your own for production)
        // Get your API key at: https://developers.google.com/tenor/guides/quickstart
        private const val API_KEY = "***REMOVED***"

        // Client key for tracking
        private const val CLIENT_KEY = "syncflow_android"

        // Media filter for optimal mobile experience
        private const val MEDIA_FILTER = "tinygif,gif"
    }

    /**
     * Data class representing a GIF from Tenor.
     */
    data class TenorGif(
        val id: String,
        val title: String,
        val previewUrl: String,     // Small preview for grid
        val fullUrl: String,        // Full size GIF for sending
        val width: Int,
        val height: Int
    )

    /**
     * Search for GIFs by query.
     *
     * @param query Search term
     * @param limit Maximum number of results (default 20)
     * @return List of GIFs matching the query
     */
    suspend fun searchGifs(
        query: String,
        limit: Int = 20
    ): List<TenorGif> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL/search?" +
                    "q=$encodedQuery" +
                    "&key=$API_KEY" +
                    "&client_key=$CLIENT_KEY" +
                    "&limit=$limit" +
                    "&media_filter=$MEDIA_FILTER" +
                    "&contentfilter=medium" // Safe content filter

            Log.d(TAG, "Searching GIFs: $query")
            val response = URL(url).readText()
            parseGifResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching GIFs", e)
            emptyList()
        }
    }

    /**
     * Get trending/featured GIFs.
     *
     * @param limit Maximum number of results (default 20)
     * @return List of trending GIFs
     */
    suspend fun getTrendingGifs(
        limit: Int = 20
    ): List<TenorGif> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/featured?" +
                    "key=$API_KEY" +
                    "&client_key=$CLIENT_KEY" +
                    "&limit=$limit" +
                    "&media_filter=$MEDIA_FILTER" +
                    "&contentfilter=medium"

            Log.d(TAG, "Fetching trending GIFs")
            val response = URL(url).readText()
            parseGifResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching trending GIFs", e)
            emptyList()
        }
    }

    /**
     * Get search suggestions for autocomplete.
     *
     * @param query Partial search term
     * @param limit Maximum number of suggestions (default 5)
     * @return List of suggested search terms
     */
    suspend fun getSearchSuggestions(
        query: String,
        limit: Int = 5
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL/autocomplete?" +
                    "q=$encodedQuery" +
                    "&key=$API_KEY" +
                    "&client_key=$CLIENT_KEY" +
                    "&limit=$limit"

            val response = URL(url).readText()
            val json = JSONObject(response)
            val results = json.optJSONArray("results") ?: return@withContext emptyList()

            (0 until results.length()).map { results.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting search suggestions", e)
            emptyList()
        }
    }

    /**
     * Get GIF categories for browsing.
     *
     * @return List of category names with preview GIFs
     */
    suspend fun getCategories(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/categories?" +
                    "key=$API_KEY" +
                    "&client_key=$CLIENT_KEY" +
                    "&type=featured"

            val response = URL(url).readText()
            val json = JSONObject(response)
            val tags = json.optJSONArray("tags") ?: return@withContext emptyList()

            (0 until tags.length()).map {
                val tag = tags.getJSONObject(it)
                val name = tag.optString("searchterm", "")
                val image = tag.optString("image", "")
                name to image
            }.filter { it.first.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting categories", e)
            emptyList()
        }
    }

    /**
     * Parse the Tenor API response and extract GIF data.
     */
    private fun parseGifResponse(response: String): List<TenorGif> {
        return try {
            val json = JSONObject(response)
            val results = json.optJSONArray("results") ?: return emptyList()

            (0 until results.length()).mapNotNull { index ->
                try {
                    val item = results.getJSONObject(index)
                    val id = item.optString("id", "")
                    val title = item.optString("title", "GIF")

                    val mediaFormats = item.optJSONObject("media_formats")

                    // Get tinygif for preview (smaller, faster loading)
                    val tinyGif = mediaFormats?.optJSONObject("tinygif")
                    val previewUrl = tinyGif?.optString("url", "") ?: ""

                    // Get regular gif for full size
                    val gif = mediaFormats?.optJSONObject("gif")
                    val fullUrl = gif?.optString("url", "") ?: previewUrl

                    // Get dimensions from tinygif for grid layout
                    val width = tinyGif?.optInt("dims_0", 100) ?: 100
                    val height = tinyGif?.optInt("dims_1", 100) ?: 100

                    if (id.isNotEmpty() && previewUrl.isNotEmpty()) {
                        TenorGif(
                            id = id,
                            title = title,
                            previewUrl = previewUrl,
                            fullUrl = fullUrl,
                            width = width,
                            height = height
                        )
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing GIF item at index $index", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing GIF response", e)
            emptyList()
        }
    }
}
