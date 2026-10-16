package com.aimediasorter.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aimediasorter.app.R
import com.aimediasorter.app.domain.cloud.AnalysisCode
import com.aimediasorter.app.ui.theme.StatusAmber

@Composable fun diagnosticText(code: AnalysisCode)=stringResource(when(code) {
    AnalysisCode.UNRECORDED->R.string.analysis_diag_unrecorded
    AnalysisCode.SUCCESS->R.string.analysis_diag_success
    AnalysisCode.CACHE_HIT->R.string.analysis_diag_cache_hit
    AnalysisCode.LOCAL_SOURCE_ACCESS->R.string.analysis_diag_local_source_access
    AnalysisCode.LOCAL_SOURCE_CHANGED->R.string.analysis_diag_local_source_changed
    AnalysisCode.LOCAL_IMAGE_DECODE->R.string.analysis_diag_local_image_decode
    AnalysisCode.LOCAL_JPEG_ENCODE->R.string.analysis_diag_local_jpeg_encode
    AnalysisCode.LOCAL_VIDEO_DECODE->R.string.analysis_diag_local_video_decode
    AnalysisCode.CONFIG_NOT_READY->R.string.analysis_diag_config_not_ready
    AnalysisCode.CONSENT_INVALID->R.string.analysis_diag_consent_invalid
    AnalysisCode.LIMIT_REACHED->R.string.analysis_diag_limit_reached
    AnalysisCode.ATTEMPT_UNCERTAIN->R.string.analysis_diag_attempt_uncertain
    AnalysisCode.REQUEST_BUILD->R.string.analysis_diag_request_build
    AnalysisCode.HTTP_AUTH->R.string.analysis_diag_http_auth
    AnalysisCode.HTTP_BILLING->R.string.analysis_diag_http_billing
    AnalysisCode.HTTP_QUOTA->R.string.analysis_diag_http_quota
    AnalysisCode.HTTP_MODEL->R.string.analysis_diag_http_model
    AnalysisCode.HTTP_REQUEST->R.string.analysis_diag_http_request
    AnalysisCode.HTTP_SERVER->R.string.analysis_diag_http_server
    AnalysisCode.HTTP_UNEXPECTED->R.string.analysis_diag_http_unexpected
    AnalysisCode.NETWORK->R.string.analysis_diag_network
    AnalysisCode.TIMEOUT->R.string.analysis_diag_timeout
    AnalysisCode.RESPONSE_TOO_LARGE->R.string.analysis_diag_response_too_large
    AnalysisCode.RESPONSE_ENVELOPE->R.string.analysis_diag_response_envelope
    AnalysisCode.RESPONSE_MAX_TOKENS->R.string.analysis_diag_response_max_tokens
    AnalysisCode.RESPONSE_FINISH->R.string.analysis_diag_response_finish
    AnalysisCode.RESPONSE_BLOCKED->R.string.analysis_diag_response_blocked
    AnalysisCode.RESPONSE_JSON->R.string.analysis_diag_response_json
    AnalysisCode.RESPONSE_SCHEMA->R.string.analysis_diag_response_schema
    AnalysisCode.RESPONSE_CATEGORIES->R.string.analysis_diag_response_categories
    AnalysisCode.INTERNAL->R.string.analysis_diag_internal
})
@Composable fun AnalysisDiagnosticDetails(codeName: String,sent: Int,reserved: Int,http: Int) {
    val code=AnalysisCode.safely(codeName)
    Column(Modifier.fillMaxWidth().testTag("analysis-diagnostic"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(code.name,style=MaterialTheme.typography.labelMedium)
        Text(diagnosticText(code),style=MaterialTheme.typography.bodySmall,color=StatusAmber)
        Text(if(sent<0) stringResource(R.string.analysis_requests_unknown) else stringResource(R.string.analysis_file_requests,sent,reserved.coerceAtLeast(0)),
            style=MaterialTheme.typography.bodySmall)
        if(http>0) Text("HTTP "+http,style=MaterialTheme.typography.labelSmall)
    }
}
@Composable fun AnalysisResultSummary(successful: Int,review: Int,failed: Int,pending: Int,sent: Int) {
    val unknown=stringResource(R.string.analysis_count_unknown)
    Column(Modifier.fillMaxWidth().testTag("analysis-result-summary"),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.analysis_summary_successful,if(successful<0) unknown else successful.toString()))
        Text(stringResource(R.string.analysis_summary_review,if(review<0) unknown else review.toString()))
        Text(stringResource(R.string.analysis_summary_failed,if(failed<0) unknown else failed.toString()))
        Text(stringResource(R.string.analysis_summary_pending,if(pending<0) unknown else pending.toString()))
        if(successful<0) Text(stringResource(R.string.analysis_counts_unknown),style=MaterialTheme.typography.bodySmall)
        Text(if(sent<0) stringResource(R.string.analysis_requests_unknown) else stringResource(R.string.analysis_summary_sent,sent))
        Text(stringResource(R.string.analysis_sent_definition),style=MaterialTheme.typography.bodySmall)
    }
}
