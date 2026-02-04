package com.phoneintegration.app.webrtc

import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame
import kotlin.math.roundToInt

enum class VideoEffect {
    NONE,
    FACE_FOCUS,
    BACKGROUND_BLUR
}

class VideoEffectCapturerObserver(
    private val downstream: CapturerObserver,
    private val effectProvider: () -> VideoEffect
) : CapturerObserver {
    private var frameCounter = 0
    private var faceFocusDisabledLogged = false

    override fun onCapturerStarted(success: Boolean) {
        downstream.onCapturerStarted(success)
    }

    override fun onCapturerStopped() {
        downstream.onCapturerStopped()
    }

    override fun onFrameCaptured(frame: VideoFrame) {
        val effect = effectProvider()

        if (effect == VideoEffect.FACE_FOCUS) {
            if (!faceFocusDisabledLogged) {
                android.util.Log.w("VideoEffects", "Face focus disabled on Android (stability safeguard)")
                faceFocusDisabledLogged = true
            }
            downstream.onFrameCaptured(frame)
            return
        }

        // Throttle heavy effects to avoid stalls on some devices.
        frameCounter++
        val shouldProcess = when (effect) {
            VideoEffect.NONE -> true
            VideoEffect.FACE_FOCUS -> frameCounter % 2 == 0
            VideoEffect.BACKGROUND_BLUR -> frameCounter % 3 == 0
        }

        if (!shouldProcess) {
            downstream.onFrameCaptured(frame)
            return
        }

        if (effect == VideoEffect.NONE) {
            downstream.onFrameCaptured(frame)
            return
        }

        val buffer = frame.buffer
        val width = buffer.width
        val height = buffer.height

        if (width <= 0 || height <= 0) {
            downstream.onFrameCaptured(frame)
            return
        }

        val processedBuffer = try {
            when (effect) {
                VideoEffect.FACE_FOCUS -> applyFaceFocus(buffer, width, height)
                VideoEffect.BACKGROUND_BLUR -> applyBackgroundBlur(buffer, width, height)
                VideoEffect.NONE -> null
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoEffects", "Error processing frame: ${e.message}")
            null
        }

        if (processedBuffer == null) {
            downstream.onFrameCaptured(frame)
            return
        }

        try {
            // Create new frame with processed buffer and pass to downstream
            // Retain the buffer so it survives after frame release
            processedBuffer.retain()
            val processedFrame = VideoFrame(processedBuffer, frame.rotation, frame.timestampNs)
            downstream.onFrameCaptured(processedFrame)
        } finally {
            // Release our reference to the buffer
            // Downstream observer will have retained if needed
            processedBuffer.release()
        }
    }

    private fun applyFaceFocus(
        buffer: VideoFrame.Buffer,
        width: Int,
        height: Int
    ): VideoFrame.Buffer? {
        if (width <= 0 || height <= 0) return null

        val zoom = 0.72f
        val cropWidth = (width * zoom).roundToInt().coerceIn(1, width)
        val cropHeight = (height * zoom).roundToInt().coerceIn(1, height)
        val cropX = ((width - cropWidth) / 2f).roundToInt().coerceIn(0, width - cropWidth)
        val cropY = ((height - cropHeight) / 2f).roundToInt().coerceIn(0, height - cropHeight)

        // Validate crop region is within bounds
        if (cropX < 0 || cropY < 0 || cropX + cropWidth > width || cropY + cropHeight > height) {
            android.util.Log.w("VideoEffects", "Invalid crop region: x=$cropX, y=$cropY, w=$cropWidth, h=$cropHeight for frame ${width}x${height}")
            return null
        }

        return try {
            buffer.cropAndScale(cropX, cropY, cropWidth, cropHeight, width, height)
        } catch (e: Exception) {
            android.util.Log.e("VideoEffects", "cropAndScale failed: ${e.message}")
            null
        }
    }

    private fun applyBackgroundBlur(
        buffer: VideoFrame.Buffer,
        width: Int,
        height: Int
    ): VideoFrame.Buffer? {
        if (width <= 0 || height <= 0) return null

        return try {
            val scale = 0.2f
            val smallWidth = (width * scale).roundToInt().coerceAtLeast(1)
            val smallHeight = (height * scale).roundToInt().coerceAtLeast(1)

            val downscaled = buffer.cropAndScale(0, 0, width, height, smallWidth, smallHeight)
            val blurred = downscaled.cropAndScale(0, 0, smallWidth, smallHeight, width, height)
            downscaled.release()
            blurred
        } catch (e: Exception) {
            android.util.Log.e("VideoEffects", "Background blur failed: ${e.message}")
            null
        }
    }
}
