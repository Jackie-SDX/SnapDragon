package com.threeseeds.app.net

/**
 * Deterministic in-memory transport pair: [send] delivers
 * synchronously to the peer's listener on the calling thread, and
 * closing one side closes both — mirroring how a dead socket behaves
 * from the session's point of view.
 */
class InMemoryTransport private constructor() : LineTransport {

    private var peer: InMemoryTransport? = null
    private var lineListener: ((String) -> Unit)? = null
    private var closedListener: ((String?) -> Unit)? = null
    private var closed = false

    /** Every line this side sent, for assertions. */
    val sent = mutableListOf<String>()

    override fun setOnLine(listener: ((String) -> Unit)?) {
        lineListener = listener
    }

    override fun setOnClosed(listener: ((String?) -> Unit)?) {
        closedListener = listener
    }

    override fun send(line: String) {
        if (closed) return
        sent += line
        peer?.lineListener?.invoke(line)
    }

    override fun close() {
        finish(null)
        peer?.finish(null)
    }

    private fun finish(reason: String?) {
        if (closed) return
        closed = true
        closedListener?.invoke(reason)
    }

    companion object {
        fun pair(): Pair<InMemoryTransport, InMemoryTransport> {
            val a = InMemoryTransport()
            val b = InMemoryTransport()
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}
