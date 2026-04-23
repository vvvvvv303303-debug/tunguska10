package io.acionyx.tunguska.app

import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class EgressIpObservationRepository(
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
) {
    fun observe(): EgressIpObservation {
        val publicIp = fetchPublicIp()
        return EgressIpObservation(
            publicIp = publicIp,
            observedAtEpochMs = Instant.now().toEpochMilli(),
        )
    }

    private fun fetchPublicIp(): String {
        val failures = mutableListOf<String>()
        endpoints.forEach { endpoint ->
            runCatching { fetchPublicIpFromEndpoint(endpoint) }
                .onSuccess { return it }
                .onFailure { error ->
                    failures += "${endpoint.substringBefore('?')}: ${error.message ?: error.javaClass.simpleName}"
                }
        }
        error(
            failures.joinToString(
                prefix = "External IP detection failed across all fallback endpoints. ",
                separator = " | ",
            ),
        )
    }

    private fun fetchPublicIpFromEndpoint(endpoint: String): String {
        System.setProperty("http.keepAlive", "false")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Connection", "close")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
        }
        return try {
            check(connection.responseCode in 200..299) {
                "HTTP ${connection.responseCode}"
            }
            connection.inputStream.bufferedReader().use { reader ->
                reader.readLines()
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.takeIf(::looksLikeIpAddress)
                    ?: error("External IP probe returned an invalid response.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun looksLikeIpAddress(value: String): Boolean {
        val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        val ipv6 = Regex("""^[0-9A-Fa-f:]+$""")
        return ipv4.matches(value) || ipv6.matches(value)
    }

    companion object {
        val DEFAULT_ENDPOINTS: List<String> = listOf(
            "https://api64.ipify.org/",
            "https://ipv4.icanhazip.com/",
            "https://ifconfig.me/ip",
            "https://ipinfo.io/ip",
        )
    }
}

data class EgressIpObservation(
    val publicIp: String,
    val observedAtEpochMs: Long,
)
