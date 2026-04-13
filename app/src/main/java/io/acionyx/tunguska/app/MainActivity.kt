package io.acionyx.tunguska.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.vpnservice.VpnRuntimePhase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private var notificationRoute by mutableStateOf<SubscriptionNotificationRoute?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationRoute = SubscriptionNotificationIntents.fromIntent(intent)
        enableEdgeToEdge()
        setContent {
            TunguskaApp(
                notificationRoute = notificationRoute,
                onNotificationRouteConsumed = { notificationRoute = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationRoute = SubscriptionNotificationIntents.fromIntent(intent)
    }
}

@Composable
private fun TunguskaApp(
    viewModel: MainViewModel = viewModel(),
    notificationRoute: SubscriptionNotificationRoute? = null,
    onNotificationRouteConsumed: () -> Unit = {},
) {
    val state = viewModel.uiState
    val context = LocalContext.current
    val subscriptionBringIntoViewRequester = remember { BringIntoViewRequester() }
    var showAdvancedDiagnostics by remember { mutableStateOf(false) }
    var showFrozenSecondarySurface by remember { mutableStateOf(false) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshSubscriptionNotificationStatus()
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.markVpnPermissionGranted()
            viewModel.connectRuntime()
        }
    }
    LaunchedEffect(notificationRoute) {
        notificationRoute?.let {
            viewModel.openSubscriptionNotificationRoute(it)
            onNotificationRouteConsumed()
        }
    }
    LaunchedEffect(state.subscriptionState.notificationAttentionToken) {
        if (state.subscriptionState.notificationAttentionToken != null) {
            showFrozenSecondarySurface = true
            subscriptionBringIntoViewRequester.bringIntoView()
        }
    }

    MaterialTheme(
        colorScheme = TunguskaChrome.colorScheme,
        typography = TunguskaChrome.typography,
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFF6F1E7),
                                Color(0xFFE4ECE7),
                            ),
                        ),
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .testTag(UiTags.MAIN_SCROLL_COLUMN)
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HeroCard(
                        title = "Tunguska",
                        subtitle = "${state.profile.name} - ${state.profile.outbound.address}:${state.profile.outbound.port}",
                        chips = listOf(
                            phaseChip(state.runtimeSnapshot.phase),
                            splitTunnelLabel(state.tunnelPlan.splitTunnelMode),
                            if (state.tunnelPlan.preserveLoopback) "Loopback local" else "Loopback tunneled",
                        ),
                    )

                    ConnectionOverviewCard(
                        state = state,
                        onConnect = {
                            val intent = VpnService.prepare(context)
                            if (intent == null) {
                                viewModel.markVpnPermissionGranted()
                                viewModel.connectRuntime()
                            } else {
                                requestPermission.launch(intent)
                            }
                        },
                        onStop = viewModel::stopRuntime,
                        onRefresh = viewModel::refreshRuntimeStatus,
                    )

                    ImportSection(
                        state = state,
                        onDraftChange = viewModel::updateImportDraft,
                        onValidateDraft = viewModel::stageImportDraft,
                        onConfirmImport = viewModel::confirmStagedImport,
                        onDiscardImport = viewModel::discardStagedImport,
                        onQrPayloadDetected = viewModel::stageQrImportPayload,
                        onImportError = viewModel::reportImportError,
                    )

                    DetailCard(
                        title = "Protection Summary",
                        body = listOf(
                            "Runtime lane: xray+tun2socks is the active MVP path; libbox stays available only for comparison.",
                            "Local bridge: loopback-only, authenticated, per-session random port and credentials, and no management API surface.",
                            "Split routing: ${splitTunnelDetail(state.tunnelPlan.splitTunnelMode)}.",
                            "Listener audit: ${state.runtimeSnapshot.auditStatus} ${state.runtimeSnapshot.lastAuditSummary ?: ""}".trim(),
                        ),
                        actions = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { showAdvancedDiagnostics = !showAdvancedDiagnostics }) {
                                    Text(if (showAdvancedDiagnostics) "Hide Advanced" else "Show Advanced")
                                }
                                Button(onClick = { showFrozenSecondarySurface = !showFrozenSecondarySurface }) {
                                    Text(if (showFrozenSecondarySurface) "Hide Secondary" else "Secondary Surface")
                                }
                            }
                        },
                    )

                    DetailCard(
                        title = "Redacted Diagnostics",
                        body = listOf(
                            "Status: ${state.exportState.status}",
                            "Last artifact: ${state.exportState.lastArtifactType ?: "none"}",
                            "Payload hash: ${state.exportState.lastArtifactHash ?: "n/a"}",
                            "Created at: ${state.exportState.lastCreatedAt ?: "n/a"}",
                        ),
                        actions = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.exportEncryptedBackup() }) {
                                    Text("Encrypted Backup")
                                }
                                Button(onClick = { viewModel.buildDiagnosticBundle() }) {
                                    Text("Redacted Audit")
                                }
                            }
                        },
                    )

                    DetailCard(
                        title = "Advanced Diagnostics",
                        body = buildList {
                            add("Hidden by default so the first screen stays on import, connect, status, and diagnostics.")
                            add("Current mode: ${if (showAdvancedDiagnostics) "expanded" else "collapsed"}")
                            add("Primary runtime: xray+tun2socks. Comparison runtime: libbox.")
                        },
                        actions = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { showAdvancedDiagnostics = !showAdvancedDiagnostics },
                                    modifier = Modifier.testTag(UiTags.SHOW_DIAGNOSTICS_BUTTON),
                                ) {
                                    Text(if (showAdvancedDiagnostics) "Hide Diagnostics" else "Show Diagnostics")
                                }
                                Button(onClick = { viewModel.refreshRuntimeStatus() }) {
                                    Text("Refresh Runtime")
                                }
                                Button(
                                    onClick = { viewModel.stageRuntime() },
                                    modifier = Modifier.testTag(UiTags.RESTAGE_RUNTIME_BUTTON),
                                ) {
                                    Text("Restage")
                                }
                            }
                        },
                    )

                    if (showAdvancedDiagnostics) {
                        PreviewCard(
                            state = state,
                            onPackageChange = { viewModel.updatePreview(packageName = it) },
                            onHostChange = { viewModel.updatePreview(destinationHost = it) },
                            onPortChange = { viewModel.updatePreview(destinationPort = it) },
                        )

                        DetailCard(
                            title = "Secure Profile Storage",
                            body = listOf(
                                "Backend: ${state.profileStorage.backend}",
                                "Key reference: ${state.profileStorage.keyReference}",
                                "File: ${state.profileStorage.storagePath}",
                                "Status: ${state.profileStorage.status}",
                                "Persisted hash: ${state.profileStorage.persistedProfileHash ?: "pending"}",
                                "Last sealed: ${state.profileStorage.lastPersistedAt ?: "n/a"}",
                                "Last storage error: ${state.profileStorage.error ?: "none"}",
                            ),
                            actions = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.reloadProfile() }) {
                                        Text("Reload")
                                    }
                                    Button(onClick = { viewModel.resealProfile() }) {
                                        Text("Reseal")
                                    }
                                }
                            },
                        )

                        DetailCard(
                            title = "Deterministic Build Surface",
                            body = listOf(
                                "Canonical profile hash: ${state.profile.canonicalHash()}",
                                "Compiled config hash: ${state.compiledConfig.configHash}",
                                "Compiled payload bytes: ${state.compiledConfig.payload.length}",
                                "Tun loopback preservation: ${state.compiledConfig.vpnDirectives.preserveLoopback}",
                                "Safe mode enforced: ${state.compiledConfig.vpnDirectives.safeMode}",
                            ),
                        )

                        DetailCard(
                            title = "Tunnel Plan",
                            body = listOf(
                                "Process: ${state.tunnelPlan.processNameSuffix}",
                                "Preserve loopback: ${state.tunnelPlan.preserveLoopback}",
                                "Split mode: ${state.tunnelPlan.splitTunnelMode::class.simpleName}",
                                "Allowed apps: ${state.tunnelPlan.allowedPackages.ifEmpty { listOf("all") }.joinToString()}",
                                "Excluded apps: ${state.tunnelPlan.disallowedPackages.ifEmpty { listOf("none") }.joinToString()}",
                                "Runtime policy: ${state.tunnelPlan.runtimeMode}",
                            ),
                        )

                        DetailCard(
                            title = "Runtime Internals",
                            body = listOf(
                                "Payload bytes: ${state.runtimeSnapshot.compiledPayloadBytes}",
                                "MTU: ${state.runtimeSnapshot.mtu ?: 0}",
                                "Routes: ${state.runtimeSnapshot.routeCount} / excluded ${state.runtimeSnapshot.excludedRouteCount}",
                                "Audit findings: ${state.runtimeSnapshot.auditFindingCount}",
                                "Last audit: ${formatTimestamp(state.runtimeSnapshot.lastAuditAtEpochMs)}",
                                "Audit summary: ${state.runtimeSnapshot.lastAuditSummary ?: "none"}",
                                "Last bootstrap: ${formatTimestamp(state.runtimeSnapshot.lastBootstrapAtEpochMs)}",
                                "Bootstrap summary: ${state.runtimeSnapshot.lastBootstrapSummary ?: "none"}",
                                "Last host prep: ${formatTimestamp(state.runtimeSnapshot.lastEngineHostAtEpochMs)}",
                                "Host summary: ${state.runtimeSnapshot.lastEngineHostSummary ?: "none"}",
                                "Last session event: ${formatTimestamp(state.runtimeSnapshot.lastEngineSessionAtEpochMs)}",
                                "Session summary: ${state.runtimeSnapshot.lastEngineSessionSummary ?: "none"}",
                                "Last health check: ${formatTimestamp(state.runtimeSnapshot.lastEngineHealthAtEpochMs)}",
                                "Health summary: ${state.runtimeSnapshot.lastEngineHealthSummary ?: "none"}",
                            ),
                        )
                    }

                    if (showFrozenSecondarySurface) {
                        DetailCard(
                            title = "Frozen Secondary Surface",
                            modifier = Modifier.bringIntoViewRequester(subscriptionBringIntoViewRequester),
                            body = buildList {
                                add("Subscription, inbox, scheduler, and notification controls remain available but are intentionally outside the MVP acceptance path.")
                                add("Status: ${state.subscriptionState.status}")
                                add("Stored source: ${state.subscriptionState.storedSourceHash ?: "not saved"}")
                                add("Pending update count: ${state.subscriptionState.pendingUpdateProfileCount}")
                                add("TLS trust status: ${state.subscriptionState.trustStatus}")
                                add("Publisher trust status: ${state.subscriptionState.publisherStatus}")
                                add("Latest inbox event: ${state.subscriptionState.latestEventSummary ?: "none"}")
                                add("Subscription error: ${state.subscriptionState.error ?: "none"}")
                            },
                            actions = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { showFrozenSecondarySurface = false }) {
                                        Text("Hide Surface")
                                    }
                                }
                                OutlinedTextField(
                                    value = state.subscriptionState.urlDraft,
                                    onValueChange = { viewModel.updateSubscriptionUrlDraft(it) },
                                    label = { Text("HTTPS subscription URL") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = state.subscriptionState.pinDraft,
                                    onValueChange = { viewModel.updateSubscriptionPinDraft(it) },
                                    label = { Text("Optional TLS SPKI pin (sha256/...)") },
                                    supportingText = {
                                        Text("Leave blank to keep the current pin set on the same URL, or save a new URL without pinning.")
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                )
                                OutlinedTextField(
                                    value = state.subscriptionState.publisherPinDraft,
                                    onValueChange = { viewModel.updateSubscriptionPublisherPinDraft(it) },
                                    label = { Text("Optional publisher pin (sha256/...)") },
                                    supportingText = {
                                        Text("Pins the signing public key for signed feed verification when present.")
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.saveSubscriptionSource() }) {
                                        Text("Save Source")
                                    }
                                    Button(onClick = { viewModel.refreshSubscriptionNow() }) {
                                        Text("Update Now")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.setSubscriptionScheduleMode(SubscriptionScheduleMode.MANUAL) }) {
                                        Text("Manual")
                                    }
                                    Button(onClick = { viewModel.setSubscriptionScheduleMode(SubscriptionScheduleMode.EVERY_6_HOURS) }) {
                                        Text("Every 6h")
                                    }
                                    Button(onClick = { viewModel.setSubscriptionScheduleMode(SubscriptionScheduleMode.EVERY_24_HOURS) }) {
                                        Text("Every 24h")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.setSubscriptionUpdateAlertsEnabled(true) }) {
                                        Text("Updates On")
                                    }
                                    Button(onClick = { viewModel.setSubscriptionUpdateAlertsEnabled(false) }) {
                                        Text("Updates Off")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.setSubscriptionFailureAlertThreshold(1) }) {
                                        Text("Fail x1")
                                    }
                                    Button(onClick = { viewModel.setSubscriptionFailureAlertThreshold(2) }) {
                                        Text("Fail x2")
                                    }
                                    Button(onClick = { viewModel.setSubscriptionFailureAlertThreshold(3) }) {
                                        Text("Fail x3")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            if (!state.subscriptionState.notificationsEnabled &&
                                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                            ) {
                                                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            } else {
                                                context.startActivity(
                                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                                    },
                                                )
                                            }
                                        },
                                    ) {
                                        Text(if (state.subscriptionState.notificationsEnabled) "Alert Settings" else "Enable Alerts")
                                    }
                                    Button(onClick = { viewModel.refreshSubscriptionNotificationStatus() }) {
                                        Text("Refresh Alerts")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.trustObservedSubscriptionIdentity() }) {
                                        Text("Trust Observed")
                                    }
                                    Button(onClick = { viewModel.clearPinnedSubscriptionIdentity() }) {
                                        Text("Clear Pin")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.trustObservedSubscriptionPublisherIdentity() }) {
                                        Text(if (state.subscriptionState.publisherRolloverReady) "Add Signer" else "Trust Signer")
                                    }
                                    Button(onClick = { viewModel.clearPinnedSubscriptionPublisherIdentity() }) {
                                        Text("Clear Signer")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { viewModel.selectPreviousSubscriptionCandidate() },
                                        enabled = state.subscriptionState.pendingUpdateProfileCount > 1,
                                    ) {
                                        Text("Prev Candidate")
                                    }
                                    Button(
                                        onClick = { viewModel.selectNextSubscriptionCandidate() },
                                        enabled = state.subscriptionState.pendingUpdateProfileCount > 1,
                                    ) {
                                        Text("Next Candidate")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.applyPendingSubscriptionUpdate() }) {
                                        Text("Apply Update")
                                    }
                                    Button(onClick = { viewModel.clearSubscriptionInbox() }) {
                                        Text("Clear Inbox")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.clearSubscriptionAlertLedger() }) {
                                        Text("Clear Alert Ledger")
                                    }
                                }
                                if (state.subscriptionState.notificationAttention != null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { viewModel.dismissSubscriptionNotificationAttention() }) {
                                            Text("Dismiss Alert")
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(epochMs: Long?): String {
    if (epochMs == null) return "n/a"
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()),
    )
}

