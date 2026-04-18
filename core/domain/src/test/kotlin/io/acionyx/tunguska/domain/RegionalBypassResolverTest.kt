package io.acionyx.tunguska.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegionalBypassResolverTest {
    @Test
    fun `russia preset expands to direct suffix geosite and geoip rules`() {
        val profile = sampleProfile(
            routing = RoutingPolicy(
                regionalBypass = defaultRegionalBypass(),
            ),
        )

        val effective = EffectiveRoutingPolicyResolver.resolve(profile)
        val russiaRule = effective.rules.first { it.id == "__regional_bypass_russia__" }

        assertEquals(RouteAction.DIRECT, russiaRule.action)
        assertEquals(listOf("ru", "su", "xn--p1ai"), russiaRule.match.domainSuffix)
        assertEquals(listOf("ru"), russiaRule.match.geoSites)
        assertEquals(listOf("ru"), russiaRule.match.geoIps)
    }

    @Test
    fun `custom direct domains are normalized through idn`() {
        val profile = sampleProfile(
            routing = RoutingPolicy(
                regionalBypass = RegionalBypassSettings(
                    customDirectDomains = listOf("пример.рф", ".YaNDEX.RU"),
                ),
            ),
        )

        val effective = EffectiveRoutingPolicyResolver.resolve(profile)

        assertEquals(listOf("xn--e1afmkfd.xn--p1ai", "yandex.ru"), effective.normalizedCustomDirectDomains)
    }

    @Test
    fun `block rules stay ahead of generated regional bypass rules`() {
        val profile = sampleProfile(
            routing = RoutingPolicy(
                regionalBypass = defaultRegionalBypass(),
                rules = listOf(
                    RouteRule(
                        id = "block-ru-bank",
                        action = RouteAction.BLOCK,
                        match = RouteMatch(domainSuffix = listOf("bank.ru")),
                    ),
                    RouteRule(
                        id = "proxy-non-ru",
                        action = RouteAction.PROXY,
                        match = RouteMatch(domainSuffix = listOf("example.com")),
                    ),
                ),
            ),
        )

        val effective = EffectiveRoutingPolicyResolver.resolve(profile)

        assertEquals("block-ru-bank", effective.rules.first().id)
        assertTrue(effective.rules.indexOfFirst { it.id == "__regional_bypass_russia__" } <
            effective.rules.indexOfFirst { it.id == "proxy-non-ru" })
    }

    private fun sampleProfile(routing: RoutingPolicy): ProfileIr = ProfileIr(
        id = "regional",
        name = "Regional",
        outbound = VlessRealityOutbound(
            address = "edge.example.com",
            port = 443,
            uuid = "11111111-1111-1111-1111-111111111111",
            serverName = "cdn.example.com",
            realityPublicKey = "public-key",
            realityShortId = "abcd1234",
        ),
        routing = routing,
    )
}
