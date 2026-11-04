package com.guiamed.data

import android.content.Context
import android.provider.Settings.Secure
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.guiamed.GuiaMedApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.dataStore by preferencesDataStore(name = "settings")

object DeviceIdRepository {

    private val DEVICE_ID_KEY = stringPreferencesKey("device_id")

    suspend fun getOrCreateDeviceId(context: Context): String {
        return withContext(Dispatchers.IO) {
            val currentId = context.dataStore.data
                .map { preferences -> preferences[DEVICE_ID_KEY] }
                .first()

            currentId ?: run {
                val newId = generateDeviceId(context)
                context.dataStore.edit { preferences ->
                    preferences[DEVICE_ID_KEY] = newId
                }
                newId
            }
        }
    }

    private fun generateDeviceId(context: Context): String {
        // Usa Android ID + timestamp + random para garantir unicidade
        val androidId = Secure.getString(context.contentResolver, Secure.ANDROID_ID) ?: ""
        val timestamp = System.currentTimeMillis()
        val random = (Math.random() * 10000).toInt()
        return "GM_${androidId}_${timestamp}_${random}".replace(" ", "_")
    }

    suspend fun clearDeviceId(context: Context) {
        withContext(Dispatchers.IO) {
            context.dataStore.edit { preferences ->
                preferences.remove(DEVICE_ID_KEY)
            }
        }
    }
}