package io.acionyx.tunguska.storage

import io.acionyx.tunguska.crypto.SoftwareAesGcmCipherBox
import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.RouteMatch
import io.acionyx.tunguska.domain.RouteRule
import io.acionyx.tunguska.domain.RoutingPolicy
import io.acionyx.tunguska.domain.SafetySettings
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.VlessRealityOutbound
import io.acionyx.tunguska.domain.VpnPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class EncryptedProfileStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `save then load round trip preserves profile and hash`() {
        val store = buildStore(tempDir.resolve("profiles/default.json.enc"))
        val profile = sampleProfile()

        val saved = store.save(profile)
        val loaded = store.load()

        requireNotNull(loaded)
        assertEquals(saved.profileHash, loaded.profileHash)
        assertEquals(profile, loaded.profile)
        assertEquals(store.path, loaded.path)
    }

    @Test
    fun `load returns null when no profile is stored`() {
        val store = buildStore(tempDir.resolve("profiles/default.json.enc"))

        assertNull(store.load())
    }

    @Test
    fun `load fails when stored hash is tampered`() {
        val path = tempDir.resolve("profiles/default.json.enc")
        val store = buildStore(path)
        store.save(sampleProfile())
        val mutated = Files.readString(path).replace("\"profileHash\":\"", "\"profileHash\":\"tampered-")
        Files.writeString(path, mutated)

        assertFailsWith<IllegalArgumentException> {
            store.load()
        }
    }

    @Test
    fun `invalid profiles are rejected before encryption`() {
        val store = buildStore(tempDir.resolve("profiles/default.json.enc"))
        val invalidProfile = sampleProfile().copy(
            outbound = sampleProfile().outbound.copy(port = 70000),
        )

        assertFailsWith<IllegalArgumentException> {
            store.save(invalidProfile)
        }
    }

    private fun buildStore(path: Path): EncryptedProfileStore = EncryptedProfileStore(
        path = path,
        cipherBox = SoftwareAesGcmCipherBox(SoftwareAesGcmCipherBox.generateKey()),
    )

    private fun sampleProfile(): ProfileIr = ProfileIr(
        id = "alpha-secure",
        name = "Alpha Secure",
        outbound = VlessRealityOutbound(
            address = "edge.example.com",
            port = 443,
            uuid = "11111111-1111-1111-1111-111111111111",
            serverName = "cdn.example.com",
            realityPublicKey = "public-key",
            realityShortId = "abcd1234",
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
                    id = "corp-direct",
                    action = RouteAction.DIRECT,
                    match = RouteMatch(
                        domainSuffix = listOf("corp.example"),
                        ports = listOf(443),
                    ),
                ),
            ),
        ),
        dns = DnsMode.SystemDns,
        safety = SafetySettings(
            safeMode = true,
            compatibilityLocalProxy = false,
            debugEndpointsEnabled = false,
        ),
    )
}
