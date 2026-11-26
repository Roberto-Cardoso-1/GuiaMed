package com.guiamed

import android.app.Application
import com.guiamed.data.DeviceIdRepository
import com.guiamed.data.OfflineMedicationProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class GuiaMedApplication : Application() {

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val medicationProcessor by lazy {
        startupScope.async { OfflineMedicationProcessor.fromContext(this@GuiaMedApplication) }
    }

    override fun onCreate() {
        super.onCreate()
        // Garante que o device_id existe ao iniciar o app
        medicationProcessor
        ensureDeviceIdExists()
    }

    private fun ensureDeviceIdExists() {
        startupScope.launch {
            DeviceIdRepository.getOrCreateDeviceId(this@GuiaMedApplication)
        }
    }
}
