package io.acionyx.tunguska.vpnservice

import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.engine.api.CompiledEngineConfig

data class TunnelSessionPlan(
    val processNameSuffix: String = ":vpn",
    val preserveLoopback: Boolean,
    val allowedPackages: List<String>,
    val disallowedPackages: List<String>,
    val splitTunnelMode: SplitTunnelMode,
    val runtimeMode: RuntimeMode,
    val configHash: String,
) {
    enum class RuntimeMode {
        FAIL_CLOSED_UNTIL_ENGINE_HOST,
    }
}

object TunnelSessionPlanner {
    fun plan(compiled: CompiledEngineConfig): TunnelSessionPlan {
        val splitTunnel = compiled.vpnDirectives.splitTunnelMode
        return TunnelSessionPlan(
            preserveLoopback = compiled.vpnDirectives.preserveLoopback,
            allowedPackages = when (splitTunnel) {
                is SplitTunnelMode.Allowlist -> splitTunnel.packageNames
                else -> emptyList()
            },
            disallowedPackages = when (splitTunnel) {
                is SplitTunnelMode.Denylist -> splitTunnel.packageNames
                else -> emptyList()
            },
            splitTunnelMode = splitTunnel,
            runtimeMode = TunnelSessionPlan.RuntimeMode.FAIL_CLOSED_UNTIL_ENGINE_HOST,
            configHash = compiled.configHash,
        )
    }
}
