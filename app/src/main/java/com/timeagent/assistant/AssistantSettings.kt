package com.timeagent.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Key provider is injected for a real AES/GCM test without depending on device Keystore hardware. */
class AssistantSecretCipher(private val keyProvider: () -> SecretKey = ::androidKey) {
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        require(iv.size == 12)
        return byteArrayOf(1, iv.size.toByte()) + iv + cipher.doFinal(plain)
    }
    fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size > 30 && encrypted[0].toInt() == 1 && encrypted[1].toInt() == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, encrypted.copyOfRange(2, 14)))
        return cipher.doFinal(encrypted.copyOfRange(14, encrypted.size))
    }
    companion object {
        private const val ALIAS = "timeagent-deepseek-key-v1"
        @Synchronized private fun androidKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
    }
}

class AssistantSettings(context: Context, private val cipher: AssistantSecretCipher = AssistantSecretCipher()) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "assistant-key.bin"))
    private val preferences = context.applicationContext.getSharedPreferences("assistant-settings", Context.MODE_PRIVATE)
    fun model(): String = preferences.getString("model", DeepSeekProtocol.DEFAULT_MODEL)?.takeIf(DeepSeekProtocol::validModel) ?: DeepSeekProtocol.DEFAULT_MODEL
    fun hasApiKey(): Boolean = file.baseFile.exists()
    fun cloudEnabled(): Boolean = preferences.getBoolean("conversation-cloud", false)
    fun setCloudEnabled(enabled: Boolean) { check(preferences.edit().putBoolean("conversation-cloud", enabled).commit()) }
    suspend fun saveModel(model: String) = withContext(Dispatchers.IO) {
        require(DeepSeekProtocol.validModel(model)) { "模型名仅支持字母、数字、点、横线和下划线。" }
        check(preferences.edit().putString("model", model).commit()) { "配置保存失败，请重试。" }
    }
    suspend fun saveApiKey(apiKey: String) = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        require(key.isNotBlank() && key.length <= 512 && key.none { it == '\r' || it == '\n' }) { "API Key 格式无效。" }
        val plain = key.toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.encrypt(plain) } finally { plain.fill(0) }
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (_: Exception) { file.failWrite(output); throw IllegalStateException("密钥保存失败，请重试。") }
    }
    suspend fun readApiKey(): String = withContext(Dispatchers.IO) {
        if (!hasApiKey()) return@withContext ""
        try {
            val plain = cipher.decrypt(file.readFully())
            try { plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
        } catch (_: Exception) { throw IllegalStateException("设备上的密钥无法解密，请删除后重新填写。") }
    }
    suspend fun clearApiKey() = withContext(Dispatchers.IO) {
        file.delete()
        check(!file.baseFile.exists()) { "密钥删除失败，请重试。" }
    }
}
