package com.aimediasorter.app

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
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
        var scenario: ActivityScenario<MainActivity>?=null
        val ui=InstalledUi(device)
        fun openSettings() {
            ui.click("nav-settings")
            assertTrue("Settings navigation must open its own scroll viewport.",
                device.wait(Until.hasObject(By.res("settings-scroll")),10_000)==true)
        }
        fun control(tag: String)=ui.control(tag,"settings-scroll")
        fun awaitKey(expected: String?) {
            val deadline=SystemClock.uptimeMillis()+10_000
            do {
                val matches=if(expected==null) !GeminiKeyVault(context).hasKey()
                    else runCatching { GeminiKeyVault(context).read()==expected }.getOrDefault(false)
                if(matches) return
                SystemClock.sleep(50)
            } while(SystemClock.uptimeMillis()<deadline)
            val actual=runCatching { GeminiKeyVault(context).read() }.getOrNull()
            val inputs=device.findObjects(By.clazz("android.widget.EditText")).map {
                "chars="+(it.text?.length ?: 0)+",focused="+it.isFocused
            }
            val saveEnabled=runCatching { device.findObject(By.res("gemini-save-key"))?.isEnabled }.getOrNull()
            val inlineFailure=device.hasObject(By.textContains("Secure saving failed")) ||
                device.hasObject(By.textContains("Güvenli kayıt başarısız"))
            fail("Encrypted key persistence timed out; expectedLength="+(expected?.length ?: 0)+
                ",storedLength="+(actual?.length ?: 0)+",saveEnabled="+saveEnabled+
                ",inlineFailure="+inlineFailure+",inputs="+inputs)
        }
        fun saveKey(value: String) {
            val field=control("gemini-key-input")
            field.click()
            device.pressKeyCode(KeyEvent.KEYCODE_A,KeyEvent.META_CTRL_ON)
            device.pressKeyCode(KeyEvent.KEYCODE_DEL)
            // Fixed synthetic fixture only: exercise actual Android key input rather than
            // setting text on an outer Compose accessibility node.
            device.executeShellCommand("input text "+value)
            device.waitForIdle()
            control("gemini-save-key")
            val save=device.wait(Until.findObject(By.res("gemini-save-key").enabled(true)),10_000)
            if(save==null) {
                val inputs=device.findObjects(By.clazz("android.widget.EditText")).map {
                    "class="+it.className+",chars="+(it.text?.length ?: 0)+",focused="+it.isFocused
                }
                fail("AQ. must enable Save without a selected model. Sanitized input state: "+inputs)
            }
            requireNotNull(save).click();awaitKey(value);device.waitForIdle()
        }
        try {
            scenario=ActivityScenario.launch<MainActivity>(intent);device.waitForIdle();openSettings()
            val emptySave=control("gemini-save-key")
            assertFalse("An empty key must disable the Save control.",emptySave.isEnabled)
            saveKey(synthetic)
            assertEquals(synthetic,GeminiKeyVault(context).read())
            device.pressBack();device.waitForIdle() // Dismiss the first Activity's keyboard before restart.
            requireNotNull(scenario).recreate();device.waitForIdle();openSettings()
            assertEquals(synthetic,GeminiKeyVault(context).read())
            saveKey(replacement)
            assertEquals(replacement,GeminiKeyVault(context).read())
            val remove=control("gemini-delete-key")
            assertTrue("The stored key must be deletable.",remove.isEnabled)
            remove.click();awaitKey(null);device.waitForIdle()
            assertFalse(vault.hasKey())
        } finally {
            scenario?.close()
            vault.clear()
        }
    }
}
