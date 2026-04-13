package io.acionyx.tunguska.engine.singbox

import io.acionyx.tunguska.domain.CanonicalJson
import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.EncryptedDnsKind
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
import kotlin.test.assertFalse
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SingboxEnginePluginTest {
    private val plugin = SingboxEnginePlugin()

    @Test
    fun `compiler emits deterministic payload and directives`() {
        val compiled = plugin.compile(sampleProfile())
        val json = CanonicalJson.instance.parseToJsonElement(compiled.payload).jsonObject
        val route = json.getValue("route").jsonObject
        val rules = route.getValue("rules").jsonArray
        val outbound = json.getValue("outbounds").jsonArray.first().jsonObject
        val dns = json.getValue("dns").jsonObject

        assertEquals("singbox", compiled.engineId)
        assertEquals(compiled.configHash, CanonicalJson.sha256Hex(compiled.payload))
        assertEquals("sniff", rules.first().jsonObject.getValue("action").jsonPrimitive.content)
        assertEquals("direct", rules[1].jsonObject.getValue("outbound").jsonPrimitive.content)
        assertEquals("hijack-dns", rules[2].jsonObject.getValue("action").jsonPrimitive.content)
        assertEquals("proxy", route.getValue("final").jsonPrimitive.content)
        assertEquals("edge.example.com", outbound.getValue("server").jsonPrimitive.content)
        assertEquals("dns-remote-0", dns.getValue("final").jsonPrimitive.content)
        assertEquals(SplitTunnelMode.Denylist(listOf("io.acionyx.excluded")), compiled.vpnDirectives.splitTunnelMode)
        assertFalse(compiled.payload.contains("allowInsecure"))
        assertFalse(compiled.payload.contains("\"type\":\"dns\""))
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
    vpn = VpnPolicy(
        splitTunnel = SplitTunnelMode.Denylist(listOf("io.acionyx.excluded")),
    ),
    dns = DnsMode.CustomEncrypted(
        kind = EncryptedDnsKind.DOH,
        endpoints = listOf("https://dns.example/dns-query"),
    ),
    routing = RoutingPolicy(
        rules = listOf(
            RouteRule(
                id = "corp-direct",
                action = RouteAction.DIRECT,
                match = RouteMatch(
                    domainSuffix = listOf("corp.example"),
                    ports = listOf(443),
                    protocols = listOf(NetworkProtocol.TCP),
                ),
            ),
        ),
    ),
)