@Composable
private fun ConnectionOverviewCard(
    state: MainUiState,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
) {
    val containerColor = when (state.runtimeSnapshot.phase) {
        VpnRuntimePhase.RUNNING -> Color(0xFFDDEBDD)
        VpnRuntimePhase.FAIL_CLOSED -> Color(0xFFF4D8D2)
        VpnRuntimePhase.START_REQUESTED -> Color(0xFFF6E5CD)
        else -> Color(0xFFF3E9D8)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = runtimeHeadline(state.runtimeSnapshot.phase),
                style = MaterialTheme.typography.displaySmall,
                color = Color(0xFF183233),
            )
            Text(
                text = runtimeSummary(state),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF22332C),
            )
            TwoColumnMetricRow(
                leftTitle = "Profile",
                leftValue = state.profile.name,
                rightTitle = "Routing",
                rightValue = splitTunnelLabel(state.tunnelPlan.splitTunnelMode),
            )
            DetailCard(
                title = "Runtime Snapshot",
                body = listOf(
                    "Phase: ${state.runtimeSnapshot.phase}",
                    "Permission: ${if (state.vpnPermissionGranted) "granted" else "required on connect"}",
                    "Listener audit: ${state.runtimeSnapshot.auditStatus}",
                    "Engine health: ${state.runtimeSnapshot.engineSessionHealthStatus}",
                    "Control channel: ${if (state.controlChannelConnected) "connected" else "disconnected"}",
                    "Last error: ${state.controlError ?: "none"}",
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.testTag(UiTags.CONNECT_BUTTON),
                ) {
                    Text("Connect")
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.testTag(UiTags.STOP_BUTTON),
                ) {
                    Text("Stop")
                }
                Button(
                    onClick = onRefresh,
                    modifier = Modifier.testTag(UiTags.REFRESH_RUNTIME_BUTTON),
                ) {
                    Text("Refresh")
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    title: String,
    subtitle: String,
    chips: List<String>,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF163233)),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                color = Color(0xFFF7EED6),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFDCE8E4),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chips.forEach { chip ->
                    Chip(text = chip)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color(0xFF28595B),
        contentColor = Color(0xFFF6F1E7),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun phaseChip(phase: VpnRuntimePhase): String = when (phase) {
    VpnRuntimePhase.IDLE -> "Ready"
    VpnRuntimePhase.STAGED -> "Profile staged"
    VpnRuntimePhase.START_REQUESTED -> "Connecting"
    VpnRuntimePhase.RUNNING -> "Connected"
    VpnRuntimePhase.FAIL_CLOSED -> "Fail closed"
}

private fun runtimeHeadline(phase: VpnRuntimePhase): String = when (phase) {
    VpnRuntimePhase.IDLE -> "Ready to connect"
    VpnRuntimePhase.STAGED -> "Profile staged"
    VpnRuntimePhase.START_REQUESTED -> "Connecting"
    VpnRuntimePhase.RUNNING -> "VPN active"
    VpnRuntimePhase.FAIL_CLOSED -> "Attention required"
}

private fun runtimeSummary(state: MainUiState): String = when (state.runtimeSnapshot.phase) {
    VpnRuntimePhase.RUNNING ->
        "Connected through ${state.profile.outbound.address}:${state.profile.outbound.port} with ${splitTunnelLabel(state.tunnelPlan.splitTunnelMode).lowercase()} and ${if (state.tunnelPlan.preserveLoopback) "local loopback preserved" else "loopback routed through the tunnel"}."

    VpnRuntimePhase.START_REQUESTED ->
        "VPN permission and runtime start were requested. The session will fail closed if bootstrap, audit, or engine health checks fail."

    VpnRuntimePhase.FAIL_CLOSED ->
        "The runtime stopped in fail-closed mode. Review the latest runtime error before reconnecting."

    VpnRuntimePhase.STAGED ->
        "The validated profile is staged. Connect to request VPN permission and start the runtime."

    VpnRuntimePhase.IDLE ->
        "Import or scan a share link, confirm the normalized profile, then connect."
}

private fun splitTunnelLabel(mode: SplitTunnelMode): String = when (mode) {
    SplitTunnelMode.FullTunnel -> "Full tunnel"
    is SplitTunnelMode.Allowlist -> "Allowlist"
    is SplitTunnelMode.Denylist -> "Denylist"
}

private fun splitTunnelDetail(mode: SplitTunnelMode): String = when (mode) {
    SplitTunnelMode.FullTunnel -> "all applications use the tunnel by default"
    is SplitTunnelMode.Allowlist -> "${mode.packageNames.size} allowed app(s) use the tunnel"
    is SplitTunnelMode.Denylist -> "${mode.packageNames.size} excluded app(s) bypass the tunnel"
}

@Composable
private fun TwoColumnMetricRow(
    leftTitle: String,
    leftValue: String,
    rightTitle: String,
    rightValue: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard(
            title = leftTitle,
            value = leftValue,
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            title = rightTitle,
            value = rightValue,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAF7F1)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Color(0xFF5D6C63))
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DetailCard(
    title: String,
    body: List<String>,
    modifier: Modifier = Modifier,
    actions: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFBF9F5)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            body.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF22332C))
            }
            actions?.invoke()
        }
    }
}

