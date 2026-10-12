package com.lpkm.signal

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

class SettingsStore(context: Context) {
    private val app = context.applicationContext
    private val storage = app.createDeviceProtectedStorageContext()
    private val dir = File(storage.filesDir, "signal").apply { mkdirs() }
    private val settingsFile = File(dir, "settings.json")
    private val screensFile = File(dir, "screens.json")
    val dashboardDir: File = File(dir, "dashboards").apply { mkdirs() }

    data class AppSettings(
        val deviceAddress: String,
        val deviceName: String,
        val htmlFile: String,
        val autostart: Boolean,
        val serverUrl: String,
        val serverLogin: String,
        val serverPassword: String,
        val accessToken: String,
        val tokenExpiresAt: Long,
    )

    data class ScreenEntry(
        val id: Long,
        val name: String,
        val version: Int,
        val fileName: String,
    )

    data class IncomingScreen(
        val id: Long,
        val name: String,
        val version: Int,
        val html: String,
    )

    fun load(): AppSettings? {
        synchronized(this) {
            if (!settingsFile.exists()) return null
            return try {
                parse(JSONObject(settingsFile.readText(Charsets.UTF_8)))
            } catch (error: Exception) {
                Log.e(TAG, "Не удалось прочитать настройки", error)
                null
            }
        }
    }

    fun serverConfigured(settings: AppSettings? = load()): Boolean {
        return !settings?.serverLogin.isNullOrBlank() && !settings?.serverPassword.isNullOrBlank()
    }

    fun screenConfigured(settings: AppSettings? = load()): Boolean {
        val name = settings?.htmlFile?.takeIf { it.isNotBlank() } ?: return false
        val file = dashboardFile(name)
        return file.exists() && file.length() > 0L
    }

    fun bluetoothConfigured(settings: AppSettings? = load()): Boolean {
        return !settings?.deviceAddress.isNullOrBlank()
    }

    fun isReady(settings: AppSettings? = load()): Boolean {
        return bluetoothConfigured(settings) && screenConfigured(settings)
    }

    fun activeDashboard(): File? {
        val settings = load() ?: return null
        if (!screenConfigured(settings)) return null
        return dashboardFile(settings.htmlFile)
    }

    fun listDashboards(): List<File> {
        try {
            ensureBundledDashboards()
        } catch (error: Exception) {
            Log.e(TAG, "Не удалось подготовить стандартную панель", error)
        }
        return dashboardDir.listFiles { file ->
            file.isFile && file.name.endsWith(".html", ignoreCase = true)
        }?.sortedBy { it.name.lowercase() }.orEmpty()
    }

    fun ensureDefaultDashboard() {
        val file = dashboardFile(DEFAULT_HTML)
        if (!file.exists() || file.length() == 0L) copyAsset(DEFAULT_HTML, file)
    }

    fun ensureBundledDashboards() {
        ensureDefaultDashboard()
        val names = app.assets.list("") ?: return
        for (name in names) {
            if (!name.endsWith(".html", ignoreCase = true)) continue
            val file = dashboardFile(name)
            if (!file.exists() || file.length() == 0L) copyAsset(name, file)
        }
    }

    fun restoreDefault(): File {
        val file = dashboardFile(DEFAULT_HTML)
        copyAsset(DEFAULT_HTML, file)
        return file
    }

    fun importDashboard(bytes: ByteArray, displayName: String): File {
        val dest = dashboardFile(uniqueName(sanitize(displayName)))
        syncWrite(dest, bytes)
        return dest
    }

    fun saveBluetooth(address: String, name: String, autostart: Boolean): AppSettings {
        val trimmed = address.trim()
        return edit { json ->
            json.put("deviceAddress", trimmed)
            json.put("deviceName", name.trim().ifBlank { trimmed })
            json.put("autostart", autostart)
        }
    }

    fun saveAutostart(autostart: Boolean): AppSettings {
        return edit { json -> json.put("autostart", autostart) }
    }

    fun saveServer(url: String, login: String, password: String): AppSettings {
        val normalized = ServerUrls.normalize(url)
        val trimmedLogin = login.trim()
        return edit { json ->
            val changed = json.optString("serverUrl") != normalized ||
                json.optString("serverLogin") != trimmedLogin ||
                json.optString("serverPassword") != password
            json.put("serverUrl", normalized)
            json.put("serverLogin", trimmedLogin)
            json.put("serverPassword", password)
            if (changed) {
                json.put("accessToken", "")
                json.put("tokenExpiresAt", 0L)
            }
        }
    }

    fun saveToken(token: String, expiresInSeconds: Long): AppSettings {
        val lifetime = if (expiresInSeconds > 120) expiresInSeconds - 60 else expiresInSeconds.coerceAtLeast(0)
        return edit { json ->
            json.put("accessToken", token)
            json.put("tokenExpiresAt", System.currentTimeMillis() + lifetime * 1000)
        }
    }

    fun selectScreen(fileName: String): AppSettings {
        val file = dashboardFile(fileName)
        if (!file.exists() || file.length() == 0L) error("missing screen")
        return edit { json -> json.put("htmlFile", file.name) }
    }

