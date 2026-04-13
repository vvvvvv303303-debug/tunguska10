package io.acionyx.tunguska.vpnservice

import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.RoutingPolicy
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.VlessRealityOutbound
import io.acionyx.tunguska.domain.VpnPolicy
import io.acionyx.tunguska.engine.singbox.SingboxEnginePlugin
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class SingboxTunnelParityTest {
    private val plugin = SingboxEnginePlugin()

    @Test
    fun `planner stays aligned with compiled sing-box tun inbound`() {
        val compiled = plugin.compile(sampleProfile())
        val plan = TunnelSessionPlanner.plan(compiled)
        val spec = TunnelInterfacePlanner.plan(plan)

        assertTrue(compiled.payload.contains("\"type\":\"tun\""))
        assertTrue(compiled.payload.contains(""""mtu":${spec.mtu}"""))
        spec.addresses
            .map { "${it.address}/${it.prefixLength}" }
            .forEach { address ->
                assertTrue(compiled.payload.contains(address), "Compiled payload is missing $address")
            }
        assertEquals(2, spec.routes.size)
    }
}

private fun sampleProfile(): ProfileIr = ProfileIr(
    id = "alpha",
    name = "Alpha",
    outbound = VlessRealityOutbound(
        address = "edge.example.com",
        port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        serverName = "www.microsoft.com",
        realityPublicKey = "public-key",
        realityShortId = "abcd1234",
    ),
    vpn = VpnPolicy(
        splitTunnel = SplitTunnelMode.FullTunnel,
    ),
    dns = DnsMode.SystemDns,
    routing = RoutingPolicy(),
)
