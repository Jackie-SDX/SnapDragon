package com.threeseeds.app.net

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log

/**
 * A line-oriented bidirectional channel. Transports deliver whole
 * lines (without terminators) to the listener and swallow nothing:
 * any failure ends in [onClosed] exactly once so the session above
 * can treat "link died" uniformly regardless of the underlying
 * medium (TCP socket, RFCOMM, or an in-memory test double).
 */
interface LineTransport {
    fun setOnLine(listener: ((String) -> Unit)?)
    fun setOnClosed(listener: ((String?) -> Unit)?)
    fun send(line: String)
    fun close()
}

/**
 * [LineTransport] over raw streams: one daemon reader thread, one
 * daemon writer thread fed by a queue. [send] only enqueues, so any
 * thread — including Android's main thread, where StrictMode throws
 * [android.os.NetworkOnMainThreadException] for direct socket writes —
 * can post messages safely. Works over TCP sockets (JVM tests, Wi-Fi)
 * and Bluetooth RFCOMM streams alike.
 */
class StreamLineTransport(
    input: InputStream,
    output: OutputStream,
    private val closeUnderlying: () -> Unit,
) : LineTransport, Closeable {

    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val outbox = LinkedBlockingQueue<String>()
    private var lineListener: ((String) -> Unit)? = null
    private var closedListener: ((String?) -> Unit)? = null
    @Volatile
    private var writerThread: Thread? = null

    private val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

    init {
        Thread({ readLoop() }, "line-transport-reader").apply {
            isDaemon = true
            start()
        }
        writerThread = Thread({ writeLoop() }, "line-transport-writer").apply {
            isDaemon = true
            start()
        }
    }

    override fun setOnLine(listener: ((String) -> Unit)?) {
        lineListener = listener
    }

    override fun setOnClosed(listener: ((String?) -> Unit)?) {
        closedListener = listener
    }

    override fun send(line: String) {
        if (closed.get()) return
        try {
            outbox.put(line)
        } catch (io: Exception) {
            finish("send failed: ${io.message}")
        }
    }

    override fun close() = finish(null)

    private fun writeLoop() {
        try {
            while (true) {
                val line = outbox.take()
                synchronized(writeLock) {
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                }
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (io: Exception) {
            Log.e("LineTransport", "send failed", io)
            finish("send failed: ${io.message}")
        }
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val line = reader.readLine() ?: break
                lineListener?.invoke(line)
            }
            finish(null)
        } catch (io: Exception) {
            Log.e("LineTransport", "read loop died", io)
            finish(io.message)
        }
    }

    private fun finish(reason: String?) {
        if (!closed.compareAndSet(false, true)) return
        writerThread?.interrupt()
        runCatching { closeUnderlying() }
        closedListener?.invoke(reason)
    }
}

/** Convenience for the TCP path. */
fun Socket.asLineTransport(): StreamLineTransport =
    StreamLineTransport(getInputStream(), getOutputStream()) { close() }