    fun listScreens(): List<ScreenEntry> {
        synchronized(this) {
        if (!screensFile.exists()) return emptyList()
        return try {
            val array = org.json.JSONArray(screensFile.readText(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val entry = ScreenEntry(
                        id = item.getLong("id"),
                        name = item.optString("name").ifBlank { "Экран ${item.getLong("id")}" },
                        version = item.optInt("version", 1),
                        fileName = item.getString("fileName"),
                    )
                    val file = dashboardFile(entry.fileName)
                    if (file.exists() && file.length() > 0L) add(entry)
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Не удалось прочитать список экранов", error)
            emptyList()
        }
        }
    }

    fun replaceScreens(screens: List<IncomingScreen>): String? = synchronized(this) {
        val previousId = listScreens().find { it.fileName == load()?.htmlFile }?.id
        val entries = writeScreens(screens)
        val keep = entries.map { it.fileName }.toSet()
        dashboardDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".html", ignoreCase = true) && file.name !in keep) {
                file.delete()
            }
        }
        writeCatalog(entries)
        val selected = entries.find { it.id == previousId }?.fileName.orEmpty()
        edit { json -> json.put("htmlFile", selected) }
        return selected.ifBlank { null }
    }

    fun addMissingScreens(screens: List<IncomingScreen>): Int = synchronized(this) {
        val current = listScreens().toMutableList()
        val have = current.map { it.id }.toSet()
        val fresh = screens.filter { it.id !in have }
        if (fresh.isEmpty()) return 0
        current += writeScreens(fresh)
        writeCatalog(current.sortedBy { it.name.lowercase() })
        return fresh.size
    }

    fun dashboardFile(name: String): File {
        val safe = File(name).name
        if (!safe.endsWith(".html", ignoreCase = true) || safe.contains("..")) {
            return File(dashboardDir, DEFAULT_HTML)
        }
        return File(dashboardDir, safe)
    }

    private fun parse(json: JSONObject): AppSettings {
        return AppSettings(
            deviceAddress = json.optString("deviceAddress"),
            deviceName = json.optString("deviceName"),
            htmlFile = json.optString("htmlFile"),
            autostart = json.optBoolean("autostart", true),
            serverUrl = json.optString("serverUrl").ifBlank { DEFAULT_SERVER_URL },
            serverLogin = json.optString("serverLogin"),
            serverPassword = json.optString("serverPassword"),
            accessToken = json.optString("accessToken"),
            tokenExpiresAt = json.optLong("tokenExpiresAt"),
        )
    }

    private fun edit(block: (JSONObject) -> Unit): AppSettings {
        synchronized(this) {
            val json = readJson()
            block(json)
            syncWrite(settingsFile, json.toString(2).toByteArray(Charsets.UTF_8))
            return parse(json)
        }
    }

    private fun readJson(): JSONObject {
        if (!settingsFile.exists()) return JSONObject()
        return try {
            JSONObject(settingsFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun writeScreens(screens: List<IncomingScreen>): List<ScreenEntry> {
        return screens.map { screen ->
            val fileName = "screen-${screen.id}.html"
            syncWrite(File(dashboardDir, fileName), screen.html.toByteArray(Charsets.UTF_8))
            ScreenEntry(
                id = screen.id,
                name = screen.name.ifBlank { "Экран ${screen.id}" },
                version = screen.version,
                fileName = fileName,
            )
        }
    }

    private fun writeCatalog(entries: List<ScreenEntry>) {
        val array = org.json.JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("version", entry.version)
                    .put("fileName", entry.fileName),
            )
        }
        syncWrite(screensFile, array.toString(2).toByteArray(Charsets.UTF_8))
    }

    private fun copyAsset(name: String, dest: File) {
        val bytes = app.assets.open(name).use { it.readBytes() }
        syncWrite(dest, bytes)
    }

    private fun sanitize(name: String): String {
        val stem = File(name).name.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .take(60)
            .ifBlank { "dashboard" }
        return "$stem.html"
    }

    private fun uniqueName(name: String): String {
        if (!dashboardFile(name).exists()) return name
        val stem = name.removeSuffix(".html")
        var index = 2
        while (dashboardFile("$stem-$index.html").exists()) index += 1
        return "$stem-$index.html"
    }

    private fun syncWrite(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Не удалось заменить ${file.name}")
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "Signal"
        const val DEFAULT_HTML = "ktm_lcd.html"
        const val DEFAULT_SERVER_URL = ServerUrls.DEFAULT

        fun looksLikeHtml(bytes: ByteArray, name: String): Boolean {
            if (bytes.isEmpty() || bytes.size > 2_000_000) return false
            if (name.endsWith(".html", ignoreCase = true) || name.endsWith(".htm", ignoreCase = true)) {
                return true
            }
            val head = bytes.copyOfRange(0, minOf(bytes.size, 512))
                .toString(Charsets.UTF_8)
                .trimStart()
                .lowercase()
            return head.startsWith("<!doctype html") || head.startsWith("<html") || head.contains("<html")
        }
    }
}
