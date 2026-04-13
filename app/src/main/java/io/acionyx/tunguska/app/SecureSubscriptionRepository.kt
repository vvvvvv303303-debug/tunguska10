package io.acionyx.tunguska.app

import io.acionyx.tunguska.crypto.CipherBox
import io.acionyx.tunguska.domain.CanonicalJson
import io.acionyx.tunguska.domain.ProfileImportParser
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.storage.EncryptedArtifactStore
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val SUBSCRIPTION_MASTER_KEY_ALIAS: String = "io.acionyx.tunguska.subscription.master"
private const val SUBSCRIPTION_STORE_RELATIVE_PATH: String = "subscriptions/default-source.json.enc"
private const val SUBSCRIPTION_PENDING_UPDATE_RELATIVE_PATH: String = "subscriptions/pending-update.json.enc"
private const val SUBSCRIPTION_EVENT_LOG_RELATIVE_PATH: String = "subscriptions/event-log.json.enc"
private const val SUBSCRIPTION_NOTIFICATION_LEDGER_RELATIVE_PATH: String = "subscriptions/notification-ledger.json.enc"
private const val SUBSCRIPTION_ARTIFACT_TYPE: String = "subscription_source"
private const val SUBSCRIPTION_PENDING_UPDATE_ARTIFACT_TYPE: String = "subscription_pending_update"
private const val SUBSCRIPTION_EVENT_LOG_ARTIFACT_TYPE: String = "subscription_event_log"
private const val SUBSCRIPTION_NOTIFICATION_LEDGER_ARTIFACT_TYPE: String = "subscription_notification_ledger"
private const val MAX_SUBSCRIPTION_PAYLOAD_BYTES: Int = 262_144
private const val CONNECT_TIMEOUT_MS: Int = 8_000
private const val READ_TIMEOUT_MS: Int = 8_000
private const val DEFAULT_BUFFER_BYTES: Int = 8_192
private const val SIGNED_SUBSCRIPTION_ALGORITHM: String = "SHA256withECDSA"
private const val MAX_SUBSCRIPTION_EVENT_LOG_ENTRIES: Int = 12
private const val MIN_SCHEDULED_FAILURE_ALERT_THRESHOLD: Int = 1
private const val MAX_SCHEDULED_FAILURE_ALERT_THRESHOLD: Int = 3

