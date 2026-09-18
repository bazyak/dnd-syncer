package com.bazyak.dndsyncer.core

import android.content.Context

/**
 * Переключение "Не беспокоить".
 *
 * Только через shell — это единственный способ, который работает на обоих
 * устройствах. NotificationManager.setInterruptionFilter() не годится:
 * он вешает правило с enabler = наш пакет, приложение может гасить лишь
 * собственные правила, а на OnePlus Watch 4 не даёт эффекта вообще.
 * Прямая запись zen_mode тоже отпадает — система её игнорирует.
 */
object Dnd {

    fun isOn(context: Context): Boolean = Zen.zen(context.contentResolver) != 0

    fun set(context: Context, on: Boolean): Boolean {
        FileLog.d(TAG, "set(dnd=$on), сейчас: ${Zen.describe(context.contentResolver)}")
        if (isOn(context) == on) {
            FileLog.d(TAG, "уже в нужном состоянии, ничего не делаю")
            return true
        }
        if (!Shell.isAvailable()) {
            FileLog.w(TAG, "нет привилегированного доступа")
            return false
        }
        val command = "cmd notification set_dnd ${if (on) "priority" else "off"}"
        val output = Shell.exec(command)
        FileLog.d(TAG, "[${Shell.backend()}] $command → ${output?.trim()?.ifEmpty { "(пусто)" }}")
        val ok = settled(context, on)
        FileLog.d(TAG, "после: ${Zen.describe(context.contentResolver)} успех=$ok")
        return ok
    }

    /** Системе нужно мгновение на применение, поэтому проверяем с ретраем. */
    private fun settled(context: Context, expected: Boolean): Boolean {
        repeat(RETRIES) {
            if (isOn(context) == expected) return true
            Thread.sleep(RETRY_MS)
        }
        FileLog.w(TAG, "DND=$expected не применился за ${RETRIES * RETRY_MS} мс")
        return false
    }

    private const val TAG = "Dnd"
    private const val RETRIES = 6
    private const val RETRY_MS = 100L
}
