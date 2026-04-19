package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.acionyx.tunguska.vpnservice.VpnRuntimePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomationRelayProofTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun automation_activity_can_start_and_stop_the_runtime_with_real_ip_change() {
        harness.ensureRuntimeIdle()
        harness.launchTunguska()
        harness.importShareLinkFromArgsOrDefault()
        harness.connectAndWait()
        harness.stopAndWaitForIdle()

        val token = harness.enableAutomationIntegrationViaUi()
        val directIp = harness.openChromeAndReadIp("automation_direct")

        val startTimestamp = harness.automationStatusTimestamp()
        harness.invokeAutomationStart(token = token)
        harness.waitForAutomationStatus(AutomationCommandStatus.SUCCESS, startTimestamp)
        harness.waitForRuntimePhaseVisible(VpnRuntimePhase.RUNNING.name)

        val vpnIp = harness.openChromeAndReadIp("automation_vpn")
        assertNotEquals("Automation start kept the same public IP.", directIp, vpnIp)

        val stopTimestamp = harness.automationStatusTimestamp()
        harness.invokeAutomationStop(token = token)
        harness.waitForAutomationStatus(AutomationCommandStatus.SUCCESS, stopTimestamp)
        harness.waitForRuntimePhaseVisible(VpnRuntimePhase.IDLE.name)

        val directAfterStop = harness.openChromeAndReadIp("automation_direct_after_stop")
        assertEquals("Automation stop did not restore the direct public IP.", directIp, directAfterStop)
    }
}