class SecureSubscriptionRepository(
    private val storePath: Path,
    private val pendingUpdatePath: Path,
    private val eventLogPath: Path,
    private val notificationLedgerPath: Path,
    private val cipherBox: CipherBox,
    private val transport: SubscriptionTransport = HttpsSubscriptionTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(
        context: android.content.Context,
        cipherBox: CipherBox = AndroidKeyStoreCipherBox(SUBSCRIPTION_MASTER_KEY_ALIAS),
        transport: SubscriptionTransport = HttpsSubscriptionTransport(),
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        storePath = context.filesDir.toPath().resolve(SUBSCRIPTION_STORE_RELATIVE_PATH),
        pendingUpdatePath = context.filesDir.toPath().resolve(SUBSCRIPTION_PENDING_UPDATE_RELATIVE_PATH),
        eventLogPath = context.filesDir.toPath().resolve(SUBSCRIPTION_EVENT_LOG_RELATIVE_PATH),
        notificationLedgerPath = context.filesDir.toPath().resolve(SUBSCRIPTION_NOTIFICATION_LEDGER_RELATIVE_PATH),
        cipherBox = cipherBox,
        transport = transport,
        clock = clock,
    )

    private val store = EncryptedArtifactStore(
        path = storePath,
        cipherBox = cipherBox,
        clock = clock,
    )
    private val pendingStore = EncryptedArtifactStore(
        path = pendingUpdatePath,
        cipherBox = cipherBox,
        clock = clock,
    )
    private val eventLogStore = EncryptedArtifactStore(
        path = eventLogPath,
        cipherBox = cipherBox,
        clock = clock,
    )
    private val notificationLedgerStore = EncryptedArtifactStore(
        path = notificationLedgerPath,
        cipherBox = cipherBox,
        clock = clock,
    )

    val storagePath: String = store.path.toString()
    val pendingUpdateStoragePath: String = pendingStore.path.toString()
    val eventLogStoragePath: String = eventLogStore.path.toString()
    val notificationLedgerStoragePath: String = notificationLedgerStore.path.toString()
    val keyReference: String = "android-keystore:$SUBSCRIPTION_MASTER_KEY_ALIAS"

    fun loadConfig(): SubscriptionConfig? {
        val loaded = store.load() ?: return null
        require(loaded.artifactType == SUBSCRIPTION_ARTIFACT_TYPE) {
            "Unexpected subscription artifact type '${loaded.artifactType}'."
        }
        val config = CanonicalJson.instance.decodeFromString<SubscriptionConfig>(loaded.payloadJson)
        SubscriptionUrlPolicy.validate(config.sourceUrl)
        return config.copy(
            expectedSpkiPins = SubscriptionPinPolicy.normalizePins(config.expectedSpkiPins),
            lastObservedSpkiPins = SubscriptionPinPolicy.normalizePins(config.lastObservedSpkiPins),
            expectedPublisherPins = SubscriptionPinPolicy.normalizePins(config.expectedPublisherPins),
            lastObservedPublisherPin = SubscriptionPinPolicy.normalizeSinglePinOrNull(config.lastObservedPublisherPin),
        )
    }

    fun loadPendingUpdate(): SubscriptionPendingUpdate? {
        val loaded = pendingStore.load() ?: return null
        require(loaded.artifactType == SUBSCRIPTION_PENDING_UPDATE_ARTIFACT_TYPE) {
            "Unexpected pending subscription artifact type '${loaded.artifactType}'."
        }
        return CanonicalJson.instance.decodeFromString<SubscriptionPendingUpdate>(loaded.payloadJson)
    }

    fun clearPendingUpdate(): Boolean = pendingStore.delete()

    fun loadEventLog(): SubscriptionEventLog {
        val loaded = eventLogStore.load() ?: return SubscriptionEventLog()
        require(loaded.artifactType == SUBSCRIPTION_EVENT_LOG_ARTIFACT_TYPE) {
            "Unexpected subscription event artifact type '${loaded.artifactType}'."
        }
        return CanonicalJson.instance.decodeFromString<SubscriptionEventLog>(loaded.payloadJson)
    }

    fun clearEventLog(): Boolean = eventLogStore.delete()

    fun loadNotificationLedger(): SubscriptionNotificationLedger {
        val loaded = notificationLedgerStore.load() ?: return SubscriptionNotificationLedger()
        require(loaded.artifactType == SUBSCRIPTION_NOTIFICATION_LEDGER_ARTIFACT_TYPE) {
            "Unexpected subscription notification artifact type '${loaded.artifactType}'."
        }
        return CanonicalJson.instance.decodeFromString<SubscriptionNotificationLedger>(loaded.payloadJson)
    }

    fun clearNotificationLedger(): Boolean = notificationLedgerStore.delete()

    fun markNotificationDelivered(route: SubscriptionNotificationRoute) {
        val sanitizedSummary = sanitizeNotificationSummary(route.summary)
        val now = clock()
        persistNotificationLedger(
            loadNotificationLedger().copy(
                lastEvaluationStatus = SubscriptionNotificationDeliveryStatus.DELIVERED,
                lastEvaluationSummary = "Delivered a scheduled subscription alert.",
                lastEvaluatedAtEpochMs = now,
                lastEvaluatedEventAtEpochMs = route.occurredAtEpochMs,
                lastEvaluatedDestination = route.destination,
                lastEvaluatedNotificationSummary = sanitizedSummary,
                lastDeliveredAtEpochMs = now,
                lastDeliveredEventAtEpochMs = route.occurredAtEpochMs,
                lastDeliveredDestination = route.destination,
                lastDeliveredSummary = sanitizedSummary,
            ),
        )
    }

    fun markNotificationSuppressed(
        route: SubscriptionNotificationRoute,
        reason: String,
        status: SubscriptionNotificationDeliveryStatus = SubscriptionNotificationDeliveryStatus.SUPPRESSED_DISABLED,
    ) {
        val sanitizedSummary = sanitizeNotificationSummary(route.summary)
        persistNotificationLedger(
            loadNotificationLedger().copy(
                lastEvaluationStatus = status,
                lastEvaluationSummary = sanitizeNotificationSummary(reason),
                lastEvaluatedAtEpochMs = clock(),
                lastEvaluatedEventAtEpochMs = route.occurredAtEpochMs,
                lastEvaluatedDestination = route.destination,
                lastEvaluatedNotificationSummary = sanitizedSummary,
            ),
        )
    }

    fun markNotificationSkippedDuplicate(route: SubscriptionNotificationRoute) {
        val sanitizedSummary = sanitizeNotificationSummary(route.summary)
        persistNotificationLedger(
            loadNotificationLedger().copy(
                lastEvaluationStatus = SubscriptionNotificationDeliveryStatus.SKIPPED_DUPLICATE,
                lastEvaluationSummary = "Skipped a duplicate scheduled subscription alert that was already delivered.",
                lastEvaluatedAtEpochMs = clock(),
                lastEvaluatedEventAtEpochMs = route.occurredAtEpochMs,
                lastEvaluatedDestination = route.destination,
                lastEvaluatedNotificationSummary = sanitizedSummary,
            ),
        )
    }

    fun markNotificationOpened(route: SubscriptionNotificationRoute) {
        val sanitizedSummary = sanitizeNotificationSummary(route.summary)
        persistNotificationLedger(
            loadNotificationLedger().copy(
                lastOpenedAtEpochMs = clock(),
                lastOpenedEventAtEpochMs = route.occurredAtEpochMs,
                lastOpenedDestination = route.destination,
                lastOpenedSummary = sanitizedSummary,
            ),
        )
    }

    fun recordEvent(
        origin: SubscriptionEventOrigin,
        severity: SubscriptionEventSeverity,
        summary: String,
    ) {
        appendEvent(
            SubscriptionEvent(
                occurredAtEpochMs = clock(),
                origin = origin,
                severity = severity,
                summary = sanitizeEventSummary(summary),
            ),
        )
    }

    fun saveSourceUrl(
        sourceUrl: String,
        expectedSpkiPins: List<String>? = null,
        expectedPublisherPins: List<String>? = null,
    ): SubscriptionConfig {
        val normalizedSourceUrl = SubscriptionUrlPolicy.validate(sourceUrl).toString()
        val normalizedPins = expectedSpkiPins?.let(SubscriptionPinPolicy::normalizePins)
        val normalizedPublisherPins = expectedPublisherPins?.let(SubscriptionPinPolicy::normalizePins)
        val current = loadConfig()
        val now = clock()
        val resetState = current == null || current.sourceUrl != normalizedSourceUrl
        val preservedScheduleMode = current?.scheduleMode ?: SubscriptionScheduleMode.MANUAL
        val preservedUpdateAlertsEnabled = current?.scheduledUpdateAlertsEnabled ?: true
        val preservedFailureAlertThreshold = current?.scheduledFailureAlertThreshold
            ?: MIN_SCHEDULED_FAILURE_ALERT_THRESHOLD
        if (resetState) {
            clearPendingUpdate()
            clearEventLog()
            clearNotificationLedger()
        }
        val config = if (resetState) {
            SubscriptionConfig(
                sourceUrl = normalizedSourceUrl,
                sourceUrlHash = normalizedSourceUrl.redactedDigest(),
                savedAtEpochMs = now,
                expectedSpkiPins = normalizedPins.orEmpty(),
                expectedPublisherPins = normalizedPublisherPins.orEmpty(),
                scheduleMode = preservedScheduleMode,
                lastScheduledStatus = if (preservedScheduleMode == SubscriptionScheduleMode.MANUAL) {
                    SubscriptionScheduledStatus.NONE
                } else {
                    SubscriptionScheduledStatus.SCHEDULED
                },
                lastScheduledSummary = preservedScheduleMode.schedulingSummary(),
                scheduledUpdateAlertsEnabled = preservedUpdateAlertsEnabled,
                scheduledFailureAlertThreshold = preservedFailureAlertThreshold,
            )
        } else {
            current.copy(
                sourceUrl = normalizedSourceUrl,
                sourceUrlHash = normalizedSourceUrl.redactedDigest(),
                savedAtEpochMs = now,
                expectedSpkiPins = normalizedPins ?: current.expectedSpkiPins,
                expectedPublisherPins = normalizedPublisherPins ?: current.expectedPublisherPins,
            )
        }.reconcileStoredTrust()
        persist(config)
        return config
    }

    fun fetchUpdate(
        config: SubscriptionConfig,
        activeProfile: ProfileIr,
        trigger: SubscriptionUpdateTrigger = SubscriptionUpdateTrigger.USER_INITIATED,
    ): SubscriptionUpdateResult {
        val response = transport.fetch(
            SubscriptionFetchRequest(
                sourceUrl = config.sourceUrl,
                etag = config.lastEtag,
                lastModified = config.lastModifiedHeader,
            ),
        )
        val resolvedUrl = SubscriptionUrlPolicy.validate(response.resolvedUrl).toString()
        val checkedAtEpochMs = clock()
        val observedPins = SubscriptionPinPolicy.normalizePins(response.observedSpkiPins)
        val trustDecision = runCatching {
            SubscriptionTrustPolicy.evaluate(
                expectedPins = config.expectedSpkiPins,
                observedPins = observedPins,
            )
        }.getOrElse { error ->
            if (error is SubscriptionTrustException) {
                val eventSummary = buildFailureEventSummary(
                    trigger = trigger,
                    summary = "TLS identity validation failed.",
                )
                val failedConfig = config.copy(
                    lastCheckedAtEpochMs = checkedAtEpochMs,
                    lastResolvedUrlHash = resolvedUrl.redactedDigest(),
                    lastObservedSpkiPins = observedPins,
                    lastTrustStatus = SubscriptionTrustStatus.PIN_MISMATCH,
                    lastTrustSummary = error.message,
                ).withScheduledOutcome(
                    trigger = trigger,
                    checkedAtEpochMs = checkedAtEpochMs,
                    status = SubscriptionScheduledStatus.FAILED,
                    summary = eventSummary,
                )
                persist(failedConfig)
                appendEvent(
                    SubscriptionEvent(
                        occurredAtEpochMs = checkedAtEpochMs,
                        origin = trigger.toEventOrigin(),
                        severity = SubscriptionEventSeverity.ERROR,
                        summary = eventSummary,
                    ),
                )
            }
            throw error
        }
        val trustedConfig = config.copy(
            lastCheckedAtEpochMs = checkedAtEpochMs,
            lastResolvedUrlHash = resolvedUrl.redactedDigest(),
            lastObservedSpkiPins = observedPins,
            lastTrustStatus = trustDecision.status,
            lastTrustSummary = trustDecision.summary,
        )
        if (response.statusCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            val eventSummary = when (trigger) {
                SubscriptionUpdateTrigger.USER_INITIATED -> "Manual subscription check found no feed changes."
                SubscriptionUpdateTrigger.SCHEDULED -> "Scheduled subscription check found no feed changes."
            }
            val updatedConfig = trustedConfig.withScheduledOutcome(
                trigger = trigger,
                checkedAtEpochMs = checkedAtEpochMs,
                status = SubscriptionScheduledStatus.NOT_MODIFIED,
                summary = eventSummary,
            )
            persist(updatedConfig)
            appendEvent(
                SubscriptionEvent(
                    occurredAtEpochMs = checkedAtEpochMs,
                    origin = trigger.toEventOrigin(),
                    severity = SubscriptionEventSeverity.INFO,
                    summary = eventSummary,
                ),
            )
            return SubscriptionUpdateResult(
                status = SubscriptionUpdateStatus.NOT_MODIFIED,
                updatedConfig = updatedConfig,
                checkedAtEpochMs = checkedAtEpochMs,
                trustStatus = trustDecision.status,
                trustSummary = trustDecision.summary,
                observedSpkiPins = observedPins,
                publisherStatus = updatedConfig.lastPublisherStatus,
                publisherSummary = updatedConfig.lastPublisherSummary,
                observedPublisherPin = updatedConfig.lastObservedPublisherPin,
            )
        }

        require(response.statusCode == HttpURLConnection.HTTP_OK) {
            "Subscription fetch returned HTTP ${response.statusCode}."
        }
        val parsed = runCatching {
            SubscriptionPayloadParser.parse(requireNotNull(response.body))
        }.getOrElse { error ->
            if (error is SubscriptionPublisherException) {
                val eventSummary = buildFailureEventSummary(
                    trigger = trigger,
                    summary = "Signed feed validation failed.",
                )
                val failedConfig = trustedConfig.copy(
                    lastObservedPublisherPin = error.observedPublisherPin,
                    lastPublisherStatus = error.status,
                    lastPublisherSummary = error.message,
                ).withScheduledOutcome(
                    trigger = trigger,
                    checkedAtEpochMs = checkedAtEpochMs,
                    status = SubscriptionScheduledStatus.FAILED,
                    summary = eventSummary,
                )
                persist(failedConfig)
                appendEvent(
                    SubscriptionEvent(
                        occurredAtEpochMs = checkedAtEpochMs,
                        origin = trigger.toEventOrigin(),
                        severity = SubscriptionEventSeverity.ERROR,
                        summary = eventSummary,
                    ),
                )
            }
            throw error
        }
        val publisherDecision = runCatching {
            SubscriptionPublisherTrustPolicy.evaluate(
                expectedPins = config.expectedPublisherPins,
                publisherSignature = parsed.publisherSignature,
            )
        }.getOrElse { error ->
            if (error is SubscriptionPublisherException) {
                val eventSummary = buildFailureEventSummary(
                    trigger = trigger,
                    summary = "Publisher trust validation failed.",
                )
                val failedConfig = trustedConfig.copy(
                    lastObservedPublisherPin = error.observedPublisherPin ?: parsed.publisherSignature?.signerPin,
                    lastPublisherStatus = error.status,
                    lastPublisherSummary = error.message,
                ).withScheduledOutcome(
                    trigger = trigger,
                    checkedAtEpochMs = checkedAtEpochMs,
                    status = SubscriptionScheduledStatus.FAILED,
                    summary = eventSummary,
                )
                persist(failedConfig)
                appendEvent(
                    SubscriptionEvent(
                        occurredAtEpochMs = checkedAtEpochMs,
                        origin = trigger.toEventOrigin(),
                        severity = SubscriptionEventSeverity.ERROR,
                        summary = eventSummary,
                    ),
                )
            }
            throw error
        }
        val primaryProfile = parsed.profiles.first()
        val selection = SubscriptionFeedSelectionPolicy.select(
            profiles = parsed.profiles,
            lastAppliedProfileHash = config.lastAppliedProfileHash,
            lastAppliedProfileName = config.lastAppliedProfileName,
        )
        val selectedProfile = parsed.profiles[selection.selectedIndex]
        val diff = SubscriptionDiff.between(activeProfile, selectedProfile, parsed.profiles.size)
        val eventSummary = when {
            parsed.profiles.size == 1 && trigger == SubscriptionUpdateTrigger.USER_INITIATED ->
                "Manual subscription check staged a validated profile update."
            parsed.profiles.size == 1 ->
                "Scheduled subscription check staged a validated profile update."
            trigger == SubscriptionUpdateTrigger.USER_INITIATED ->
                "Manual subscription check staged ${parsed.profiles.size} validated profiles and selected candidate ${selection.selectedIndex + 1}/${parsed.profiles.size}."
            else ->
                "Scheduled subscription check staged ${parsed.profiles.size} validated profiles and selected candidate ${selection.selectedIndex + 1}/${parsed.profiles.size}."
        }
        val updatedConfig = trustedConfig.copy(
            lastFetchedProfileHash = primaryProfile.canonicalHash(),
            lastFetchedProfileCount = parsed.profiles.size,
            lastEtag = response.etag,
            lastModifiedHeader = response.lastModified,
            lastObservedPublisherPin = parsed.publisherSignature?.signerPin,
            lastPublisherStatus = publisherDecision.status,
            lastPublisherSummary = publisherDecision.summary,
        ).withScheduledOutcome(
            trigger = trigger,
            checkedAtEpochMs = checkedAtEpochMs,
            status = SubscriptionScheduledStatus.UPDATED,
            summary = eventSummary,
        )
        persist(updatedConfig)
        persistPendingUpdate(
            SubscriptionPendingUpdate(
                sourceUrlHash = updatedConfig.sourceUrlHash,
                title = parsed.title,
                checkedAtEpochMs = checkedAtEpochMs,
                fetchedProfileCount = parsed.profiles.size,
                selectedProfileIndex = selection.selectedIndex,
                selectionSummary = selection.summary,
                trustStatus = trustDecision.status,
                publisherStatus = publisherDecision.status,
                observedPublisherPin = parsed.publisherSignature?.signerPin,
                profiles = parsed.profiles,
            ),
        )
        appendEvent(
            SubscriptionEvent(
                occurredAtEpochMs = checkedAtEpochMs,
                origin = trigger.toEventOrigin(),
                severity = SubscriptionEventSeverity.INFO,
                summary = eventSummary,
            ),
        )
        return SubscriptionUpdateResult(
            status = SubscriptionUpdateStatus.UPDATED,
            updatedConfig = updatedConfig,
            checkedAtEpochMs = checkedAtEpochMs,
            title = parsed.title,
            fetchedProfileCount = parsed.profiles.size,
            fetchedProfiles = parsed.profiles,
            primaryProfile = primaryProfile,
            selectedProfileIndex = selection.selectedIndex,
            selectionSummary = selection.summary,
            diff = diff,
            trustStatus = trustDecision.status,
            trustSummary = trustDecision.summary,
            observedSpkiPins = observedPins,
            publisherStatus = publisherDecision.status,
            publisherSummary = publisherDecision.summary,
            observedPublisherPin = parsed.publisherSignature?.signerPin,
        )
    }

    fun markApplied(
        config: SubscriptionConfig,
        appliedProfile: ProfileIr,
    ): SubscriptionConfig {
        val updatedConfig = config.copy(
            lastAppliedAtEpochMs = clock(),
            lastAppliedProfileHash = appliedProfile.canonicalHash(),
            lastAppliedProfileName = appliedProfile.name,
        )
        persist(updatedConfig)
        clearPendingUpdate()
        return updatedConfig
    }

    fun updateSchedule(
        config: SubscriptionConfig,
        scheduleMode: SubscriptionScheduleMode,
    ): SubscriptionConfig {
        val summary = scheduleMode.schedulingSummary()
        val updatedConfig = config.copy(
            scheduleMode = scheduleMode,
            lastScheduledStatus = if (scheduleMode == SubscriptionScheduleMode.MANUAL) {
                SubscriptionScheduledStatus.DISABLED
            } else {
                SubscriptionScheduledStatus.SCHEDULED
            },
            lastScheduledSummary = summary,
        )
        persist(updatedConfig)
        appendEvent(
            SubscriptionEvent(
                occurredAtEpochMs = clock(),
                origin = SubscriptionEventOrigin.SYSTEM,
                severity = SubscriptionEventSeverity.INFO,
                summary = summary,
            ),
        )
        return updatedConfig
    }

    fun updateNotificationPolicy(
        config: SubscriptionConfig,
        scheduledUpdateAlertsEnabled: Boolean,
        scheduledFailureAlertThreshold: Int,
    ): SubscriptionConfig {
        require(scheduledFailureAlertThreshold in MIN_SCHEDULED_FAILURE_ALERT_THRESHOLD..MAX_SCHEDULED_FAILURE_ALERT_THRESHOLD) {
            "Scheduled failure alert threshold must be between $MIN_SCHEDULED_FAILURE_ALERT_THRESHOLD and $MAX_SCHEDULED_FAILURE_ALERT_THRESHOLD."
        }
        val updatedConfig = config.copy(
            scheduledUpdateAlertsEnabled = scheduledUpdateAlertsEnabled,
            scheduledFailureAlertThreshold = scheduledFailureAlertThreshold,
        )
        persist(updatedConfig)
        appendEvent(
            SubscriptionEvent(
                occurredAtEpochMs = clock(),
                origin = SubscriptionEventOrigin.SYSTEM,
                severity = SubscriptionEventSeverity.INFO,
                summary = "Updated subscription alert policy: updates ${if (scheduledUpdateAlertsEnabled) "enabled" else "disabled"}; failure alerts after $scheduledFailureAlertThreshold consecutive scheduled failure(s).",
            ),
        )
        return updatedConfig
    }

    fun markScheduledRunStarted(config: SubscriptionConfig): SubscriptionConfig {
        val updatedConfig = config.copy(
            lastScheduledRunAtEpochMs = clock(),
            lastScheduledStatus = SubscriptionScheduledStatus.CHECKING,
            lastScheduledSummary = "Scheduled subscription validation is running.",
        )
        persist(updatedConfig)
        return updatedConfig
    }

    fun markScheduledRunFailure(
        config: SubscriptionConfig,
        summary: String,
    ): SubscriptionConfig {
        val sanitizedSummary = buildFailureEventSummary(
            trigger = SubscriptionUpdateTrigger.SCHEDULED,
            summary = summary,
        )
        val updatedConfig = config.withScheduledOutcome(
            trigger = SubscriptionUpdateTrigger.SCHEDULED,
            checkedAtEpochMs = clock(),
            status = SubscriptionScheduledStatus.FAILED,
            summary = sanitizedSummary,
        )
        persist(updatedConfig)
        appendEvent(
            SubscriptionEvent(
                occurredAtEpochMs = updatedConfig.lastScheduledRunAtEpochMs ?: clock(),
                origin = SubscriptionEventOrigin.SCHEDULED,
                severity = SubscriptionEventSeverity.ERROR,
                summary = sanitizedSummary,
            ),
        )
        return updatedConfig
    }

    fun trustObservedIdentity(config: SubscriptionConfig): SubscriptionConfig {
        require(config.lastObservedSpkiPins.isNotEmpty()) {
            "Fetch the subscription at least once before pinning its observed TLS identity."
        }
        val updatedConfig = config.copy(
            expectedSpkiPins = SubscriptionPinPolicy.normalizePins(config.lastObservedSpkiPins),
        ).reconcileStoredTrust().copy(
            lastTrustSummary = "Pinned the last observed TLS identity for future subscription updates.",
        )
        persist(updatedConfig)
        return updatedConfig
    }

    fun clearPinnedIdentity(config: SubscriptionConfig): SubscriptionConfig {
        val updatedConfig = config.copy(
            expectedSpkiPins = emptyList(),
        ).reconcileStoredTrust().copy(
            lastTrustSummary = if (config.lastObservedSpkiPins.isEmpty()) {
                "Cleared the explicit TLS identity pin; future checks rely on CA-validated HTTPS."
            } else {
                "Cleared the explicit TLS identity pin; future checks rely on CA-validated HTTPS until you pin an observed identity again."
            },
        )
        persist(updatedConfig)
        return updatedConfig
    }

    fun trustObservedPublisherIdentity(config: SubscriptionConfig): SubscriptionConfig {
        val observedPublisherPin = requireNotNull(config.lastObservedPublisherPin) {
            "Fetch a signed subscription feed before pinning its observed publisher identity."
        }
        val normalizedCurrentPins = SubscriptionPinPolicy.normalizePins(config.expectedPublisherPins)
        val normalizedUpdatedPins = SubscriptionPinPolicy.normalizePins(
            normalizedCurrentPins + observedPublisherPin,
        )
        val updatedConfig = config.copy(
            expectedPublisherPins = normalizedUpdatedPins,
        ).reconcileStoredTrust().copy(
            lastPublisherSummary = when {
                normalizedCurrentPins.isEmpty() -> {
                    "Pinned the last observed signed publisher identity for future subscription updates."
                }
                observedPublisherPin in normalizedCurrentPins -> {
                    "The observed signed publisher identity was already trusted."
                }
                else -> {
                    "Added the observed signed publisher identity to the trusted publisher set for rollover."
                }
            },
        )
        persist(updatedConfig)
        return updatedConfig
    }

    fun clearPublisherPins(config: SubscriptionConfig): SubscriptionConfig {
        val updatedConfig = config.copy(
            expectedPublisherPins = emptyList(),
        ).reconcileStoredTrust().copy(
            lastPublisherSummary = if (config.lastObservedPublisherPin == null) {
                "Cleared the explicit publisher pin; signed feeds will be informational unless pinned again."
            } else {
                "Cleared the explicit publisher pin; signed feeds will be informational until you trust an observed publisher identity again."
            },
        )
        persist(updatedConfig)
        return updatedConfig
    }

    private fun persist(config: SubscriptionConfig) {
        store.save(
            artifactType = SUBSCRIPTION_ARTIFACT_TYPE,
            payloadJson = CanonicalJson.instance.encodeToString(config),
            redacted = true,
        )
    }

    private fun persistPendingUpdate(pendingUpdate: SubscriptionPendingUpdate) {
        pendingStore.save(
            artifactType = SUBSCRIPTION_PENDING_UPDATE_ARTIFACT_TYPE,
            payloadJson = CanonicalJson.instance.encodeToString(pendingUpdate),
            redacted = true,
        )
    }

    private fun persistNotificationLedger(ledger: SubscriptionNotificationLedger) {
        notificationLedgerStore.save(
            artifactType = SUBSCRIPTION_NOTIFICATION_LEDGER_ARTIFACT_TYPE,
            payloadJson = CanonicalJson.instance.encodeToString(ledger),
            redacted = true,
        )
    }

    private fun appendEvent(event: SubscriptionEvent) {
        val current = loadEventLog()
        val updated = SubscriptionEventLog(
            entries = (listOf(event) + current.entries)
                .distinctBy { existing ->
                    "${existing.occurredAtEpochMs}:${existing.origin}:${existing.severity}:${existing.summary}"
                }
                .take(MAX_SUBSCRIPTION_EVENT_LOG_ENTRIES),
        )
        eventLogStore.save(
            artifactType = SUBSCRIPTION_EVENT_LOG_ARTIFACT_TYPE,
            payloadJson = CanonicalJson.instance.encodeToString(updated),
            redacted = true,
        )
    }
}

