package com.threeseeds.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkSessionTest {

    /** Collects listener callbacks for assertions. */
    private class RecordingListener : LinkSession.Listener {
        var connected: Pair<String, String>? = null
        var guestTap: Int? = null
        var state: String? = null
        var rematchCount = 0
        var disconnected = false

        override fun onConnected(peerName: String, rules: String) {
            connected = peerName to rules
        }

        override fun onGuestTap(index: Int) {
            guestTap = index
        }

        override fun onState(encoded: String) {
            state = encoded
        }

        override fun onRematch() {
            rematchCount++
        }

        override fun onDisconnected(reason: String?) {
            disconnected = true
        }
    }

    private class Wired(val host: LinkSession, val guest: LinkSession, val h: RecordingListener, val g: RecordingListener)

    private fun wired(hostRules: String = "FREE"): Wired {
        val (hostTransport, guestTransport) = InMemoryTransport.pair()
        val h = RecordingListener()
        val g = RecordingListener()
        val host = LinkSession(isHost = true, transport = hostTransport, localName = "Host") { hostRules }
        val guest = LinkSession(isHost = false, transport = guestTransport, localName = "Guest")
        host.setListener(h)
        guest.setListener(g)
        host.start()
        guest.start() // HI goes out synchronously; WELCOME comes back
        return Wired(host, guest, h, g)
    }

    @Test
    fun `handshake delivers names and the host ruleset both ways`() {
        val w = wired(hostRules = "TAPATAN")
        assertEquals("Host" to "TAPATAN", w.g.connected, "guest learns host + rules")
        assertEquals("Guest" to "TAPATAN", w.h.connected, "host learns guest")
    }

    @Test
    fun `guest taps reach the host`() {
        val w = wired()
        w.guest.sendTap(7)
        assertEquals(7, w.h.guestTap)
    }

    @Test
    fun `host states reach the guest`() {
        val w = wired()
        w.host.sendState("1..2.....")
        assertEquals("1..2.....", w.g.state)
    }

    @Test
    fun `rematch flows both directions`() {
        val w = wired()
        w.guest.sendRematch()
        assertEquals(1, w.h.rematchCount)
        w.host.sendRematch()
        assertEquals(1, w.g.rematchCount)
    }

    @Test
    fun `malformed input earns an error on the host`() {
        val (hostTransport, guestTransport) = InMemoryTransport.pair()
        val host = LinkSession(true, hostTransport, "Host") { "FREE" }
        host.setListener(RecordingListener())
        host.start()

        guestTransport.send("TOTALLY|GARBAGE")

        val errors = hostTransport.sent.filter { it.startsWith("ERR|") }
        assertEquals(1, errors.size, "host should answer one malformed line with ERR")
        assertTrue(errors.single().contains("malformed"))
    }

    @Test
    fun `malformed input is ignored quietly on the guest`() {
        val (hostTransport, guestTransport) = InMemoryTransport.pair()
        val guest = LinkSession(false, guestTransport, "Guest")
        guest.setListener(RecordingListener())
        guest.start()

        hostTransport.send("GARBAGE|STUFF")

        // Guest sent only its HI; no crash, no reply, link stays usable.
        assertEquals(listOf("HI|Guest|1"), guestTransport.sent)
    }

    @Test
    fun `unsupported protocol version is refused without completing the handshake`() {
        val (hostTransport, guestTransport) = InMemoryTransport.pair()
        val listener = RecordingListener()
        val host = LinkSession(true, hostTransport, "Host") { "FREE" }
        host.setListener(listener)
        host.start()

        guestTransport.send("HI|OldClient|99")

        assertNull(listener.connected, "handshake must not complete")
        assertTrue(hostTransport.sent.any { it.startsWith("ERR|") })
    }

    @Test
    fun `closing one side notifies both listeners`() {
        val w = wired()
        w.guest.sendBye()
        assertTrue(w.g.disconnected, "guest learns it closed")
        assertTrue(w.h.disconnected, "host learns the link died")
    }

    @Test
    fun `host ignores guest-bound STATE messages instead of treating them as inbound`() {
        val (hostTransport, guestTransport) = InMemoryTransport.pair()
        val listener = RecordingListener()
        val host = LinkSession(true, hostTransport, "Host") { "FREE" }
        host.setListener(listener)
        host.start()

        guestTransport.send("HI|P|1")
        guestTransport.send("STATE|whatever")

        assertNull(listener.state, "host must not surface STATE as an inbound event")
    }
}
