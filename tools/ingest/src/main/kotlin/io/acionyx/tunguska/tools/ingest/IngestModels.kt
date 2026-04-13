package io.acionyx.tunguska.tools.ingest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubIssueLike(
    val number: Int,
    val title: String,
    val body: String? = null,
    val state: String,
    @SerialName("html_url")
    val htmlUrl: String,
    @SerialName("pull_request")
    val pullRequest: PullRequestMarker? = null,
)

@Serializable
data class PullRequestMarker(
    val url: String? = null,
)

enum class IssueCategory {
    LOCALHOST_PROXY,
    SPLIT_TUNNEL_BYPASS,
    UDP_AUTH_GAP,
    EXPORTED_COMPONENTS_INTENTS,
    DEBUG_ENDPOINTS_METRICS_PPROF,
    TLS_INSECURE_TRUST_ALL,
    SUPPLY_CHAIN_BUILD,
    DNS_LEAK_OR_OVERRIDE,
    ROUTING_CORRECTNESS,
}

enum class ImpactLevel {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
}

data class ClassifiedIssue(
    val number: Int,
    val title: String,
    val state: String,
    val htmlUrl: String,
    val isPullRequest: Boolean,
    val categories: Set<IssueCategory>,
    val impact: ImpactLevel,
)