@Serializable
data class SubscriptionConfig(
    val sourceUrl: String,
    val sourceUrlHash: String,
    val savedAtEpochMs: Long,
    val lastCheckedAtEpochMs: Long? = null,
    val lastAppliedAtEpochMs: Long? = null,
    val lastAppliedProfileHash: String? = null,
    val lastAppliedProfileName: String? = null,
    val lastFetchedProfileHash: String? = null,
    val lastFetchedProfileCount: Int = 0,
    val lastResolvedUrlHash: String? = null,
    val lastEtag: String? = null,
    val lastModifiedHeader: String? = null,
    val expectedSpkiPins: List<String> = emptyList(),
    val lastObservedSpkiPins: List<String> = emptyList(),
    val lastTrustStatus: SubscriptionTrustStatus = SubscriptionTrustStatus.NONE,
    val lastTrustSummary: String? = null,
    val expectedPublisherPins: List<String> = emptyList(),
    val lastObservedPublisherPin: String? = null,
    val lastPublisherStatus: SubscriptionPublisherStatus = SubscriptionPublisherStatus.NONE,
    val lastPublisherSummary: String? = null,
    val scheduleMode: SubscriptionScheduleMode = SubscriptionScheduleMode.MANUAL,
    val lastScheduledRunAtEpochMs: Long? = null,
    val lastScheduledStatus: SubscriptionScheduledStatus = SubscriptionScheduledStatus.NONE,
    val lastScheduledSummary: String? = null,
    val scheduledUpdateAlertsEnabled: Boolean = true,
    val scheduledFailureAlertThreshold: Int = MIN_SCHEDULED_FAILURE_ALERT_THRESHOLD,
    val scheduledFailureStreak: Int = 0,
)

