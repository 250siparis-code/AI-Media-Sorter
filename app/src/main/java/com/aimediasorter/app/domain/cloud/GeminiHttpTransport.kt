package com.aimediasorter.app.domain.cloud

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Fixed official HTTPS origin; no redirects, URL credentials, logging, or raw exceptions. */
class GeminiHttpTransport : CloudTransport {
    companion object {
        private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r,"MediaGemini").apply { isDaemon=true } }
        private val pathPattern=Regex("(models\\?pageSize=100(&pageToken=[A-Za-z0-9%+._~-]+)?|models/gemini-[A-Za-z0-9._-]+:generateContent)")
    }
    override suspend fun request(path: String, apiKey: String, body: String?): CloudHttpResponse {
        require(pathPattern.matches(path))
        require(Regex("[A-Za-z0-9_-]{20,256}").matches(apiKey))
        return suspendCancellableCoroutine { continuation ->
            val active=AtomicReference<HttpURLConnection?>()
            val future=executor.submit {
                try {
                    if(!continuation.isActive) return@submit
                    val c=URL("https://generativelanguage.googleapis.com/v1beta/$path").openConnection() as HttpURLConnection
                    active.set(c); c.instanceFollowRedirects=false
                    c.connectTimeout=10_000; c.readTimeout=30_000
                    c.setRequestProperty("x-goog-api-key",apiKey)
                    c.setRequestProperty("Content-Type","application/json")
                    c.requestMethod=if(body==null) "GET" else "POST"
                    if(body!=null) {
                        c.doOutput=true; val bytes=body.toByteArray(Charsets.UTF_8)
                        c.setFixedLengthStreamingMode(bytes.size)
                        c.outputStream.use { stream ->
                            for(offset in bytes.indices step 8192) {
                                if(!continuation.isActive) return@submit
                                stream.write(bytes,offset,minOf(8192,bytes.size-offset))
                            }
                        }
                    }
                    val code=c.responseCode
                    val stream=if(code in 200..299) c.inputStream else c.errorStream
                    val data=stream?.use { it.readNBytes(131_073) } ?: byteArrayOf()
                    if(data.size>131_072) throw CloudFailure(CloudErrorKind.RESPONSE)
                    if(continuation.isActive) continuation.resume(CloudHttpResponse(code,data.toString(Charsets.UTF_8),
                        c.getHeaderField("Retry-After")?.toLongOrNull() ?: 0))
                } catch (known: CloudFailure) {
                    if(continuation.isActive) continuation.resumeWithException(known)
                } catch (_: Exception) {
                    if(continuation.isActive) continuation.resumeWithException(CloudFailure(CloudErrorKind.NETWORK))
                } finally { active.getAndSet(null)?.disconnect() }
            }
            continuation.invokeOnCancellation { active.getAndSet(null)?.disconnect();future.cancel(true) }
        }
    }
}
