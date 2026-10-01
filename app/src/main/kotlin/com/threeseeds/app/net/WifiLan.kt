package com.threeseeds.app.net

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** One discovered match host (Wi-Fi LAN or Bluetooth). */
data class LinkPeer(
    val name: String,
    val kind: LinkKind,
    /** Stable id for UI lists: host IP:port for Wi-Fi, MAC for Bluetooth. */
    val id: String,
    val hostAddress: InetAddress?,
    val hostPort: Int,
    /** Bluetooth peers dial by MAC; Wi-Fi peers by [hostAddress]:[hostPort]. */
    val bluetoothAddress: String? = null,
)

enum class LinkKind { WIFI, BLUETOOTH }

/**
 * Wi-Fi LAN discovery and connection: the HOST answers UDP beacons on
 * [BEACON_PORT] and accepts one TCP client on [TCP_PORT]; GUESTS probe
 * the broadcast address, learn the host's address from the reply, and
 * dial TCP. No platform APIs involved — plain java.net, so the whole
 * flow is exercisable from JVM tests against loopback.
 */
object WifiLan {

    const val BEACON_PORT = 44771
    const val TCP_PORT = 44772
    private const val PROBE = "TS3-PROBE"
    private const val BEACON = "TS3-HOST|"

    /** A running listener/scan; close() stops everything. */
    interface Handle : Closeable

    /**
     * Starts hosting: beacon responder + one-shot TCP accept. The
     * callback fires at most once (first guest wins), off-thread.
     */
    fun startHost(localName: String, onGuest: (Socket) -> Unit): Handle {
        val alive = AtomicBoolean(true)
        val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(TCP_PORT)) }
        val beacon = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(BEACON_PORT))
        }

        thread("wifi-beacon") {
            val buffer = ByteArray(256)
            while (alive.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    beacon.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (text == PROBE) {
                        val reply = "$BEACON$localName".toByteArray(Charsets.UTF_8)
                        beacon.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                } catch (io: Exception) {
                    if (!alive.get()) return@thread
                }
            }
        }

        thread("wifi-accept") {
            try {
                val guest = server.accept()
                if (alive.compareAndSet(true, false)) {
                    beacon.close()
                    onGuest(guest)
                } else {
                    guest.close()
                }
            } catch (io: Exception) {
                // listener closed — nothing to report
            }
        }

        return object : Handle {
            override fun close() {
                alive.set(false)
                runCatching { beacon.close() }
                runCatching { server.close() }
            }
        }
    }

    /**
     * Probes for hosts every second, reporting each fresh sighting
     * (and re-reporting after a sighting goes quiet) through [onFound].
     */
    fun scan(onFound: (LinkPeer) -> Unit): Handle {
        val alive = AtomicBoolean(true)
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1000
            bind(InetSocketAddress(0))
        }
        val known = mutableMapOf<String, Long>()

        thread("wifi-scan") {
            val probe = PROBE.toByteArray(Charsets.UTF_8)
            var lastProbeAt = 0L
            while (alive.get()) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastProbeAt >= 1000) {
                        lastProbeAt = now
                        socket.send(
                            DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), BEACON_PORT)
                        )
                        // Loopback probe so JVM tests on one machine work too.
                        socket.send(DatagramPacket(probe, probe.size, InetAddress.getByName("127.0.0.1"), BEACON_PORT))
                    }
                    val buffer = ByteArray(256)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (text.startsWith(BEACON)) {
                        val name = text.removePrefix(BEACON)
                        val id = "${packet.address.hostAddress}:$TCP_PORT"
                        val fresh = known[id]?.let { now - it > 5000 } ?: true
                        known[id] = now
                        if (fresh) {
                            onFound(
                                LinkPeer(
                                    name = name,
                                    kind = LinkKind.WIFI,
                                    id = id,
                                    hostAddress = packet.address,
                                    hostPort = TCP_PORT,
                                )
                            )
                        }
                    }
                } catch (io: Exception) {
                    if (!alive.get()) return@thread
                }
            }
        }

        return object : Handle {
            override fun close() {
                alive.set(false)
                runCatching { socket.close() }
            }
        }
    }

    /** Dials a discovered host; the callback delivers a connected socket or the error. */
    fun connect(peer: LinkPeer, onConnected: (Socket) -> Unit, onError: (Throwable) -> Unit) {
        thread("wifi-connect") {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(peer.hostAddress, peer.hostPort), 4000)
                socket.tcpNoDelay = true
                onConnected(socket)
            } catch (io: Throwable) {
                onError(io)
            }
        }
    }

    private fun thread(name: String, body: () -> Unit) =
        Thread({ body() }, name).apply { isDaemon = true; start() }
}
