package io.acionyx.tunguska.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityExportProofTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = VpnTestHarness(composeRule)

    @Test
    fun backup_and_audit_export_buttons_save_artifacts_and_open_share_sheet() {
        harness.ensureRuntimeIdle()
        harness.launchTunguska()
        harness.importPayload(harness.buildProfile(TestSplitMode.FULL_TUNNEL).canonicalJson())

        harness.exportBackupThroughUiAndDismissShareSheet()
        harness.exportAuditThroughUiAndDismissShareSheet()
        harness.assertBackupAndAuditStatesIndependent()
    }
}
