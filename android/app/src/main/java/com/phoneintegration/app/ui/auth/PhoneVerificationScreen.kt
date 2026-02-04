package com.phoneintegration.app.ui.auth

import android.app.Activity
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneintegration.app.auth.PhoneAuthManager
import com.phoneintegration.app.auth.PhoneVerificationState
import kotlinx.coroutines.delay

/**
 * Phone verification screen for first-time users.
 * This establishes a stable user identity tied to their phone number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneVerificationScreen(
    onVerificationComplete: (userId: String) -> Unit,
    onSkip: (() -> Unit)? = null // Optional skip for testing/development
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val phoneAuthManager = remember { PhoneAuthManager.getInstance(context) }
    val verificationState by phoneAuthManager.verificationState.collectAsState()

    var phoneNumber by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var countryCode by remember { mutableStateOf("+1") }

    // Handle verification success
    LaunchedEffect(verificationState) {
        if (verificationState is PhoneVerificationState.Verified) {
            val verified = verificationState as PhoneVerificationState.Verified
            delay(1000) // Brief pause to show success
            onVerificationComplete(verified.userId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify Your Phone") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header icon and text
            Icon(
                imageVector = Icons.Default.Phone,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Welcome to SyncFlow",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Verify your phone number to sync messages across your devices",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Content based on state
            AnimatedContent(
                targetState = verificationState,
                transitionSpec = {
                    fadeIn() + slideInVertically() togetherWith fadeOut() + slideOutVertically()
                },
                label = "verification_state"
            ) { state ->
                when (state) {
                    is PhoneVerificationState.Idle,
                    is PhoneVerificationState.SendingCode -> {
                        PhoneInputSection(
                            countryCode = countryCode,
                            phoneNumber = phoneNumber,
                            onCountryCodeChange = { countryCode = it },
                            onPhoneNumberChange = { phoneNumber = it },
                            onSubmit = {
                                activity?.let {
                                    phoneAuthManager.startVerification(
                                        "$countryCode$phoneNumber",
                                        it
                                    )
                                }
                            },
                            isLoading = state is PhoneVerificationState.SendingCode
                        )
                    }

                    is PhoneVerificationState.CodeSent -> {
                        OtpInputSection(
                            phoneNumber = state.phoneNumber,
                            otpCode = otpCode,
                            onOtpChange = { otpCode = it },
                            onSubmit = {
                                phoneAuthManager.verifyCode(otpCode, state.phoneNumber)
                            },
                            onResend = {
                                activity?.let {
                                    phoneAuthManager.resendCode(state.phoneNumber, it)
                                }
                            },
                            onChangeNumber = {
                                phoneAuthManager.resetState()
                                otpCode = ""
                            }
                        )
                    }

                    is PhoneVerificationState.Verifying -> {
                        VerifyingSection()
                    }

                    is PhoneVerificationState.Verified -> {
                        SuccessSection(phoneNumber = state.phoneNumber)
                    }

                    is PhoneVerificationState.Error -> {
                        ErrorSection(
                            message = state.message,
                            onRetry = {
                                phoneAuthManager.resetState()
                                otpCode = ""
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Privacy note
            Text(
                text = "Your phone number is used to identify you across devices and is stored securely.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // Skip option for development/testing
            if (onSkip != null) {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onSkip) {
                    Text("Skip for now (Dev only)")
                }
            }
        }
    }
}

@Composable
private fun PhoneInputSection(
    countryCode: String,
    phoneNumber: String,
    onCountryCodeChange: (String) -> Unit,
    onPhoneNumberChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isLoading: Boolean
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Phone number input
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Country code
            OutlinedTextField(
                value = countryCode,
                onValueChange = { if (it.length <= 4) onCountryCodeChange(it) },
                modifier = Modifier.width(80.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next
                ),
                singleLine = true,
                enabled = !isLoading
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Phone number
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { if (it.length <= 15) onPhoneNumberChange(it.filter { c -> c.isDigit() }) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("(555) 123-4567") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (phoneNumber.length >= 10) onSubmit()
                    }
                ),
                singleLine = true,
                enabled = !isLoading
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Submit button
        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = phoneNumber.length >= 10 && !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Sms,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Send Verification Code")
            }
        }
    }
}

@Composable
private fun OtpInputSection(
    phoneNumber: String,
    otpCode: String,
    onOtpChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
    onChangeNumber: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    var resendEnabled by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(60) }

    // Countdown timer for resend
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
        resendEnabled = true
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Sms,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Enter verification code",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Code sent to $phoneNumber",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // OTP input boxes
        OtpTextField(
            otpText = otpCode,
            onOtpTextChange = { value, complete ->
                onOtpChange(value)
                if (complete) onSubmit()
            },
            modifier = Modifier.focusRequester(focusRequester)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Verify button
        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = otpCode.length == 6
        ) {
            Text("Verify")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Resend and change number options
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(
                onClick = onResend,
                enabled = resendEnabled
            ) {
                if (resendEnabled) {
                    Text("Resend Code")
                } else {
                    Text("Resend in ${countdown}s")
                }
            }

            TextButton(onClick = onChangeNumber) {
                Text("Change Number")
            }
        }
    }
}

@Composable
private fun OtpTextField(
    otpText: String,
    onOtpTextChange: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = otpText,
        onValueChange = {
            if (it.length <= 6 && it.all { c -> c.isDigit() }) {
                onOtpTextChange(it, it.length == 6)
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done
        ),
        modifier = modifier,
        decorationBox = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(6) { index ->
                    val char = when {
                        index >= otpText.length -> ""
                        else -> otpText[index].toString()
                    }
                    val isFocused = otpText.length == index

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .border(
                                width = 2.dp,
                                color = when {
                                    isFocused -> MaterialTheme.colorScheme.primary
                                    char.isNotEmpty() -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                            .background(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = char,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun VerifyingSection() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Verifying...",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SuccessSection(phoneNumber: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = Color(0xFF4CAF50)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Phone Verified!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4CAF50)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = phoneNumber,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Setting up your account...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorSection(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Verification Failed",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Try Again")
        }
    }
}
