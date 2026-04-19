package io.acionyx.tunguska.netpolicy

import io.acionyx.tunguska.domain.NetworkProtocol
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.EffectiveRoutingPolicyResolver
import io.acionyx.tunguska.domain.RouteMatch
import io.acionyx.tunguska.domain.RouteRule
import io.acionyx.tunguska.domain.RegionalBypassPresetId
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.domain.normalizeDomainForMatching
import java.net.InetAddress

data class RoutePreviewRequest(
    val packageName: String? = null,
    val destinationHost: String? = null,
    val destinationIp: String? = null,
    val destinationPort: Int? = null,
    val protocol: NetworkProtocol? = null,
)

data class RoutePreviewOutcome(
    val action: RouteAction,
    val matchedRuleId: String?,
    val reason: String,
    val runtimeDatasetHint: String? = null,
)

class RoutePreviewEngine {
    fun evaluate(profile: ProfileIr, request: RoutePreviewRequest): RoutePreviewOutcome {
        if (isLoopback(request.destinationHost, request.destinationIp)) {
            return RoutePreviewOutcome(
                action = RouteAction.DIRECT,
                matchedRuleId = "__loopback__",
                reason = "Loopback traffic always remains local.",
            )
        }

        splitTunnelOutcome(profile, request.packageName)?.let { return it }

        val effectiveRouting = EffectiveRoutingPolicyResolver.resolve(profile)
        effectiveRouting.rules.firstOrNull { rule -> matches(rule, request) }?.let { matchedRule ->
            return RoutePreviewOutcome(
                action = matchedRule.action,
                matchedRuleId = matchedRule.id,
                reason = when (matchedRule.id) {
                    "__regional_bypass_russia__" -> "Matched regional bypass preset 'Russia'."
                    "__regional_bypass_custom_direct__" -> "Matched a custom direct domain in Regional Bypass."
                    else -> "Matched explicit routing rule '${matchedRule.id}'."
                },
            )
        }

        val runtimeDatasetHint = runtimeDatasetHint(profile, request)
        return RoutePreviewOutcome(
            action = profile.routing.defaultAction,
            matchedRuleId = null,
            reason = "No explicit rule matched; using routing default.",
            runtimeDatasetHint = runtimeDatasetHint,
        )
    }

    private fun splitTunnelOutcome(profile: ProfileIr, packageName: String?): RoutePreviewOutcome? = when (
        val splitTunnel = profile.vpn.splitTunnel
    ) {
        SplitTunnelMode.FullTunnel -> null
        is SplitTunnelMode.Allowlist -> {
            if (packageName == null || packageName !in splitTunnel.packageNames) {
                RoutePreviewOutcome(
                    action = RouteAction.DIRECT,
                    matchedRuleId = "__split_tunnel_allowlist__",
                    reason = "Package is outside the VPN allowlist.",
                )
            } else {
                null
            }
        }

        is SplitTunnelMode.Denylist -> {
            if (packageName != null && packageName in splitTunnel.packageNames) {
                RoutePreviewOutcome(
                    action = RouteAction.DIRECT,
                    matchedRuleId = "__split_tunnel_denylist__",
                    reason = "Package is excluded by the VPN denylist.",
                )
            } else {
                null
            }
        }
    }

    private fun matches(rule: RouteRule, request: RoutePreviewRequest): Boolean {
        val match = rule.match
        return matchesDomains(match, request) &&
            matchesPackage(match, request.packageName) &&
            matchesPort(match, request.destinationPort) &&
            matchesProtocol(match, request.protocol) &&
            matchesCidrs(match, request.destinationIp)
    }

