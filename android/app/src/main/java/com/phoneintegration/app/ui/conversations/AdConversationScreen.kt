package com.phoneintegration.app.ui.conversations

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.ui.deals.DealCategoryChips
import com.phoneintegration.app.ui.deals.DealShimmerPlaceholder
import com.phoneintegration.app.ui.deals.DealCard
import com.phoneintegration.app.deals.model.Deal
import com.phoneintegration.app.deals.DealsRepository
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdConversationScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { DealsRepository(context) }

    var deals by remember { mutableStateOf<List<Deal>>(emptyList()) }
    var filteredDeals by remember { mutableStateOf<List<Deal>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var lastUpdated by remember { mutableStateOf<Long?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val pullRefreshState = rememberPullToRefreshState()

    // Load initial deals
    LaunchedEffect(Unit) {
        deals = repo.getDeals()
        filteredDeals = deals
        loading = false
        lastUpdated = System.currentTimeMillis()
    }

    // Update filtered deals when category or search changes
    LaunchedEffect(selectedCategory, searchQuery, deals) {
        filteredDeals = when {
            searchQuery.isNotEmpty() -> {
                deals.filter { deal ->
                    deal.title.contains(searchQuery, ignoreCase = true) ||
                    deal.category.contains(searchQuery, ignoreCase = true)
                }
            }
            selectedCategory != "All" -> {
                deals.filter { it.category.equals(selectedCategory, ignoreCase = true) }
            }
            else -> deals
        }
    }

    // Refresh function
    suspend fun refreshDeals() {
        refreshing = true
        val result = repo.refreshDeals()
        deals = repo.getDeals()
        filteredDeals = if (selectedCategory != "All") {
            deals.filter { it.category.equals(selectedCategory, ignoreCase = true) }
        } else {
            deals
        }
        refreshing = false
        lastUpdated = System.currentTimeMillis()

        if (result) {
            snackbarHostState.showSnackbar("Deals updated!")
        } else {
            snackbarHostState.showSnackbar("Failed to refresh deals")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "SyncFlow Deals",
                            fontWeight = FontWeight.Bold
                        )
                        if (lastUpdated != null) {
                            Text(
                                "Updated ${formatLastUpdated(lastUpdated!!)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Search toggle
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(
                            if (showSearch) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (showSearch) "Close search" else "Search deals"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar (animated)
            AnimatedVisibility(
                visible = showSearch,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search deals...") },
                    leadingIcon = { Icon(Icons.Default.Search, "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, "Clear search")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
            }

            // Category chips
            val dealCategories = remember(deals) {
                listOf("All") + deals.map { it.category }.distinct().sorted()
            }

            DealCategoryChips(
                categories = dealCategories,
                selected = selectedCategory,
                onSelected = {
                    selectedCategory = it
                    if (showSearch) searchQuery = ""
                }
            )

            // Deal count header
            if (!loading && filteredDeals.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${filteredDeals.size} deals found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    TextButton(
                        onClick = { scope.launch { refreshDeals() } },
                        enabled = !refreshing
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Refresh")
                    }
                }
            }

            // Main content with pull-to-refresh
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { refreshDeals() } },
                modifier = Modifier.fillMaxSize(),
                state = pullRefreshState
            ) {
                when {
                    loading -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            items(5) {
                                DealShimmerPlaceholder()
                            }
                        }
                    }
                    filteredDeals.isEmpty() -> {
                        EmptyDealsState(
                            isSearchResult = searchQuery.isNotEmpty() || selectedCategory != "All",
                            onRefresh = { scope.launch { refreshDeals() } }
                        )
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            items(
                                items = filteredDeals,
                                key = { it.id }
                            ) { deal ->
                                DealCard(
                                    deal = deal,
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deal.url))
                                        context.startActivity(intent)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDealsState(
    isSearchResult: Boolean,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isSearchResult) Icons.Default.SearchOff else Icons.Default.LocalOffer,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isSearchResult) "No deals found" else "No deals available",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isSearchResult)
                "Try a different search term or category"
            else
                "Pull down to refresh and load the latest deals",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (!isSearchResult) {
            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Load Deals")
            }
        }
    }
}

private fun formatLastUpdated(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))
    }
}
