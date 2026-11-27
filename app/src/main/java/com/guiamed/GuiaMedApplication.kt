package com.guiamed

import android.app.Application
import com.guiamed.data.DeviceIdRepository
import com.guiamed.data.OfflineMedicationProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GuiaMedApplication : Application() {

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Immutable snapshots: basic catalog immediately, DCB before the larger EAN index.
    @Volatile internal var medicationProcessor = OfflineMedicationProcessor(emptyList())
        private set

    override fun onCreate() {
        super.onCreate()
        // Garante que o device_id existe ao iniciar o app
        startupScope.launch {
            val names = OfflineMedicationProcessor.loadDcbNames(this@GuiaMedApplication)
            medicationProcessor = OfflineMedicationProcessor(names)
            val ean = OfflineMedicationProcessor.loadEanNames(this@GuiaMedApplication)
            medicationProcessor = OfflineMedicationProcessor(names, ean)
        }
        ensureDeviceIdExists()
    }

    private fun ensureDeviceIdExists() {
        startupScope.launch {
            DeviceIdRepository.getOrCreateDeviceId(this@GuiaMedApplication)
        }
    }
}
