package io.acionyx.tunguska.domain

import java.net.IDN
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val REGIONAL_BYPASS_CUSTOM_DIRECT_RULE_ID: String = "__regional_bypass_custom_direct__"

@Serializable
data class RegionalBypassSettings(
    val enabledPresets: List<RegionalBypassPresetId> = emptyList(),
    val customDirectDomains: List<String> = emptyList(),
) {
    fun validate(): List<ValidationIssue> = buildList {
        val duplicatePresets = enabledPresets.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        duplicatePresets.forEach { preset ->
            add(ValidationIssue("routing.regionalBypass.enabledPresets", "Duplicate preset '$preset'."))
        }

        customDirectDomains.forEachIndexed { index, domain ->
            if (domain.isBlank()) {
                add(
                    ValidationIssue(
                        "routing.regionalBypass.customDirectDomains[$index]",
                        "Custom direct domains must not be blank.",
                    ),
                )
                return@forEachIndexed
            }

            runCatching { normalizeDomainForRouting(domain) }
                .onFailure { error ->
                    add(
                        ValidationIssue(
                            "routing.regionalBypass.customDirectDomains[$index]",
                            error.message ?: "Invalid direct domain.",
                        ),
                    )
                }
        }

        val duplicateDomains = customDirectDomains
            .mapNotNull { domain -> runCatching { normalizeDomainForRouting(domain) }.getOrNull() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        duplicateDomains.forEach { domain ->
            add(
                ValidationIssue(
                    "routing.regionalBypass.customDirectDomains",
                    "Duplicate direct domain '$domain'.",
                ),
            )
        }
    }

    fun isEmpty(): Boolean = enabledPresets.isEmpty() && customDirectDomains.isEmpty()

    fun isPresetEnabled(presetId: RegionalBypassPresetId): Boolean = presetId in enabledPresets

    fun normalizedCustomDirectDomains(): List<String> = customDirectDomains
        .map(::normalizeDomainForRouting)
        .distinct()
}

@Serializable
enum class RegionalBypassPresetId {
    @SerialName("russia")
    RUSSIA,
}

data class EffectiveRoutingPolicy(
    val rules: List<RouteRule>,
    val generatedRegionalRuleCount: Int,
    val enabledRegionalPresets: List<RegionalBypassPresetId>,
    val normalizedCustomDirectDomains: List<String>,
)

object EffectiveRoutingPolicyResolver {
    fun resolve(profile: ProfileIr): EffectiveRoutingPolicy = resolve(profile.routing)

    fun resolve(routing: RoutingPolicy): EffectiveRoutingPolicy {
        val generatedRegionalRules = buildGeneratedRegionalRules(routing.regionalBypass)
        val explicitBlockRules = routing.rules.filter { it.action == RouteAction.BLOCK }
        val explicitNonBlockRules = routing.rules.filter { it.action != RouteAction.BLOCK }
        return EffectiveRoutingPolicy(
            rules = explicitBlockRules + generatedRegionalRules + explicitNonBlockRules,
            generatedRegionalRuleCount = generatedRegionalRules.size,
            enabledRegionalPresets = routing.regionalBypass.enabledPresets.distinct(),
            normalizedCustomDirectDomains = routing.regionalBypass.normalizedCustomDirectDomains(),
        )
    }

    private fun buildGeneratedRegionalRules(settings: RegionalBypassSettings): List<RouteRule> = buildList {
        settings.enabledPresets.distinct().forEach { preset ->
            add(RegionalBypassPresetRegistry.ruleFor(preset))
        }
        val customDirectDomains = settings.normalizedCustomDirectDomains()
        if (customDirectDomains.isNotEmpty()) {
            add(
                RouteRule(
                    id = REGIONAL_BYPASS_CUSTOM_DIRECT_RULE_ID,
                    action = RouteAction.DIRECT,
                    match = RouteMatch(
                        domainSuffix = customDirectDomains,
                    ),
                ),
            )
        }
    }
}

internal object RegionalBypassPresetRegistry {
    fun ruleFor(presetId: RegionalBypassPresetId): RouteRule = when (presetId) {
        RegionalBypassPresetId.RUSSIA -> RouteRule(
            id = "__regional_bypass_russia__",
            action = RouteAction.DIRECT,
            match = RouteMatch(
                domainSuffix = listOf("ru", "su", "xn--p1ai"),
                geoSites = listOf("ru"),
                geoIps = listOf("ru"),
            ),
        )
    }
}

fun defaultRegionalBypass(): RegionalBypassSettings = RegionalBypassSettings(
    enabledPresets = listOf(RegionalBypassPresetId.RUSSIA),
)

fun normalizeDomainForRouting(rawValue: String): String {
    val trimmed = rawValue
        .trim()
        .removePrefix("*.") // Accept simple wildcard-like input but store as suffix.
        .removePrefix(".")
        .trimEnd('.')
    require(trimmed.isNotBlank()) { "Domain must not be blank." }

    val ascii = runCatching { IDN.toASCII(trimmed, IDN.ALLOW_UNASSIGNED) }.getOrElse {
        throw IllegalArgumentException("Domain '$rawValue' is not a valid IDN hostname.")
    }
    require(ascii.isNotBlank()) { "Domain must not be blank." }
    return ascii.lowercase()
}

fun normalizeDomainForMatching(rawValue: String): String? = runCatching {
    normalizeDomainForRouting(rawValue)
}.getOrNull()
