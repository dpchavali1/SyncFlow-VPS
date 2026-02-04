package com.phoneintegration.app.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.phoneintegration.app.network.FirebaseSecurityConfig

private const val TAG = "PhoneNumberRegistration"
private const val PREFS_NAME = "phone_registration_prefs"
private const val KEY_PHONE_REGISTERED = "phone_number_registered"
private const val KEY_REGISTERED_PHONE = "registered_phone_number"

/**
 * Dialog to prompt user for their phone number for video calling.
 * Pre-fills with SIM-detected number if available.
 */
@Composable
fun PhoneNumberRegistrationDialog(
    onDismiss: () -> Unit,
    onRegistered: () -> Unit
) {
    val context = LocalContext.current
    var phoneNumber by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Try to detect phone number from SIM on first load
    LaunchedEffect(Unit) {
        val detectedNumber = detectPhoneNumber(context)
        if (!detectedNumber.isNullOrEmpty()) {
            phoneNumber = detectedNumber
            Log.d(TAG, "Pre-filled phone number from SIM: $detectedNumber")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.VideoCall,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                "Enable Video Calling",
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Enter your phone number to receive video calls from other SyncFlow users.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = {
                        phoneNumber = it.filter { c -> c.isDigit() || c == '+' }
                        errorMessage = null
                    },
                    label = { Text("Phone Number") },
                    placeholder = { Text("+1234567890") },
                    leadingIcon = {
                        Icon(Icons.Default.Phone, contentDescription = null)
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone
                    ),
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "This number will be visible to other SyncFlow users when you call them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (phoneNumber.length < 10) {
                        errorMessage = "Please enter a valid phone number"
                        return@Button
                    }
                    isLoading = true
                    registerPhoneNumber(context, phoneNumber) { success, error ->
                        isLoading = false
                        if (success) {
                            onRegistered()
                        } else {
                            errorMessage = error ?: "Registration failed"
                        }
                    }
                },
                enabled = phoneNumber.isNotEmpty() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Register")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Skip for now")
            }
        }
    )
}

/**
 * Try to detect phone number from SIM card
 */
private fun detectPhoneNumber(context: Context): String? {
    try {
        // Check permission first
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "No READ_PHONE_STATE permission")
            return null
        }

        // Try SubscriptionManager first (more reliable on dual-SIM)
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        val subscriptions = try {
            subscriptionManager?.activeSubscriptionInfoList
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException getting subscriptions", e)
            null
        }

        if (!subscriptions.isNullOrEmpty()) {
            for (subscription in subscriptions) {
                val number = subscription.number
                if (!number.isNullOrBlank()) {
                    Log.d(TAG, "Found phone number from subscription: ${number.take(4)}***")
                    return normalizePhoneNumber(number)
                }
            }
        }

        // Fallback to TelephonyManager
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val line1Number = try {
            @Suppress("DEPRECATION")
            telephonyManager?.line1Number
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException getting line1Number", e)
            null
        }

        if (!line1Number.isNullOrBlank()) {
            Log.d(TAG, "Found phone number from TelephonyManager: ${line1Number.take(4)}***")
            return normalizePhoneNumber(line1Number)
        }

        Log.d(TAG, "Could not detect phone number from SIM")
        return null
    } catch (e: Exception) {
        Log.e(TAG, "Error detecting phone number", e)
        return null
    }
}

/**
 * Normalize phone number for consistent storage
 */
private fun normalizePhoneNumber(phone: String): String {
    // Remove all non-digit characters except leading +
    val digits = phone.filter { it.isDigit() }

    // If it starts with country code, keep it
    return if (phone.startsWith("+")) {
        "+$digits"
    } else if (digits.length == 10) {
        // US number without country code - add +1
        "+1$digits"
    } else {
        "+$digits"
    }
}

/**
 * Register phone number in Firebase for video calling lookup
 */
private fun registerPhoneNumber(
    context: Context,
    phoneNumber: String,
    callback: (success: Boolean, error: String?) -> Unit
) {
    val userId = FirebaseAuth.getInstance().currentUser?.uid
    if (userId == null) {
        Log.e(TAG, "Not signed in - cannot register phone")
        callback(false, "Not signed in")
        return
    }

    val normalizedPhone = normalizePhoneNumber(phoneNumber)
    val phoneKey = normalizedPhone.replace("+", "").replace("-", "").replace(" ", "")

    Log.d(TAG, "Registering phone number: $phoneKey for user: $userId")

    val database = FirebaseDatabase.getInstance()

    // Use Firebase's executeFirebaseWrite wrapper to temporarily go online
    // (Firebase is kept offline to prevent OOM, this wrapper handles going online/offline)
    GlobalScope.launch(Dispatchers.IO) {
        try {
            Log.d(TAG, "Going online to register phone...")

            // Use the security wrapper that handles online/offline state
            FirebaseSecurityConfig.executeFirebaseWrite {
                // Register primary phone number
                database.reference
                    .child("phone_to_uid")
                    .child(phoneKey)
                    .setValue(userId)
                    .await()

                Log.d(TAG, "SUCCESS: Registered primary phone: $phoneKey")

                // SAVE IMMEDIATELY after primary registration succeeds
                // (before the 2-second delay in executeFirebaseWrite)
                saveRegistrationState(context, normalizedPhone)
                Log.d(TAG, "Saved registration state immediately")

                // Also save to user profile (fire and forget - don't await)
                database.reference
                    .child("users")
                    .child(userId)
                    .child("phoneNumber")
                    .setValue(normalizedPhone)

                // Register variant (fire and forget - don't await)
                val variant = if (phoneKey.length == 11 && phoneKey.startsWith("1")) {
                    phoneKey.substring(1)
                } else if (phoneKey.length == 10) {
                    "1$phoneKey"
                } else null

                if (variant != null) {
                    database.reference
                        .child("phone_to_uid")
                        .child(variant)
                        .setValue(userId)
                    Log.d(TAG, "Registering variant: $variant")
                }
            }

            withContext(Dispatchers.Main) {
                callback(true, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering phone: ${e.message}", e)
            // Save locally on error too so user isn't stuck
            saveRegistrationState(context, normalizedPhone)
            withContext(Dispatchers.Main) {
                callback(false, e.message)
            }
        }
    }
}

/**
 * Save registration state to preferences
 */
private fun saveRegistrationState(context: Context, phoneNumber: String) {
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
