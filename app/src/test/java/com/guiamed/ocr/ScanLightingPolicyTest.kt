package com.guiamed.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLightingPolicyTest {

    @Test
    fun darkAmbientLuxTurnsTorchOnEvenWhenAutoExposureBoostsPreviewLuma() {
        val policy = ScanLightingPolicy()
        policy.updateAmbientLux(8f)
        // Câmera compensou no ISO e elevou Y para 95: regra antiga (< 50) falharia!
        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 95f, clippedRatio = 0f), nowMs = 1_000L)

        val directive = policy.startSession(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_500L)

        assertTrue(directive.targetTorchOn)
        assertEquals(0, directive.targetEvSteps)
    }

    @Test
    fun stoppingUnlitSessionPreservesBaselineWhileLitSessionResetsFlashInflatedLuma() {
        val policy = ScanLightingPolicy()
        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 175f, clippedRatio = 0f), nowMs = 1_000L)

        // Sessão em local claro (lanterna não acendeu): preserva EMA real do ambiente.
        val brightStart = policy.startSession(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_100L)
        assertFalse(brightStart.targetTorchOn)
        policy.stopSession()
        assertEquals(175f, policy.centerEmaY ?: 0f, 0.01f)

        // Sessão no escuro com lux: lanterna acende e EMA sobe pelo LED.
        policy.updateAmbientLux(10f)
        val darkStart = policy.startSession(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_400L)
        assertTrue(darkStart.targetTorchOn)
        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 180f, clippedRatio = 0f), nowMs = 1_900L)
        policy.stopSession()

        // O brilho artificial pós-LED é descartado, mas o sensor de lux segue preservado.
        assertNull(policy.centerEmaY)
        assertEquals(10f, policy.ambientLux ?: 0f, 0.01f)
        val nextDarkStart = policy.startSession(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 2_000L)
        assertTrue(nextDarkStart.targetTorchOn)
    }

    @Test
    fun mediumLightDeadbandAvoidsFlickering() {
        val policy = ScanLightingPolicy()
        policy.updateAmbientLux(50f) // Entre 25 e 85 lux (faixa neutra)
        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 105f, clippedRatio = 0f), nowMs = 1_000L)

        val start = policy.startSession(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_100L)
        assertFalse(start.targetTorchOn)

        val steady = policy.evaluate(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 2_000L)
        assertFalse(steady.targetTorchOn)
    }

    @Test
    fun localizedDarkShadowUnderBrightRoomLightTurnsTorchOnAfterTwoFrames() {
        val policy = ScanLightingPolicy()
        policy.updateAmbientLux(120f)
        policy.startSession(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_000L)

        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 38f, clippedRatio = 0f), nowMs = 1_200L)
        val firstEval = policy.evaluate(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_210L)
        assertFalse(firstEval.targetTorchOn)

        // Chamadas extras sem novo frame não podem inflar a streak.
        val repeatedSameFrame = policy.evaluate(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_220L)
        assertFalse(repeatedSameFrame.targetTorchOn)

        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 36f, clippedRatio = 0f), nowMs = 1_300L)
        val secondEval = policy.evaluate(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_310L)
        assertTrue(secondEval.targetTorchOn)
    }

    @Test
    fun coldStartDarkFrameEnablesTorchDuringEvaluationOnceFramesArrive() {
        val policy = ScanLightingPolicy()
        // Sem lux e sem preview no milissegundo 0: começa desligada, mas não assume 128 fixo.
        val start = policy.startSession(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_000L)
        assertFalse(start.targetTorchOn)

        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 34f, clippedRatio = 0f), nowMs = 1_400L)
        policy.evaluate(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_410L)
        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 32f, clippedRatio = 0f), nowMs = 1_800L)
        val secondEval = policy.evaluate(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_810L)

        assertTrue(secondEval.targetTorchOn)
    }

    @Test
    fun transientBrightFramesRightAfterTorchTurnsOnAreIgnoredDuringAeSettleWindow() {
        val policy = ScanLightingPolicy()
        policy.updateAmbientLux(10f)
        val start = policy.startSession(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_000L)
        assertTrue(start.targetTorchOn)

        // Quadros superexpostos durante os primeiros 350ms da acomodação do AE não devem contaminar EMA nem desligar o flash.
        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 248f, clippedRatio = 0.80f), nowMs = 1_150L)
        val duringSettle = policy.evaluate(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_200L)

        assertTrue(duringSettle.targetTorchOn)
        assertEquals(0, duringSettle.targetEvSteps)
    }

    @Test
    fun sustainedGlareReducesEvUsingStepSizeFirstAndTurnsTorchOffOnceWithoutOscillating() {
        val policy = ScanLightingPolicy()
        policy.updateAmbientLux(45f)
        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 44f, clippedRatio = 0f), nowMs = 1_000L)
        val start = policy.startSession(hasFlash = true, minEvSteps = -6, maxEvSteps = 6, nowMs = 1_100L)
        assertTrue(start.targetTorchOn)

        // Frame 1 com reflexo moderado -> ainda não dispara redução mesmo com múltiplas avaliações repetidas.
        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 210f, clippedRatio = 0.24f), nowMs = 1_600L)
        val eval1a = policy.evaluate(hasFlash = true, minEvSteps = -6, maxEvSteps = 6, nowMs = 1_610L, evStepEv = 0.33f)
        val eval1b = policy.evaluate(hasFlash = true, minEvSteps = -6, maxEvSteps = 6, nowMs = 1_620L, evStepEv = 0.33f)
        assertEquals(0, eval1a.targetEvSteps)
        assertEquals(0, eval1b.targetEvSteps)

        // Frame 2 com reflexo -> Camada 1 reduz EV em ~-1.0 EV (-3 passos quando step = 0.33 EV).
        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 220f, clippedRatio = 0.26f), nowMs = 2_000L)
        val reducedEv = policy.evaluate(hasFlash = true, minEvSteps = -6, maxEvSteps = 6, nowMs = 2_010L, evStepEv = 0.33f)

        assertTrue(reducedEv.targetTorchOn)
        assertEquals(-3, reducedEv.targetEvSteps)
        assertEquals(LightingCue.GLARE_HINT, reducedEv.cue)

        // Se continuar reflexo severo -> Camada 2 desliga a lanterna uma única vez.
        var t = 2_450L
        var lastDirective = reducedEv
        repeat(4) {
            policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 235f, clippedRatio = 0.42f), nowMs = t)
            lastDirective = policy.evaluate(hasFlash = true, minEvSteps = -6, maxEvSteps = 6, nowMs = t + 10L, evStepEv = 0.33f)
            t += 400L
        }

        assertFalse(lastDirective.targetTorchOn)
        assertEquals(0, lastDirective.targetEvSteps)

        // Mesmo que fique escuro depois na mesma sessão, não oscila voltando a ligar na hora.
        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 30f, clippedRatio = 0f), nowMs = 5_000L)
        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 30f, clippedRatio = 0f), nowMs = 5_400L)
        val noOscillation = policy.evaluate(hasFlash = true, minEvSteps = -6, maxEvSteps = 6, nowMs = 5_500L, evStepEv = 0.33f)
        assertFalse(noOscillation.targetTorchOn)
    }

    @Test
    fun pitchDarkKeepsTorchOnStepsEvNegativeOnRetryAndEmitsLowLightCuesWhenApplicable() {
        val policy = ScanLightingPolicy()
        policy.updateAmbientLux(4f)
        val start = policy.startSession(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_000L)
        assertTrue(start.targetTorchOn)

        // 1 único frame com reflexo em breu: se for retentar validade, aplica EV negativo mantendo lanterna ligada.
        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 215f, clippedRatio = 0.25f), nowMs = 1_500L)
        policy.evaluate(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_510L)
        val pitchDarkRetry = policy.overrideForValidityRetry(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_600L)
        assertTrue(pitchDarkRetry.targetTorchOn)
        assertEquals(-2, pitchDarkRetry.targetEvSteps)
        assertTrue(pitchDarkRetry.glareDetected)

        // Sem flash disponível em local escuro -> sinaliza LOW_LIGHT_NO_FLASH.
        policy.clearEnvironmentState()
        assertNull(policy.ambientLux)
        assertNull(policy.centerEmaY)
        policy.updateAmbientLux(5f)
        val noFlash = policy.evaluate(hasFlash = false, minEvSteps = 0, maxEvSteps = 0, nowMs = 2_000L)
        assertFalse(noFlash.targetTorchOn)
        assertEquals(LightingCue.LOW_LIGHT_NO_FLASH, noFlash.cue)
    }

    @Test
    fun validityRetryDisablesTorchOnShinyTabAndEnablesTorchWhenUnlitDark() {
        val policy = ScanLightingPolicy()
        policy.updateAmbientLux(35f)
        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 42f, clippedRatio = 0f), nowMs = 1_000L)
        assertTrue(policy.startSession(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_100L).targetTorchOn)

        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 205f, clippedRatio = 0.20f), nowMs = 1_600L)
        policy.evaluate(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 1_610L)

        val retryDirective = policy.overrideForValidityRetry(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 2_000L)
        assertFalse(retryDirective.targetTorchOn)
        assertTrue(retryDirective.glareDetected)

        // Segundo cenário: começou sem flash e depois ficou escuro -> retry liga a lanterna.
        policy.clearEnvironmentState()
        policy.updateAmbientLux(50f)
        assertFalse(policy.startSession(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 3_000L).targetTorchOn)
        policy.updatePreviewMetrics(PreviewLightingMetrics(meanY = 40f, clippedRatio = 0f), nowMs = 3_200L)
        val retryDarkOn = policy.overrideForValidityRetry(hasFlash = true, minEvSteps = -4, maxEvSteps = 4, nowMs = 3_210L)
        assertTrue(retryDarkOn.targetTorchOn)
    }
}
