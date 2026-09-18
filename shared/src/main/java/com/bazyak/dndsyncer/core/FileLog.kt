package com.bazyak.dndsyncer.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Подробный лог в файл. logcat живёт до перезагрузки и переполняется,
 * а ловим мы редкий ночной баг — нужна запись, которая переживёт утро.
 *
 * Куда пишется, решает модуль: сюда приходит уже готовый приёмник строк.
 */
object FileLog {

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FileLog").apply { isDaemon = true }
    }

    @Volatile
    private var sink: ((String) -> Unit)? = null

    @Volatile
    var enabled: Boolean = true

    fun install(sink: (String) -> Unit) {
        this.sink = sink
        d("FileLog", "=== лог открыт ===")
    }

    fun detach() {
        d("FileLog", "=== лог закрыт ===")
        sink = null
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        write("D", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        write("W", tag, message)
    }

    /**
     * Вывод команды в лог целиком класть нельзя: `dumpsys notification` —
     * это около мегабайта за вызов, за ночь набегали сотни мегабайт.
     * В файл идёт первая строка и размер, всё остальное разбирается кодом.
     */
    fun brief(output: String): String {
        val trimmed = output.trim()
        if (trimmed.length <= BRIEF_CHARS) return trimmed.ifEmpty { "(пусто)" }
        val firstLine = trimmed.lineSequence().firstOrNull().orEmpty().take(BRIEF_CHARS)
        return "$firstLine … [всего ${trimmed.length} символов]"
    }

    /** Многострочный блок: только то, что мы сами отобрали для разбора. */
    fun block(tag: String, title: String, body: String) {
        d(tag, "$title:")
        body.lineSequence()
            .filter { it.isNotBlank() }
            .take(BLOCK_LINES)
            .forEach { write("D", tag, "    $it") }
    }

    private fun write(level: String, tag: String, message: String) {
        if (!enabled) return
        val target = sink ?: return
        val line = "${stamp.format(Date())} $level/$tag: $message\n"
        writer.execute { runCatching { target(line) } }
    }

    private const val BRIEF_CHARS = 200
    private const val BLOCK_LINES = 60
}