    private fun matchesDomains(match: RouteMatch, request: RoutePreviewRequest): Boolean {
        val host = request.destinationHost?.lowercase() ?: return match.domainExact.isEmpty() &&
            match.domainSuffix.isEmpty() &&
            match.domainKeyword.isEmpty()
        val normalizedHost = normalizeDomainForMatching(host) ?: host

        val exact = match.domainExact.isEmpty() || match.domainExact.any { candidate ->
            val normalizedCandidate = normalizeDomainForMatching(candidate) ?: candidate.lowercase()
            normalizedHost == normalizedCandidate
        }
        val suffix = match.domainSuffix.isEmpty() || match.domainSuffix.any { suffix ->
            val normalizedSuffix = normalizeDomainForMatching(suffix) ?: suffix.lowercase()
            normalizedHost == normalizedSuffix || normalizedHost.endsWith(".$normalizedSuffix")
        }
        val keyword = match.domainKeyword.isEmpty() || match.domainKeyword.any { keyword ->
            normalizedHost.contains((normalizeDomainForMatching(keyword) ?: keyword.lowercase()))
        }
        return exact && suffix && keyword
    }

    private fun matchesPackage(match: RouteMatch, packageName: String?): Boolean {
        if (match.packageNames.isEmpty()) return true
        return packageName != null && packageName in match.packageNames
    }

    private fun matchesPort(match: RouteMatch, destinationPort: Int?): Boolean {
        if (match.ports.isEmpty()) return true
        return destinationPort != null && destinationPort in match.ports
    }

    private fun matchesProtocol(match: RouteMatch, protocol: NetworkProtocol?): Boolean {
        if (match.protocols.isEmpty()) return true
        return protocol != null && protocol in match.protocols
    }

    private fun matchesCidrs(match: RouteMatch, destinationIp: String?): Boolean {
        if (match.ipCidrs.isEmpty()) return true
        return destinationIp != null && match.ipCidrs.any { cidr -> destinationIp.inCidr(cidr) }
    }

    private fun isLoopback(host: String?, ip: String?): Boolean {
        if (host != null) {
            val normalized = host.lowercase()
            if (normalized == "localhost" || normalized.endsWith(".localhost")) {
                return true
            }
        }

        return ip?.let { candidate ->
            runCatching { InetAddress.getByName(candidate).isLoopbackAddress }.getOrDefault(false)
        } ?: false
    }

    private fun runtimeDatasetHint(profile: ProfileIr, request: RoutePreviewRequest): String? {
        val regionalBypass = profile.routing.regionalBypass
        if (!regionalBypass.isPresetEnabled(RegionalBypassPresetId.RUSSIA)) {
            return null
        }
        if (!request.destinationIp.isNullOrBlank()) {
            return "Regional bypass can still apply at runtime via geoip:ru classification for the destination IP."
        }
        if (!request.destinationHost.isNullOrBlank()) {
            val normalizedHost = normalizeDomainForMatching(request.destinationHost)
            val suffixMatched = normalizedHost != null && listOf("ru", "su", "xn--p1ai").any { suffix ->
                normalizedHost == suffix || normalizedHost.endsWith(".$suffix")
            }
            if (!suffixMatched) {
                return "Regional bypass can still apply at runtime if the resolved destination IP is classified as geoip:ru."
            }
        }
        return null
    }
}

private fun String.inCidr(cidr: String): Boolean {
    val parts = cidr.split("/")
    if (parts.size != 2) return false

    val network = runCatching { InetAddress.getByName(parts[0]).address }.getOrNull() ?: return false
    val target = runCatching { InetAddress.getByName(this).address }.getOrNull() ?: return false
    val prefix = parts[1].toIntOrNull() ?: return false

    if (network.size != target.size) return false
    if (prefix !in 0..(network.size * 8)) return false

    var remainingBits = prefix
    for (index in network.indices) {
        if (remainingBits <= 0) return true
        val bitsToCompare = minOf(remainingBits, 8)
        val mask = (0xFF shl (8 - bitsToCompare)) and 0xFF
        if ((network[index].toInt() and mask) != (target[index].toInt() and mask)) {
            return false
        }
        remainingBits -= bitsToCompare
    }
    return true
}
