package io.acionyx.tunguska.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrImportPayloadSelectorTest {
    @Test
    fun `selects a single trimmed payload`() {
        val result = QrImportPayloadSelector.select(listOf("  vless://example  "))

        assertTrue(result is QrImportPayloadSelection.Single)
        result as QrImportPayloadSelection.Single
        assertEquals("vless://example", result.payload)
    }

    @Test
    fun `rejects empty payload lists`() {
        val result = QrImportPayloadSelector.select(listOf(null, "", "   "))

        assertTrue(result is QrImportPayloadSelection.Failure)
        result as QrImportPayloadSelection.Failure
        assertTrue(result.message.contains("does not contain a text share link"))
    }

    @Test
    fun `rejects multiple unique payloads`() {
        val result = QrImportPayloadSelector.select(
            listOf(
                "vless://one",
                "ess://two",
            ),
        )

        assertTrue(result is QrImportPayloadSelection.Failure)
        result as QrImportPayloadSelection.Failure
        assertTrue(result.message.contains("multiple text payloads"))
    }
}
