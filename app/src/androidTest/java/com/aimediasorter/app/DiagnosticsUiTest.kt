package com.aimediasorter.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsUiTest {
    private val activity=InstalledActivityRule()
    private val compose=AndroidComposeTestRule(activity) { it.activity() }
    private val permission=object: ExternalResource() {
        override fun before() {
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.executeShellCommand("pm grant "+instrumentation.targetContext.packageName+" android.permission.POST_NOTIFICATIONS")
                .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
    }
    @get:Rule val rules: RuleChain=RuleChain.outerRule(permission).around(compose)
    private fun text(name: String): String {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        return context.getString(context.resources.getIdentifier(name,"string",context.packageName))
    }
    @Test fun settingsDiagnosticsShowsExportPreviewWithoutWritingUntilSafConfirmation() {
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("open-diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-screen").assertExists()
        compose.onNodeWithTag("diagnostics-export-all").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("diagnostics-confirm-export").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text("diagnostics_preview")).assertExists()
        compose.onNodeWithText(text("btn_cancel")).performClick()
        compose.onNodeWithTag("diagnostics-confirm-export").assertDoesNotExist()
    }
    @Test fun clearRequiresExplicitConfirmationAndCanBeCancelled() {
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("open-diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-clear").performScrollTo().performClick()
        compose.onNodeWithText(text("diagnostics_clear_confirm")).assertExists()
        compose.onNodeWithText(text("btn_cancel")).performClick()
        compose.onNodeWithText(text("diagnostics_clear_confirm")).assertDoesNotExist()
    }
}
