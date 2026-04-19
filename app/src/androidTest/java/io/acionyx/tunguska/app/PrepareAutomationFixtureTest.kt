package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrepareAutomationFixtureTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun prepares_a_real_profile_and_writes_the_automation_token_fixture() {
        harness.ensureRuntimeIdle()
        harness.launchTunguska()
        harness.importShareLinkFromArgsOrDefault()
        harness.connectAndWait()
        harness.stopAndWaitForIdle()

        val token = harness.enableAutomationIntegrationViaUi()
        harness.writeAutomationTokenFixture(token)
    }
}
