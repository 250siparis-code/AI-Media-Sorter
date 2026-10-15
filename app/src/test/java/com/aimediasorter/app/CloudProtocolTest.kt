package com.aimediasorter.app
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.preferences.AppSettings
import com.aimediasorter.app.domain.cloud.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Mocked protocol/policy tests; no live requests or accuracy claims. */
class CloudProtocolTest {
    private val categories=listOf(CategoryEntity("food","Food","Main dish",0,"label",1,destinationSubfolder="Food"),
        CategoryEntity("people","People","Main person",0,"label",1,destinationSubfolder="People"))
    private val options=CloudOptions(mode=AnalysisMode.GEMINI,model="models/gemini-test",
        estimatedMicrosPerAttempt=100,maxEstimatedBatchMicros=1000)
    private val image=listOf(CloudImage(byteArrayOf(1,2,3)))
    private val decision="""{"category":"food","short_description":"A meal","alternative_categories":[],"review_status":"CLEAR"}"""
    private fun response(d: String=decision,finish: String="STOP")=buildJsonObject {
        put("candidates",buildJsonArray { add(buildJsonObject {
            put("finishReason",finish)
            putJsonObject("content") { put("parts",buildJsonArray { add(buildJsonObject { put("text",d) }) }) }
        }) })
        putJsonObject("usageMetadata") { put("promptTokenCount",10);put("candidatesTokenCount",7);put("thoughtsTokenCount",3) }
    }.toString()
    private fun failure(kind: CloudErrorKind,block: ()->Unit) {
        try { block();fail("Expected a safe failure") } catch(e: CloudFailure) { assertEquals(kind,e.kind) }
    }
    @Test fun defaultIsOfflineWithoutCloudConfiguration() {
        assertEquals(AnalysisMode.OFFLINE,AppSettings().cloudOptions.mode)
        assertFalse(CloudOptions().ready())
    }
    @Test fun structuredRequestContainsConfiguredIdsImagesAndNoCredential() {
        val body=CloudProtocol.request(categories,image,options)
        val root=Json.parseToJsonElement(body).jsonObject
        assertEquals("application/json",root["generationConfig"]!!.jsonObject["responseMimeType"]!!.jsonPrimitive.content)
        assertTrue(body.contains("\"food\""));assertTrue(body.contains("inlineData"))
        assertFalse(body.contains("apiKey"));assertFalse(body.contains("confidence\":"))
    }
    @Test fun validResponseReportsTokensWithoutInventingConfidence() {
        val result=CloudProtocol.parseResponse(response(),setOf("food","people"))
        assertEquals("food",result.decision.category)
        assertEquals(10,result.inputTokens);assertEquals(10,result.outputTokens)
    }
    @Test fun unknownCategoryIsRejected() {
        failure(CloudErrorKind.RESPONSE) { CloudProtocol.parseResponse(response(decision.replace("\"food\"","\"invented\"")),setOf("food")) }
    }
    @Test fun addedConfidenceFieldIsRejected() {
        failure(CloudErrorKind.RESPONSE) { CloudProtocol.parseResponse(response(decision.dropLast(1)+""","confidence":0.99}"""),setOf("food")) }
    }
    @Test fun nonStringDescriptionsAreRejected() {
        failure(CloudErrorKind.RESPONSE) { CloudProtocol.parseResponse(response(decision.replace("\"A meal\"","8")),setOf("food")) }
    }
    @Test fun duplicateOrUnconfiguredAlternativesAreRejected() {
        for(alts in listOf("[\"food\"]","[\"people\",\"people\"]","[\"unknown\"]")) {
            failure(CloudErrorKind.RESPONSE) { CloudProtocol.parseResponse(response(decision.replace("[]",alts)),setOf("food","people")) }
        }
    }
    @Test fun unknownSubjectRetainsExplicitReviewSentinel() {
        assertEquals("__review__",CloudProtocol.parseResponse(response(decision.replace("\"food\"","\"__review__\"").replace("CLEAR","REQUIRED")),setOf("food")).decision.category)
    }
    @Test fun truncatedAndBlockedResponsesFailClosed() {
        failure(CloudErrorKind.RESPONSE) { CloudProtocol.parseResponse(response(finish="MAX_TOKENS"),setOf("food")) }
        failure(CloudErrorKind.BLOCKED) { CloudProtocol.parseResponse(response(finish="SAFETY"),setOf("food")) }
        failure(CloudErrorKind.RESPONSE) { CloudProtocol.parseResponse("not JSON",setOf("food")) }
    }
    @Test fun modelDiscoveryUsesAccessiblePaginatedGenerateContentModels() = runBlocking {
        val paths=mutableListOf<String>()
        val client=GeminiClient(CloudTransport { p,_,body ->
            paths+=p;assertNull(body)
            if(paths.size==1) CloudHttpResponse(200,"""{"models":[{"name":"models/gemini-accessible","supportedGenerationMethods":["generateContent"]},{"name":"models/embedding","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"next"}""")
            else CloudHttpResponse(200,"""{"models":[{"name":"models/gemini-second","supportedGenerationMethods":["generateContent"]}]}""")
        })
        assertEquals(listOf("models/gemini-accessible","models/gemini-second"),client.models("test-key"))
        assertTrue(paths[1].contains("pageToken=next"))
    }
    @Test fun retriesReserveEveryAttemptAndHonorBackoff() = runBlocking {
        var requests=0;var reserved=0;val waits=mutableListOf<Long>()
        val client=GeminiClient(CloudTransport { _,_,_ ->
            requests++;if(requests==1) CloudHttpResponse(429,"quota",2) else CloudHttpResponse(200,response())
        },{waits+=it})
        val result=client.classify("test-key",options,categories,image) { reserved++ }
        assertEquals(2,requests);assertEquals(2,reserved);assertEquals(listOf(2000L),waits)
        assertEquals("food",result.decision.category)
    }
    @Test fun localBudgetFailurePreventsNetworkCall() = runBlocking {
        var requests=0
        val client=GeminiClient(CloudTransport { _,_,_ -> requests++;CloudHttpResponse(200,response()) })
        failure(CloudErrorKind.BUDGET) { runBlocking {
            client.classify("test-key",options,categories,image) { throw CloudFailure(CloudErrorKind.BUDGET) }
        } }
        assertEquals(0,requests)
    }
    @Test fun authBillingAndExhaustedQuotaAreClearAndSanitized() = runBlocking {
        for((code,body,kind) in listOf(Triple(401,"secret-user-key",CloudErrorKind.AUTH),
            Triple(403,"billing secret-user-key",CloudErrorKind.BILLING),Triple(429,"quota",CloudErrorKind.QUOTA))) {
            val client=GeminiClient(CloudTransport { _,_,_ -> CloudHttpResponse(code,body) },{})
            try { client.classify("secret-user-key",options.copy(retries=0),categories,image) {};fail() }
            catch(e: CloudFailure) { assertEquals(kind,e.kind);assertFalse(e.message!!.contains("secret-user-key")) }
        }
    }
    @Test fun uncertainNetworkFailureIsNeverAutomaticallyRetried() = runBlocking {
        var calls=0
        val client=GeminiClient(CloudTransport { _,_,_ -> calls++;throw CloudFailure(CloudErrorKind.NETWORK) })
        failure(CloudErrorKind.NETWORK) { runBlocking { client.classify("test-key",options,categories,image) {} } }
        assertEquals(1,calls)
    }
    @Test fun cancellationPropagatesWithoutRetry() = runBlocking {
        var calls=0
        val client=GeminiClient(CloudTransport { _,_,_ -> calls++;throw CancellationException() })
        try { client.classify("test-key",options,categories,image) {};fail() } catch(_: CancellationException) {}
        assertEquals(1,calls)
    }
    @Test fun cloudOnlyCannotBypassIndividualTransferApproval() {
        assertTrue(CloudApproval.requiresIndividualReview("GEMINI"))
        assertTrue(CloudApproval.requiresIndividualReview("GEMINI_HYBRID_REVIEW"))
        assertFalse(CloudApproval.requiresIndividualReview("GEMINI_HYBRID_AGREEMENT"))
    }

