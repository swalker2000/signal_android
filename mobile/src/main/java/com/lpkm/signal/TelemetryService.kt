package com.lpkm.signal

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class TelemetryService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decoder = FrameDecoder()
    private val commands = ArrayDeque<String>()
    private var snapshot = Telemetry()
    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var currentAddress: String? = null
    private var currentName: String? = null
    private var connected = false
    private var connecting = false
    private var running = false
    private var writeBusy = false
    private var commandInFlight: String? = null
    private var notifyAttempts = 0
    private var lastBytes: ByteArray? = null
    private var lastAt = 0L
    private var lastLogAt = 0L

    private val wakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signal:telemetry")
            .apply { setReferenceCounted(false) }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post { handleConnection(gatt, status, newState) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.post {
                if (gatt != this@TelemetryService.gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(getString(R.string.no_service))
                    return@post
                }
                enableTelemetry(gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            mainHandler.post {
                if (gatt != this@TelemetryService.gatt || connected) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    markConnected()
                } else if (notifyAttempts < 5) {
                    mainHandler.postDelayed({ writeNotify(gatt) }, 300L)
                } else {
                    Log.w(TAG, "CCCD status=$status, всё равно жду телеметрию")
                    markConnected()
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            mainHandler.post {
                if (gatt != this@TelemetryService.gatt) return@post
                writeBusy = false
                mainHandler.removeCallbacks(writeTimeout)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    commandInFlight?.let { commands.addFirst(it) }
                }
                commandInFlight = null
                pumpCommands()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val copy = value.copyOf()
            mainHandler.post { ingest(copy) }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            val copy = value.copyOf()
            mainHandler.post { ingest(copy) }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val copy = value.copyOf()
            mainHandler.post { ingest(copy) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ensureChannel(this)
        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notif_waiting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (error: Exception) {
            Log.e(TAG, "Не удалось поднять службу", error)
            stopSelf()
            return START_NOT_STICKY
        }

        val saved = SettingsStore(this).load()
        val address = intent?.getStringExtra(EXTRA_ADDRESS)?.takeIf { it.isNotBlank() }
            ?: saved?.deviceAddress
        val name = intent?.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }
            ?: saved?.deviceName
            ?: address

        if (intent == null && saved?.autostart == true) {
            bringDashboardForward()
        }

        if (address.isNullOrBlank()) {
            TelemetryBus.setLink(TelemetryBus.Link.Status.DISCONNECTED, getString(R.string.link_idle))
            return START_STICKY
        }
        if (address == currentAddress && (connected || connecting)) return START_STICKY

        currentAddress = address
        currentName = name ?: address
        beginConnect()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacksAndMessages(RETRY)
        mainHandler.removeCallbacksAndMessages(TIMEOUT)
        closeGatt()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun beginConnect() {
        if (!running) return
        mainHandler.removeCallbacksAndMessages(RETRY)
        mainHandler.removeCallbacksAndMessages(TIMEOUT)
        closeGatt()
        val address = currentAddress ?: return
        val label = currentName ?: address
        connecting = true
        connected = false
        decoder.reset()
        stopSession()

        if (!hasConnectPermission()) {
            connecting = false
            TelemetryBus.setLink(TelemetryBus.Link.Status.ERROR, getString(R.string.bt_permission))
            updateNotification(getString(R.string.bt_permission))
            return
        }

        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            connecting = false
            TelemetryBus.setLink(TelemetryBus.Link.Status.ERROR, getString(R.string.bt_disabled))
            updateNotification(getString(R.string.bt_disabled))
            scheduleRetry()
            return
        }

        TelemetryBus.setLink(
            TelemetryBus.Link.Status.CONNECTING,
            getString(R.string.status_connecting, label),
        )
        updateNotification(getString(R.string.status_connecting, label))
        try {
            val device = adapter.getRemoteDevice(address)
            gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
            armTimeout()
        } catch (error: Exception) {
            Log.w(TAG, "connectGatt", error)
            connecting = false
            fail(error.message ?: getString(R.string.status_error, -1))
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnection(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (gatt != this.gatt) return
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            mainHandler.removeCallbacksAndMessages(TIMEOUT)
            val link = gatt
            mainHandler.postDelayed({
                if (link != this.gatt || connected) return@postDelayed
                if (!link.discoverServices()) fail(getString(R.string.no_service))
            }, 400L)
            return
        }
        connecting = false
        connected = false
        closeGatt()
        val message = if (status == BluetoothGatt.GATT_SUCCESS) {
            getString(R.string.status_disconnected)
        } else {
            getString(R.string.status_error, status)
        }
        TelemetryBus.setLink(
            if (status == BluetoothGatt.GATT_SUCCESS) {
                TelemetryBus.Link.Status.DISCONNECTED
            } else {
                TelemetryBus.Link.Status.ERROR
            },
            message,
        )
        updateNotification(message)
        scheduleRetry()
    }

    @SuppressLint("MissingPermission")
    private fun enableTelemetry(gatt: BluetoothGatt) {
        val service = gatt.getService(SignalBle.SERVICE_UUID)
        val tx = service?.getCharacteristic(SignalBle.TX_UUID)
        val rx = service?.getCharacteristic(SignalBle.RX_UUID)
        if (tx == null || rx == null) {
            fail(getString(R.string.no_service))
            return
        }
        txChar = tx
        rxChar = rx
        gatt.setCharacteristicNotification(tx, true)
        notifyAttempts = 0
        mainHandler.postDelayed({
            if (gatt == this.gatt && !connected) writeNotify(gatt)
        }, 200L)
    }

    @SuppressLint("MissingPermission")
    private fun writeNotify(gatt: BluetoothGatt) {
        if (gatt != this.gatt || connected) return
        val tx = txChar ?: return
        val descriptor = tx.getDescriptor(SignalBle.CCCD_UUID)
        if (descriptor == null) {
            markConnected()
            return
        }
        notifyAttempts += 1
        if (writeNotifyDescriptor(gatt, descriptor)) return
        if (notifyAttempts < 5) {
            mainHandler.postDelayed({ writeNotify(gatt) }, 300L)
        } else {
            Log.w(TAG, "writeDescriptor не принят, всё равно включаю телеметрию")
            markConnected()
        }
    }

    private fun markConnected() {
        connecting = false
        connected = true
        mainHandler.removeCallbacksAndMessages(TIMEOUT)
        mainHandler.removeCallbacksAndMessages(RETRY)
        val message = getString(R.string.status_connected, currentName ?: currentAddress.orEmpty())
        TelemetryBus.setLink(TelemetryBus.Link.Status.CONNECTED, message)
        updateNotification(message)
        enqueueCommand(SignalProtocol.CMD_TELM)
        mainHandler.removeCallbacks(pulsRunnable)
        mainHandler.postDelayed(pulsRunnable, 1_000L)
    }

    private val pulsRunnable = object : Runnable {
        override fun run() {
            if (!running || !connected) return
            enqueueCommand(SignalProtocol.CMD_PULS)
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    private val writeTimeout = Runnable {
        val command = commandInFlight
        writeBusy = false
        commandInFlight = null
        if (command != null) commands.addFirst(command)
        pumpCommands()
    }

    private val pumpRetry = Runnable { pumpCommands() }

    private fun enqueueCommand(command: String) {
        if (command == SignalProtocol.CMD_PULS && commands.lastOrNull() == SignalProtocol.CMD_PULS) return
        commands.addLast(command)
        pumpCommands()
    }

    @SuppressLint("MissingPermission")
    private fun pumpCommands() {
        if (writeBusy || !connected) return
        val link = gatt ?: return
        val rx = rxChar ?: return
        val command = commands.removeFirstOrNull() ?: return
        commandInFlight = command
        writeBusy = true
        mainHandler.postDelayed(writeTimeout, 1_200L)
        if (!writeCommand(link, rx, command.toByteArray(Charsets.US_ASCII))) {
            writeBusy = false
            commandInFlight = null
            mainHandler.removeCallbacks(writeTimeout)
            commands.addFirst(command)
            mainHandler.postDelayed(pumpRetry, 200L)
        }
    }

    private fun stopSession() {
        mainHandler.removeCallbacks(pulsRunnable)
        mainHandler.removeCallbacks(writeTimeout)
        mainHandler.removeCallbacks(pumpRetry)
        commands.clear()
        writeBusy = false
        commandInFlight = null
        notifyAttempts = 0
    }

    private fun fail(message: String) {
        connecting = false
        connected = false
        TelemetryBus.setLink(TelemetryBus.Link.Status.ERROR, message)
        updateNotification(message)
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (!running || currentAddress.isNullOrBlank()) return
        mainHandler.removeCallbacksAndMessages(RETRY)
        mainHandler.postDelayed({ beginConnect() }, RETRY, 3_000L)
    }

    private fun armTimeout() {
        mainHandler.removeCallbacksAndMessages(TIMEOUT)
        mainHandler.postDelayed({
            if (!connected && running) {
                TelemetryBus.setLink(TelemetryBus.Link.Status.ERROR, getString(R.string.connect_timeout))
                beginConnect()
            }
        }, TIMEOUT, 12_000L)
    }

    private fun ingest(value: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastBytes
        if (previous != null && previous.contentEquals(value) && now - lastAt < 30) return
        lastBytes = value
        lastAt = now

        val frames = decoder.append(value)
        if (frames.isEmpty()) return
        for ((param, sampleValue) in frames) {
            snapshot = snapshot.withFrame(param, sampleValue)
        }
        if (now - lastLogAt > 1_000) {
            lastLogAt = now
            Log.d(TAG, "telemetry rpm=${snapshot.rpm} speed=${snapshot.speed} gear=${snapshot.gear}")
            updateNotification(
                "rpm ${snapshot.rpm?.toInt() ?: "—"} · ${snapshot.speed?.toInt() ?: "—"} км/ч",
            )
        }
        TelemetryBus.publish(snapshot)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        stopSession()
        val old = gatt
        gatt = null
        txChar = null
        rxChar = null
        try {
            old?.disconnect()
        } catch (_: Exception) {
        }
        mainHandler.postDelayed({
            try {
                old?.close()
            } catch (_: Exception) {
            }
        }, 250L)
    }

    private fun bringDashboardForward() {
        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            startActivity(launch)
        } catch (error: Exception) {
            Log.w(TAG, "Не удалось вернуть панель на экран", error)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            1,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun writeNotifyDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.copyOf()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCommand(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = writeType
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val manager = getSystemService(BluetoothManager::class.java) ?: return null
        return manager.adapter
    }

    companion object {
        private const val TAG = "Signal"
        private const val CHANNEL_ID = "signal_link"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_NAME = "name"
        private val RETRY = Any()
        private val TIMEOUT = Any()

        fun ensureChannel(context: Context) {
            try {
                createChannel(context)
            } catch (error: Exception) {
                Log.e(TAG, "Не удалось создать канал уведомлений", error)
            }
        }

        private fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }

        fun start(context: Context, address: String? = null, name: String? = null) {
            val intent = Intent(context, TelemetryService::class.java)
            if (!address.isNullOrBlank()) {
                intent.putExtra(EXTRA_ADDRESS, address)
                intent.putExtra(EXTRA_NAME, name)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
