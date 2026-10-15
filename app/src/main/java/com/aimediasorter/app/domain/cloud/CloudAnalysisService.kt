package com.aimediasorter.app.domain.cloud
import android.content.Context
import android.net.Uri
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.local.CloudStateStore
import com.aimediasorter.app.data.model.ClassificationScoreMetrics
import com.aimediasorter.app.data.preferences.AppSettings
import com.aimediasorter.app.data.preferences.GeminiKeyVault
import com.aimediasorter.app.domain.ai.VisualFeatureExtractor
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

object CloudApproval {
    fun requiresIndividualReview(engine: String)=engine in setOf("GEMINI","GEMINI_HYBRID_REVIEW")
}
class CloudAnalysisService(context: Context,extractor: VisualFeatureExtractor,
    private val client: GeminiClient=GeminiClient(GeminiHttpTransport())) {
    val vault=GeminiKeyVault(context)
    val state=CloudStateStore(context)
    private val encoder=CloudMediaEncoder(context)
    private fun digest(s: String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    fun authorize(signature: String,options: CloudOptions): String {
        if(!vault.hasKey()) throw CloudFailure(CloudErrorKind.AUTH)
        return state.authorize(signature,vault.revision,options)
    }
    suspend fun refreshModels(): List<String> {
        val credential=try { vault.snapshot() } catch (_: Exception) { throw CloudFailure(CloudErrorKind.AUTH) }
        val models=client.models(credential.secret)
        if(vault.revision!=credential.revision) throw CloudFailure(CloudErrorKind.CONSENT)
        state.replaceModels(credential.revision,models);return models
    }
    suspend fun analyze(settings: AppSettings,batch: String,signature: String,originalHash: String?,
        categories: List<CategoryEntity>,uri: Uri,path: String?,isVideo: Boolean,orientation: Int,mirrored: Boolean,
        offline: ClassificationScoreMetrics?,liveCheck: suspend () -> Unit): ClassificationScoreMetrics {
        val o=settings.cloudOptions
        var cacheKey=""
        try {
            if(originalHash==null) throw CloudFailure(CloudErrorKind.CONSENT)
            if(!o.ready() || o.model !in state.models(vault.revision)) throw CloudFailure(CloudErrorKind.CONFIG)
            cacheKey=digest(originalHash+";"+vault.revision+";"+o.model+";"+o.maxOutputTokens+";"+orientation+";"+mirrored+";"+isVideo+";"+CloudProtocol.VERSION+";"+CloudProtocol.categories(categories))
            var decision=state.cached(cacheKey)
            var sampledTimes=""
            if(decision==null) {
                if(batch.isBlank()) throw CloudFailure(CloudErrorKind.CONSENT)
                liveCheck()
                state.begin(batch,cacheKey)
                val images=encoder.images(uri,path,isVideo,orientation,mirrored)
                sampledTimes=images.mapNotNull { it.timestampMs }.joinToString(",")
                val credential=try { vault.snapshot() } catch (_: Exception) { throw CloudFailure(CloudErrorKind.AUTH) }
                val response=client.classify(credential.secret,o,categories,images) {
                    liveCheck()
                    if(vault.revision!=credential.revision) throw CloudFailure(CloudErrorKind.CONSENT)
                    state.reserve(batch,signature,credential.revision)
                }
                liveCheck() // Do not cache a result against bytes/configuration that changed in flight.
                if(vault.revision!=credential.revision) throw CloudFailure(CloudErrorKind.CONSENT)
                state.complete(batch,cacheKey,response);decision=response.decision
            }
            val result=decision ?: throw CloudFailure(CloudErrorKind.RESPONSE)
            val cat=categories.firstOrNull { it.id==result.category }
            if(cat==null) return review(offline,"Gemini selected unknown/ambiguous content.")
            val note="Gemini: "+result.short_description+
                (if(isVideo) " Joint sampled-frame result at requested 10/50/90 percent offsets; duplicate or undecodable frames omitted. "+(if(sampledTimes.isEmpty()) "Cached sample result." else "Decoded request offsets (ms): $sampledTimes.")+" Not complete-video analysis." else "")+
                " No calibrated Gemini probability is available."
            val agreement=offline!=null && offline.categoryId==cat.id && !offline.needsManualReview &&
                result.review_status=="CLEAR" && result.alternative_categories.isEmpty()
            if(agreement) return offline!!.copy(primarySubjectNote=note+" ML Kit agrees; score is ML Kit evidence.",
                inferenceEngine="GEMINI_HYBRID_AGREEMENT")
            return ClassificationScoreMetrics(cat.id,cat.name,0f,0f,0f,
                secondaryCategoryName=result.alternative_categories.firstOrNull()?.let { id -> categories.firstOrNull { it.id==id }?.name },
                needsManualReview=true,primarySubjectNote=note+" Individual category approval is required.",
                inferenceEngine=if(offline==null) "GEMINI" else "GEMINI_HYBRID_REVIEW")
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: CloudFailure) {
            if(batch.isNotBlank() && cacheKey.isNotBlank()) state.failed(batch,cacheKey,error.kind)
            return review(offline,error.message.orEmpty())
        } catch (_: Exception) {
            if(batch.isNotBlank() && cacheKey.isNotBlank()) state.failed(batch,cacheKey,CloudErrorKind.RESPONSE)
            return review(offline,"Gemini input or response could not be verified.")
        }
    }
    private fun review(offline: ClassificationScoreMetrics?,note: String): ClassificationScoreMetrics =
        offline?.copy(needsManualReview=true,primarySubjectNote=note+" Offline result retained for review.",
            inferenceEngine="GEMINI_HYBRID_REVIEW") ?: ClassificationScoreMetrics("cat_other","Other",0f,0f,0f,
            needsManualReview=true,primarySubjectNote=note,inferenceEngine="GEMINI")
}
