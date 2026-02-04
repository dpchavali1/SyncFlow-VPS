package com.phoneintegration.app.deals.notify

import android.content.Context
import com.phoneintegration.app.deals.model.Deal

class PriceDropEngine(private val context: Context) {

    private val history = PriceHistoryStore(context)

    suspend fun checkForPriceDrop(deals: List<Deal>): Deal? {
        var dropCandidate: Deal? = null
        var biggestDrop = 0.0

        for (d in deals) {
            val last = history.getLastPrice(d.id)

            if (last != null) {
                val current = extractPrice(d.price) ?: continue
                val diff = last - current

                if (diff >= 5.0 && diff > biggestDrop) {
                    biggestDrop = diff
                    dropCandidate = d
                }
            }

            // Always update price history
            extractPrice(d.price)?.let { history.savePrice(d.id, it) }
        }

        return dropCandidate
    }

    private fun extractPrice(text: String?): Double? {
        if (text == null) return null
        val clean = text.replace("$", "").replace(",", "")
        return clean.toDoubleOrNull()
    }
}