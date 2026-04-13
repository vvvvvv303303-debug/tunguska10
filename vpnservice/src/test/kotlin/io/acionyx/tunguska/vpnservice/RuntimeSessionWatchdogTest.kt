package io.acionyx.tunguska.vpnservice

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class RuntimeSessionWatchdogTest {
    @Test
    fun `watchdog records healthy sessions without stopping runtime`() {
        var observedHealth: EmbeddedEngineSessionHealthResult? = null
        var stopped = false
        var failClosedReason: String? = null
        var stoppedResult: EmbeddedEngineSessionResult? = null
        val watchdog = RuntimeSessionWatchdog(
            healthProbe = {
                EmbeddedEngineSessionHealthResult(
                    status = EmbeddedEngineSessionHealthStatus.HEALTHY,
                    summary = "Embedded engine session is healthy.",
                    observedAtEpochMs = 1234L,
                )
            },
            stopRuntime = {
                stopped = true
                EmbeddedEngineSessionResult(
                    status = EmbeddedEngineSessionStatus.STOPPED,
                    summary = "Stopped.",
                    observedAtEpochMs = 5678L,
                )
            },
            onHealthObserved = { observedHealth = it },
            onRuntimeStopped = { stoppedResult = it },
            onFailClosed = { failClosedReason = it },
        )

        val outcome = watchdog.evaluate()

        assertTrue(outcome.evaluated)
        assertNull(outcome.failureReason)
        assertEquals(EmbeddedEngineSessionHealthStatus.HEALTHY, observedHealth?.status)
        assertFalse(stopped)
        assertNull(stoppedResult)
        assertNull(failClosedReason)
    }

    @Test
    fun `watchdog stops and fails closed when health probe reports failure`() {
        var observedHealth: EmbeddedEngineSessionHealthResult? = null
        var stoppedResult: EmbeddedEngineSessionResult? = null
        var failClosedReason: String? = null
        val watchdog = RuntimeSessionWatchdog(
            healthProbe = {
                EmbeddedEngineSessionHealthResult(
                    status = EmbeddedEngineSessionHealthStatus.FAILED,
                    summary = "Embedded engine session died.",
                    observedAtEpochMs = 1234L,
                )
            },
            stopRuntime = {
                EmbeddedEngineSessionResult(
                    status = EmbeddedEngineSessionStatus.STOPPED,
                    summary = "Embedded engine session stopped.",
                    observedAtEpochMs = 5678L,
                )
            },
            onHealthObserved = { observedHealth = it },
            onRuntimeStopped = { stoppedResult = it },
            onFailClosed = { failClosedReason = it },
        )

        val outcome = watchdog.evaluate()

        assertTrue(outcome.evaluated)
        assertEquals("Embedded engine session died.", outcome.failureReason)
        assertEquals(EmbeddedEngineSessionHealthStatus.FAILED, observedHealth?.status)
        assertEquals(EmbeddedEngineSessionStatus.STOPPED, stoppedResult?.status)
        assertEquals("Embedded engine session died.", failClosedReason)
    }

    @Test
    fun `watchdog skips evaluation when no active runtime exists`() {
        var observedHealth = false
        val watchdog = RuntimeSessionWatchdog(
            healthProbe = { null },
            stopRuntime = {
                error("stopRuntime should not be called without an active session")
            },
            onHealthObserved = { observedHealth = true },
            onRuntimeStopped = { error("onRuntimeStopped should not be called without an active session") },
            onFailClosed = { error("onFailClosed should not be called without an active session") },
        )

        val outcome = watchdog.evaluate()

        assertFalse(outcome.evaluated)
        assertNull(outcome.failureReason)
        assertFalse(observedHealth)
    }
}
