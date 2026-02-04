package com.phoneintegration.app.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * Haptic feedback utility for providing tactile responses
 */
object HapticFeedbackUtils {

    /**
     * Get the system Vibrator service
     */
    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * Light tap feedback (for button clicks, selections)
     */
    fun lightTap(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(10)
        }
    }

    /**
     * Medium tap feedback (for swipe actions, toggles)
     */
    fun mediumTap(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(25)
        }
    }

    /**
     * Success feedback (for completed actions)
     */
    fun success(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            // Double tap pattern for success
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 15, 50, 15), -1)
        }
    }

    /**
     * Error/warning feedback
     */
    fun error(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Strong double vibration for error
            val pattern = longArrayOf(0, 30, 80, 30)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 30, 80, 30), -1)
        }
    }

    /**
     * Long press feedback
     */
    fun longPress(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    /**
     * Selection changed feedback
     */
    fun selectionChanged(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(5)
        }
    }

    /**
     * Keyboard tap feedback
     */
    fun keyboardTap(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(5, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(5)
        }
    }

    /**
     * Delete/swipe action feedback
     */
    fun delete(context: Context) {
        val vibrator = getVibrator(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 20, 40, 20), -1)
        }
    }
}

/**
 * Enum of haptic feedback types for the app
 */
enum class HapticType {
    LIGHT_TAP,
    MEDIUM_TAP,
    SUCCESS,
    ERROR,
    LONG_PRESS,
    SELECTION,
    KEYBOARD,
    DELETE
}

/**
 * Extension function to perform haptic feedback
 */
fun View.performHaptic(type: HapticType) {
    val constant = when (type) {
        HapticType.LIGHT_TAP -> HapticFeedbackConstants.VIRTUAL_KEY
        HapticType.MEDIUM_TAP -> HapticFeedbackConstants.LONG_PRESS
        HapticType.SUCCESS -> HapticFeedbackConstants.CONFIRM
        HapticType.ERROR -> HapticFeedbackConstants.REJECT
        HapticType.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
        HapticType.SELECTION -> HapticFeedbackConstants.CLOCK_TICK
        HapticType.KEYBOARD -> HapticFeedbackConstants.KEYBOARD_TAP
        HapticType.DELETE -> HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(constant)
}

/**
 * Composable hook for haptic feedback in Compose UI
 */
@Composable
fun rememberHapticFeedback(): HapticController {
    val view = LocalView.current
    val hapticFeedback = LocalHapticFeedback.current

    return remember(view, hapticFeedback) {
        HapticController(view, hapticFeedback)
    }
}

/**
 * Controller for haptic feedback in Compose
 */
class HapticController(
    private val view: View,
    private val composeFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback
) {
    /**
     * Perform light tap feedback
     */
    fun lightTap() {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /**
     * Perform medium tap feedback
     */
    fun mediumTap() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * Perform long press feedback
     */
    fun longPress() {
        composeFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * Perform text handle move feedback
     */
    fun textHandleMove() {
        composeFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /**
     * Perform success feedback
     */
    fun success() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /**
     * Perform error feedback
     */
    fun error() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * Perform selection feedback
     */
    fun selection() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /**
     * Perform keyboard tap feedback
     */
    fun keyboard() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * Perform delete/swipe feedback
     */
    fun delete() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
