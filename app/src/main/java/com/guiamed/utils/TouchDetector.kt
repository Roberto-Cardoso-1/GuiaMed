package com.guiamed.utils

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TouchDetector(
    private val onSingleTap: () -> Unit,
    private val onDoubleTap: () -> Unit,
    private val onTripleTap: () -> Unit,
    private val onQuadTap: () -> Unit = {},
    private val onLongPress: () -> Unit = {},
    private val tapWindowMs: Long = 500,
    private val longPressMs: Long = 700
) {

    private var tapCount = 0
    private var tapTimer: Job? = null
    private var longPressTriggered = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        resetTapSequence()
        onLongPress()
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressTriggered = false
                handler.postDelayed(longPressRunnable, longPressMs)
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (!longPressTriggered) handleTap()
            }
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPressRunnable)
        }
        return true
    }

    private fun handleTap() {
        tapCount++
        tapTimer?.cancel()
        tapTimer = scope.launch {
            delay(tapWindowMs)
            processTapCount()
        }
    }

    private fun processTapCount() {
        when (tapCount) {
            1 -> onSingleTap()
            2 -> onDoubleTap()
            3 -> onTripleTap()
            4 -> onQuadTap()
        }
        tapCount = 0
    }

    private fun resetTapSequence() {
        tapTimer?.cancel()
        tapTimer = null
        tapCount = 0
    }

    fun reset() {
        handler.removeCallbacks(longPressRunnable)
        resetTapSequence()
    }

    fun destroy() {
        reset()
        scope.cancel()
    }
}
