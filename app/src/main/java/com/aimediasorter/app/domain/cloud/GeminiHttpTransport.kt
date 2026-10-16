package com.aimediasorter.app.domain.cloud

import com.aimediasorter.app.domain.diagnostics.*
import kotlinx.coroutines.currentCoroutineContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Fixed official HTTPS origin; no redirects, URL credentials, logging, or raw exceptions. */
class GeminiHttpTransport(private val openConnection: (URL)->HttpURLConnection = { it.openConnection() as HttpURLConnection }) : CloudTransport {
    companion object {
        private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r,"MediaGemini").apply { isDaemon=true } }
        private val pathPattern=Regex("(models\\?pageSize=100(&pageToken=[A-Za-z0-9%+._~-]+)?|models/gemini-[A-Za-z0-9._-]+:generateContent)")
    }
    override suspend fun request(path: String,apiKey: String,body: String?): CloudHttpResponse =
        exchange(path,apiKey,body,{})
    override suspend fun request(path: String,apiKey: String,body: String,onSent: ()->Unit): CloudHttpResponse =
        exchange(path,apiKey,body,onSent)
    private suspend fun exchange(path: String, apiKey: String, body: String?,onSent: ()->Unit): CloudHttpResponse {
        require(pathPattern.matches(path))
        require(ApiKeyInput.canSave(apiKey) && apiKey == ApiKeyInput.normalized(apiKey))
        val diagnostic=currentCoroutineContext()[DiagnosticTraceContext]?.operation
        diagnostic?.event(DiagnosticStage.TRANSPORT_CONNECT,DiagnosticStatus.STARTED)
        return suspendCancellableCoroutine { continuation ->
            val active=AtomicReference<HttpURLConnection?>()
            val future=executor.submit {
                try {
                    if(!continuation.isActive) return@submit
                    val c=openConnection(URL("https://generativelanguage.googleapis.com/v1beta/$path"))
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
                        // Count only after the complete body was written and flushed. No claim of Google receipt.
                        onSent()
                    }
                    val code=c.responseCode
                    diagnostic?.event(DiagnosticStage.HTTP_HEADERS,DiagnosticStatus.SUCCEEDED,http=code)
                    val stream=if(code in 200..299) c.inputStream else c.errorStream
                    val data=stream?.use { it.readNBytes(131_073) } ?: byteArrayOf()
                    if(data.size>131_072) throw CloudFailure(CloudErrorKind.RESPONSE,AnalysisCode.RESPONSE_TOO_LARGE)
                    if(continuation.isActive) continuation.resume(CloudHttpResponse(code,data.toString(Charsets.UTF_8),
                        c.getHeaderField("Retry-After")?.toLongOrNull() ?: 0))
                } catch (_: java.net.SocketTimeoutException) {
                    if(continuation.isActive) continuation.resumeWithException(CloudFailure(CloudErrorKind.TIMEOUT))
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
