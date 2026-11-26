package com.guiamed

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.cancel
import com.google.common.util.concurrent.ListenableFuture
import com.guiamed.data.GuiaMedRepository
import com.guiamed.data.ExpirationReader
import com.guiamed.data.VerificarResponse
import com.guiamed.data.ReceitaResponse
import com.guiamed.data.withReadExpiration
import com.guiamed.ocr.MlKitOcrEngine
import com.guiamed.ocr.readPrescription
import com.guiamed.speech.SpeechRecognitionManager
import com.guiamed.speech.TtsManager
import com.guiamed.speech.AllergyRegistrationInputType
import com.guiamed.speech.AllergyRegistrationParser
import com.guiamed.speech.ParsedVoiceCommand
import com.guiamed.speech.VoiceCommandParser
import com.guiamed.speech.VoiceCommandType
import com.guiamed.ui.ReadingPanel
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
    private lateinit var repository: GuiaMedRepository
    private lateinit var touchDetector: TouchDetector
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var isFlashOn = false
    @Volatile private var previewLuminosity: Int? = null
    private var isProcessing = false
        set(value) {
            field = value
            if (!value) stopScanLighting()
        }
    private var hasAnnouncedStartup = false
    private var pendingSharedIntent: Intent? = null
    private var permissionFlowActive = false
    @Volatile private var packageFrameSession = 0L
    private var nextPackageFrameSession = 0L
    private val packageFrames = MutableSharedFlow<Pair<Long, MlKitOcrEngine.PackageFrame>>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val maxPackageReadAttempts = 2
    private val maxValidityReadAttempts = 4
    private var packageScanJob: Job? = null
    private var identifiedPackageResponse: VerificarResponse? = null
    private val maxPrescriptionReadAttempts = 3
    private var prescriptionReadAttempt = 0
    private var prescriptionCaptureJob: Job? = null
    private var allergyJob: Job? = null
    private var voiceCommandJob: Job? = null
    private var voiceCommandResumeJob: Job? = null
    private var voiceCommandSessionId = 0L
    private var resumeVoiceCommandsAfterOperation = false
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
        readingPanel = ReadingPanel(findViewById(R.id.readingPanel))
        rootLayout = findViewById(R.id.rootLayout)

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
        repository = GuiaMedRepository()
        cameraExecutor = Executors.newSingleThreadExecutor()
        permissionHelper = PermissionAccessibilityHelper(this, ttsManager)
        
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
        val preview = Preview.Builder()
            .setTargetRotation(previewView.display.rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setTargetRotation(previewView.display.rotation)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(95)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetRotation(previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    previewLuminosity = readPreviewLuminosity(proxy)
                    val session = packageFrameSession
                    if (session == 0L) {
                        proxy.close()
                    } else {
                        ocrEngine.analyzePackageFrame(proxy) { frame ->
                            if (packageFrameSession == session) packageFrames.tryEmit(session to frame)
                        }
                    }
                }
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                this as LifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis
            )
        } catch (e: Exception) {
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

        val status = findViewById<View>(R.id.statusText)
        val actions = listOf(
            R.string.accessibility_read_package to ::handleSingleTap,
            R.string.accessibility_read_prescription to ::handleDoubleTap,
            R.string.accessibility_register_allergy to ::handleTripleTap,
            R.string.accessibility_exit_or_help to ::handleQuadTap,
        )
        actions.forEach { (label, action) ->
            ViewCompat.addAccessibilityAction(status, getString(label)) { _, _ ->
                action()
                true
            }
        }
        ViewCompat.replaceAccessibilityAction(
            status,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
            getString(R.string.accessibility_open_settings),
        ) { _, _ ->
            openAppSettings()
            true
        }
    }

    private fun repeatInstructions() {
        val instructions = getString(R.string.repeat_instructions)
        updateStatus("Instruções", isDanger = false, detail = instructions)
        ttsManager.speak(instructions) { success ->
            if (success) startVoiceCommandSession(prompt = false)
        }
        vibrationManager.vibrateShort()
    }

    private fun handleQuadTap() {
        if (permissionFlowActive) {
            val message = "Conclua a solicitação de permissão do sistema."
            updateStatus("Permissão em andamento", isDanger = false, detail = message)
            vibrationManager.vibrateShort()
            ttsManager.speak(message)
            return
        }
        if (isProcessing) cancelCurrentOperation() else repeatInstructions()
    }

    private fun cancelCurrentOperation() {
        interruptCurrentOperation()
        val message = "Função encerrada. Depois do sinal, diga a próxima função."
        updateStatus("Função encerrada", isDanger = false, detail = message)
        vibrationManager.vibrateShort()
        ttsManager.speak(message) { success ->
            if (success) startVoiceCommandSession(prompt = false)
        }
    }

    private fun interruptCurrentOperation() {
        resumeVoiceCommandsAfterOperation = false
        cancelVoiceCommandSession()
        resetPackageReadSession()
        resetPrescriptionReadSession()
        cancelAllergyRegistration()
        stopScanLighting()
        ttsManager.stop()
        isProcessing = false
    }

    private fun withExitInstruction(message: String): String =
        "$message ${getString(R.string.operation_exit_instruction)}"

    private fun openAppSettings() {
        resumeVoiceCommandsAfterOperation = false
        cancelVoiceCommandSession()
        resetPackageReadSession()
        resetPrescriptionReadSession()
        cancelAllergyRegistration()
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
        val activeCamera = camera ?: return
        // Decide com a lanterna apagada e mantém a decisão durante a leitura.
        if (activeCamera.cameraInfo.hasFlashUnit() && (previewLuminosity ?: 128) < 50) {
            activeCamera.cameraControl.enableTorch(true)
            isFlashOn = true
        }
    }

    private fun stopScanLighting() {
        if (!isFlashOn) return
        camera?.cameraControl?.enableTorch(false)
        isFlashOn = false
        previewLuminosity = null
    }

    private fun handleSingleTap() {
        if (permissionFlowActive) return
        if (isProcessing && identifiedPackageResponse != null) {
            finishPackageWithoutValidity()
            return
        }
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
            awaitScanFocus()
            updateStatus("Capturando receita...", isDanger = false)
            vibrationManager.vibrateDoubleShort()
            ttsManager.speak(getString(R.string.reading_now))
            capturePrescriptionPhoto()
        }
    }

    private suspend fun awaitScanInstruction(message: String) {
        if (!awaitInstruction(message)) delay(1_500)
    }

    private suspend fun awaitInstruction(message: String): Boolean {
        readingPanel.showDetails(message)
        val spoken = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                ttsManager.speak(message) { success ->
                    if (continuation.isActive) continuation.resume(success)
                }
            }
        }
        if (spoken == null) ttsManager.stop()
        return spoken == true
    }

    private suspend fun awaitScanFocus(maxWaitMillis: Long = 2_500L) {
        val activeCamera = camera
        if (activeCamera == null || previewView.width == 0 || previewView.height == 0) {
            delay(350)
            return
        }

        val centerPoint = previewView.meteringPointFactory.createPoint(
            previewView.width / 2f,
            previewView.height / 2f,
        )
        val action = FocusMeteringAction.Builder(
            centerPoint,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        val focusFuture = runCatching { activeCamera.cameraControl.startFocusAndMetering(action) }
            .getOrNull()
        if (focusFuture == null) {
            delay(350)
            return
        }
        withTimeoutOrNull(maxWaitMillis) {
            suspendCancellableCoroutine<Unit> { continuation ->
                focusFuture.addListener({
                    if (continuation.isActive) continuation.resume(Unit)
                }, ContextCompat.getMainExecutor(this@MainActivity))
            }
        }
        // Pausa curta para a exposição estabilizar sem tornar a leitura lenta.
        delay(120)
    }

    private fun handleTripleTap() {
        if (permissionFlowActive) return
        interruptCurrentOperation()
        isProcessing = true
        
        vibrationManager.vibrateDoubleShort()
        updateStatus("Cadastro de alergia", isDanger = false)
        startAllergyVoiceRegistration()
    }

    private suspend fun scanPackage() {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val liveResult = readLivePackage()
            if (liveResult != null) {
                Log.d("GuiaMedScan", "Embalagem identificada por quadro em ${SystemClock.elapsedRealtime() - startedAt} ms")
                finishPackageIdentification(liveResult)
                return
            }
            Log.d("GuiaMedScan", "Leitura de embalagem por foto")
            updateStatus(getString(R.string.reading_package), isDanger = false)
            for (attempt in 1..maxPackageReadAttempts) {
                if (attempt > 1) {
                    updateStatus("Ajustando foco — $attempt de $maxPackageReadAttempts", isDanger = false)
                    vibrationManager.vibrateShort()
                    awaitScanInstruction("Mantenha a embalagem inteira dentro da moldura e fique parado.")
                }
                awaitScanFocus(maxWaitMillis = 1_200L)
                updateStatus(getString(R.string.reading_package), isDanger = false)
                val result = readPackageFrame(::identifyPackageFromBitmap)
                if (isIdentifiedMedication(result) || result?.perigo == true) {
                    Log.d("GuiaMedScan", "Resultado de embalagem por foto em ${SystemClock.elapsedRealtime() - startedAt} ms")
                    finishPackageIdentification(requireNotNull(result))
                    return
                }
            }
            handleProcessingError("Não consegui identificar o medicamento com segurança.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("GuiaMed", "Falha ao ler embalagem", e)
            handleProcessingError(getString(R.string.ocr_error))
        }
    }

    private suspend fun readLivePackage(): VerificarResponse? = coroutineScope {
        val session = ++nextPackageFrameSession
        val focus = launch { awaitScanFocus(maxWaitMillis = 1_200L) }
        packageFrameSession = session
        try {
            withTimeoutOrNull(1_800) {
                var lastFrame: MlKitOcrEngine.PackageFrame? = null
                packageFrames.mapNotNull { (frameSession, frame) ->
                    if (frameSession != session || frame == lastFrame ||
                        (frame.text.isBlank() && frame.ean == null)
                    ) return@mapNotNull null
                    lastFrame = frame
                    repository.verificarLocal(this@MainActivity, frame.text, frame.ean, allowFuzzy = false)
                        .takeIf { isIdentifiedMedication(it) || it.perigo }
                }.first()
            }
        } finally {
            if (packageFrameSession == session) packageFrameSession = 0
            focus.cancel()
        }
    }

    private suspend fun identifyPackageFromBitmap(bitmap: Bitmap): VerificarResponse? {
        val focusedText = ocrEngine.recognizeFocusedPackageText(bitmap)
        val focusedResult = focusedText.takeIf(String::isNotBlank)?.let {
            repository.verificarLocal(this, it)
        }
        if (isIdentifiedMedication(focusedResult) || focusedResult?.perigo == true) return focusedResult

        val fullText = ocrEngine.recognizeFullPackageText(bitmap)
        val candidate = fullText.ifBlank { focusedText }
        return candidate.takeIf(String::isNotBlank)
            ?.let { repository.verificarMedicamento(this, it).getOrThrow() }
    }

    private fun isIdentifiedMedication(response: VerificarResponse?): Boolean =
        response != null && !response.nome.startsWith("Medicamento não identificado")

    private suspend fun finishPackageIdentification(response: VerificarResponse) {
        if (response.validade != null || response.perigo || response.alertaAlergia) {
            handleVerificarSuccess(response)
        } else {
            scanValidity(response)
        }
    }

    private suspend fun scanValidity(response: VerificarResponse) {
        identifiedPackageResponse = response
        updateStatus("${response.nome}\nVire para ler a validade", isDanger = false)
        vibrationManager.vibrateDoubleShort()
        awaitScanInstruction("${response.nome}. Vire a mesma embalagem para mostrar a validade. Um toque encerra a busca.")

        for (attempt in 1..maxValidityReadAttempts) {
            updateStatus("Procurando validade — $attempt de $maxValidityReadAttempts", isDanger = false)
            vibrationManager.vibrateShort()
            if (attempt > 1) awaitScanInstruction("Mostre o lado com a data e mantenha parado.")
            awaitScanFocus(maxWaitMillis = 1_500L)
            val dates = readPackageFrame { bitmap ->
                val original = ocrEngine.recognizeDocumentTextFromBitmap(bitmap)
                val contrast = withTimeoutOrNull(3_500) {
                    ocrEngine.recognizeDocumentTextFromBitmap(bitmap, highContrast = true)
                }.orEmpty()
                ExpirationReader.findDates(original) + ExpirationReader.findDates(contrast)
            }.orEmpty()
            val expiration = dates.singleOrNull()
            if (expiration != null) {
                handleVerificarSuccess(response.withReadExpiration(expiration))
                return
            }
        }
        handleVerificarSuccess(response)
    }

    private suspend fun <T> readPackageFrame(
        read: suspend (Bitmap) -> T,
    ): T? {
        val capture = imageCapture ?: error("A câmera ainda não está pronta")
        val file = File(cacheDir, "embalagem-${java.util.UUID.randomUUID()}.jpg")
        try {
            return withTimeoutOrNull(7_000) {
                saveScanPhoto(capture, file)
                withContext(Dispatchers.IO) {
                    val bitmap = decodeBitmapRespectingExif(file) ?: return@withContext null
                    var processingFinished = false
                    try {
                        read(bitmap).also { processingFinished = true }
                    } finally {
                        if (processingFinished && !bitmap.isRecycled) bitmap.recycle()
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
        readingPanel.show(result)
        when {
            response.alertaAlergia || response.vencido -> vibrationManager.vibrateAlert()
            response.perigo -> vibrationManager.vibrateTripleLong()
            else -> vibrationManager.vibrateShort()
        }
        ttsManager.speak(result.speech, TtsManager.UTTERANCE_ID_READING) { success ->
            returnToVoiceCommandsAfterSpeech(success)
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
        prescriptionReadAttempt += 1
        prescriptionCaptureJob = mainScope.launch {
            val file = File(cacheDir, "receita-${java.util.UUID.randomUUID()}.jpg")
            try {
                val startedAt = SystemClock.elapsedRealtime()
                withTimeout(6_000) { saveScanPhoto(imageCapture, file) }
                updateStatus("Lendo texto da receita…", isDanger = false)
                val response = withTimeout(18_000) {
                    withContext(Dispatchers.IO) { readPrescriptionPhoto(file) }
                }
                Log.d("GuiaMedScan", "Receita processada em ${SystemClock.elapsedRealtime() - startedAt} ms")
                if (response != null && response.medicamentos.isNotEmpty()) {
                    handlePrescriptionSuccess(response)
                } else {
                    retryPrescriptionReadOrFinish(response?.mensagem ?: getString(R.string.no_text_found))
                }
            } catch (_: TimeoutCancellationException) {
                handleProcessingError("A leitura da receita demorou demais. Enquadre a folha e tente novamente.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GuiaMed", "Falha na captura ou leitura da receita", e)
                retryPrescriptionReadOrFinish(getString(R.string.ocr_error))
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
                        withTimeout(16_000) { readPrescriptionBitmap(bitmap) }
                    } finally {
                        if (currentCoroutineContext().isActive) bitmap.recycle()
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

    private suspend fun readPrescriptionPhoto(file: File): ReceitaResponse? {
        val bitmap = decodeBitmapRespectingExif(file) ?: error("Imagem inválida")
        try {
            return readPrescriptionBitmap(bitmap)
        } finally {
            if (currentCoroutineContext().isActive) bitmap.recycle()
        }
    }

    private suspend fun readPrescriptionBitmap(bitmap: Bitmap): ReceitaResponse? =
        ocrEngine.readPrescription(this, repository, bitmap)

    private fun retryPrescriptionReadOrFinish(message: String) {
        if (prescriptionReadAttempt >= maxPrescriptionReadAttempts) {
            handleProcessingError(message)
            return
        }

        val nextAttempt = prescriptionReadAttempt + 1
        val retryMessage = "Mantenha a receita inteira no enquadramento e fique parado."
        updateStatus("Ajustando receita — tentativa $nextAttempt de $maxPrescriptionReadAttempts", isDanger = false)
        vibrationManager.vibrateDoubleShort()
        guideAndCapturePrescription(retryMessage)
    }

    private fun resetPrescriptionReadSession() {
        prescriptionReadAttempt = 0
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
        readingPanel.show(result)
        ttsManager.speak(result.speech, TtsManager.UTTERANCE_ID_READING) { success ->
            returnToVoiceCommandsAfterSpeech(success)
        }
        if (result.alert != null) vibrationManager.vibrateAlert()
        else vibrationManager.vibrateDoubleShort()
    }

    private fun startAllergyVoiceRegistration(initialAllergyName: String? = null) {
        allergyJob = mainScope.launch {
            val owner = currentCoroutineContext()[Job]
            try {
                val recognizedText = initialAllergyName ?: run {
                    if (!awaitInstruction(withExitInstruction(getString(R.string.allergy_mode))) || ttsManager.isSpeakingNow()) {
                        handleProcessingError("Não foi possível preparar o cadastro por voz.")
                        return@launch
                    }
                    val deferred = speechRecognitionManager.startListening(
                        noMatchMessage = "Não entendi o nome da alergia",
                    ) {
                        updateStatus("Pode falar agora", isDanger = false)
                        vibrationManager.vibrateDoubleShort()
                    }
                    withTimeout(20_000L) { deferred.await() }
                }
                if (recognizedText.isBlank()) {
                    handleProcessingError("Não entendi. Tente novamente.")
                    return@launch
                }
                val allergyInput = AllergyRegistrationParser.parse(recognizedText)
                if (allergyInput.type == AllergyRegistrationInputType.CANCEL) {
                    announceAllergyCancellation()
                    return@launch
                }
                val allergyName = when (allergyInput.type) {
                    AllergyRegistrationInputType.CAMERA -> scanAllergyMedicationName()
                    AllergyRegistrationInputType.MEDICATION_NAME -> allergyInput.medicationName
                    else -> null
                }
                if (allergyName.isNullOrBlank()) {
                    handleProcessingError("Não consegui identificar o medicamento para cadastrar a alergia.")
                    return@launch
                }
                if (!confirmAllergyRegistration(allergyName)) {
                    announceAllergyCancellation()
                    return@launch
                }
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
            } catch (_: TimeoutCancellationException) {
                handleProcessingError("Tempo esgotado para o cadastro por voz. Tente novamente.")
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

    private suspend fun scanAllergyMedicationName(): String? {
        startScanLighting()
        return try {
            val guidance = "Aponte a câmera para o medicamento e mantenha a embalagem inteira centralizada e parada."
            updateStatus("Escaneando alergia", isDanger = false, detail = guidance)
            vibrationManager.vibrateShort()
            if (!awaitInstruction(guidance)) return null

            repeat(2) { attempt ->
                awaitScanFocus(maxWaitMillis = 1_200L)
                val response = readLivePackage() ?: readPackageFrame(::identifyPackageFromBitmap)
                if (isIdentifiedMedication(response)) {
                    return requireNotNull(response).nome
                }
                if (attempt == 0) {
                    awaitScanInstruction("Não identifiquei. Mantenha a caixa centralizada e parada para tentar novamente.")
                }
            }
            null
        } finally {
            stopScanLighting()
        }
    }

    private suspend fun confirmAllergyRegistration(allergyName: String): Boolean {
        val confirmation = "Identifiquei $allergyName. Confirma alergia a este medicamento? Diga confirmar ou cancelar."
        updateStatus("Confirmar alergia", isDanger = false, detail = confirmation)
        vibrationManager.vibrateDoubleShort()
        if (!awaitInstruction(confirmation) || ttsManager.isSpeakingNow()) return false

        val deferred = speechRecognitionManager.startListening(
            noMatchMessage = "Não entendi a confirmação",
        ) {
            updateStatus("Pode confirmar agora", isDanger = false)
            vibrationManager.vibrateDoubleShort()
        }
        val answer = withTimeout(15_000L) { deferred.await() }
        return AllergyRegistrationParser.parse(answer).type == AllergyRegistrationInputType.CONFIRM
    }

    private fun announceAllergyCancellation() {
        resumeVoiceCommandsAfterOperation = false
        isProcessing = false
        val message = "Cadastro de alergia cancelado."
        updateStatus("Cadastro cancelado", isDanger = false, detail = message)
        vibrationManager.vibrateShort()
        ttsManager.speak(message)
    }

    private fun cancelAllergyRegistration() {
        val pending = allergyJob
        allergyJob = null
        pending?.cancel()
        speechRecognitionManager.stopListening()
    }

    private fun startVoiceCommandSession(prompt: Boolean, retryOnUnknown: Boolean = true) {
        if (permissionFlowActive || isProcessing || voiceCommandJob?.isActive == true) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val message = "Comandos de voz indisponíveis sem a permissão de microfone. Os gestos continuam funcionando."
            updateStatus("Comandos de voz indisponíveis", isDanger = false, detail = message)
            vibrationManager.vibrateShort()
            ttsManager.speak(message)
            return
        }

        val sessionId = ++voiceCommandSessionId
        isProcessing = true
        voiceCommandJob = mainScope.launch {
            val owner = currentCoroutineContext()[Job]
            var commandDispatched = false
            try {
                if (prompt) {
                    updateStatus("Comandos por voz", isDanger = false, detail = getString(R.string.voice_command_prompt))
                    if (!awaitInstruction(getString(R.string.voice_command_prompt))) {
                        throw IllegalStateException("Não foi possível anunciar os comandos de voz")
                    }
                }
                if (sessionId != voiceCommandSessionId || ttsManager.isSpeakingNow()) return@launch

                val deferred = speechRecognitionManager.startListening(
                    noMatchMessage = "Não entendi o comando",
                ) {
                    if (sessionId == voiceCommandSessionId) {
                        updateStatus("Pode falar agora", isDanger = false, detail = getString(R.string.voice_command_examples))
                        vibrationManager.vibrateDoubleShort()
                    }
                }
                val spokenText = withTimeout(18_000L) { deferred.await() }
                if (sessionId != voiceCommandSessionId) return@launch
                isProcessing = false
                commandDispatched = dispatchVoiceCommand(
                    VoiceCommandParser.parse(spokenText),
                    spokenText,
                    retryOnUnknown,
                )
            } catch (_: TimeoutCancellationException) {
                if (sessionId == voiceCommandSessionId) {
                    isProcessing = false
                    handleVoiceCommandFailure("Nenhum comando foi ouvido.", retryOnUnknown)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (sessionId == voiceCommandSessionId) {
                    isProcessing = false
                    handleVoiceCommandFailure(error.message ?: "Não entendi o comando.", retryOnUnknown)
                }
            } finally {
                if (voiceCommandJob == owner) {
                    voiceCommandJob = null
                    speechRecognitionManager.stopListening()
                    if (!commandDispatched) isProcessing = false
                }
            }
        }
    }

    private fun dispatchVoiceCommand(
        command: ParsedVoiceCommand,
        originalText: String,
        retryOnUnknown: Boolean,
    ): Boolean {
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
                resumeVoiceCommandsAfterOperation = true
                isProcessing = true
                startAllergyVoiceRegistration(command.argument)
                true
            }
            VoiceCommandType.REPEAT_INSTRUCTIONS -> {
                resumeVoiceCommandsAfterOperation = true
                val instructions = getString(R.string.repeat_instructions)
                updateStatus("Instruções", isDanger = false, detail = instructions)
                vibrationManager.vibrateShort()
                ttsManager.speak(instructions) { success -> returnToVoiceCommandsAfterSpeech(success) }
                true
            }
            VoiceCommandType.CANCEL -> {
                resumeVoiceCommandsAfterOperation = false
                val message = "Comandos de voz encerrados. Os gestos continuam disponíveis."
                updateStatus("Comandos de voz encerrados", isDanger = false, detail = message)
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
        ttsManager.speak(guidance) { success ->
            if (success && retry) startVoiceCommandSession(prompt = false, retryOnUnknown = false)
        }
    }

    private fun returnToVoiceCommandsAfterSpeech(success: Boolean) {
        if (!success || !resumeVoiceCommandsAfterOperation) return
        val expectedSession = voiceCommandSessionId
        voiceCommandResumeJob?.cancel()
        voiceCommandResumeJob = mainScope.launch {
            delay(400)
            if (expectedSession == voiceCommandSessionId &&
                resumeVoiceCommandsAfterOperation &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                startVoiceCommandSession(prompt = true)
            }
            voiceCommandResumeJob = null
        }
    }

    private fun cancelVoiceCommandSession() {
        voiceCommandSessionId += 1
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

    private fun continueAfterPermissions() {
        permissionFlowActive = false
        isProcessing = false
        val sharedIntent = pendingSharedIntent
        if (sharedIntent != null) {
            pendingSharedIntent = null
            handleIntent(sharedIntent)
        } else if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
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

    private fun announceStartupIfNeeded() {
        if (hasAnnouncedStartup || isProcessing || permissionFlowActive) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        hasAnnouncedStartup = true
        ttsManager.addInitializationListener { success ->
            if (success) {
                mainScope.launch {
                    kotlinx.coroutines.delay(750)
                    if (!isProcessing) {
                        ttsManager.speakStartupMessage { spoken ->
                            if (spoken) startVoiceCommandSession(prompt = false)
                        }
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
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            cameraProviderFuture == null
        ) {
            startCamera()
        }
        announceStartupIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        resumeVoiceCommandsAfterOperation = false
        cancelVoiceCommandSession()
        touchDetector.reset()
        if (packageScanJob?.isActive == true || prescriptionCaptureJob?.isActive == true || allergyJob?.isActive == true) {
            resetPackageReadSession()
            resetPrescriptionReadSession()
            isProcessing = false
        }
        stopScanLighting()
        previewLuminosity = null
        cancelAllergyRegistration()
        ttsManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
        speechRecognitionManager.destroy()
        ocrEngine.close()
        cameraExecutor.shutdown()
        cameraScope.coroutineContext.cancel()
        mainScope.coroutineContext.cancel()
        touchDetector.destroy()
    }
}

private fun readPreviewLuminosity(image: ImageProxy): Int {
    val buffer = image.planes.firstOrNull()?.buffer ?: return 128
    var sum = 0L
    var count = 0
    for (index in buffer.position() until buffer.limit() step 100) {
        sum += buffer.get(index).toInt() and 0xFF
        count++
    }
    return if (count == 0) 128 else (sum / count).toInt()
}
