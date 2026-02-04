package com.phoneintegration.app.deals.local

import android.content.Context
import com.phoneintegration.app.deals.model.Deal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LocalDealEntity(
    val title: String,
    val image: String,
    val price: String,
    val url: String,
    val category: String = "Tech"
)

class LocalDealsLoader(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    fun loadFromAssets(): List<Deal> {
        return try {
            val text = context.assets.open("default_deals.json")
                .bufferedReader().use { it.readText() }

            val list = json.decodeFromString<List<LocalDealEntity>>(text)

            list.map {
                Deal(
                    id = it.url.hashCode().toString(),
                    title = it.title,
                    price = it.price,
                    image = it.image,
                    url = it.url,
                    category = it.category
                )
            }

        } catch (e: Exception) {
            emptyList()
        }
    }
}
