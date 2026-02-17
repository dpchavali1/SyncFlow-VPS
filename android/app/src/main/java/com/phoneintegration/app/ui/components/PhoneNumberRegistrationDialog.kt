package com.phoneintegration.app.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.SimManager
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PhoneNumberRegistration"
private const val PREFS_NAME = "phone_registration_prefs"
private const val KEY_PHONE_REGISTERED = "phone_number_registered"
private const val KEY_REGISTERED_PHONE = "registered_phone_number"

/**
 * SIM-only phone number picker dialog.
 * Shows available SIM cards as selectable options — no manual entry.
 *
 * @param mandatory If true, dialog cannot be dismissed/skipped (used from MainNavigation)
 * @param onDismiss Called when dialog is dismissed (only effective when mandatory=false)
 * @param onRegistered Called when phone number is successfully registered
 */
@Composable
fun PhoneNumberRegistrationDialog(
    mandatory: Boolean = false,
    onDismiss: () -> Unit,
    onRegistered: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var isRegistering by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var sims by remember { mutableStateOf<List<SimManager.SimInfo>>(emptyList()) }
    var selectedSim by remember { mutableStateOf<SimManager.SimInfo?>(null) }

    // Load SIM cards on first display
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val simManager = SimManager(context)
            sims = simManager.getActiveSims()
        }
        isLoading = false
        // Auto-select if only one SIM with a readable number
        val selectableSims = sims.filter { hasReadableNumber(it) }
        if (selectableSims.size == 1) {
            selectedSim = selectableSims[0]
        }
    }

    AlertDialog(
        onDismissRequest = if (mandatory) { {} } else onDismiss,
        icon = {
            Icon(
                Icons.Default.Phone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                "Select Phone Number",
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Reading SIM cards...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (sims.isEmpty()) {
                    Text(
                        "No SIM cards found. Please ensure your SIM is inserted and phone permissions are granted.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    val selectableSims = sims.filter { hasReadableNumber(it) }
                    val unreadableSims = sims.filter { !hasReadableNumber(it) }

                    Text(
                        "Choose which phone number to register for messaging and video calls.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Show selectable SIMs (have readable numbers)
                    selectableSims.forEach { sim ->
                        val isSelected = selectedSim?.subscriptionId == sim.subscriptionId
                        val normalized = normalizePhoneNumber(sim.phoneNumber!!)

                        OutlinedCard(
                            onClick = { selectedSim = sim },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.outlineVariant
                            ),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else
                                    MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.SimCard,
                                    contentDescription = null,
                                    tint = if (isSelected)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = normalized,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = buildString {
                                            append(sim.carrierName)
                                            if (sim.isEmbedded) append(" (eSIM)")
                                            append(" - Slot ${sim.slotIndex + 1}")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedSim = sim }
                                )
                            }
                        }
                    }

                    // Show unreadable SIMs (detected but number not available)
                    unreadableSims.forEach { sim ->
                        OutlinedCard(
                            onClick = { },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.SimCard,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Number not readable",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = buildString {
                                            append(sim.carrierName)
                                            if (sim.isEmbedded) append(" (eSIM)")
                                            append(" - Slot ${sim.slotIndex + 1}")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    // Hint if some SIMs have unreadable numbers
                    if (unreadableSims.isNotEmpty() && selectableSims.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Some SIMs don't expose their phone number. You can only select SIMs with readable numbers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    // All SIMs are unreadable
                    if (selectableSims.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "None of your SIM cards expose a readable phone number. " +
                                    "This is common with eSIMs. Please contact your carrier to enable number visibility, " +
                                    "or check your SIM settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sim = selectedSim ?: return@Button
                    isRegistering = true
                    errorMessage = null
                    scope.launch {
                        val normalized = normalizePhoneNumber(sim.phoneNumber!!)
                        val result = registerPhoneNumber(context, normalized)
                        isRegistering = false
                        if (result.first) {
                            onRegistered()
                        } else {
                            errorMessage = result.second ?: "Registration failed"
                        }
                    }
                },
                enabled = selectedSim != null && !isRegistering && sims.isNotEmpty()
            ) {
                if (isRegistering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Register")
            }
        },
        dismissButton = if (!mandatory) {
            {
                TextButton(onClick = onDismiss) {
                    Text("Skip for now")
                }
            }
        } else null
    )
}

/**
 * Check if a SIM has a readable phone number
 */
private fun hasReadableNumber(sim: SimManager.SimInfo): Boolean {
    return !sim.phoneNumber.isNullOrEmpty() && sim.phoneNumber != "Unknown"
}

/**
 * Normalize phone number using libphonenumber for consistent E.164 format
 */
private fun normalizePhoneNumber(phone: String): String {
    return com.phoneintegration.app.PhoneNumberUtils.toE164(phone)
}

/**
 * Register phone number in VPS for video calling lookup.
 * Returns (success, errorMessage).
 */
private suspend fun registerPhoneNumber(
    context: Context,
    normalizedPhone: String
): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
    try {
        val vpsClient = VPSClient.getInstance(context)

        // Ensure authenticated before registering
        if (!vpsClient.isAuthenticated) {
            Log.d(TAG, "Authenticating with VPS before phone registration...")
            val unifiedIdentityManager = com.phoneintegration.app.auth.UnifiedIdentityManager.getInstance(context)
            val authUserId = unifiedIdentityManager.getUnifiedUserId()
            if (authUserId == null || !vpsClient.isAuthenticated) {
                return@withContext Pair(false, "Authentication failed. Please try again.")
            }
        }

        val result = vpsClient.registerPhoneNumber(normalizedPhone)

        if (result.success) {
            Log.d(TAG, "SUCCESS: Registered phone: $normalizedPhone")
            saveRegistrationState(context, normalizedPhone)
            Pair(true, null)
        } else {
            Log.e(TAG, "Failed to register phone: ${result.error}")
            // Save locally anyway so user isn't stuck
            saveRegistrationState(context, normalizedPhone)
            Pair(false, result.error ?: "Registration failed")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error registering phone: ${e.message}", e)
        // Save locally on error too so user isn't stuck
        saveRegistrationState(context, normalizedPhone)
        Pair(false, e.message)
    }
}

/**
 * Save registration state to preferences
 */
fun saveRegistrationState(context: Context, phoneNumber: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
        .putBoolean(KEY_PHONE_REGISTERED, true)
        .putString(KEY_REGISTERED_PHONE, phoneNumber)
        .apply()
    Log.d(TAG, "Saved phone registration state")
}

/**
 * Check if phone number is already registered
 */
fun isPhoneNumberRegistered(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getBoolean(KEY_PHONE_REGISTERED, false)
}

/**
 * Get the registered phone number
 */
fun getRegisteredPhoneNumber(context: Context): String? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(KEY_REGISTERED_PHONE, null)
}

/**
 * Clear registration state (for testing or re-registration)
 */
fun clearPhoneRegistration(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().clear().apply()
}

/**
 * Restore phone registration from VPS server if missing locally.
 * Call this on app startup after VPS authentication.
 */
suspend fun restorePhoneRegistrationFromVPS(context: Context) {
    if (isPhoneNumberRegistered(context)) return

    try {
        val vpsClient = VPSClient.getInstance(context)
        if (!vpsClient.isAuthenticated) return

        val phoneNumber = vpsClient.getMyPhoneNumber()
        if (!phoneNumber.isNullOrEmpty()) {
            Log.d(TAG, "Restoring phone registration from VPS: $phoneNumber")
            saveRegistrationState(context, phoneNumber)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to restore phone registration from VPS: ${e.message}")
    }
}
