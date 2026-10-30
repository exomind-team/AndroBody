package org.exomind.androbody.nativeapp.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import org.exomind.androbody.nativeapp.core.readAtMost
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Own namespace: legacy DSH/model-vault files are deliberately never opened or deleted. */
class EncryptedStore(
    context: Context,
    name: String,
    private val storageRoot: File = File(context.filesDir, "native-v1"),
) {
    init { require(name.matches(STORE_NAME)) { "Invalid encrypted store name" } }
    private val base = File(storageRoot, "$name.bin").apply { parentFile?.mkdirs() }
    private val file = AtomicFile(base)
    @Synchronized fun read(): JSONObject? {
        if (!file.baseFile.exists()) return null
        val bytes = file.openRead().use { it.readAtMost(MAX_RECORD_BYTES + 30) }
        check(bytes.size in 29..(MAX_RECORD_BYTES + 29) && bytes[0] == 1.toByte()) { "加密存储格式无效" }
        val key = key(create = false) ?: error("本机加密密钥缺失；未清空原数据")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        }
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
    }
    @Synchronized fun write(value: JSONObject) {
        val plain = value.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_RECORD_BYTES) { "加密记录超过上限" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(create = true)) }
        val stream = file.startWrite()
        try {
            stream.write(byteArrayOf(1) + cipher.iv + cipher.doFinal(plain))
            file.finishWrite(stream)
        } catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    @Synchronized fun delete(): Boolean {
        file.delete()
        return !base.exists() && !File(base.path + ".bak").exists()
    }
    @Synchronized fun storedByteCount(): Long? = base.takeIf(File::exists)?.length()
    private fun key(create: Boolean): SecretKey? = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: if (!create) null else
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }
    companion object {
        const val MAX_RECORD_BYTES = 4 * 1024 * 1024
        private val STORE_NAME = Regex("[a-z0-9][a-z0-9-]{0,79}")
        private const val ALIAS = "andromorph.native.v1"
        private val KEY_LOCK = Any()
    }
}
