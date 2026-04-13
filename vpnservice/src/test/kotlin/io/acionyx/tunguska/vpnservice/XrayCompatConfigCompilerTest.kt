package io.acionyx.tunguska.vpnservice

import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.RouteMatch
import io.acionyx.tunguska.domain.RouteRule
import io.acionyx.tunguska.domain.RoutingPolicy
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.VlessRealityOutbound
import io.acionyx.tunguska.domain.VpnPolicy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class XrayCompatConfigCompilerTest {
    @Test
    fun `compiler binds authenticated socks bridge to loopback only`() {
        val compiled = XrayCompatConfigCompiler.compile(
            profile = sampleProfile(),
            bridge = AuthenticatedLocalBridge(
                port = 25001,
                user = "bridge-user",
                password = "bridge-pass",
            ),
        )

        val root = Json.parseToJsonElement(compiled.json).jsonObject
        val inbound = root.getValue("inbounds").jsonArray.single().jsonObject
        val settings = inbound.getValue("settings").jsonObject
        val account = settings.getValue("accounts").jsonArray.single().jsonObject

        assertEquals("127.0.0.1", inbound.getValue("listen").jsonPrimitive.content)
        assertEquals(25001, inbound.getValue("port").jsonPrimitive.content.toInt())
        assertEquals("password", settings.getValue("auth").jsonPrimitive.content)
        assertEquals("127.0.0.1", settings.getValue("ip").jsonPrimitive.content)
        assertTrue(settings.getValue("udp").jsonPrimitive.content.toBoolean())
        assertEquals("bridge-user", account.getValue("user").jsonPrimitive.content)
        assertEquals("bridge-pass", account.getValue("pass").jsonPrimitive.content)
    }

    @Test
    fun `compiler does not expose management or debug surfaces`() {
        val compiled = XrayCompatConfigCompiler.compile(
            profile = sampleProfile(),
            bridge = AuthenticatedLocalBridge(
                port = 25001,
                user = "bridge-user",
                password = "bridge-pass",
            ),
        )

        val root = Json.parseToJsonElement(compiled.json).jsonObject

        assertFalse(root.containsKey("api"))
        assertFalse(root.containsKey("stats"))
        assertFalse(root.containsKey("reverse"))
        assertFalse(root.containsKey("observatory"))
        assertFalse(root.containsKey("burstObservatory"))
        assertEquals("warning", root.getValue("log").jsonObject.getValue("loglevel").jsonPrimitive.content)
    }

    @Test
    fun `compiler preserves proxy default route and custom direct bypass rules`() {
        val compiled = XrayCompatConfigCompiler.compile(
            profile = sampleProfile(),
            bridge = AuthenticatedLocalBridge(
                port = 25001,
                user = "bridge-user",
                password = "bridge-pass",
            ),
        )

        val root = Json.parseToJsonElement(compiled.json).jsonObject
        val rules = root.getValue("routing").jsonObject.getValue("rules").jsonArray

        assertTrue(
            rules.any { rule ->
                val json = rule.jsonObject
                json["outboundTag"]?.jsonPrimitive?.content == "direct" &&
                    json["domain"]?.jsonArray?.any { it.jsonPrimitive.content == "bank.example" } == true
            },
        )
        assertTrue(
            rules.any { rule ->
                val json = rule.jsonObject
                json["outboundTag"]?.jsonPrimitive?.content == "proxy" &&
                    json["inboundTag"]?.jsonArray?.any { it.jsonPrimitive.content == "socks-in" } == true
            },
        )
    }

    private fun sampleProfile(): ProfileIr = ProfileIr(
        id = "alpha-secure",
        name = "Alpha Secure",
        outbound = VlessRealityOutbound(
            address = "edge.example.com",
            port = 443,
            uuid = "11111111-1111-1111-1111-111111111111",
            serverName = "www.microsoft.com",
            realityPublicKey = "69HNUl7KDDAnCPYBc6Yjp1KlUsw2bZ_z6vZ69W1Z300",
            realityShortId = "79",
            flow = null,
            utlsFingerprint = "chrome",
        ),
        vpn = VpnPolicy(
            splitTunnel = SplitTunnelMode.Denylist(
                packageNames = listOf("io.acionyx.bank"),
            ),
        ),
        routing = RoutingPolicy(
            defaultAction = RouteAction.PROXY,
            rules = listOf(
                RouteRule(
                    id = "bank-direct",
                    action = RouteAction.DIRECT,
                    match = RouteMatch(domainExact = listOf("bank.example")),
                ),
            ),
        ),
        dns = DnsMode.SystemDns,
    )
}
