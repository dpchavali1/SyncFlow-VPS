package com.phoneintegration.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.data.PreferencesManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    prefsManager: PreferencesManager,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Font Size
            Text(
                "Message Text Size",
                style = MaterialTheme.typography.titleMedium
            )
            
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Preview: Hello! How are you?",
                        fontSize = androidx.compose.ui.unit.TextUnit(
                            value = prefsManager.fontSize.value.toFloat(),
                            type = androidx.compose.ui.unit.TextUnitType.Sp
                        ),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("A", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = prefsManager.fontSize.value.toFloat(),
                            onValueChange = { prefsManager.setFontSize(it.toInt()) },
                            valueRange = 12f..20f,
                            steps = 7,
                            modifier = Modifier.weight(1f)
                        )
                        Text("A", style = MaterialTheme.typography.titleLarge)
                    }
                    
                    Text(
                        "${prefsManager.fontSize.value} sp",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
            
            // Bubble Style
            Text(
                "Bubble Style",
                style = MaterialTheme.typography.titleMedium
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BubbleStyleOption(
                    "Rounded",
                    "rounded",
                    prefsManager.bubbleStyle.value == "rounded",
                    onClick = { prefsManager.setBubbleStyle("rounded") },
                    modifier = Modifier.weight(1f)
                )
                
                BubbleStyleOption(
                    "Square",
                    "square",
                    prefsManager.bubbleStyle.value == "square",
                    onClick = { prefsManager.setBubbleStyle("square") },
                    modifier = Modifier.weight(1f)
                )
            }
            
            // Chat Wallpaper
            Text(
                "Chat Wallpaper",
                style = MaterialTheme.typography.titleMedium
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WallpaperOption(
                    "Default",
                    "default",
                    Color.White,
                    prefsManager.chatWallpaper.value == "default",
                    onClick = { prefsManager.setChatWallpaper("default") },
                    modifier = Modifier.weight(1f)
                )
                
                WallpaperOption(
                    "Light",
                    "light",
                    Color(0xFFF5F5F5),
                    prefsManager.chatWallpaper.value == "light",
                    onClick = { prefsManager.setChatWallpaper("light") },
                    modifier = Modifier.weight(1f)
                )
                
                WallpaperOption(
                    "Dark",
                    "dark",
                    Color(0xFF2C2C2C),
                    prefsManager.chatWallpaper.value == "dark",
                    onClick = { prefsManager.setChatWallpaper("dark") },
                    modifier = Modifier.weight(1f)
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WallpaperOption(
                    "Blue",
                    "blue",
                    Color(0xFFE3F2FD),
                    prefsManager.chatWallpaper.value == "blue",
                    onClick = { prefsManager.setChatWallpaper("blue") },
                    modifier = Modifier.weight(1f)
                )
                
                WallpaperOption(
                    "Green",
                    "green",
                    Color(0xFFE8F5E9),
                    prefsManager.chatWallpaper.value == "green",
                    onClick = { prefsManager.setChatWallpaper("green") },
                    modifier = Modifier.weight(1f)
                )
                
                WallpaperOption(
                    "Pink",
                    "pink",
                    Color(0xFFFCE4EC),
                    prefsManager.chatWallpaper.value == "pink",
                    onClick = { prefsManager.setChatWallpaper("pink") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BubbleStyleOption(
    label: String,
    value: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(if (value == "rounded") RoundedCornerShape(12.dp) else RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WallpaperOption(
    label: String,
    value: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color)
                    .then(
                        if (selected) {
                            Modifier.border(
                                3.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(8.dp)
                            )
                        } else {
                            Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(8.dp)
                            )
                        }
                    )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}
