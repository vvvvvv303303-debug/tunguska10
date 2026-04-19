package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DenylistRoutingProofTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun helper_app_stays_direct_when_denylisted() {
        harness.ensureRuntimeIdle()
        val directIp = harness.launchTrafficProbeAndReadIp("baseline_direct_denylist")

        harness.launchTunguska()
        harness.importPayload(harness.buildProfile(TestSplitMode.DENYLIST_EXCLUDED_PROBE).canonicalJson())
        harness.connectAndWait()

        val denylistIp = harness.launchTrafficProbeAndReadIp("denylist_excluded")
        assertEquals("Helper app tunneled even though it was denylisted.", directIp, denylistIp)
    }
}
