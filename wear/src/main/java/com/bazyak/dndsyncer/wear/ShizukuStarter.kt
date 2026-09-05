package com.bazyak.dndsyncer.wear

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import com.bazyak.dndsyncer.core.ShizukuShell
import com.bazyak.dndsyncer.core.Zen

/**
 * Поднимает Shizuku без участия пользователя.
 *
 * Зачем: Wear OS не включает Wi-Fi, пока часы связаны с телефоном по Bluetooth,
 * а Shizuku при загрузке пробует поднять беспроводную отладку один раз — сети
 * в этот момент нет, попытка проваливается, и повторять он не умеет.
 * Поэтому всю цепочку делаем сами: Wi-Fi → отладка → официальный intent
 * автозапуска Shizuku (moe.shizuku.privileged.api.START с токеном auth).
 */
object ShizukuStarter {

    enum class Result { ALREADY_RUNNING, STARTED, NO_WIFI, FAILED }

    fun ensureRunning(context: Context): Result {
        if (ShizukuShell.isReady()) return Result.ALREADY_RUNNING
        if (!Zen.canWriteSecure(context)) {
            Log.w(TAG, "Нет WRITE_SECURE_SETTINGS — поднять Wi-Fi и отладку нечем")
            return Result.FAILED
        }

        if (!ensureWifi(context)) return Result.NO_WIFI

        Log.d(TAG, "Включаю беспроводную отладку")
        Settings.Global.putInt(context.contentResolver, KEY_ADB_WIFI, 1)
        Thread.sleep(ADB_SETTLE_MS)

        Log.d(TAG, "Отправляю intent автозапуска")
        context.sendBroadcast(
            Intent(ACTION_START)
                .setPackage(SHIZUKU_PKG)
                .putExtra(EXTRA_AUTH, BuildConfig.SHIZUKU_AUTH),
        )

        return if (waitFor(SHIZUKU_TIMEOUT_MS) { ShizukuShell.isReady() }) {
            Log.d(TAG, "Shizuku поднялся")
            Result.STARTED
        } else {
            Log.w(TAG, "Shizuku не ответил")
            Result.FAILED
        }
    }

    /**
     * wifi_on — единственный рычаг, доступный с WRITE_SECURE_SETTINGS:
     * WifiManager.setWifiEnabled() с Android 10 закрыт для обычных приложений.
     * Если прошивка запись игнорирует, честно возвращаем NO_WIFI.
     */
    private fun ensureWifi(context: Context): Boolean {
        if (hasWifi(context)) return true

        Log.d(TAG, "Поднимаю Wi-Fi")
        Settings.Global.putInt(context.contentResolver, KEY_WIFI_ON, 1)
        return waitFor(WIFI_TIMEOUT_MS) { hasWifi(context) }
    }

    private fun hasWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private inline fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    private const val TAG = "ShizukuStarter"
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val ACTION_START = "moe.shizuku.privileged.api.START"
    private const val EXTRA_AUTH = "auth"
    private const val KEY_WIFI_ON = "wifi_on"
    private const val KEY_ADB_WIFI = "adb_wifi_enabled"
    private const val WIFI_TIMEOUT_MS = 25_000L
    private const val ADB_SETTLE_MS = 3_000L
    private const val SHIZUKU_TIMEOUT_MS = 20_000L
    private const val POLL_MS = 500L
}
