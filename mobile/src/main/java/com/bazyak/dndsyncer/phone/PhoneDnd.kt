package com.bazyak.dndsyncer.phone

import android.content.Context
import com.bazyak.dndsyncer.core.Dnd
import com.bazyak.dndsyncer.core.FileLog

/**
 * Включение и выключение тишины на телефоне.
 *
 * Отличается от общего Dnd тем, что учитывает автоправила. `cmd notification
 * set_dnd off` гасит только ручное правило, а тишину могли поднять
 * "Тсс при перевороте", "Вождение" или обычное расписание — такие правила
 * гасятся каждое по отдельности, от имени системы.
 */
object PhoneDnd {

    fun set(context: Context, on: Boolean): Boolean {
        if (on) return Dnd.set(context, true)

        // activeDndRules уже отфильтрован по выбору пользователя: чужое
        // "Вождение" гасить не будем, раз оно и не синхронизируется.
        ZenDump.read(context)?.activeDndRules?.forEach { rule ->
            FileLog.d(TAG, "гашу правило ${rule.id}")
            ZenRule.setState(context, rule.id, rule.conditionId, on = false)
        }
        return Dnd.set(context, false)
    }

    /**
     * Ранние сборки ставили DND через setInterruptionFilter, и система завела
     * для приложения правило implicit_<пакет>. В списке режимов телефона оно
     * выглядит как "Не беспокоить (DND syncer)" и ничего не делает: сейчас
     * тишина ставится системной командой. Убираем, если осталось.
     */
    fun removeLeftoverRule(context: Context) {
        val id = ZenDump.read(context)?.implicitRuleId ?: return
        FileLog.d(TAG, "нашёл осколок $id, удаляю")
        val ok = ZenRule.remove(context, id)
        FileLog.d(TAG, "удаление осколка: успех=$ok")
    }

    private const val TAG = "PhoneDnd"
}
