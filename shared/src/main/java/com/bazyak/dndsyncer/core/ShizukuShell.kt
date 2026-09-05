package com.bazyak.dndsyncer.core

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Выполнение команд с правами shell через Shizuku.
 *
 * Нужно на часах: `cmd notification set_dnd` пускает только uid shell (2000)
 * или root, а setInterruptionFilter() на OxygenOS Watch не даёт эффекта
 * вообще (проверено: mZenMode остаётся ZEN_MODE_OFF).
 */
object ShizukuShell {

    /** Сервис запущен и разрешение нашему приложению выдано. */
    fun isReady(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Сервис запущен, но разрешение ещё не выдано. */
    fun needsPermission(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(requestCode: Int) {
        runCatching { Shizuku.requestPermission(requestCode) }
            .onFailure { Log.w(TAG, "Запрос разрешения не удался", it) }
    }

    /**
     * Shizuku.newProcess помечен @hide, публичной обёртки в api нет —
     * вызываем рефлексией. Это штатный способ, им пользуется большинство
     * приложений на Shizuku.
     */
    fun exec(command: String): String? = runCatching {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }

        val process = method.invoke(
            null,
            arrayOf("sh", "-c", command),
            null,
            null,
        ) as Process

        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        process.waitFor()
        Log.d(TAG, "$command → ${output.trim()}")
        output
    }.onFailure { Log.w(TAG, "Shizuku exec не удался: $it") }.getOrNull()

    private const val TAG = "ShizukuShell"
}
