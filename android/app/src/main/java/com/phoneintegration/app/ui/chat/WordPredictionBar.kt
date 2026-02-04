package com.phoneintegration.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Word prediction bar that shows word completion suggestions above the keyboard.
 * Uses a basic dictionary of common words and phrases for predictions.
 */
@Composable
fun WordPredictionBar(
    currentInput: String,
    onSuggestionSelected: (String) -> Unit,
    isKeyboardVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Get the last word being typed
    val lastWord = remember(currentInput) {
        currentInput.trim().split("\\s+".toRegex()).lastOrNull()?.lowercase() ?: ""
    }

    // Generate suggestions based on the last word
    val suggestions = remember(lastWord) {
        if (lastWord.length >= 2) {
            getSuggestionsForInput(lastWord).take(3)
        } else {
            emptyList()
        }
    }

    AnimatedVisibility(
        visible = isKeyboardVisible && suggestions.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
            tonalElevation = 2.dp
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(suggestions) { suggestion ->
                    SuggestionChip(
                        onClick = {
                            // Replace the last word with the suggestion
                            val inputWords = currentInput.trim().split("\\s+".toRegex()).toMutableList()
                            if (inputWords.isNotEmpty()) {
                                inputWords[inputWords.lastIndex] = suggestion
                            }
                            val newInput = inputWords.joinToString(" ") + " "
                            onSuggestionSelected(newInput)
                        },
                        label = { Text(suggestion) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }
    }
}

/**
 * Get word completion suggestions based on input prefix.
 * Uses a simple dictionary of common words for predictions.
 */
private fun getSuggestionsForInput(prefix: String): List<String> {
    if (prefix.isBlank()) return emptyList()

    val commonWords = listOf(
        // Greetings and common phrases
        "hello", "hey", "hi", "how", "hope",
        "thanks", "thank", "that", "the", "this", "there", "they", "their", "them", "then",
        "what", "when", "where", "which", "who", "why", "with", "would", "will",
        "please", "probably", "people",
        "good", "great", "got", "going", "get",
        "just", "join",
        "know", "keep",
        "love", "like", "let", "look", "later",
        "make", "maybe", "meet", "message", "morning",
        "need", "nice", "night", "now", "never",
        "okay", "only", "other", "out",
        "really", "right",
        "see", "send", "some", "sorry", "sure", "sounds",
        "time", "today", "tomorrow", "tonight",
        "very", "via",
        "want", "well", "work", "working",
        "yes", "you", "your", "yeah",
        // Common verbs
        "about", "after", "again", "always", "also", "around",
        "back", "because", "been", "before", "being", "between",
        "call", "can", "come", "could",
        "did", "didn't", "don't", "does", "doesn't", "doing", "done",
        "even", "every",
        "feel", "find", "first", "for", "from",
        "had", "has", "have", "having", "here",
        "into", "its", "it's",
        "much", "must", "my",
        "one", "our", "over",
        "said", "same", "say", "should", "since", "still", "such",
        "take", "tell", "than", "think", "through", "too", "trying",
        "under", "until", "upon", "us", "use",
        "way", "we", "were", "wasn't", "weren't", "won't",
        // Common nouns
        "address", "anything", "anyway",
        "birthday", "brother",
        "check", "class", "coming",
        "day", "dinner", "down", "drive",
        "everyone", "everything",
        "family", "friend", "friends",
        "girl", "give", "guy",
        "happen", "happy", "help", "home", "house", "hours",
        "idea",
        "life", "little", "lunch",
        "man", "minute", "minutes", "miss", "mom", "money", "more", "most",
        "number",
        "okay", "off", "omw", "once",
        "part", "party", "phone", "place", "point",
        "question",
        "running",
        "school", "shit", "side", "sister", "sleep", "something", "soon", "start", "stop", "stuff",
        "talk", "talking", "text", "thing", "things", "thought", "times", "together", "trip",
        "video",
        "waiting", "walk", "watching", "water", "week", "weekend", "woman", "worry",
        "year", "years", "yesterday"
    )

    return commonWords
        .filter { it.startsWith(prefix) && it != prefix }
        .sortedBy { it.length }
        .take(5)
}
