package com.phoneintegration.app.spam

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TensorFlow Lite based spam classifier
 * Runs completely on-device for privacy
 */
class SpamMLClassifier(private val context: Context) {

    companion object {
        private const val TAG = "SpamMLClassifier"
        private const val MODEL_FILE = "spam_model.tflite"
        private const val VOCAB_FILE = "vocab.txt"
        private const val MAX_SEQUENCE_LENGTH = 128
        private const val EMBEDDING_DIM = 64
    }

    data class ClassificationResult(
        val isSpam: Boolean,
        val confidence: Float,
        val spamProbability: Float,
        val hamProbability: Float
    )

    private var interpreter: Interpreter? = null
    private var vocabulary: Map<String, Int> = emptyMap()
    private var modelVersion: String? = null

    init {
        try {
            loadModel()
            loadVocabulary()
        } catch (e: Exception) {
            Log.w(TAG, "ML classifier initialization failed: ${e.message}")
            // Classifier is optional - app works without it
        }
    }

    /**
     * Classify a message as spam or ham
     */
    fun classify(text: String): ClassificationResult {
        val interpreter = this.interpreter
            ?: return ClassificationResult(false, 0f, 0f, 1f)

        try {
            // Preprocess text
            val processed = preprocessText(text)

            // Tokenize
            val tokens = tokenize(processed)

            // Convert to input tensor
            val inputBuffer = createInputBuffer(tokens)

            // Output buffer [1, 2] - [ham_prob, spam_prob]
            val outputBuffer = Array(1) { FloatArray(2) }

            // Run inference
            interpreter.run(inputBuffer, outputBuffer)

            val hamProb = outputBuffer[0][0]
            val spamProb = outputBuffer[0][1]
            val isSpam = spamProb > hamProb && spamProb > 0.5f
            val confidence = if (isSpam) spamProb else hamProb

            return ClassificationResult(
                isSpam = isSpam,
                confidence = confidence,
                spamProbability = spamProb,
                hamProbability = hamProb
            )
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed", e)
            return ClassificationResult(false, 0f, 0f, 1f)
        }
    }

    /**
     * Batch classify multiple messages
     */
    fun classifyBatch(texts: List<String>): List<ClassificationResult> {
        return texts.map { classify(it) }
    }

    fun getModelVersion(): String? = modelVersion

    fun isModelLoaded(): Boolean = interpreter != null

