package io.acionyx.tunguska.vpnservice

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface as JvmNetworkInterface
import java.security.KeyStore
import java.util.Locale

class LibboxEmbeddedHost(
    private val environmentFactory: (Context) -> LibboxRuntimeEnvironment = { context ->
        LibboxRuntimeEnvironment(context)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) : EmbeddedEngineHost {
    override val engineId: String = "singbox"
    override val strategyId: EmbeddedRuntimeStrategyId = EmbeddedRuntimeStrategyId.LIBBOX

    override fun prepare(
        workspace: EngineSessionWorkspace,
        request: StagedRuntimeRequest,
        runtimeDependencies: EmbeddedRuntimeDependencies,
    ): EmbeddedEngineHostPreparation {
        val service = runtimeDependencies.vpnService
        val context = runtimeDependencies.context ?: service?.applicationContext
        if (service == null || context == null) {
            return EmbeddedEngineHostPreparation(
                result = EmbeddedEngineHostResult(
                    status = EmbeddedEngineHostStatus.FAILED,
                    summary = "Embedded sing-box host requires a live VpnService runtime context.",
                    preparedAtEpochMs = clock(),
                    workspacePath = workspace.rootDir.absolutePath,
                ),
            )
        }
        if (request.compiledConfig.format != "application/json") {
            return EmbeddedEngineHostPreparation(
                result = EmbeddedEngineHostResult(
                    status = EmbeddedEngineHostStatus.FAILED,
                    summary = "Embedded sing-box host only accepts application/json configs.",
                    preparedAtEpochMs = clock(),
                    workspacePath = workspace.rootDir.absolutePath,
                ),
            )
        }
        return runCatching {
            environmentFactory(context.applicationContext).ensureSetup()
            Libbox.checkConfig(request.compiledConfig.payload)
            EmbeddedEngineHostPreparation(
                result = EmbeddedEngineHostResult(
                    status = EmbeddedEngineHostStatus.READY,
                    summary = "Prepared embedded sing-box runtime for ${request.compiledConfig.configHash.take(12)}.",
                    preparedAtEpochMs = clock(),
                    workspacePath = workspace.rootDir.absolutePath,
                ),
                session = LibboxEmbeddedEngineSession(
                    service = service,
                    request = request,
                    clock = clock,
                ),
            )
        }.getOrElse { error ->
            EmbeddedEngineHostPreparation(
                result = EmbeddedEngineHostResult(
                    status = EmbeddedEngineHostStatus.FAILED,
                    summary = error.message ?: error.javaClass.simpleName,
                    preparedAtEpochMs = clock(),
                    workspacePath = workspace.rootDir.absolutePath,
                ),
            )
        }
    }
}

class LibboxRuntimeEnvironment(
    private val context: Context,
) {
    fun ensureSetup() {
        synchronized(lock) {
            val baseDir = File(context.filesDir, "libbox").apply { mkdirs() }
            val workingDir = File(baseDir, "working").apply { mkdirs() }
            val tempDir = File(context.cacheDir, "libbox").apply { mkdirs() }
            runCatching {
                Libbox.setLocale(Locale.getDefault().toLanguageTag().replace("-", "_"))
            }
            val options = SetupOptions().apply {
                basePath = baseDir.absolutePath
                workingPath = workingDir.absolutePath
                tempPath = tempDir.absolutePath
                fixAndroidStack = false
                logMaxLines = 256
                debug = false
                crashReportSource = "TunguskaVpnService"
            }
            if (!isSetup) {
                Libbox.setup(options)
                isSetup = true
            } else {
                Libbox.reloadSetupOptions(options)
            }
        }
    }

    private companion object {
        private val lock = Any()
        private var isSetup: Boolean = false
    }
}

private class LibboxEmbeddedEngineSession(
    private val service: VpnService,
    private val request: StagedRuntimeRequest,
    private val clock: () -> Long,
) : EmbeddedEngineSession {
    private val lock = Any()
    private var platform: LibboxPlatformBridge? = null
    private var commandServer: CommandServer? = null
    private var coreStopReason: String? = null

    override fun start(): EmbeddedEngineSessionResult {
        synchronized(lock) {
            if (commandServer != null) {
                return failure("Embedded sing-box runtime is already active.")
            }
        }
        val platformBridge = LibboxPlatformBridge(
            service = service,
            sessionLabel = TunnelInterfacePlanner.plan(request.plan).sessionLabel,
            onBootstrap = VpnRuntimeStore::recordBootstrap,
            clock = clock,
        )
        val server = CommandServer(
            LibboxCommandHandler(
                systemProxyStatusProvider = {
                    SystemProxyStatus().apply {
                        available = false
                        enabled = false
                    }
                },
                onServiceStop = { reason ->
                    coreStopReason = reason
                    platformBridge.closeTun()
                },
            ),
            platformBridge,
        )
        return runCatching {
            server.start()
            server.startOrReloadService(
                request.compiledConfig.payload,
                request.plan.toOverrideOptions(service.packageName),
            )
            if (server.needWIFIState()) {
                error("WIFI-state dependent sing-box profiles are not supported by the secure MVP runtime.")
            }
            val bootstrap = platformBridge.lastBootstrapResult()
                ?: error("Embedded sing-box runtime did not request a TUN interface.")
            if (bootstrap.status != TunnelBootstrapStatus.ESTABLISHED) {
                error(bootstrap.summary)
            }
            if (!platformBridge.hasEstablishedTun()) {
                error("Embedded sing-box runtime failed to retain the established TUN descriptor.")
            }
            synchronized(lock) {
                platform = platformBridge
                commandServer = server
            }
            EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.STARTED,
                summary = "Embedded sing-box runtime started for ${request.compiledConfig.configHash.take(12)}.",
                observedAtEpochMs = clock(),
            )
        }.getOrElse { error ->
            runCatching { server.closeService() }
            runCatching { server.close() }
            platformBridge.closeTun()
            failure(error.message ?: error.javaClass.simpleName)
        }
    }

    override fun stop(): EmbeddedEngineSessionResult {
        val current = synchronized(lock) {
            val snapshot = commandServer to platform
            commandServer = null
            platform = null
            coreStopReason = null
            snapshot
        }
        val server = current.first
        val platformBridge = current.second
        if (server == null && platformBridge == null) {
            return EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.STOPPED,
                summary = "Embedded sing-box runtime is already stopped.",
                observedAtEpochMs = clock(),
            )
        }
        val errors = mutableListOf<String>()
        if (server != null) {
            runCatching { server.closeService() }.exceptionOrNull()?.message?.let(errors::add)
            runCatching { server.close() }.exceptionOrNull()?.message?.let(errors::add)
        }
        platformBridge?.closeTun()?.let(errors::add)
        return if (errors.isEmpty()) {
            EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.STOPPED,
                summary = "Embedded sing-box runtime stopped.",
                observedAtEpochMs = clock(),
            )
        } else {
            failure(errors.first())
        }
    }

    override fun health(): EmbeddedEngineSessionHealthResult {
        val platformBridge = synchronized(lock) { platform }
            ?: return EmbeddedEngineSessionHealthResult(
                status = EmbeddedEngineSessionHealthStatus.FAILED,
                summary = coreStopReason ?: "Embedded sing-box runtime is not active.",
                observedAtEpochMs = clock(),
            )
        coreStopReason?.let { reason ->
            return EmbeddedEngineSessionHealthResult(
                status = EmbeddedEngineSessionHealthStatus.FAILED,
                summary = reason,
                observedAtEpochMs = clock(),
            )
        }
        return runCatching {
            val startedAt = Libbox.newStandaloneCommandClient().startedAt
            if (!platformBridge.hasEstablishedTun()) {
                EmbeddedEngineSessionHealthResult(
                    status = EmbeddedEngineSessionHealthStatus.FAILED,
                    summary = "Embedded sing-box runtime lost its TUN descriptor.",
                    observedAtEpochMs = clock(),
                )
            } else {
                EmbeddedEngineSessionHealthResult(
                    status = EmbeddedEngineSessionHealthStatus.HEALTHY,
                    summary = "Embedded sing-box runtime is healthy; startedAt=$startedAt.",
                    observedAtEpochMs = clock(),
                )
            }
        }.getOrElse { error ->
            EmbeddedEngineSessionHealthResult(
                status = EmbeddedEngineSessionHealthStatus.FAILED,
                summary = error.message ?: error.javaClass.simpleName,
                observedAtEpochMs = clock(),
            )
        }
    }

    private fun failure(message: String): EmbeddedEngineSessionResult = EmbeddedEngineSessionResult(
        status = EmbeddedEngineSessionStatus.FAILED,
        summary = message,
        observedAtEpochMs = clock(),
    )
}

