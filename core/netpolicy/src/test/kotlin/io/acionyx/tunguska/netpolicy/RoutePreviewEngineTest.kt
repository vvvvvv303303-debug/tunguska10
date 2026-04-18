package io.acionyx.tunguska.netpolicy

import io.acionyx.tunguska.domain.NetworkProtocol
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.RegionalBypassSettings
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.RouteMatch
import io.acionyx.tunguska.domain.RouteRule
import io.acionyx.tunguska.domain.RegionalBypassPresetId
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

    @Test
    fun `russia preset sends ru domains direct`() {
        val outcome = engine.evaluate(
            profile = sampleProfile().copy(
                routing = sampleProfile().routing.copy(
                    regionalBypass = RegionalBypassSettings(
                        enabledPresets = listOf(RegionalBypassPresetId.RUSSIA),
                    ),
                ),
            ),
            request = RoutePreviewRequest(
                destinationHost = "yandex.ru",
                destinationPort = 443,
            ),
        )

        assertEquals(RouteAction.DIRECT, outcome.action)
        assertEquals("__regional_bypass_russia__", outcome.matchedRuleId)
    }

    @Test
    fun `block rules override russia direct`() {
        val outcome = engine.evaluate(
            profile = sampleProfile().copy(
                routing = RoutingPolicy(
                    defaultAction = RouteAction.PROXY,
                    regionalBypass = RegionalBypassSettings(
                        enabledPresets = listOf(RegionalBypassPresetId.RUSSIA),
                    ),
                    rules = listOf(
                        RouteRule(
                            id = "block-bank-ru",
                            action = RouteAction.BLOCK,
                            match = RouteMatch(domainSuffix = listOf("bank.ru")),
                        ),
                    ),
                ),
            ),
            request = RoutePreviewRequest(
                destinationHost = "online.bank.ru",
                destinationPort = 443,
            ),
        )

        assertEquals(RouteAction.BLOCK, outcome.action)
        assertEquals("block-bank-ru", outcome.matchedRuleId)
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
