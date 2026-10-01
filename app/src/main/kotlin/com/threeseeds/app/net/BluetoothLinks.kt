package com.threeseeds.app.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.util.UUID

/**
 * Bluetooth RFCOMM hosting, discovery, and connection. Thin Android
 * wrapper: the socket handed to the callback plugs straight into
 * [SocketLineTransport] exactly like the TCP path, so all protocol
 * logic stays medium-agnostic (and JVM-tested).
 *
 * Requires runtime BLUETOOTH_CONNECT (host/guest) and BLUETOOTH_SCAN
 * (guest) on Android 12+; the screen requests them before calling in.
 */
object BluetoothLinks {

    private const val SERVICE_NAME = "ThreeSeeds"
    private val SERVICE_UUID: UUID = UUID.fromString("b7f2c3d0-5a41-4e8e-9c66-3d0a9e1f7b22")

    fun isSupported(): Boolean = adapterOrNull() != null

    /** Host: listen for one incoming RFCOMM connection. */
    @SuppressLint("MissingPermission")
    fun startHost(onGuest: (BluetoothSocket) -> Unit, onError: (Throwable) -> Unit): Closeable {
        val adapter = adapterOrNull()
        if (adapter == null) {
            onError(IllegalStateException("Bluetooth unavailable"))
            return Closeable { }
        }
        var server: BluetoothServerSocket? = null
        try {
            server = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        } catch (io: Throwable) {
            onError(io)
            return Closeable { }
        }
        val accepted = server
        val thread = Thread({
            try {
                val socket = accepted.accept()
                runCatching { accepted.close() }
                onGuest(socket)
            } catch (io: Throwable) {
                runCatching { accepted.close() }
            }
        }, "bt-accept")
        thread.isDaemon = true
        thread.start()
        return Closeable { runCatching { accepted.close() } }
    }

    /**
     * Guest: kick off a classic inquiry scan and immediately report
     * already-bonded devices. New discoveries are delivered by the
     * system's [BluetoothDevice.ACTION_FOUND] broadcast, which the
     * lobby screen registers (it owns the Context) and forwards
     * through `onBondedOrFound`-style callbacks.
     */
    @SuppressLint("MissingPermission")
    fun startScan(
        onBonded: (BluetoothDevice) -> Unit,
        onError: (Throwable) -> Unit,
    ): Closeable {
        val adapter = adapterOrNull()
        if (adapter == null) {
            onError(IllegalStateException("Bluetooth unavailable"))
            return Closeable { }
        }
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            @Suppress("DEPRECATION")
            adapter.bondedDevices?.forEach(onBonded)
            adapter.startDiscovery()
        } catch (io: Throwable) {
            onError(io)
        }
        return Closeable {
            runCatching { adapter.cancelDiscovery() }
        }
    }

    /** Turns a broadcast-reported MAC into a connectable device. */
    fun deviceFor(address: String): BluetoothDevice? =
        runCatching { adapterOrNull()?.getRemoteDevice(address) }.getOrNull()

    /** Guest: connect to a discovered/bonded device's RFCOMM service. */
    @SuppressLint("MissingPermission")
    fun connect(
        device: BluetoothDevice,
        onConnected: (BluetoothSocket) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        Thread({
            try {
                val adapter = adapterOrNull() ?: throw IllegalStateException("Bluetooth unavailable")
                if (adapter.isDiscovering) adapter.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                onConnected(socket)
            } catch (io: Throwable) {
                onError(io)
            }
        }, "bt-connect").apply { isDaemon = true; start() }
    }

    private fun adapterOrNull(): BluetoothAdapter? =
        runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
}
