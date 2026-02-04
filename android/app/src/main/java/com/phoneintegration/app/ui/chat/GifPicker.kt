package com.phoneintegration.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.phoneintegration.app.data.TenorApiService
import kotlinx.coroutines.launch

/**
 * GIF Picker bottom sheet for selecting GIFs from Tenor.
 *
 * @param onGifSelected Callback when a GIF is selected, passes the GIF URL
 * @param onDismiss Callback when the picker is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GifPicker(
    onGifSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tenorService = remember { TenorApiService() }

    var searchQuery by remember { mutableStateOf("") }
    var gifs by remember { mutableStateOf<List<TenorApiService.TenorGif>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Load trending GIFs on first display
    LaunchedEffect(Unit) {
        try {
            isLoading = true
            errorMessage = null
            gifs = tenorService.getTrendingGifs(limit = 30)
            if (gifs.isEmpty()) {
                errorMessage = "No GIFs found"
            }
        } catch (e: Exception) {
            errorMessage = "Failed to load GIFs"
        } finally {
            isLoading = false
        }
    }

    // Search function
    fun searchGifs(query: String) {
        scope.launch {
            try {
                isLoading = true
                errorMessage = null
                gifs = if (query.isBlank()) {
                    tenorService.getTrendingGifs(limit = 30)
                } else {
                    tenorService.searchGifs(query, limit = 30)
                }
                if (gifs.isEmpty()) {
                    errorMessage = if (query.isBlank()) "No trending GIFs found" else "No GIFs found for \"$query\""
                }
            } catch (e: Exception) {
                errorMessage = "Search failed"
            } finally {
                isLoading = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.8f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Choose a GIF",
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search GIFs") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            searchGifs("")
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { searchGifs(searchQuery) }
                ),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Quick search chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("happy", "sad", "love", "funny", "ok").forEach { term ->
                    FilterChip(
                        selected = searchQuery.lowercase() == term,
                        onClick = {
                            searchQuery = term
                            searchGifs(term)
                        },
                        label = { Text(term) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Content area
            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    errorMessage != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = errorMessage ?: "Error",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = { searchGifs(searchQuery) }) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(gifs, key = { it.id }) { gif ->
                                GifGridItem(
                                    gif = gif,
                                    onClick = { onGifSelected(gif.fullUrl) }
                                )
                            }
                        }
                    }
                }
            }

            // Tenor attribution
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Powered by Tenor",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Individual GIF item in the grid.
 */
@Composable
private fun GifGridItem(
    gif: TenorApiService.TenorGif,
    onClick: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Loading placeholder
            var isLoading by remember { mutableStateOf(true) }

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }

            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(gif.previewUrl)
                    .decoderFactory(
                        if (android.os.Build.VERSION.SDK_INT >= 28) {
                            ImageDecoderDecoder.Factory()
                        } else {
                            GifDecoder.Factory()
                        }
                    )
                    .crossfade(true)
                    .build(),
                contentDescription = gif.title,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
                onSuccess = { isLoading = false },
                onError = { isLoading = false }
            )
        }
    }
}

/**
 * Compact GIF picker button that can be added to the message input row.
 * Opens the full GIF picker when clicked.
 */
@Composable
fun GifPickerButton(
    onGifSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }

    FilledTonalIconButton(
        onClick = { showPicker = true },
        modifier = modifier.size(40.dp)
    ) {
        Text("GIF", style = MaterialTheme.typography.labelSmall)
    }

    if (showPicker) {
        GifPicker(
            onGifSelected = { url ->
                onGifSelected(url)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}
