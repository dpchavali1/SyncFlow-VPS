package com.phoneintegration.app.deals.notify

import android.content.Context
import androidx.work.*
import com.phoneintegration.app.deals.DealsRepository
import com.phoneintegration.app.deals.notify.DealNotificationManager
import com.phoneintegration.app.deals.notify.PriceDropEngine

class SendDealNotificationWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    private val repo = DealsRepository(ctx)
    private val dropEngine = PriceDropEngine(ctx)
    private val prefs = ctx.getSharedPreferences("deal_notifications", Context.MODE_PRIVATE)

    override suspend fun doWork(): Result {
        val deals = repo.getDeals()

        if (deals.isEmpty()) return Result.success()

        // Check for price drops first (highest priority)
        val drop = dropEngine.checkForPriceDrop(deals)
        if (drop != null) {
            DealNotificationManager.showPriceDropNotification(
                context = applicationContext,
                deal = drop
            )
            return Result.success()
        }

        // Select a random deal (simple approach)
        val deal = deals.random()
        DealNotificationManager.showDealNotification(
            context = applicationContext,
            deal = deal
        )

        return Result.success()
    }
}