@Serializable
data class SubscriptionPendingUpdate(
    val sourceUrlHash: String,
    val title: String? = null,
    val checkedAtEpochMs: Long,
    val fetchedProfileCount: Int,
    val selectedProfileIndex: Int,
    val selectionSummary: String? = null,
    val trustStatus: SubscriptionTrustStatus,
    val publisherStatus: SubscriptionPublisherStatus,
    val observedPublisherPin: String? = null,
    val profiles: List<ProfileIr>,
)

@Serializable
data class SubscriptionEventLog(
    val entries: List<SubscriptionEvent> = emptyList(),
)

@Serializable
data class SubscriptionNotificationLedger(
    val lastEvaluationStatus: SubscriptionNotificationDeliveryStatus = SubscriptionNotificationDeliveryStatus.NONE,
    val lastEvaluationSummary: String? = null,
    val lastEvaluatedAtEpochMs: Long? = null,
    val lastEvaluatedEventAtEpochMs: Long? = null,
    val lastEvaluatedDestination: SubscriptionNotificationDestination? = null,
    val lastEvaluatedNotificationSummary: String? = null,
    val lastDeliveredAtEpochMs: Long? = null,
    val lastDeliveredEventAtEpochMs: Long? = null,
    val lastDeliveredDestination: SubscriptionNotificationDestination? = null,
    val lastDeliveredSummary: String? = null,
    val lastOpenedAtEpochMs: Long? = null,
    val lastOpenedEventAtEpochMs: Long? = null,
    val lastOpenedDestination: SubscriptionNotificationDestination? = null,
    val lastOpenedSummary: String? = null,
)

