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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.phoneintegration.app.vps.VPSClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val TAG = "PhoneNumberRegistration"
private const val PREFS_NAME = "phone_registration_prefs"
private const val KEY_PHONE_REGISTERED = "phone_number_registered"
private const val KEY_REGISTERED_PHONE = "registered_phone_number"

/**
 * Common country codes for the picker dropdown.
 */
private data class CountryCode(val region: String, val code: Int, val label: String)

private val COMMON_COUNTRIES = listOf(
    CountryCode("US", 1, "US +1"),
    CountryCode("GB", 44, "UK +44"),
    CountryCode("IN", 91, "IN +91"),
    CountryCode("CA", 1, "CA +1"),
    CountryCode("AU", 61, "AU +61"),
    CountryCode("DE", 49, "DE +49"),
    CountryCode("FR", 33, "FR +33"),
    CountryCode("JP", 81, "JP +81"),
    CountryCode("BR", 55, "BR +55"),
    CountryCode("MX", 52, "MX +52"),
    CountryCode("KR", 82, "KR +82"),
    CountryCode("IT", 39, "IT +39"),
    CountryCode("ES", 34, "ES +34"),
    CountryCode("NL", 31, "NL +31"),
    CountryCode("SE", 46, "SE +46"),
    CountryCode("NO", 47, "NO +47"),
    CountryCode("DK", 45, "DK +45"),
    CountryCode("FI", 358, "FI +358"),
    CountryCode("PL", 48, "PL +48"),
    CountryCode("RU", 7, "RU +7"),
    CountryCode("CN", 86, "CN +86"),
    CountryCode("SG", 65, "SG +65"),
    CountryCode("NZ", 64, "NZ +64"),
    CountryCode("PH", 63, "PH +63"),
    CountryCode("ZA", 27, "ZA +27"),
    CountryCode("AE", 971, "AE +971"),
    CountryCode("SA", 966, "SA +966"),
    CountryCode("NG", 234, "NG +234"),
    CountryCode("EG", 20, "EG +20"),
    CountryCode("PK", 92, "PK +92"),
    CountryCode("BD", 880, "BD +880"),
    CountryCode("ID", 62, "ID +62"),
    CountryCode("TH", 66, "TH +66"),
    CountryCode("VN", 84, "VN +84"),
    CountryCode("MY", 60, "MY +60"),
    CountryCode("CO", 57, "CO +57"),
    CountryCode("AR", 54, "AR +54"),
    CountryCode("CL", 56, "CL +56"),
    CountryCode("PE", 51, "PE +51"),
    CountryCode("IE", 353, "IE +353"),
    CountryCode("AT", 43, "AT +43"),
    CountryCode("CH", 41, "CH +41"),
    CountryCode("BE", 32, "BE +32"),
    CountryCode("PT", 351, "PT +351"),
    CountryCode("GR", 30, "GR +30"),
    CountryCode("CZ", 420, "CZ +420"),
    CountryCode("RO", 40, "RO +40"),
    CountryCode("HU", 36, "HU +36"),
    CountryCode("IL", 972, "IL +972"),
    CountryCode("TR", 90, "TR +90"),
    CountryCode("UA", 380, "UA +380"),
    CountryCode("KE", 254, "KE +254"),
    CountryCode("GH", 233, "GH +233"),
    CountryCode("TZ", 255, "TZ +255")
)

