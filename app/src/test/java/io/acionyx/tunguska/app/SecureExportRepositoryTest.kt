package io.acionyx.tunguska.app

import io.acionyx.tunguska.crypto.SoftwareAesGcmCipherBox
import io.acionyx.tunguska.engine.singbox.SingboxEnginePlugin
import io.acionyx.tunguska.netpolicy.RoutePreviewOutcome
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.vpnservice.VpnRuntimePhase
import io.acionyx.tunguska.vpnservice.VpnRuntimeSnapshot
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureExportRepositoryTest {
    @Test
    fun `encrypted profile backup preserves full profile payload`() {
        val profile = defaultBootstrapProfile()
        val compiled = SingboxEnginePlugin().compile(profile)
        val repository = buildRepository()

        val artifact = repository.exportEncryptedProfileBackup(
            profile = profile,
            compiledConfig = compiled,
        )
        val loaded = repository.loadArtifact(Paths.get(artifact.path))

        assertEquals("profile_backup", artifact.artifactType)
        assertFalse(artifact.redacted)
        assertTrue(loaded.payloadJson.contains(profile.outbound.uuid))
        assertTrue(loaded.payloadJson.contains(profile.outbound.address))
        assertTrue(Files.exists(Paths.get(artifact.path)))
    }

    @Test
    fun `redacted diagnostic bundle strips raw server identifiers`() {
        val profile = defaultBootstrapProfile()
        val compiled = SingboxEnginePlugin().compile(profile)
        val repository = buildRepository()

        val artifact = repository.exportRedactedDiagnosticBundle(
            profile = profile,
            compiledConfig = compiled,
            tunnelPlanSummary = TunnelPlanSummary(
                processNameSuffix = ":vpn",
                preserveLoopback = true,
                splitTunnelMode = "Denylist",
                allowedPackageCount = 0,
                disallowedPackageCount = 1,
                runtimeMode = "FAIL_CLOSED_UNTIL_ENGINE_HOST",
            ),
            runtimeSnapshot = VpnRuntimeSnapshot(
                phase = VpnRuntimePhase.RUNNING,
                configHash = compiled.configHash,
            ),
            profileStorage = ProfileStorageState(
                backend = "Android Keystore AES-GCM",
                keyReference = "android-keystore:test",
                storagePath = "C:/private/profile.json.enc",
                status = "Loaded encrypted profile.",
                persistedProfileHash = profile.canonicalHash(),
            ),
            automationState = AutomationState(
                storagePath = "C:/private/automation.json.enc",
                keyReference = "android-keystore:test-automation",
                vpnPermissionReady = true,
            ),
            routePreview = PreviewInputs(
                packageName = "io.acionyx.browser",
                destinationHost = "login.corp.example",
                destinationPort = "443",
            ),
            previewOutcome = RoutePreviewOutcome(
                action = RouteAction.PROXY,
                matchedRuleId = "corp-direct",
                reason = "Matched explicit routing rule.",
            ),
        )
        val loaded = repository.loadArtifact(Paths.get(artifact.path))

        assertEquals("diagnostic_bundle", artifact.artifactType)
        assertTrue(artifact.redacted)
        assertFalse(loaded.payloadJson.contains(profile.outbound.uuid))
        assertFalse(loaded.payloadJson.contains(profile.outbound.address))
        assertFalse(loaded.payloadJson.contains(profile.outbound.serverName))
        assertTrue(loaded.payloadJson.contains(compiled.configHash))
        assertTrue(loaded.payloadJson.contains("\"engineSessionHealthStatus\":\"UNKNOWN\""))
    }

    private fun buildRepository(): SecureExportRepository = SecureExportRepository(
        Files.createTempDirectory("tunguska-exports"),
        SoftwareAesGcmCipherBox(SoftwareAesGcmCipherBox.generateKey()),
        clock = { 1234L },
    )
}