@Serializable
data class SubscriptionEvent(
    val occurredAtEpochMs: Long,
    val origin: SubscriptionEventOrigin,
    val severity: SubscriptionEventSeverity,
    val summary: String,
)

@Serializable
enum class SubscriptionEventOrigin {
    USER,
    SCHEDULED,
    SYSTEM,
}

@Serializable
enum class SubscriptionEventSeverity {
    INFO,
    WARN,
    ERROR,
}

@Serializable
enum class SubscriptionNotificationDeliveryStatus {
    NONE,
    DELIVERED,
    SKIPPED_DUPLICATE,
    SUPPRESSED_DISABLED,
    SUPPRESSED_POLICY,
}

@Serializable
enum class SubscriptionTrustStatus {
    NONE,
    TLS_ONLY,
    PINNED,
    PIN_MISMATCH,
}

@Serializable
enum class SubscriptionPublisherStatus {
    NONE,
    UNSIGNED,
    SIGNED_UNTRUSTED,
    TRUSTED,
    REQUIRED_MISSING,
    PIN_MISMATCH,
    INVALID_SIGNATURE,
}

enum class SubscriptionUpdateStatus {
    NOT_MODIFIED,
    UPDATED,
}

enum class SubscriptionUpdateTrigger {
    USER_INITIATED,
    SCHEDULED,
}

data class SubscriptionUpdateResult(
    val status: SubscriptionUpdateStatus,
    val updatedConfig: SubscriptionConfig,
    val checkedAtEpochMs: Long,
    val title: String? = null,
    val fetchedProfileCount: Int = 0,
    val fetchedProfiles: List<ProfileIr> = emptyList(),
    val primaryProfile: ProfileIr? = null,
    val selectedProfileIndex: Int = 0,
    val selectionSummary: String? = null,
    val diff: SubscriptionDiff? = null,
    val trustStatus: SubscriptionTrustStatus,
    val trustSummary: String,
    val observedSpkiPins: List<String>,
    val publisherStatus: SubscriptionPublisherStatus,
    val publisherSummary: String?,
    val observedPublisherPin: String?,
)

data class SubscriptionDiff(
    val activeProfileHash: String,
    val fetchedProfileHash: String,
    val fetchedProfileCount: Int,
    val endpointChanged: Boolean,
    val routingChanged: Boolean,
    val dnsChanged: Boolean,
    val vpnPolicyChanged: Boolean,
    val safetyChanged: Boolean,
) {
    fun summary(): String = buildString {
        append("Fetched $fetchedProfileCount validated profile")
        if (fetchedProfileCount != 1) append('s')
        append(".")
        val changedAreas = buildList {
            if (endpointChanged) add("endpoint")
            if (routingChanged) add("routing")
            if (dnsChanged) add("dns")
            if (vpnPolicyChanged) add("vpn policy")
            if (safetyChanged) add("safety")
        }
        if (changedAreas.isEmpty()) {
            append(" No material differences from the active profile were detected.")
        } else {
            append(" Changed areas: ${changedAreas.joinToString()}.")
        }
    }

    companion object {
        fun between(
            activeProfile: ProfileIr,
            fetchedProfile: ProfileIr,
            fetchedProfileCount: Int,
        ): SubscriptionDiff = SubscriptionDiff(
            activeProfileHash = activeProfile.canonicalHash(),
            fetchedProfileHash = fetchedProfile.canonicalHash(),
            fetchedProfileCount = fetchedProfileCount,
            endpointChanged = activeProfile.outbound != fetchedProfile.outbound,
            routingChanged = activeProfile.routing != fetchedProfile.routing,
            dnsChanged = activeProfile.dns != fetchedProfile.dns,
            vpnPolicyChanged = activeProfile.vpn != fetchedProfile.vpn,
            safetyChanged = activeProfile.safety != fetchedProfile.safety,
        )
    }
}

private data class SubscriptionFeedSelectionDecision(
    val selectedIndex: Int,
    val summary: String,
)

private object SubscriptionFeedSelectionPolicy {
    fun select(
        profiles: List<ProfileIr>,
        lastAppliedProfileHash: String?,
        lastAppliedProfileName: String?,
    ): SubscriptionFeedSelectionDecision {
        require(profiles.isNotEmpty()) {
            "Subscription feed selection requires at least one validated profile."
        }
        if (profiles.size == 1) {
            return SubscriptionFeedSelectionDecision(
                selectedIndex = 0,
                summary = "Selected the only validated feed candidate automatically.",
            )
        }
        val normalizedAppliedHash = lastAppliedProfileHash
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (normalizedAppliedHash != null) {
            val hashMatches = profiles.withIndex()
                .filter { (_, profile) -> profile.canonicalHash() == normalizedAppliedHash }
            if (hashMatches.isNotEmpty()) {
                val selectedIndex = hashMatches.first().index
                return SubscriptionFeedSelectionDecision(
                    selectedIndex = selectedIndex,
                    summary = "Matched the previously applied candidate by canonical hash and selected ${selectedIndex + 1}/${profiles.size}.",
                )
            }
        }
        val normalizedAppliedName = lastAppliedProfileName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (normalizedAppliedName != null) {
            val nameMatches = profiles.withIndex()
                .filter { (_, profile) -> profile.name == normalizedAppliedName }
            if (nameMatches.size == 1) {
                val selectedIndex = nameMatches.first().index
                return SubscriptionFeedSelectionDecision(
                    selectedIndex = selectedIndex,
                    summary = "Matched the previously applied candidate by profile name and selected ${selectedIndex + 1}/${profiles.size}.",
                )
            }
            if (nameMatches.size > 1) {
                val selectedIndex = nameMatches.first().index
                return SubscriptionFeedSelectionDecision(
                    selectedIndex = selectedIndex,
                    summary = "Multiple feed candidates matched the previously applied profile name; selected the first match at ${selectedIndex + 1}/${profiles.size}.",
                )
            }
        }
        return SubscriptionFeedSelectionDecision(
            selectedIndex = 0,
            summary = "The previously applied candidate was not present in the latest feed, so candidate 1/${profiles.size} is selected.",
        )
    }
}

