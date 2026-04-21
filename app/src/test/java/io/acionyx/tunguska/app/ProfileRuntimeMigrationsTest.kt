package io.acionyx.tunguska.app

import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.VlessRealityOutbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRuntimeMigrationsTest {
    @Test
    fun `migration rewrites legacy default doh over ip to system dns`() {
        val legacyProfile = sampleProfile(
            dns = DnsMode.VpnDns(
                servers = listOf(
                    "https://1.1.1.1/dns-query",
                    "https://1.0.0.1/dns-query",
                ),
            ),
        )

        val result = ProfileRuntimeMigrations.migrate(legacyProfile)

        assertEquals(DnsMode.SystemDns, result.profile.dns)
        assertNotNull(result.status)
        assertTrue(result.status!!.contains("System DNS"))
    }

    @Test
    fun `migration preserves non legacy dns configurations`() {
        val customProfile = sampleProfile(
            dns = DnsMode.VpnDns(
                servers = listOf("https://cloudflare-dns.com/dns-query"),
            ),
        )

        val result = ProfileRuntimeMigrations.migrate(customProfile)

        assertEquals(customProfile, result.profile)
        assertEquals(null, result.status)
    }

    @Test
    fun `migration does not silently enable russia direct on existing profiles`() {
        val legacyProfile = sampleProfile(
            dns = DnsMode.SystemDns,
        )

        val result = ProfileRuntimeMigrations.migrate(legacyProfile)

        assertEquals(legacyProfile, result.profile)
        assertEquals(null, result.status)
    }

    private fun sampleProfile(dns: DnsMode): ProfileIr = ProfileIr(
        id = "alpha-secure",
        name = "Alpha Secure",
        outbound = VlessRealityOutbound(
            address = "edge.example.com",
            port = 443,
            uuid = "11111111-1111-1111-1111-111111111111",
            serverName = "cdn.example.com",
            realityPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            realityShortId = "abcd1234",
            flow = null,
            utlsFingerprint = "chrome",
        ),
        dns = dns,
    )
}
