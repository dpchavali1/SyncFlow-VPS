package com.phoneintegration.app.deals.storage

import android.content.Context
import android.content.SharedPreferences
import com.phoneintegration.app.deals.model.Deal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class DealCache(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("deal_cache", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    fun saveDeals(list: List<Deal>) {
        prefs.edit().putString("deals", json.encodeToString(list)).apply()
    }

    fun loadDeals(): List<Deal> {
        val text = prefs.getString("deals", null) ?: return emptyList()
        return try {
            json.decodeFromString(text)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