private class LibboxCommandHandler(
    private val systemProxyStatusProvider: () -> SystemProxyStatus,
    private val onServiceStop: (String) -> Unit,
) : CommandServerHandler {
    override fun getSystemProxyStatus(): SystemProxyStatus = systemProxyStatusProvider()

    override fun serviceReload() = Unit

    override fun serviceStop() {
        onServiceStop("Embedded sing-box runtime requested service stop.")
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        if (isEnabled) {
            error("System HTTP proxy integration is disabled in safe mode.")
        }
    }

    override fun triggerNativeCrash() {
        error("Native crash triggering is disabled in release runtime.")
    }

    override fun writeDebugMessage(message: String?) = Unit
}

private class LibboxPlatformBridge(
    private val service: VpnService,
    private val sessionLabel: String,
    private val onBootstrap: (TunnelBootstrapResult) -> Unit,
    private val clock: () -> Long,
) : PlatformInterface {
    private val lock = Any()
    private val connectivityManager = service.getSystemService(ConnectivityManager::class.java)
    private var tunnelDescriptor: ParcelFileDescriptor? = null
    private var bootstrapResult: TunnelBootstrapResult? = null

    fun hasEstablishedTun(): Boolean = synchronized(lock) {
        tunnelDescriptor != null && bootstrapResult?.status == TunnelBootstrapStatus.ESTABLISHED
    }

    fun lastBootstrapResult(): TunnelBootstrapResult? = synchronized(lock) { bootstrapResult }

    fun closeTun(): String? {
        val descriptor = synchronized(lock) {
            val current = tunnelDescriptor
            tunnelDescriptor = null
            current
        } ?: return null
        return runCatching {
            descriptor.close()
            null
        }.getOrElse { error ->
            error.message ?: error.javaClass.simpleName
        }
    }

    override fun openTun(options: TunOptions): Int {
        val opened = establishTun(options)
        synchronized(lock) {
            bootstrapResult = opened.result
            tunnelDescriptor = opened.descriptor
        }
        onBootstrap(opened.result)
        return opened.descriptor?.fd ?: error(opened.result.summary)
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        service.protect(fd)
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Connection owner discovery requires Android 10 or newer."
        }
        val uid = connectivityManager.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) {
            error("android: connection owner not found")
        }
        return ConnectionOwner().apply {
            userId = uid
            userName = service.packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
            setAndroidPackageNames(StringArray(service.packageManager.getPackagesForUid(uid).orEmpty().iterator()))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = JvmNetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { network ->
            NetworkInterface().apply {
                name = network.name
                index = network.index
                mtu = runCatching { network.mtu }.getOrDefault(0)
                flags = network.dumpFlags()
                metered = false
                addresses = StringArray(network.interfaceAddresses.map(InterfaceAddress::toPrefix).iterator())
                type = detectType(network.name)
            }
        }
        return NetworkInterfaceArray(interfaces.iterator())
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() = Unit

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun systemCertificates(): StringIterator {
        val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        val certificates = mutableListOf<String>()
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val certificate = keyStore.getCertificate(aliases.nextElement())
            val encoded = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded)
            certificates += "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----"
        }
        return StringArray(certificates.iterator())
    }

    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun registerMyInterface(name: String?) = Unit

    override fun sendNotification(notification: Notification) = Unit

    private fun establishTun(options: TunOptions): TunOpenResult {
        if (VpnService.prepare(service) != null) {
            return failure("VPN permission is missing or was revoked before the embedded runtime could start.")
        }
        val builder = service.Builder()
            .setSession(sessionLabel)
            .setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val inet4Addresses = options.inet4Address.toList()
        val inet6Addresses = options.inet6Address.toList()
        val inet4RouteAddresses = options.inet4RouteAddress.toList()
        val inet6RouteAddresses = options.inet6RouteAddress.toList()
        val inet4RouteExcludes = options.inet4RouteExcludeAddress.toList()
        val inet6RouteExcludes = options.inet6RouteExcludeAddress.toList()
        val inet4RouteRanges = options.inet4RouteRange.toList()
        val inet6RouteRanges = options.inet6RouteRange.toList()
        val includePackages = options.includePackage.toList()
        val excludePackages = options.excludePackage.toList()

        try {
            inet4Addresses.forEach { builder.addAddress(it.address(), it.prefix()) }
            inet6Addresses.forEach { builder.addAddress(it.address(), it.prefix()) }

            if (options.autoRoute) {
                options.dnsServerAddress.value
                    .takeIf { it.isNotBlank() }
                    ?.let(builder::addDnsServer)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (inet4RouteAddresses.isNotEmpty()) {
                        inet4RouteAddresses.forEach { builder.addRoute(it.toIpPrefix()) }
                    } else if (inet4Addresses.isNotEmpty()) {
                        builder.addRoute("0.0.0.0", 0)
                    }
                    if (inet6RouteAddresses.isNotEmpty()) {
                        inet6RouteAddresses.forEach { builder.addRoute(it.toIpPrefix()) }
                    } else if (inet6Addresses.isNotEmpty()) {
                        builder.addRoute("::", 0)
                    }
                    inet4RouteExcludes.forEach { builder.excludeRoute(it.toIpPrefix()) }
                    inet6RouteExcludes.forEach { builder.excludeRoute(it.toIpPrefix()) }
                } else {
                    if (inet4RouteExcludes.isNotEmpty() || inet6RouteExcludes.isNotEmpty()) {
                        return failure("Loopback-preserving excludeRoute support requires Android 13 or newer; refusing bootstrap on this device.")
                    }
                    inet4RouteRanges.forEach { builder.addRoute(it.address(), it.prefix()) }
                    inet6RouteRanges.forEach { builder.addRoute(it.address(), it.prefix()) }
                }

                includePackages.forEach { packageName ->
                    try {
                        builder.addAllowedApplication(packageName)
                    } catch (_: PackageManager.NameNotFoundException) {
                        error("Allowed application '$packageName' is not installed.")
                    }
                }
                excludePackages.forEach { packageName ->
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (_: PackageManager.NameNotFoundException) {
                        error("Disallowed application '$packageName' is not installed.")
                    }
                }
            }

            if (options.isHTTPProxyEnabled) {
                return failure("Embedded sing-box runtime attempted to enable HTTP proxy integration, which is disabled in safe mode.")
            }

            val descriptor = builder.establish()
                ?: return failure("VpnService.Builder.establish() returned null for $sessionLabel.")
            return TunOpenResult(
                result = TunnelBootstrapResult(
                    status = TunnelBootstrapStatus.ESTABLISHED,
                    summary = "Established $sessionLabel through embedded sing-box openTun() and retained the tunnel descriptor.",
                    bootstrappedAtEpochMs = clock(),
                ),
                descriptor = descriptor,
            )
        } catch (error: Exception) {
            return failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun failure(summary: String): TunOpenResult = TunOpenResult(
        result = TunnelBootstrapResult(
            status = TunnelBootstrapStatus.FAILED,
            summary = summary,
            bootstrappedAtEpochMs = clock(),
        ),
    )

    private fun detectType(interfaceName: String): Int = when {
        interfaceName.startsWith("wlan", ignoreCase = true) || interfaceName.startsWith("wifi", ignoreCase = true) ->
            Libbox.InterfaceTypeWIFI
        interfaceName.startsWith("rmnet", ignoreCase = true) || interfaceName.startsWith("ccmni", ignoreCase = true) ->
            Libbox.InterfaceTypeCellular
        interfaceName.startsWith("eth", ignoreCase = true) -> Libbox.InterfaceTypeEthernet
        else -> Libbox.InterfaceTypeOther
    }
}

