package com.phoneintegration.app.deals.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.phoneintegration.app.R
import com.phoneintegration.app.deals.model.Deal

object DealNotificationManager {

    private const val CHANNEL_DEALS = "syncflow_deals_channel"
    private const val CHANNEL_DROPS = "syncflow_price_drops"

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DEALS,
                    "SyncFlow Deals",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DROPS,
                    "Price Drops",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    suspend fun showDealNotification(context: Context, deal: Deal) {
        ensureChannels(context)

        val image = loadBitmap(context, deal.image)

        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            Uri.parse("${deal.url}?tag=syncflow-20")
        )

        val pi = android.app.PendingIntent.getActivity(
            context,
            deal.id.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_DEALS)
            .setSmallIcon(R.drawable.ic_syncflow)
            .setContentTitle("🔥 New Deal!")
            .setContentText("${deal.title} — ${deal.price}")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(image)
                    .setSummaryText("${deal.title} — ${deal.price}")
            )

        NotificationManagerCompat.from(context)
            .notify(deal.id.hashCode(), builder.build())
    }

    @android.annotation.SuppressLint("MissingPermission")
    suspend fun showPriceDropNotification(context: Context, deal: Deal) {
        ensureChannels(context)

        val image = loadBitmap(context, deal.image)

        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            Uri.parse("${deal.url}?tag=syncflow-20")
        )

        val pi = android.app.PendingIntent.getActivity(
            context,
            deal.id.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_DROPS)
            .setSmallIcon(R.drawable.ic_syncflow)
            .setContentTitle("🔥 Price Dropped!")
            .setContentText("${deal.title} is now ${deal.price}")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(image)
                    .setSummaryText("${deal.title} — ${deal.price}")
            )

        NotificationManagerCompat.from(context)
            .notify(deal.id.hashCode(), builder.build())
    }

    private suspend fun loadBitmap(context: Context, url: String?): android.graphics.Bitmap? {
        if (url.isNullOrBlank()) return null

        val loader = ImageLoader(context)
        val req = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .build()

        val res = loader.execute(req)
        return if (res is SuccessResult)
            (res.drawable as android.graphics.drawable.BitmapDrawable).bitmap
        else null
    }
}
