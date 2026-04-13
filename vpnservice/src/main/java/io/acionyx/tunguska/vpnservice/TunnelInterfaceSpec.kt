package io.acionyx.tunguska.vpnservice

import io.acionyx.tunguska.domain.SplitTunnelMode

data class TunnelInterfaceSpec(
    val sessionLabel: String,
    val mtu: Int,
    val addresses: List<IpSubnet>,
    val routes: List<IpSubnet>,
    val excludedRoutes: List<IpSubnet>,
)

data class IpSubnet(
    val address: String,
    val prefixLength: Int,
)

object TunnelInterfacePlanner {
    fun plan(sessionPlan: TunnelSessionPlan): TunnelInterfaceSpec = TunnelInterfaceSpec(
        sessionLabel = "Tunguska ${sessionPlan.configHash.take(8)}",
        mtu = 9000,
        addresses = listOf(
            IpSubnet(address = "172.19.0.1", prefixLength = 30),
            IpSubnet(address = "fdfe:dcba:9876::1", prefixLength = 126),
        ),
        routes = listOf(
            IpSubnet(address = "0.0.0.0", prefixLength = 0),
            IpSubnet(address = "::", prefixLength = 0),
        ),
        excludedRoutes = if (sessionPlan.preserveLoopback) {
            listOf(
                IpSubnet(address = "127.0.0.0", prefixLength = 8),
                IpSubnet(address = "::1", prefixLength = 128),
            )
        } else {
            emptyList()
        },
    )
}

interface TunnelBuilderAdapter {
    fun setSession(label: String)
    fun setMtu(mtu: Int)
    fun addAddress(subnet: IpSubnet)
    fun addRoute(subnet: IpSubnet)
    fun excludeRoute(subnet: IpSubnet)
    fun addAllowedApplication(packageName: String)
    fun addDisallowedApplication(packageName: String)
}

object TunnelBuilderApplier {
    fun apply(
        builder: TunnelBuilderAdapter,
        sessionPlan: TunnelSessionPlan,
        spec: TunnelInterfaceSpec = TunnelInterfacePlanner.plan(sessionPlan),
    ) {
        builder.setSession(spec.sessionLabel)
        builder.setMtu(spec.mtu)
        spec.addresses.forEach(builder::addAddress)
        spec.routes.forEach(builder::addRoute)
        spec.excludedRoutes.forEach(builder::excludeRoute)

        when (val splitTunnel = sessionPlan.splitTunnelMode) {
            SplitTunnelMode.FullTunnel -> Unit
            is SplitTunnelMode.Allowlist -> {
                splitTunnel.packageNames.forEach(builder::addAllowedApplication)
            }

            is SplitTunnelMode.Denylist -> {
                splitTunnel.packageNames.forEach(builder::addDisallowedApplication)
            }
        }
    }
}
