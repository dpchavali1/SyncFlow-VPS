package com.phoneintegration.app.deals.model

import kotlinx.serialization.Serializable

@Serializable
data class Deal(
    val id: String,
    val title: String,
    val price: String,
    val image: String,
    val url: String,
    val category: String = "General",
    val timestamp: Long = 0,
    val discount: Int = 0,          // Discount percentage (e.g., 30 for 30% off)
    val rating: String = "N/A",     // Star rating
    val reviews: String = "N/A",    // Number of reviews
    val score: Int = 0              // Deal quality score
)
