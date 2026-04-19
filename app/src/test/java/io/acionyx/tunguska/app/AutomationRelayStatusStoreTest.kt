package io.acionyx.tunguska.app

import io.acionyx.tunguska.crypto.SoftwareAesGcmCipherBox
import java.nio.file.Files
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRelayStatusStoreTest {
    @Test
    fun `accepted status is encrypted at rest and readable through the store`() {
        val path = Files.createTempDirectory("tunguska-automation-status").resolve("relay-status.json.enc")
        val store = AutomationRelayStatusStore(
            path = path,
            cipherBox = SoftwareAesGcmCipherBox(SoftwareAesGcmCipherBox.generateKey()),
            clock = { 4321L },
        )

        val accepted = store.markAccepted(
            action = "io.acionyx.tunguska.action.AUTOMATION_START",
            callerHint = "anubis",
        )
        val loaded = store.load()
        val raw = path.readText()

        assertNotNull(loaded)
        assertEquals(accepted, loaded)
        assertTrue(raw.contains("\"schema\""))
        assertFalse(raw.contains("AUTOMATION_START"))
        assertFalse(raw.contains("anubis"))
    }

    @Test
    fun `completed status persists redacted runtime metadata without plaintext token material`() {
        val path = Files.createTempDirectory("tunguska-automation-status").resolve("relay-status.json.enc")
        val store = AutomationRelayStatusStore(
            path = path,
            cipherBox = SoftwareAesGcmCipherBox(SoftwareAesGcmCipherBox.generateKey()),
            clock = { 9876L },
        )

        val result = RuntimeAutomationResult(
            status = AutomationCommandStatus.RUNTIME_START_FAILED,
            summary = "The isolated Tunguska runtime did not reach RUNNING.",
            error = "Timed out",
        )

        val stored = store.markRejected(
            action = "io.acionyx.tunguska.action.AUTOMATION_START",
            callerHint = "anubis",
            result = result,
        )

        assertEquals(AutomationCommandStatus.RUNTIME_START_FAILED.name, stored.status)
        assertEquals("Timed out", store.load()?.error)
    }
}
