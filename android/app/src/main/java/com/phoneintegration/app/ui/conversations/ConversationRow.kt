
package com.phoneintegration.app.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.MessageCategory
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.ui.shared.formatTimestamp

@Composable
fun ConversationRow(
    sms: SmsMessage,
    category: MessageCategory,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        // Avatar circle with emoji
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = category.emoji,
                style = MaterialTheme.typography.headlineSmall
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sms.contactName ?: sms.address,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = sms.body.take(40),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatTimestamp(sms.date),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
