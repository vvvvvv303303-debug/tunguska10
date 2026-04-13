package io.acionyx.tunguska.vpnservice

import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.engine.api.CompiledEngineConfig
import io.acionyx.tunguska.engine.api.VpnDirectives
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class TunnelRuntimeBootstrapTest {
    @Test
    fun `bootstrap can retain descriptor for active runtime sessions`() {
        val builder = FakeRuntimeBuilder(
            unsupportedExcludedRoutes = emptyList(),
            establishResult = FakeTunnelHandle(),
        )
        val plan = samplePlan()

        val result = TunnelRuntimeBootstrapper(clock = { 1234L }).establishLease(builder, plan)

        assertEquals(TunnelBootstrapStatus.ESTABLISHED, result.result.status)
        assertEquals(1234L, result.result.bootstrappedAtEpochMs)
        assertTrue(builder.establishCalled)
        assertFalse((builder.establishResult as FakeTunnelHandle).closed)
        result.lease?.handle?.close()
    }

    @Test
    fun `bootstrap establishes and releases descriptor when exclusions are supported`() {
        val builder = FakeRuntimeBuilder(
            unsupportedExcludedRoutes = emptyList(),
            establishResult = FakeTunnelHandle(),
        )
        val plan = samplePlan()

        val result = TunnelRuntimeBootstrapper(clock = { 1234L }).establishAndRelease(builder, plan)

        assertEquals(TunnelBootstrapStatus.ESTABLISHED_AND_RELEASED, result.status)
        assertEquals(1234L, result.bootstrappedAtEpochMs)
        assertTrue(builder.establishCalled)
        assertTrue((builder.establishResult as FakeTunnelHandle).closed)
    }

    @Test
    fun `bootstrap fails when loopback exclusions are unsupported`() {
        val builder = FakeRuntimeBuilder(
            unsupportedExcludedRoutes = listOf(IpSubnet(address = "127.0.0.0", prefixLength = 8)),
            establishResult = FakeTunnelHandle(),
        )
        val plan = samplePlan()

        val result = TunnelRuntimeBootstrapper(clock = { 1234L }).establishAndRelease(builder, plan)

        assertEquals(TunnelBootstrapStatus.FAILED, result.status)
        assertFalse(builder.establishCalled)
        assertTrue(result.summary.contains("Android 13"))
    }

    @Test
    fun `bootstrap fails when establish returns null`() {
        val builder = FakeRuntimeBuilder(
            unsupportedExcludedRoutes = emptyList(),
            establishResult = null,
        )
        val plan = samplePlan()

        val result = TunnelRuntimeBootstrapper(clock = { 1234L }).establishAndRelease(builder, plan)

        assertEquals(TunnelBootstrapStatus.FAILED, result.status)
        assertTrue(builder.establishCalled)
        assertTrue(result.summary.contains("returned null"))
    }

    private fun samplePlan(): TunnelSessionPlan = TunnelSessionPlanner.plan(
        CompiledEngineConfig(
            engineId = "singbox",
            format = "application/json",
            payload = "{}",
            configHash = "abc123",
            vpnDirectives = VpnDirectives(
                preserveLoopback = true,
                splitTunnelMode = SplitTunnelMode.FullTunnel,
                safeMode = true,
            ),
        ),
    )
}

private class FakeRuntimeBuilder(
    override val unsupportedExcludedRoutes: List<IpSubnet>,
    val establishResult: TunnelInterfaceHandle?,
) : TunnelRuntimeBuilder {
    var establishCalled: Boolean = false

    override fun setSession(label: String) = Unit

    override fun setMtu(mtu: Int) = Unit

    override fun addAddress(subnet: IpSubnet) = Unit

    override fun addRoute(subnet: IpSubnet) = Unit

    override fun excludeRoute(subnet: IpSubnet) = Unit

    override fun addAllowedApplication(packageName: String) = Unit

    override fun addDisallowedApplication(packageName: String) = Unit

    override fun establish(): TunnelInterfaceHandle? {
        establishCalled = true
        return establishResult
    }
}

private class FakeTunnelHandle : TunnelInterfaceHandle {
    var closed: Boolean = false

    override fun close() {
        closed = true
    }
}
