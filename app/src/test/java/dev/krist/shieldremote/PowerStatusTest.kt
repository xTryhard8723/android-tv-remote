package dev.krist.shieldremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 3-state TV status dot: [Remote.Msg.Start] + connection link, and reset-on-disconnect. */
class PowerStatusTest {

    @Test fun connectedAwakeIsOn() = assertEquals(TvStatus.ON, tvStatus(Link.CONNECTED, true))

    @Test fun connectedAsleep() = assertEquals(TvStatus.ASLEEP, tvStatus(Link.CONNECTED, false))

    @Test fun connectedBeforeStartMessageTreatedAsOn() =
        assertEquals(TvStatus.ON, tvStatus(Link.CONNECTED, null))

    @Test fun anyDisconnectedLinkIsOffEvenWithStaleIsOn() {
        val disconnected = listOf(
            Link.PAUSED, Link.NO_DEVICE, Link.NO_WIFI, Link.NEEDS_PAIRING,
            Link.CONNECTING, Link.RECONNECTING,
        )
        for (link in disconnected) {
            assertEquals("$link with stale isOn=true", TvStatus.OFF, tvStatus(link, true))
            assertEquals("$link with stale isOn=false", TvStatus.OFF, tvStatus(link, false))
            assertEquals("$link with isOn=null", TvStatus.OFF, tvStatus(link, null))
        }
    }

    @Test fun startMessageTrueThenFalse() {
        var isOn: Boolean? = null
        assertEquals(TvStatus.ON, tvStatus(Link.CONNECTED, isOn))
        isOn = true  // Remote.Msg.Start(started = true)
        assertEquals(TvStatus.ON, tvStatus(Link.CONNECTED, isOn))
        isOn = false // Remote.Msg.Start(started = false)
        assertEquals(TvStatus.ASLEEP, tvStatus(Link.CONNECTED, isOn))
        isOn = true  // woke again
        assertEquals(TvStatus.ON, tvStatus(Link.CONNECTED, isOn))
    }

    @Test fun withLinkClearsIsOnWhenLeavingConnected() {
        val on = RemoteState(link = Link.CONNECTED, isOn = true)
        val paused = on.withLink(Link.PAUSED)
        assertEquals(Link.PAUSED, paused.link)
        assertNull(paused.isOn)
        assertEquals(TvStatus.OFF, tvStatus(paused.link, paused.isOn))

        val reconnecting = on.withLink(Link.RECONNECTING, "timeout")
        assertEquals(Link.RECONNECTING, reconnecting.link)
        assertEquals("timeout", reconnecting.detail)
        assertNull(reconnecting.isOn)
        assertEquals(TvStatus.OFF, tvStatus(reconnecting.link, reconnecting.isOn))
    }

    @Test fun withLinkPreservesIsOnWhileStillConnected() {
        val on = RemoteState(link = Link.CONNECTED, isOn = true)
        assertEquals(true, on.withLink(Link.CONNECTED).isOn)
        val asleep = RemoteState(link = Link.CONNECTED, isOn = false)
        assertEquals(false, asleep.withLink(Link.CONNECTED, queued = 0).isOn)
    }

    @Test fun withLinkEnteringConnectedDoesNotInventIsOn() {
        val s = RemoteState(link = Link.CONNECTING, isOn = null)
        val connected = s.withLink(Link.CONNECTED)
        assertEquals(Link.CONNECTED, connected.link)
        assertNull(connected.isOn)
        // Until Start arrives, the dot shows On rather than a false Asleep.
        assertEquals(TvStatus.ON, tvStatus(connected.link, connected.isOn))
    }
}
