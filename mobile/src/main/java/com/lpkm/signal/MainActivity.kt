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
import android.os.SystemClock
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.lpkm.signal.databinding.ActivityMainBinding
import java.net.URI
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val store by lazy { SettingsStore(applicationContext) }
    private val server = SignalServer()
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, FoundDevice>()

    private var showingDashboard = false
    private var settingsPage = SettingsPage.MENU
    private var askedPermissions = false
    private var scanning = false
    private var settingsBusy = false
    private var ignoreAutostart = false
    private var ioToken = 0
    private var selectedAddress: String? = null
    private var selectedName: String? = null
    private var selectedScreenFile: String? = null
    private var previewStamp = Long.MIN_VALUE
    private var pageReady = false
    private var loadedStamp = Long.MIN_VALUE
    private var lastPushed: Telemetry? = null
    private var pendingTelemetry: Telemetry? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private var waitingForLocation = false
    private var pendingVehicleId: String? = null
    private var askedVehiclesAt = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (result.values.all { it }) action?.invoke()
        else Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
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

    private val onVehicles: (TelemetryBus.Vehicles) -> Unit = {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            pendingVehicleId = null
            askedVehiclesAt = 0L
            handler.removeCallbacks(vehicleWait)
            if (showingDashboard) return@runOnUiThread
            if (settingsPage == SettingsPage.MENU) refreshMenu()
            if (settingsPage == SettingsPage.BLUETOOTH) renderVehicles()
        }
    }

    private val onVehicleFailure: () -> Unit = {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            pendingVehicleId = null
            Toast.makeText(this, R.string.vehicle_failed, Toast.LENGTH_SHORT).show()
            if (!showingDashboard && settingsPage == SettingsPage.BLUETOOTH) renderVehicles()
        }
    }

    private val vehicleWait = Runnable {
        if (!isDestroyed && settingsPage == SettingsPage.BLUETOOTH) renderVehicles()
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
                if (settingsPage != SettingsPage.MENU) {
                    showSettingsPage(SettingsPage.MENU)
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

    override fun onDestroy() {
        ioToken += 1
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        TelemetryBus.addTelemetryListener(onTelemetry)
        TelemetryBus.addLinkListener(onLink)
        TelemetryBus.addVehicleListener(onVehicles)
        TelemetryBus.addVehicleFailureListener(onVehicleFailure)
        if (showingDashboard) {
            TelemetryBus.lastTelemetry?.let { applyTelemetry(it) }
        } else {
            showLink(TelemetryBus.link)
            binding.settings.telemetryPreview.text = formatTelemetry(TelemetryBus.lastTelemetry)
            refreshAutostartButton()
            if (waitingForLocation && locationEnabled() && settingsPage == SettingsPage.BLUETOOTH) {
                waitingForLocation = false
                startScan()
            }
        }
    }

    override fun onStop() {
        stopScan()
        TelemetryBus.removeTelemetryListener(onTelemetry)
        TelemetryBus.removeLinkListener(onLink)
        TelemetryBus.removeVehicleListener(onVehicles)
        TelemetryBus.removeVehicleFailureListener(onVehicleFailure)
        handler.removeCallbacks(vehicleWait)
        super.onStop()
    }

    private fun wireControls() {
        binding.dashboard.openSettings.setOnClickListener { showSettings() }
        binding.settings.menuServer.root.setOnClickListener { showSettingsPage(SettingsPage.SERVER) }
        binding.settings.menuScreens.root.setOnClickListener { showSettingsPage(SettingsPage.SCREENS) }
        binding.settings.menuBluetooth.root.setOnClickListener { showSettingsPage(SettingsPage.BLUETOOTH) }
        binding.settings.menuPanel.root.setOnClickListener { openPanel() }
        binding.settings.serverBack.setOnClickListener { showSettingsPage(SettingsPage.MENU) }
        binding.settings.screensBack.setOnClickListener { showSettingsPage(SettingsPage.MENU) }
        binding.settings.bluetoothBack.setOnClickListener { showSettingsPage(SettingsPage.MENU) }
        binding.settings.serverSave.setOnClickListener { saveServer(checkConnection = false) }
        binding.settings.serverCheck.setOnClickListener { saveServer(checkConnection = true) }
        binding.settings.screensReplace.setOnClickListener { confirmReplaceScreens() }
        binding.settings.screensMissing.setOnClickListener { downloadScreens(replace = false) }
        binding.settings.scanButton.setOnClickListener { ensurePermissions { startScan() } }
        binding.settings.autostart.setOnCheckedChangeListener { _, checked ->
            refreshAutostartButton()
            if (ignoreAutostart) return@setOnCheckedChangeListener
            val saved = store.load()
            if (saved?.deviceAddress.isNullOrBlank()) return@setOnCheckedChangeListener
            store.saveAutostart(checked)
            try {
                setHomeAlias(checked)
            } catch (error: Exception) {
                Log.w(TAG, "home alias", error)
            }
        }
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
        setupPreview()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupPreview() {
        val web = binding.settings.screenPreview
        web.setBackgroundColor(Color.parseColor("#B8BCC6"))
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
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
    }

    private fun showSettings() {
        showingDashboard = false
        stopScan()
        exitImmersive()
        binding.dashboard.root.visibility = View.GONE
        binding.settings.root.visibility = View.VISIBLE
        showSettingsPage(SettingsPage.MENU)
    }

    private fun showSettingsPage(page: SettingsPage) {
        if (page != settingsPage) {
            ioToken += 1
            settingsBusy = false
        }
        if (settingsPage == SettingsPage.BLUETOOTH && page != SettingsPage.BLUETOOTH) stopScan()
        settingsPage = page
        binding.settings.menuRoot.isVisible = page == SettingsPage.MENU
        binding.settings.serverPanel.isVisible = page == SettingsPage.SERVER
        binding.settings.screensPanel.isVisible = page == SettingsPage.SCREENS
        binding.settings.bluetoothPanel.isVisible = page == SettingsPage.BLUETOOTH
        when (page) {
            SettingsPage.MENU -> refreshMenu()
            SettingsPage.SERVER -> bindServerForm()
            SettingsPage.SCREENS -> renderScreens()
            SettingsPage.BLUETOOTH -> openBluetooth()
        }
    }

    private fun refreshMenu() {
        val saved = store.load()
        val serverStatus = if (store.serverConfigured(saved)) {
            val host = runCatching { URI(ServerUrls.normalize(saved?.serverUrl.orEmpty())).host }.getOrNull()
            listOf(saved?.serverLogin.orEmpty(), host.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            getString(R.string.menu_server_missing)
        }
        val screenStatus = if (store.screenConfigured(saved)) {
            screenLabel(saved?.htmlFile)
        } else {
            getString(R.string.menu_screen_missing)
        }
        val bluetoothStatus = if (store.bluetoothConfigured(saved)) {
            val device = saved?.deviceName?.ifBlank { saved.deviceAddress }.orEmpty()
            val vehicle = TelemetryBus.vehicles.currentName?.takeIf { it.isNotBlank() }
                ?: saved?.vehicleName?.takeIf { it.isNotBlank() }
            listOf(device, vehicle).filter { !it.isNullOrBlank() }.joinToString(" · ")
        } else {
            getString(R.string.menu_bluetooth_missing)
        }
        paintMenu(binding.settings.menuServer, R.string.menu_server, serverStatus, !store.serverConfigured(saved))
        paintMenu(binding.settings.menuScreens, R.string.menu_screens, screenStatus, !store.screenConfigured(saved))
        paintMenu(
            binding.settings.menuBluetooth,
            R.string.menu_bluetooth,
            bluetoothStatus,
            !store.bluetoothConfigured(saved),
        )
        paintMenu(binding.settings.menuPanel, R.string.menu_panel, getString(R.string.menu_panel_hint), false)
    }

    private fun paintMenu(
        card: com.lpkm.signal.databinding.ItemMenuBinding,
        title: Int,
        status: String,
        alert: Boolean,
    ) {
        card.menuTitle.setText(title)
        card.menuStatus.text = status
        val color = ContextCompat.getColor(this, if (alert) R.color.dash_alert else R.color.dash_ink)
        val statusColor = ContextCompat.getColor(this, if (alert) R.color.dash_alert else R.color.dash_muted)
        card.menuTitle.setTextColor(color)
        card.menuStatus.setTextColor(statusColor)
        card.root.setBackgroundResource(if (alert) R.drawable.bg_menu_alert else R.drawable.bg_card)
    }

    private fun screenLabel(fileName: String?): String {
        if (fileName.isNullOrBlank()) return getString(R.string.menu_screen_missing)
        val named = store.listScreens().find { it.fileName == fileName }?.name
        if (!named.isNullOrBlank()) return named
        val file = store.dashboardFile(fileName)
        return if (file.exists()) dashboardTitle(file) else fileName
    }

    private fun bindServerForm() {
        val saved = store.load()
        binding.settings.serverUrl.setText(
            saved?.serverUrl?.ifBlank { SettingsStore.DEFAULT_SERVER_URL } ?: SettingsStore.DEFAULT_SERVER_URL,
        )
        binding.settings.serverLogin.setText(saved?.serverLogin.orEmpty())
        binding.settings.serverPassword.setText(saved?.serverPassword.orEmpty())
        binding.settings.serverStatus.setText(R.string.server_status_idle)
        binding.settings.serverStatus.setTextColor(ContextCompat.getColor(this, R.color.dash_muted))
        updateBusyUi()
    }

    private fun saveServer(checkConnection: Boolean) {
        val url = binding.settings.serverUrl.text?.toString().orEmpty()
        val login = binding.settings.serverLogin.text?.toString().orEmpty()
        val password = binding.settings.serverPassword.text?.toString().orEmpty()
        if (login.isBlank() || password.isBlank()) {
            showStatus(getString(R.string.server_need_credentials), ok = false)
            return
        }
        if (!checkConnection) {
            try {
                store.saveServer(url, login, password)
                showStatus(getString(R.string.server_saved), ok = true)
            } catch (_: IllegalArgumentException) {
                showStatus(getString(R.string.server_bad_url), ok = false)
            }
            return
        }
        launchIo {
            val auth = server.authenticate(url, login, password)
            IoSuccess(getString(R.string.server_ok)) {
                store.saveServer(url, login, password)
                store.saveToken(auth.token, auth.expiresInSeconds)
            }
        }
    }

    private fun confirmReplaceScreens() {
        if (!store.serverConfigured()) {
            showStatus(getString(R.string.server_need_setup), ok = false)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.screens_replace_title)
            .setMessage(R.string.screens_replace_message)
            .setPositiveButton(R.string.screens_replace_confirm) { _, _ -> downloadScreens(replace = true) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun downloadScreens(replace: Boolean) {
        if (!store.serverConfigured()) {
            showStatus(getString(R.string.server_need_setup), ok = false)
            return
        }
        launchIo {
            val remote = fetchScreens(onlyMissing = !replace)
            val message = when {
                !replace && remote.isEmpty() -> getString(R.string.screens_none_missing)
                else -> getString(R.string.screens_loaded, remote.size)
            }
            IoSuccess(message) {
                if (replace) store.replaceScreens(remote.map { it.toIncoming() })
                else if (remote.isNotEmpty()) store.addMissingScreens(remote.map { it.toIncoming() })
            }
        }
    }

    private fun fetchScreens(onlyMissing: Boolean): List<RemoteScreen> {
        var session = openSession(force = false)
        fun <T> call(block: (Session) -> T): T {
            return try {
                block(session)
            } catch (error: ServerException) {
                if (!error.unauthorized) throw error
                session = openSession(force = true)
                block(session)
            }
        }
        val briefs = call { server.listDisplays(it.base, it.token) }
        val wanted = if (onlyMissing) {
            val have = store.listScreens().map { it.id }.toSet()
            briefs.filter { it.id !in have }
        } else {
            briefs
        }
        return wanted.map { brief ->
            call { server.downloadDisplay(it.base, it.token, brief.id) }
        }
    }

    private fun openSession(force: Boolean): Session {
        val settings = store.load()
        val login = settings?.serverLogin.orEmpty()
        val password = settings?.serverPassword.orEmpty()
        if (login.isBlank() || password.isBlank()) {
            throw ServerException(ServerException.Kind.NEED_CREDENTIALS)
        }
        val base = try {
            ServerUrls.normalize(settings?.serverUrl.orEmpty())
        } catch (_: IllegalArgumentException) {
            throw ServerException(ServerException.Kind.BAD_URL)
        }
        val token = settings?.accessToken.orEmpty()
        val expiresAt = settings?.tokenExpiresAt ?: 0L
        if (!force && token.isNotBlank() && expiresAt > System.currentTimeMillis()) {
            return Session(base, token)
        }
        val auth = server.authenticate(base, login, password)
        store.saveToken(auth.token, auth.expiresInSeconds)
        return Session(base, auth.token)
    }

    private fun launchIo(block: () -> IoSuccess) {
        if (settingsBusy || isDestroyed) return
        val token = ++ioToken
        settingsBusy = true
        updateBusyUi()
        showStatus(getString(R.string.loading), ok = true)
        io.execute {
            val outcome = try {
                IoOutcome.Ok(block())
            } catch (error: Exception) {
                Log.e(TAG, "server", error)
                val message = if (error is ServerException) error.text() else getString(R.string.server_failed)
                IoOutcome.Fail(message)
            }
            runOnUiThread {
                if (token != ioToken || isDestroyed) return@runOnUiThread
                settingsBusy = false
                updateBusyUi()
                when (outcome) {
                    is IoOutcome.Ok -> {
                        outcome.result.commit()
                        if (settingsPage == SettingsPage.SCREENS) renderScreens()
                        if (outcome.result.message.isNotEmpty()) showStatus(outcome.result.message, ok = true)
                    }
                    is IoOutcome.Fail -> showStatus(outcome.message, ok = false)
                }
            }
        }
    }

    private fun updateBusyUi() {
        val enabled = !settingsBusy
        binding.settings.serverSave.isEnabled = enabled
        binding.settings.serverCheck.isEnabled = enabled
        binding.settings.screensReplace.isEnabled = enabled
        binding.settings.screensMissing.isEnabled = enabled
    }

    private fun showStatus(message: String, ok: Boolean) {
        val view = when (settingsPage) {
            SettingsPage.SERVER -> binding.settings.serverStatus
            SettingsPage.SCREENS -> binding.settings.screensStatus
            else -> null
        }
        view?.text = message
        view?.setTextColor(ContextCompat.getColor(this, if (ok) R.color.dash_accent else R.color.dash_alert))
        if (!ok) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun ServerException.text(): String {
        return when (kind) {
            ServerException.Kind.BAD_URL -> getString(R.string.server_bad_url)
            ServerException.Kind.NEED_CREDENTIALS -> getString(R.string.server_need_setup)
            ServerException.Kind.UNREACHABLE -> getString(R.string.server_failed)
            ServerException.Kind.BAD_RESPONSE -> getString(R.string.server_bad_response)
            ServerException.Kind.HTTP -> serverMessage ?: getString(R.string.server_failed)
        }
    }

    private fun RemoteScreen.toIncoming(): SettingsStore.IncomingScreen {
        return SettingsStore.IncomingScreen(id = id, name = name, version = version, html = html)
    }

    private fun openPanel() {
        val saved = store.load()
        if (!store.screenConfigured(saved)) {
            Toast.makeText(this, R.string.panel_need_screen, Toast.LENGTH_SHORT).show()
            return
        }
        if (!store.bluetoothConfigured(saved)) {
            Toast.makeText(this, R.string.panel_need_bluetooth, Toast.LENGTH_SHORT).show()
            return
        }
        ensurePermissions {
            try {
                setHomeAlias(saved?.autostart ?: true)
            } catch (error: Exception) {
                Log.w(TAG, "home alias", error)
            }
            TelemetryService.start(this, saved?.deviceAddress, saved?.deviceName)
            showDashboard(forceReload = true)
        }
    }

    private fun openBluetooth() {
        loadDraft()
        includeKnownDevices()
        if (selectedAddress.isNullOrBlank()) {
            devices.values.firstOrNull { it.signal }?.let { known ->
                selectedAddress = known.address
                selectedName = known.name
            }
        }
        renderDevices()
        renderVehicles()
        updateSelectionText()
        showLink(TelemetryBus.link)
        binding.settings.telemetryPreview.text = formatTelemetry(TelemetryBus.lastTelemetry)
        refreshAutostartButton()
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
        ignoreAutostart = true
        binding.settings.autostart.isChecked = saved?.autostart ?: true
        ignoreAutostart = false
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
                val switching = device.address != selectedAddress
                selectedAddress = device.address
                selectedName = device.name
                store.saveBluetooth(device.address, device.name, binding.settings.autostart.isChecked)
                if (switching) {
                    pendingVehicleId = null
                    askedVehiclesAt = 0L
                    handler.removeCallbacks(vehicleWait)
                    TelemetryBus.publishVehicles(TelemetryBus.Vehicles())
                }
                try {
                    setHomeAlias(binding.settings.autostart.isChecked)
                } catch (error: Exception) {
                    Log.w(TAG, "home alias", error)
                }
                renderDevices()
                renderVehicles()
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

    private fun renderScreens() {
        val screens = shownScreens()
        val savedName = store.load()?.htmlFile
        if (selectedScreenFile == null || screens.none { it.fileName == selectedScreenFile }) {
            selectedScreenFile = savedName?.takeIf { name -> screens.any { it.fileName == name } }
        }
        val list = binding.settings.screenList
        list.removeAllViews()
        binding.settings.screensEmpty.isVisible = screens.isEmpty()
        for (screen in screens) {
            val subtitle = if (screen.version > 0) {
                getString(R.string.screens_version, screen.version)
            } else {
                getString(R.string.screens_local)
            }
            addChoice(list, screen.name, subtitle, screen.fileName == selectedScreenFile) {
                selectedScreenFile = screen.fileName
                try {
                    store.selectScreen(screen.fileName)
                } catch (error: Exception) {
                    Log.e(TAG, "select screen", error)
                    Toast.makeText(this, R.string.html_failed, Toast.LENGTH_LONG).show()
                }
                renderScreens()
            }
        }
        showPreview(selectedScreenFile)
        updateBusyUi()
    }

    private fun shownScreens(): List<SettingsStore.ScreenEntry> {
        val catalog = store.listScreens()
        val active = store.load()?.htmlFile.orEmpty()
        if (active.isBlank() || catalog.any { it.fileName == active }) return catalog
        val file = store.dashboardFile(active)
        if (!file.exists() || file.length() == 0L) return catalog
        return catalog + SettingsStore.ScreenEntry(
            id = LOCAL_SCREEN_ID,
            name = dashboardTitle(file),
            version = 0,
            fileName = active,
        )
    }

    private fun showPreview(fileName: String?) {
        val file = fileName?.let { store.dashboardFile(it) }?.takeIf { it.exists() && it.length() > 0L }
        if (file == null) {
            previewStamp = Long.MIN_VALUE
            binding.settings.screenPreviewEmpty.isVisible = true
            binding.settings.screenPreview.loadUrl("about:blank")
            return
        }
        val stamp = file.lastModified() xor file.length() xor file.name.hashCode().toLong()
        if (stamp == previewStamp) return
        val html = try {
            file.readText(Charsets.UTF_8)
        } catch (error: Exception) {
            Log.e(TAG, "preview", error)
            previewStamp = Long.MIN_VALUE
            binding.settings.screenPreviewEmpty.isVisible = true
            return
        }
        previewStamp = stamp
        binding.settings.screenPreviewEmpty.isVisible = false
        binding.settings.screenPreview.loadDataWithBaseURL(
            DASHBOARD_ORIGIN,
            html,
            "text/html",
            "utf-8",
            null,
        )
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
        if (settingsPage != SettingsPage.BLUETOOTH) return
        if (link.status == TelemetryBus.Link.Status.CONNECTED) {
            TelemetryService.requestVehicles(this)
            armVehicleWait()
        }
        renderVehicles()
    }

    private fun armVehicleWait() {
        if (TelemetryBus.vehicles.choices.isNotEmpty()) return
        if (askedVehiclesAt == 0L) askedVehiclesAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(vehicleWait)
        handler.postDelayed(vehicleWait, 2_600L)
    }

    private fun renderVehicles() {
        if (isDestroyed) return
        val state = TelemetryBus.vehicles
        val connected = TelemetryBus.link.status == TelemetryBus.Link.Status.CONNECTED
        val list = binding.settings.vehicleList
        list.removeAllViews()
        val hasChoices = state.choices.isNotEmpty()
        binding.settings.vehicleScroll.isVisible = hasChoices
        binding.settings.vehicleStatus.isVisible = !hasChoices
        if (!hasChoices) {
            val timedOut = connected && askedVehiclesAt != 0L &&
                SystemClock.elapsedRealtime() - askedVehiclesAt >= 2_500L
            binding.settings.vehicleStatus.setText(
                when {
                    state.loaded || timedOut -> R.string.vehicle_unsupported
                    connected -> R.string.vehicle_loading
                    else -> R.string.vehicle_need_link
                },
            )
            return
        }
        val selectedId = pendingVehicleId ?: state.currentId
        for (vehicle in state.choices) {
            addChoice(list, vehicle.name, "", vehicle.id == selectedId) {
                if (TelemetryBus.link.status != TelemetryBus.Link.Status.CONNECTED) {
                    Toast.makeText(this, R.string.vehicle_need_link, Toast.LENGTH_SHORT).show()
                    return@addChoice
                }
                if (vehicle.id == state.currentId) return@addChoice
                pendingVehicleId = vehicle.id
                renderVehicles()
                TelemetryService.selectVehicle(this, vehicle.id)
            }
        }
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

    private data class Session(val base: String, val token: String)

    private class IoSuccess(val message: String, val commit: () -> Unit = {})

    private sealed class IoOutcome {
        data class Ok(val result: IoSuccess) : IoOutcome()
        data class Fail(val message: String) : IoOutcome()
    }

    private enum class SettingsPage {
        MENU,
        SERVER,
        SCREENS,
        BLUETOOTH,
    }

    companion object {
        private const val TAG = "Signal"
        private const val DASHBOARD_ORIGIN = "https://signal.local/dashboard/"
        private const val LOCAL_SCREEN_ID = -1L

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
