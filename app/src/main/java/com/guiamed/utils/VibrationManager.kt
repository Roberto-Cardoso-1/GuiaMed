package com.guiamed.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class VibrationManager(private val context: Context) {

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(VibratorManager::class.java)
            vibratorManager.getDefaultVibrator()
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun vibrateShort() = vibrateOnce(50)

    fun vibrateFocusTick() {
        val amplitude = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()) {
            85
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }
        vibrateOnce(25, amplitude)
    }

    fun vibrateDoubleShort() = vibratePattern(longArrayOf(0, 50, 100, 50))

    fun vibrateGuidance() = vibrateOnce(110)

    fun vibrateFlipBox() = vibratePattern(longArrayOf(0, 140, 90, 60))

    fun vibrateLong() = vibrateOnce(300)

    fun vibrateTripleLong() = vibratePattern(longArrayOf(0, 300, 200, 300, 200, 300))

    fun vibrateAlert() = vibratePattern(longArrayOf(0, 500, 200, 500, 200, 500))

    fun vibrateSuccess() = vibratePattern(longArrayOf(0, 100, 100, 100, 100, 200))

    private fun vibrateOnce(duration: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    private fun vibratePattern(pattern: LongArray) {
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    fun cancel() {
        vibrator.cancel()
    }
}
