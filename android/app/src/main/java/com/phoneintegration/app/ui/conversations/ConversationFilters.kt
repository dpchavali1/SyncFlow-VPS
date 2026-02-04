package com.phoneintegration.app.ui.conversations

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.MessageCategory

@Composable
fun ConversationFilters(
    selected: MessageCategory?,
    onSelect: (MessageCategory?) -> Unit
) {
    Row(modifier = Modifier.padding(8.dp)) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All") }
        )
        Spacer(Modifier.width(6.dp))

        MessageCategory.values().forEach { cat ->
            FilterChip(
                selected = selected == cat,
                onClick = { onSelect(cat) },
                label = { Text(cat.name) }
            )
            Spacer(Modifier.width(6.dp))
        }
    }
}
