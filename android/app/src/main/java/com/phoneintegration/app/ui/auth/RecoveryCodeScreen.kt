package com.phoneintegration.app.ui.auth

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneintegration.app.auth.RecoveryCodeManager
import kotlinx.coroutines.launch

/**
 * Recovery code setup/restore screen.
 * Shows on first launch to help users protect their data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryCodeScreen(
    onSetupComplete: (userId: String) -> Unit
) {
    val context = LocalContext.current
    val recoveryManager = remember { RecoveryCodeManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var screenState by remember { mutableStateOf<RecoveryScreenState>(RecoveryScreenState.Initial) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var generatedCode by remember { mutableStateOf<String?>(null) }
    var enteredCode by remember { mutableStateOf("") }
    var codeCopied by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account Setup") },
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
            AnimatedContent(
                targetState = screenState,
                transitionSpec = {
                    fadeIn() + slideInHorizontally() togetherWith fadeOut() + slideOutHorizontally()
                },
                label = "screen_state"
            ) { state ->
                when (state) {
                    RecoveryScreenState.Initial -> {
                        InitialScreen(
                            onNewSetup = {
                                screenState = RecoveryScreenState.GeneratingCode
                                scope.launch {
                                    isLoading = true
                                    val result = recoveryManager.setupRecoveryCode()
                                    isLoading = false
                                    result.fold(
                                        onSuccess = { code ->
                                            generatedCode = code
                                            screenState = RecoveryScreenState.ShowCode
                                        },
                                        onFailure = { e ->
                                            errorMessage = e.message
                                            screenState = RecoveryScreenState.Initial
                                        }
                                    )
                                }
                            },
                            onHaveCode = {
                                screenState = RecoveryScreenState.EnterCode
                            },
                            onSkip = {
                                screenState = RecoveryScreenState.SkipWarning
                            }
                        )
                    }

                    RecoveryScreenState.GeneratingCode -> {
                        LoadingScreen(message = "Setting up your account...")
                    }

                    RecoveryScreenState.ShowCode -> {
                        ShowCodeScreen(
                            code = generatedCode ?: "",
                            codeCopied = codeCopied,
                            onCopyCode = {
                                clipboardManager.setText(AnnotatedString(generatedCode ?: ""))
                                codeCopied = true
                            },
                            onContinue = {
                                recoveryManager.getEffectiveUserId()?.let { userId ->
                                    onSetupComplete(userId)
                                }
                            }
                        )
                    }

                    RecoveryScreenState.EnterCode -> {
                        EnterCodeScreen(
                            code = enteredCode,
                            onCodeChange = { enteredCode = it.uppercase() },
                            errorMessage = errorMessage,
                            isLoading = isLoading,
                            onSubmit = {
                                scope.launch {
                                    isLoading = true
                                    errorMessage = null
                                    val result = recoveryManager.recoverWithCode(enteredCode)
                                    isLoading = false
                                    result.fold(
                                        onSuccess = { userId ->
                                            onSetupComplete(userId)
                                        },
                                        onFailure = { e ->
                                            errorMessage = e.message
                                        }
                                    )
                                }
                            },
                            onBack = {
                                screenState = RecoveryScreenState.Initial
                                errorMessage = null
                                enteredCode = ""
                            }
                        )
                    }

                    RecoveryScreenState.SkipWarning -> {
                        SkipWarningScreen(
                            isLoading = isLoading,
                            onConfirmSkip = {
                                scope.launch {
                                    isLoading = true
                                    val result = recoveryManager.skipSetup()
                                    isLoading = false
                                    result.fold(
                                        onSuccess = { userId ->
                                            onSetupComplete(userId)
                                        },
                                        onFailure = { e ->
                                            errorMessage = e.message
                                            screenState = RecoveryScreenState.Initial
                                        }
                                    )
                                }
                            },
                            onGoBack = {
                                screenState = RecoveryScreenState.Initial
                            }
                        )
                    }
                }
            }
        }
    }
}

private enum class RecoveryScreenState {
    Initial,
    GeneratingCode,
    ShowCode,
    EnterCode,
    SkipWarning
}

@Composable
private fun InitialScreen(
    onNewSetup: () -> Unit,
    onHaveCode: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Protect Your Data",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Set up a recovery code to keep your messages safe if you reinstall the app or switch devices.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(40.dp))

        // New setup button
        Button(
            onClick = onNewSetup,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create Recovery Code")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Have a code button
        OutlinedButton(
            onClick = onHaveCode,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("I Have a Recovery Code")
        }

        Spacer(modifier = Modifier.weight(1f))

        // Skip option
        TextButton(onClick = onSkip) {
            Text(
                text = "Skip for now",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Without a recovery code, reinstalling the app will result in data loss.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LoadingScreen(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ShowCodeScreen(
    code: String,
    codeCopied: Boolean,
    onCopyCode: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = Color(0xFF4CAF50)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Your Recovery Code",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Save this code somewhere safe. You'll need it to recover your account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Code display box
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    ),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onCopyCode,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (codeCopied) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (codeCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (codeCopied) "Copied!" else "Copy Code")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Warning
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Write this code down or save it in a secure location. If you lose this code and reinstall the app, your data cannot be recovered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = codeCopied
        ) {
            Text(if (codeCopied) "Continue to App" else "Copy the code first")
        }

        if (!codeCopied) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Please copy or write down the code before continuing",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnterCodeScreen(
    code: String,
    onCodeChange: (String) -> Unit,
    errorMessage: String?,
    isLoading: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Key,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Enter Recovery Code",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enter the recovery code you saved when you first set up the app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = code,
            onValueChange = {
                // Allow only valid characters and limit length
                val filtered = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }
                if (filtered.length <= 19) { // SYNC-XXXX-XXXX-XXXX = 19 chars
                    onCodeChange(filtered)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Recovery Code") },
            placeholder = { Text("SYNC-XXXX-XXXX-XXXX") },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (code.length >= 16 && !isLoading) onSubmit() }
            ),
            isError = errorMessage != null,
            supportingText = if (errorMessage != null) {
                { Text(errorMessage, color = MaterialTheme.colorScheme.error) }
            } else null,
            enabled = !isLoading
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = code.replace("-", "").length >= 16 && !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Login,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Recover Account")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onBack, enabled = !isLoading) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Back")
        }
    }
}

@Composable
private fun SkipWarningScreen(
    isLoading: Boolean,
    onConfirmSkip: () -> Unit,
    onGoBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Are You Sure?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Without a recovery code:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                Spacer(modifier = Modifier.height(12.dp))

                WarningItem("Reinstalling the app = ALL data lost")
                WarningItem("Paired devices will be disconnected")
                WarningItem("Messages won't sync to new install")
                WarningItem("No way to recover your account")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Go back button (recommended)
        Button(
            onClick = onGoBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isLoading
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Set Up Recovery Code")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Skip anyway button
        OutlinedButton(
            onClick = onConfirmSkip,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text("Skip Anyway (Not Recommended)")
            }
        }
    }
}

@Composable
private fun WarningItem(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}
