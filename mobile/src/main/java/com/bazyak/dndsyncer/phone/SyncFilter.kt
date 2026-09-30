package com.bazyak.dndsyncer.phone

import android.content.Context
import androidx.core.content.edit

/**
 * Какие режимы телефона уезжают на часы.
 *
 * Нужно потому, что на часах фильтров нет: там DND глушит всё подряд.
 * "Вождение" и "Общественный транспорт" на телефоне пропускают часть звонков
 * и сообщений, и синхронизировать их означает потерять эти исключения.
 *
 * Выбор хранится по id правила. Правила приходят из дампа, поэтому чужие
 * идентификаторы нигде не зашиты.
 */
object SyncFilter {

    /** Ручное "Не беспокоить" — не правило, но в списке выбора наравне с ними. */
    const val MANUAL = "MANUAL_RULE"

    fun isEnabled(context: Context, rule: ZenDump.Rule): Boolean {
        val prefs = prefs(context)
        val key = rule.id
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else byDefault(rule)
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        prefs(context).edit { putBoolean(id, enabled) }
    }

    /**
     * По умолчанию синхронизируем то, что глушит телефон целиком: ручной DND,
     * ночной режим и "Тсс при перевороте" — все три от системы или Wellbeing.
     * Режимы от сервисов Google (вождение, транспорт) по умолчанию выключены.
     */
    private fun byDefault(rule: ZenDump.Rule): Boolean = when {
        rule.id == MANUAL -> true
        rule.pkg == WELLBEING -> true
        else -> false
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("sync_filter", Context.MODE_PRIVATE)

    private const val WELLBEING = "com.google.android.apps.wellbeing"
}
