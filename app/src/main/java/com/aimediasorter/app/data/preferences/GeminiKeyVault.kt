package com.aimediasorter.app.data.preferences

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
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
    val revision: Int get()=metadata.getInt("revision",0)
    @Synchronized fun hasKey()=file.baseFile.exists()
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun read(): String {
        val bytes=file.readFully()
        require(bytes.size in 29..1024)
        val n=bytes[0].toInt() and 255;require(n==12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(1,n+1)))
        return cipher.doFinal(bytes.copyOfRange(n+1,bytes.size)).toString(Charsets.UTF_8)
    }
    @Synchronized fun save(value: String) {
        require(Regex("[A-Za-z0-9_-]{20,256}").matches(value))
        if(runCatching { read()==value }.getOrDefault(false)) return
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val data=byteArrayOf(cipher.iv.size.toByte())+cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val stream=file.startWrite()
        try { stream.write(data);file.finishWrite(stream) } catch(e: Exception) { file.failWrite(stream);throw e }
        check(metadata.edit().putInt("revision",revision+1).commit())
    }
    @Synchronized fun clear() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) }
        check(metadata.edit().putInt("revision",revision+1).commit())
    }
}