    @Test fun failedCloudRowsCanRetryInANewExplicitBatch() {
        assertFalse(CloudApproval.shouldReuse(AnalysisMode.GEMINI,"Gemini: Rate or quota limit reached.",1000,false,1001))
        assertFalse(CloudApproval.shouldReuse(AnalysisMode.HYBRID,"Gemini: Network outcome is uncertain. Offline result retained for review.",1000,false,1001))
    }
    @Test fun validCloudRowsRespectSevenDayFreshness() {
        val note="Gemini: A meal. No calibrated Gemini probability is available."
        assertTrue(CloudApproval.shouldReuse(AnalysisMode.GEMINI,note,1000,false,1001))
        assertFalse(CloudApproval.shouldReuse(AnalysisMode.GEMINI,note,1000,false,604_801_001))
        assertFalse(CloudApproval.shouldReuse(AnalysisMode.GEMINI,note,1000,false,999))
        assertTrue(CloudApproval.shouldReuse(AnalysisMode.HYBRID,"Gemini selected unknown/ambiguous content.",1000,false,1001))
    }
    @Test fun manualDecisionsAndOfflineReuseArePreserved() {
        assertTrue(CloudApproval.shouldReuse(AnalysisMode.GEMINI,"reviewed by user",1,true,900_000_000))
        assertTrue(CloudApproval.shouldReuse(AnalysisMode.OFFLINE,"ML Kit",1,false,900_000_000))
    }
}
