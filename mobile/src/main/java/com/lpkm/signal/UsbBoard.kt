package com.lpkm.signal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

internal class UsbLineBuffer {
    private val line = StringBuilder()

    enum class Event { PROBE, READY, LOG }

    fun reset() {
        line.clear()
    }

    fun push(data: ByteArray): List<Event> {
        val events = ArrayList<Event>()
        for (byte in data) {
            val ch = byte.toInt() and 0xFF
            if (ch == '\r'.code) continue
            if (ch == '\n'.code) {
                when (line.toString()) {
                    SignalProtocol.USB_PROBE -> events.add(Event.PROBE)
                    SignalProtocol.USB_READY -> events.add(Event.READY)
                    "" -> Unit
                    else -> events.add(Event.LOG)
                }
                line.clear()
                continue
            }
            if (ch == SignalProtocol.STX || ch !in 0x20..0x7E || line.length >= 48) {
                line.clear()
                continue
            }
            line.append(ch.toChar())
        }
        return events
    }
}

class UsbBoard(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPortOpen()
        fun onSessionUp()
        fun onLogMode()
        fun onPortClosed()
        fun onBytes(data: ByteArray)
    }

    private val app = context.applicationContext
    private val manager = app.getSystemService(UsbManager::class.java)
    private val lines = UsbLineBuffer()
    private val outgoing = ConcurrentLinkedQueue<ByteArray>()
    private val running = AtomicBoolean(false)
    private val permissionAction = "${app.packageName}.USB_PERMISSION"
    private var worker: Thread? = null
    private var connection: UsbDeviceConnection? = null
    private var input: UsbEndpoint? = null
    private var output: UsbEndpoint? = null
    private var claimed = ArrayList<UsbInterface>()
    private var askedPermissionFor: String? = null
    private var announced = false
    private var logReported = false
    private var lastAckMs = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = Unit
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        worker = Thread({ loop() }, "signal-usb").also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            app.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        worker?.join(800)
        worker = null
        closePort(notify = false)
    }

    fun write(data: ByteArray): Boolean {
        if (connection == null || output == null) return false
        outgoing.add(data.copyOf())
        return true
    }

    private fun loop() {
        val buffer = ByteArray(256)
        while (running.get()) {
            if (connection == null) {
                if (!openBoard()) {
                    sleepQuiet(200)
                    continue
                }
            }
            if (!announced && SystemClock.elapsedRealtime() - lastAckMs >= 1_000L) {
                outgoing.add(ACK)
                lastAckMs = SystemClock.elapsedRealtime()
            }
            drainWrites()
            val port = connection
            val endpoint = input
            if (port == null || endpoint == null) continue
            val count = try {
                port.bulkTransfer(endpoint, buffer, buffer.size, 80)
            } catch (error: Exception) {
                Log.w(TAG, "USB read", error)
                -1
            }
            if (!running.get()) break
            if (count < 0) {
                if (!boardPresent()) closePort(notify = true)
                continue
            }
            if (count == 0) continue
            accept(buffer.copyOf(count))
        }
        closePort(notify = true)
    }

    private fun accept(chunk: ByteArray) {
        var becameReady = false
        for (event in lines.push(chunk)) {
            when (event) {
                UsbLineBuffer.Event.PROBE -> outgoing.add(ACK)
                UsbLineBuffer.Event.READY -> if (!announced) {
                    announced = true
                    becameReady = true
                }
                UsbLineBuffer.Event.LOG -> if (!announced && !logReported) {
                    logReported = true
                    listener.onLogMode()
                }
            }
        }
        if (becameReady) listener.onSessionUp()
        if (announced) listener.onBytes(chunk)
        drainWrites()
    }

    private fun openBoard(): Boolean {
        val usb = manager ?: return false
        val device = usb.deviceList.values.firstOrNull { isSignalBoard(it) } ?: return false
        if (!usb.hasPermission(device)) {
            if (askedPermissionFor != device.deviceName) {
                askedPermissionFor = device.deviceName
                requestPermission(usb, device)
            }
            return false
        }
        val port = findPort(device) ?: return false
        val opened = usb.openDevice(device) ?: return false
        val held = ArrayList<UsbInterface>()
        port.comm?.let { intf ->
            if (opened.claimInterface(intf, true)) held.add(intf)
        }
        if (!opened.claimInterface(port.data, true)) {
            held.forEach { opened.releaseInterface(it) }
            opened.close()
            return false
        }
        held.add(port.data)
        setLineCoding(opened, port.comm?.id ?: port.data.id)
        connection = opened
        input = port.input
        output = port.output
        claimed = held
        announced = false
        logReported = false
        lines.reset()
        outgoing.clear()
        outgoing.add(ACK)
        lastAckMs = SystemClock.elapsedRealtime()
        listener.onPortOpen()
        return true
    }

    private fun requestPermission(usb: UsbManager, device: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            app,
            0,
            Intent(permissionAction).setPackage(app.packageName),
            flags,
        )
        try {
            usb.requestPermission(device, pending)
        } catch (error: Exception) {
            Log.w(TAG, "USB permission", error)
        }
    }

    private fun drainWrites() {
        val port = connection ?: return
        val endpoint = output ?: return
        while (true) {
            val data = outgoing.poll() ?: return
            try {
                port.bulkTransfer(endpoint, data, data.size, 200)
            } catch (error: Exception) {
                Log.w(TAG, "USB write", error)
                return
            }
        }
    }

    private fun boardPresent(): Boolean {
        val usb = manager ?: return false
        return usb.deviceList.values.any { isSignalBoard(it) }
    }

    private fun closePort(notify: Boolean) {
        val wasOpen = connection != null
        val port = connection
        connection = null
        input = null
        output = null
        claimed.forEach { intf ->
            try {
                port?.releaseInterface(intf)
            } catch (_: Exception) {
            }
        }
        claimed = ArrayList()
        try {
            port?.close()
        } catch (_: Exception) {
        }
        outgoing.clear()
        lines.reset()
        announced = false
        logReported = false
        if (notify && wasOpen) listener.onPortClosed()
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private data class Port(
        val comm: UsbInterface?,
        val data: UsbInterface,
        val input: UsbEndpoint,
        val output: UsbEndpoint,
    )

    companion object {
        private const val TAG = "Signal"
        private const val ESPRESSIF_VID = 0x303A
        private val ACK = "${SignalProtocol.USB_ACK}\n".toByteArray(Charsets.US_ASCII)

        fun isSignalBoard(device: UsbDevice): Boolean {
            if (device.vendorId != ESPRESSIF_VID) return false
            for (index in 0 until device.interfaceCount) {
                if (device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                    return true
                }
            }
            return false
        }

        private fun findPort(device: UsbDevice): Port? {
            var comm: UsbInterface? = null
            var data: UsbInterface? = null
            for (index in 0 until device.interfaceCount) {
                val intf = device.getInterface(index)
                when (intf.interfaceClass) {
                    UsbConstants.USB_CLASS_COMM -> comm = intf
                    UsbConstants.USB_CLASS_CDC_DATA -> data = intf
                }
            }
            val dataInterface = data ?: return null
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (index in 0 until dataInterface.endpointCount) {
                val endpoint = dataInterface.getEndpoint(index)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
            }
            val inn = input ?: return null
            val out = output ?: return null
            return Port(comm, dataInterface, inn, out)
        }

        private fun setLineCoding(connection: UsbDeviceConnection, interfaceId: Int) {
            val coding = byteArrayOf(
                0x00, 0xC2.toByte(), 0x01, 0x00,
                0x00,
                0x00,
                0x08,
            )
            connection.controlTransfer(0x21, 0x20, 0, interfaceId, coding, coding.size, 200)
            connection.controlTransfer(0x21, 0x22, 0x0003, interfaceId, null, 0, 200)
        }
    }
}
