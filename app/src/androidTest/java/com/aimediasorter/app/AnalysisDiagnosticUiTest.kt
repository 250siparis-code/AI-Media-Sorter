package com.aimediasorter.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aimediasorter.app.data.repository.LiveAnalysisProgress
import com.aimediasorter.app.ui.screens.AnalysisProgressScreen
import com.aimediasorter.app.ui.theme.AIMediaSorterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisDiagnosticUiTest {
    private val activity=InstalledActivityRule()
    private val compose=AndroidComposeTestRule(activity) { it.activity() }
    private val permission=object: ExternalResource() {
        override fun before() {
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.executeShellCommand("pm grant "+instrumentation.targetContext.packageName+
                " android.permission.POST_NOTIFICATIONS").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
    }
    @get:Rule val rules: RuleChain=RuleChain.outerRule(permission).around(compose)
    private fun text(name: String,vararg args: Any): String {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        return context.getString(context.resources.getIdentifier(name,"string",context.packageName),*args)
    }
    private fun render(progress: LiveAnalysisProgress) {
        compose.runOnUiThread {
            activity.activity().setContent { AIMediaSorterTheme {
                AnalysisProgressScreen(progress,{},{},{})
            } }
        }
        compose.waitForIdle()
    }
    @Test fun failedJpegShowsActualDiagnosticAndAllFiveCountsWithoutVideoInformation() {
        render(LiveAnalysisProgress(totalFiles=36,processedFiles=36,currentFileName="public-fixture.jpg",
            currentDetectedCategory="Analysis Failed",failedCount=36,apiRequestsSent=0,
            currentDiagnosticCode="LOCAL_IMAGE_DECODE",currentRequestsSent=0,currentReservations=0,currentIsVideo=false))
        compose.onNodeWithTag("analysis-result-summary").assertExists()
        compose.onNodeWithText(text("analysis_summary_successful",0)).assertExists()
        compose.onNodeWithText(text("analysis_summary_review",0)).assertExists()
        compose.onNodeWithText(text("analysis_summary_failed",36)).assertExists()
        compose.onNodeWithText(text("analysis_summary_pending",0)).assertExists()
        compose.onNodeWithText(text("analysis_summary_sent",0)).assertExists()
        compose.onNodeWithText("LOCAL_IMAGE_DECODE").assertExists()
        compose.onNodeWithText(text("analysis_diag_local_image_decode")).assertExists()
        compose.onNodeWithText(text("progress_video_sampling")).assertDoesNotExist()
        compose.onNodeWithText(text("analysis_completed_issues")).assertExists()
    }
    @Test fun jsonFailureIsShownAsJsonFailureWithoutVideoInformation() {
        render(LiveAnalysisProgress(totalFiles=1,processedFiles=1,currentFileName="public-fixture.jpg",
            failedCount=1,apiRequestsSent=1,currentDiagnosticCode="RESPONSE_JSON",currentRequestsSent=1,
            currentReservations=1,currentHttpStatus=200,currentIsVideo=false))
        compose.onNodeWithText("RESPONSE_JSON").assertExists()
        compose.onNodeWithText("HTTP 200").assertExists()
        compose.onNodeWithText(text("analysis_diag_response_json")).assertExists()
        compose.onNodeWithText(text("progress_video_sampling")).assertDoesNotExist()
    }
    @Test fun videoInformationAppearsOnlyForSuccessfulVideo() {
        render(LiveAnalysisProgress(totalFiles=1,processedFiles=1,currentFileName="test-video.mp4",
            successfulCount=1,apiRequestsSent=1,currentDiagnosticCode="SUCCESS",currentRequestsSent=1,
            currentReservations=1,currentHttpStatus=200,currentIsVideo=true))
        compose.onNodeWithTag("analysis-video-info",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
    }
    @Test fun olderResultsShowUnknownCountsInsteadOfInventedDispatchOrOutcomeCounts() {
        render(LiveAnalysisProgress(totalFiles=36,processedFiles=36,successfulCount=-1,needsReviewCount=-1,
            failedCount=-1,pendingCount=-1,apiRequestsSent=-1))
        val unknown=text("analysis_count_unknown")
        compose.onNodeWithText(text("analysis_summary_successful",unknown)).assertExists()
        compose.onNodeWithText(text("analysis_summary_review",unknown)).assertExists()
        compose.onNodeWithText(text("analysis_summary_failed",unknown)).assertExists()
        compose.onNodeWithText(text("analysis_summary_pending",unknown)).assertExists()
        compose.onNodeWithText(text("analysis_requests_unknown")).assertExists()
    }

}
