package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullTunnelProofTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun helper_app_tunnels_in_full_tunnel_mode() {
        harness.ensureRuntimeIdle()
        val directIp = harness.launchTrafficProbeAndReadIp("baseline_direct_full_tunnel")

        harness.launchTunguska()
        harness.importPayload(harness.buildProfile(TestSplitMode.FULL_TUNNEL).canonicalJson())
        harness.connectAndWait()

        val tunneledIp = harness.launchTrafficProbeAndReadIp("full_tunnel")
        assertNotEquals("Helper app stayed direct in full tunnel mode.", directIp, tunneledIp)
    }
}
