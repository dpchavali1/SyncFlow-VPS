package com.phoneintegration.app.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Smart Reply suggestion chips shown above the message input box.
 *
 * @param suggestions A list of ML Kit–generated replies.
 * @param onSendSuggestion Callback when user taps a suggestion.
 */
@Composable
fun SmartReplySection(
    suggestions: List<String>,
    onSendSuggestion: (String) -> Unit
) {
    if (suggestions.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Quick Replies",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
        )

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
        ) {

            suggestions.forEach { reply ->
                AssistChip(
                    onClick = { onSendSuggestion(reply) },
                    label = {
                        Text(reply)
                    },
                    modifier = Modifier
                        .padding(end = 8.dp),
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
