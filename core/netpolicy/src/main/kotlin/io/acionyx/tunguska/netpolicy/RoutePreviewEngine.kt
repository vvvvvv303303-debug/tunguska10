package io.acionyx.tunguska.netpolicy

import io.acionyx.tunguska.domain.NetworkProtocol
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.RouteMatch
import io.acionyx.tunguska.domain.RouteRule
import io.acionyx.tunguska.domain.SplitTunnelMode
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

        profile.routing.rules.firstOrNull { rule -> matches(rule, request) }?.let { matchedRule ->
            return RoutePreviewOutcome(
                action = matchedRule.action,
                matchedRuleId = matchedRule.id,
                reason = "Matched explicit routing rule '${matchedRule.id}'.",
            )
        }

        return RoutePreviewOutcome(
            action = profile.routing.defaultAction,
            matchedRuleId = null,
            reason = "No explicit rule matched; using routing default.",
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

        val exact = match.domainExact.isEmpty() || host in match.domainExact.map { it.lowercase() }
        val suffix = match.domainSuffix.isEmpty() || match.domainSuffix.any { suffix ->
            host == suffix.lowercase() || host.endsWith(".${suffix.lowercase()}")
        }
        val keyword = match.domainKeyword.isEmpty() || match.domainKeyword.any { keyword ->
            host.contains(keyword.lowercase())
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

