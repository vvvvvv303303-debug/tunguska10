package io.acionyx.tunguska.vpnservice

import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL

enum class RuntimeEgressIpObservationStatus {
    UNAVAILABLE,
    OBSERVED,
    FAILED,
}

data class RuntimeEgressIpObservation(
    val status: RuntimeEgressIpObservationStatus,
    val publicIp: String? = null,
    val observedAtEpochMs: Long = System.currentTimeMillis(),
    val summary: String? = null,
)

internal object RuntimeEgressIpProbe {
    val DEFAULT_ENDPOINTS: List<String> = listOf(
        "https://api64.ipify.org/",
        "https://ipv4.icanhazip.com/",
        "https://ifconfig.me/ip",
        "https://ipinfo.io/ip",
    )

    private val authenticatorLock = Any()

    fun unavailable(summary: String): RuntimeEgressIpObservation = RuntimeEgressIpObservation(
        status = RuntimeEgressIpObservationStatus.UNAVAILABLE,
        summary = summary,
    )

    fun failed(summary: String): RuntimeEgressIpObservation = RuntimeEgressIpObservation(
        status = RuntimeEgressIpObservationStatus.FAILED,
        summary = summary,
    )

    fun observeViaSocksBridge(
        bridge: AuthenticatedLocalBridge,
        endpoints: List<String> = DEFAULT_ENDPOINTS,
    ): RuntimeEgressIpObservation {
        val proxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress(LOOPBACK_HOST, bridge.port),
        )
        return synchronized(authenticatorLock) {
            Authenticator.setDefault(
                object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication? {
                        val isBridgeRequest = requestingHost == LOOPBACK_HOST || requestingPort == bridge.port
                        if (!isBridgeRequest) return null
                        return PasswordAuthentication(bridge.user, bridge.password.toCharArray())
                    }
                },
            )
            try {
                observe(endpoints = endpoints, proxy = proxy)
            } finally {
                Authenticator.setDefault(null)
            }
        }
    }

    private fun observe(
        endpoints: List<String>,
        proxy: Proxy,
    ): RuntimeEgressIpObservation {
        val failures = mutableListOf<String>()
        endpoints.forEach { endpoint ->
            runCatching { fetchPublicIpFromEndpoint(endpoint = endpoint, proxy = proxy) }
                .onSuccess { publicIp ->
                    return RuntimeEgressIpObservation(
                        status = RuntimeEgressIpObservationStatus.OBSERVED,
                        publicIp = publicIp,
                    )
                }
                .onFailure { error ->
                    failures += "${endpoint.substringBefore('?')}: ${error.message ?: error.javaClass.simpleName}"
                }
        }
        return failed(
            failures.joinToString(
                prefix = "Engine-routed exit IP detection failed across all fallback endpoints. ",
                separator = " | ",
            ),
        )
    }

    private fun fetchPublicIpFromEndpoint(
        endpoint: String,
        proxy: Proxy,
    ): String {
        System.setProperty("http.keepAlive", "false")
        val connection = (URL(endpoint).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
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

    private const val LOOPBACK_HOST = "127.0.0.1"
    private const val PROBE_TIMEOUT_MS = 8_000
}
