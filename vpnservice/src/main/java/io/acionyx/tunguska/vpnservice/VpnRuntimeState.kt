package io.acionyx.tunguska.vpnservice

enum class VpnRuntimePhase {
    IDLE,
    STAGED,
    START_REQUESTED,
    RUNNING,
    FAIL_CLOSED,
}

data class VpnRuntimeSnapshot(
    val phase: VpnRuntimePhase = VpnRuntimePhase.IDLE,
    val configHash: String? = null,
    val sessionLabel: String? = null,
    val engineId: String? = null,
    val engineFormat: String? = null,
    val compiledPayloadBytes: Int = 0,
    val allowCount: Int = 0,
    val denyCount: Int = 0,
    val routeCount: Int = 0,
    val excludedRouteCount: Int = 0,
    val mtu: Int? = null,
    val runtimeMode: TunnelSessionPlan.RuntimeMode = TunnelSessionPlan.RuntimeMode.FAIL_CLOSED_UNTIL_ENGINE_HOST,
    val auditStatus: RuntimeAuditStatus = RuntimeAuditStatus.NOT_RUN,
    val auditFindingCount: Int = 0,
    val lastAuditAtEpochMs: Long? = null,
    val lastAuditSummary: String? = null,
    val bootstrapStatus: TunnelBootstrapStatus = TunnelBootstrapStatus.NOT_ATTEMPTED,
    val lastBootstrapAtEpochMs: Long? = null,
    val lastBootstrapSummary: String? = null,
    val engineHostStatus: EmbeddedEngineHostStatus = EmbeddedEngineHostStatus.NOT_PREPARED,
    val lastEngineHostAtEpochMs: Long? = null,
    val lastEngineHostSummary: String? = null,
    val engineSessionStatus: EmbeddedEngineSessionStatus = EmbeddedEngineSessionStatus.NOT_STARTED,
    val lastEngineSessionAtEpochMs: Long? = null,
    val lastEngineSessionSummary: String? = null,
    val engineSessionHealthStatus: EmbeddedEngineSessionHealthStatus = EmbeddedEngineSessionHealthStatus.UNKNOWN,
    val lastEngineHealthAtEpochMs: Long? = null,
    val lastEngineHealthSummary: String? = null,
    val sessionWorkspacePath: String? = null,
    val lastError: String? = null,
)

object VpnRuntimeStore {
    private val lock = Any()

    private var stagedRequest: StagedRuntimeRequest? = null
    private var snapshot: VpnRuntimeSnapshot = VpnRuntimeSnapshot()

    fun snapshot(): VpnRuntimeSnapshot = synchronized(lock) { snapshot }

    fun stagedRequest(): StagedRuntimeRequest? = synchronized(lock) { stagedRequest }

    fun stage(request: StagedRuntimeRequest): VpnRuntimeSnapshot = synchronized(lock) {
        stagedRequest = request
        val plan = request.plan
        val spec = TunnelInterfacePlanner.plan(plan)
        snapshot = VpnRuntimeSnapshot(
            phase = VpnRuntimePhase.STAGED,
            configHash = plan.configHash,
            sessionLabel = spec.sessionLabel,
            engineId = request.compiledConfig.engineId,
            engineFormat = request.compiledConfig.format,
            compiledPayloadBytes = request.compiledConfig.payload.toByteArray(Charsets.UTF_8).size,
            allowCount = plan.allowedPackages.size,
            denyCount = plan.disallowedPackages.size,
            routeCount = spec.routes.size,
            excludedRouteCount = spec.excludedRoutes.size,
            mtu = spec.mtu,
            runtimeMode = plan.runtimeMode,
            lastError = null,
        )
        snapshot
    }

    fun markStartRequested(): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            phase = VpnRuntimePhase.START_REQUESTED,
            lastError = null,
        )
        snapshot
    }

    fun recordAudit(result: RuntimeListenerAuditResult): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            auditStatus = result.status,
            auditFindingCount = result.findings.size,
            lastAuditAtEpochMs = result.auditedAtEpochMs,
            lastAuditSummary = result.summary,
        )
        snapshot
    }

    fun recordBootstrap(result: TunnelBootstrapResult): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            bootstrapStatus = result.status,
            lastBootstrapAtEpochMs = result.bootstrappedAtEpochMs,
            lastBootstrapSummary = result.summary,
        )
        snapshot
    }

    fun recordEngineHost(result: EmbeddedEngineHostResult): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            engineHostStatus = result.status,
            lastEngineHostAtEpochMs = result.preparedAtEpochMs,
            lastEngineHostSummary = result.summary,
            sessionWorkspacePath = result.workspacePath,
        )
        snapshot
    }

    fun recordEngineSession(result: EmbeddedEngineSessionResult): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            phase = if (result.status == EmbeddedEngineSessionStatus.STARTED) {
                VpnRuntimePhase.RUNNING
            } else {
                snapshot.phase
            },
            engineSessionStatus = result.status,
            lastEngineSessionAtEpochMs = result.observedAtEpochMs,
            lastEngineSessionSummary = result.summary,
            lastError = if (result.status == EmbeddedEngineSessionStatus.FAILED) {
                result.summary
            } else {
                snapshot.lastError
            },
        )
        snapshot
    }

    fun recordEngineHealth(result: EmbeddedEngineSessionHealthResult): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            engineSessionHealthStatus = result.status,
            lastEngineHealthAtEpochMs = result.observedAtEpochMs,
            lastEngineHealthSummary = result.summary,
            lastError = if (result.status == EmbeddedEngineSessionHealthStatus.FAILED) {
                result.summary
            } else {
                snapshot.lastError
            },
        )
        snapshot
    }

    fun markFailClosed(reason: String): VpnRuntimeSnapshot = synchronized(lock) {
        snapshot = snapshot.copy(
            phase = VpnRuntimePhase.FAIL_CLOSED,
            lastError = reason,
        )
        snapshot
    }

    fun stop(): VpnRuntimeSnapshot = synchronized(lock) {
        RuntimeSessionWorkspaceCleaner.delete(snapshot.sessionWorkspacePath)
        stagedRequest = null
        snapshot = VpnRuntimeSnapshot()
        snapshot
    }
}
