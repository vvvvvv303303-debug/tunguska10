package io.acionyx.tunguska.app

import io.acionyx.tunguska.crypto.SoftwareAesGcmCipherBox
import io.acionyx.tunguska.domain.CanonicalJson
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureSubscriptionRepositoryTest {
    @Test
    fun `save and load config encrypts subscription url and pin`() {
        val repository = buildRepository()
        val sourceUrl = "https://updates.example.com/subscription.json"

        val saved = repository.saveSourceUrl(
            sourceUrl = sourceUrl,
            expectedSpkiPins = listOf(TRUSTED_PIN),
        )
        val loaded = repository.loadConfig()
        val rawFile = String(Files.readAllBytes(Paths.get(repository.storagePath)))

        assertNotNull(loaded)
        assertEquals(saved.sourceUrl, loaded!!.sourceUrl)
        assertEquals(saved.sourceUrlHash, loaded.sourceUrlHash)
        assertEquals(listOf(TRUSTED_PIN), loaded.expectedSpkiPins)
        assertTrue(Files.exists(Paths.get(repository.storagePath)))
        assertTrue(!rawFile.contains(sourceUrl))
        assertTrue(!rawFile.contains(TRUSTED_PIN))
    }

    @Test
    fun `fetch update parses validated profile computes diff and records observed tls identity`() {
        val activeProfile = defaultBootstrapProfile()
        val fetchedProfile = activeProfile.copy(
            outbound = activeProfile.outbound.copy(
                address = "updates.example.net",
                port = 8443,
            ),
        )
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 200,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = fetchedProfile.canonicalJson(),
                    etag = "\"abc\"",
                    lastModified = "Thu, 10 Apr 2026 10:00:00 GMT",
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl("https://updates.example.com/subscription.json")

        val result = repository.fetchUpdate(
            config = config,
            activeProfile = activeProfile,
        )

        assertEquals(SubscriptionUpdateStatus.UPDATED, result.status)
        assertEquals(SubscriptionTrustStatus.TLS_ONLY, result.trustStatus)
        assertEquals(1, result.fetchedProfileCount)
        assertEquals(fetchedProfile.canonicalHash(), result.primaryProfile?.canonicalHash())
        assertTrue(result.diff?.endpointChanged == true)
        assertTrue(result.updatedConfig.lastCheckedAtEpochMs != null)
        assertEquals("\"abc\"", result.updatedConfig.lastEtag)
        assertEquals(listOf(OBSERVED_PIN), result.updatedConfig.lastObservedSpkiPins)
    }

    @Test
    fun `fetch update preserves every validated profile candidate`() {
        val activeProfile = defaultBootstrapProfile()
        val firstProfile = activeProfile.copy(name = "Candidate A")
        val secondProfile = activeProfile.copy(
            name = "Candidate B",
            outbound = activeProfile.outbound.copy(address = "candidate-b.example.net"),
        )
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 200,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = """
                        {
                          "title": "Primary Feed",
                          "profiles": [
                            ${firstProfile.canonicalJson()},
                            ${secondProfile.canonicalJson()}
                          ]
                        }
                    """.trimIndent(),
                    etag = null,
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl("https://updates.example.com/subscription.json")

        val result = repository.fetchUpdate(
            config = config,
            activeProfile = activeProfile,
        )

        assertEquals(2, result.fetchedProfileCount)
        assertEquals(2, result.fetchedProfiles.size)
        assertEquals(firstProfile.canonicalHash(), result.fetchedProfiles[0].canonicalHash())
        assertEquals(secondProfile.canonicalHash(), result.fetchedProfiles[1].canonicalHash())
        assertEquals(firstProfile.canonicalHash(), result.primaryProfile?.canonicalHash())
    }

    @Test
    fun `manual fetch appends an inbox event`() {
        val activeProfile = defaultBootstrapProfile()
        val fetchedProfile = activeProfile.copy(
            outbound = activeProfile.outbound.copy(address = "inbox.example.net"),
        )
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 200,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = fetchedProfile.canonicalJson(),
                    etag = null,
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl("https://updates.example.com/subscription.json")

        repository.fetchUpdate(
            config = config,
            activeProfile = activeProfile,
        )
        val eventLog = repository.loadEventLog()

        assertEquals(1, eventLog.entries.size)
        assertEquals(SubscriptionEventOrigin.USER, eventLog.entries.first().origin)
        assertEquals(SubscriptionEventSeverity.INFO, eventLog.entries.first().severity)
        assertTrue(eventLog.entries.first().summary.contains("Manual subscription check staged"))
    }

    @Test
    fun `fetch update persists a pending update artifact`() {
        val activeProfile = defaultBootstrapProfile()
        val fetchedProfile = activeProfile.copy(
            outbound = activeProfile.outbound.copy(address = "pending.example.net"),
        )
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 200,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = fetchedProfile.canonicalJson(),
                    etag = null,
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl("https://updates.example.com/subscription.json")

        repository.fetchUpdate(
            config = config,
            activeProfile = activeProfile,
        )
        val pending = repository.loadPendingUpdate()

        assertNotNull(pending)
        assertEquals(config.sourceUrlHash, pending!!.sourceUrlHash)
        assertEquals(1, pending.fetchedProfileCount)
        assertEquals(1, pending.profiles.size)
        assertEquals(fetchedProfile.canonicalHash(), pending.profiles.single().canonicalHash())
    }

    @Test
    fun `fetch update auto-selects the previously applied candidate by hash`() {
        val activeProfile = defaultBootstrapProfile()
        val firstProfile = activeProfile.copy(name = "Candidate A")
        val preferredProfile = activeProfile.copy(
            name = "Candidate B",
            outbound = activeProfile.outbound.copy(address = "candidate-b.example.net"),
        )
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 200,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = """
                        {
                          "title": "Primary Feed",
                          "profiles": [
                            ${firstProfile.canonicalJson()},
                            ${preferredProfile.canonicalJson()}
                          ]
                        }
                    """.trimIndent(),
                    etag = null,
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl("https://updates.example.com/subscription.json")
        val appliedConfig = repository.markApplied(config, preferredProfile)

        val result = repository.fetchUpdate(
            config = appliedConfig,
            activeProfile = activeProfile,
        )

        assertEquals(1, result.selectedProfileIndex)
        assertTrue(result.selectionSummary!!.contains("canonical hash"))
        assertEquals(preferredProfile.canonicalHash(), result.diff?.fetchedProfileHash)
    }

    @Test
    fun `fetch update auto-selects the previously applied candidate by name after feed rotation`() {
        val activeProfile = defaultBootstrapProfile()
        val appliedProfile = activeProfile.copy(
            name = "Stable Candidate",
            outbound = activeProfile.outbound.copy(address = "stable-old.example.net"),
        )
        val rotatedProfile = appliedProfile.copy(
            outbound = appliedProfile.outbound.copy(address = "stable-new.example.net"),
        )
        val fallbackProfile = activeProfile.copy(name = "Fallback Candidate")
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 200,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = """
                        {
                          "title": "Rotated Feed",
                          "profiles": [
                            ${fallbackProfile.canonicalJson()},
                            ${rotatedProfile.canonicalJson()}
                          ]
                        }
                    """.trimIndent(),
                    etag = null,
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl("https://updates.example.com/subscription.json")
        val appliedConfig = repository.markApplied(config, appliedProfile)

        val result = repository.fetchUpdate(
            config = appliedConfig,
            activeProfile = activeProfile,
        )

        assertEquals(1, result.selectedProfileIndex)
        assertTrue(result.selectionSummary!!.contains("profile name"))
        assertEquals(rotatedProfile.canonicalHash(), result.diff?.fetchedProfileHash)
    }

    @Test
    fun `fetch update handles not modified responses and still evaluates tls trust`() {
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 304,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = null,
                    etag = "\"etag\"",
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl("https://updates.example.com/subscription.json")

        val result = repository.fetchUpdate(
            config = config,
            activeProfile = defaultBootstrapProfile(),
        )

        assertEquals(SubscriptionUpdateStatus.NOT_MODIFIED, result.status)
        assertEquals(SubscriptionTrustStatus.TLS_ONLY, result.trustStatus)
        assertNull(result.primaryProfile)
        assertNull(result.diff)
        assertTrue(result.updatedConfig.lastCheckedAtEpochMs != null)
        assertEquals(listOf(OBSERVED_PIN), result.updatedConfig.lastObservedSpkiPins)
    }

    @Test
    fun `mark applied persists last applied profile metadata`() {
        val repository = buildRepository()
        val config = repository.saveSourceUrl("https://updates.example.com/subscription.json")
        val appliedProfile = defaultBootstrapProfile()

        val updated = repository.markApplied(
            config = config,
            appliedProfile = appliedProfile,
        )

        assertEquals(appliedProfile.canonicalHash(), updated.lastAppliedProfileHash)
        assertEquals(appliedProfile.name, updated.lastAppliedProfileName)
        assertTrue(updated.lastAppliedAtEpochMs != null)
    }

    @Test
    fun `mark applied clears the staged pending update artifact`() {
        val activeProfile = defaultBootstrapProfile()
        val fetchedProfile = activeProfile.copy(
            outbound = activeProfile.outbound.copy(address = "apply.example.net"),
        )
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 200,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = fetchedProfile.canonicalJson(),
                    etag = null,
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl("https://updates.example.com/subscription.json")
        val update = repository.fetchUpdate(
            config = config,
            activeProfile = activeProfile,
        )

        repository.markApplied(update.updatedConfig, fetchedProfile)

        assertNull(repository.loadPendingUpdate())
    }

    @Test
    fun `fetch update rejects mismatched pinned identity and persists observed pins`() {
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 304,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = null,
                    etag = null,
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl(
            sourceUrl = "https://updates.example.com/subscription.json",
            expectedSpkiPins = listOf(TRUSTED_PIN),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            repository.fetchUpdate(
                config = config,
                activeProfile = defaultBootstrapProfile(),
            )
        }

        val persisted = repository.loadConfig()

        assertTrue(error.message!!.contains("did not match"))
        assertNotNull(persisted)
        assertEquals(SubscriptionTrustStatus.PIN_MISMATCH, persisted!!.lastTrustStatus)
        assertEquals(listOf(OBSERVED_PIN), persisted.lastObservedSpkiPins)
    }

    @Test
    fun `trust observed identity promotes captured tls pin`() {
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 304,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = null,
                    etag = null,
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl("https://updates.example.com/subscription.json")
        repository.fetchUpdate(
            config = config,
            activeProfile = defaultBootstrapProfile(),
        )

        val updated = repository.trustObservedIdentity(repository.loadConfig()!!)

        assertEquals(listOf(OBSERVED_PIN), updated.expectedSpkiPins)
        assertEquals(SubscriptionTrustStatus.PINNED, updated.lastTrustStatus)
    }

    @Test
    fun `clear pinned identity falls back to tls only trust`() {
        val repository = buildRepository()
        val config = repository.saveSourceUrl(
            sourceUrl = "https://updates.example.com/subscription.json",
            expectedSpkiPins = listOf(TRUSTED_PIN),
        )

        val updated = repository.clearPinnedIdentity(config)

        assertTrue(updated.expectedSpkiPins.isEmpty())
        assertEquals(SubscriptionTrustStatus.NONE, updated.lastTrustStatus)
    }

    @Test
    fun `fetch update trusts a signed feed when publisher pin matches`() {
        val keyPair = generatePublisherKeyPair()
        val signedProfile = defaultBootstrapProfile().copy(
            outbound = defaultBootstrapProfile().outbound.copy(address = "signed.example.net"),
        )
        val publisherPin = SubscriptionPinPolicy.spkiSha256Pin(keyPair.public.encoded)
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 200,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = signedDocument(signedProfile, keyPair),
                    etag = "\"signed\"",
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl(
            sourceUrl = "https://updates.example.com/subscription.json",
            expectedPublisherPins = listOf(publisherPin),
        )

        val result = repository.fetchUpdate(
            config = config,
            activeProfile = defaultBootstrapProfile(),
        )

        assertEquals(SubscriptionPublisherStatus.TRUSTED, result.publisherStatus)
        assertEquals(publisherPin, result.observedPublisherPin)
        assertEquals(publisherPin, result.updatedConfig.lastObservedPublisherPin)
    }

    @Test
    fun `fetch update rejects unsigned payloads when a publisher pin is configured`() {
        val keyPair = generatePublisherKeyPair()
        val publisherPin = SubscriptionPinPolicy.spkiSha256Pin(keyPair.public.encoded)
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 200,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = defaultBootstrapProfile().canonicalJson(),
                    etag = null,
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl(
            sourceUrl = "https://updates.example.com/subscription.json",
            expectedPublisherPins = listOf(publisherPin),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            repository.fetchUpdate(
                config = config,
                activeProfile = defaultBootstrapProfile(),
            )
        }

        val persisted = repository.loadConfig()

        assertTrue(error.message!!.contains("unsigned"))
        assertEquals(SubscriptionPublisherStatus.REQUIRED_MISSING, persisted!!.lastPublisherStatus)
    }

    @Test
    fun `trust observed publisher identity promotes a signed feed signer`() {
        val keyPair = generatePublisherKeyPair()
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 200,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = signedDocument(defaultBootstrapProfile(), keyPair),
                    etag = null,
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl("https://updates.example.com/subscription.json")
        repository.fetchUpdate(
            config = config,
            activeProfile = defaultBootstrapProfile(),
        )

        val updated = repository.trustObservedPublisherIdentity(repository.loadConfig()!!)
        val publisherPin = SubscriptionPinPolicy.spkiSha256Pin(keyPair.public.encoded)

        assertEquals(listOf(publisherPin), updated.expectedPublisherPins)
        assertEquals(SubscriptionPublisherStatus.TRUSTED, updated.lastPublisherStatus)
    }

    @Test
    fun `trust observed publisher identity appends a new signer during rollover`() {
        val oldPublisherPin = TRUSTED_PIN
        val newPublisherKeyPair = generatePublisherKeyPair()
        val newPublisherPin = SubscriptionPinPolicy.spkiSha256Pin(newPublisherKeyPair.public.encoded)
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 200,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = signedDocument(defaultBootstrapProfile(), newPublisherKeyPair),
                    etag = null,
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl(
            sourceUrl = "https://updates.example.com/subscription.json",
            expectedPublisherPins = listOf(oldPublisherPin),
        )

        assertThrows(IllegalStateException::class.java) {
            repository.fetchUpdate(
                config = config,
                activeProfile = defaultBootstrapProfile(),
            )
        }

        val updated = repository.trustObservedPublisherIdentity(repository.loadConfig()!!)

        assertEquals(2, updated.expectedPublisherPins.size)
        assertTrue(updated.expectedPublisherPins.contains(oldPublisherPin))
        assertTrue(updated.expectedPublisherPins.contains(newPublisherPin))
        assertEquals(SubscriptionPublisherStatus.TRUSTED, updated.lastPublisherStatus)
        assertTrue(updated.lastPublisherSummary!!.contains("rollover"))
    }

    @Test
    fun `update schedule persists background policy metadata`() {
        val repository = buildRepository()
        val config = repository.saveSourceUrl("https://updates.example.com/subscription.json")

        val updated = repository.updateSchedule(
            config = config,
            scheduleMode = SubscriptionScheduleMode.EVERY_6_HOURS,
        )
        val persisted = repository.loadConfig()

        assertEquals(SubscriptionScheduleMode.EVERY_6_HOURS, updated.scheduleMode)
        assertEquals(SubscriptionScheduledStatus.SCHEDULED, updated.lastScheduledStatus)
        assertTrue(updated.lastScheduledSummary!!.contains("6 hours"))
        assertEquals(SubscriptionScheduleMode.EVERY_6_HOURS, persisted!!.scheduleMode)
    }

    @Test
    fun `scheduled fetch records scheduled outcome metadata`() {
        val activeProfile = defaultBootstrapProfile()
        val fetchedProfile = activeProfile.copy(
            outbound = activeProfile.outbound.copy(address = "scheduled.example.net"),
        )
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 200,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = fetchedProfile.canonicalJson(),
                    etag = null,
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.updateSchedule(
            config = repository.saveSourceUrl("https://updates.example.com/subscription.json"),
            scheduleMode = SubscriptionScheduleMode.EVERY_24_HOURS,
        )

        val result = repository.fetchUpdate(
            config = repository.markScheduledRunStarted(config),
            activeProfile = activeProfile,
            trigger = SubscriptionUpdateTrigger.SCHEDULED,
        )

        assertEquals(SubscriptionScheduledStatus.UPDATED, result.updatedConfig.lastScheduledStatus)
        assertTrue(result.updatedConfig.lastScheduledSummary!!.contains("Scheduled subscription check"))
        assertTrue(result.updatedConfig.lastScheduledRunAtEpochMs != null)
    }

    @Test
    fun `scheduled failure streak increments and resets after a successful scheduled check`() {
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 304,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = null,
                    etag = "\"etag\"",
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val initialConfig = repository.updateSchedule(
            config = repository.saveSourceUrl("https://updates.example.com/subscription.json"),
            scheduleMode = SubscriptionScheduleMode.EVERY_24_HOURS,
        )
        val failedConfig = repository.markScheduledRunFailure(
            config = initialConfig,
            summary = "network timeout",
        )

        assertEquals(1, failedConfig.scheduledFailureStreak)

        val result = repository.fetchUpdate(
            config = repository.markScheduledRunStarted(failedConfig),
            activeProfile = defaultBootstrapProfile(),
            trigger = SubscriptionUpdateTrigger.SCHEDULED,
        )

        assertEquals(0, result.updatedConfig.scheduledFailureStreak)
    }

    @Test
    fun `update notification policy persists alert settings`() {
        val repository = buildRepository()
        val config = repository.saveSourceUrl("https://updates.example.com/subscription.json")

        val updated = repository.updateNotificationPolicy(
            config = config,
            scheduledUpdateAlertsEnabled = false,
            scheduledFailureAlertThreshold = 2,
        )
        val persisted = repository.loadConfig()
        val eventLog = repository.loadEventLog()

        assertEquals(false, updated.scheduledUpdateAlertsEnabled)
        assertEquals(2, updated.scheduledFailureAlertThreshold)
        assertEquals(false, persisted!!.scheduledUpdateAlertsEnabled)
        assertEquals(2, persisted.scheduledFailureAlertThreshold)
        assertTrue(eventLog.entries.first().summary.contains("Updated subscription alert policy"))
    }

    @Test
    fun `scheduled failure appends a redacted inbox event`() {
        val repository = buildRepository()
        val config = repository.updateSchedule(
            config = repository.saveSourceUrl("https://updates.example.com/subscription.json"),
            scheduleMode = SubscriptionScheduleMode.EVERY_24_HOURS,
        )

        repository.markScheduledRunFailure(
            config = config,
            summary = "Dial to https://updates.example.com/subscription.json failed for 11111111-1111-1111-1111-111111111111.",
        )
        val eventLog = repository.loadEventLog()

        assertEquals(2, eventLog.entries.size)
        assertEquals(SubscriptionEventOrigin.SCHEDULED, eventLog.entries.first().origin)
        assertEquals(SubscriptionEventSeverity.ERROR, eventLog.entries.first().severity)
        assertTrue(eventLog.entries.first().summary.contains("Scheduled subscription check failed"))
        assertTrue(!eventLog.entries.first().summary.contains("https://updates.example.com/subscription.json"))
        assertTrue(!eventLog.entries.first().summary.contains("11111111-1111-1111-1111-111111111111"))
    }

    @Test
    fun `clear event log removes stored inbox entries`() {
        val repository = buildRepository()

        repository.recordEvent(
            origin = SubscriptionEventOrigin.SYSTEM,
            severity = SubscriptionEventSeverity.INFO,
            summary = "Background subscription checks are disabled.",
        )
        assertEquals(1, repository.loadEventLog().entries.size)

        assertTrue(repository.clearEventLog())
        assertTrue(repository.loadEventLog().entries.isEmpty())
    }

    @Test
    fun `notification ledger persists delivered duplicate and opened alert state`() {
        val repository = buildRepository()
        val route = SubscriptionNotificationRoute(
            destination = SubscriptionNotificationDestination.REVIEW_UPDATE,
            summary = "Scheduled subscription check staged a validated profile update.",
            occurredAtEpochMs = 1234L,
        )

        repository.markNotificationDelivered(route)
        repository.markNotificationSkippedDuplicate(route)
        repository.markNotificationOpened(route)
        val ledger = repository.loadNotificationLedger()

        assertEquals(SubscriptionNotificationDeliveryStatus.SKIPPED_DUPLICATE, ledger.lastEvaluationStatus)
        assertEquals(route.occurredAtEpochMs, ledger.lastDeliveredEventAtEpochMs)
        assertEquals(route.destination, ledger.lastDeliveredDestination)
        assertEquals(route.summary, ledger.lastDeliveredSummary)
        assertEquals(route.occurredAtEpochMs, ledger.lastOpenedEventAtEpochMs)
        assertEquals(route.destination, ledger.lastOpenedDestination)
    }

    @Test
    fun `resetting the source url clears the notification ledger`() {
        val repository = buildRepository()
        val originalConfig = repository.saveSourceUrl("https://updates.example.com/subscription.json")
        val updatedPolicyConfig = repository.updateNotificationPolicy(
            config = originalConfig,
            scheduledUpdateAlertsEnabled = false,
            scheduledFailureAlertThreshold = 2,
        )
        repository.markNotificationDelivered(
            SubscriptionNotificationRoute(
                destination = SubscriptionNotificationDestination.REVIEW_UPDATE,
                summary = "Scheduled subscription check staged a validated profile update.",
                occurredAtEpochMs = updatedPolicyConfig.savedAtEpochMs,
            ),
        )

        val rotatedConfig = repository.saveSourceUrl("https://updates.example.com/rotated-subscription.json")

        assertEquals(SubscriptionNotificationDeliveryStatus.NONE, repository.loadNotificationLedger().lastEvaluationStatus)
        assertNull(repository.loadNotificationLedger().lastDeliveredEventAtEpochMs)
        assertEquals(false, rotatedConfig.scheduledUpdateAlertsEnabled)
        assertEquals(2, rotatedConfig.scheduledFailureAlertThreshold)
    }

    @Test
    fun `fetch update rejects an invalid signed feed`() {
        val keyPair = generatePublisherKeyPair()
        val otherKeyPair = generatePublisherKeyPair()
        val repository = buildRepository(
            transport = SubscriptionTransport {
                SubscriptionFetchResponse(
                    statusCode = 200,
                    resolvedUrl = "https://updates.example.com/subscription.json",
                    body = signedDocument(defaultBootstrapProfile(), keyPair, signingKeyPair = otherKeyPair),
                    etag = null,
                    lastModified = null,
                    observedSpkiPins = listOf(OBSERVED_PIN),
                )
            },
        )
        val config = repository.saveSourceUrl("https://updates.example.com/subscription.json")

        val error = assertThrows(IllegalStateException::class.java) {
            repository.fetchUpdate(
                config = config,
                activeProfile = defaultBootstrapProfile(),
            )
        }

        val persisted = repository.loadConfig()

        assertTrue(error.message!!.contains("verification failed"))
        assertEquals(SubscriptionPublisherStatus.INVALID_SIGNATURE, persisted!!.lastPublisherStatus)
    }

    @Test
    fun `url policy rejects cleartext and localhost sources`() {
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionUrlPolicy.validate("http://updates.example.com/subscription.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionUrlPolicy.validate("https://localhost/subscription.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionUrlPolicy.validate("https://127.0.0.1/subscription.json")
        }
    }

    @Test
    fun `payload parser accepts wrapped profile arrays`() {
        val profile = defaultBootstrapProfile()
        val wrapped = """
            {
              "title": "Primary Feed",
              "profiles": [
                ${profile.canonicalJson()}
              ]
            }
        """.trimIndent()

        val parsed = SubscriptionPayloadParser.parse(wrapped)

        assertEquals("Primary Feed", parsed.title)
        assertEquals(1, parsed.profiles.size)
        assertEquals(profile.canonicalHash(), parsed.profiles.first().canonicalHash())
    }

    private fun buildRepository(
        transport: SubscriptionTransport = SubscriptionTransport {
            error("Network transport should be stubbed in unit tests.")
        },
    ): SecureSubscriptionRepository {
        val root = Files.createTempDirectory("tunguska-subscriptions")
        return SecureSubscriptionRepository(
            storePath = root.resolve("default-source.json.enc"),
            pendingUpdatePath = root.resolve("pending-update.json.enc"),
            eventLogPath = root.resolve("event-log.json.enc"),
            notificationLedgerPath = root.resolve("notification-ledger.json.enc"),
            cipherBox = SoftwareAesGcmCipherBox(SoftwareAesGcmCipherBox.generateKey()),
            transport = transport,
            clock = { 1234L },
        )
    }

    private fun generatePublisherKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        return generator.generateKeyPair()
    }

    private fun signedDocument(
        profile: io.acionyx.tunguska.domain.ProfileIr,
        publicKeyPair: KeyPair,
        signingKeyPair: KeyPair = publicKeyPair,
    ): String {
        val payloadJson = CanonicalJson.instance.encodeToString(
            SignedSubscriptionPayloadFixture(
                title = "Signed Feed",
                profiles = listOf(profile),
            ),
        )
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(signingKeyPair.private)
        signer.update(payloadJson.toByteArray())
        val signatureBase64 = Base64.getEncoder().encodeToString(signer.sign())
        val publicKeyPem = """
            -----BEGIN PUBLIC KEY-----
            ${Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(publicKeyPair.public.encoded)}
            -----END PUBLIC KEY-----
        """.trimIndent()
        return """
            {
              "title": "Signed Feed",
              "profiles": [
                ${profile.canonicalJson()}
              ],
              "signature": {
                "algorithm": "SHA256withECDSA",
                "publicKeyPem": ${CanonicalJson.instance.encodeToString(publicKeyPem)},
                "signatureBase64": "${signatureBase64}"
              }
            }
        """.trimIndent()
    }

    @kotlinx.serialization.Serializable
    private data class SignedSubscriptionPayloadFixture(
        val title: String? = null,
        val profiles: List<io.acionyx.tunguska.domain.ProfileIr> = emptyList(),
    )

    companion object {
        private val TRUSTED_PIN: String = SubscriptionPinPolicy.spkiSha256Pin("trusted-spki".toByteArray())
        private val OBSERVED_PIN: String = SubscriptionPinPolicy.spkiSha256Pin("observed-spki".toByteArray())
    }
}
