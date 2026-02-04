package com.phoneintegration.app.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.utils.InputValidation

@Composable
fun MessageInputBar(
    onSend: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                // Clear error when user starts typing
                if (errorMessage != null) {
                    errorMessage = null
                }
            },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a message…") },
            isError = errorMessage != null,
            supportingText = errorMessage?.let { { Text(it) } }
        )

        IconButton(
            onClick = {
                val validation = InputValidation.validateMessage(text)
                if (validation.isValid) {
                    onSend(validation.sanitizedValue ?: text.trim())
                    text = ""
                    errorMessage = null
                } else {
                    errorMessage = validation.errorMessage
                }
            }
        ) {
            Icon(Icons.Default.Send, contentDescription = "Send")
        }
    }
}