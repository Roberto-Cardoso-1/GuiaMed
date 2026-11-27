package com.guiamed.ocr

import kotlin.math.roundToInt

data class PreviewLightingMetrics(
    val meanY: Float,
    val clippedRatio: Float,
)

enum class LightingCue {
    NONE,
    GLARE_HINT,
    LOW_LIGHT_NO_FLASH,
    LOW_LIGHT_NEED_CLOSER,
}

data class LightingDirective(
    val targetTorchOn: Boolean,
    val targetEvSteps: Int,
    val cue: LightingCue = LightingCue.NONE,
    val glareDetected: Boolean = false,
)

/**
 * Máquina de estados determinística para controlar lanterna e compensação de exposição (EV)
 * combinando lux ambiente (`Sensor.TYPE_LIGHT`), luminância central e taxa de estouro especular.
 */
class ScanLightingPolicy {

    var ambientLux: Float? = null
        private set

    var centerEmaY: Float? = null
        private set

    var lastClippedRatio: Float = 0f
        private set

    var lastGlareDetected: Boolean = false
        private set

    private var activeTorchOn = false
    private var activeEvSteps = 0
    private var lastAdjustmentAtMs = -AE_SETTLE_MILLIS
    private var lastTorchOffAtMs = -TORCH_OFF_COOLDOWN_MILLIS
    private var glareStreak = 0
    private var darkStreak = 0
    private var previewSampleVersion = 0L
    private var lastEvaluatedSampleVersion = -1L
    private var glareDisabledForSession = false
    private var torchTurnedOnAtMs = 0L

    fun updateAmbientLux(lux: Float) {
        if (!lux.isFinite() || lux < 0f) return
        ambientLux = lux
    }

    fun updatePreviewMetrics(metrics: PreviewLightingMetrics, nowMs: Long) {
        if (!metrics.meanY.isFinite() || !metrics.clippedRatio.isFinite()) return
        if (nowMs - lastAdjustmentAtMs < AE_SETTLE_MILLIS) return

        val clampedMean = metrics.meanY.coerceIn(0f, 255f)
        val clampedClip = metrics.clippedRatio.coerceIn(0f, 1f)
        centerEmaY = centerEmaY?.let { prev ->
            EMA_ALPHA * clampedMean + (1f - EMA_ALPHA) * prev
        } ?: clampedMean
        lastClippedRatio = clampedClip
        previewSampleVersion++
    }

    fun startSession(
        hasFlash: Boolean,
        minEvSteps: Int,
        maxEvSteps: Int,
        nowMs: Long,
    ): LightingDirective {
        glareStreak = 0
        darkStreak = 0
        lastEvaluatedSampleVersion = previewSampleVersion
        glareDisabledForSession = false
        lastGlareDetected = false
        activeEvSteps = 0

        if (!hasFlash) {
            activeTorchOn = false
            return LightingDirective(targetTorchOn = false, targetEvSteps = 0)
        }

        val shouldEnable = shouldTurnTorchOn(initialStart = true, countDarkSample = false)
        if (shouldEnable) {
            activeTorchOn = true
            torchTurnedOnAtMs = nowMs
            lastAdjustmentAtMs = nowMs
        } else {
            activeTorchOn = false
        }
        return LightingDirective(
            targetTorchOn = activeTorchOn,
            targetEvSteps = 0.coerceIn(minEvSteps, maxEvSteps),
        )
    }

