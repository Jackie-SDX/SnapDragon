package com.threeseeds.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkMessageTest {

    private fun roundTrip(message: LinkMessage): LinkMessage? =
        LinkMessage.decode(message.encode())

    @Test
    fun `hi round-trips with names intact`() {
        val decoded = roundTrip(LinkMessage.Hi("Jackie", 1))
        assertEquals(LinkMessage.Hi("Jackie", 1), decoded)
    }

    @Test
    fun `welcome carries the host ruleset`() {
        val decoded = roundTrip(LinkMessage.Welcome("Guest", "Host", "TAPATAN"))
        assertEquals(LinkMessage.Welcome("Guest", "Host", "TAPATAN"), decoded)
    }

    @Test
    fun `tap only accepts board indices`() {
        assertEquals(LinkMessage.Tap(0), roundTrip(LinkMessage.Tap(0)))
        assertEquals(LinkMessage.Tap(8), roundTrip(LinkMessage.Tap(8)))
        assertNull(LinkMessage.decode("TAP|9"))
        assertNull(LinkMessage.decode("TAP|-1"))
        assertNull(LinkMessage.decode("TAP|abc"))
    }

    @Test
    fun `state survives the codec alphabet`() {
        val encoded = "....1;...12;1..2.2"
        assertEquals(LinkMessage.State(encoded), roundTrip(LinkMessage.State(encoded)))
    }

    @Test
    fun `pipes and newlines in names cannot break framing`() {
        val hostile = "a|b\nc\rd%e"
        val decoded = roundTrip(LinkMessage.Hi(hostile, 1)) as LinkMessage.Hi
        assertEquals(hostile, decoded.name)
        // Exactly three fields survive: HI, name, version.
        assertEquals(3, LinkMessage.Hi(hostile, 1).encode().split('|').size)
    }

    @Test
    fun `control messages are bare keywords`() {
        assertEquals(LinkMessage.Rematch, roundTrip(LinkMessage.Rematch))
        assertEquals(LinkMessage.Bye, roundTrip(LinkMessage.Bye))
        assertEquals("REMATCH", LinkMessage.Rematch.encode())
        assertEquals("BYE", LinkMessage.Bye.encode())
    }

    @Test
    fun `malformed lines decode to null instead of throwing`() {
        val garbage = listOf(
            "", "HI", "HI|only-two-fields", "WELCOME|a|b", "STATE",
            "TAP", "ERR", "UNKNOWN|x", "HI|n|not-a-number", "TAP|4|extra",
        )
        garbage.forEach { assertNull(LinkMessage.decode(it), "expected null for: $it") }
    }

    @Test
    fun `err round-trips its reason`() {
        assertEquals(LinkMessage.Err("unsupported protocol version"), roundTrip(LinkMessage.Err("unsupported protocol version")))
    }

    @Test
    fun `protocol version is part of hi so future peers can refuse cleanly`() {
        val line = LinkMessage.Hi("Peer", LinkMessage.PROTOCOL_VERSION).encode()
        assertTrue(line.startsWith("HI|Peer|"))
    }
}
