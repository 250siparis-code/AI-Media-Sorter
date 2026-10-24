package com.aimediasorter.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimediasorter.app.domain.cloud.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Exercises the production HTTP builder with an injected in-memory connection, never the network. */
@RunWith(AndroidJUnit4::class)
class TransportDiagnosticsTest {
    private class Connection(url: URL,val beforeWrite: Boolean=false,val timeoutAfterWrite: Boolean=false,
        val status: Int=200,val oversized: Boolean=false): HttpURLConnection(url) {
        val uploaded=ByteArrayOutputStream()
        var bodyClosed=false
        var disconnected=false
        override fun connect() {}
        override fun disconnect() { disconnected=true }
        override fun usingProxy()=false
        override fun getOutputStream(): java.io.OutputStream {
            if(beforeWrite) throw IOException("Synthetic pre-upload network failure")
            return object: java.io.FilterOutputStream(uploaded) {
                override fun close() { super.close();bodyClosed=true }
            }
        }
        override fun getResponseCode(): Int {
            if(timeoutAfterWrite) throw SocketTimeoutException("Synthetic uncertain outcome")
            return status
        }
        override fun getInputStream()=ByteArrayInputStream(if(oversized) ByteArray(131_073) else "{}".toByteArray())
        override fun getErrorStream()=ByteArrayInputStream("""{"error":{"message":"DO_NOT_EXPOSE_PROVIDER_BODY"}}""".toByteArray())
        override fun getHeaderField(name: String?)=if(name=="Retry-After") "2" else null
    }
    @Test fun productionHttpUsesOfficialPathJpegJsonAndHeaderOnlyCredential()=runBlocking {
        val key="AQ.SyntheticTransportFixtureCredential"
        lateinit var c: Connection
        val transport=GeminiHttpTransport { url ->
            assertEquals("https",url.protocol);assertEquals("generativelanguage.googleapis.com",url.host)
            assertEquals("/v1beta/models/gemini-test:generateContent",url.path);assertNull(url.query)
            Connection(url).also { c=it }
        }
        var sent=0
        transport.request("models/gemini-test:generateContent",key,"{}") {
            assertTrue(c.bodyClosed);sent++
        }
        assertEquals(1,sent);assertEquals("POST",c.requestMethod)
        assertEquals("application/json",c.getRequestProperty("Content-Type"))
        assertTrue(c.getRequestProperty("x-goog-api-key")==key)
        assertFalse(c.instanceFollowRedirects)
        assertEquals(10_000,c.connectTimeout);assertEquals(30_000,c.readTimeout)
        assertEquals("{}",c.uploaded.toString("UTF-8"))
    }
    @Test fun failureBeforeUploadDoesNotCountAsACompletedPost()=runBlocking {
        var sent=0
        val transport=GeminiHttpTransport { Connection(it,beforeWrite=true) }
        try { transport.request("models/gemini-test:generateContent","AQ.SyntheticTransportCredential","{}") { sent++ };fail() }
        catch(e: CloudFailure) { assertEquals(AnalysisCode.NETWORK,e.diagnostic) }
        assertEquals(0,sent)
    }
    @Test fun timeoutAfterCompleteUploadIsCountedAndOutcomeRemainsUncertain()=runBlocking {
        var sent=0
        val transport=GeminiHttpTransport { Connection(it,timeoutAfterWrite=true) }
        try { transport.request("models/gemini-test:generateContent","AQ.SyntheticTransportCredential","{}") { sent++ };fail() }
        catch(e: CloudFailure) { assertEquals(AnalysisCode.TIMEOUT,e.diagnostic) }
        assertEquals(1,sent)
    }
    @Test fun quotaHttpReplyAndOversizedResponseBothCountCompletedUploads()=runBlocking {
        var sent=0
        val quota=GeminiHttpTransport { Connection(it,status=429) }
        assertEquals(429,quota.request("models/gemini-test:generateContent","AQ.SyntheticTransportCredential","{}") { sent++ }.code)
        val large=GeminiHttpTransport { Connection(it,oversized=true) }
        try { large.request("models/gemini-test:generateContent","AQ.SyntheticTransportCredential","{}") { sent++ };fail() }
        catch(e: CloudFailure) { assertEquals(AnalysisCode.RESPONSE_TOO_LARGE,e.diagnostic) }
        assertEquals(2,sent)
    }
}
