package com.phoneintegration.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.data.PreferencesManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    prefsManager: PreferencesManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Theme") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Choose Theme",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            // Auto Theme
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { 
                        prefsManager.setAutoTheme(true)
                        Toast.makeText(context, "Theme set to System Default", Toast.LENGTH_SHORT).show()
                    },
                colors = if (prefsManager.isAutoTheme.value) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    CardDefaults.cardColors()
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("System Default", style = MaterialTheme.typography.titleMedium)
                        Text("Follow system theme", style = MaterialTheme.typography.bodySmall)
                    }
                    RadioButton(
                        selected = prefsManager.isAutoTheme.value,
                        onClick = { 
                            prefsManager.setAutoTheme(true)
                            Toast.makeText(context, "Theme set to System Default", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
            
            // Light Theme
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { 
                        prefsManager.setAutoTheme(false)
                        prefsManager.setDarkMode(false)
                        Toast.makeText(context, "Theme set to Light", Toast.LENGTH_SHORT).show()
                    },
                colors = if (!prefsManager.isAutoTheme.value && !prefsManager.isDarkMode.value) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    CardDefaults.cardColors()
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Light", style = MaterialTheme.typography.titleMedium)
                        Text("Always use light theme", style = MaterialTheme.typography.bodySmall)
                    }
                    RadioButton(
                        selected = !prefsManager.isAutoTheme.value && !prefsManager.isDarkMode.value,
                        onClick = { 
                            prefsManager.setAutoTheme(false)
                            prefsManager.setDarkMode(false)
                            Toast.makeText(context, "Theme set to Light", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
            
            // Dark Theme
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { 
                        prefsManager.setAutoTheme(false)
                        prefsManager.setDarkMode(true)
                        Toast.makeText(context, "Theme set to Dark", Toast.LENGTH_SHORT).show()
                    },
                colors = if (!prefsManager.isAutoTheme.value && prefsManager.isDarkMode.value) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    CardDefaults.cardColors()
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Dark", style = MaterialTheme.typography.titleMedium)
                        Text("Always use dark theme", style = MaterialTheme.typography.bodySmall)
                    }
                    RadioButton(
                        selected = !prefsManager.isAutoTheme.value && prefsManager.isDarkMode.value,
                        onClick = { 
                            prefsManager.setAutoTheme(false)
                            prefsManager.setDarkMode(true)
                            Toast.makeText(context, "Theme set to Dark", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "💡 Note",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Theme changes take effect immediately. Restart the app if you don't see changes.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
