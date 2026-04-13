package io.acionyx.tunguska.vpnservice

import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.net.InetAddress

enum class TunnelBootstrapStatus {
    NOT_ATTEMPTED,
    ESTABLISHED,
    ESTABLISHED_AND_RELEASED,
    FAILED,
}

data class TunnelBootstrapResult(
    val status: TunnelBootstrapStatus,
    val summary: String,
    val bootstrappedAtEpochMs: Long,
)

data class TunnelInterfaceLease(
    val handle: TunnelInterfaceHandle,
    val spec: TunnelInterfaceSpec,
)

data class TunnelBootstrapLeaseResult(
    val result: TunnelBootstrapResult,
    val lease: TunnelInterfaceLease? = null,
)

fun interface TunnelInterfaceHandle {
    fun close()
}

interface TunnelRuntimeBuilder : TunnelBuilderAdapter {
    val unsupportedExcludedRoutes: List<IpSubnet>

    fun establish(): TunnelInterfaceHandle?
}

class TunnelRuntimeBootstrapper(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun establishLease(
        builder: TunnelRuntimeBuilder,
        sessionPlan: TunnelSessionPlan,
        spec: TunnelInterfaceSpec = TunnelInterfacePlanner.plan(sessionPlan),
    ): TunnelBootstrapLeaseResult {
        return runCatching {
            TunnelBuilderApplier.apply(builder, sessionPlan, spec)
            if (sessionPlan.preserveLoopback && builder.unsupportedExcludedRoutes.isNotEmpty()) {
                return failureLease(
                    "Loopback-preserving excludeRoute support requires Android 13 or newer; refusing bootstrap on this device.",
                )
            }

            val handle = builder.establish()
                ?: return failureLease("VpnService.Builder.establish() returned null for ${spec.sessionLabel}.")
            TunnelBootstrapLeaseResult(
                result = TunnelBootstrapResult(
                    status = TunnelBootstrapStatus.ESTABLISHED,
                    summary = "Established ${spec.sessionLabel} and retained the tunnel descriptor for the embedded engine runtime.",
                    bootstrappedAtEpochMs = clock(),
                ),
                lease = TunnelInterfaceLease(
                    handle = handle,
                    spec = spec,
                ),
            )
        }.getOrElse { error ->
            failureLease(error.message ?: error.javaClass.simpleName)
        }
    }

    fun establishAndRelease(
        builder: TunnelRuntimeBuilder,
        sessionPlan: TunnelSessionPlan,
        spec: TunnelInterfaceSpec = TunnelInterfacePlanner.plan(sessionPlan),
    ): TunnelBootstrapResult {
        val leaseResult = establishLease(
            builder = builder,
            sessionPlan = sessionPlan,
            spec = spec,
        )
        val lease = leaseResult.lease ?: return leaseResult.result
        return runCatching {
            lease.handle.close()
            TunnelBootstrapResult(
                status = TunnelBootstrapStatus.ESTABLISHED_AND_RELEASED,
                summary = "Established ${spec.sessionLabel} and released the tunnel descriptor in bootstrap-only mode.",
                bootstrappedAtEpochMs = clock(),
            )
        }.getOrElse { error ->
            failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun failure(summary: String): TunnelBootstrapResult = TunnelBootstrapResult(
        status = TunnelBootstrapStatus.FAILED,
        summary = summary,
        bootstrappedAtEpochMs = clock(),
    )

    private fun failureLease(summary: String): TunnelBootstrapLeaseResult = TunnelBootstrapLeaseResult(
        result = failure(summary),
    )
}

class AndroidTunnelRuntimeBuilder(
    service: VpnService,
) : TunnelRuntimeBuilder {
    private val builder = service.Builder()
    private val unsupportedExcludedRoutesMutable = mutableListOf<IpSubnet>()

    override val unsupportedExcludedRoutes: List<IpSubnet>
        get() = unsupportedExcludedRoutesMutable.toList()

    override fun setSession(label: String) {
        builder.setSession(label)
    }

    override fun setMtu(mtu: Int) {
        builder.setMtu(mtu)
    }

    override fun addAddress(subnet: IpSubnet) {
        builder.addAddress(subnet.address, subnet.prefixLength)
    }

    override fun addRoute(subnet: IpSubnet) {
        builder.addRoute(subnet.address, subnet.prefixLength)
    }

    override fun excludeRoute(subnet: IpSubnet) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.excludeRoute(
                IpPrefix(InetAddress.getByName(subnet.address), subnet.prefixLength),
            )
        } else {
            unsupportedExcludedRoutesMutable += subnet
        }
    }

    override fun addAllowedApplication(packageName: String) {
        try {
            builder.addAllowedApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("Allowed application '$packageName' is not installed.")
        }
    }

    override fun addDisallowedApplication(packageName: String) {
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("Disallowed application '$packageName' is not installed.")
        }
    }

    override fun establish(): TunnelInterfaceHandle? {
        return builder.establish()?.let(::ParcelFileDescriptorHandle)
    }
}

private class ParcelFileDescriptorHandle(
    private val descriptor: ParcelFileDescriptor,
) : TunnelInterfaceHandle {
    override fun close() {
        descriptor.close()
    }
}