/**
 * Dialog to prompt user for their phone number.
 * Pre-fills with SIM-detected number if available.
 *
 * @param mandatory If true, dialog cannot be dismissed/skipped (used from MainNavigation)
 * @param onDismiss Called when dialog is dismissed (only effective when mandatory=false)
 * @param onRegistered Called when phone number is successfully registered
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneNumberRegistrationDialog(
    mandatory: Boolean = false,
    onDismiss: () -> Unit,
    onRegistered: () -> Unit
) {
    val context = LocalContext.current
    var phoneNumber by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Country code picker state
    val phoneUtil = remember { PhoneNumberUtil.getInstance() }
    val deviceRegion = remember { Locale.getDefault().country.uppercase() }
    val defaultCountry = remember {
        COMMON_COUNTRIES.find { it.region == deviceRegion } ?: COMMON_COUNTRIES[0]
    }
    var selectedCountry by remember { mutableStateOf(defaultCountry) }
    var countryDropdownExpanded by remember { mutableStateOf(false) }

    // Try to detect phone number from SIM on first load
    LaunchedEffect(Unit) {
        val detectedNumber = detectPhoneNumber(context)
        if (!detectedNumber.isNullOrEmpty()) {
            // If detected number starts with +, extract country code and local number
            if (detectedNumber.startsWith("+")) {
                try {
                    val parsed = phoneUtil.parse(detectedNumber, deviceRegion)
                    val countryCode = parsed.countryCode
                    val match = COMMON_COUNTRIES.find { it.code == countryCode }
                    if (match != null) {
                        selectedCountry = match
                    }
                    phoneNumber = parsed.nationalNumber.toString()
                } catch (e: Exception) {
                    // Fallback: strip the + and use as-is
                    phoneNumber = detectedNumber.removePrefix("+")
                }
            } else {
                phoneNumber = detectedNumber
            }
            Log.d(TAG, "Pre-filled phone number from SIM: $detectedNumber")
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
                "Register Phone Number",
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Your phone number is required to use SyncFlow. It will be used for messaging and video calls.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Country code picker
                ExposedDropdownMenuBox(
                    expanded = countryDropdownExpanded,
                    onExpandedChange = { countryDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedCountry.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Country") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = countryDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = countryDropdownExpanded,
                        onDismissRequest = { countryDropdownExpanded = false }
                    ) {
                        COMMON_COUNTRIES.forEach { country ->
                            DropdownMenuItem(
                                text = { Text(country.label) },
                                onClick = {
                                    selectedCountry = country
                                    countryDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Phone number input (local number without country code)
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = {
                        phoneNumber = it.filter { c -> c.isDigit() }
                        errorMessage = null
                    },
                    label = { Text("Phone Number") },
                    placeholder = { Text("1234567890") },
                    prefix = { Text("+${selectedCountry.code} ") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone
                    ),
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (phoneNumber.length < 5) {
                        errorMessage = "Please enter a valid phone number"
                        return@Button
                    }
                    isLoading = true
                    // Combine country code + local number
                    val fullNumber = "+${selectedCountry.code}$phoneNumber"
                    registerPhoneNumber(context, fullNumber) { success, error ->
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
 * Normalize phone number using libphonenumber for consistent E.164 format
 */
private fun normalizePhoneNumber(phone: String): String {
    return com.phoneintegration.app.PhoneNumberUtils.toE164(phone)
}

/**
 * Register phone number in VPS for video calling lookup
 */
private fun registerPhoneNumber(
    context: Context,
    phoneNumber: String,
    callback: (success: Boolean, error: String?) -> Unit
) {
    val vpsClient = VPSClient.getInstance(context)
    var userId = vpsClient.userId

    if (userId == null) {
        Log.w(TAG, "Not signed in - will authenticate first during registration")
    }

    val normalizedPhone = normalizePhoneNumber(phoneNumber)
    val phoneKey = normalizedPhone.replace("+", "").replace("-", "").replace(" ", "")

    Log.d(TAG, "Registering phone number: $phoneKey for user: $userId")

    GlobalScope.launch(Dispatchers.IO) {
        try {
            // Ensure authenticated before registering
            if (!vpsClient.isAuthenticated) {
                Log.d(TAG, "Authenticating with VPS before phone registration...")
                val unifiedIdentityManager = com.phoneintegration.app.auth.UnifiedIdentityManager.getInstance(context)
                val authUserId = unifiedIdentityManager.getUnifiedUserId()
                if (authUserId == null || !vpsClient.isAuthenticated) {
                    Log.e(TAG, "Failed to authenticate for phone registration")
                    withContext(Dispatchers.Main) {
                        callback(false, "Authentication failed. Please try again.")
                    }
                    return@launch
                }
                userId = authUserId
                Log.d(TAG, "Authenticated as: $userId")
            }

            // Register phone number via VPS API
            val result = vpsClient.registerPhoneNumber(normalizedPhone)

            if (result.success) {
                Log.d(TAG, "SUCCESS: Registered phone: $phoneKey")
                saveRegistrationState(context, normalizedPhone)
                Log.d(TAG, "Saved registration state")

                withContext(Dispatchers.Main) {
                    callback(true, null)
                }
            } else {
                Log.e(TAG, "Failed to register phone: ${result.error}")
                // Save locally anyway so user isn't stuck
                saveRegistrationState(context, normalizedPhone)
                withContext(Dispatchers.Main) {
                    callback(false, result.error ?: "Registration failed")
                }
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
