package com.aimediasorter.app

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.aimediasorter.app.data.preferences.GeminiKeyVault
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Installed Compose controls, including their disabled state; no live API calls. */
@RunWith(AndroidJUnit4::class)
class GeminiSettingsUiTest {
    @Test fun authorizationKeyEnablesSavePersistsAcrossActivityRestartAndCanBeDeleted() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val vault=GeminiKeyVault(context)
        assumeFalse("Never overwrite a pre-existing personal key.",vault.hasKey())
        val device=UiDevice.getInstance(instrumentation)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        val intent=Intent().setClassName(context.packageName,context.packageName.removeSuffix(".debug")+".MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val synthetic="AQ."+"SyntheticCredential".repeat(8)
        val replacement="AQ."+"ReplacementCredential".repeat(9)
        var activity: android.app.Activity?=null
        fun openSettings() {
            val settings=device.wait(Until.findObject(By.text("Settings")),10_000)
                ?: device.wait(Until.findObject(By.text("Ayarlar")),10_000)
            requireNotNull(settings).click();device.waitForIdle()
        }
        fun control(tag: String): UiObject2 {
            UiScrollable(UiSelector().scrollable(true)).setAsVerticalList()
                .scrollIntoView(UiSelector().resourceId(tag))
            return requireNotNull(device.wait(Until.findObject(By.res(tag)),10_000)) { "Control unavailable: "+tag }
        }
        fun awaitKey(expected: String?) {
            val deadline=SystemClock.uptimeMillis()+10_000
            do {
                val matches=if(expected==null) !GeminiKeyVault(context).hasKey()
                    else runCatching { GeminiKeyVault(context).read()==expected }.getOrDefault(false)
                if(matches) return
                SystemClock.sleep(50)
            } while(SystemClock.uptimeMillis()<deadline)
            fail("Encrypted key persistence did not complete.")
        }
        fun saveKey(value: String) {
            control("gemini-key-input").text=value
            device.waitForIdle()
            val save=control("gemini-save-key")
            assertTrue("AQ. must enable Save without a selected model.",save.isEnabled)
            save.click();awaitKey(value);device.waitForIdle()
        }
        try {
            activity=instrumentation.startActivitySync(intent);device.waitForIdle();openSettings()
            val emptySave=control("gemini-save-key")
            assertFalse("An empty key must disable the Save control.",emptySave.isEnabled)
            saveKey(synthetic)
            assertEquals(synthetic,GeminiKeyVault(context).read())
            instrumentation.runOnMainSync { activity?.finish() }
            activity=instrumentation.startActivitySync(intent);device.waitForIdle();openSettings()
            assertEquals(synthetic,GeminiKeyVault(context).read())
            saveKey(replacement)
            assertEquals(replacement,GeminiKeyVault(context).read())
            val remove=control("gemini-delete-key")
            assertTrue("The stored key must be deletable.",remove.isEnabled)
            remove.click();awaitKey(null);device.waitForIdle()
            assertFalse(vault.hasKey())
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            vault.clear()
        }
    }
}
