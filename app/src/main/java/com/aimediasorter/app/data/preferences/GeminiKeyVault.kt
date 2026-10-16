package com.aimediasorter.app.data.preferences

import com.aimediasorter.app.domain.cloud.ApiKeyInput
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The secret never enters AppSettings, WorkData, logs, Room, or diagnostics. */
class GeminiKeyVault(context: Context, namespace: String = "user") {
    init { require(Regex("[A-Za-z0-9_-]{1,80}").matches(namespace)) }
    private val file=AtomicFile(File(context.noBackupFilesDir,"gemini-key-"+namespace+".v1"))
    private val metadata=context.getSharedPreferences("gemini-key-metadata-"+namespace,Context.MODE_PRIVATE)
    private val alias="com.aimediasorter.gemini."+namespace+".v1"
    // AtomicFile has no locking: openRead() may remove a concurrent writer's .new file.
    // Every vault instance in this app process must share the same canonical-file lock.
    private val fileLock=locks.computeIfAbsent(file.baseFile.canonicalPath) { Any() }
    val revision: Int get()=synchronized(fileLock) { metadata.getInt("revision",0) }
    fun hasKey()=synchronized(fileLock) { file.baseFile.exists() }
    private companion object {
        val locks=ConcurrentHashMap<String,Any>()
    }
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun read(): String = synchronized(fileLock) {
        val bytes=file.readFully()
        require(bytes.size in 29..8192)
        val n=bytes[0].toInt() and 255;require(n==12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(1,n+1)))
        cipher.doFinal(bytes.copyOfRange(n+1,bytes.size)).toString(Charsets.UTF_8)
    }
    class Snapshot(val secret: String, val revision: Int) {
        override fun toString() = "GeminiCredential(redacted)"
    }
    fun snapshot() = synchronized(fileLock) { Snapshot(read(),revision) }
    fun save(value: String) { synchronized(fileLock) {
        val normalized = ApiKeyInput.requireValid(value)
        if(runCatching { read()==normalized }.getOrDefault(false)) return
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val data=byteArrayOf(cipher.iv.size.toByte())+cipher.iv+cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        // Rotate the non-secret epoch BEFORE publication so a crash cannot authorize a new key with an old grant.
        check(metadata.edit().putInt("revision",revision+1).commit())
        val stream=file.startWrite()
        try { stream.write(data);file.finishWrite(stream) } catch(e: Exception) { file.failWrite(stream);throw e }
    } }
    fun clear() { synchronized(fileLock) {
        check(metadata.edit().putInt("revision",revision+1).commit())
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) }
    } }
}
