package com.bazyak.dndsyncer.core

import android.content.Context
import android.util.Log

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
        if (isOn(context) == on) return true
        if (!Shell.isAvailable()) {
            Log.w(TAG, "Нет привилегированного доступа")
            return false
        }
        Shell.exec("cmd notification set_dnd ${if (on) "priority" else "off"}")
        return settled(context, on)
    }

    /** Системе нужно мгновение на применение, поэтому проверяем с ретраем. */
    private fun settled(context: Context, expected: Boolean): Boolean {
        repeat(RETRIES) {
            if (isOn(context) == expected) return true
            Thread.sleep(RETRY_MS)
        }
        Log.w(TAG, "DND=$expected не применился")
        return false
    }

    private const val TAG = "Dnd"
    private const val RETRIES = 6
    private const val RETRY_MS = 100L
}
