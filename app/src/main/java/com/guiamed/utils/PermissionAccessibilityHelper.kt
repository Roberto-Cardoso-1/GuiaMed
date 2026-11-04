package com.guiamed.utils

import android.content.Context
import com.guiamed.R
import com.guiamed.speech.TtsManager

/**
 * Helper para tornar dialogs de permissão acessíveis para deficientes visuais.
 * 
 * Anuncia por TTS antes de mostrar o dialog do sistema.
 */
class PermissionAccessibilityHelper(private val context: Context, private val ttsManager: TtsManager) {

    /**
     * Solicita permissão com anúncio por voz e instruções de acessibilidade
     */
    fun requestPermissionWithVoiceGuidance(
        permission: String,
        rationale: String,
        onResult: (Boolean) -> Unit
    ) {
        // 1. Anuncia qual permissão será pedida e POR QUÊ
        val announcement = rationale
        ttsManager.speak(announcement, "permission_announcement_$permission") { _ ->
            // 2. Após falar, instrui COMO confirmar (usando string específica)
            val instruction = getPermissionInstruction(permission)
            ttsManager.speak(instruction, "permission_instruction_$permission") { _ ->
                // 3. Pequeno delay para o usuário processar
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    actuallyRequestPermission(permission, onResult)
                }, 1500)
            }
        }
    }

    private fun getPermissionInstruction(permission: String): String {
        return when (permission) {
            android.Manifest.permission.CAMERA -> context.getString(R.string.permission_request_camera)
            android.Manifest.permission.RECORD_AUDIO -> context.getString(R.string.permission_request_audio)
            else -> "Quando o alerta do sistema aparecer, confirme a permissão."
        }
    }

    private fun actuallyRequestPermission(permission: String, onResult: (Boolean) -> Unit) {
        (context as? PermissionRequester)?.requestPermission(permission, onResult)
    }

    /**
     * Interface para a Activity implementar
     */
    interface PermissionRequester {
        fun requestPermission(permission: String, onResult: (Boolean) -> Unit)
    }
}
