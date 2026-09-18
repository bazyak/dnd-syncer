package com.bazyak.dndsyncer.wear

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.ShizukuShell
import com.bazyak.dndsyncer.core.Zen

/**
 * Поднимает Shizuku без участия пользователя.
 *
 * Зачем: Wear OS не включает Wi-Fi, пока часы связаны с телефоном по Bluetooth,
 * а Shizuku при загрузке пробует поднять беспроводную отладку один раз — сети
 * в этот момент нет, попытка проваливается, и повторять он не умеет.
 *
 * Что мы можем и чего не можем (проверено на OnePlus Watch 4):
 *   adb_wifi_enabled — РАБОТАЕТ, запись реально включает и выключает отладку;
 *   wifi_on          — ЗЕРКАЛО, значение ложится, но Wi-Fi не поднимается.
 *                      WifiManager.setWifiEnabled() с Android 10 закрыт для
 *                      обычных приложений, обхода нет.
 *
 * Поэтому сеть мы не включаем, а ЖДЁМ — через registerNetworkCallback.
 * Как только Wi-Fi появится (пользователь зашёл в меню, часы подцепились
 * сами), цепочка доигрывается автоматически.
 */
object ShizukuStarter {

    enum class Result { ALREADY_RUNNING, STARTED, NO_WIFI, FAILED }

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Однократная попытка. NO_WIFI означает "сети нет прямо сейчас" —
     * вызывающий может показать уведомление, а ожидание всё равно уже
     * поставлено через awaitWifi().
     */
    fun ensureRunning(context: Context): Result {
        if (ShizukuShell.isReady()) {
            FileLog.d(TAG, "Shizuku уже работает")
            return Result.ALREADY_RUNNING
        }
        if (!Zen.canWriteSecure(context)) {
            FileLog.w(TAG, "нет WRITE_SECURE_SETTINGS — отладку включить нечем")
            return Result.FAILED
        }
        if (!hasWifi(context)) {
            FileLog.w(TAG, "Wi-Fi нет, включить его мы не можем — жду появления сети")
            awaitWifi(context)
            return Result.NO_WIFI
        }
        return start(context)
    }

    private fun start(context: Context): Result {
        FileLog.d(TAG, "включаю беспроводную отладку")
        val ok = Settings.Global.putInt(context.contentResolver, KEY_ADB_WIFI, 1)
        FileLog.d(TAG, "adb_wifi_enabled=1 успех=$ok")
        Thread.sleep(ADB_SETTLE_MS)

        val token = BuildConfig.SHIZUKU_AUTH
        if (token.isBlank()) {
            FileLog.w(TAG, "токен auth не задан в gradle.properties — intent отвергнут будет")
        }
        FileLog.d(TAG, "отправляю $ACTION_START")
        context.sendBroadcast(
            Intent(ACTION_START).setPackage(SHIZUKU_PKG).putExtra(EXTRA_AUTH, token),
        )

        return if (waitFor(SHIZUKU_TIMEOUT_MS) { ShizukuShell.isReady() }) {
            FileLog.d(TAG, "Shizuku поднялся")
            Result.STARTED
        } else {
            FileLog.w(TAG, "Shizuku не ответил за $SHIZUKU_TIMEOUT_MS мс")
            Result.FAILED
        }
    }

    /**
     * Ждём Wi-Fi в фоне. Колбэк ничего не расходует, поэтому висит постоянно:
     * это чинит не только загрузку, но и любой уход сети — вернулся домой,
     * сеть поднялась, Shizuku восстановился сам.
     */
    fun awaitWifi(context: Context) {
        if (callback != null) {
            FileLog.d(TAG, "ожидание Wi-Fi уже поставлено")
            return
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                FileLog.d(TAG, "Wi-Fi появился, продолжаю цепочку")
                if (ShizukuShell.isReady()) {
                    FileLog.d(TAG, "Shizuku уже работает, ничего не делаю")
                    return
                }
                Thread {
                    val result = start(context)
                    FileLog.d(TAG, "после появления Wi-Fi: $result")
                    if (result == Result.STARTED) Notify.clear(context)
                }.start()
            }

            override fun onLost(network: Network) {
                FileLog.d(TAG, "Wi-Fi пропал")
            }
        }

        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess {
                callback = cb
                FileLog.d(TAG, "ожидание Wi-Fi поставлено")
            }
            .onFailure { FileLog.w(TAG, "не удалось поставить ожидание Wi-Fi: $it") }
    }

    fun stopAwaiting(context: Context) {
        val cb = callback ?: return
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(cb)
        }
        callback = null
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
    private const val KEY_ADB_WIFI = "adb_wifi_enabled"
    private const val ADB_SETTLE_MS = 3_000L
    private const val SHIZUKU_TIMEOUT_MS = 20_000L
    private const val POLL_MS = 500L
}
