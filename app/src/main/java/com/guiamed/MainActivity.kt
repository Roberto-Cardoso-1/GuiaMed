package com.guiamed

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
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
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.cancel
import com.google.common.util.concurrent.ListenableFuture
import com.guiamed.data.GuiaMedRepository
import com.guiamed.data.VerificarResponse
import com.guiamed.data.ReceitaResponse
import com.guiamed.ocr.TesseractOcrEngine
import com.guiamed.speech.SpeechRecognitionManager
import com.guiamed.speech.TtsManager
import com.guiamed.utils.PermissionAccessibilityHelper
import com.guiamed.utils.TouchDetector
import com.guiamed.utils.VibrationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), PermissionAccessibilityHelper.PermissionRequester {

    private lateinit var previewView: PreviewView
    private lateinit var topPanel: View
    private lateinit var statusText: android.widget.TextView
    private lateinit var instructionText: android.widget.TextView
    private lateinit var rootLayout: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var ttsManager: TtsManager
    private lateinit var vibrationManager: VibrationManager
    private lateinit var ocrEngine: TesseractOcrEngine
    private lateinit var speechRecognitionManager: SpeechRecognitionManager
    private lateinit var repository: GuiaMedRepository
    private lateinit var touchDetector: TouchDetector
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var isFlashOn = false
    private var isProcessing = false
    private var hasAnnouncedStartup = false
    private var pendingSharedIntent: Intent? = null
    private var permissionFlowActive = false
    private val maxPackageReadAttempts = 3
    private val maxValidityReadAttempts = 4
    private var packageReadAttempt = 0
    private var validityReadAttempt = 0
    private var isScanningValidity = false
    private var identifiedPackageResponse: VerificarResponse? = null
    private val accumulatedPackageOcr = mutableListOf<String>()
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
        topPanel = findViewById(R.id.topPanel)
        statusText = findViewById(R.id.statusText)
        instructionText = findViewById(R.id.instructionText)
        rootLayout = findViewById(R.id.rootLayout)
        
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
        ocrEngine = TesseractOcrEngine(this)
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
                updateStatus("Permissões concedidas", isDanger = false)
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
                updateStatus(message, isDanger = true)
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

        updateStatus(rationale, isDanger = false)
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
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetRotation(previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luminosity ->
                // Auto flash baseado na luminosidade
                if (luminosity < 50 && !isFlashOn) {
                    toggleFlash(true)
                } else if (luminosity > 150 && isFlashOn) {
                    toggleFlash(false)
                }
            }) }

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
            onQuadTap = { repeatInstructions() },
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
    }

    private fun repeatInstructions() {
        isProcessing = false
        speechRecognitionManager.stopListening()
        updateStatus("Instruções", isDanger = false)
        ttsManager.speak(getString(R.string.repeat_instructions))
        vibrationManager.vibrateShort()
    }

    private fun openAppSettings() {
        isProcessing = false
        speechRecognitionManager.stopListening()
        updateStatus("Abrindo configurações", isDanger = false)
        vibrationManager.vibrateDoubleShort()
        ttsManager.speak("Abrindo configurações do aplicativo. Aqui você pode ativar as permissões necessárias.")
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun toggleFlash(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
        isFlashOn = on
    }

    private fun handleSingleTap() {
        if (isProcessing || permissionFlowActive) return
        isProcessing = true
        resetPackageReadSession()
        
        vibrationManager.vibrateShort()
        updateStatus("Focando embalagem...", isDanger = false)
        ttsManager.speakReadingPackage()
        focusCameraThen {
            updateStatus(getString(R.string.reading_package), isDanger = false)
            captureAndProcessImage()
        }
    }

    private fun handleDoubleTap() {
        if (isProcessing || permissionFlowActive) return
        isProcessing = true
        
        vibrationManager.vibrateDoubleShort()
        updateStatus("Modo receita", isDanger = false)
        ttsManager.speak(getString(R.string.prescription_mode), TtsManager.UTTERANCE_ID_DEFAULT) { success ->
            if (success) {
                focusCameraThen { capturePrescriptionPhoto() }
            } else {
                handleProcessingError("Não foi possível iniciar o modo receita. Tente novamente.")
            }
        }
    }

    private fun focusCameraThen(onReady: () -> Unit) {
        val activeCamera = camera
        if (activeCamera == null || previewView.width == 0 || previewView.height == 0) {
            mainScope.launch {
                kotlinx.coroutines.delay(350)
                onReady()
            }
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

        val focusFuture = activeCamera.cameraControl.startFocusAndMetering(action)
        focusFuture.addListener({
            mainScope.launch {
                // Evita que o toque para capturar produza uma foto ainda em movimento.
                kotlinx.coroutines.delay(300)
                onReady()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleTripleTap() {
        if (isProcessing || permissionFlowActive) return
        isProcessing = true
        
        vibrationManager.vibrateDoubleShort()
        updateStatus("Cadastro de alergia", isDanger = false)
        startAllergyVoiceRegistration()
    }

    private fun captureAndProcessImage() {
        val imageCapture = this.imageCapture ?: run {
            handleProcessingError("A câmera ainda não está pronta. Tente novamente em alguns segundos.")
            return
        }
        if (isScanningValidity) {
            validityReadAttempt += 1
        } else {
            packageReadAttempt += 1
        }
        
        val outputFile = createImageFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        
        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    processSavedImage(outputFile)
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.e("GuiaMed", "Erro ao capturar imagem", exc)
                    runOnUiThread {
                        handleProcessingError(getString(R.string.ocr_error))
                    }
                }
            }
        )
    }

    private fun createImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())
        return File(cacheDir, "IMG_${timestamp}.jpg")
    }

    private fun processSavedImage(file: File) {
        cameraScope.launch {
            try {
                val bitmap = decodeBitmapRespectingExif(file)
                if (bitmap == null) {
                    runOnUiThread { handleProcessingError(getString(R.string.ocr_error)) }
                    return@launch
                }

                val textoOcr = ocrEngine.recognizeTextFromBitmap(bitmap)
                bitmap.recycle()

                if (textoOcr.isBlank()) {
                    runOnUiThread {
                        if (isScanningValidity) {
                            continueValidityScanOrFinish()
                        } else {
                            retryPackageReadOrFinish(getString(R.string.no_text_found))
                        }
                    }
                    return@launch
                }

                if (textoOcr !in accumulatedPackageOcr) {
                    accumulatedPackageOcr.add(textoOcr)
                }
                val combinedOcr = accumulatedPackageOcr.joinToString("\n")

                // Envia para API
                val result = repository.verificarMedicamento(this@MainActivity, combinedOcr)
                
                runOnUiThread {
                    result.onSuccess { response ->
                        val identified = !response.nome.startsWith("Medicamento não identificado")
                        if (identified && response.validade != null) {
                            handleVerificarSuccess(response)
                        } else if (identified && !isScanningValidity) {
                            startValidityScan(response)
                        } else if (isScanningValidity) {
                            if (identified) identifiedPackageResponse = response
                            continueValidityScanOrFinish()
                        } else {
                            retryPackageReadOrFinish(response.mensagem ?: getString(R.string.no_text_found), response)
                        }
                    }.onFailure { e ->
                        handleProcessingError(e.message ?: getString(R.string.network_error))
                    }
                }
            } catch (e: Exception) {
                Log.e("GuiaMed", "Erro no processamento", e)
                runOnUiThread { handleProcessingError(getString(R.string.network_error)) }
            } finally {
                file.delete()
            }
        }
    }

    private fun startValidityScan(response: VerificarResponse) {
        identifiedPackageResponse = response
        isScanningValidity = true
        validityReadAttempt = 0
        updateStatus("${response.nome} — vire para a validade", isDanger = false)
        vibrationManager.vibrateDoubleShort()
        ttsManager.speak(
            "${response.nome} identificado. Agora vire lentamente a embalagem para a parte com a validade e mantenha parado.",
            TtsManager.UTTERANCE_ID_DEFAULT,
        ) {
            mainScope.launch {
                kotlinx.coroutines.delay(600)
                focusCameraThen {
                    updateStatus("Procurando validade — tentativa 1 de $maxValidityReadAttempts", isDanger = false)
                    captureAndProcessImage()
                }
            }
        }
    }

    private fun continueValidityScanOrFinish() {
        val identifiedResponse = identifiedPackageResponse
        if (validityReadAttempt >= maxValidityReadAttempts) {
            if (identifiedResponse != null) {
                handleVerificarSuccess(identifiedResponse)
            } else {
                handleProcessingError("Não consegui identificar o medicamento com segurança.")
            }
            return
        }

        val nextAttempt = validityReadAttempt + 1
        updateStatus("Procurando validade — tentativa $nextAttempt de $maxValidityReadAttempts", isDanger = false)
        vibrationManager.vibrateShort()
        ttsManager.speak(
            "Procurando a validade. Mova um pouco e pare novamente.",
            TtsManager.UTTERANCE_ID_DEFAULT,
        ) {
            mainScope.launch {
                kotlinx.coroutines.delay(450)
                focusCameraThen { captureAndProcessImage() }
            }
        }
    }

    private fun retryPackageReadOrFinish(message: String, finalResponse: VerificarResponse? = null) {
        if (packageReadAttempt >= maxPackageReadAttempts) {
            if (finalResponse != null) {
                handleVerificarSuccess(finalResponse)
            } else {
                handleProcessingError(message)
            }
            return
        }

        val nextAttempt = packageReadAttempt + 1
        val retryMessage = "Não ficou nítido. Ajustando foco, tentativa $nextAttempt de $maxPackageReadAttempts."
        updateStatus("Ajustando foco — tentativa $nextAttempt de $maxPackageReadAttempts", isDanger = false)
        vibrationManager.vibrateShort()
        ttsManager.speak(retryMessage)
        focusCameraThen {
            updateStatus(getString(R.string.reading_package), isDanger = false)
            captureAndProcessImage()
        }
    }

    private fun resetPackageReadSession() {
        packageReadAttempt = 0
        validityReadAttempt = 0
        isScanningValidity = false
        identifiedPackageResponse = null
        accumulatedPackageOcr.clear()
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

    private fun handleVerificarSuccess(response: VerificarResponse) {
        isProcessing = false
        resetPackageReadSession()
        
        val builder = StringBuilder()
        builder.append("Medicamento: ${response.nome}")
        response.miligramas?.let { builder.append(", ${it}") }
        response.validade?.let { builder.append(", Validade: $it") }
        response.posologia?.let { builder.append(". Posologia: $it") }
        response.mensagem?.let { builder.append(". $it") }
        
        var speechText = builder.toString()
        
        if (response.perigo || response.vencido || response.alertaAlergia) {
            updateStatus(response.nome, isDanger = true)
            
            if (response.alertaAlergia) {
                speechText = "${getString(R.string.allergy_alert)} $speechText"
                vibrationManager.vibrateAlert()
            } else if (response.vencido) {
                speechText = "${getString(R.string.expired_medicine)} $speechText"
                vibrationManager.vibrateAlert()
            } else {
                vibrationManager.vibrateTripleLong()
            }
        } else {
            updateStatus(response.nome, isDanger = false)
            vibrationManager.vibrateShort()
        }
        ttsManager.speak(speechText, TtsManager.UTTERANCE_ID_READING)
    }

    private fun handleProcessingError(message: String) {
        isProcessing = false
        resetPackageReadSession()
        updateStatus("Erro: $message", isDanger = true)
        ttsManager.speak(message)
        vibrationManager.vibrateTripleLong()
    }

    private fun capturePrescriptionPhoto() {
        val imageCapture = this.imageCapture ?: run {
            handleProcessingError("A câmera ainda não está pronta. Tente novamente em alguns segundos.")
            return
        }
        val outputFile = createImageFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        
        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    processPrescriptionImage(outputFile)
                }
                override fun onError(exc: ImageCaptureException) {
                    runOnUiThread { handleProcessingError(getString(R.string.ocr_error)) }
                }
            }
        )
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
        cameraScope.launch {
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                val textoOcr = ocrEngine.recognizeTextFromBitmap(bitmap)
                bitmap.recycle()
                
                if (textoOcr.isBlank()) {
                    runOnUiThread { handleProcessingError(getString(R.string.no_text_found)) }
                    return@launch
                }
                
                sendPrescriptionToApi(textoOcr)
            } catch (e: Exception) {
                runOnUiThread { handleProcessingError(getString(R.string.ocr_error)) }
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

    private fun processPrescriptionImage(file: File) {
        cameraScope.launch {
            try {
                val bitmap = decodeBitmapRespectingExif(file)
                    ?: throw IllegalStateException("Imagem inválida")
                val textoOcr = ocrEngine.recognizeTextFromBitmap(bitmap)
                bitmap.recycle()
                
                if (textoOcr.isBlank()) {
                    runOnUiThread { handleProcessingError(getString(R.string.no_text_found)) }
                    return@launch
                }
                
                sendPrescriptionToApi(textoOcr)
            } catch (e: Exception) {
                runOnUiThread { handleProcessingError(getString(R.string.ocr_error)) }
            } finally {
                file.delete()
            }
        }
    }

    private fun sendPrescriptionToApi(textoOcr: String) {
        cameraScope.launch {
            val result = repository.lerReceita(this@MainActivity, textoOcr)
            runOnUiThread {
                result.onSuccess { response ->
                    handlePrescriptionSuccess(response)
                }.onFailure { e ->
                    handleProcessingError(e.message ?: getString(R.string.network_error))
                }
            }
        }
    }

    private fun handlePrescriptionSuccess(response: ReceitaResponse) {
        isProcessing = false
        if (response.medicamentos.isEmpty()) {
            handleProcessingError(response.mensagem ?: "Nenhum medicamento foi identificado na receita.")
            return
        }
        updateStatus("Receita lida", isDanger = false)
        
        var speechText = "Receita médica: "
        response.medicamentos.forEachIndexed { index, med ->
            speechText += "${index + 1}. ${med.nome} ${med.dosagem}, ${med.frequencia}"
            med.duracao?.let { speechText += " por $it" }
            med.instrucoes?.let { speechText += ". $it" }
            speechText += ". "
        }
        response.horarios.forEach { speechText += "Horário: $it. " }
        
        ttsManager.speak(speechText, TtsManager.UTTERANCE_ID_READING)
        vibrationManager.vibrateDoubleShort()
    }

    private fun startAllergyVoiceRegistration() {
        ttsManager.speak(getString(R.string.allergy_mode), TtsManager.UTTERANCE_ID_DEFAULT) { success ->
            if (success) {
                launchRecognition()
            } else {
                handleProcessingError("Não foi possível preparar o cadastro por voz.")
            }
        }
    }

    private fun launchRecognition() {
        val deferred = speechRecognitionManager.startListening {
            updateStatus("Pode falar agora", isDanger = false)
            vibrationManager.vibrateDoubleShort()
        }
        mainScope.launch {
            try {
                val recognizedText = withTimeout(20_000L) { deferred.await() }
                if (recognizedText.isBlank()) {
                    handleProcessingError("Não entendi. Tente novamente.")
                    return@launch
                }
                updateStatus("Registrando alergia", isDanger = false)
                val result = repository.registrarAlergia(this@MainActivity, recognizedText)
                result.onSuccess { response ->
                    if (response.sucesso) {
                        updateStatus("Alergia registrada", isDanger = false)
                        ttsManager.speak(response.mensagem)
                        vibrationManager.vibrateDoubleShort()
                    } else {
                        handleProcessingError(response.mensagem)
                    }
                }.onFailure { error ->
                    handleProcessingError(error.message ?: getString(R.string.allergy_error))
                }
            } catch (e: Exception) {
                handleProcessingError(e.message ?: getString(R.string.allergy_error))
            } finally {
                speechRecognitionManager.stopListening()
                isProcessing = false
            }
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

    private fun updateStatus(message: String, isDanger: Boolean) {
        topPanel.setBackgroundColor(
            ContextCompat.getColor(this, if (isDanger) R.color.red_danger else R.color.black)
        )
        statusText.setTextAppearance(
            if (isDanger) R.style.TextAppearance_GuiaMed_Danger else R.style.TextAppearance_GuiaMed_HighContrast
        )
        instructionText.setTextColor(
            ContextCompat.getColor(this, if (isDanger) R.color.white else R.color.yellow_high_contrast)
        )
        statusText.text = message
    }

    private fun announceStartupIfNeeded() {
        if (hasAnnouncedStartup || isProcessing || permissionFlowActive) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        hasAnnouncedStartup = true
        ttsManager.addInitializationListener { success ->
            if (success) {
                mainScope.launch {
                    kotlinx.coroutines.delay(750)
                    if (!isProcessing) ttsManager.speakStartupMessage()
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
        speechRecognitionManager.stopListening()
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

// Analisador de luminosidade para auto-flash
class LuminosityAnalyzer(private val onLuminosity: (Int) -> Unit) : ImageAnalysis.Analyzer {
    override fun analyze(imageProxy: ImageProxy) {
        try {
            val buffer = imageProxy.planes[0].buffer
            val pixels = ByteArray(buffer.remaining())
            buffer.get(pixels)
            
            // Calcula luminosidade média (amostragem)
            var sum = 0L
            var count = 0
            for (i in pixels.indices step 100) {
                sum += (pixels[i].toInt() and 0xFF).toLong()
                count++
            }
            val avgLuminosity = if (count > 0) (sum / count).toInt() else 128
            
            onLuminosity(avgLuminosity)
        } finally {
            imageProxy.close()
        }
    }
}
