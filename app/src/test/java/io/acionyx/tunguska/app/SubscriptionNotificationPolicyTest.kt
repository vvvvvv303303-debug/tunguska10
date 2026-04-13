package io.acionyx.tunguska.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionNotificationPolicyTest {
    @Test
    fun `scheduled updated result with pending profiles produces a notification`() {
        val content = SubscriptionNotificationPolicy.decide(
            config = subscriptionConfig(lastScheduledStatus = SubscriptionScheduledStatus.UPDATED),
            latestEvent = SubscriptionEvent(
                occurredAtEpochMs = 1234L,
                origin = SubscriptionEventOrigin.SCHEDULED,
                severity = SubscriptionEventSeverity.INFO,
                summary = "Scheduled subscription check staged a validated profile update.",
            ),
            pendingUpdate = SubscriptionPendingUpdate(
                sourceUrlHash = "sha256:source",
                checkedAtEpochMs = 1234L,
                fetchedProfileCount = 1,
                selectedProfileIndex = 0,
                trustStatus = SubscriptionTrustStatus.TLS_ONLY,
                publisherStatus = SubscriptionPublisherStatus.UNSIGNED,
                profiles = listOf(defaultBootstrapProfile()),
            ),
        )

        assertNotNull(content)
        assertEquals("Subscription Update Ready", content!!.title)
        assertEquals("Review Update", content.actionTitle)
        assertEquals(SubscriptionNotificationDestination.REVIEW_UPDATE, content.route.destination)
    }

    @Test
    fun `scheduled failure produces a notification`() {
        val content = SubscriptionNotificationPolicy.decide(
            config = subscriptionConfig(lastScheduledStatus = SubscriptionScheduledStatus.FAILED),
            latestEvent = SubscriptionEvent(
                occurredAtEpochMs = 1234L,
                origin = SubscriptionEventOrigin.SCHEDULED,
                severity = SubscriptionEventSeverity.ERROR,
                summary = "Scheduled subscription check failed: Publisher trust validation failed.",
            ),
            pendingUpdate = null,
        )

        assertNotNull(content)
        assertEquals("Subscription Check Failed", content!!.title)
        assertEquals("Open Inbox", content.actionTitle)
        assertEquals(SubscriptionNotificationDestination.REVIEW_INBOX, content.route.destination)
    }

    @Test
    fun `scheduled update with multiple profiles produces candidate action`() {
        val content = SubscriptionNotificationPolicy.decide(
            config = subscriptionConfig(lastScheduledStatus = SubscriptionScheduledStatus.UPDATED),
            latestEvent = SubscriptionEvent(
                occurredAtEpochMs = 1234L,
                origin = SubscriptionEventOrigin.SCHEDULED,
                severity = SubscriptionEventSeverity.INFO,
                summary = "Scheduled subscription check staged 2 validated profiles and selected candidate 1/2.",
            ),
            pendingUpdate = SubscriptionPendingUpdate(
                sourceUrlHash = "sha256:source",
                checkedAtEpochMs = 1234L,
                fetchedProfileCount = 2,
                selectedProfileIndex = 0,
                trustStatus = SubscriptionTrustStatus.TLS_ONLY,
                publisherStatus = SubscriptionPublisherStatus.UNSIGNED,
                profiles = listOf(defaultBootstrapProfile(), defaultBootstrapProfile().copy(name = "Alt Candidate")),
            ),
        )

        assertNotNull(content)
        assertEquals("Subscription Candidates Ready", content!!.title)
        assertEquals("Review Candidates", content.actionTitle)
        assertEquals(SubscriptionNotificationDestination.REVIEW_UPDATE, content.route.destination)
    }

    @Test
    fun `manual events do not produce notifications`() {
        val content = SubscriptionNotificationPolicy.decide(
            config = subscriptionConfig(lastScheduledStatus = SubscriptionScheduledStatus.UPDATED),
            latestEvent = SubscriptionEvent(
                occurredAtEpochMs = 1234L,
                origin = SubscriptionEventOrigin.USER,
                severity = SubscriptionEventSeverity.INFO,
                summary = "Manual subscription check staged a validated profile update.",
            ),
            pendingUpdate = SubscriptionPendingUpdate(
                sourceUrlHash = "sha256:source",
                checkedAtEpochMs = 1234L,
                fetchedProfileCount = 1,
                selectedProfileIndex = 0,
                trustStatus = SubscriptionTrustStatus.TLS_ONLY,
                publisherStatus = SubscriptionPublisherStatus.UNSIGNED,
                profiles = listOf(defaultBootstrapProfile()),
            ),
        )

        assertNull(content)
    }

    @Test
    fun `scheduled not modified result does not produce notifications`() {
        val content = SubscriptionNotificationPolicy.decide(
            config = subscriptionConfig(lastScheduledStatus = SubscriptionScheduledStatus.NOT_MODIFIED),
            latestEvent = SubscriptionEvent(
                occurredAtEpochMs = 1234L,
                origin = SubscriptionEventOrigin.SCHEDULED,
                severity = SubscriptionEventSeverity.INFO,
                summary = "Scheduled subscription check found no feed changes.",
            ),
            pendingUpdate = null,
        )

        assertNull(content)
    }

    @Test
    fun `dispatch policy skips a duplicate delivered alert`() {
        val route = SubscriptionNotificationRoute(
            destination = SubscriptionNotificationDestination.REVIEW_UPDATE,
            summary = "Scheduled subscription check staged a validated profile update.",
            occurredAtEpochMs = 1234L,
        )

        val decision = SubscriptionNotificationDispatchPolicy.decide(
            route = route,
            ledger = SubscriptionNotificationLedger(
                lastDeliveredEventAtEpochMs = 1234L,
                lastDeliveredDestination = SubscriptionNotificationDestination.REVIEW_UPDATE,
                lastDeliveredSummary = "Scheduled subscription check staged a validated profile update.",
            ),
        )

        assertEquals(SubscriptionNotificationDispatchDecision.SKIP_DUPLICATE, decision)
    }

    @Test
    fun `dispatch policy republishes a previously suppressed alert`() {
        val route = SubscriptionNotificationRoute(
            destination = SubscriptionNotificationDestination.REVIEW_INBOX,
            summary = "Scheduled subscription check failed: Publisher trust validation failed.",
            occurredAtEpochMs = 1234L,
        )

        val decision = SubscriptionNotificationDispatchPolicy.decide(
            route = route,
            ledger = SubscriptionNotificationLedger(
                lastEvaluationStatus = SubscriptionNotificationDeliveryStatus.SUPPRESSED_DISABLED,
                lastEvaluatedEventAtEpochMs = 1234L,
                lastEvaluatedDestination = SubscriptionNotificationDestination.REVIEW_INBOX,
                lastEvaluatedNotificationSummary = "Scheduled subscription check failed: Publisher trust validation failed.",
            ),
        )

        assertEquals(SubscriptionNotificationDispatchDecision.PUBLISH, decision)
    }

    @Test
    fun `alert policy suppresses scheduled updates when update alerts are disabled`() {
        val result = SubscriptionNotificationAlertPolicy.evaluate(
            config = subscriptionConfig(
                lastScheduledStatus = SubscriptionScheduledStatus.UPDATED,
                scheduledUpdateAlertsEnabled = false,
            ),
            content = SubscriptionNotificationContent(
                title = "Subscription Update Ready",
                text = "Scheduled subscription check staged a validated profile update.",
                actionTitle = "Review Update",
                route = SubscriptionNotificationRoute(
                    destination = SubscriptionNotificationDestination.REVIEW_UPDATE,
                    summary = "Scheduled subscription check staged a validated profile update.",
                    occurredAtEpochMs = 1234L,
                ),
            ),
        )

        assertEquals(false, result.shouldPublish)
        assertEquals("Scheduled update alerts are disabled by policy.", result.summary)
    }

    @Test
    fun `alert policy suppresses scheduled failures below configured threshold`() {
        val result = SubscriptionNotificationAlertPolicy.evaluate(
            config = subscriptionConfig(
                lastScheduledStatus = SubscriptionScheduledStatus.FAILED,
                scheduledFailureAlertThreshold = 2,
                scheduledFailureStreak = 1,
            ),
            content = SubscriptionNotificationContent(
                title = "Subscription Check Failed",
                text = "Scheduled subscription check failed: Publisher trust validation failed.",
                actionTitle = "Open Inbox",
                route = SubscriptionNotificationRoute(
                    destination = SubscriptionNotificationDestination.REVIEW_INBOX,
                    summary = "Scheduled subscription check failed: Publisher trust validation failed.",
                    occurredAtEpochMs = 1234L,
                ),
            ),
        )

        assertEquals(false, result.shouldPublish)
        assertEquals("Holding scheduled failure alerts until streak 2; current streak 1.", result.summary)
    }

    private fun subscriptionConfig(
        lastScheduledStatus: SubscriptionScheduledStatus,
        scheduledUpdateAlertsEnabled: Boolean = true,
        scheduledFailureAlertThreshold: Int = 1,
        scheduledFailureStreak: Int = 0,
    ): SubscriptionConfig = SubscriptionConfig(
        sourceUrl = "https://updates.example.com/subscription.json",
        sourceUrlHash = "sha256:source",
        savedAtEpochMs = 1234L,
        lastScheduledStatus = lastScheduledStatus,
        scheduledUpdateAlertsEnabled = scheduledUpdateAlertsEnabled,
        scheduledFailureAlertThreshold = scheduledFailureAlertThreshold,
        scheduledFailureStreak = scheduledFailureStreak,
    )
}