    /**
     * Reload model (after update)
     */
    fun reloadModel() {
        close()
        loadModel()
        loadVocabulary()
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    // Model loading
    private fun loadModel() {
        // First try to load from files directory (downloaded model)
        val downloadedModel = File(context.filesDir, MODEL_FILE)
        if (downloadedModel.exists()) {
            try {
                val buffer = loadModelFromFile(downloadedModel)
                interpreter = Interpreter(buffer, Interpreter.Options().apply {
                    setNumThreads(2)
                })
                modelVersion = getModelVersionFromFile(downloadedModel)
                return
            } catch (_: Exception) { }
        }

        // Fall back to bundled model in assets
        try {
            val buffer = loadModelFromAssets()
            if (buffer != null) {
                interpreter = Interpreter(buffer, Interpreter.Options().apply {
                    setNumThreads(2)
                })
                modelVersion = "bundled-1.0"
            }
        } catch (_: Exception) { }
    }

    private fun loadModelFromFile(file: File): MappedByteBuffer {
        val inputStream = FileInputStream(file)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
    }

    private fun loadModelFromAssets(): MappedByteBuffer? {
        return try {
            val assetManager = context.assets
            val fileDescriptor = assetManager.openFd(MODEL_FILE)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val channel = inputStream.channel
            channel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun getModelVersionFromFile(file: File): String {
        // Version could be encoded in filename or separate metadata file
        val metaFile = File(file.parent, "model_meta.txt")
        return if (metaFile.exists()) {
            metaFile.readText().trim()
        } else {
            "downloaded-${file.lastModified()}"
        }
    }

    // Vocabulary loading
    private fun loadVocabulary() {
        // Try downloaded vocab first
        val downloadedVocab = File(context.filesDir, VOCAB_FILE)
        if (downloadedVocab.exists()) {
            try {
                vocabulary = loadVocabFromFile(downloadedVocab)
                return
            } catch (_: Exception) { }
        }

        // Fall back to bundled or default vocabulary
        try {
            vocabulary = loadVocabFromAssets()
        } catch (_: Exception) {
            // Use minimal default vocabulary
            vocabulary = createDefaultVocabulary()
        }
    }

    private fun loadVocabFromFile(file: File): Map<String, Int> {
        val vocab = mutableMapOf<String, Int>()
        file.readLines().forEachIndexed { index, word ->
            vocab[word.trim().lowercase()] = index
        }
        return vocab
    }

    private fun loadVocabFromAssets(): Map<String, Int> {
        val vocab = mutableMapOf<String, Int>()
        context.assets.open(VOCAB_FILE).bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, word ->
                vocab[word.trim().lowercase()] = index
            }
        }
        return vocab
    }

    private fun createDefaultVocabulary(): Map<String, Int> {
        // Common spam words with indices
        val spamWords = listOf(
            "<PAD>", "<UNK>", "<START>", "<END>",
            "free", "win", "winner", "won", "prize", "lottery", "cash", "money",
            "claim", "urgent", "click", "link", "verify", "account", "bank",
            "password", "otp", "code", "update", "kyc", "block", "suspend",
            "offer", "discount", "sale", "limited", "expire", "congratulations",
            "selected", "lucky", "credit", "loan", "approved", "instant",
            "earn", "income", "work", "home", "investment", "profit", "guaranteed",
            "call", "now", "today", "immediately", "hurry", "act", "fast",
            "sms", "text", "message", "reply", "send", "contact", "whatsapp",
            "amazon", "flipkart", "paytm", "phonepe", "google", "apple", "netflix",
            "hdfc", "icici", "sbi", "axis", "bank", "card", "payment", "transaction",
            "dear", "customer", "user", "member", "sir", "madam", "valued"
        )
        return spamWords.mapIndexed { index, word -> word.lowercase() to index }.toMap()
    }

    // Text preprocessing
    private fun preprocessText(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")  // Remove special chars
            .replace(Regex("\\s+"), " ")           // Normalize whitespace
            .trim()
    }

    private fun tokenize(text: String): List<Int> {
        val words = text.split(" ")
        val unkIndex = vocabulary["<UNK>"] ?: 1
        val padIndex = vocabulary["<PAD>"] ?: 0

        val tokens = mutableListOf<Int>()

        // Add start token if in vocab
        vocabulary["<START>"]?.let { tokens.add(it) }

        // Convert words to indices
        for (word in words) {
            val index = vocabulary[word] ?: unkIndex
            tokens.add(index)
            if (tokens.size >= MAX_SEQUENCE_LENGTH - 1) break
        }

        // Add end token if in vocab
        vocabulary["<END>"]?.let { tokens.add(it) }

        // Pad to fixed length
        while (tokens.size < MAX_SEQUENCE_LENGTH) {
            tokens.add(padIndex)
        }

        return tokens.take(MAX_SEQUENCE_LENGTH)
    }

    private fun createInputBuffer(tokens: List<Int>): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * 4)
        buffer.order(ByteOrder.nativeOrder())

        for (token in tokens) {
            buffer.putFloat(token.toFloat())
        }

        buffer.rewind()
        return buffer
    }
}

/**
 * Simple fallback classifier when TF Lite model is not available
 * Uses keyword-based scoring
 */
class SimpleSpamClassifier {

    private val spamKeywords = mapOf(
        // High confidence spam indicators
        "won" to 0.3f,
        "winner" to 0.3f,
        "lottery" to 0.4f,
        "prize" to 0.3f,
        "claim" to 0.2f,
        "congratulations" to 0.2f,

        // Banking fraud indicators
        "kyc" to 0.25f,
        "block" to 0.15f,
        "suspend" to 0.2f,
        "verify immediately" to 0.3f,
        "account will be" to 0.25f,

        // Urgency indicators
        "urgent" to 0.15f,
        "immediately" to 0.15f,
        "expire" to 0.1f,
        "last chance" to 0.2f,

        // Action requests
        "click here" to 0.2f,
        "click link" to 0.2f,
        "call now" to 0.15f,

        // Loan scams
        "pre-approved" to 0.2f,
        "instant loan" to 0.25f,
        "guaranteed" to 0.15f,

        // Job scams
        "work from home" to 0.2f,
        "earn daily" to 0.25f,
        "part time job" to 0.15f,
    )

    fun classify(text: String): SpamMLClassifier.ClassificationResult {
        val lowerText = text.lowercase()
        var spamScore = 0f

        for ((keyword, weight) in spamKeywords) {
            if (lowerText.contains(keyword)) {
                spamScore += weight
            }
        }

        // Normalize to 0-1 range
        spamScore = minOf(spamScore, 1f)

        val isSpam = spamScore >= 0.5f

        return SpamMLClassifier.ClassificationResult(
            isSpam = isSpam,
            confidence = if (isSpam) spamScore else 1 - spamScore,
            spamProbability = spamScore,
            hamProbability = 1 - spamScore
        )
    }
}
