package io.acionyx.tunguska.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import io.acionyx.tunguska.R
import kotlinx.serialization.Serializable

private const val SUBSCRIPTION_NOTIFICATION_CHANNEL_ID: String = "subscription-updates"
private const val SUBSCRIPTION_NOTIFICATION_CHANNEL_NAME: String = "Subscription Updates"
private const val SUBSCRIPTION_NOTIFICATION_CHANNEL_DESCRIPTION: String =
    "Background subscription checks and staged update alerts."
private const val SUBSCRIPTION_NOTIFICATION_ID: Int = 3001
private const val SUBSCRIPTION_NOTIFICATION_ACTION: String =
    "io.acionyx.tunguska.app.action.SUBSCRIPTION_NOTIFICATION"
private const val EXTRA_SUBSCRIPTION_NOTIFICATION_DESTINATION: String =
    "io.acionyx.tunguska.app.extra.SUBSCRIPTION_NOTIFICATION_DESTINATION"
private const val EXTRA_SUBSCRIPTION_NOTIFICATION_SUMMARY: String =
    "io.acionyx.tunguska.app.extra.SUBSCRIPTION_NOTIFICATION_SUMMARY"
private const val EXTRA_SUBSCRIPTION_NOTIFICATION_OCCURRED_AT: String =
    "io.acionyx.tunguska.app.extra.SUBSCRIPTION_NOTIFICATION_OCCURRED_AT"
private const val MAX_NOTIFICATION_SUMMARY_CHARS: Int = 180

@Serializable
enum class SubscriptionNotificationDestination {
    REVIEW_UPDATE,
    REVIEW_INBOX,
}

data class SubscriptionNotificationRoute(
    val destination: SubscriptionNotificationDestination,
    val summary: String,
    val occurredAtEpochMs: Long,
)

data class SubscriptionNotificationContent(
    val title: String,
    val text: String,
    val actionTitle: String,
    val route: SubscriptionNotificationRoute,
)

data class SubscriptionNotificationAlertPolicyResult(
    val shouldPublish: Boolean,
    val summary: String? = null,
)

enum class SubscriptionNotificationDispatchDecision {
    PUBLISH,
    SKIP_DUPLICATE,
}

object SubscriptionNotificationPolicy {
    fun decide(
        config: SubscriptionConfig,
        latestEvent: SubscriptionEvent?,
        pendingUpdate: SubscriptionPendingUpdate?,
    ): SubscriptionNotificationContent? {
        if (latestEvent == null || latestEvent.origin != SubscriptionEventOrigin.SCHEDULED) {
            return null
        }
        return when (config.lastScheduledStatus) {
            SubscriptionScheduledStatus.UPDATED -> {
                if (pendingUpdate == null || pendingUpdate.profiles.isEmpty()) {
                    null
                } else {
                    SubscriptionNotificationContent(
                        title = if (pendingUpdate.fetchedProfileCount == 1) {
                            "Subscription Update Ready"
                        } else {
                            "Subscription Candidates Ready"
                        },
                        text = latestEvent.summary,
                        actionTitle = if (pendingUpdate.fetchedProfileCount == 1) {
                            "Review Update"
                        } else {
                            "Review Candidates"
                        },
                        route = SubscriptionNotificationRoute(
                            destination = SubscriptionNotificationDestination.REVIEW_UPDATE,
                            summary = latestEvent.summary,
                            occurredAtEpochMs = latestEvent.occurredAtEpochMs,
                        ),
                    )
                }
            }
            SubscriptionScheduledStatus.FAILED -> SubscriptionNotificationContent(
                title = "Subscription Check Failed",
                text = latestEvent.summary,
                actionTitle = "Open Inbox",
                route = SubscriptionNotificationRoute(
                    destination = SubscriptionNotificationDestination.REVIEW_INBOX,
                    summary = latestEvent.summary,
                    occurredAtEpochMs = latestEvent.occurredAtEpochMs,
                ),
            )
            else -> null
        }
    }
}

object SubscriptionNotificationDispatchPolicy {
    fun decide(
        route: SubscriptionNotificationRoute,
        ledger: SubscriptionNotificationLedger,
    ): SubscriptionNotificationDispatchDecision {
        val summary = sanitizeNotificationSummary(route.summary)
        return if (
            ledger.lastDeliveredEventAtEpochMs == route.occurredAtEpochMs &&
            ledger.lastDeliveredDestination == route.destination &&
            ledger.lastDeliveredSummary == summary
        ) {
            SubscriptionNotificationDispatchDecision.SKIP_DUPLICATE
        } else {
            SubscriptionNotificationDispatchDecision.PUBLISH
        }
    }
}

