package com.bazyak.dndsyncer.wear

import android.app.Application
import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.LogSettings
import com.bazyak.dndsyncer.core.Shell
import java.io.File

/**
 * На часах Downloads нет смысла — забирать всё равно через adb.
 * Пишем в папку приложения:
 *   /sdcard/Android/data/com.bazyak.dndsyncer/files/dnd-syncer-watch.log
 */
class WatchApp : Application() {

    private val logFile: File by lazy {
        File(getExternalFilesDir(null), FILE_NAME)
    }

    override fun onCreate() {
        super.onCreate()
        FileLog.enabled = LogSettings.isEnabled(this)
        FileLog.install(::append)
        FileLog.d(TAG, "Запуск процесса, привилегированный доступ: ${Shell.backend()}")
        FileLog.d(TAG, "Лог: ${logFile.absolutePath}")
    }

    private fun append(line: String) {
        runCatching {
            if (logFile.length() > MAX_BYTES) rotate()
            logFile.appendText(line)
        }
    }

    /**
     * Ротация как в linux: .log, .log.1, .log.2, .log.3.
     * Сдвигаем с конца, иначе затрём ещё не сдвинутый файл.
     */
    private fun rotate() {
        val dir = logFile.parentFile ?: return
        File(dir, "$FILE_NAME.$KEEP").delete()
        for (i in KEEP - 1 downTo 1) {
            File(dir, "$FILE_NAME.$i").renameTo(File(dir, "$FILE_NAME.${i + 1}"))
        }
        logFile.renameTo(File(dir, "$FILE_NAME.1"))
    }

    private companion object {
        const val TAG = "WatchApp"
        const val FILE_NAME = "dnd-syncer-watch.log"
        const val MAX_BYTES = 4L * 1024 * 1024
        const val KEEP = 3
    }
}