@Composable
private fun PreviewCard(
    state: MainUiState,
    onPackageChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7E7D5)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Routing Preview", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = state.routePreview.packageName,
                onValueChange = onPackageChange,
                label = { Text("Package name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.routePreview.destinationHost,
                onValueChange = onHostChange,
                label = { Text("Destination host") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.routePreview.destinationPort,
                onValueChange = onPortChange,
                label = { Text("Destination port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text(
                text = "Decision: ${state.previewOutcome.action} via ${state.previewOutcome.matchedRuleId ?: "default"}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = state.previewOutcome.reason,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private object TunguskaChrome {
    val colorScheme = androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF193B3C),
        onPrimary = Color(0xFFF7EED6),
        secondary = Color(0xFFB55A32),
        onSecondary = Color.White,
        surface = Color(0xFFFAF7F1),
        onSurface = Color(0xFF1E2B25),
        background = Color(0xFFF6F1E7),
        onBackground = Color(0xFF18221E),
    )

    val typography = androidx.compose.material3.Typography(
        displaySmall = TextStyle(fontFamily = FontFamily.Serif),
        titleLarge = TextStyle(fontFamily = FontFamily.Serif),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif),
        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif),
        labelLarge = TextStyle(fontFamily = FontFamily.Monospace),
        labelMedium = TextStyle(fontFamily = FontFamily.Monospace),
    )
}