private data class TunOpenResult(
    val result: TunnelBootstrapResult,
    val descriptor: ParcelFileDescriptor? = null,
)

private fun RoutePrefixIterator.toList(): List<RoutePrefix> {
    val values = mutableListOf<RoutePrefix>()
    while (hasNext()) {
        values += next()
    }
    return values
}

private fun StringIterator.toList(): List<String> {
    val values = mutableListOf<String>()
    while (hasNext()) {
        values += next()
    }
    return values
}

private fun RoutePrefix.toIpPrefix(): IpPrefix = IpPrefix(InetAddress.getByName(address()), prefix())

private class StringArray(
    private val iterator: Iterator<String>,
) : StringIterator {
    override fun hasNext(): Boolean = iterator.hasNext()

    override fun len(): Int = 0

    override fun next(): String = iterator.next()
}

private class NetworkInterfaceArray(
    private val iterator: Iterator<NetworkInterface>,
) : NetworkInterfaceIterator {
    override fun hasNext(): Boolean = iterator.hasNext()

    override fun next(): NetworkInterface = iterator.next()
}

private fun TunnelSessionPlan.toOverrideOptions(appPackageName: String): OverrideOptions = OverrideOptions().apply {
    when (splitTunnelMode) {
        is io.acionyx.tunguska.domain.SplitTunnelMode.Allowlist -> {
            includePackage = StringArray((allowedPackages + appPackageName).distinct().iterator())
        }

        is io.acionyx.tunguska.domain.SplitTunnelMode.Denylist -> {
            excludePackage = StringArray(disallowedPackages.filterNot { it == appPackageName }.iterator())
        }

        io.acionyx.tunguska.domain.SplitTunnelMode.FullTunnel -> Unit
    }
    autoRedirect = false
}

private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
    "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
} else {
    "${address.hostAddress}/$networkPrefixLength"
}

private fun JvmNetworkInterface.dumpFlags(): Int {
    var flags = 0
    if (isUp) {
        flags = flags or 0x1
    }
    if (isLoopback) {
        flags = flags or 0x8
    }
    if (isPointToPoint) {
        flags = flags or 0x10
    }
    if (supportsMulticast()) {
        flags = flags or 0x1000
    }
    return flags
}
