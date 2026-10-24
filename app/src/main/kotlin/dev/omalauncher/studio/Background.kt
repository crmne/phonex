package dev.omalauncher.studio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri

/**
 * Whether Phonex may keep working while it isn't on screen. Under battery optimization, Android may
 * take a background app's network away and abort its open connections (ECONNABORTED); ColorOS did so
 * about once a minute, so Codex's reply stream never finished and a turn wrote nothing. The
 * foreground service and the wakelock don't prevent it; the exemption does.
 */
object Background {
    fun allowed(context: Context) = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Android's own prompt to exempt Phonex; nothing happens if it already is. */
    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        if (allowed(context)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        runCatching { context.startActivity(intent) }.onFailure {
            // Some ROMs leave the prompt out; their battery settings list Phonex instead.
            Log.w("studio", "no battery optimization prompt", it)
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
