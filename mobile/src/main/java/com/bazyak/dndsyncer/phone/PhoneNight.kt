package com.bazyak.dndsyncer.phone

import android.content.Context
import com.bazyak.dndsyncer.core.FileLog

/**
 * Ночной режим на телефоне — это AutomaticZenRule Digital Wellbeing
 * (pkg=com.google.android.apps.wellbeing, type=3, conditionId=.../winddown).
 *
 * Правилами типа TYPE_BEDTIME по документации может владеть только Wellbeing,
 * поэтому завести своё нельзя — остаётся дёргать чужое от имени системы.
 *
 * Состояние читаем не через getAutomaticZenRuleState (он про наши правила),
 * а из дампа: там видно фактическое state=STATE_TRUE.
 */
object PhoneNight {

    fun isOn(context: Context): Boolean = ZenDump.read(context)?.night ?: false

    /** Ручной сбор полного среза в лог — кнопкой с экрана телефона. */
    fun logSnapshot(context: Context) {
        FileLog.d(TAG, "--- ручной срез ---")
        ZenDump.read(context, verbose = true)
    }

    fun setOn(context: Context, on: Boolean): Boolean {
        val dump = ZenDump.read(context)
        val id = dump?.bedtimeRuleId ?: run {
            FileLog.w(TAG, "правило ночного режима не найдено")
            return false
        }
        val conditionId = dump.bedtimeConditionId.orEmpty()

        if (!ZenRule.setState(context, id, conditionId, on)) return false

        // Команда может отработать вхолостую, если система откажется трогать
        // чужое правило — проверяем по факту.
        Thread.sleep(VERIFY_DELAY_MS)
        val after = ZenDump.read(context)?.night
        FileLog.d(TAG, "после команды night=$after (ожидалось $on)")
        return after == on
    }

    /** Диагностика для экрана телефона. */
    fun dump(context: Context): String {
        val state = ZenDump.read(context)
            ?: return "нет привилегированного доступа"
        return buildString {
            append("DND: ${if (state.dnd) "вкл" else "выкл"}\n")
            append("Ночь: ${if (state.night) "вкл" else "выкл"}\n")
            append("Правило ночи: ${state.bedtimeRuleId ?: "не найдено"}")
            if (state.activeDndRules.isNotEmpty()) {
                append("\nАктивные правила: ")
                append(state.activeDndRules.joinToString { it.name })
            }
        }
    }

    private const val TAG = "PhoneNight"
    private const val VERIFY_DELAY_MS = 600L
}
