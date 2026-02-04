package com.phoneintegration.app.ui.send

import com.phoneintegration.app.SmsViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.phoneintegration.app.utils.InputValidation


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendSmsScreen(
    viewModel: SmsViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var address by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var addressError by remember { mutableStateOf<String?>(null) }
    var bodyError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send SMS") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {

            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it
                    if (addressError != null) addressError = null
                },
                label = { Text("Phone Number") },
                modifier = Modifier.fillMaxWidth(),
                isError = addressError != null,
                supportingText = addressError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = body,
                onValueChange = {
                    body = it
                    if (bodyError != null) bodyError = null
                },
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 5,
                isError = bodyError != null,
                supportingText = bodyError?.let { { Text(it) } }
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    // Validate inputs
                    val phoneValidation = InputValidation.validatePhoneNumber(address)
                    val messageValidation = InputValidation.validateMessage(body)

                    addressError = phoneValidation.errorMessage
                    bodyError = messageValidation.errorMessage

                    if (phoneValidation.isValid && messageValidation.isValid) {
                        viewModel.sendSms(
                            phoneValidation.sanitizedValue ?: address,
                            messageValidation.sanitizedValue ?: body
                        ) { success ->
                            if (success) {
                                Toast.makeText(context, "Message sent", Toast.LENGTH_SHORT).show()
                                onBack()
                            } else {
                                Toast.makeText(context, "Send failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Send")
            }
        }
    }
}

