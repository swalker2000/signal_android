package com.lpkm.signal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in BOOT_ACTIONS) return

        val store = SettingsStore(context)
        val settings = store.load()
        if (!store.isReady(settings) || settings?.autostart != true) return

        try {
            TelemetryService.start(context)
        } catch (error: Exception) {
            Log.e(TAG, "Не удалось запустить службу телеметрии", error)
        }

        val userManager = context.getSystemService(UserManager::class.java)
        val unlocked = userManager?.isUserUnlocked == true
        if (!unlocked || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            context.startActivity(launch)
        } catch (error: Exception) {
            Log.w(TAG, "Система не дала открыть панель, остаётся уведомление", error)
        }
    }

    companion object {
        private const val TAG = "Signal"
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
