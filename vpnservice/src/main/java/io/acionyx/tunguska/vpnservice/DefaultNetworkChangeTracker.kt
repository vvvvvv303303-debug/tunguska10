package io.acionyx.tunguska.vpnservice

internal data class DefaultNetworkIdentity(
    val networkHandle: Long,
    val interfaceName: String,
)

internal class DefaultNetworkChangeTracker {
    private var lastObserved: DefaultNetworkIdentity? = null
    private var networkBecameUnavailable: Boolean = false

    fun seed(identity: DefaultNetworkIdentity?) {
        lastObserved = identity
        networkBecameUnavailable = false
    }

    fun clear() {
        lastObserved = null
        networkBecameUnavailable = false
    }

    fun recordObservation(identity: DefaultNetworkIdentity?, runtimeActive: Boolean): Boolean {
        val previous = lastObserved
        lastObserved = identity
        if (!runtimeActive) {
            networkBecameUnavailable = false
            return false
        }
        if (identity == null) {
            if (previous != null) {
                networkBecameUnavailable = true
            }
            return false
        }
        if (previous == null) {
            val shouldRestart = networkBecameUnavailable
            networkBecameUnavailable = false
            return shouldRestart
        }
        networkBecameUnavailable = false
        return previous != identity
    }
}

internal fun DefaultNetworkIdentity.description(): String = "$interfaceName#$networkHandle"