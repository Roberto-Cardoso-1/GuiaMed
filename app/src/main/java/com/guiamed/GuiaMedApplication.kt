package com.guiamed

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.guiamed.data.DeviceIdRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GuiaMedApplication : Application() {

    companion object {
        private const val DEVICE_ID_KEY = "device_id"
        private const val ALLERGIES_KEY = "cached_allergies"
        val Context.dataStore by preferencesDataStore("guiamed_prefs")
    }

    override fun onCreate() {
        super.onCreate()
        // Garante que o device_id existe ao iniciar o app
        ensureDeviceIdExists()
    }

    private fun ensureDeviceIdExists() {
        CoroutineScope(Dispatchers.IO).launch {
            val deviceId = DeviceIdRepository.getOrCreateDeviceId(this@GuiaMedApplication)
        }
    }
}