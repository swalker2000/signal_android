package com.lpkm.signal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.lpkm.signal.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    private val store by lazy { SettingsStore(applicationContext) }
    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, FoundDevice>()

    private var showingDashboard = false
    private var onFormStep = false
    private var askedPermissions = false
    private var scanning = false
    private var selectedAddress: String? = null
    private var selectedName: String? = null
    private var selectedHtml: String? = null
    private var pageReady = false
    private var loadedStamp = Long.MIN_VALUE
    private var lastPushed: Telemetry? = null
    private var pendingTelemetry: Telemetry? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private var waitingForLocation = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (result.values.all { it }) action?.invoke()
        else Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
    }

    private val htmlPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importHtml(uri)
    }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) startScan()
    }

    private val scanTimeout = Runnable {
        if (!isDestroyed) stopScan()
    }

    private val onTelemetry: (Telemetry) -> Unit = { sample ->
        runOnUiThread { applyTelemetry(sample) }
    }

    private val onLink: (TelemetryBus.Link) -> Unit = { link ->
        runOnUiThread {
            if (!showingDashboard) showLink(link)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val record = result.scanRecord
            val advertised = record?.deviceName ?: device.name
            val signal = advertised?.contains("signal", ignoreCase = true) == true ||
                record?.serviceUuids?.any { it.uuid == SignalBle.SERVICE_UUID } == true
            val label = advertised?.takeIf { it.isNotBlank() } ?: getString(R.string.unnamed_device)
            handler.post {
                devices[device.address] = FoundDevice(device.address, label, signal)
                renderDevices()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                binding.settings.scanButton.setText(R.string.scan)
                binding.settings.linkStatus.text = getString(R.string.scan_failed, errorCode)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        setupWeb()
        wireControls()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingDashboard) return
                if (onFormStep) {
                    showBluetoothStep()
                    return
                }
                if (store.isReady()) showDashboard(forceReload = false) else finish()
            }
        })

        if (store.isReady()) {
            TelemetryService.start(this)
            showDashboard(forceReload = false)
        } else {
            showSettings()
        }
    }

    override fun onStart() {
        super.onStart()
        TelemetryBus.addTelemetryListener(onTelemetry)
        TelemetryBus.addLinkListener(onLink)
        if (showingDashboard) {
            TelemetryBus.lastTelemetry?.let { applyTelemetry(it) }
        } else {
            showLink(TelemetryBus.link)
            binding.settings.telemetryPreview.text = formatTelemetry(TelemetryBus.lastTelemetry)
            refreshAutostartButton()
            if (waitingForLocation && locationEnabled()) {
                waitingForLocation = false
                startScan()
            }
        }
    }

    override fun onStop() {
        stopScan()
        TelemetryBus.removeTelemetryListener(onTelemetry)
        TelemetryBus.removeLinkListener(onLink)
        super.onStop()
    }

    private fun wireControls() {
        binding.dashboard.openSettings.setOnClickListener { showSettings() }
        binding.settings.scanButton.setOnClickListener { ensurePermissions { startScan() } }
        binding.settings.loadHtml.setOnClickListener { htmlPicker.launch(arrayOf("*/*")) }
        binding.settings.resetHtml.setOnClickListener { restoreDefaultHtml() }
        binding.settings.nextButton.setOnClickListener { showFormStep() }
        binding.settings.saveSettings.setOnClickListener { saveAndShow() }
        binding.settings.autostart.setOnCheckedChangeListener { _, _ -> refreshAutostartButton() }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.settings.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left + dp(16), bars.top + dp(16), bars.right + dp(16), bars.bottom + dp(16))
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.dashboard.openSettings) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.topMargin = cutout.top + dp(12)
            params.marginEnd = cutout.right + dp(12)
            view.layoutParams = params
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val web = binding.dashboard.dashboardWeb
        web.setBackgroundColor(Color.parseColor("#B8BCC6"))
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = false
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                val sample = pendingTelemetry ?: TelemetryBus.lastTelemetry
                pendingTelemetry = null
                lastPushed = null
                if (sample != null) applyTelemetry(sample)
            }
        }
    }

    private fun showSettings() {
        showingDashboard = false
        exitImmersive()
        binding.dashboard.root.visibility = View.GONE
        binding.settings.root.visibility = View.VISIBLE
        selectedAddress = null
        selectedName = null
        selectedHtml = null
        devices.clear()
        loadDraft()
        includeKnownDevices()
        if (selectedAddress.isNullOrBlank()) {
            devices.values.firstOrNull { it.signal }?.let { known ->
                selectedAddress = known.address
                selectedName = known.name
            }
        }
        renderDevices()
        renderHtml()
        updateSelectionText()
        showLink(TelemetryBus.link)
        binding.settings.telemetryPreview.text = formatTelemetry(TelemetryBus.lastTelemetry)
        refreshAutostartButton()
        showBluetoothStep()
        val knownAddress = selectedAddress
        val knownName = selectedName
        if (!askedPermissions) {
            askedPermissions = true
            ensurePermissions {
                if (!knownAddress.isNullOrBlank()) {
                    TelemetryService.start(this, knownAddress, knownName)
                }
                startScan()
            }
        } else if (!knownAddress.isNullOrBlank()) {
            ensurePermissions { TelemetryService.start(this, knownAddress, knownName) }
        }
    }

    private fun showDashboard(forceReload: Boolean) {
        val file = store.activeDashboard()
        if (file == null) {
            showSettings()
            return
        }
        showingDashboard = true
        binding.settings.root.visibility = View.GONE
        binding.dashboard.root.visibility = View.VISIBLE
        enterImmersive()
        loadDashboard(forceReload, file)
    }

    private fun loadDraft() {
        val saved = store.load()
        selectedAddress = saved?.deviceAddress?.takeIf { it.isNotBlank() }
        selectedName = saved?.deviceName?.takeIf { it.isNotBlank() }
        selectedHtml = saved?.htmlFile ?: SettingsStore.DEFAULT_HTML
        binding.settings.autostart.isChecked = saved?.autostart ?: true
    }

    private fun seedSelectedDevice() {
        val address = selectedAddress ?: return
        if (devices.containsKey(address)) return
        devices[address] = FoundDevice(
            address = address,
            name = selectedName ?: address,
            signal = true,
        )
    }

    @SuppressLint("MissingPermission")
    private fun includeKnownDevices() {
        seedSelectedDevice()
        val adapter = bluetoothAdapter() ?: return
        if (!adapter.isEnabled) return
        val bonded = try {
            adapter.bondedDevices
        } catch (_: Exception) {
            emptySet()
        }
        for (device in bonded) {
            val name = device.name?.takeIf { it.isNotBlank() } ?: getString(R.string.unnamed_device)
            val signal = name.contains("signal", ignoreCase = true)
            if (!signal && device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) continue
            val existing = devices[device.address]
            if (existing == null || (signal && !existing.signal)) {
                devices[device.address] = FoundDevice(device.address, name, signal || existing?.signal == true)
            }
        }
    }

    private fun renderDevices() {
        val list = binding.settings.deviceList
        list.removeAllViews()
        val rows = devices.values.sortedWith(
            compareByDescending<FoundDevice> { it.signal }.thenBy { it.name.lowercase() },
        )
        binding.settings.emptyDevices.isVisible = rows.isEmpty()
        for (device in rows) {
            val subtitle = buildString {
                append(device.address)
                if (device.signal) {
                    append(" · ")
                    append(getString(R.string.signal_mark))
                }
            }
            addChoice(list, device.name, subtitle, device.address == selectedAddress) {
                selectedAddress = device.address
                selectedName = device.name
                renderDevices()
                updateSelectionText()
                ensurePermissions {
                    TelemetryService.start(this, device.address, device.name)
                }
            }
        }
    }

    private fun dashboardTitle(file: java.io.File): String {
        return try {
            val head = file.inputStream().use { input ->
                val buf = ByteArray(1024)
                val count = input.read(buf)
                if (count <= 0) return file.name
                String(buf, 0, count, Charsets.UTF_8)
            }
            Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE)
                .find(head)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.ifBlank { file.name }
                ?: file.name
        } catch (_: Exception) {
            file.name
        }
    }

    private fun renderHtml() {
        val list = binding.settings.htmlList
        list.removeAllViews()
        val files = store.listDashboards()
        if (selectedHtml == null || files.none { it.name == selectedHtml }) {
            selectedHtml = files.firstOrNull { it.name == SettingsStore.DEFAULT_HTML }?.name
                ?: files.firstOrNull()?.name
        }
        for (file in files) {
            val subtitle = if (file.name == SettingsStore.DEFAULT_HTML) {
                getString(R.string.html_builtin)
            } else {
                ""
            }
            addChoice(list, dashboardTitle(file), subtitle, file.name == selectedHtml) {
                selectedHtml = file.name
                renderHtml()
            }
        }
    }

    private fun addChoice(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        val row = layoutInflater.inflate(R.layout.item_choice, parent, false)
        row.findViewById<TextView>(R.id.choice_title).text = title
        val subtitleView = row.findViewById<TextView>(R.id.choice_subtitle)
        if (subtitle.isBlank()) {
            subtitleView.visibility = View.GONE
        } else {
            subtitleView.visibility = View.VISIBLE
            subtitleView.text = subtitle
        }
        row.isSelected = selected
        row.setOnClickListener { onClick() }
        parent.addView(row)
    }

    private fun updateSelectionText() {
        val address = selectedAddress
        binding.settings.selectedDevice.text = if (address.isNullOrBlank()) {
            getString(R.string.link_idle)
        } else {
            getString(R.string.selected_device, selectedName ?: address)
        }
        val hasDevice = !address.isNullOrBlank()
        binding.settings.nextButton.isEnabled = hasDevice
        binding.settings.saveSettings.isEnabled = hasDevice
    }

    private fun showBluetoothStep() {
        onFormStep = false
        binding.settings.bluetoothPanel.isVisible = true
        binding.settings.htmlPanel.isVisible = false
        binding.settings.nextButton.isVisible = true
        binding.settings.saveSettings.isVisible = false
    }

    private fun showFormStep() {
        if (selectedAddress.isNullOrBlank()) {
            Toast.makeText(this, R.string.save_need_device, Toast.LENGTH_SHORT).show()
            return
        }
        onFormStep = true
        binding.settings.bluetoothPanel.isVisible = false
        binding.settings.htmlPanel.isVisible = true
        binding.settings.nextButton.isVisible = false
        binding.settings.saveSettings.isVisible = true
    }

    private fun restoreDefaultHtml() {
        try {
            val file = store.restoreDefault()
            selectedHtml = file.name
            renderHtml()
        } catch (error: Exception) {
            Log.e(TAG, "restore html", error)
            Toast.makeText(this, R.string.html_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun importHtml(uri: Uri) {
        try {
            val name = queryDisplayName(uri)
            val size = querySize(uri)
            if (size > 2_000_000) {
                Toast.makeText(this, R.string.html_invalid, Toast.LENGTH_LONG).show()
                return
            }
            val bytes = readLimited(uri)
            if (!SettingsStore.looksLikeHtml(bytes, name)) {
                Toast.makeText(this, R.string.html_invalid, Toast.LENGTH_LONG).show()
                return
            }
            val file = store.importDashboard(bytes, name)
            selectedHtml = file.name
            renderHtml()
        } catch (error: Exception) {
            Log.e(TAG, "import html", error)
            Toast.makeText(this, R.string.html_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveAndShow() {
        val address = selectedAddress
        if (address.isNullOrBlank()) {
            Toast.makeText(this, R.string.save_need_device, Toast.LENGTH_SHORT).show()
            return
        }
        val html = selectedHtml
        if (html.isNullOrBlank() || !store.dashboardFile(html).exists()) {
            Toast.makeText(this, R.string.save_need_html, Toast.LENGTH_SHORT).show()
            return
        }
        ensurePermissions {
            val settings = store.save(
                address = address,
                name = selectedName ?: address,
                htmlFile = html,
                autostart = binding.settings.autostart.isChecked,
            )
            try {
                setHomeAlias(settings.autostart)
            } catch (error: Exception) {
                Log.w(TAG, "home alias", error)
            }
            TelemetryService.start(this, settings.deviceAddress, settings.deviceName)
            showDashboard(forceReload = true)
        }
    }

    private fun loadDashboard(force: Boolean, file: java.io.File) {
        val stamp = file.lastModified() xor file.length()
        if (!force && pageReady && stamp == loadedStamp) {
            TelemetryBus.lastTelemetry?.let { applyTelemetry(it) }
            return
        }
        val html = try {
            file.readText(Charsets.UTF_8)
        } catch (error: Exception) {
            Log.e(TAG, "read html", error)
            Toast.makeText(this, R.string.html_failed, Toast.LENGTH_LONG).show()
            showSettings()
            return
        }
        pageReady = false
        loadedStamp = stamp
        lastPushed = null
        binding.dashboard.dashboardWeb.loadDataWithBaseURL(
            DASHBOARD_ORIGIN,
            html,
            "text/html",
            "utf-8",
            null,
        )
    }

    private fun applyTelemetry(sample: Telemetry) {
        if (!showingDashboard) {
            binding.settings.telemetryPreview.text = formatTelemetry(sample)
            return
        }
        if (!pageReady) {
            pendingTelemetry = sample
            return
        }
        if (sample == lastPushed) return
        lastPushed = sample
        binding.dashboard.dashboardWeb.evaluateJavascript(telemetryScript(sample), null)
    }

    private fun telemetryScript(sample: Telemetry): String {
        val gear = sample.gear?.let { value ->
            "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
        } ?: "null"
        return """
            (function(){
              var data = {rpm:${jsNumber(sample.rpm)},speed:${jsNumber(sample.speed)},gear:$gear,temp:${jsNumber(sample.temp)},fuel:${jsNumber(sample.fuel)},lights:${jsInt(sample.lights)},turnLeft:${jsBool(sample.turnLeft)},turnRight:${jsBool(sample.turnRight)}};
              if (typeof window.onSignalData === 'function') { window.onSignalData(data); return; }
              if (typeof updateRpm === 'function' && data.rpm != null) updateRpm(data.rpm);
              if (typeof updateMetrics === 'function') updateMetrics(data.speed, data.gear, data.temp, data.fuel);
            })();
        """.trimIndent()
    }

    private fun formatTelemetry(sample: Telemetry?): String {
        if (sample == null) return getString(R.string.no_telemetry)
        return getString(
            R.string.telemetry_line,
            sample.rpm?.toInt()?.toString() ?: "—",
            sample.speed?.toInt()?.toString() ?: "—",
            sample.gear ?: "—",
            sample.temp?.toInt()?.let { "$it°C" } ?: "—",
            sample.fuel?.toInt()?.let { "$it%" } ?: "—",
        )
    }

    private fun showLink(link: TelemetryBus.Link) {
        binding.settings.linkStatus.text = link.message.ifBlank { getString(R.string.link_idle) }
    }

    private fun refreshAutostartButton() {
        val button = binding.settings.autostartAction
        if (!binding.settings.autostart.isChecked) {
            button.isEnabled = false
            button.setText(R.string.autostart_off)
            button.setOnClickListener(null)
            return
        }
        val next = nextAutostartIntent()
        if (next == null) {
            button.isEnabled = false
            button.setText(R.string.autostart_ready)
            button.setOnClickListener(null)
            return
        }
        button.isEnabled = true
        button.setText(
            when (next.action) {
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> R.string.autostart_battery
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT -> R.string.autostart_fullscreen
                else -> R.string.autostart_home
            },
        )
        button.setOnClickListener {
            try {
                startActivity(next)
            } catch (error: Exception) {
                Log.w(TAG, "autostart settings", error)
                Toast.makeText(this, R.string.autostart_ready, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun nextAutostartIntent(): Intent? {
        val power = getSystemService(PowerManager::class.java)
        if (power != null && !power.isIgnoringBatteryOptimizations(packageName)) {
            return Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
        }
        val notifications = getSystemService(android.app.NotificationManager::class.java)
        if (notifications != null && !notifications.canUseFullScreenIntent()) {
            return Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:$packageName"),
            )
        }
        if (!isDefaultHome()) return Intent(Settings.ACTION_HOME_SETTINGS)
        return null
    }

    private fun isDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return info?.activityInfo?.packageName == packageName
    }

    private fun setHomeAlias(enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        packageManager.setComponentEnabledSetting(
            ComponentName(this, "$packageName.HomeAlias"),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasScanPermission()) return
        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            binding.settings.linkStatus.setText(R.string.bt_disabled)
            try {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (error: Exception) {
                Log.w(TAG, "enable bluetooth", error)
            }
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return
        if (!locationEnabled()) {
            waitingForLocation = true
            includeKnownDevices()
            renderDevices()
            binding.settings.linkStatus.setText(R.string.location_needed)
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (error: Exception) {
                Log.w(TAG, "location settings", error)
            }
            return
        }
        waitingForLocation = false
        stopScan()
        devices.clear()
        includeKnownDevices()
        renderDevices()
        scanning = true
        binding.settings.scanButton.setText(R.string.scanning)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
            handler.postDelayed(scanTimeout, 12_000L)
        } catch (error: Exception) {
            scanning = false
            binding.settings.scanButton.setText(R.string.scan)
            Log.w(TAG, "startScan", error)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        if (::binding.isInitialized) binding.settings.scanButton.setText(R.string.scan)
        try {
            bluetoothAdapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private fun ensurePermissions(action: () -> Unit) {
        val missing = requiredPermissions().filter { !hasPermission(it) }
        if (missing.isEmpty()) {
            action()
            return
        }
        pendingPermissionAction = action
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(android.Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requiredPermissions(): Array<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(android.Manifest.permission.BLUETOOTH_SCAN)
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        return getSystemService(BluetoothManager::class.java)?.adapter
    }

    private fun locationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val manager = getSystemService(LocationManager::class.java) ?: return false
        return manager.isLocationEnabled
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
        return "dashboard.html"
    }

    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return -1L
    }

    private fun readLimited(uri: Uri): ByteArray {
        contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                total += count
                if (total > 2_000_000) error("html too large")
                output.write(chunk, 0, count)
            }
            return output.toByteArray()
        }
        error("empty html")
    }

    private fun enterImmersive() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun exitImmersive() {
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class FoundDevice(val address: String, val name: String, val signal: Boolean)

    companion object {
        private const val TAG = "Signal"
        private const val DASHBOARD_ORIGIN = "https://signal.local/dashboard/"

        private fun jsNumber(value: Double?): String {
            if (value == null || value.isNaN() || value.isInfinite()) return "null"
            return value.toString()
        }

        private fun jsInt(value: Int?): String = value?.toString() ?: "null"

        private fun jsBool(value: Boolean?): String = when (value) {
            null -> "null"
            true -> "true"
            false -> "false"
        }
    }
}
