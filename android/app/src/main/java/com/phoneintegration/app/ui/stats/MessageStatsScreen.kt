package com.phoneintegration.app.ui.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Message
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.SmsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageStatsScreen(
    viewModel: SmsViewModel,
    onBack: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsState()
    
    val totalConversations = conversations.size
    val unreadCount = conversations.sumOf { it.unreadCount }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message Statistics") },
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
            
            // Overview Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Overview",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    
                    StatRow(
                        icon = Icons.Default.Message,
                        label = "Total Conversations",
                        value = totalConversations.toString(),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    
                    StatRow(
                        icon = Icons.Default.MarkEmailUnread,
                        label = "Unread Messages",
                        value = unreadCount.toString(),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            // Recent Activity
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Recent Activity",
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    if (conversations.isNotEmpty()) {
                        val mostRecent = conversations.first()
                        Text(
                            "Most Recent: ${mostRecent.contactName ?: mostRecent.address}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        Text(
                            "Last Message: ${mostRecent.lastMessage.take(50)}${if (mostRecent.lastMessage.length > 50) "..." else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "No recent activity",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Top Contacts
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Top Contacts",
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    if (conversations.isNotEmpty()) {
                        conversations.take(5).forEachIndexed { index, convo ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${index + 1}.", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        convo.contactName ?: convo.address,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                
                                if (convo.unreadCount > 0) {
                                    Badge {
                                        Text(convo.unreadCount.toString())
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            "No contacts yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(
    icon: ImageVector,
    label: String,
    value: String,
    contentColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = contentColor
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor
            )
        }
        
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = contentColor
        )
    }
}
