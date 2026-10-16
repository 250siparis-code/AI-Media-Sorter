package com.aimediasorter.app

import android.Manifest
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.aimediasorter.app.data.preferences.GeminiKeyVault
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the installed Compose app through accessibility. Never calls the API. */
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
        var activity: android.app.Activity?=null
        try {
            activity=instrumentation.startActivitySync(intent)
            device.waitForIdle()
            val settings=device.wait(Until.findObject(By.text("Settings")),10_000)
                ?: device.wait(Until.findObject(By.text("Ayarlar")),10_000)
            requireNotNull(settings).click()
            val scroll=UiScrollable(UiSelector().scrollable(true))
            val saveSelector=UiSelector().textMatches("Save key|Anahtarı kaydet")
            scroll.scrollIntoView(saveSelector)
            val save=device.findObject(saveSelector)
            assertTrue(save.exists());assertFalse(save.isEnabled)
            val field=device.findObject(UiSelector().className("android.widget.EditText").instance(0))
            field.setText(synthetic)
            device.waitForIdle()
            assertTrue("AQ. must enable Save without a selected model.",save.isEnabled)
            save.click();device.waitForIdle()
            device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile("An encrypted key.*|Şifrelenmiş anahtar.*"))),10_000)
            assertEquals(synthetic,GeminiKeyVault(context).read())
            instrumentation.runOnMainSync { activity?.finish() }
            activity=instrumentation.startActivitySync(intent)
            device.waitForIdle()
            val settingsAgain=device.wait(Until.findObject(By.text("Settings")),10_000)
                ?: device.wait(Until.findObject(By.text("Ayarlar")),10_000)
            requireNotNull(settingsAgain).click()
            scroll.scrollIntoView(UiSelector().textMatches("Replace key|Anahtarı değiştir"))
            assertEquals(synthetic,GeminiKeyVault(context).read())
            val remove=device.findObject(UiSelector().textMatches("Remove key|Anahtarı kaldır"))
            assertTrue(remove.isEnabled);remove.click();device.waitForIdle()
            device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile("No API key saved.*|Kayıtlı API anahtarı yok.*"))),10_000)
            assertFalse(vault.hasKey())
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            vault.clear()
        }
    }
}