    fun evaluate(
        hasFlash: Boolean,
        minEvSteps: Int,
        maxEvSteps: Int,
        nowMs: Long,
        glareHintActive: Boolean = true,
        evStepEv: Float = 0.5f,
    ): LightingDirective {
        val hasNewPreviewSample = previewSampleVersion != lastEvaluatedSampleVersion
        lastEvaluatedSampleVersion = previewSampleVersion

        if (!hasFlash) {
            activeTorchOn = false
            activeEvSteps = 0
            val cue = if (isClearlyDark()) LightingCue.LOW_LIGHT_NO_FLASH else LightingCue.NONE
            return LightingDirective(targetTorchOn = false, targetEvSteps = 0, cue = cue)
        }

        val settled = nowMs - lastAdjustmentAtMs >= AE_SETTLE_MILLIS
        val ema = centerEmaY
        val clip = lastClippedRatio
        val lux = ambientLux

        if (!activeTorchOn) {
            if (!glareDisabledForSession &&
                nowMs - lastTorchOffAtMs >= TORCH_OFF_COOLDOWN_MILLIS &&
                shouldTurnTorchOn(initialStart = false, countDarkSample = hasNewPreviewSample)
            ) {
                activeTorchOn = true
                torchTurnedOnAtMs = nowMs
                lastAdjustmentAtMs = nowMs
                darkStreak = 0
                glareStreak = 0
                return LightingDirective(targetTorchOn = true, targetEvSteps = activeEvSteps)
            }
            val cue = if (glareDisabledForSession && glareHintActive && clip >= MODERATE_GLARE_RATIO) {
                LightingCue.GLARE_HINT
            } else {
                LightingCue.NONE
            }
            return LightingDirective(targetTorchOn = false, targetEvSteps = activeEvSteps, cue = cue)
        }

        if (!settled) {
            return LightingDirective(
                targetTorchOn = true,
                targetEvSteps = activeEvSteps,
                glareDetected = lastGlareDetected,
            )
        }

        val moderateGlare = clip >= MODERATE_GLARE_RATIO || (ema != null && ema >= HIGH_LUMA_GLARE_THRESHOLD)
        val severeGlare = clip >= SEVERE_GLARE_RATIO || (ema != null && ema >= EXTREME_LUMA_THRESHOLD)

        if (hasNewPreviewSample) {
            if (moderateGlare) {
                glareStreak++
                lastGlareDetected = true
            } else {
                glareStreak = 0
                if (clip < MODERATE_GLARE_RATIO * 0.6f && (ema == null || ema < HIGH_LUMA_GLARE_THRESHOLD - 20f)) {
                    lastGlareDetected = false
                }
            }
        }

        // Camada 1: reduz EV (~ -1.0 EV) antes de desligar a lanterna em reflexo moderado.
        val desiredNegativeEv = negativeEvTarget(minEvSteps, maxEvSteps, evStepEv)
        if (moderateGlare && glareStreak >= 2 && activeEvSteps > desiredNegativeEv && desiredNegativeEv < 0) {
            activeEvSteps = desiredNegativeEv
            lastAdjustmentAtMs = nowMs
            glareStreak = 0
            return LightingDirective(
                targetTorchOn = true,
                targetEvSteps = activeEvSteps,
                cue = if (glareHintActive) LightingCue.GLARE_HINT else LightingCue.NONE,
                glareDetected = true,
            )
        }

        // Camada 2: em reflexo severo fora de breu absoluto, desliga a lanterna 1 vez na sessão (sem oscilar).
        val isPitchDark = lux != null && lux < PITCH_DARK_LUX
        if (severeGlare && glareStreak >= 3) {
            if (!isPitchDark) {
                activeTorchOn = false
                activeEvSteps = 0.coerceIn(minEvSteps, maxEvSteps)
                glareDisabledForSession = true
                lastTorchOffAtMs = nowMs
                lastAdjustmentAtMs = nowMs
                glareStreak = 0
                return LightingDirective(
                    targetTorchOn = false,
                    targetEvSteps = activeEvSteps,
                    cue = if (glareHintActive) LightingCue.GLARE_HINT else LightingCue.NONE,
                    glareDetected = true,
                )
            }
            return LightingDirective(
                targetTorchOn = true,
                targetEvSteps = activeEvSteps,
                cue = if (glareHintActive) LightingCue.GLARE_HINT else LightingCue.NONE,
                glareDetected = true,
            )
        }

        val stillDarkAfterFlash = ema != null &&
            ema < VERY_DARK_Y_WITH_FLASH &&
            nowMs - torchTurnedOnAtMs >= FLASH_EFFECTIVE_CHECK_MILLIS
        val cue = when {
            moderateGlare && glareHintActive -> LightingCue.GLARE_HINT
            stillDarkAfterFlash -> LightingCue.LOW_LIGHT_NEED_CLOSER
            else -> LightingCue.NONE
        }
        return LightingDirective(
            targetTorchOn = true,
            targetEvSteps = activeEvSteps,
            cue = cue,
            glareDetected = lastGlareDetected,
        )
    }

