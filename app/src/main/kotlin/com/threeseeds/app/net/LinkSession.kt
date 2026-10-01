package com.threeseeds.app.net

/**
 * Role logic on top of a [LineTransport]: the HOST is authoritative
 * (it owns GameEngine and answers guest taps with fresh states), the
 * GUEST mirrors states and forwards taps. Pure Kotlin — listeners are
 * invoked on the transport's reader thread, so the app layer hops to
 * its own scope if it needs the main thread.
 */
class LinkSession(
    val isHost: Boolean,
    private val transport: LineTransport,
    val localName: String,
    /** Host only: current ruleset name, included in WELCOME so both sides freeze the same rules. */
    private val hostRules: () -> String = { "" },
) {

    interface Listener {
        /** Handshake finished: host saw HI, guest saw WELCOME. [rules] is the host's ruleset. */
        fun onConnected(peerName: String, rules: String)

        /** Host only: a validated TAP from the guest (board index 0..8). */
        fun onGuestTap(index: Int)

        /** Guest only: authoritative state broadcast from the host. */
        fun onState(encoded: String)

        /** Either side asked for a fresh match. */
        fun onRematch()

        /** Link closed (BYE or transport failure). */
        fun onDisconnected(reason: String?)
    }

    var peerName: String = ""
        private set

    private var listener: Listener? = null
    private var started = false

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        if (started) return
        started = true
        transport.setOnLine(::handle)
        transport.setOnClosed { reason -> listener?.onDisconnected(reason) }
        if (!isHost) {
            send(LinkMessage.Hi(localName))
        }
    }

    fun sendState(encoded: String) = send(LinkMessage.State(encoded))

    fun sendTap(index: Int) = send(LinkMessage.Tap(index))

    fun sendRematch() = send(LinkMessage.Rematch)

    fun sendBye() {
        send(LinkMessage.Bye)
        transport.close()
    }

    fun close() = transport.close()

    private fun send(message: LinkMessage) = transport.send(message.encode())

    private fun handle(line: String) {
        val message = LinkMessage.decode(line)
        if (message == null) {
            // Malformed input: the host tells the peer off; both sides
            // ignore malformed input rather than tearing the link down —
            // one stray line must not kill a match.
            if (isHost) send(LinkMessage.Err("malformed message"))
            return
        }
        when {
            isHost -> handleHost(message)
            else -> handleGuest(message)
        }
    }

    private fun handleHost(message: LinkMessage) {
        when (message) {
            is LinkMessage.Hi -> {
                if (message.protocolVersion != LinkMessage.PROTOCOL_VERSION) {
                    send(LinkMessage.Err("unsupported protocol version"))
                    return
                }
                peerName = message.name
                send(LinkMessage.Welcome(guestName = peerName, hostName = localName, rules = hostRules()))
                listener?.onConnected(peerName, hostRules())
            }

            is LinkMessage.Tap -> listener?.onGuestTap(message.index)
            LinkMessage.Rematch -> listener?.onRematch()
            LinkMessage.Bye -> transport.close()
            else -> send(LinkMessage.Err("unexpected message on host"))
        }
    }

    private fun handleGuest(message: LinkMessage) {
        when (message) {
            is LinkMessage.Welcome -> {
                peerName = message.hostName
                listener?.onConnected(message.hostName, message.rules)
            }

            is LinkMessage.State -> listener?.onState(message.encoded)
            LinkMessage.Rematch -> listener?.onRematch()
            LinkMessage.Bye -> transport.close()
            is LinkMessage.Err -> listener?.onDisconnected("host: ${message.reason}")
            else -> Unit // TAP/HI/WELCOME are host-bound; ignore quietly
        }
    }
}
