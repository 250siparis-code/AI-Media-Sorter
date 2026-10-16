package com.aimediasorter.app.domain.cloud

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest

/** Analysis reads only the selected URI. Provider-redacted pixels are sufficient.
 * This is NEVER an original-byte transfer accessor or proof of byte-preserving COPY/MOVE.
 * OriginalMediaAccess remains strict and independent.
 */
class SelectedMediaAccess(private val context: Context) {
    fun input(uri: Uri): InputStream=context.contentResolver.openInputStream(uri)
        ?: throw CloudFailure(CloudErrorKind.RESPONSE,AnalysisCode.LOCAL_SOURCE_ACCESS)

    suspend fun fingerprint(uri: Uri): String {
        return input(uri).use { stream ->
            val hash=MessageDigest.getInstance("SHA-256")
            val buffer=ByteArray(65536)
            while(true) {
                currentCoroutineContext().ensureActive()
                val n=stream.read(buffer)
                if(n<0) break
                hash.update(buffer,0,n)
            }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
