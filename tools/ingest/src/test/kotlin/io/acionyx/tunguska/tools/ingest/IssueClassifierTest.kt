package io.acionyx.tunguska.tools.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IssueClassifierTest {
    private val classifier = IssueClassifier()

    @Test
    fun `classifies localhost split tunnel reports as critical`() {
        val item = GitHubIssueLike(
            number = 1155,
            title = "Critical vulnerability: unauth local SOCKS5 bypasses split tunneling",
            body = "Apps can scan localhost on 127.0.0.1 and bypass per-app VPN policy.",
            state = "open",
            htmlUrl = "https://example.invalid/issues/1155",
        )

        val classified = classifier.classifyOne(item)

        assertTrue(IssueCategory.LOCALHOST_PROXY in classified)
        assertTrue(IssueCategory.SPLIT_TUNNEL_BYPASS in classified)
        assertEquals(ImpactLevel.CRITICAL, classifier.classify(listOf(item)).single().impact)
    }

    @Test
    fun `renderer emits csv rows`() {
        val csv = InventoryRenderer.toCsv(
            listOf(
                ClassifiedIssue(
                    number = 1,
                    title = "Routing regression",
                    state = "open",
                    htmlUrl = "https://example.invalid/issues/1",
                    isPullRequest = false,
                    categories = setOf(IssueCategory.ROUTING_CORRECTNESS),
                    impact = ImpactLevel.MEDIUM,
                ),
            ),
        )

        assertTrue(csv.contains("Routing regression"))
        assertTrue(csv.contains("ROUTING_CORRECTNESS"))
    }
}
