package com.aimediasorter.app.domain.cloud

import java.util.concurrent.atomic.AtomicInteger

/** Finite, non-sensitive codes. Never include provider bodies, URIs, filenames, keys or pixels. */
enum class AnalysisCode(val stage: String,val message: String) {
    UNRECORDED("UNKNOWN","This older result has no request diagnostics. Request count is unknown."),
    SUCCESS("COMPLETE","Analysis succeeded."),
    CACHE_HIT("COMPLETE","A validated cached analysis was reused; no new request was sent."),
    LOCAL_SOURCE_ACCESS("SOURCE","Selected media could not be read. Check the selected folder permission."),
    LOCAL_SOURCE_CHANGED("SOURCE","Selected media changed during analysis. Review and explicitly retry."),
    LOCAL_IMAGE_DECODE("PREPROCESS","The selected image could not be decoded. No request was sent."),
    LOCAL_JPEG_ENCODE("PREPROCESS","The upload JPEG could not be encoded within the size limit. No request was sent."),
    LOCAL_VIDEO_DECODE("PREPROCESS","Representative video frames could not be decoded. No request was sent."),
    CONFIG_NOT_READY("PREFLIGHT","Save a key, load models, select and test the selected model before analysis."),
    CONSENT_INVALID("PREFLIGHT","Batch consent expired or source, credentials or configuration changed."),
    LIMIT_REACHED("RESERVATION","The local batch, daily request or spending limit was reached. Remaining files are pending."),
    ATTEMPT_UNCERTAIN("PREFLIGHT","A previous attempt has an uncertain outcome. No duplicate request was sent; explicitly retry with new consent."),
    REQUEST_BUILD("REQUEST","The local request configuration could not be constructed. No request was sent."),
    HTTP_AUTH("HTTP","Authentication failed. Check project access and credential restrictions."),
    HTTP_BILLING("HTTP","Project billing or payment is unavailable. No billing settings were changed."),
    HTTP_QUOTA("HTTP","Google quota or rate limit reached. Wait before explicitly retrying."),
    HTTP_MODEL("HTTP","The selected model is unavailable for this request."),
    HTTP_REQUEST("HTTP","Google rejected the request. HTTP 400; no category was guessed."),
    HTTP_SERVER("HTTP","Google returned a server error. Retry later with explicit consent."),
    HTTP_UNEXPECTED("HTTP","Google returned an unexpected HTTP status. No category was guessed."),
    NETWORK("TRANSPORT","Network failure. A partial upload or unknown provider outcome is possible; review before retrying."),
    TIMEOUT("TRANSPORT","Connection timed out. The provider outcome may be uncertain; review before retrying."),
    RESPONSE_TOO_LARGE("ENVELOPE","The provider response exceeded the local safe response-size limit."),
    RESPONSE_ENVELOPE("ENVELOPE","The provider response had an invalid or missing candidate envelope."),
    RESPONSE_MAX_TOKENS("FINISH","Google stopped at the output token limit before a complete result. No category was guessed."),
    RESPONSE_FINISH("FINISH","Google did not finish with a complete usable result."),
    RESPONSE_BLOCKED("FINISH","Google declined this content. Manual review is required."),
    RESPONSE_JSON("JSON","Google returned invalid or incomplete JSON. No category was guessed."),
    RESPONSE_SCHEMA("VALIDATION","The returned JSON did not match the required fields, types or limits."),
    RESPONSE_CATEGORIES("VALIDATION","Returned category IDs were unsupported or duplicated. No category was guessed."),
    INTERNAL("LOCAL","Local analysis failed unexpectedly. No offline fallback or category guess was used.");

    companion object {
        fun forKind(kind: CloudErrorKind)=when(kind) {
            CloudErrorKind.AUTH->HTTP_AUTH;CloudErrorKind.BILLING->HTTP_BILLING
            CloudErrorKind.QUOTA->HTTP_QUOTA;CloudErrorKind.NETWORK->NETWORK
            CloudErrorKind.TIMEOUT->TIMEOUT;CloudErrorKind.MODEL->HTTP_MODEL
            CloudErrorKind.REQUEST->HTTP_REQUEST;CloudErrorKind.RESPONSE->RESPONSE_SCHEMA
            CloudErrorKind.CONFIG->CONFIG_NOT_READY;CloudErrorKind.CONSENT->CONSENT_INVALID
            CloudErrorKind.BUDGET->LIMIT_REACHED;CloudErrorKind.BLOCKED->RESPONSE_BLOCKED
            CloudErrorKind.SERVER->HTTP_SERVER
        }
        fun safely(value: String)=entries.firstOrNull { it.name==value } ?: UNRECORDED
    }
}

/** Sent means the complete POST body was written/flushed, not Google receipt or billing.
 * A failure before that point may still have uploaded part of the body.
 * Reservations are deliberately separate. Counters are local and contain no media identity.
 */
class CloudRequestTrace(private val onSent: ()->Unit = {}) {
    private val sent=AtomicInteger()
    var reservations: Int=0
        private set
    var httpStatus: Int=0
        internal set
    val requestsSent get()=sent.get()
    fun reserved() { reservations++ }
    fun bodySent() { sent.incrementAndGet();onSent() }
}
