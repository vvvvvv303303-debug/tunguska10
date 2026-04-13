package io.acionyx.tunguska.vpnservice

data class RuntimeSessionWatchdogOutcome(
    val evaluated: Boolean,
    val failureReason: String? = null,
)

class RuntimeSessionWatchdog(
    private val healthProbe: () -> EmbeddedEngineSessionHealthResult?,
    private val stopRuntime: () -> EmbeddedEngineSessionResult?,
    private val onHealthObserved: (EmbeddedEngineSessionHealthResult) -> Unit,
    private val onRuntimeStopped: (EmbeddedEngineSessionResult) -> Unit,
    private val onFailClosed: (String) -> Unit,
) {
    fun evaluate(): RuntimeSessionWatchdogOutcome {
        val health = healthProbe() ?: return RuntimeSessionWatchdogOutcome(evaluated = false)
        onHealthObserved(health)
        if (health.status != EmbeddedEngineSessionHealthStatus.FAILED) {
            return RuntimeSessionWatchdogOutcome(evaluated = true)
        }

        stopRuntime()?.let(onRuntimeStopped)
        onFailClosed(health.summary)
        return RuntimeSessionWatchdogOutcome(
            evaluated = true,
            failureReason = health.summary,
        )
    }
}
