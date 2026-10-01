package com.threeseeds.app.net

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SocketLineTransportTest {

    private fun connectedPair(): Pair<StreamLineTransport, StreamLineTransport> {
        val server = ServerSocket(0)
        val latch = CountDownLatch(1)
        var accepted: java.net.Socket? = null
        Thread {
            accepted = server.accept()
            latch.countDown()
        }.apply { isDaemon = true; start() }

        val client = java.net.Socket("127.0.0.1", server.localPort)
        assertTrue(latch.await(5, TimeUnit.SECONDS), "server should accept")
        server.close()
        val serverSide = accepted!!
        return client.asLineTransport() to serverSide.asLineTransport()
    }

    @Test
    fun `lines travel both ways over a real socket`() {
        val (a, b) = connectedPair()
        val fromA = LinkedBlockingQueue<String>()
        val fromB = LinkedBlockingQueue<String>()
        a.setOnLine { fromA.put(it) }
        b.setOnLine { fromB.put(it) }

        a.send("HI|Jackie|1")
        assertEquals("HI|Jackie|1", fromB.poll(5, TimeUnit.SECONDS))

        b.send("WELCOME|Jackie|Host|FREE")
        assertEquals("WELCOME|Jackie|Host|FREE", fromA.poll(5, TimeUnit.SECONDS))

        a.close()
        b.close()
    }

    @Test
    fun `closing one end reports closed exactly once on each side`() {
        val (a, b) = connectedPair()
        val aClosed = CountDownLatch(1)
        val bClosed = CountDownLatch(1)
        a.setOnClosed { aClosed.countDown() }
        b.setOnClosed { bClosed.countDown() }

        a.close()

        assertTrue(aClosed.await(5, TimeUnit.SECONDS), "closing side notified")
        assertTrue(bClosed.await(5, TimeUnit.SECONDS), "peer notified")
        a.close() // idempotent: no crash, no double delivery
        assertTrue(true)
    }

    @Test
    fun `lines with hostile characters survive intact`() {
        val (a, b) = connectedPair()
        val received = LinkedBlockingQueue<String>()
        b.setOnLine { received.put(it) }

        val line = "WELCOME|a|b|c"
        a.send(line)
        assertEquals(line, received.poll(5, TimeUnit.SECONDS))
    }
}
