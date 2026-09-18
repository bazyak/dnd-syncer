package com.bazyak.dndsyncer.core

import java.io.BufferedReader
import java.io.InputStreamReader

/** Выполнение команд от рута. Используется на телефоне. */
object RootShell {

    @Volatile
    private var available: Boolean? = null

    fun isAvailable(): Boolean = available ?: run {
        val ok = exec("id").let { it != null && it.contains("uid=0") }
        available = ok
        ok
    }

    /** Возвращает вывод команды или null, если рут недоступен. */
    fun exec(command: String): String? = execAs(null, command)

    /**
     * Выполнение от конкретного uid. Нужно для uid 1000 (система): часть
     * проверок в системных сервисах смотрит именно на uid вызывающего,
     * и от root они проходят не всегда, а от системы проходят.
     */
    fun execAs(uid: Int?, command: String): String? = runCatching {
        val argv = if (uid == null) {
            arrayOf("su", "-c", command)
        } else {
            arrayOf("su", uid.toString(), "-c", command)
        }
        val process = ProcessBuilder(*argv)
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        process.waitFor()
        FileLog.d(TAG, "su${if (uid == null) "" else " $uid"}: $command → ${FileLog.brief(output)}")
        output
    }.onFailure { FileLog.w(TAG, "su недоступен: $it") }.getOrNull()

    private const val TAG = "RootShell"
}
