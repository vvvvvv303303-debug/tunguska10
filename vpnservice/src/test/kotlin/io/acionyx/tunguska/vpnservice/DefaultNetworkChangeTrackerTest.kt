package io.acionyx.tunguska.vpnservice

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DefaultNetworkChangeTrackerTest {
    @Test
    fun `same observed network does not restart`() {
        val tracker = DefaultNetworkChangeTracker()
        val wifi = DefaultNetworkIdentity(networkHandle = 11L, interfaceName = "wlan0")

        tracker.seed(wifi)

        assertFalse(tracker.recordObservation(identity = wifi, runtimeActive = true))
    }

    @Test
    fun `switching to a different active network restarts`() {
        val tracker = DefaultNetworkChangeTracker()
        val wifi = DefaultNetworkIdentity(networkHandle = 11L, interfaceName = "wlan0")
        val cellular = DefaultNetworkIdentity(networkHandle = 19L, interfaceName = "rmnet_data0")

        tracker.seed(wifi)

        assertTrue(tracker.recordObservation(identity = cellular, runtimeActive = true))
    }

    @Test
    fun `loss followed by a new default network restarts when connectivity returns`() {
        val tracker = DefaultNetworkChangeTracker()
        val wifi = DefaultNetworkIdentity(networkHandle = 11L, interfaceName = "wlan0")
        val cellular = DefaultNetworkIdentity(networkHandle = 19L, interfaceName = "rmnet_data0")

        tracker.seed(wifi)

        assertFalse(tracker.recordObservation(identity = null, runtimeActive = true))
        assertTrue(tracker.recordObservation(identity = cellular, runtimeActive = true))
    }

    @Test
    fun `inactive runtime never requests restart`() {
        val tracker = DefaultNetworkChangeTracker()
        val wifi = DefaultNetworkIdentity(networkHandle = 11L, interfaceName = "wlan0")
        val cellular = DefaultNetworkIdentity(networkHandle = 19L, interfaceName = "rmnet_data0")

        tracker.seed(wifi)

        assertFalse(tracker.recordObservation(identity = cellular, runtimeActive = false))
    }
}