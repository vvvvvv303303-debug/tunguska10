package io.acionyx.tunguska.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

private const val SUBSCRIPTION_REFRESH_WORK_NAME: String = "io.acionyx.tunguska.subscription.refresh"
private const val SUBSCRIPTION_REFRESH_WORK_TAG: String = "subscription-refresh"

@Serializable
enum class SubscriptionScheduleMode {
    MANUAL,
    EVERY_6_HOURS,
    EVERY_24_HOURS,
}

@Serializable
enum class SubscriptionScheduledStatus {
    NONE,
    DISABLED,
    SCHEDULED,
    CHECKING,
    NOT_MODIFIED,
    UPDATED,
    FAILED,
}

internal val SubscriptionScheduleMode.repeatIntervalHours: Long?
    get() = when (this) {
        SubscriptionScheduleMode.MANUAL -> null
        SubscriptionScheduleMode.EVERY_6_HOURS -> 6L
        SubscriptionScheduleMode.EVERY_24_HOURS -> 24L
    }

internal fun SubscriptionScheduleMode.summary(): String = when (this) {
    SubscriptionScheduleMode.MANUAL -> "Manual only"
    SubscriptionScheduleMode.EVERY_6_HOURS -> "Every 6 hours"
    SubscriptionScheduleMode.EVERY_24_HOURS -> "Every 24 hours"
}

class SubscriptionUpdateScheduler(
    context: Context,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun reconcile(config: SubscriptionConfig) {
        val repeatIntervalHours = config.scheduleMode.repeatIntervalHours
        if (repeatIntervalHours == null) {
            workManager.cancelUniqueWork(SUBSCRIPTION_REFRESH_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(
            repeatIntervalHours,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .addTag(SUBSCRIPTION_REFRESH_WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            SUBSCRIPTION_REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

class SubscriptionRefreshWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    private val profileRepository = SecureProfileRepository(appContext)
    private val subscriptionRepository = SecureSubscriptionRepository(appContext)
    private val notificationPublisher = SubscriptionNotificationPublisher(appContext)

    override fun doWork(): Result {
        val config = runCatching { subscriptionRepository.loadConfig() }
            .getOrElse { return Result.success() }
            ?: return Result.success()
        if (config.scheduleMode == SubscriptionScheduleMode.MANUAL) {
            return Result.success()
        }
        val activeProfile = runCatching {
            profileRepository.loadOrSeed(defaultBootstrapProfile()).storedProfile.profile
        }.getOrElse { error ->
            val failedConfig = subscriptionRepository.markScheduledRunFailure(
                config = config,
                summary = error.message ?: error.javaClass.simpleName,
            )
            publishScheduledNotification(failedConfig)
            return Result.success()
        }
        val runningConfig = subscriptionRepository.markScheduledRunStarted(config)
        return runCatching {
            subscriptionRepository.fetchUpdate(
                config = runningConfig,
                activeProfile = activeProfile,
                trigger = SubscriptionUpdateTrigger.SCHEDULED,
            )
        }.fold(
            onSuccess = { update ->
                publishScheduledNotification(update.updatedConfig)
                Result.success()
            },
            onFailure = { error ->
                val failedConfig = subscriptionRepository.markScheduledRunFailure(
                    config = runCatching { subscriptionRepository.loadConfig() }.getOrNull() ?: runningConfig,
                    summary = error.message ?: error.javaClass.simpleName,
                )
                publishScheduledNotification(failedConfig)
                Result.success()
            },
        )
    }

    private fun publishScheduledNotification(config: SubscriptionConfig) {
        val latestEvent = subscriptionRepository.loadEventLog().entries.firstOrNull()
        val pendingUpdate = subscriptionRepository.loadPendingUpdate()
        val content = SubscriptionNotificationPolicy.decide(
            config = config,
            latestEvent = latestEvent,
            pendingUpdate = pendingUpdate,
        ) ?: return
        val alertPolicy = SubscriptionNotificationAlertPolicy.evaluate(config, content)
        if (!alertPolicy.shouldPublish) {
            subscriptionRepository.markNotificationSuppressed(
                route = content.route,
                reason = alertPolicy.summary ?: "Scheduled alert was suppressed by policy.",
                status = SubscriptionNotificationDeliveryStatus.SUPPRESSED_POLICY,
            )
            return
        }
        when (
            SubscriptionNotificationDispatchPolicy.decide(
                route = content.route,
                ledger = subscriptionRepository.loadNotificationLedger(),
            )
        ) {
            SubscriptionNotificationDispatchDecision.SKIP_DUPLICATE -> {
                subscriptionRepository.markNotificationSkippedDuplicate(content.route)
            }
            SubscriptionNotificationDispatchDecision.PUBLISH -> {
                if (notificationPublisher.publish(content)) {
                    subscriptionRepository.markNotificationDelivered(content.route)
                } else {
                    subscriptionRepository.markNotificationSuppressed(
                        route = content.route,
                        reason = "Android notification delivery is blocked or disabled.",
                        status = SubscriptionNotificationDeliveryStatus.SUPPRESSED_DISABLED,
                    )
                }
            }
        }
    }
}
