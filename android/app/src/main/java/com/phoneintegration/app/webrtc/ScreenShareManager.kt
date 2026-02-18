package com.phoneintegration.app.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.*

/**
 * Manages screen capture via Android MediaProjection for WebRTC screen sharing.
 * Wraps ScreenCapturerAndroid to produce a VideoTrack suitable for PeerConnection.
 */
class ScreenShareManager(
    private val context: Context,
    private val eglBase: EglBase,
    private val peerConnectionFactory: PeerConnectionFactory
) {
    companion object {
        private const val TAG = "ScreenShareManager"
        private const val SCREEN_TRACK_ID = "screen0"
        private const val SCREEN_STREAM_ID = "screen_stream"
    }

    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var isCapturing = false

    /**
     * Start screen capture using the MediaProjection result from the consent dialog.
     * @param resultCode Activity.RESULT_OK from MediaProjection intent
     * @param data Intent data from MediaProjection consent
     * @param width Capture width in pixels
     * @param height Capture height in pixels
     * @param fps Frames per second
     */
    fun startCapture(
        resultCode: Int,
        data: Intent,
        width: Int = 720,
        height: Int = 1280,
        fps: Int = 15
    ): VideoTrack? {
        if (isCapturing) {
            Log.w(TAG, "Already capturing, returning existing track")
            return videoTrack
        }

        try {
            // Create MediaProjection callback for cleanup
            val mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped")
                    stopCapture()
                }
            }

            // Create ScreenCapturerAndroid - this is provided by the WebRTC SDK
            screenCapturer = ScreenCapturerAndroid(data, mediaProjectionCallback)

            // Create video source with isScreencast=true for screen content optimization
            videoSource = peerConnectionFactory.createVideoSource(true) // isScreencast=true

            surfaceTextureHelper = SurfaceTextureHelper.create(
                "ScreenCaptureThread",
                eglBase.eglBaseContext
            )

            screenCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
            screenCapturer?.startCapture(width, height, fps)

            videoTrack = peerConnectionFactory.createVideoTrack(SCREEN_TRACK_ID, videoSource)
            videoTrack?.setEnabled(true)

            isCapturing = true
            Log.d(TAG, "Screen capture started: ${width}x${height}@${fps}fps")

            return videoTrack

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen capture", e)
            stopCapture()
            return null
        }
    }

    /**
     * Stop screen capture and release all resources.
     */
    fun stopCapture() {
        isCapturing = false

        try {
            screenCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping capturer: ${e.message}")
        }

        try {
            screenCapturer?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing capturer: ${e.message}")
        }

        try {
            videoTrack?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing video track: ${e.message}")
        }

        try {
            videoSource?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing video source: ${e.message}")
        }

        try {
            surfaceTextureHelper?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing surface texture helper: ${e.message}")
        }

        screenCapturer = null
        videoSource = null
        videoTrack = null
        surfaceTextureHelper = null

        Log.d(TAG, "Screen capture stopped and resources released")
    }

    /**
     * Get the current screen capture video track.
     */
    fun getVideoTrack(): VideoTrack? = videoTrack

    /**
     * Get the stream ID for the screen share track.
     */
    fun getStreamId(): String = SCREEN_STREAM_ID

    /**
     * Whether screen capture is currently active.
     */
    fun isActive(): Boolean = isCapturing

    /**
     * Change capture resolution/fps while active.
     */
    fun changeCaptureFormat(width: Int, height: Int, fps: Int) {
        if (!isCapturing) return
        try {
            screenCapturer?.changeCaptureFormat(width, height, fps)
            Log.d(TAG, "Capture format changed to ${width}x${height}@${fps}fps")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to change capture format: ${e.message}")
        }
    }
}
