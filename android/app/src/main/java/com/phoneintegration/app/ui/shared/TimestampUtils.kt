package com.phoneintegration.app.ui.shared

import com.phoneintegration.app.SmsMessage
import java.text.SimpleDateFormat
import java.util.*

fun formatTimestamp(timestamp: Long): String {
    val now = Calendar.getInstance()
    val msg = Calendar.getInstance().apply { timeInMillis = timestamp }

    val sameDay = now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)

    return if (sameDay) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestamp))
    } else {
        SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
    }
}

// For date headers
fun SmsMessage.formattedDate(): String {
    return SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(this.date))
}
