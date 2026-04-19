package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AllowlistRoutingProofTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun helper_app_tunnels_when_allowlisted() {
        harness.ensureRuntimeIdle()
        val directIp = harness.launchTrafficProbeAndReadIp("baseline_direct_allowlist")

        harness.launchTunguska()
        harness.importPayload(harness.buildProfile(TestSplitMode.ALLOWLIST_INCLUDED_PROBE).canonicalJson())
        harness.connectAndWait()

        val allowlistIp = harness.launchTrafficProbeAndReadIp("allowlist_included")
        assertNotEquals("Helper app stayed direct even though it was allowlisted.", directIp, allowlistIp)
    }
}
