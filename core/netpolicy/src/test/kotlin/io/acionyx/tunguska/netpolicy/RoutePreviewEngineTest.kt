package io.acionyx.tunguska.netpolicy

import io.acionyx.tunguska.domain.NetworkProtocol
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.RouteMatch
import io.acionyx.tunguska.domain.RouteRule
import io.acionyx.tunguska.domain.RoutingPolicy
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.VlessRealityOutbound
import io.acionyx.tunguska.domain.VpnPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutePreviewEngineTest {
    private val engine = RoutePreviewEngine()

    @Test
    fun `loopback always bypasses vpn`() {
        val outcome = engine.evaluate(
            profile = sampleProfile(),
            request = RoutePreviewRequest(destinationHost = "localhost", destinationPort = 8080),
        )

        assertEquals(RouteAction.DIRECT, outcome.action)
        assertEquals("__loopback__", outcome.matchedRuleId)
    }

    @Test
    fun `allowlist excludes packages outside vpn`() {
        val outcome = engine.evaluate(
            profile = sampleProfile().copy(
                vpn = VpnPolicy(
                    splitTunnel = SplitTunnelMode.Allowlist(listOf("io.acionyx.allowed")),
                ),
            ),
            request = RoutePreviewRequest(
                packageName = "io.acionyx.blocked",
                destinationHost = "api.example.com",
                destinationPort = 443,
            ),
        )

        assertEquals(RouteAction.DIRECT, outcome.action)
        assertEquals("__split_tunnel_allowlist__", outcome.matchedRuleId)
    }

    @Test
    fun `ordered rule matching is deterministic`() {
        val outcome = engine.evaluate(
            profile = sampleProfile(),
            request = RoutePreviewRequest(
                packageName = "io.acionyx.browser",
                destinationHost = "login.corp.example",
                destinationPort = 443,
                protocol = NetworkProtocol.TCP,
            ),
        )

        assertEquals(RouteAction.DIRECT, outcome.action)
        assertEquals("corp-direct", outcome.matchedRuleId)
    }
}

private fun sampleProfile(): ProfileIr = ProfileIr(
    id = "alpha",
    name = "Alpha",
    outbound = VlessRealityOutbound(
        address = "edge.example.com",
        port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        serverName = "cdn.example.com",
        realityPublicKey = "public-key",
        realityShortId = "abcd1234",
    ),
    routing = RoutingPolicy(
        defaultAction = RouteAction.PROXY,
        rules = listOf(
            RouteRule(
                id = "corp-direct",
                action = RouteAction.DIRECT,
                match = RouteMatch(
                    domainSuffix = listOf("corp.example"),
                    ports = listOf(443),
                ),
            ),
        ),
    ),
)
