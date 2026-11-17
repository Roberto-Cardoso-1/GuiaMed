package com.guiamed

import android.app.Application
import com.guiamed.data.DeviceIdRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GuiaMedApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Garante que o device_id existe ao iniciar o app
        ensureDeviceIdExists()
    }

    private fun ensureDeviceIdExists() {
        CoroutineScope(Dispatchers.IO).launch {
            DeviceIdRepository.getOrCreateDeviceId(this@GuiaMedApplication)
        }
    }
}
