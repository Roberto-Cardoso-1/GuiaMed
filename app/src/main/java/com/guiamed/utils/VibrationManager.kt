package com.guiamed.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.min

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

    fun vibrateDoubleShort() = vibratePattern(longArrayOf(0, 50, 100, 50))

    fun vibrateLong() = vibrateOnce(300)

    fun vibrateTripleLong() = vibratePattern(longArrayOf(0, 300, 200, 300, 200, 300))

    fun vibrateAlert() = vibratePattern(longArrayOf(0, 500, 200, 500, 200, 500))

    fun vibrateSuccess() = vibratePattern(longArrayOf(0, 100, 100, 100, 100, 200))

    private fun vibrateOnce(duration: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    private fun vibratePattern(pattern: LongArray) {
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