data class SubscriptionFetchRequest(
    val sourceUrl: String,
    val etag: String? = null,
    val lastModified: String? = null,
)

data class SubscriptionFetchResponse(
    val statusCode: Int,
    val resolvedUrl: String,
    val body: String?,
    val etag: String?,
    val lastModified: String?,
    val observedSpkiPins: List<String> = emptyList(),
)

fun interface SubscriptionTransport {
    fun fetch(request: SubscriptionFetchRequest): SubscriptionFetchResponse
}

class HttpsSubscriptionTransport(
    private val maxPayloadBytes: Int = MAX_SUBSCRIPTION_PAYLOAD_BYTES,
) : SubscriptionTransport {
    override fun fetch(request: SubscriptionFetchRequest): SubscriptionFetchResponse {
        val validated = SubscriptionUrlPolicy.validate(request.sourceUrl)
        val connection = (URL(validated.toString()).openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json, text/plain, */*")
            request.etag?.let { setRequestProperty("If-None-Match", it) }
            request.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
        }
        return connection.useConnection { activeConnection ->
            val statusCode = activeConnection.responseCode
            if (statusCode in 300..399) {
                error("Subscription redirects are disabled by default; received HTTP $statusCode.")
            }
            val secureConnection = activeConnection as HttpsURLConnection
            val observedSpkiPins = secureConnection.peerSpkiPins()
            if (statusCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return@useConnection SubscriptionFetchResponse(
                    statusCode = statusCode,
                    resolvedUrl = activeConnection.url.toString(),
                    body = null,
                    etag = activeConnection.getHeaderField("ETag"),
                    lastModified = activeConnection.getHeaderField("Last-Modified"),
                    observedSpkiPins = observedSpkiPins,
                )
            }
            val contentLength = activeConnection.contentLengthLong
            if (contentLength > maxPayloadBytes) {
                error("Subscription payload exceeds the $maxPayloadBytes byte limit.")
            }
            val body = activeConnection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    if (output.size() > maxPayloadBytes) {
                        error("Subscription payload exceeds the $maxPayloadBytes byte limit.")
                    }
                }
                output.toString(StandardCharsets.UTF_8.name())
            }
            SubscriptionFetchResponse(
                statusCode = statusCode,
                resolvedUrl = activeConnection.url.toString(),
                body = body,
                etag = activeConnection.getHeaderField("ETag"),
                lastModified = activeConnection.getHeaderField("Last-Modified"),
                observedSpkiPins = observedSpkiPins,
            )
        }
    }
}

object SubscriptionPayloadParser {
    fun parse(input: String): ParsedSubscriptionPayload {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) {
            "Subscription payload must not be blank."
        }
        require(trimmed.toByteArray(Charsets.UTF_8).size <= MAX_SUBSCRIPTION_PAYLOAD_BYTES) {
            "Subscription payload exceeds the $MAX_SUBSCRIPTION_PAYLOAD_BYTES byte limit."
        }

        if (trimmed.startsWith("[")) {
            val profiles = CanonicalJson.instance.decodeFromString<List<ProfileIr>>(trimmed)
            return ParsedSubscriptionPayload(
                title = null,
                profiles = profiles.validateProfiles(),
            )
        }

        if (trimmed.startsWith("{")) {
            val document = runCatching {
                CanonicalJson.instance.decodeFromString<SubscriptionDocument>(trimmed)
            }.getOrNull()
            if (document != null) {
                require(document.signature == null || document.profiles.isNotEmpty()) {
                    "Signed subscription payloads must include at least one validated profile."
                }
                if (document.profiles.isNotEmpty()) {
                    val profiles = document.profiles.validateProfiles()
                    val publisherSignature = document.signature?.let { signature ->
                        SubscriptionSignatureVerifier.verify(
                            title = document.title,
                            profiles = profiles,
                            signature = signature,
                        )
                    }
                    return ParsedSubscriptionPayload(
                        title = document.title,
                        profiles = profiles,
                        publisherSignature = publisherSignature,
                    )
                }
            }
        }

        val imported = ProfileImportParser.parse(trimmed)
        return ParsedSubscriptionPayload(
            title = null,
            profiles = listOf(imported.profile),
        )
    }
}

data class ParsedSubscriptionPayload(
    val title: String?,
    val profiles: List<ProfileIr>,
    val publisherSignature: VerifiedSubscriptionPublisher? = null,
)

data class VerifiedSubscriptionPublisher(
    val algorithm: String,
    val signerPin: String,
)

@Serializable
private data class SubscriptionDocument(
    val title: String? = null,
    val profiles: List<ProfileIr> = emptyList(),
    val signature: SubscriptionSignatureDocument? = null,
)

@Serializable
private data class SubscriptionSignatureDocument(
    val algorithm: String,
    val publicKeyPem: String,
    val signatureBase64: String,
)

@Serializable
private data class SignedSubscriptionPayload(
    val title: String? = null,
    val profiles: List<ProfileIr> = emptyList(),
)

object SubscriptionUrlPolicy {
    fun validate(sourceUrl: String): URI {
        val candidate = sourceUrl.trim()
        require(candidate.isNotEmpty()) {
            "Subscription URL must not be blank."
        }
        val uri = URI(candidate)
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Only HTTPS subscription URLs are accepted."
        }
        require(uri.userInfo.isNullOrBlank()) {
            "Subscription URLs must not embed credentials."
        }
        require(uri.fragment.isNullOrBlank()) {
            "Subscription URLs must not include fragments."
        }
        val host = uri.host?.trim().orEmpty()
        require(host.isNotEmpty()) {
            "Subscription URL host must not be blank."
        }
        val normalizedHost = host.lowercase()
        require(normalizedHost != "localhost" && !normalizedHost.endsWith(".localhost")) {
            "Localhost subscription URLs are not allowed."
        }
        require(!normalizedHost.endsWith(".local")) {
            "Local network hostnames are not allowed for subscription updates."
        }
        parseLiteralAddress(normalizedHost)?.let { address ->
            require(!address.isAnyLocalAddress) {
                "Wildcard subscription endpoints are not allowed."
            }
            require(!address.isLoopbackAddress) {
                "Loopback subscription endpoints are not allowed."
            }
            require(!address.isSiteLocalAddress) {
                "Private network subscription endpoints are not allowed."
            }
            require(!address.isLinkLocalAddress) {
                "Link-local subscription endpoints are not allowed."
            }
            require(!address.isMulticastAddress) {
                "Multicast subscription endpoints are not allowed."
            }
        }
        return uri.normalize()
    }

    private fun parseLiteralAddress(host: String): InetAddress? {
        val isIpv4Literal = IPV4_LITERAL.matches(host)
        val isIpv6Literal = host.contains(':') && IPV6_LITERAL.matches(host)
        if (!isIpv4Literal && !isIpv6Literal) {
            return null
        }
        val address = InetAddress.getByName(host)
        return when (address) {
            is Inet4Address, is Inet6Address -> address
            else -> null
        }
    }

    private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val IPV6_LITERAL = Regex("""[0-9a-f:.%]+""")
}

object SubscriptionPinPolicy {
    private const val PIN_PREFIX: String = "sha256/"

    fun normalizeInput(raw: String): List<String>? {
        val candidate = raw.trim()
        if (candidate.isEmpty()) {
            return null
        }
        val pins = candidate.split(Regex("""[\s,]+"""))
            .map(String::trim)
            .filter(String::isNotEmpty)
        require(pins.isNotEmpty()) {
            "Enter at least one identity pin or leave the field blank."
        }
        return normalizePins(pins)
    }

    fun normalizePins(pins: Iterable<String>): List<String> = pins
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(::normalizePin)
        .distinct()
        .sorted()

    fun spkiSha256Pin(subjectPublicKeyInfo: ByteArray): String = PIN_PREFIX + Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(subjectPublicKeyInfo),
    )

    fun summarize(pins: List<String>): String? {
        if (pins.isEmpty()) {
            return null
        }
        val first = pins.first().summarizePin()
        if (pins.size == 1) {
            return first
        }
        return "$first (+${pins.size - 1} more)"
    }

    fun normalizeSinglePinOrNull(pin: String?): String? {
        val candidate = pin?.trim().orEmpty()
        if (candidate.isEmpty()) {
            return null
        }
        return normalizePins(listOf(candidate)).single()
    }

    private fun normalizePin(pin: String): String {
        require(pin.startsWith(PIN_PREFIX)) {
            "Identity pins must use the sha256/<base64> format."
        }
        val decoded = runCatching { Base64.getDecoder().decode(pin.removePrefix(PIN_PREFIX)) }
            .getOrElse { throw IllegalArgumentException("Identity pins must contain valid base64 SHA-256 digests.", it) }
        require(decoded.size == 32) {
            "Identity pins must encode a 32-byte SHA-256 digest."
        }
        return PIN_PREFIX + Base64.getEncoder().encodeToString(decoded)
    }
}

data class SubscriptionTrustDecision(
    val status: SubscriptionTrustStatus,
    val summary: String,
)

class SubscriptionTrustException(
    message: String,
) : IllegalStateException(message)

data class SubscriptionPublisherTrustDecision(
    val status: SubscriptionPublisherStatus,
    val summary: String,
)

class SubscriptionPublisherException(
    val status: SubscriptionPublisherStatus,
    val observedPublisherPin: String? = null,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private object SubscriptionTrustPolicy {
    fun evaluate(
        expectedPins: List<String>,
        observedPins: List<String>,
    ): SubscriptionTrustDecision {
        val normalizedExpectedPins = SubscriptionPinPolicy.normalizePins(expectedPins)
        val normalizedObservedPins = SubscriptionPinPolicy.normalizePins(observedPins)
        if (normalizedExpectedPins.isEmpty()) {
            val summary = if (normalizedObservedPins.isEmpty()) {
                "Validated HTTPS transport, but the peer TLS identity pin could not be captured for review."
            } else {
                "Validated HTTPS transport and observed ${normalizedObservedPins.size} TLS identity pin(s); no explicit pin is configured."
            }
            return SubscriptionTrustDecision(
                status = SubscriptionTrustStatus.TLS_ONLY,
                summary = summary,
            )
        }
        if (normalizedObservedPins.isEmpty()) {
            throw SubscriptionTrustException(
                "Pinned subscription updates require at least one observed TLS identity pin from the peer certificate chain.",
            )
        }
        if (normalizedObservedPins.any { it in normalizedExpectedPins }) {
            return SubscriptionTrustDecision(
                status = SubscriptionTrustStatus.PINNED,
                summary = "Validated HTTPS transport and matched a pinned TLS identity.",
            )
        }
        throw SubscriptionTrustException(
            "Observed TLS identity pin(s) did not match the configured pin set.",
        )
    }
}

private object SubscriptionPublisherTrustPolicy {
    fun evaluate(
        expectedPins: List<String>,
        publisherSignature: VerifiedSubscriptionPublisher?,
    ): SubscriptionPublisherTrustDecision {
        val normalizedExpectedPins = SubscriptionPinPolicy.normalizePins(expectedPins)
        if (publisherSignature == null) {
            if (normalizedExpectedPins.isEmpty()) {
                return SubscriptionPublisherTrustDecision(
                    status = SubscriptionPublisherStatus.UNSIGNED,
                    summary = "Subscription payload was unsigned; relying on transport trust only.",
                )
            }
            throw SubscriptionPublisherException(
                status = SubscriptionPublisherStatus.REQUIRED_MISSING,
                message = "A trusted publisher pin is configured, but the subscription payload was unsigned.",
            )
        }
        if (normalizedExpectedPins.isEmpty()) {
            return SubscriptionPublisherTrustDecision(
                status = SubscriptionPublisherStatus.SIGNED_UNTRUSTED,
                summary = "Validated the subscription signature from an observed publisher identity; no trusted publisher pin is configured.",
            )
        }
        if (publisherSignature.signerPin in normalizedExpectedPins) {
            return SubscriptionPublisherTrustDecision(
                status = SubscriptionPublisherStatus.TRUSTED,
                summary = "Validated the subscription signature and matched a trusted publisher identity.",
            )
        }
        throw SubscriptionPublisherException(
            status = SubscriptionPublisherStatus.PIN_MISMATCH,
            observedPublisherPin = publisherSignature.signerPin,
            message = "Signed subscription publisher identity did not match the configured publisher pin set.",
        )
    }
}

private object SubscriptionSignatureVerifier {
    fun verify(
        title: String?,
        profiles: List<ProfileIr>,
        signature: SubscriptionSignatureDocument,
    ): VerifiedSubscriptionPublisher {
        require(signature.algorithm == SIGNED_SUBSCRIPTION_ALGORITHM) {
            "Unsupported subscription signature algorithm '${signature.algorithm}'."
        }
        val publicKeyBytes = decodePemPublicKey(signature.publicKeyPem)
        val signerPin = SubscriptionPinPolicy.spkiSha256Pin(publicKeyBytes)
        val publicKey = parseEcPublicKey(publicKeyBytes, signerPin)
        val signatureBytes = decodeSignature(signature.signatureBase64, signerPin)
        val canonicalPayload = CanonicalJson.instance.encodeToString(
            SignedSubscriptionPayload(
                title = title,
                profiles = profiles,
            ),
        ).toByteArray(StandardCharsets.UTF_8)
        val verifier = runCatching { Signature.getInstance(SIGNED_SUBSCRIPTION_ALGORITHM) }
            .getOrElse {
                throw SubscriptionPublisherException(
                    status = SubscriptionPublisherStatus.INVALID_SIGNATURE,
                    observedPublisherPin = signerPin,
                    message = "Signature algorithm '$SIGNED_SUBSCRIPTION_ALGORITHM' is unavailable in the current runtime.",
                    cause = it,
                )
            }
        val verified = runCatching {
            verifier.initVerify(publicKey)
            verifier.update(canonicalPayload)
            verifier.verify(signatureBytes)
        }.getOrElse {
            throw SubscriptionPublisherException(
                status = SubscriptionPublisherStatus.INVALID_SIGNATURE,
                observedPublisherPin = signerPin,
                message = "Subscription payload signature verification failed.",
                cause = it,
            )
        }
        if (!verified) {
            throw SubscriptionPublisherException(
                status = SubscriptionPublisherStatus.INVALID_SIGNATURE,
                observedPublisherPin = signerPin,
                message = "Subscription payload signature verification failed.",
            )
        }
        return VerifiedSubscriptionPublisher(
            algorithm = SIGNED_SUBSCRIPTION_ALGORITHM,
            signerPin = signerPin,
        )
    }

    private fun decodePemPublicKey(publicKeyPem: String): ByteArray {
        val cleaned = publicKeyPem.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("-----BEGIN") && !it.startsWith("-----END") }
            .joinToString(separator = "")
        if (cleaned.isEmpty()) {
            throw SubscriptionPublisherException(
                status = SubscriptionPublisherStatus.INVALID_SIGNATURE,
                message = "Subscription signature public key PEM must not be blank.",
            )
        }
        return runCatching { Base64.getDecoder().decode(cleaned) }
            .getOrElse {
                throw SubscriptionPublisherException(
                    status = SubscriptionPublisherStatus.INVALID_SIGNATURE,
                    message = "Subscription signature public key PEM is not valid base64.",
                    cause = it,
                )
            }
    }

    private fun parseEcPublicKey(
        publicKeyBytes: ByteArray,
        signerPin: String,
    ): PublicKey = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyBytes))
    }.getOrElse {
        throw SubscriptionPublisherException(
            status = SubscriptionPublisherStatus.INVALID_SIGNATURE,
            observedPublisherPin = signerPin,
            message = "Subscription signature public key is not a valid EC SPKI key.",
            cause = it,
        )
    }

    private fun decodeSignature(
        signatureBase64: String,
        signerPin: String,
    ): ByteArray = runCatching {
        Base64.getDecoder().decode(signatureBase64.trim())
    }.getOrElse {
        throw SubscriptionPublisherException(
            status = SubscriptionPublisherStatus.INVALID_SIGNATURE,
            observedPublisherPin = signerPin,
            message = "Subscription signature payload is not valid base64.",
            cause = it,
        )
    }
}

private fun SubscriptionConfig.reconcileStoredTrust(): SubscriptionConfig {
    return reconcileStoredTransportTrust()
        .reconcileStoredPublisherTrust()
}

private fun SubscriptionConfig.reconcileStoredTransportTrust(): SubscriptionConfig {
    if (expectedSpkiPins.isEmpty()) {
        return copy(
            lastTrustStatus = if (lastObservedSpkiPins.isEmpty()) {
                SubscriptionTrustStatus.NONE
            } else {
                SubscriptionTrustStatus.TLS_ONLY
            },
            lastTrustSummary = if (lastObservedSpkiPins.isEmpty()) {
                null
            } else {
                "Explicit TLS identity pinning is disabled; updates rely on CA-validated HTTPS."
            },
        )
    }
    if (lastObservedSpkiPins.isEmpty()) {
        return copy(
            lastTrustStatus = SubscriptionTrustStatus.NONE,
            lastTrustSummary = "A TLS identity pin is configured and will be enforced on the next update check.",
        )
    }
    return runCatching {
        SubscriptionTrustPolicy.evaluate(
            expectedPins = expectedSpkiPins,
            observedPins = lastObservedSpkiPins,
        )
    }.map { decision ->
        copy(
            lastTrustStatus = decision.status,
            lastTrustSummary = decision.summary,
        )
    }.getOrElse { error ->
        copy(
            lastTrustStatus = SubscriptionTrustStatus.PIN_MISMATCH,
            lastTrustSummary = error.message,
        )
    }
}

private fun SubscriptionConfig.reconcileStoredPublisherTrust(): SubscriptionConfig {
    if (lastPublisherStatus == SubscriptionPublisherStatus.INVALID_SIGNATURE) {
        return this
    }
    val observedPublisherPin = SubscriptionPinPolicy.normalizeSinglePinOrNull(lastObservedPublisherPin)
    val normalizedExpectedPins = SubscriptionPinPolicy.normalizePins(expectedPublisherPins)
    if (normalizedExpectedPins.isEmpty()) {
        return copy(
            lastPublisherStatus = if (observedPublisherPin == null) {
                SubscriptionPublisherStatus.NONE
            } else {
                SubscriptionPublisherStatus.SIGNED_UNTRUSTED
            },
            lastPublisherSummary = if (observedPublisherPin == null) {
                null
            } else {
                "Observed a signed publisher identity, but no trusted publisher pin is configured."
            },
        )
    }
    if (observedPublisherPin == null) {
        return copy(
            lastPublisherStatus = SubscriptionPublisherStatus.NONE,
            lastPublisherSummary = "A trusted publisher pin is configured and will be enforced when a signed subscription feed is fetched.",
        )
    }
    return if (observedPublisherPin in normalizedExpectedPins) {
        copy(
            lastPublisherStatus = SubscriptionPublisherStatus.TRUSTED,
            lastPublisherSummary = "Matched a trusted subscription publisher identity.",
        )
    } else {
        copy(
            lastPublisherStatus = SubscriptionPublisherStatus.PIN_MISMATCH,
            lastPublisherSummary = "Observed signed publisher identity did not match the configured publisher pin set.",
        )
    }
}

private fun SubscriptionConfig.withScheduledOutcome(
    trigger: SubscriptionUpdateTrigger,
    checkedAtEpochMs: Long,
    status: SubscriptionScheduledStatus,
    summary: String,
): SubscriptionConfig {
    val updatedFailureStreak = when (status) {
        SubscriptionScheduledStatus.FAILED -> {
            if (trigger == SubscriptionUpdateTrigger.SCHEDULED) {
                scheduledFailureStreak + 1
            } else {
                scheduledFailureStreak
            }
        }
        SubscriptionScheduledStatus.NOT_MODIFIED,
        SubscriptionScheduledStatus.UPDATED,
            -> 0
        else -> scheduledFailureStreak
    }
    if (trigger != SubscriptionUpdateTrigger.SCHEDULED) {
        return copy(
            scheduledFailureStreak = updatedFailureStreak,
        )
    }
    return copy(
        lastScheduledRunAtEpochMs = checkedAtEpochMs,
        lastScheduledStatus = status,
        lastScheduledSummary = summary,
        scheduledFailureStreak = updatedFailureStreak,
    )
}

private fun SubscriptionUpdateTrigger.toEventOrigin(): SubscriptionEventOrigin = when (this) {
    SubscriptionUpdateTrigger.USER_INITIATED -> SubscriptionEventOrigin.USER
    SubscriptionUpdateTrigger.SCHEDULED -> SubscriptionEventOrigin.SCHEDULED
}

private fun buildFailureEventSummary(
    trigger: SubscriptionUpdateTrigger,
    summary: String,
): String {
    val prefix = when (trigger) {
        SubscriptionUpdateTrigger.USER_INITIATED -> "Manual subscription check failed"
        SubscriptionUpdateTrigger.SCHEDULED -> "Scheduled subscription check failed"
    }
    return "$prefix: ${sanitizeEventSummary(summary)}"
}

private fun SubscriptionScheduleMode.schedulingSummary(): String = when (this) {
    SubscriptionScheduleMode.MANUAL -> "Background subscription checks are disabled."
    SubscriptionScheduleMode.EVERY_6_HOURS -> "Background subscription checks are scheduled every 6 hours when network is available."
    SubscriptionScheduleMode.EVERY_24_HOURS -> "Background subscription checks are scheduled every 24 hours when network is available."
}

private fun HttpsURLConnection.peerSpkiPins(): List<String> = runCatching {
    serverCertificates.asList()
        .mapNotNull { certificate -> (certificate as? X509Certificate)?.publicKey?.encoded }
        .map(SubscriptionPinPolicy::spkiSha256Pin)
        .distinct()
        .sorted()
}.getOrDefault(emptyList())

private fun HttpURLConnection.useConnection(block: (HttpURLConnection) -> SubscriptionFetchResponse): SubscriptionFetchResponse {
    return try {
        connect()
        block(this)
    } finally {
        disconnect()
    }
}

private fun List<ProfileIr>.validateProfiles(): List<ProfileIr> {
    require(isNotEmpty()) {
        "Subscription payload must include at least one validated profile."
    }
    forEachIndexed { index, profile ->
        val issues = profile.validate()
        require(issues.isEmpty()) {
            "Subscription profile[$index] failed validation: ${issues.joinToString { "${it.field}: ${it.message}" }}"
        }
    }
    return this
}

private fun String.redactedDigest(): String = "sha256:${CanonicalJson.sha256Hex(this).take(16)}:$length"

private fun sanitizeEventSummary(summary: String): String = summary
    .replace(Regex("""https?://\S+"""), "<redacted-url>")
    .replace(Regex("""sha256/[A-Za-z0-9+/=]+"""), "sha256/<redacted>")
    .replace(
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"""),
        "<redacted-uuid>",
    )
    .trim()

private fun String.summarizePin(): String {
    if (length <= 24) {
        return this
    }
    return "${take(18)}...${takeLast(8)}"
}
