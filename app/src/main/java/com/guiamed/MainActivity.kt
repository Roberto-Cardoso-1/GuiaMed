package com.guiamed

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.cancel
import com.google.common.util.concurrent.ListenableFuture
import com.guiamed.data.GuiaMedRepository
import com.guiamed.data.ExpirationDate
import com.guiamed.data.ExpirationReader
import com.guiamed.data.FramingState
import com.guiamed.data.LocalAllergyStore
import com.guiamed.data.PackageFace
import com.guiamed.data.PackageVisionGuidance
import com.guiamed.data.PackageVisionStats
import com.guiamed.data.PrescriptionAlarm
import com.guiamed.data.PrescriptionScheduleParser
import com.guiamed.data.VerificarResponse
import com.guiamed.data.ReceitaResponse
import com.guiamed.data.withReadExpiration
import com.guiamed.ocr.LightingCue
import com.guiamed.ocr.LightingDirective
import com.guiamed.ocr.MlKitOcrEngine
import com.guiamed.ocr.PreviewLightingMetrics
import com.guiamed.ocr.readPrescription
import com.guiamed.ocr.ReadBudget
import com.guiamed.ocr.ScanLightingPolicy
import com.guiamed.speech.SpeechRecognitionManager
import com.guiamed.speech.TtsManager
import com.guiamed.speech.AllergyRegistrationInputType
import com.guiamed.speech.AllergyRegistrationParser
import com.guiamed.speech.ParsedVoiceCommand
import com.guiamed.speech.VoiceBargeInDetector
import com.guiamed.speech.VoiceCommandParser
import com.guiamed.speech.VoiceCommandType
import com.guiamed.ui.ReadingPanel
import com.guiamed.ui.ReadingPresentation
import com.guiamed.ui.toReadingPresentation
import com.guiamed.utils.PermissionAccessibilityHelper
import com.guiamed.utils.TouchDetector
import com.guiamed.utils.VibrationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : AppCompatActivity(), PermissionAccessibilityHelper.PermissionRequester {

    private lateinit var previewView: PreviewView
    private lateinit var readingPanel: ReadingPanel
    private lateinit var rootLayout: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var ttsManager: TtsManager
    private lateinit var vibrationManager: VibrationManager
    private lateinit var ocrEngine: MlKitOcrEngine
    private lateinit var speechRecognitionManager: SpeechRecognitionManager
    private lateinit var voiceBargeInDetector: VoiceBargeInDetector
    private lateinit var repository: GuiaMedRepository
    private lateinit var touchDetector: TouchDetector
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    @Volatile private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var analysisUseCase: ImageAnalysis? = null
    private val lightingPolicy = ScanLightingPolicy()
    private val lightingLock = Any()
    @Volatile private var isScanLightingActive = false
    @Volatile private var isFlashOn = false
    @Volatile private var currentEvSteps = 0
    private var lightingEvalScheduled = false
    @Volatile private var lastLightingAdjustmentAtMs = 0L
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            val lux = event?.values?.firstOrNull() ?: return
            val shouldEvaluate = synchronized(lightingLock) {
                lightingPolicy.updateAmbientLux(lux)
                isScanLightingActive
            }
            if (shouldEvaluate) {
                evaluateAndApplyScanLighting()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private var isProcessing = false
        set(value) {
            field = value
            if (!value) stopScanLighting()
        }
    private var hasAnnouncedStartup = false
    private var startupAnnouncementScheduled = false
    private var pendingSharedIntent: Intent? = null
    private var permissionFlowActive = false
    @Volatile private var packageFrameSession = 0L
    private var nextPackageFrameSession = 0L
    private val packageFrames = MutableSharedFlow<Pair<Long, MlKitOcrEngine.PackageFrame>>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var packageScanJob: Job? = null
    private var identifiedPackageResponse: VerificarResponse? = null
    private var prescriptionCaptureJob: Job? = null
    private var allergyJob: Job? = null
    private var alarmJob: Job? = null
    private var voiceCommandJob: Job? = null
    private var voiceCommandResumeJob: Job? = null
    private var bargeInResumeJob: Job? = null
    private var standbyVoiceJob: Job? = null
    private var voiceMonitorSyncJob: Job? = null
    private var voicePreemptJob: Job? = null
    private var voiceCommandSessionId = 0L
    private var bargeInSessionId = 0L
    @Volatile private var customBargeInActive = false
    @Volatile private var criticalAlertProtectedUntilMs = 0L
    private var resumeVoiceCommandsAfterOperation = false
    private var voiceStandbyPausedByUser = false
    private var consecutiveStandbyMisses = 0
    private var accessibilityManagerRef: AccessibilityManager? = null
    private val touchExplorationListener = AccessibilityManager.TouchExplorationStateChangeListener {
        syncVoiceMonitor()
    }
    private var lastReading: ReadingPresentation? = null
    private var lastReadingIsDanger = false
    private var lastPrescriptionAlarms: List<PrescriptionAlarm> = emptyList()
    private val cameraScope = CoroutineScope(Dispatchers.IO)
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private lateinit var permissionHelper: PermissionAccessibilityHelper
    private var pendingPermissions = mutableListOf<String>()
    private var permissionResults = mutableMapOf<String, Boolean>()
    private val pendingPermissionCallbacks = mutableMapOf<String, (Boolean) -> Unit>()

    // Permission Request Launchers
    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingPermissionCallbacks[Manifest.permission.CAMERA]?.invoke(granted)
        pendingPermissionCallbacks.remove(Manifest.permission.CAMERA)
    }
    private val requestAudioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingPermissionCallbacks[Manifest.permission.RECORD_AUDIO]?.invoke(granted)
        pendingPermissionCallbacks.remove(Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize views with viewBinding-like pattern
        previewView = findViewById(R.id.previewView)
        readingPanel = ReadingPanel(findViewById(R.id.readingPanel), findViewById(R.id.panelCameraDivider))
        rootLayout = findViewById(R.id.rootLayout)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(rootLayout)

        val framingGuide = findViewById<View>(R.id.framingGuide)
        val cameraGuideLabel = findViewById<View>(R.id.cameraGuideLabel)
        previewView.previewStreamState.observe(this) { state ->
            val visibility = if (state == PreviewView.StreamState.STREAMING) View.VISIBLE else View.INVISIBLE
            framingGuide.visibility = visibility
            cameraGuideLabel.visibility = visibility
        }
        
        // Inicializa componentes
        initializeComponents()
        
        // Verifica permissões
        checkAndRequestPermissions()
        
        // Configura touch detector
        setupTouchDetector()
        
        // Trata conteúdo compartilhado (WhatsApp, etc)
        handleIntent(intent)
    }

    private fun initializeComponents() {
        ttsManager = TtsManager(this)
        vibrationManager = VibrationManager(this)
        ocrEngine = MlKitOcrEngine()
        speechRecognitionManager = SpeechRecognitionManager(this)
        voiceBargeInDetector = VoiceBargeInDetector()
        repository = GuiaMedRepository()
        cameraExecutor = Executors.newSingleThreadExecutor()
        permissionHelper = PermissionAccessibilityHelper(this, ttsManager)
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        accessibilityManagerRef = getSystemService(AccessibilityManager::class.java)
        ttsManager.onSpeakingChanged = { speaking ->
            schedulePriorityVoiceSync(delayMs = if (speaking) 0L else 120L)
        }
        speechRecognitionManager.onSessionEnded = {
            val backoff = when (consecutiveStandbyMisses) {
                0 -> 150L
                1 -> 300L
                else -> 600L
            }
            schedulePriorityVoiceSync(delayMs = backoff)
        }
        
        // Inicializa OCR em background
        cameraScope.launch {
            ocrEngine.initialize()
        }
    }

    private fun checkAndRequestPermissions() {
        pendingPermissions.clear()
        permissionResults.clear()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (pendingPermissions.isNotEmpty()) {
            permissionFlowActive = true
            isProcessing = true
            // Inicia fluxo acessível: anuncia cada permissão por voz antes de pedir
            requestNextPermissionWithVoiceGuidance()
        } else {
            startCamera()
            announceStartupIfNeeded()
        }
    }

    private fun requestNextPermissionWithVoiceGuidance() {
        if (pendingPermissions.isEmpty()) {
            // Todas as permissões processadas, verifica resultados
            val allGranted = permissionResults.values.all { it }
            val cameraGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (cameraGranted) startCamera()

            if (allGranted) {
                updateStatus("Permissões concedidas", isDanger = false, detail = "Iniciando câmera.")
                vibrationManager.vibrateShort()
                ttsManager.speak("Todas as permissões concedidas. Iniciando câmera.") {
                    continueAfterPermissions()
                }
            } else {
                val denied = permissionResults.filter { !it.value }.keys.joinToString(", ") {
                    when (it) {
                        Manifest.permission.CAMERA -> "câmera"
                        Manifest.permission.RECORD_AUDIO -> "microfone"
                        else -> it
                    }
                }
                val message = "Permissões negadas: $denied. O app terá funcionalidades limitadas."
                updateStatus("Permissões negadas", isDanger = true, detail = "Acesso limitado: $denied.")
                vibrationManager.vibrateTripleLong()
                ttsManager.speak(message) { continueAfterPermissions() }
            }
            return
        }

        val permission = pendingPermissions.removeAt(0)
        val rationale = when (permission) {
            Manifest.permission.CAMERA -> getString(R.string.camera_permission_rationale)
            Manifest.permission.RECORD_AUDIO -> getString(R.string.audio_permission_rationale)
            else -> "Permissão necessária para o funcionamento do aplicativo."
        }

        val permissionTitle = if (permission == Manifest.permission.CAMERA) "Permissão de câmera"
        else "Permissão de microfone"
        updateStatus(permissionTitle, isDanger = false, detail = rationale)
        vibrationManager.vibrateShort()
        permissionHelper.requestPermissionWithVoiceGuidance(permission, rationale) { granted ->
            permissionResults[permission] = granted
            // Processa próxima permissão após um pequeno delay
            mainScope.launch {
                kotlinx.coroutines.delay(500)
                requestNextPermissionWithVoiceGuidance()
            }
        }
    }

    // Implementação da interface PermissionRequester
    override fun requestPermission(permission: String, onResult: (Boolean) -> Unit) {
        // Guarda o callback para o launcher que já está registrado
        pendingPermissionCallbacks[permission] = onResult

        when (permission) {
            Manifest.permission.CAMERA -> requestCameraPermission.launch(permission)
            Manifest.permission.RECORD_AUDIO -> requestAudioPermission.launch(permission)
            else -> onResult(false)
        }
    }

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture?.addListener({
            try {
                val cameraProvider = cameraProviderFuture?.get()
                bindCameraUseCases(cameraProvider!!)
            } catch (e: Exception) {
                cameraProviderFuture = null
                Log.e("GuiaMed", "Erro ao iniciar câmera", e)
                runOnUiThread {
                    handleProcessingError("Erro ao iniciar câmera")
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setTargetRotation(rotation)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(95)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(
                    Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ))
                .build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    try {
                        handlePreviewLightingSample(readPreviewLightingMetrics(proxy))
                        val session = packageFrameSession
                        if (session == 0L) {
                            proxy.close()
                        } else {
                            ocrEngine.analyzePackageFrame(proxy, cameraExecutor) { frame ->
                                if (packageFrameSession == session) packageFrames.tryEmit(session to frame)
                            }
                        }
                    } catch (_: Throwable) {
                        proxy.close()
                    }
                }
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            analysisUseCase = imageAnalysis
            val boundCamera = cameraProvider.bindToLifecycle(
                this as LifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis
            )
            camera = boundCamera
            boundCamera.cameraInfo.torchState.removeObservers(this)
            boundCamera.cameraInfo.torchState.observe(this) { state ->
                val torchActive = state == TorchState.ON
                val now = SystemClock.elapsedRealtime()
                synchronized(lightingLock) {
                    // Ignora estado atrasado logo após enableTorch(...) durante acomodação do AE.
                    if (now - lastLightingAdjustmentAtMs >= ScanLightingPolicy.AE_SETTLE_MILLIS) {
                        isFlashOn = torchActive
                        lightingPolicy.syncHardwareTorch(torchActive, now)
                    } else if (torchActive == isFlashOn) {
                        lightingPolicy.syncHardwareTorch(torchActive, now)
                    }
                }
            }
            if (isScanLightingActive) {
                startScanLighting()
            }
        } catch (e: Exception) {
            analysisUseCase = null
            cameraProviderFuture = null
            Log.e("GuiaMed", "Erro ao vincular casos de uso da câmera", e)
            handleProcessingError("Não foi possível preparar a câmera.")
        }
    }

    private fun setupTouchDetector() {
        touchDetector = TouchDetector(
            onSingleTap = { handleSingleTap() },
            onDoubleTap = { handleDoubleTap() },
            onTripleTap = { handleTripleTap() },
            onQuadTap = { handleQuadTap() },
            onLongPress = { openAppSettings() }
        )

        // Aplica o detector na PreviewView (tela inteira)
        previewView.setOnTouchListener { _, event ->
            touchDetector.onTouchEvent(event)
        }

        // Também no root layout para garantir cobertura total
        rootLayout.setOnTouchListener { _, event ->
            touchDetector.onTouchEvent(event)
        }
        readingPanel.content.setOnTouchListener { _, event ->
            touchDetector.onTouchEvent(event)
        }
        findViewById<View>(R.id.gesturePanel).setOnTouchListener { _, event ->
            touchDetector.onTouchEvent(event)
        }

        val status = findViewById<View>(R.id.statusText)
        val actions = listOf(
            R.string.accessibility_read_package to ::handleSingleTap,
            R.string.accessibility_read_prescription to ::handleDoubleTap,
            R.string.accessibility_register_allergy to ::handleTripleTap,
            R.string.accessibility_exit_or_help to ::handleQuadTap,
        )
        listOf(status, previewView).forEach { target ->
            actions.forEach { (label, action) ->
                ViewCompat.addAccessibilityAction(target, getString(label)) { _, _ ->
                    action()
                    true
                }
            }
            ViewCompat.replaceAccessibilityAction(
                target,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                getString(R.string.accessibility_open_settings),
            ) { _, _ ->
                openAppSettings()
                true
            }
        }
    }

    private fun repeatInstructions() {
        voiceStandbyPausedByUser = false
        consecutiveStandbyMisses = 0
        val instructions = getString(R.string.repeat_instructions)
        updateStatus("Instruções", isDanger = false, detail = instructions)
        vibrationManager.vibrateShort()
        speakFunctionsWithBargeIn(instructions) {
            startVoiceCommandSession(prompt = false)
        }
    }

    private fun handleQuadTap() {
        if (permissionFlowActive) {
            val message = "Conclua a solicitação de permissão do sistema."
            updateStatus("Permissão em andamento", isDanger = false, detail = message)
            vibrationManager.vibrateShort()
            ttsManager.speak(message)
            return
        }
        val hasActiveOperation = isProcessing ||
            packageScanJob?.isActive == true ||
            prescriptionCaptureJob?.isActive == true ||
            allergyJob?.isActive == true ||
            alarmJob?.isActive == true ||
            voiceCommandJob?.isActive == true ||
            voiceCommandResumeJob?.isActive == true ||
            bargeInResumeJob?.isActive == true ||
            ttsManager.isSpeakingNow()
        if (hasActiveOperation) cancelCurrentOperation() else repeatInstructions()
    }

    private fun cancelCurrentOperation() {
        voiceStandbyPausedByUser = false
        interruptCurrentOperation()
        val message = "Função encerrada."
        updateStatus("Função encerrada", isDanger = false, detail = message)
        vibrationManager.vibrateShort()
        if (!ttsManager.speak(message) { _ -> scheduleStandbyVoiceIfIdle() }) {
            scheduleStandbyVoiceIfIdle()
        }
    }

    private fun interruptCurrentOperation() {
        resumeVoiceCommandsAfterOperation = false
        customBargeInActive = false
        criticalAlertProtectedUntilMs = 0L
        voiceMonitorSyncJob?.cancel()
        voiceMonitorSyncJob = null
        voicePreemptJob?.cancel()
        voicePreemptJob = null
        standbyVoiceJob?.cancel()
        standbyVoiceJob = null
        bargeInSessionId += 1
        bargeInResumeJob?.cancel()
        bargeInResumeJob = null
        voiceBargeInDetector.stop()
        cancelVoiceCommandSession()
        resetPackageReadSession()
        resetPrescriptionReadSession()
        cancelAllergyRegistration()
        cancelAlarmCreation()
        stopScanLighting()
        ttsManager.stop()
        vibrationManager.cancel()
        isProcessing = false
    }

    private fun cancelAlarmCreation() {
        val pending = alarmJob
        alarmJob = null
        pending?.cancel()
    }

    private fun withExitInstruction(message: String): String =
        "$message ${getString(R.string.operation_exit_instruction)}"

    private fun openAppSettings() {
        resumeVoiceCommandsAfterOperation = false
        bargeInSessionId += 1
        bargeInResumeJob?.cancel()
        bargeInResumeJob = null
        voiceBargeInDetector.stop()
        cancelVoiceCommandSession()
        resetPackageReadSession()
        resetPrescriptionReadSession()
        cancelAllergyRegistration()
        cancelAlarmCreation()
        isProcessing = false
        updateStatus("Abrindo configurações", isDanger = false, detail = "Aqui você pode ativar as permissões.")
        vibrationManager.vibrateDoubleShort()
        ttsManager.speak("Abrindo configurações do aplicativo. Aqui você pode ativar as permissões necessárias.")
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun startScanLighting() {
        val activeCamera = camera
        if (activeCamera == null) {
            synchronized(lightingLock) {
                isScanLightingActive = true
                lightingEvalScheduled = false
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        val directive = synchronized(lightingLock) {
            isScanLightingActive = true
            lightingEvalScheduled = false
            val evRange = activeCamera.cameraInfo.exposureState.exposureCompensationRange
            lightingPolicy.startSession(
                hasFlash = activeCamera.cameraInfo.hasFlashUnit(),
                minEvSteps = evRange.lower,
                maxEvSteps = evRange.upper,
                nowMs = now,
            )
        }
        applyLightingDirective(activeCamera, directive)
    }

    private fun stopScanLighting() {
        val activeCamera = camera
        synchronized(lightingLock) {
            isScanLightingActive = false
            lightingEvalScheduled = false
            lightingPolicy.stopSession()
            if (activeCamera != null) {
                val hardwareTorchOn = activeCamera.cameraInfo.torchState.value == TorchState.ON
                if (isFlashOn || hardwareTorchOn) {
                    activeCamera.cameraControl.enableTorch(false)
                    lastLightingAdjustmentAtMs = SystemClock.elapsedRealtime()
                }
                if (currentEvSteps != 0) {
                    activeCamera.cameraControl.setExposureCompensationIndex(0)
                    currentEvSteps = 0
                }
            }
            isFlashOn = false
        }
    }

    private fun handlePreviewLightingSample(metrics: PreviewLightingMetrics) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val now = SystemClock.elapsedRealtime()
        val shouldScheduleMainEval = synchronized(lightingLock) {
            lightingPolicy.updatePreviewMetrics(metrics, now)
            if (isScanLightingActive && !lightingEvalScheduled) {
                lightingEvalScheduled = true
                true
            } else {
                false
            }
        }
        if (shouldScheduleMainEval) {
            mainScope.launch {
                synchronized(lightingLock) {
                    lightingEvalScheduled = false
                }
                if (isScanLightingActive && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    evaluateAndApplyScanLighting(applyHardware = true)
                }
            }
        }
    }

    private fun evaluateAndApplyScanLighting(applyHardware: Boolean = true): LightingDirective {
        val activeCamera = camera ?: return LightingDirective(targetTorchOn = false, targetEvSteps = 0)
        val evState = activeCamera.cameraInfo.exposureState
        val evRange = evState.exposureCompensationRange
        val evStepEv = evState.exposureCompensationStep.toFloat()
        val now = SystemClock.elapsedRealtime()
        var needsHardwareApply = false
        val directive = synchronized(lightingLock) {
            val evaluated = lightingPolicy.evaluate(
                hasFlash = activeCamera.cameraInfo.hasFlashUnit(),
                minEvSteps = evRange.lower,
                maxEvSteps = evRange.upper,
                nowMs = now,
                evStepEv = evStepEv,
            )
            needsHardwareApply = applyHardware &&
                isScanLightingActive &&
                (evaluated.targetTorchOn != isFlashOn || evaluated.targetEvSteps != currentEvSteps)
            evaluated
        }
        if (needsHardwareApply) {
            applyLightingDirective(activeCamera, directive)
        }
        return directive
    }

    private fun applyLightingDirective(activeCamera: Camera, directive: LightingDirective) {
        val evState = activeCamera.cameraInfo.exposureState
        val evRange = evState.exposureCompensationRange
        val now = SystemClock.elapsedRealtime()
        synchronized(lightingLock) {
            val torchChanged = activeCamera.cameraInfo.hasFlashUnit() && directive.targetTorchOn != isFlashOn
            if (torchChanged) {
                activeCamera.cameraControl.enableTorch(directive.targetTorchOn)
                isFlashOn = directive.targetTorchOn
                lastLightingAdjustmentAtMs = now
            }
            val clampedEv = directive.targetEvSteps.coerceIn(evRange.lower, evRange.upper)
            if (clampedEv != currentEvSteps && evRange.lower < evRange.upper) {
                activeCamera.cameraControl.setExposureCompensationIndex(clampedEv)
                currentEvSteps = clampedEv
                lastLightingAdjustmentAtMs = now
            }
        }
    }

    private suspend fun awaitLightingSettleIfChanged() {
        evaluateAndApplyScanLighting(applyHardware = true)
        val elapsedSinceAdjust = SystemClock.elapsedRealtime() - lastLightingAdjustmentAtMs
        val remainingSettle = ScanLightingPolicy.AE_SETTLE_MILLIS - elapsedSinceAdjust
        if (remainingSettle > 40L) {
            delay(remainingSettle.coerceAtMost(ScanLightingPolicy.AE_SETTLE_MILLIS))
        }
    }

    private fun lightingCueTexts(cue: LightingCue): Pair<Int, String>? = when (cue) {
        LightingCue.GLARE_HINT -> R.string.scan_glare_hint to "Muito reflexo · Incline a caixa"
        LightingCue.LOW_LIGHT_NO_FLASH -> R.string.scan_low_light to "Pouca luz"
        LightingCue.LOW_LIGHT_NEED_CLOSER -> R.string.scan_low_light_with_flash to "Pouca luz · Aproxime"
        LightingCue.NONE -> null
    }

    private fun handleSingleTap() {
        if (permissionFlowActive) return
        if (isProcessing && identifiedPackageResponse != null) {
            finishPackageWithoutValidity()
            return
        }
        voiceStandbyPausedByUser = false
        consecutiveStandbyMisses = 0
        interruptCurrentOperation()
        isProcessing = true
        resetPackageReadSession()
        startScanLighting()
        
        vibrationManager.vibrateShort()
        updateStatus("Lendo embalagem", isDanger = false)
        ttsManager.speak(getString(R.string.reading_now))
        packageScanJob = mainScope.launch { scanPackage() }
    }

    private fun handleDoubleTap() {
        if (permissionFlowActive) return
        voiceStandbyPausedByUser = false
        consecutiveStandbyMisses = 0
        interruptCurrentOperation()
        isProcessing = true
        resetPrescriptionReadSession()
        startScanLighting()
        
        vibrationManager.vibrateDoubleShort()
        updateStatus("Modo receita", isDanger = false)
        guideAndCapturePrescription(withExitInstruction(getString(R.string.prescription_mode)))
    }

    private fun guideAndCapturePrescription(message: String) {
        prescriptionCaptureJob = mainScope.launch {
            awaitScanInstruction(message)
            capturePrescriptionPhoto()
        }
    }

    private suspend fun awaitScanInstruction(message: String) {
        if (!awaitInstruction(message)) {
            if (isVoiceInterruptionActive()) {
                awaitVoiceInterruptionIdle()
                return
            }
            delay(1_500)
        }
        awaitVoiceInterruptionIdle()
    }

    private suspend fun awaitInstruction(message: String): Boolean {
        readingPanel.showDetails(message)
        // Tempo proporcional ao texto para não cortar instruções longas em pt-BR no meio da fala.
        val timeoutMillis = (message.length * 140L + 6_000L).coerceIn(15_000L, 45_000L)
        val spoken = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                continuation.invokeOnCancellation { ttsManager.stop() }
                ttsManager.speak(message) { success ->
                    if (continuation.isActive) continuation.resume(success)
                }
            }
        }
        if (spoken == null) ttsManager.stop()
        if (spoken != true) return false
        repeat(8) {
            if (!ttsManager.isSpeakingNow()) return true
            delay(50)
        }
        return !ttsManager.isSpeakingNow()
    }

    private suspend fun awaitScanFocus(
        maxWaitMillis: Long = 2_500L,
        targetPointNorm: Pair<Float, Float>? = null,
        settleMillis: Long = 220L,
    ) {
        val activeCamera = camera
        if (activeCamera == null || previewView.width == 0 || previewView.height == 0) {
            delay(350)
            return
        }

        val activeAnalysis = analysisUseCase
        val focusPoint = if (targetPointNorm != null && activeAnalysis != null) {
            val normX = targetPointNorm.first.coerceIn(0.15f, 0.85f)
            val normY = targetPointNorm.second.coerceIn(0.15f, 0.85f)
            SurfaceOrientedMeteringPointFactory(1f, 1f, activeAnalysis).createPoint(normX, normY)
        } else {
            previewView.meteringPointFactory.createPoint(
                previewView.width * 0.5f,
                previewView.height * 0.5f,
            )
        }
        val action = FocusMeteringAction.Builder(
            focusPoint,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
            .setAutoCancelDuration(6, TimeUnit.SECONDS)
            .build()

        val focusFuture = runCatching { activeCamera.cameraControl.startFocusAndMetering(action) }
            .getOrNull()
        if (focusFuture == null) {
            delay(350)
            return
        }
        withTimeoutOrNull(maxWaitMillis) {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { focusFuture.cancel(false) }
                focusFuture.addListener({
                    runCatching { focusFuture.get() }
                    if (continuation.isActive) continuation.resume(Unit)
                }, ContextCompat.getMainExecutor(this@MainActivity))
            }
        }
        // Aguarda acomodação da lente e firmeza da mão antes de disparar a captura.
        if (settleMillis > 0L) delay(settleMillis)
    }

    private fun handleTripleTap() {
        if (permissionFlowActive) return
        voiceStandbyPausedByUser = false
        consecutiveStandbyMisses = 0
        interruptCurrentOperation()
        isProcessing = true
        
        vibrationManager.vibrateDoubleShort()
        updateStatus("Cadastro de alergia", isDanger = false)
        startAllergyVoiceRegistration()
    }

    private inner class PackageGuidanceController {
        private var lastKey: String? = null
        private var consecutiveCount = 0
        private var alignedStreak = 0
        private var lastSpokenKey: String? = null
        private var lastSpokenAt = 0L
        private var alignedSignaled = false

        fun reset() {
            lastKey = null
            consecutiveCount = 0
            alignedStreak = 0
            alignedSignaled = false
        }

        fun recentlySpoke(withinMillis: Long): Boolean =
            lastSpokenAt > 0L && (SystemClock.elapsedRealtime() - lastSpokenAt) < withinMillis

        fun updateFramingCue(
            key: String?,
            spokenText: String?,
            panelStatus: String?,
            alignedWithStats: Boolean,
            flipVibration: Boolean = false,
        ) {
            if (alignedWithStats) {
                alignedStreak++
                if (!alignedSignaled && alignedStreak >= 2) {
                    alignedSignaled = true
                    vibrationManager.vibrateFocusTick()
                }
            } else {
                alignedStreak = 0
            }
            if (key == null || spokenText == null) {
                lastKey = null
                consecutiveCount = 0
                return
            }
            if (key == lastKey) {
                consecutiveCount++
            } else {
                lastKey = key
                consecutiveCount = 1
            }
            if (consecutiveCount < 3) return
            val now = SystemClock.elapsedRealtime()
            val minCooldown = 3_200L
            val sameKeyCooldown = 7_000L
            if (now - lastSpokenAt < minCooldown) return
            if (key == lastSpokenKey && now - lastSpokenAt < sameKeyCooldown) return
            if (ttsManager.isSpeakingNow() || speechRecognitionManager.isListening || voicePreemptJob?.isActive == true) return

            lastSpokenKey = key
            lastSpokenAt = now
            panelStatus?.let { updateStatus(it, isDanger = false) }
            if (flipVibration) vibrationManager.vibrateFlipBox() else vibrationManager.vibrateGuidance()
            ttsManager.speak(spokenText)
        }
    }

    private suspend fun scanPackage() {
        val startedAt = SystemClock.elapsedRealtime()
        val budget = ReadBudget(ReadBudget.PACKAGE_MILLIS)
        var best: VerificarResponse? = null
        var focusTarget: Pair<Float, Float>? = null
        val guidance = PackageGuidanceController()
        try {
            val completed = budget.run(budget.remainingMillis) {
                val liveResult = readLivePackage(
                    guidance = guidance,
                    onPartial = { partialLive ->
                        if (shouldPreferPackageResult(partialLive, best)) best = partialLive
                    },
                    onFocusTarget = { focusTarget = it },
                )
                if (liveResult != null) {
                    best = liveResult
                    Log.d("GuiaMedScan", "Embalagem identificada por quadro em ${SystemClock.elapsedRealtime() - startedAt} ms")
                    finishPackageIdentification(liveResult, budget)
                    return@run true
                }
                Log.d("GuiaMedScan", "Leitura de embalagem por foto")
                awaitVoiceInterruptionIdle()
                updateStatus(getString(R.string.reading_package), isDanger = false)
                if (!ttsManager.isSpeakingNow() && !isVoiceInterruptionActive() && !guidance.recentlySpoke(2_500L)) {
                    ttsManager.speak(getString(R.string.scan_hold_steady))
                }
                val result = captureWithFocusTicks(budget) {
                    awaitVoiceInterruptionIdle()
                    awaitLightingSettleIfChanged()
                    budget.run(1_800) { awaitScanFocus(maxWaitMillis = 1_600L, targetPointNorm = focusTarget, settleMillis = 280L) }
                    readPackageFrame(budget) { bitmap ->
                        identifyPackageFromBitmap(bitmap, budget) { candidate ->
                            if (shouldPreferPackageResult(candidate, best)) best = candidate
                        }
                    }
                }
                val finalResult = if (shouldPreferPackageResult(result, best)) result else best
                if (finalResult != null) best = finalResult
                if (isIdentifiedMedication(finalResult) || finalResult?.perigo == true) {
                    Log.d("GuiaMedScan", "Resultado de embalagem por foto em ${SystemClock.elapsedRealtime() - startedAt} ms")
                    finishPackageIdentification(requireNotNull(finalResult), budget)
                    return@run true
                }
                false
            }
            if (completed != true) {
                val partial = best
                if (partial != null) handleVerificarSuccess(partial)
                else handleProcessingError("Não consegui identificar o medicamento com segurança. Reposicione a caixa e toque uma vez.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("GuiaMed", "Falha ao ler embalagem", e)
            handleProcessingError(getString(R.string.ocr_error))
        }
    }

    private suspend fun readLivePackage(
        guidance: PackageGuidanceController = PackageGuidanceController(),
        onPartial: (VerificarResponse) -> Unit = {},
        onFocusTarget: (Pair<Float, Float>) -> Unit = {},
    ): VerificarResponse? = coroutineScope {
        val session = ++nextPackageFrameSession
        val focus = launch { awaitScanFocus(maxWaitMillis = 1_200L, settleMillis = 150L) }
        packageFrameSession = session
        try {
            withTimeoutOrNull(3_600) {
                packageFrames.mapNotNull { (frameSession, frame) ->
                    if (frameSession != session) return@mapNotNull null
                    frame.visionStats?.let { stats ->
                        if (stats.hasAnyText) onFocusTarget(stats.centerXRatio to stats.centerYRatio)
                    }
                    val verified = if (frame.text.isNotBlank() || frame.ean != null) {
                        repository.verificarLocal(this@MainActivity, frame.text, frame.ean, allowFuzzy = false)
                    } else null
                    val identified = verified != null && isIdentifiedMedication(verified)
                    if (verified != null && (verified.perigo || (identified && verified.miligramas != null))) {
                        return@mapNotNull verified
                    }
                    if (verified != null && identified) onPartial(verified)

                    val decision = withContext(Dispatchers.Default) {
                        PackageVisionGuidance.classify(
                            text = frame.text,
                            ean = frame.ean,
                            identifiedFrontMedication = identified && frame.ean == null,
                            stats = frame.visionStats,
                        )
                    }
                    val lightingDirective = evaluateAndApplyScanLighting(applyHardware = true)
                    when {
                        decision.framing == FramingState.NO_TEXT -> {
                            val cueTexts = lightingCueTexts(lightingDirective.cue)
                            if (cueTexts != null) {
                                guidance.updateFramingCue(
                                    key = "lighting_${lightingDirective.cue.name}",
                                    spokenText = getString(cueTexts.first),
                                    panelStatus = cueTexts.second,
                                    alignedWithStats = false,
                                )
                            } else {
                                guidance.updateFramingCue(
                                    key = "no_text",
                                    spokenText = getString(R.string.scan_no_text),
                                    panelStatus = "Aponte para a caixa",
                                    alignedWithStats = false,
                                )
                            }
                        }
                        decision.face == PackageFace.BACK_OR_SIDE -> guidance.updateFramingCue(
                            key = "seems_back",
                            spokenText = getString(R.string.scan_seems_back),
                            panelStatus = "Parece o verso · Gire a caixa",
                            alignedWithStats = false,
                            flipVibration = true,
                        )
                        decision.framing == FramingState.TOO_CLOSE -> guidance.updateFramingCue(
                            key = "too_close",
                            spokenText = getString(R.string.scan_too_close),
                            panelStatus = "Muito perto · Afaste um pouco",
                            alignedWithStats = false,
                        )
                        decision.framing == FramingState.OFF_CENTER -> guidance.updateFramingCue(
                            key = "off_center",
                            spokenText = getString(R.string.scan_center_box),
                            panelStatus = "Centralize a caixa",
                            alignedWithStats = false,
                        )
                        decision.framing == FramingState.TOO_FAR -> guidance.updateFramingCue(
                            key = "too_far",
                            spokenText = getString(R.string.scan_too_far),
                            panelStatus = "Aproxime um pouco",
                            alignedWithStats = false,
                        )
                        else -> {
                            if (lightingDirective.cue == LightingCue.GLARE_HINT) {
                                guidance.updateFramingCue(
                                    key = "lighting_glare",
                                    spokenText = getString(R.string.scan_glare_hint),
                                    panelStatus = "Muito reflexo · Incline a caixa",
                                    alignedWithStats = frame.visionStats != null,
                                )
                            } else {
                                guidance.updateFramingCue(
                                    key = null,
                                    spokenText = null,
                                    panelStatus = null,
                                    alignedWithStats = frame.visionStats != null,
                                )
                            }
                        }
                    }
                    null
                }.first()
            }
        } finally {
            if (packageFrameSession == session) packageFrameSession = 0
            focus.cancel()
        }
    }

    private suspend fun identifyPackageFromBitmap(
        bitmap: Bitmap,
        budget: ReadBudget,
        publish: (VerificarResponse) -> Unit,
    ): VerificarResponse? {
        val focusedText = budget.run(3_500, repository.remoteReserveMillis) {
            ocrEngine.recognizeFocusedPackageText(bitmap)
        }.orEmpty()
        val focusedResult = focusedText.takeIf(String::isNotBlank)?.let {
            repository.verificarLocal(this, it)
        }
        focusedResult?.let(publish)
        if (focusedResult?.perigo == true ||
            (isIdentifiedMedication(focusedResult) && focusedResult?.miligramas != null)
        ) {
            return focusedResult
        }

        val fullText = budget.run(4_500, repository.remoteReserveMillis) {
            ocrEngine.recognizeFullPackageText(bitmap)
        }.orEmpty()
        val mergedText = mergeDistinctOcrLines(focusedText, fullText)
        val mergedResult = mergedText.takeIf(String::isNotBlank)
            ?.let { repository.verificarMedicamento(this, it, budget).getOrThrow() }
        if (shouldPreferPackageResult(mergedResult, focusedResult)) {
            mergedResult?.let(publish)
            if (mergedResult?.perigo == true ||
                (isIdentifiedMedication(mergedResult) && mergedResult?.miligramas != null)
            ) {
                return mergedResult
            }
        }

        val fullResult = fullText.takeIf { it.isNotBlank() && it != mergedText }
            ?.let { repository.verificarMedicamento(this, it, budget).getOrThrow() }
        val candidates = listOfNotNull(mergedResult, fullResult, focusedResult)
        return candidates.maxWithOrNull(compareBy<VerificarResponse>(
            { it.perigo },
            { isIdentifiedMedication(it) },
            { it.miligramas != null },
            { it.validade != null },
        ))
    }

    private fun mergeDistinctOcrLines(first: String, second: String): String {
        val unique = linkedMapOf<String, String>()
        sequenceOf(first, second)
            .flatMap { it.lineSequence() }
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { line -> unique.putIfAbsent(line.uppercase(), line) }
        return unique.values.joinToString("\n")
    }

    private fun shouldPreferPackageResult(
        candidate: VerificarResponse?,
        currentBest: VerificarResponse?,
    ): Boolean {
        if (candidate == null) return false
        if (currentBest == null) return true
        val candidateIdentified = isIdentifiedMedication(candidate)
        val currentIdentified = isIdentifiedMedication(currentBest)
        if (candidateIdentified != currentIdentified) return candidateIdentified
        if ((candidate.miligramas != null) != (currentBest.miligramas != null)) {
            return candidate.miligramas != null
        }
        if (candidate.perigo != currentBest.perigo) return candidate.perigo
        return candidate.validade != null && currentBest.validade == null
    }

    private fun isIdentifiedMedication(response: VerificarResponse?): Boolean =
        response != null && !response.nome.startsWith("Medicamento não identificado")

    private suspend fun finishPackageIdentification(response: VerificarResponse, budget: ReadBudget) {
        if (response.validade != null || response.perigo || response.alertaAlergia) {
            handleVerificarSuccess(response)
        } else {
            scanValidity(response, budget)
        }
    }

    private suspend fun scanValidity(response: VerificarResponse, budget: ReadBudget) {
        if (budget.remainingMillis < 6_500) {
            handleVerificarSuccess(response)
            return
        }
        identifiedPackageResponse = response
        updateStatus("${response.nome}\nVire para ler a validade", isDanger = false)
        vibrationManager.vibrateDoubleShort()
        awaitScanInstruction(getString(R.string.validity_turn_box, response.nome))

        val guidance = PackageGuidanceController()
        var targetPoint: Pair<Float, Float>? = null

        val liveExpiration = readLiveValidity(
            frontMedicationName = response.nome,
            maxWaitMillis = minOf(4_800L, (budget.remainingMillis - 5_500L).coerceAtLeast(1_500L)),
            guidance = guidance,
        ) { targetPoint = it }
        if (liveExpiration != null) {
            handleVerificarSuccess(response.withReadExpiration(liveExpiration))
            return
        }

        // Tentativa 1 em alta resolução com foco direcionado e acomodação da câmera.
        val firstPhotoExpiration = captureValidityAttempt(
            budget = budget,
            targetPoint = targetPoint,
            announceFocus = true,
        )
        if (firstPhotoExpiration != null) {
            handleVerificarSuccess(response.withReadExpiration(firstPhotoExpiration))
            return
        }

        // Espera adicional se o usuário ainda estiver girando ou a lente não tiver focado.
        if (budget.remainingMillis >= 7_000L) {
            updateStatus("Tente outra lateral para validade", isDanger = false)
            vibrationManager.vibrateFlipBox()
            awaitScanInstruction(getString(R.string.validity_try_other_side))
            guidance.reset()
            val secondLiveExpiration = readLiveValidity(
                frontMedicationName = response.nome,
                maxWaitMillis = minOf(3_600L, (budget.remainingMillis - 4_500L).coerceAtLeast(1_200L)),
                guidance = guidance,
            ) { targetPoint = it }
            if (secondLiveExpiration != null) {
                handleVerificarSuccess(response.withReadExpiration(secondLiveExpiration))
                return
            }

            if (budget.remainingMillis >= 4_500L) {
                val secondPhotoExpiration = captureValidityAttempt(
                    budget = budget,
                    targetPoint = targetPoint,
                    announceFocus = false,
                    isRetryAttempt = true,
                )
                if (secondPhotoExpiration != null) {
                    handleVerificarSuccess(response.withReadExpiration(secondPhotoExpiration))
                    return
                }
            }
        }

        handleVerificarSuccess(response)
    }

    private suspend fun readLiveValidity(
        frontMedicationName: String,
        maxWaitMillis: Long,
        guidance: PackageGuidanceController,
        onFocusTarget: (Pair<Float, Float>) -> Unit,
    ): ExpirationDate? = coroutineScope {
        val session = ++nextPackageFrameSession
        packageFrameSession = session
        var readyStreak = 0
        var lastLiveExpiration: ExpirationDate? = null
        var expirationStreak = 0
        var stopForHighRes = false
        try {
            withTimeoutOrNull(maxWaitMillis) {
                packageFrames.mapNotNull { (frameSession, frame) ->
                    if (frameSession != session) return@mapNotNull null
                    val liveExpiration = withContext(Dispatchers.Default) {
                        ExpirationReader.findDates(frame.text).singleOrNull()
                    }
                    if (liveExpiration != null) {
                        if (liveExpiration == lastLiveExpiration) {
                            expirationStreak++
                        } else {
                            lastLiveExpiration = liveExpiration
                            expirationStreak = 1
                        }
                        if (expirationStreak >= 2) {
                            return@mapNotNull liveExpiration
                        }
                    } else {
                        lastLiveExpiration = null
                        expirationStreak = 0
                    }
                    frame.visionStats?.let { stats ->
                        if (stats.hasAnyText) onFocusTarget(stats.centerXRatio to stats.centerYRatio)
                    }
                    val verified = frame.text.takeIf(String::isNotBlank)?.let {
                        repository.verificarLocal(this@MainActivity, it, frame.ean, allowFuzzy = false)
                    }
                    val (stillSameFront, decision) = withContext(Dispatchers.Default) {
                        val preliminary = PackageVisionGuidance.classify(
                            text = frame.text,
                            ean = frame.ean,
                            identifiedFrontMedication = verified?.nome == frontMedicationName && frame.ean == null,
                            stats = frame.visionStats,
                        )
                        val sameFront = verified != null &&
                            verified.nome == frontMedicationName &&
                            frame.ean == null &&
                            !preliminary.hasBatchOrExpiryCue
                        sameFront to preliminary
                    }
                    val lightingDirective = evaluateAndApplyScanLighting(applyHardware = true)
                    when {
                        decision.framing == FramingState.NO_TEXT -> {
                            readyStreak = 0
                            val cueTexts = lightingCueTexts(lightingDirective.cue)
                            if (cueTexts != null) {
                                guidance.updateFramingCue(
                                    key = "val_lighting_${lightingDirective.cue.name}",
                                    spokenText = getString(cueTexts.first),
                                    panelStatus = cueTexts.second,
                                    alignedWithStats = false,
                                )
                            } else {
                                guidance.updateFramingCue(
                                    key = "val_no_text",
                                    spokenText = getString(R.string.validity_no_text),
                                    panelStatus = "Sem texto · Gire devagar",
                                    alignedWithStats = false,
                                )
                            }
                        }
                        stillSameFront -> {
                            readyStreak = 0
                            guidance.updateFramingCue(
                                key = "val_still_front",
                                spokenText = getString(R.string.validity_still_front),
                                panelStatus = "Ainda na frente · Vire a caixa",
                                alignedWithStats = false,
                                flipVibration = true,
                            )
                        }
                        decision.framing == FramingState.TOO_CLOSE -> {
                            readyStreak = 0
                            guidance.updateFramingCue(
                                key = "val_too_close",
                                spokenText = getString(R.string.scan_too_close),
                                panelStatus = "Muito perto · Afaste um pouco",
                                alignedWithStats = false,
                            )
                        }
                        decision.framing == FramingState.OFF_CENTER -> {
                            readyStreak = 0
                            guidance.updateFramingCue(
                                key = "val_off_center",
                                spokenText = getString(R.string.scan_center_box),
                                panelStatus = "Centralize a lateral",
                                alignedWithStats = false,
                            )
                        }
                        decision.framing == FramingState.TOO_FAR -> {
                            readyStreak = 0
                            guidance.updateFramingCue(
                                key = "val_too_far",
                                spokenText = getString(R.string.scan_too_far),
                                panelStatus = "Aproxime um pouco",
                                alignedWithStats = false,
                            )
                        }
                        else -> {
                            guidance.updateFramingCue(null, null, null, alignedWithStats = frame.visionStats != null)
                            if (decision.hasBatchOrExpiryCue || decision.face == PackageFace.BACK_OR_SIDE) {
                                readyStreak++
                                if (readyStreak >= 2) {
                                    stopForHighRes = true
                                    return@mapNotNull null
                                }
                            }
                        }
                    }
                    if (stopForHighRes) ExpirationDate(0, 1, null) else null
                }.first()
            }?.takeIf { it.year > 0 }
        } finally {
            if (packageFrameSession == session) packageFrameSession = 0
        }
    }

    private suspend fun captureValidityAttempt(
        budget: ReadBudget,
        targetPoint: Pair<Float, Float>?,
        announceFocus: Boolean,
        isRetryAttempt: Boolean = false,
    ): ExpirationDate? {
        if (budget.remainingMillis < 4_200L) return null
        awaitVoiceInterruptionIdle()
        updateStatus("Focando validade…", isDanger = false)
        if (announceFocus && !ttsManager.isSpeakingNow() && !isVoiceInterruptionActive()) {
            ttsManager.speak(getString(R.string.validity_focusing))
        }
        return captureWithFocusTicks(budget) {
            awaitVoiceInterruptionIdle()
            if (isRetryAttempt) {
                camera?.let { activeCamera ->
                    val evState = activeCamera.cameraInfo.exposureState
                    val evRange = evState.exposureCompensationRange
                    val evStepEv = evState.exposureCompensationStep.toFloat()
                    val retryDirective = synchronized(lightingLock) {
                        lightingPolicy.overrideForValidityRetry(
                            hasFlash = activeCamera.cameraInfo.hasFlashUnit(),
                            minEvSteps = evRange.lower,
                            maxEvSteps = evRange.upper,
                            nowMs = SystemClock.elapsedRealtime(),
                            evStepEv = evStepEv,
                        )
                    }
                    applyLightingDirective(activeCamera, retryDirective)
                }
            }
            awaitLightingSettleIfChanged()
            budget.run(2_400L) {
                awaitScanFocus(
                    maxWaitMillis = 2_000L,
                    targetPointNorm = targetPoint,
                    settleMillis = 450L,
                )
            }
            val dates = readPackageFrame(budget) { bitmap ->
                val text = budget.run(4_500L) { ocrEngine.recognizeValidityText(bitmap) }.orEmpty()
                ExpirationReader.findDates(text)
            }.orEmpty()
            dates.singleOrNull()
        }
    }

    private suspend fun <T> captureWithFocusTicks(
        budget: ReadBudget,
        block: suspend () -> T,
    ): T = coroutineScope {
        val tickJob = launch {
            delay(600L)
            repeat(3) {
                if (budget.remainingMillis < 1_000L) return@launch
                vibrationManager.vibrateFocusTick()
                delay(1_200L)
            }
        }
        try {
            block()
        } finally {
            tickJob.cancel()
        }
    }

    private suspend fun <T> readPackageFrame(
        budget: ReadBudget,
        read: suspend (Bitmap) -> T,
    ): T? {
        val capture = imageCapture ?: error("A câmera ainda não está pronta")
        val file = File(cacheDir, "embalagem-${java.util.UUID.randomUUID()}.jpg")
        try {
            return budget.run(budget.remainingMillis) {
                budget.run(4_000) { saveScanPhoto(capture, file) } ?: return@run null
                withContext(Dispatchers.IO) {
                    val bitmap = decodeBitmapRespectingExif(file) ?: return@withContext null
                    try {
                        read(bitmap)
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }
        } catch (e: ImageCaptureException) {
            Log.w("GuiaMed", "Falha na captura da embalagem", e)
            return null
        } finally {
            file.delete()
        }
    }

    private fun finishPackageWithoutValidity() {
        val response = identifiedPackageResponse ?: return
        val cleanedMessage = response.mensagem
            ?.replace("Validade não encontrada na imagem.", "")
            ?.trim()
            ?.ifBlank { null }
        handleVerificarSuccess(response.copy(mensagem = cleanedMessage), showMissingValidity = false)
    }

    private fun resetPackageReadSession() {
        packageScanJob?.cancel()
        packageScanJob = null
        identifiedPackageResponse = null
    }

    private fun decodeBitmapRespectingExif(file: File): Bitmap? {
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return source

        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            .also { rotated ->
                if (rotated !== source) source.recycle()
            }
    }

    private fun handleVerificarSuccess(response: VerificarResponse, showMissingValidity: Boolean = true) {
        isProcessing = false
        resetPackageReadSession()
        val result = response.toReadingPresentation(showMissingValidity)
        lastReading = result
        lastReadingIsDanger = response.alertaAlergia || response.vencido || response.perigo
        lastPrescriptionAlarms = emptyList()
        readingPanel.show(result)
        when {
            response.alertaAlergia || response.vencido -> vibrationManager.vibrateAlert()
            response.perigo -> vibrationManager.vibrateTripleLong()
            else -> vibrationManager.vibrateShort()
        }
        val voiceMode = resumeVoiceCommandsAfterOperation
        criticalAlertProtectedUntilMs = if (lastReadingIsDanger) SystemClock.elapsedRealtime() + 1_500L else 0L
        val spokenOutput = "${result.speech} ${buildPostReadSuffix(isPrescription = false, hasAlarms = false, voiceMode = voiceMode)}"
        ttsManager.speak(spokenOutput, TtsManager.UTTERANCE_ID_READING) { success ->
            returnToVoiceCommandsAfterSpeech(success, prompt = !voiceMode)
        }
    }

    private fun handleProcessingError(message: String) {
        isProcessing = false
        resetPackageReadSession()
        resetPrescriptionReadSession()
        updateStatus("Não foi possível concluir", isDanger = true, detail = message)
        ttsManager.speak(message) { success -> returnToVoiceCommandsAfterSpeech(success) }
        vibrationManager.vibrateTripleLong()
    }

    private fun capturePrescriptionPhoto() {
        val imageCapture = this.imageCapture ?: run {
            handleProcessingError("A câmera ainda não está pronta. Tente novamente em alguns segundos.")
            return
        }
        prescriptionCaptureJob = mainScope.launch {
            val file = File(cacheDir, "receita-${java.util.UUID.randomUUID()}.jpg")
            val budget = ReadBudget(ReadBudget.PRESCRIPTION_MILLIS)
            try {
                val startedAt = SystemClock.elapsedRealtime()
                awaitVoiceInterruptionIdle()
                awaitLightingSettleIfChanged()
                budget.run(1_400) { awaitScanFocus(maxWaitMillis = 1_200) }
                awaitVoiceInterruptionIdle()
                updateStatus("Capturando receita...", isDanger = false)
                vibrationManager.vibrateDoubleShort()
                if (!isVoiceInterruptionActive()) {
                    ttsManager.speak(getString(R.string.reading_now))
                }
                if (budget.run(4_000) { saveScanPhoto(imageCapture, file) } == null) {
                    handleProcessingError("Não foi possível capturar a receita. Enquadre a folha e dê dois toques.")
                    return@launch
                }
                updateStatus("Lendo texto da receita…", isDanger = false)
                val response = withContext(Dispatchers.IO) { readPrescriptionPhoto(file, budget) }
                Log.d("GuiaMedScan", "Receita processada em ${SystemClock.elapsedRealtime() - startedAt} ms")
                if (response != null && response.medicamentos.isNotEmpty()) {
                    handlePrescriptionSuccess(response)
                } else {
                    handleProcessingError(response?.mensagem ?: getString(R.string.no_text_found))
                }
            } catch (_: TimeoutCancellationException) {
                handleProcessingError("A leitura da receita demorou demais. Enquadre a folha e tente novamente.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GuiaMed", "Falha na captura ou leitura da receita", e)
                handleProcessingError(getString(R.string.ocr_error))
            } finally {
                file.delete()
            }
        }
    }

    private suspend fun saveScanPhoto(capture: ImageCapture, file: File) {
        suspendCancellableCoroutine<Unit> { continuation ->
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (continuation.isActive) continuation.resume(Unit) else file.delete()
                    }

                    override fun onError(exc: ImageCaptureException) {
                        if (continuation.isActive) continuation.resumeWithException(exc) else file.delete()
                    }
                },
            )
        }
    }

    private fun processSharedContent(data: Intent) {
        @Suppress("DEPRECATION")
        val uri = data.data
            ?: data.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: data.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
            ?: data.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri == null) {
            handleProcessingError("Não foi possível acessar o arquivo compartilhado.")
            return
        }
        isProcessing = true
        vibrationManager.vibrateDoubleShort()
        updateStatus("Lendo receita compartilhada", isDanger = false)
        ttsManager.speak("Lendo receita compartilhada.")
        val mimeType = contentResolver.getType(uri) ?: ""
        
        if (mimeType.startsWith("image/")) {
            processImageUri(uri)
        } else if (mimeType == "application/pdf") {
            processPdfUri(uri)
        } else {
            handleProcessingError("Formato de arquivo não suportado.")
        }
    }

    private fun processImageUri(uri: Uri) {
        prescriptionCaptureJob = mainScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    try {
                        readPrescriptionBitmap(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                if (response == null) handleProcessingError(getString(R.string.no_text_found))
                else handlePrescriptionSuccess(response)
            } catch (e: TimeoutCancellationException) {
                handleProcessingError("A leitura da receita demorou demais. Tente outra foto.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleProcessingError(getString(R.string.ocr_error))
            }
        }
    }

    private fun processPdfUri(uri: Uri) {
        isProcessing = false
        val intent = Intent(this, PrescriptionReaderActivity::class.java).apply {
            putExtra("pdf_uri", uri.toString())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private suspend fun readPrescriptionPhoto(file: File, budget: ReadBudget): ReceitaResponse? {
        val bitmap = decodeBitmapRespectingExif(file) ?: error("Imagem inválida")
        try {
            return readPrescriptionBitmap(bitmap, budget)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun readPrescriptionBitmap(
        bitmap: Bitmap,
        budget: ReadBudget = ReadBudget(ReadBudget.PRESCRIPTION_MILLIS),
    ): ReceitaResponse? = ocrEngine.readPrescription(this, repository, bitmap, budget)

    private fun resetPrescriptionReadSession() {
        prescriptionCaptureJob?.cancel()
        prescriptionCaptureJob = null
    }

    private fun handlePrescriptionSuccess(response: ReceitaResponse) {
        isProcessing = false
        resetPrescriptionReadSession()
        if (response.medicamentos.isEmpty()) {
            handleProcessingError(response.mensagem ?: "Nenhum medicamento foi identificado na receita.")
            return
        }
        val result = response.toReadingPresentation()
        lastReading = result
        lastReadingIsDanger = result.alert != null
        lastPrescriptionAlarms = PrescriptionScheduleParser.fromPrescription(response)
        readingPanel.show(result)
        val voiceMode = resumeVoiceCommandsAfterOperation
        criticalAlertProtectedUntilMs = if (lastReadingIsDanger) SystemClock.elapsedRealtime() + 1_500L else 0L
        val suggestAlarms = lastPrescriptionAlarms.isNotEmpty() && !lastReadingIsDanger
        val spokenOutput = "${result.speech} ${buildPostReadSuffix(isPrescription = true, hasAlarms = suggestAlarms, voiceMode = voiceMode)}"
        ttsManager.speak(spokenOutput, TtsManager.UTTERANCE_ID_READING) { success ->
            returnToVoiceCommandsAfterSpeech(success, prompt = !voiceMode)
        }
        if (result.alert != null) vibrationManager.vibrateAlert()
        else vibrationManager.vibrateDoubleShort()
    }

    private fun buildPostReadSuffix(
        isPrescription: Boolean,
        hasAlarms: Boolean,
        voiceMode: Boolean,
    ): String {
        if (isTalkBackTouchExplorationActive()) {
            return getString(R.string.post_read_talkback_prompt)
        }
        return when {
            !isPrescription && voiceMode -> getString(R.string.post_read_package_voice_prompt)
            !isPrescription -> getString(R.string.post_read_package_prompt)
            hasAlarms && voiceMode -> getString(R.string.post_read_prescription_with_alarms_voice_prompt)
            hasAlarms -> getString(R.string.post_read_prescription_with_alarms_prompt)
            voiceMode -> getString(R.string.post_read_prescription_voice_prompt)
            else -> getString(R.string.post_read_prescription_prompt)
        }
    }

    private enum class AllergyConfirmOutcome {
        CONFIRMED,
        CANCELLED,
        RETRY_WITH_CAMERA,
        GLOBAL_SWITCHED,
    }

    private fun startAllergyVoiceRegistration(initialAllergyName: String? = null) {
        allergyJob = mainScope.launch {
            val owner = currentCoroutineContext()[Job]
            var nextStepPrompt: String? = null
            try {
                var resolvedName: String? = null
                var pendingInitial = initialAllergyName

                for (attempt in 1..3) {
                    val candidateText = pendingInitial.also { pendingInitial = null } ?: run {
                        val prompt = nextStepPrompt ?: withExitInstruction(getString(R.string.allergy_mode))
                        if (!awaitInterruptibleFunctionsPrompt(prompt)) {
                            handleProcessingError("Não foi possível preparar o cadastro por voz.")
                            return@launch
                        }
                        listenForAllergyStep(noMatchMessage = "Não entendi o nome da alergia") {
                            updateStatus("Pode falar agora", isDanger = false, detail = prompt)
                            vibrationManager.vibrateDoubleShort()
                        }
                    }

                    if (candidateText.isNullOrBlank()) {
                        if (attempt < 3) {
                            nextStepPrompt = getString(R.string.allergy_retry_prompt)
                            continue
                        }
                        handleProcessingError("Não entendi o medicamento. Tente novamente com 3 toques ou diga cadastrar alergia.")
                        return@launch
                    }
                    val allergyInput = AllergyRegistrationParser.parse(candidateText)
                    if (allergyInput.type != AllergyRegistrationInputType.CANCEL &&
                        allergyInput.type != AllergyRegistrationInputType.CAMERA
                    ) {
                        val globalCommand = VoiceCommandParser.parse(candidateText, strictStandby = false)
                        if (globalCommand.type in setOf(
                                VoiceCommandType.READ_PACKAGE,
                                VoiceCommandType.READ_PRESCRIPTION,
                                VoiceCommandType.LIST_ALLERGIES,
                                VoiceCommandType.REMOVE_ALLERGY,
                                VoiceCommandType.REPEAT_LAST_READING,
                                VoiceCommandType.CREATE_REMINDER,
                                VoiceCommandType.REPEAT_INSTRUCTIONS,
                                VoiceCommandType.PAUSE_VOICE,
                            )
                        ) {
                            dispatchVoiceCommand(globalCommand, candidateText, retryOnUnknown = false)
                            return@launch
                        }
                    }

                    when (allergyInput.type) {
                        AllergyRegistrationInputType.CANCEL -> {
                            announceAllergyCancellation(owner)
                            return@launch
                        }
                        AllergyRegistrationInputType.CAMERA -> {
                            val scannedName = scanAllergyMedicationName()
                            if (scannedName.isNullOrBlank()) {
                                if (attempt < 3) {
                                    nextStepPrompt = getString(R.string.allergy_camera_not_found)
                                    continue
                                }
                                handleProcessingError("Não consegui identificar o medicamento pela câmera.")
                                return@launch
                            }
                            resolvedName = scannedName
                            break
                        }
                        AllergyRegistrationInputType.MEDICATION_NAME -> {
                            resolvedName = allergyInput.medicationName
                            break
                        }
                        else -> {
                            if (attempt < 3) {
                                nextStepPrompt = getString(R.string.allergy_retry_prompt)
                                continue
                            }
                            handleProcessingError("Não consegui identificar o medicamento para cadastrar a alergia.")
                            return@launch
                        }
                    }
                }

                val allergyName = resolvedName ?: run {
                    handleProcessingError("Não consegui identificar o medicamento para cadastrar a alergia.")
                    return@launch
                }

                when (confirmAllergyRegistration(allergyName, owner)) {
                    AllergyConfirmOutcome.CANCELLED -> {
                        announceAllergyCancellation(owner)
                        return@launch
                    }
                    AllergyConfirmOutcome.GLOBAL_SWITCHED -> return@launch
                    AllergyConfirmOutcome.RETRY_WITH_CAMERA -> {
                        val rescanned = scanAllergyMedicationName()
                        if (rescanned.isNullOrBlank()) {
                            handleProcessingError("Não consegui identificar o medicamento pela câmera.")
                            return@launch
                        }
                        when (confirmAllergyRegistration(rescanned, owner)) {
                            AllergyConfirmOutcome.CONFIRMED -> saveAllergyAfterConfirmation(rescanned)
                            AllergyConfirmOutcome.GLOBAL_SWITCHED -> return@launch
                            else -> {
                                announceAllergyCancellation(owner)
                                return@launch
                            }
                        }
                    }
                    AllergyConfirmOutcome.CONFIRMED -> {
                        saveAllergyAfterConfirmation(allergyName)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleProcessingError(e.message ?: getString(R.string.allergy_error))
            } finally {
                if (allergyJob == owner) {
                    allergyJob = null
                    speechRecognitionManager.stopListening()
                    isProcessing = false
                }
            }
        }
    }

    private suspend fun listenForAllergyStep(
        noMatchMessage: String,
        timeoutMillis: Long = 20_000L,
        onReady: () -> Unit,
    ): String? {
        return try {
            voiceMonitorSyncJob?.cancel()
            voiceMonitorSyncJob = null
            voiceBargeInDetector.stop()
            val deferred = speechRecognitionManager.startListening(
                noMatchMessage = noMatchMessage,
                onReady = onReady,
            )
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun parseGlobalSwitchFromNestedFlow(
        spokenText: String,
        allowPackagePhrase: Boolean,
    ): ParsedVoiceCommand? {
        val parsed = VoiceCommandParser.parse(spokenText, strictStandby = false)
        return when (parsed.type) {
            VoiceCommandType.READ_PRESCRIPTION,
            VoiceCommandType.LIST_ALLERGIES,
            VoiceCommandType.REMOVE_ALLERGY,
            VoiceCommandType.REPEAT_LAST_READING,
            VoiceCommandType.CREATE_REMINDER,
            VoiceCommandType.REPEAT_INSTRUCTIONS,
            VoiceCommandType.PAUSE_VOICE -> parsed
            VoiceCommandType.READ_PACKAGE -> if (allowPackagePhrase) parsed else null
            else -> null
        }
    }

    private suspend fun saveAllergyAfterConfirmation(allergyName: String) {
        updateStatus("Registrando alergia", isDanger = false)
        val result = repository.registrarAlergia(this@MainActivity, allergyName)
        result.onSuccess { response ->
            if (response.sucesso) {
                updateStatus("Alergia registrada", isDanger = false, detail = response.mensagem)
                ttsManager.speak(response.mensagem) { success ->
                    returnToVoiceCommandsAfterSpeech(success)
                }
                vibrationManager.vibrateDoubleShort()
            } else {
                handleProcessingError(response.mensagem)
            }
        }.onFailure { error ->
            handleProcessingError(error.message ?: getString(R.string.allergy_error))
        }
    }

    private suspend fun scanAllergyMedicationName(): String? {
        startScanLighting()
        return try {
            val guidance = "Aponte a câmera para o medicamento e mantenha a embalagem inteira centralizada e parada."
            updateStatus("Escaneando alergia", isDanger = false, detail = guidance)
            vibrationManager.vibrateShort()
            awaitScanInstruction(guidance)

            val budget = ReadBudget(ReadBudget.PACKAGE_MILLIS)
            var partial: VerificarResponse? = null
            var focusTarget: Pair<Float, Float>? = null
            val packageGuidance = PackageGuidanceController()
            val response = budget.run(budget.remainingMillis) {
                readLivePackage(
                    guidance = packageGuidance,
                    onPartial = { candidate ->
                        if (shouldPreferPackageResult(candidate, partial)) partial = candidate
                    },
                    onFocusTarget = { focusTarget = it },
                ) ?: captureWithFocusTicks(budget) {
                    awaitVoiceInterruptionIdle()
                    awaitLightingSettleIfChanged()
                    budget.run(1_800L) {
                        awaitScanFocus(maxWaitMillis = 1_600L, targetPointNorm = focusTarget, settleMillis = 280L)
                    }
                    readPackageFrame(budget) { bitmap ->
                        identifyPackageFromBitmap(bitmap, budget) { candidate ->
                            if (shouldPreferPackageResult(candidate, partial)) partial = candidate
                        }
                    }
                }
            }
            (response ?: partial)?.takeIf { isIdentifiedMedication(it) }?.nome
        } finally {
            stopScanLighting()
        }
    }

    private suspend fun confirmAllergyRegistration(
        allergyName: String,
        completingOwner: Job?,
    ): AllergyConfirmOutcome {
        val prompts = listOf(
            getString(R.string.allergy_confirm_prompt, allergyName),
            getString(R.string.allergy_confirm_retry, allergyName),
        )
        for (prompt in prompts) {
            updateStatus("Confirmar alergia", isDanger = false, detail = prompt)
            vibrationManager.vibrateDoubleShort()
            if (!awaitInterruptibleFunctionsPrompt(prompt)) return AllergyConfirmOutcome.CANCELLED

            val answer = listenForAllergyStep(
                noMatchMessage = "Não entendi a confirmação",
                timeoutMillis = 15_000L,
            ) {
                updateStatus("Pode confirmar agora", isDanger = false, detail = prompt)
                vibrationManager.vibrateDoubleShort()
            } ?: continue

            val allergyDecision = AllergyRegistrationParser.parse(answer).type
            if (allergyDecision == AllergyRegistrationInputType.CONFIRM) return AllergyConfirmOutcome.CONFIRMED
            if (allergyDecision == AllergyRegistrationInputType.CANCEL) return AllergyConfirmOutcome.CANCELLED

            val globalSwitch = parseGlobalSwitchFromNestedFlow(
                spokenText = answer,
                allowPackagePhrase = allergyDecision != AllergyRegistrationInputType.CAMERA,
            )
            if (globalSwitch != null) {
                if (completingOwner != null && allergyJob == completingOwner) {
                    allergyJob = null
                    isProcessing = false
                }
                dispatchVoiceCommand(globalSwitch, answer, retryOnUnknown = false)
                return AllergyConfirmOutcome.GLOBAL_SWITCHED
            }

            if (allergyDecision == AllergyRegistrationInputType.CAMERA) return AllergyConfirmOutcome.RETRY_WITH_CAMERA
        }
        return AllergyConfirmOutcome.CANCELLED
    }

    private fun announceAllergyCancellation(completingOwner: Job? = null) {
        if (completingOwner != null && allergyJob == completingOwner) {
            allergyJob = null
        }
        resumeVoiceCommandsAfterOperation = false
        voiceStandbyPausedByUser = false
        consecutiveStandbyMisses = 0
        isProcessing = false
        val message = getString(R.string.allergy_cancelled_prompt)
        updateStatus("Cadastro cancelado", isDanger = false, detail = message)
        vibrationManager.vibrateShort()
        val started = ttsManager.speak(message) { success ->
            if (success && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !isDestroyed) {
                startVoiceCommandSession(prompt = false, retryOnUnknown = true)
            } else {
                scheduleStandbyVoiceIfIdle()
            }
        }
        if (!started) scheduleStandbyVoiceIfIdle()
    }

    private fun cancelAllergyRegistration() {
        val pending = allergyJob
        allergyJob = null
        pending?.cancel()
        speechRecognitionManager.stopListening()
    }

    private fun startVoiceCommandSession(
        prompt: Boolean,
        retryOnUnknown: Boolean = true,
        isStandbyWake: Boolean = false,
        allowCancelOnWake: Boolean = !isStandbyWake,
        quickRetryOnInterruptedSpeech: Boolean = false,
    ) {
        if (permissionFlowActive || voiceCommandJob?.isActive == true) return
        if (!isStandbyWake && isProcessing) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!isStandbyWake) {
                val message = "Comandos de voz indisponíveis sem a permissão de microfone. Os gestos continuam funcionando."
                updateStatus("Comandos de voz indisponíveis", isDanger = false, detail = message)
                vibrationManager.vibrateShort()
                ttsManager.speak(message)
            }
            return
        }

        standbyVoiceJob?.cancel()
        standbyVoiceJob = null
        voiceMonitorSyncJob?.cancel()
        voiceMonitorSyncJob = null
        voiceBargeInDetector.stop()
        val sessionId = ++voiceCommandSessionId
        val wasOperationRunning = isProcessing ||
            packageScanJob?.isActive == true ||
            prescriptionCaptureJob?.isActive == true ||
            allergyJob?.isActive == true ||
            alarmJob?.isActive == true
        if (!wasOperationRunning) {
            isProcessing = true
        }
        val strictStandby = isStandbyWake && !allowCancelOnWake
        var queuedQuickRetry = false
        voiceCommandJob = mainScope.launch {
            val owner = currentCoroutineContext()[Job]
            var commandDispatched = false
            fun triggerInterruptedRetry(): Boolean {
                if (!quickRetryOnInterruptedSpeech) return false
                queuedQuickRetry = true
                consecutiveStandbyMisses = 0
                val promptStarted = ttsManager.speak("Pode falar.") { success ->
                    if (success &&
                        !isDestroyed &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        !voiceStandbyPausedByUser
                    ) {
                        startVoiceCommandSession(
                            prompt = false,
                            retryOnUnknown = false,
                            isStandbyWake = true,
                            allowCancelOnWake = true,
                            quickRetryOnInterruptedSpeech = false,
                        )
                    } else {
                        schedulePriorityVoiceSync(150L)
                    }
                }
                if (!promptStarted) {
                    queuedQuickRetry = false
                }
                return promptStarted
            }

            try {
                if (prompt) {
                    updateStatus("Comandos por voz", isDanger = false, detail = getString(R.string.voice_command_prompt))
                    if (!awaitInterruptibleFunctionsPrompt(getString(R.string.voice_command_prompt))) {
                        throw IllegalStateException("Não foi possível anunciar os comandos de voz")
                    }
                }
                if (sessionId != voiceCommandSessionId || ttsManager.isSpeakingNow()) return@launch

                val deferred = speechRecognitionManager.startListening(
                    noMatchMessage = "Não entendi o comando",
                    minimumInputLengthMillis = 900L,
                    completeSilenceLengthMillis = 1_400L,
                    partialAcceptPredicate = { partialText ->
                        VoiceCommandParser.canDispatchFromPartial(partialText, strictStandby = strictStandby) != null
                    },
                ) {
                    if (sessionId == voiceCommandSessionId) {
                        if (isStandbyWake) {
                            vibrationManager.vibrateFocusTick()
                        } else {
                            updateStatus("Pode falar agora", isDanger = false, detail = getString(R.string.voice_command_examples))
                            vibrationManager.vibrateDoubleShort()
                        }
                    }
                }
                val timeoutMs = if (isStandbyWake) 9_000L else 18_000L
                val spokenText = withTimeout(timeoutMs) { deferred.await() }
                if (sessionId != voiceCommandSessionId) return@launch
                if (!wasOperationRunning) {
                    isProcessing = false
                }
                val parsed = VoiceCommandParser.parse(spokenText, strictStandby = strictStandby)
                if (isStandbyWake && parsed.type == VoiceCommandType.UNKNOWN) {
                    if (!triggerInterruptedRetry()) {
                        consecutiveStandbyMisses++
                    }
                } else {
                    if (parsed.type != VoiceCommandType.UNKNOWN) {
                        consecutiveStandbyMisses = 0
                        voiceStandbyPausedByUser = false
                    }
                    commandDispatched = dispatchVoiceCommand(
                        parsed,
                        spokenText,
                        retryOnUnknown = !isStandbyWake && retryOnUnknown,
                    )
                }
            } catch (_: TimeoutCancellationException) {
                if (sessionId == voiceCommandSessionId) {
                    if (!wasOperationRunning) isProcessing = false
                    if (isStandbyWake) {
                        if (!triggerInterruptedRetry()) consecutiveStandbyMisses++
                    } else {
                        handleVoiceCommandFailure("Nenhum comando foi ouvido.", retryOnUnknown)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (sessionId == voiceCommandSessionId) {
                    if (!wasOperationRunning) isProcessing = false
                    if (isStandbyWake) {
                        if (!triggerInterruptedRetry()) consecutiveStandbyMisses++
                    } else {
                        handleVoiceCommandFailure(error.message ?: "Não entendi o comando.", retryOnUnknown)
                    }
                }
            } finally {
                if (voiceCommandJob == owner) {
                    voiceCommandJob = null
                    speechRecognitionManager.stopListening()
                    if (!commandDispatched && !wasOperationRunning) {
                        isProcessing = false
                    }
                    if (isStandbyWake && !commandDispatched && !queuedQuickRetry) {
                        val backoff = when (consecutiveStandbyMisses) {
                            0 -> 150L
                            1 -> 300L
                            else -> 600L
                        }
                        schedulePriorityVoiceSync(delayMs = backoff)
                    }
                }
            }
        }
    }

    private fun dispatchVoiceCommand(
        command: ParsedVoiceCommand,
        originalText: String,
        retryOnUnknown: Boolean,
    ): Boolean {
        if (command.type != VoiceCommandType.UNKNOWN &&
            command.type != VoiceCommandType.CANCEL &&
            command.type != VoiceCommandType.PAUSE_VOICE
        ) {
            interruptCurrentOperation()
        }
        return when (command.type) {
            VoiceCommandType.READ_PACKAGE -> {
                handleSingleTap()
                resumeVoiceCommandsAfterOperation = true
                true
            }
            VoiceCommandType.READ_PRESCRIPTION -> {
                handleDoubleTap()
                resumeVoiceCommandsAfterOperation = true
                true
            }
            VoiceCommandType.REGISTER_ALLERGY -> {
                voiceStandbyPausedByUser = false
                resumeVoiceCommandsAfterOperation = true
                isProcessing = true
                startAllergyVoiceRegistration(command.argument)
                true
            }
            VoiceCommandType.LIST_ALLERGIES -> {
                voiceStandbyPausedByUser = false
                resumeVoiceCommandsAfterOperation = true
                listRegisteredAllergies()
                true
            }
            VoiceCommandType.REMOVE_ALLERGY -> {
                voiceStandbyPausedByUser = false
                resumeVoiceCommandsAfterOperation = true
                isProcessing = true
                removeAllergyByVoice(command.argument)
                true
            }
            VoiceCommandType.REPEAT_LAST_READING -> {
                voiceStandbyPausedByUser = false
                resumeVoiceCommandsAfterOperation = true
                isProcessing = true
                repeatLastReading()
                true
            }
            VoiceCommandType.CREATE_REMINDER -> {
                voiceStandbyPausedByUser = false
                resumeVoiceCommandsAfterOperation = true
                isProcessing = true
                createPrescriptionReminders(command.argument)
                true
            }
            VoiceCommandType.REPEAT_INSTRUCTIONS -> {
                voiceStandbyPausedByUser = false
                resumeVoiceCommandsAfterOperation = false
                val instructions = getString(R.string.repeat_instructions)
                updateStatus("Instruções", isDanger = false, detail = instructions)
                vibrationManager.vibrateShort()
                speakFunctionsWithBargeIn(instructions) {
                    startVoiceCommandSession(prompt = false, retryOnUnknown = true, allowCancelOnWake = true)
                }
                true
            }
            VoiceCommandType.CANCEL -> {
                cancelCurrentOperation()
                true
            }
            VoiceCommandType.PAUSE_VOICE -> {
                interruptCurrentOperation()
                resumeVoiceCommandsAfterOperation = false
                voiceStandbyPausedByUser = true
                val message = "Escuta por voz pausada. Os toques continuam funcionando."
                updateStatus("Voz pausada", isDanger = false, detail = message)
                vibrationManager.vibrateShort()
                ttsManager.speak(message)
                true
            }
            VoiceCommandType.UNKNOWN -> {
                handleVoiceCommandFailure("Não entendi: $originalText.", retryOnUnknown)
                true
            }
        }
    }

    private fun handleVoiceCommandFailure(message: String, retry: Boolean) {
        updateStatus("Comando não entendido", isDanger = false, detail = message)
        vibrationManager.vibrateShort()
        val guidance = "$message ${getString(R.string.voice_command_examples)}"
        val started = ttsManager.speak(guidance) { success ->
            if (success && retry) {
                startVoiceCommandSession(prompt = false, retryOnUnknown = false, allowCancelOnWake = true)
            } else {
                scheduleStandbyVoiceIfIdle()
            }
        }
        if (!started) scheduleStandbyVoiceIfIdle()
    }

    private fun returnToVoiceCommandsAfterSpeech(success: Boolean, prompt: Boolean = true) {
        if (!success || !resumeVoiceCommandsAfterOperation) {
            scheduleStandbyVoiceIfIdle()
            return
        }
        val expectedSession = voiceCommandSessionId
        voiceCommandResumeJob?.cancel()
        voiceCommandResumeJob = mainScope.launch {
            delay(400)
            if (expectedSession == voiceCommandSessionId &&
                resumeVoiceCommandsAfterOperation &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                startVoiceCommandSession(prompt = prompt)
            } else {
                scheduleStandbyVoiceIfIdle()
            }
            voiceCommandResumeJob = null
        }
    }

    private fun cancelVoiceCommandSession() {
        voiceCommandSessionId += 1
        voiceMonitorSyncJob?.cancel()
        voiceMonitorSyncJob = null
        voicePreemptJob?.cancel()
        voicePreemptJob = null
        standbyVoiceJob?.cancel()
        standbyVoiceJob = null
        bargeInSessionId += 1
        bargeInResumeJob?.cancel()
        bargeInResumeJob = null
        customBargeInActive = false
        voiceBargeInDetector.stop()
        voiceCommandResumeJob?.cancel()
        voiceCommandResumeJob = null
        val pending = voiceCommandJob
        voiceCommandJob = null
        pending?.cancel()
        speechRecognitionManager.stopListening()
        if (!permissionFlowActive && packageScanJob?.isActive != true &&
            prescriptionCaptureJob?.isActive != true && allergyJob?.isActive != true
        ) {
            isProcessing = false
        }
    }

    private fun scheduleStandbyVoiceIfIdle(delayMs: Long = 150L) {
        schedulePriorityVoiceSync(delayMs = delayMs)
    }

    private fun schedulePriorityVoiceSync(delayMs: Long = 150L) {
        if (voiceStandbyPausedByUser || isDestroyed || isTalkBackTouchExplorationActive()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        voiceMonitorSyncJob?.cancel()
        voiceMonitorSyncJob = mainScope.launch {
            val owner = currentCoroutineContext()[Job]
            try {
                if (delayMs > 0L) delay(delayMs)
                syncVoiceMonitor()
            } finally {
                if (voiceMonitorSyncJob == owner) voiceMonitorSyncJob = null
            }
        }
    }

    private fun syncVoiceMonitor() {
        if (!canRunPriorityVoiceMonitor()) {
            voiceBargeInDetector.stop()
            return
        }
        if (ttsManager.isSpeakingNow()) {
            val remainingGuard = criticalAlertProtectedUntilMs - SystemClock.elapsedRealtime()
            if (remainingGuard > 0L) {
                voiceBargeInDetector.stop()
                schedulePriorityVoiceSync(delayMs = remainingGuard + 40L)
                return
            }
            voiceBargeInDetector.start(VoiceBargeInDetector.Mode.TTS_BARGE_IN) {
                onPriorityVoiceDetected()
            }
        } else {
            voiceBargeInDetector.start(VoiceBargeInDetector.Mode.AMBIENT_STANDBY) {
                onPriorityVoiceDetected()
            }
        }
    }

    private fun onPriorityVoiceDetected() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            isDestroyed ||
            permissionFlowActive ||
            voiceStandbyPausedByUser ||
            isTalkBackTouchExplorationActive() ||
            speechRecognitionManager.isListening ||
            voiceCommandJob?.isActive == true
        ) {
            return
        }

        val ttsWasSpeaking = ttsManager.isSpeakingNow()
        val hadActiveOperationOrSpeech = ttsWasSpeaking || isOperationActiveForVoice()

        if (ttsWasSpeaking) {
            voiceBargeInDetector.stop()
            ttsManager.stop()
            vibrationManager.vibrateDoubleShort()
            voicePreemptJob?.cancel()
            voicePreemptJob = mainScope.launch {
                val owner = currentCoroutineContext()[Job]
                try {
                    delay(100)
                    repeat(6) {
                        if (!ttsManager.isSpeakingNow()) return@repeat
                        delay(25)
                    }
                    if (!ttsManager.isSpeakingNow() &&
                        !isDestroyed &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        !voiceStandbyPausedByUser
                    ) {
                        startVoiceCommandSession(
                            prompt = false,
                            retryOnUnknown = false,
                            isStandbyWake = true,
                            allowCancelOnWake = hadActiveOperationOrSpeech,
                            quickRetryOnInterruptedSpeech = true,
                        )
                    }
                } finally {
                    if (voicePreemptJob == owner) voicePreemptJob = null
                }
            }
        } else {
            startVoiceCommandSession(
                prompt = false,
                retryOnUnknown = false,
                isStandbyWake = true,
                allowCancelOnWake = hadActiveOperationOrSpeech,
                quickRetryOnInterruptedSpeech = false,
            )
        }
    }

    private fun isVoiceInterruptionActive(): Boolean =
        voicePreemptJob?.isActive == true ||
            speechRecognitionManager.isListening ||
            voiceCommandJob?.isActive == true

    private suspend fun awaitVoiceInterruptionIdle() {
        while (voicePreemptJob?.isActive == true ||
            voiceCommandJob?.isActive == true ||
            speechRecognitionManager.isListening
        ) {
            voicePreemptJob?.takeIf { it.isActive }?.join()
            voiceCommandJob?.takeIf { it.isActive }?.join()
            if (speechRecognitionManager.isListening) delay(40)
        }
    }

    private fun isOperationActiveForVoice(): Boolean =
        isProcessing ||
            packageScanJob?.isActive == true ||
            prescriptionCaptureJob?.isActive == true ||
            allergyJob?.isActive == true ||
            alarmJob?.isActive == true

    private fun canRunPriorityVoiceMonitor(): Boolean =
        !voiceStandbyPausedByUser &&
            !isDestroyed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !permissionFlowActive &&
            !customBargeInActive &&
            !isTalkBackTouchExplorationActive() &&
            !speechRecognitionManager.isListening &&
            voiceCommandJob?.isActive != true &&
            voiceCommandResumeJob?.isActive != true &&
            bargeInResumeJob?.isActive != true &&
            voicePreemptJob?.isActive != true &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun continueAfterPermissions() {
        permissionFlowActive = false
        isProcessing = false
        val sharedIntent = pendingSharedIntent
        if (sharedIntent != null) {
            pendingSharedIntent = null
            handleIntent(sharedIntent)
        } else {
            announceStartupIfNeeded()
        }
    }

    private fun handleIntent(sourceIntent: Intent) {
        val action = sourceIntent.action
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            if (permissionFlowActive) {
                pendingSharedIntent = sourceIntent
                return
            }
            val type = sourceIntent.type ?: ""
            if (type.startsWith("image/") || type == "application/pdf") {
                processSharedContent(sourceIntent)
            }
        }
    }

    private fun updateStatus(message: String, isDanger: Boolean, detail: String = "") {
        readingPanel.show(message, detail, getString(R.string.visual_alert_label).takeIf { isDanger })
    }

    private fun listRegisteredAllergies() {
        val allergies = LocalAllergyStore.listSorted(this)
        val (title, detail, speech) = if (allergies.isEmpty()) {
            Triple(
                "Sem alergias",
                "Nenhuma alergia cadastrada no aparelho.",
                "Você não tem nenhuma alergia cadastrada no aparelho.",
            )
        } else {
            val joined = allergies.joinToString(", ")
            Triple(
                "Alergias (${allergies.size})",
                joined,
                "Suas alergias cadastradas são: $joined.",
            )
        }
        updateStatus(title, isDanger = false, detail = detail)
        vibrationManager.vibrateShort()
        ttsManager.speak(speech) { success -> returnToVoiceCommandsAfterSpeech(success) }
    }

    private fun removeAllergyByVoice(initialTarget: String?) {
        allergyJob = mainScope.launch {
            val owner = currentCoroutineContext()[Job]
            try {
                val target = initialTarget ?: run {
                    if (!awaitInterruptibleFunctionsPrompt("Fale o nome da alergia que deseja remover.")) {
                        handleProcessingError("Não foi possível preparar a remoção por voz.")
                        return@launch
                    }
                    listenForAllergyStep(
                        noMatchMessage = "Não entendi o nome da alergia",
                        timeoutMillis = 15_000L,
                    ) {
                        updateStatus("Pode falar agora", isDanger = false)
                        vibrationManager.vibrateDoubleShort()
                    } ?: run {
                        handleProcessingError("Não entendi o nome da alergia para remover.")
                        return@launch
                    }
                }
                if (AllergyRegistrationParser.parse(target).type == AllergyRegistrationInputType.CANCEL) {
                    announceAllergyRemovalCancelled()
                    return@launch
                }
                val preGlobal = VoiceCommandParser.parse(target, strictStandby = false)
                if (preGlobal.type in setOf(
                        VoiceCommandType.READ_PACKAGE,
                        VoiceCommandType.READ_PRESCRIPTION,
                        VoiceCommandType.LIST_ALLERGIES,
                        VoiceCommandType.REGISTER_ALLERGY,
                        VoiceCommandType.REPEAT_LAST_READING,
                        VoiceCommandType.CREATE_REMINDER,
                        VoiceCommandType.REPEAT_INSTRUCTIONS,
                        VoiceCommandType.CANCEL,
                        VoiceCommandType.PAUSE_VOICE,
                    )
                ) {
                    if (allergyJob == owner) {
                        allergyJob = null
                        isProcessing = false
                    }
                    dispatchVoiceCommand(preGlobal, target, retryOnUnknown = false)
                    return@launch
                }
                val matched = withContext(Dispatchers.IO) {
                    LocalAllergyStore.findMatch(this@MainActivity, target)
                }
                if (matched == null) {
                    val current = withContext(Dispatchers.IO) { LocalAllergyStore.listSorted(this@MainActivity) }
                    val detail = if (current.isEmpty()) {
                        "Não há alergias cadastradas."
                    } else {
                        "Não encontrei $target. Cadastradas: ${current.joinToString(", ")}."
                    }
                    updateStatus("Alergia não encontrada", isDanger = false, detail = detail)
                    vibrationManager.vibrateShort()
                    ttsManager.speak(detail) { success ->
                        returnToVoiceCommandsAfterSpeech(success)
                    }
                    return@launch
                }

                val confirmation = "Confirma remover a alergia a $matched? Diga confirmar ou cancelar."
                updateStatus("Confirmar remoção", isDanger = false, detail = confirmation)
                vibrationManager.vibrateDoubleShort()
                if (!awaitInterruptibleFunctionsPrompt(confirmation)) {
                    announceAllergyRemovalCancelled()
                    return@launch
                }
                var confirmed = false
                for (attempt in 1..2) {
                    val answer = listenForAllergyStep(
                        noMatchMessage = "Não entendi a confirmação",
                        timeoutMillis = 15_000L,
                    ) {
                        updateStatus("Pode confirmar agora", isDanger = false)
                        vibrationManager.vibrateDoubleShort()
                    }
                    if (answer.isNullOrBlank()) {
                        if (attempt == 1 && awaitInterruptibleFunctionsPrompt("Não entendi. Diga confirmar ou cancelar.")) continue
                        announceAllergyRemovalCancelled()
                        return@launch
                    }
                    val allergyType = AllergyRegistrationParser.parse(answer).type
                    if (allergyType == AllergyRegistrationInputType.CONFIRM) {
                        confirmed = true
                        break
                    }
                    if (allergyType == AllergyRegistrationInputType.CANCEL) {
                        announceAllergyRemovalCancelled()
                        return@launch
                    }
                    val globalSwitch = parseGlobalSwitchFromNestedFlow(answer, allowPackagePhrase = true)
                    if (globalSwitch != null) {
                        if (allergyJob == owner) {
                            allergyJob = null
                            isProcessing = false
                        }
                        dispatchVoiceCommand(globalSwitch, answer, retryOnUnknown = false)
                        return@launch
                    }
                    if (attempt == 1 && awaitInterruptibleFunctionsPrompt("Não entendi. Diga confirmar para remover ou cancelar.")) {
                        continue
                    }
                    announceAllergyRemovalCancelled()
                    return@launch
                }
                if (!confirmed) {
                    announceAllergyRemovalCancelled()
                    return@launch
                }

                val (removed, remaining) = withContext(Dispatchers.IO) {
                    LocalAllergyStore.remove(this@MainActivity, matched)
                }
                if (removed != null) {
                    val summary = if (remaining.isEmpty()) {
                        "Nenhuma alergia restante."
                    } else {
                        "Alergias restantes: ${remaining.joinToString(", ")}."
                    }
                    updateStatus("Alergia removida", isDanger = false, detail = "Removida: $removed\n$summary")
                    vibrationManager.vibrateDoubleShort()
                    ttsManager.speak("Alergia a $removed removida.") { success ->
                        returnToVoiceCommandsAfterSpeech(success)
                    }
                } else {
                    announceAllergyRemovalCancelled()
                }
            } catch (_: TimeoutCancellationException) {
                handleProcessingError("Tempo esgotado para remover a alergia.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleProcessingError(e.message ?: "Não foi possível remover a alergia.")
            } finally {
                if (allergyJob == owner) {
                    allergyJob = null
                    speechRecognitionManager.stopListening()
                    isProcessing = false
                }
            }
        }
    }

    private fun announceAllergyRemovalCancelled() {
        val message = "Remoção de alergia cancelada."
        updateStatus("Remoção cancelada", isDanger = false, detail = message)
        vibrationManager.vibrateShort()
        ttsManager.speak(message) { success -> returnToVoiceCommandsAfterSpeech(success) }
    }

    private fun repeatLastReading() {
        val saved = lastReading
        if (saved == null) {
            isProcessing = false
            val message = "Ainda não há nenhuma leitura recente para repetir."
            updateStatus("Sem leitura recente", isDanger = false, detail = message)
            vibrationManager.vibrateShort()
            ttsManager.speak(message) { success -> returnToVoiceCommandsAfterSpeech(success) }
            return
        }
        readingPanel.show(saved)
        if (lastReadingIsDanger) vibrationManager.vibrateAlert() else vibrationManager.vibrateShort()
        ttsManager.speak(saved.speech, TtsManager.UTTERANCE_ID_READING) { success ->
            isProcessing = false
            returnToVoiceCommandsAfterSpeech(success)
        }
    }

    private fun createPrescriptionReminders(spokenCommand: String?) {
        alarmJob?.cancel()
        alarmJob = mainScope.launch {
            val owner = currentCoroutineContext()[Job]
            try {
                val explicit = spokenCommand?.let { PrescriptionScheduleParser.fromSpokenTime(it) }
                val alarms = if (explicit != null) listOf(explicit) else lastPrescriptionAlarms
                if (alarms.isEmpty()) {
                    val message = "Nenhuma receita com horários foi lida. Leia uma receita ou diga criar lembrete às oito horas."
                    updateStatus("Sem horários", isDanger = false, detail = message)
                    vibrationManager.vibrateShort()
                    ttsManager.speak(message) { success -> returnToVoiceCommandsAfterSpeech(success) }
                    return@launch
                }

                val testIntent = Intent(AlarmClock.ACTION_SET_ALARM)
                if (testIntent.resolveActivity(packageManager) == null) {
                    handleProcessingError("Nenhum aplicativo de relógio disponível para criar o alarme.")
                    return@launch
                }

                for ((index, alarm) in alarms.withIndex()) {
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) break
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, alarm.label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    }
                    startActivity(intent)
                    if (index + 1 < alarms.size) delay(300)
                }

                val timesText = alarms.joinToString(", ") { it.formattedTime() }
                val detail = "Alarmes configurados: $timesText"
                updateStatus("Lembretes criados", isDanger = false, detail = detail)
                vibrationManager.vibrateDoubleShort()
                ttsManager.speak("Lembrete criado para $timesText.") { success ->
                    returnToVoiceCommandsAfterSpeech(success)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("GuiaMed", "Falha ao criar alarme", e)
                handleProcessingError("Não foi possível criar o alarme no relógio do aparelho.")
            } finally {
                if (alarmJob == owner) {
                    alarmJob = null
                    isProcessing = false
                }
            }
        }
    }

    private fun speakFunctionsWithBargeIn(message: String, onReadyForVoice: () -> Unit) {
        val expectedSession = ++bargeInSessionId
        bargeInResumeJob?.cancel()
        bargeInResumeJob = null
        customBargeInActive = true
        var bargeInTriggered = false
        var speechStarted = false
        isProcessing = true

        fun scheduleVoiceStartAfterBargeIn() {
            bargeInResumeJob?.cancel()
            bargeInResumeJob = mainScope.launch {
                try {
                    // Drena o buffer residual do alto-falante após ttsManager.stop() antes do microfone.
                    delay(150)
                    repeat(6) {
                        if (!ttsManager.isSpeakingNow()) return@repeat
                        delay(40)
                    }
                    customBargeInActive = false
                    isProcessing = false
                    if (expectedSession == bargeInSessionId &&
                        !isDestroyed &&
                        !ttsManager.isSpeakingNow() &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    ) {
                        vibrationManager.vibrateShort()
                        delay(70)
                        if (expectedSession == bargeInSessionId &&
                            !isDestroyed &&
                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        ) {
                            onReadyForVoice()
                        }
                    }
                } finally {
                    bargeInResumeJob = null
                    customBargeInActive = false
                }
            }
        }

        val canListenBargeIn = !isTalkBackTouchExplorationActive() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val started = ttsManager.speak(
            text = message,
            onStart = {
                speechStarted = true
                if (canListenBargeIn && expectedSession == bargeInSessionId && !isDestroyed) {
                    voiceBargeInDetector.start(VoiceBargeInDetector.Mode.TTS_BARGE_IN) {
                        if (expectedSession != bargeInSessionId || bargeInTriggered) return@start
                        bargeInTriggered = true
                        ttsManager.stop()
                        scheduleVoiceStartAfterBargeIn()
                    }
                }
            },
        ) { success ->
            voiceBargeInDetector.stop()
            if (bargeInTriggered) return@speak
            customBargeInActive = false
            if (expectedSession == bargeInSessionId && success && speechStarted) {
                isProcessing = false
                onReadyForVoice()
            } else if (bargeInResumeJob?.isActive != true) {
                isProcessing = false
                schedulePriorityVoiceSync(150L)
            }
        }
        if (!started) {
            customBargeInActive = false
            voiceBargeInDetector.stop()
            isProcessing = false
            schedulePriorityVoiceSync(150L)
        }
    }

    private suspend fun awaitInterruptibleFunctionsPrompt(message: String): Boolean {
        readingPanel.showDetails(message)
        val timeoutMillis = (message.length * 140L + 6_000L).coerceIn(15_000L, 45_000L)
        var bargeInTriggered = false
        var speechStarted = false
        customBargeInActive = true
        val canListenBargeIn = !isTalkBackTouchExplorationActive() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val spoken = try {
            withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    continuation.invokeOnCancellation {
                        voiceBargeInDetector.stop()
                        ttsManager.stop()
                    }
                    ttsManager.speak(
                        text = message,
                        onStart = {
                            speechStarted = true
                            if (canListenBargeIn && continuation.isActive) {
                                voiceBargeInDetector.start(VoiceBargeInDetector.Mode.TTS_BARGE_IN) {
                                    bargeInTriggered = true
                                    ttsManager.stop()
                                }
                            }
                        },
                    ) { success ->
                        if (continuation.isActive) {
                            continuation.resume(success || (bargeInTriggered && speechStarted))
                        }
                    }
                }
            }
        } finally {
            customBargeInActive = false
            voiceBargeInDetector.stop()
        }
        if (spoken == null) ttsManager.stop()
        if (spoken != true) return false
        if (bargeInTriggered) delay(150)
        repeat(8) {
            if (!ttsManager.isSpeakingNow()) return true
            delay(40)
        }
        return !ttsManager.isSpeakingNow()
    }

    private fun isTalkBackTouchExplorationActive(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager?.isEnabled == true && manager.isTouchExplorationEnabled
    }

    private fun buildStartupSpeech(): String {
        val base = getString(R.string.app_opened)
        return if (isTalkBackTouchExplorationActive()) {
            "$base Com o TalkBack ativo, use também o menu Ações para escolher a função."
        } else {
            base
        }
    }

    private fun announceStartupIfNeeded() {
        if (hasAnnouncedStartup || startupAnnouncementScheduled || isProcessing || permissionFlowActive) return
        startupAnnouncementScheduled = true
        ttsManager.addInitializationListener { success ->
            if (!success) {
                startupAnnouncementScheduled = false
                return@addInitializationListener
            }
            mainScope.launch {
                kotlinx.coroutines.delay(750)
                startupAnnouncementScheduled = false
                if (!hasAnnouncedStartup &&
                    !isProcessing &&
                    !permissionFlowActive &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    hasAnnouncedStartup = true
                    updateStatus("GuiaMed pronto", isDanger = false, detail = getString(R.string.startup_panel_details))
                    speakFunctionsWithBargeIn(buildStartupSpeech()) {
                        startVoiceCommandSession(prompt = false)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        accessibilityManagerRef?.addTouchExplorationStateChangeListener(touchExplorationListener)
        lightSensor?.let { sensor ->
            sensorManager?.registerListener(lightSensorListener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            cameraProviderFuture == null
        ) {
            startCamera()
        }
        announceStartupIfNeeded()
        if (hasAnnouncedStartup) schedulePriorityVoiceSync(150L)
    }

    override fun onPause() {
        super.onPause()
        accessibilityManagerRef?.removeTouchExplorationStateChangeListener(touchExplorationListener)
        sensorManager?.unregisterListener(lightSensorListener)
        resumeVoiceCommandsAfterOperation = false
        customBargeInActive = false
        criticalAlertProtectedUntilMs = 0L
        voiceMonitorSyncJob?.cancel()
        voiceMonitorSyncJob = null
        voicePreemptJob?.cancel()
        voicePreemptJob = null
        standbyVoiceJob?.cancel()
        standbyVoiceJob = null
        bargeInSessionId += 1
        bargeInResumeJob?.cancel()
        bargeInResumeJob = null
        voiceBargeInDetector.stop()
        cancelVoiceCommandSession()
        touchDetector.reset()
        if (packageScanJob?.isActive == true || prescriptionCaptureJob?.isActive == true || allergyJob?.isActive == true || alarmJob?.isActive == true) {
            resetPackageReadSession()
            resetPrescriptionReadSession()
            isProcessing = false
        }
        stopScanLighting()
        synchronized(lightingLock) {
            lightingPolicy.clearEnvironmentState()
        }
        cancelAllergyRegistration()
        cancelAlarmCreation()
        ttsManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        packageFrameSession = 0
        ttsManager.onSpeakingChanged = null
        speechRecognitionManager.onSessionEnded = null
        voiceBargeInDetector.stop()
        ttsManager.shutdown()
        speechRecognitionManager.destroy()
        vibrationManager.cancel()
        analysisUseCase = null
        runCatching { cameraProviderFuture?.get()?.unbindAll() }
        cameraScope.coroutineContext.cancel()
        mainScope.coroutineContext.cancel()
        ocrEngine.close()
        cameraExecutor.shutdown()
        touchDetector.destroy()
    }
}

private fun readPreviewLightingMetrics(image: ImageProxy): PreviewLightingMetrics {
    val plane = image.planes.firstOrNull() ?: return PreviewLightingMetrics(meanY = 128f, clippedRatio = 0f)
    val buffer = plane.buffer
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return PreviewLightingMetrics(meanY = 128f, clippedRatio = 0f)

    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride.coerceAtLeast(1)
    val startX = width / 4
    val endX = (width * 3 / 4).coerceAtLeast(startX + 1)
    val startY = height / 4
    val endY = (height * 3 / 4).coerceAtLeast(startY + 1)
    val stepX = ((endX - startX) / 36).coerceAtLeast(4)
    val stepY = ((endY - startY) / 36).coerceAtLeast(4)

    val baseOffset = buffer.position()
    val limit = buffer.limit()
    var sum = 0L
    var clipped = 0
    var count = 0

    var y = startY
    while (y < endY) {
        val rowBase = baseOffset + y * rowStride
        if (rowBase >= limit) break
        var x = startX
        while (x < endX) {
            val idx = rowBase + x * pixelStride
            if (idx in baseOffset until limit) {
                val luma = buffer.get(idx).toInt() and 0xFF
                sum += luma
                if (luma >= 245) clipped++
                count++
            }
            x += stepX
        }
        y += stepY
    }

    return if (count == 0) {
        PreviewLightingMetrics(meanY = 128f, clippedRatio = 0f)
    } else {
        PreviewLightingMetrics(
            meanY = sum.toFloat() / count.toFloat(),
            clippedRatio = clipped.toFloat() / count.toFloat(),
        )
    }
}
