package com.phoneintegration.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * State of voice recognition
 */
sealed class VoiceRecognitionState {
    object Idle : VoiceRecognitionState()
    object Listening : VoiceRecognitionState()
    object Processing : VoiceRecognitionState()
    data class Result(val text: String, val isFinal: Boolean) : VoiceRecognitionState()
    data class Error(val message: String, val errorCode: Int) : VoiceRecognitionState()
}

/**
 * Manager for voice-to-text functionality
 */
class VoiceToTextManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceToTextManager"

        /**
         * Check if speech recognition is available on this device
         */
        fun isAvailable(context: Context): Boolean {
            return SpeechRecognizer.isRecognitionAvailable(context)
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private val _state = MutableStateFlow<VoiceRecognitionState>(VoiceRecognitionState.Idle)
    val state: StateFlow<VoiceRecognitionState> = _state.asStateFlow()

    private val _partialResults = MutableStateFlow("")
    val partialResults: StateFlow<String> = _partialResults.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    // Callback for when final text is recognized
    private var onResultCallback: ((String) -> Unit)? = null

    /**
     * Initialize speech recognizer
     */
    fun initialize() {
        if (speechRecognizer != null) return

        if (!isAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            _state.value = VoiceRecognitionState.Error(
                "Speech recognition not available",
                SpeechRecognizer.ERROR_CLIENT
            )
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createRecognitionListener())
        }

        Log.d(TAG, "Speech recognizer initialized")
    }

    /**
     * Start listening for voice input
     */
    fun startListening(
        language: String = Locale.getDefault().toString(),
        onResult: ((String) -> Unit)? = null
    ) {
        if (isListening) {
            Log.w(TAG, "Already listening")
            return
        }

        if (speechRecognizer == null) {
            initialize()
        }

        onResultCallback = onResult

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            _state.value = VoiceRecognitionState.Listening
            _partialResults.value = ""
            Log.d(TAG, "Started listening")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            _state.value = VoiceRecognitionState.Error(e.message ?: "Unknown error", -1)
        }
    }

    /**
     * Stop listening
     */
    fun stopListening() {
        if (!isListening) return

        try {
            speechRecognizer?.stopListening()
            isListening = false
            Log.d(TAG, "Stopped listening")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognition", e)
        }
    }

    /**
     * Cancel recognition
     */
    fun cancel() {
        try {
            speechRecognizer?.cancel()
            isListening = false
            _state.value = VoiceRecognitionState.Idle
            _partialResults.value = ""
            onResultCallback = null
            Log.d(TAG, "Recognition cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling speech recognition", e)
        }
    }

    /**
     * Release resources
     */
    fun release() {
        cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        Log.d(TAG, "Speech recognizer released")
    }

    /**
     * Check if currently listening
     */
    fun isCurrentlyListening(): Boolean = isListening

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                _state.value = VoiceRecognitionState.Listening
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Normalize RMS to 0-1 range for UI
                val normalized = ((rmsdB + 2) / 12).coerceIn(0f, 1f)
                _amplitude.value = normalized
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Not typically needed
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
                isListening = false
                _state.value = VoiceRecognitionState.Processing
            }

            override fun onError(error: Int) {
                Log.e(TAG, "Recognition error: $error")
                isListening = false
                _amplitude.value = 0f

                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    else -> "Unknown error"
                }

                // Don't report "no match" as error if we have partial results
                if (error == SpeechRecognizer.ERROR_NO_MATCH && _partialResults.value.isNotBlank()) {
                    val finalText = _partialResults.value
                    _state.value = VoiceRecognitionState.Result(finalText, true)
                    onResultCallback?.invoke(finalText)
                } else {
                    _state.value = VoiceRecognitionState.Error(errorMessage, error)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                _amplitude.value = 0f

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val bestResult = matches?.firstOrNull() ?: ""

                Log.d(TAG, "Final results: $bestResult")
                _state.value = VoiceRecognitionState.Result(bestResult, true)
                onResultCallback?.invoke(bestResult)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull() ?: ""

                if (partial.isNotBlank()) {
                    Log.d(TAG, "Partial results: $partial")
                    _partialResults.value = partial
                    _state.value = VoiceRecognitionState.Result(partial, false)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "Recognition event: $eventType")
            }
        }
    }
}
