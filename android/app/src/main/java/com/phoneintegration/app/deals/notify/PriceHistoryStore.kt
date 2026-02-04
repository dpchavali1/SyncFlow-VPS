package com.phoneintegration.app.deals.notify

import android.content.Context
import android.content.SharedPreferences

class PriceHistoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("price_history", Context.MODE_PRIVATE)

    fun savePrice(dealId: String, price: Double) {
        prefs.edit().putFloat(dealId, price.toFloat()).apply()
    }

    fun getLastPrice(dealId: String): Double? {
        val v = prefs.getFloat(dealId, -1f)
        return if (v == -1f) null else v.toDouble()
    }

    fun clearOldData() {
        // Basic cleanup — keeps prefs small
        prefs.edit().clear().apply()
    }
}