object SubscriptionNotificationAlertPolicy {
    fun evaluate(
        config: SubscriptionConfig,
        content: SubscriptionNotificationContent,
    ): SubscriptionNotificationAlertPolicyResult {
        return when (content.route.destination) {
            SubscriptionNotificationDestination.REVIEW_UPDATE -> {
                if (config.scheduledUpdateAlertsEnabled) {
                    SubscriptionNotificationAlertPolicyResult(shouldPublish = true)
                } else {
                    SubscriptionNotificationAlertPolicyResult(
                        shouldPublish = false,
                        summary = "Scheduled update alerts are disabled by policy.",
                    )
                }
            }
            SubscriptionNotificationDestination.REVIEW_INBOX -> {
                if (config.scheduledFailureStreak >= config.scheduledFailureAlertThreshold) {
                    SubscriptionNotificationAlertPolicyResult(shouldPublish = true)
                } else {
                    SubscriptionNotificationAlertPolicyResult(
                        shouldPublish = false,
                        summary = "Holding scheduled failure alerts until streak ${config.scheduledFailureAlertThreshold}; current streak ${config.scheduledFailureStreak}.",
                    )
                }
            }
        }
    }
}

class SubscriptionNotificationPublisher(
    private val context: Context,
) {
    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun areNotificationsEnabled(): Boolean = notificationManager.areNotificationsEnabled()

    fun publish(content: SubscriptionNotificationContent): Boolean {
        if (!areNotificationsEnabled()) {
            return false
        }
        ensureChannel()
        val openAppIntent = SubscriptionNotificationIntents.buildOpenAppPendingIntent(context, content.route)
        notificationManager.notify(
            SUBSCRIPTION_NOTIFICATION_ID,
            Notification.Builder(context, SUBSCRIPTION_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tunguska_notification)
                .setContentTitle(content.title)
                .setContentText(content.text)
                .setStyle(Notification.BigTextStyle().bigText(content.text))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(openAppIntent)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_tunguska_notification),
                        content.actionTitle,
                        openAppIntent,
                    ).build(),
                )
                .build(),
        )
        return true
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val existing = notificationManager.getNotificationChannel(SUBSCRIPTION_NOTIFICATION_CHANNEL_ID)
        if (existing != null) {
            return
        }
        notificationManager.createNotificationChannel(
            NotificationChannel(
                SUBSCRIPTION_NOTIFICATION_CHANNEL_ID,
                SUBSCRIPTION_NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = SUBSCRIPTION_NOTIFICATION_CHANNEL_DESCRIPTION
            },
        )
    }
}

object SubscriptionNotificationIntents {
    fun buildOpenAppPendingIntent(
        context: Context,
        route: SubscriptionNotificationRoute,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = SUBSCRIPTION_NOTIFICATION_ACTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SUBSCRIPTION_NOTIFICATION_DESTINATION, route.destination.name)
            putExtra(EXTRA_SUBSCRIPTION_NOTIFICATION_SUMMARY, sanitizeNotificationSummary(route.summary))
            putExtra(EXTRA_SUBSCRIPTION_NOTIFICATION_OCCURRED_AT, route.occurredAtEpochMs)
        }
        return PendingIntent.getActivity(
            context,
            route.destination.ordinal + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun fromIntent(intent: Intent?): SubscriptionNotificationRoute? {
        val destinationName = intent?.getStringExtra(EXTRA_SUBSCRIPTION_NOTIFICATION_DESTINATION)
            ?: return null
        val destination = runCatching { SubscriptionNotificationDestination.valueOf(destinationName) }
            .getOrNull()
            ?: return null
        val occurredAtEpochMs = intent.getLongExtra(EXTRA_SUBSCRIPTION_NOTIFICATION_OCCURRED_AT, 0L)
            .takeIf { it > 0L }
            ?: return null
        val summary = sanitizeNotificationSummary(
            intent.getStringExtra(EXTRA_SUBSCRIPTION_NOTIFICATION_SUMMARY).orEmpty(),
        ).ifBlank {
            when (destination) {
                SubscriptionNotificationDestination.REVIEW_UPDATE ->
                    "Scheduled subscription check staged a validated profile update."
                SubscriptionNotificationDestination.REVIEW_INBOX ->
                    "Scheduled subscription check failed and recorded a redacted inbox entry."
            }
        }
        return SubscriptionNotificationRoute(
            destination = destination,
            summary = summary,
            occurredAtEpochMs = occurredAtEpochMs,
        )
    }
}

internal fun sanitizeNotificationSummary(summary: String): String = summary
    .replace(Regex("""https?://\S+"""), "<redacted-url>")
    .replace(Regex("""sha256/[A-Za-z0-9+/=]+"""), "sha256/<redacted>")
    .replace(Regex("""\s+"""), " ")
    .trim()
    .let {
        if (it.length <= MAX_NOTIFICATION_SUMMARY_CHARS) {
            it
        } else {
            "${it.take(MAX_NOTIFICATION_SUMMARY_CHARS - 3)}..."
        }
    }
