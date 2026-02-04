package com.phoneintegration.app.ui.conversations

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.phoneintegration.app.utils.InputValidation

data class ContactInfo(
    val name: String,
    val phoneNumber: String,
    val photoUri: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationScreen(
    onBack: () -> Unit,
    onContactsSelected: (List<ContactInfo>) -> Unit,
    onCreateGroup: (List<ContactInfo>) -> Unit = {},
    initialNumber: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var contacts by remember { mutableStateOf<List<ContactInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var selectedContacts by remember { mutableStateOf<Set<String>>(emptySet()) }
    var manualNumber by remember { mutableStateOf(TextFieldValue(initialNumber ?: "")) }
    var phoneError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialNumber) {
        if (!initialNumber.isNullOrBlank()) {
            manualNumber = TextFieldValue(initialNumber)
        }
    }

    // Load contacts on screen open
    LaunchedEffect(Unit) {
        scope.launch {
            contacts = loadContacts(context.contentResolver)
            isLoading = false
        }
    }

    val filteredContacts = remember(contacts, searchQuery.text) {
        val query = searchQuery.text.lowercase()
        if (query.isBlank()) contacts
        else contacts.filter {
            it.name.lowercase().contains(query) ||
                    it.phoneNumber.contains(query)
        }
    }

    val selectedContactsList = remember(selectedContacts) {
        contacts.filter { selectedContacts.contains(it.phoneNumber) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedContacts.isEmpty()) "New Message"
                        else "New Message (${selectedContacts.size})"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (selectedContacts.isNotEmpty() || manualNumber.text.isNotBlank()) {
                        val finalContacts = selectedContactsList.toMutableList()
                        // Add manual number if provided
                        if (manualNumber.text.isNotBlank()) {
                            finalContacts.add(
                                ContactInfo(
                                    name = manualNumber.text,
                                    phoneNumber = manualNumber.text
                                )
                            )
                        }

                        // Show different options based on contact count
                        if (finalContacts.size > 1) {
                            // Multiple contacts - show both options via menu
                            Row {
                                // Create Group button
                                TextButton(
                                    onClick = {
                                        if (finalContacts.isNotEmpty()) {
                                            onCreateGroup(finalContacts)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Groups, "Group", modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Group")
                                }

                                // Send Message button
                                IconButton(
                                    onClick = {
                                        if (finalContacts.isNotEmpty()) {
                                            onContactsSelected(finalContacts)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Check, "Send")
                                }
                            }
                        } else {
                            // Single contact - just show send
                            IconButton(
                                onClick = {
                                    if (finalContacts.isNotEmpty()) {
                                        onContactsSelected(finalContacts)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Check, "Continue")
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search / Manual Entry
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search contacts or enter number...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true
            )

            // Manual number entry section
            if (searchQuery.text.matches(Regex("^[0-9+() -]+$")) && searchQuery.text.isNotBlank()) {
                val phoneValidation = InputValidation.validatePhoneNumber(searchQuery.text)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable(enabled = phoneValidation.isValid) {
                            if (phoneValidation.isValid) {
                                manualNumber = TextFieldValue(phoneValidation.sanitizedValue ?: searchQuery.text)
                                searchQuery = TextFieldValue("")
                                phoneError = null
                            } else {
                                phoneError = phoneValidation.errorMessage
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (phoneValidation.isValid)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = if (phoneValidation.isValid)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Use number: ${searchQuery.text}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (phoneValidation.isValid)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error
                            )
                        }

                        if (!phoneValidation.isValid && phoneValidation.errorMessage != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = phoneValidation.errorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Show manually added number
            if (manualNumber.text.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = manualNumber.text,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Manual number",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        TextButton(onClick = { manualNumber = TextFieldValue("") }) {
                            Text("Remove")
                        }
                    }
                }
                Divider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // Selected contacts chip list
            if (selectedContacts.isNotEmpty()) {
                Text(
                    text = "Selected (${selectedContacts.size})",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.3f)
                ) {
                    items(selectedContactsList) { contact ->
                        ContactItem(
                            contact = contact,
                            isSelected = true,
                            onToggle = {
                                selectedContacts = selectedContacts - contact.phoneNumber
                            }
                        )
                    }
                }

                Divider()
            }

            // Contacts list
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredContacts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No contacts found", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(filteredContacts) { contact ->
                        val isSelected = selectedContacts.contains(contact.phoneNumber)
                        ContactItem(
                            contact = contact,
                            isSelected = isSelected,
                            onToggle = {
                                selectedContacts = if (isSelected) {
                                    selectedContacts - contact.phoneNumber
                                } else {
                                    selectedContacts + contact.phoneNumber
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContactItem(
    contact: ContactInfo,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else
            MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Contact photo or initial
            if (contact.photoUri != null) {
                AsyncImage(
                    model = contact.photoUri,
                    contentDescription = "Contact photo",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contact.name.firstOrNull()?.uppercase() ?: "?",
                        color = if (isSelected)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Name and phone
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = contact.phoneNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // Selection indicator
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

suspend fun loadContacts(contentResolver: ContentResolver): List<ContactInfo> =
    withContext(Dispatchers.IO) {
        val contacts = mutableListOf<ContactInfo>()
        val seenNumbers = mutableSetOf<String>()

        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1)?.replace(Regex("[^0-9+]"), "") ?: continue
                val photoUri = c.getString(2)

                // Avoid duplicates
                if (number.isNotBlank() && !seenNumbers.contains(number)) {
                    seenNumbers.add(number)
                    contacts.add(
                        ContactInfo(
                            name = name,
                            phoneNumber = number,
                            photoUri = photoUri
                        )
                    )
                }
            }
        }

        contacts
    }
