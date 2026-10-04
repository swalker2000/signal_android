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
    val dashboardDir: File = File(dir, "dashboards").apply { mkdirs() }

    data class AppSettings(
        val deviceAddress: String,
        val deviceName: String,
        val htmlFile: String,
        val autostart: Boolean,
    )

    fun load(): AppSettings? {
        if (!settingsFile.exists()) return null
        return try {
            val json = JSONObject(settingsFile.readText(Charsets.UTF_8))
            AppSettings(
                deviceAddress = json.optString("deviceAddress"),
                deviceName = json.optString("deviceName"),
                htmlFile = json.optString("htmlFile", DEFAULT_HTML).ifBlank { DEFAULT_HTML },
                autostart = json.optBoolean("autostart", true),
            )
        } catch (error: Exception) {
            Log.e(TAG, "Не удалось прочитать настройки", error)
            null
        }
    }

    fun isReady(settings: AppSettings? = load()): Boolean {
        if (settings == null || settings.deviceAddress.isBlank()) return false
        val file = dashboardFile(settings.htmlFile)
        return file.exists() && file.length() > 0L
    }

    fun activeDashboard(): File? {
        val settings = load() ?: return null
        return dashboardFile(settings.htmlFile).takeIf { it.exists() && it.length() > 0L }
    }

    fun listDashboards(): List<File> {
        try {
            ensureDefaultDashboard()
        } catch (error: Exception) {
            Log.e(TAG, "Не удалось подготовить стандартную панель", error)
        }
        return dashboardDir.listFiles { file ->
            file.isFile && file.name.endsWith(".html", ignoreCase = true)
        }?.sortedBy { it.name.lowercase() }.orEmpty()
    }

    fun ensureDefaultDashboard() {
        val file = dashboardFile(DEFAULT_HTML)
        if (!file.exists() || file.length() == 0L) copyAsset(file)
    }

    fun restoreDefault(): File {
        val file = dashboardFile(DEFAULT_HTML)
        copyAsset(file)
        return file
    }

    fun importDashboard(bytes: ByteArray, displayName: String): File {
        val dest = dashboardFile(uniqueName(sanitize(displayName)))
        syncWrite(dest, bytes)
        return dest
    }

    fun save(address: String, name: String, htmlFile: String, autostart: Boolean): AppSettings {
        val settings = AppSettings(
            deviceAddress = address.trim(),
            deviceName = name.trim().ifBlank { address.trim() },
            htmlFile = File(htmlFile).name,
            autostart = autostart,
        )
        val json = JSONObject()
            .put("deviceAddress", settings.deviceAddress)
            .put("deviceName", settings.deviceName)
            .put("htmlFile", settings.htmlFile)
            .put("autostart", settings.autostart)
        syncWrite(settingsFile, json.toString(2).toByteArray(Charsets.UTF_8))
        return settings
    }

    fun dashboardFile(name: String): File {
        val safe = File(name).name
        if (!safe.endsWith(".html", ignoreCase = true) || safe.contains("..")) {
            return File(dashboardDir, DEFAULT_HTML)
        }
        return File(dashboardDir, safe)
    }

    private fun copyAsset(dest: File) {
        val bytes = app.assets.open(DEFAULT_HTML).use { it.readBytes() }
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
