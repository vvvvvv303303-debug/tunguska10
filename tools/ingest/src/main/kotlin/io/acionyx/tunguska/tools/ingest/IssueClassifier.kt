package io.acionyx.tunguska.tools.ingest

class IssueClassifier {
    fun classify(items: List<GitHubIssueLike>): List<ClassifiedIssue> = items.map { item ->
        val categories = classifyOne(item)
        ClassifiedIssue(
            number = item.number,
            title = item.title,
            state = item.state,
            htmlUrl = item.htmlUrl,
            isPullRequest = item.pullRequest != null,
            categories = categories,
            impact = score(categories),
        )
    }

    fun classifyOne(item: GitHubIssueLike): Set<IssueCategory> {
        val haystack = "${item.title}\n${item.body.orEmpty()}".lowercase()
        return IssueCategory.entries.filterTo(linkedSetOf()) { category ->
            patterns.getValue(category).any { token -> token in haystack }
        }
    }

    private fun score(categories: Set<IssueCategory>): ImpactLevel = when {
        categories.any { it in criticalCategories } -> ImpactLevel.CRITICAL
        categories.any { it in highCategories } -> ImpactLevel.HIGH
        categories.any { it in mediumCategories } -> ImpactLevel.MEDIUM
        else -> ImpactLevel.LOW
    }

    private companion object {
        val criticalCategories = setOf(
            IssueCategory.LOCALHOST_PROXY,
            IssueCategory.SPLIT_TUNNEL_BYPASS,
        )

        val highCategories = setOf(
            IssueCategory.UDP_AUTH_GAP,
            IssueCategory.DEBUG_ENDPOINTS_METRICS_PPROF,
            IssueCategory.TLS_INSECURE_TRUST_ALL,
            IssueCategory.EXPORTED_COMPONENTS_INTENTS,
        )

        val mediumCategories = setOf(
            IssueCategory.ROUTING_CORRECTNESS,
            IssueCategory.DNS_LEAK_OR_OVERRIDE,
            IssueCategory.SUPPLY_CHAIN_BUILD,
        )

        val patterns: Map<IssueCategory, List<String>> = mapOf(
            IssueCategory.LOCALHOST_PROXY to listOf("127.0.0.1", "localhost", "socks5", "mixed inbound", "open proxy"),
            IssueCategory.SPLIT_TUNNEL_BYPASS to listOf("split tunneling", "split tunnel", "per-app", "bypass"),
            IssueCategory.UDP_AUTH_GAP to listOf("udp auth", "udp not authenticated", "udp gap"),
            IssueCategory.EXPORTED_COMPONENTS_INTENTS to listOf("exported", "deep link", "intent injection", "receiver"),
            IssueCategory.DEBUG_ENDPOINTS_METRICS_PPROF to listOf("pprof", "metrics", "debug server", "handlerservice"),
            IssueCategory.TLS_INSECURE_TRUST_ALL to listOf("allowinsecure", "ignore certificate", "trust all"),
            IssueCategory.SUPPLY_CHAIN_BUILD to listOf("workflow", "github actions", "sbom", "provenance", "slsa"),
            IssueCategory.DNS_LEAK_OR_OVERRIDE to listOf("dns leak", "dot", "doh", "system dns"),
            IssueCategory.ROUTING_CORRECTNESS to listOf("route", "routing", "bypasslan", "longest-prefix", "allow lan"),
        )
    }
}

