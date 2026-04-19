package io.acionyx.tunguska.app

import android.content.Context
import io.acionyx.tunguska.domain.CanonicalJson
import io.acionyx.tunguska.vpnservice.VpnRuntimePhase
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

class AutomationRelayStatusStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val file: File = resolveStatusFile(context)
    private val lock = Any()

    val shellPath: String
        get() = file.absolutePath

    fun markAccepted(
        action: String,
        callerHint: String?,
    ): AutomationRelayStatusRecord {
        return write(
            AutomationRelayStatusRecord(
                requestId = UUID.randomUUID().toString(),
                action = action,
                status = AutomationRelayProgressStatus.REQUEST_ACCEPTED,
                callerHint = callerHint?.trim()?.takeIf { it.isNotEmpty() }?.take(64),
                updatedAtEpochMs = clock(),
            ),
        )
    }

    fun markCompleted(
        accepted: AutomationRelayStatusRecord,
        result: RuntimeAutomationResult,
    ): AutomationRelayStatusRecord {
        return markCompleted(
            requestId = accepted.requestId,
            action = accepted.action,
            callerHint = accepted.callerHint,
            result = result,
        )
    }

    fun markProgress(
        requestId: String,
        action: String,
        callerHint: String?,
        summary: String,
    ): AutomationRelayStatusRecord {
        return write(
            AutomationRelayStatusRecord(
                requestId = requestId,
                action = action,
                status = AutomationRelayProgressStatus.REQUEST_ACCEPTED,
                summary = summary.take(240),
                callerHint = callerHint?.trim()?.takeIf { it.isNotEmpty() }?.take(64),
                updatedAtEpochMs = clock(),
            ),
        )
    }

    fun markCompleted(
        requestId: String,
        action: String,
        callerHint: String?,
        result: RuntimeAutomationResult,
    ): AutomationRelayStatusRecord {
        return write(
            AutomationRelayStatusRecord(
                requestId = requestId,
                action = action,
                status = result.status.name,
                runtimePhase = result.snapshot?.phase?.name,
                error = result.error,
                summary = result.summary.take(240),
                callerHint = callerHint?.trim()?.takeIf { it.isNotEmpty() }?.take(64),
                updatedAtEpochMs = clock(),
            ),
        )
    }

    fun markRejected(
        action: String,
        callerHint: String?,
        result: RuntimeAutomationResult,
    ): AutomationRelayStatusRecord {
        return write(
            AutomationRelayStatusRecord(
                requestId = UUID.randomUUID().toString(),
                action = action.ifBlank { AutomationRelayProgressStatus.UNSPECIFIED_ACTION },
                status = result.status.name,
                runtimePhase = result.snapshot?.phase?.name,
                error = result.error,
                summary = result.summary.take(240),
                callerHint = callerHint?.trim()?.takeIf { it.isNotEmpty() }?.take(64),
                updatedAtEpochMs = clock(),
            ),
        )
    }

    private fun write(record: AutomationRelayStatusRecord): AutomationRelayStatusRecord = synchronized(lock) {
        file.parentFile?.mkdirs()
        file.writeText(CanonicalJson.instance.encodeToString(record), Charsets.UTF_8)
        record
    }

    private fun resolveStatusFile(context: Context): File {
        val mediaRoot = context.externalMediaDirs
            .firstOrNull { it != null }
            ?.resolve("automation")
        val fallback = context.getExternalFilesDir("automation")
        val root = mediaRoot ?: fallback ?: context.filesDir.resolve("automation-status-fallback")
        return root.resolve("relay-status.json")
    }
}

private object AutomationRelayProgressStatus {
    const val REQUEST_ACCEPTED: String = "REQUEST_ACCEPTED"
    const val UNSPECIFIED_ACTION: String = "UNSPECIFIED_ACTION"
}

@Serializable
data class AutomationRelayStatusRecord(
    val requestId: String,
    val action: String,
    val status: String,
    val runtimePhase: String? = null,
    val error: String? = null,
    val summary: String? = null,
    val callerHint: String? = null,
    val updatedAtEpochMs: Long,
)
