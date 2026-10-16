package com.aimediasorter.app

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** Bounded real gestures within the named viewport, avoiding nested text-field scrollers. */
internal class InstalledUi(private val device: UiDevice) {
    fun control(tag: String, viewportTag: String?=null): UiObject2 {
        fun visible(): UiObject2?=device.findObject(By.res(tag))?.takeIf { !it.visibleBounds.isEmpty }
        if(viewportTag==null) return requireNotNull(device.wait(Until.findObject(By.res(tag)),10_000)) {
            "Control unavailable: "+tag
        }
        requireNotNull(device.wait(Until.findObject(By.res(viewportTag)),10_000)) { "Viewport unavailable: "+viewportTag }
        visible()?.let { return it }
        fun swipe(towardStart: Boolean) {
            val bounds=requireNotNull(device.findObject(By.res(viewportTag))).visibleBounds
            check(bounds.width()>0 && bounds.height()>0)
            val upper=bounds.top+bounds.height()/4
            val lower=bounds.bottom-bounds.height()/4
            device.swipe(bounds.centerX(),if(towardStart) upper else lower,
                bounds.centerX(),if(towardStart) lower else upper,25)
            device.waitForIdle()
        }
        repeat(8) {
            visible()?.let { return it }
            swipe(true)
        }
        repeat(16) {
            visible()?.let { return it }
            swipe(false)
        }
        return requireNotNull(visible()) { "Control unavailable in "+viewportTag+": "+tag }
    }
    fun click(tag: String, viewportTag: String?=null) {
        control(tag,viewportTag).click()
        device.waitForIdle()
    }
}