    /**
     * Em segunda tentativa de foto da validade (ex.: verso aluminizado ou aba com alto reflexo),
     * alterna a lanterna ou reduz EV quando houve reflexo ou baixa luz persistente sem flash.
     */
    fun overrideForValidityRetry(
        hasFlash: Boolean,
        minEvSteps: Int,
        maxEvSteps: Int,
        nowMs: Long,
        evStepEv: Float = 0.5f,
    ): LightingDirective {
        if (!hasFlash) return LightingDirective(targetTorchOn = false, targetEvSteps = 0)
        val isPitchDark = ambientLux != null && ambientLux!! < PITCH_DARK_LUX
        val hadGlare = lastGlareDetected || lastClippedRatio >= MODERATE_GLARE_RATIO
        if (activeTorchOn && hadGlare) {
            if (!isPitchDark) {
                activeTorchOn = false
                activeEvSteps = 0.coerceIn(minEvSteps, maxEvSteps)
                glareDisabledForSession = true
                lastTorchOffAtMs = nowMs
                lastAdjustmentAtMs = nowMs
                return LightingDirective(targetTorchOn = false, targetEvSteps = activeEvSteps, glareDetected = true)
            }
            val desiredNegativeEv = negativeEvTarget(minEvSteps, maxEvSteps, evStepEv)
            if (activeEvSteps > desiredNegativeEv && desiredNegativeEv < 0) {
                activeEvSteps = desiredNegativeEv
                lastAdjustmentAtMs = nowMs
            }
            return LightingDirective(targetTorchOn = true, targetEvSteps = activeEvSteps, glareDetected = true)
        }
        if (!activeTorchOn && !glareDisabledForSession && isClearlyDark()) {
            activeTorchOn = true
            torchTurnedOnAtMs = nowMs
            lastAdjustmentAtMs = nowMs
            return LightingDirective(targetTorchOn = true, targetEvSteps = activeEvSteps)
        }
        return LightingDirective(
            targetTorchOn = activeTorchOn,
            targetEvSteps = activeEvSteps,
            glareDetected = lastGlareDetected,
        )
    }

    fun stopSession() {
        val wasTorchOn = activeTorchOn
        activeTorchOn = false
        activeEvSteps = 0
        glareStreak = 0
        darkStreak = 0
        glareDisabledForSession = false
        lastGlareDetected = false
        if (wasTorchOn) {
            // Não reutiliza o brilho artificial do LED como leitura de luz ambiente na próxima varredura.
            centerEmaY = null
            lastClippedRatio = 0f
        }
    }

    fun clearEnvironmentState() {
        stopSession()
        ambientLux = null
        centerEmaY = null
        lastClippedRatio = 0f
    }

    fun syncHardwareTorch(torchCurrentlyOn: Boolean, nowMs: Long) {
        if (activeTorchOn != torchCurrentlyOn) {
            activeTorchOn = torchCurrentlyOn
            lastAdjustmentAtMs = nowMs
            if (torchCurrentlyOn) torchTurnedOnAtMs = nowMs
        }
    }

    private fun negativeEvTarget(minEvSteps: Int, maxEvSteps: Int, evStepEv: Float): Int {
        if (minEvSteps >= 0) return 0.coerceIn(minEvSteps, maxEvSteps)
        val safeStep = evStepEv.takeIf { it.isFinite() && it > 0.05f } ?: 0.5f
        val stepsForOneEv = (-1.0f / safeStep).roundToInt().coerceAtMost(-1)
        return stepsForOneEv.coerceIn(minEvSteps, maxEvSteps)
    }

    private fun shouldTurnTorchOn(initialStart: Boolean, countDarkSample: Boolean): Boolean {
        val lux = ambientLux
        val ema = centerEmaY

        // 1. Sensor de luz ambiente: imune ao ganho ISO/AE da câmera.
        if (lux != null && lux < DARK_LUX_THRESHOLD) {
            return true
        }

        // 2. Zona de penumbra/sombra (ou dispositivo sem sensor de lux): usa EMA central de Y.
        val darkYDetected = when {
            lux == null || lux <= BRIGHT_LUX_THRESHOLD -> ema != null && ema < DARK_Y_THRESHOLD
            else -> ema != null && ema < SEVERE_LOCAL_SHADOW_Y_THRESHOLD
        }
        if (darkYDetected) {
            if (initialStart) return true
            if (countDarkSample) darkStreak++
            return darkStreak >= 2
        }

        if (!initialStart && countDarkSample) darkStreak = 0
        return false
    }

    private fun isClearlyDark(): Boolean {
        val lux = ambientLux
        val ema = centerEmaY
        return (lux != null && lux < DARK_LUX_THRESHOLD) || (ema != null && ema < DARK_Y_THRESHOLD)
    }

    companion object {
        const val DARK_LUX_THRESHOLD = 25f
        const val BRIGHT_LUX_THRESHOLD = 85f
        const val PITCH_DARK_LUX = 12f

        const val DARK_Y_THRESHOLD = 58f
        const val SEVERE_LOCAL_SHADOW_Y_THRESHOLD = 42f
        const val VERY_DARK_Y_WITH_FLASH = 40f

        const val MODERATE_GLARE_RATIO = 0.16f
        const val SEVERE_GLARE_RATIO = 0.30f
        const val HIGH_LUMA_GLARE_THRESHOLD = 200f
        const val EXTREME_LUMA_THRESHOLD = 225f

        const val EMA_ALPHA = 0.35f
        const val AE_SETTLE_MILLIS = 350L
        const val TORCH_OFF_COOLDOWN_MILLIS = 2_500L
        const val FLASH_EFFECTIVE_CHECK_MILLIS = 900L
    }
}
