package com.huigu.phone10.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class MobileSettings(
    val speech: SpeechConfig,
    val chatId: String = "",
    val chatTitle: String = "",
    val overlayEnabled: Boolean = false,
    val smartEndpoint: Boolean = false,
    val endJudge: EndJudgeConfig? = null,
    val voiceName: String? = null,
    val disableVoiceInterruption: Boolean = false,
)

/** User-owned credentials stay encrypted in this app's non-backup storage. */
class SettingsStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "voice-settings.bin"))
    private val gson = Gson()

    fun load(): MobileSettings {
        if (!file.baseFile.exists()) return MobileSettings(SpeechConfig.bailianDefaults())
        try {
            val bytes = file.readFully()
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return gson.fromJson(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8), MobileSettings::class.java)
        } catch (_: Exception) {
            throw IllegalStateException("语音配置无法解密，请重新填写并保存。")
        }
    }

    fun save(settings: MobileSettings) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(gson.toJson(settings).toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object { private const val ALIAS = "phone10-mobile-voice-settings-v1" }
}
