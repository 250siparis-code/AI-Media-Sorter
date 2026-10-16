package com.aimediasorter.app

import androidx.test.core.app.ActivityScenario
import org.junit.rules.ExternalResource

/** A real Activity relaunch, including ViewModel destruction, synchronized with its lifecycle. */
internal class InstalledActivityRule : ExternalResource() {
    private var scenario: ActivityScenario<MainActivity>?=null
    override fun before() { launch() }
    override fun after() { scenario?.close();scenario=null }
    private fun launch() { scenario=ActivityScenario.launch(MainActivity::class.java) }
    fun restart() { scenario?.close();launch() }
    fun activity(): MainActivity {
        var activity: MainActivity?=null
        requireNotNull(scenario).onActivity { activity=it }
        return requireNotNull(activity)
    }
}
