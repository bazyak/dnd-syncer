package com.bazyak.dndsyncer.phone

import android.util.Log
import com.bazyak.dndsyncer.core.Shell

/**
 * Разбор `dumpsys notification`.
 *
 * Нужен потому, что zen_mode на телефоне — общий флаг: ночной режим и
 * "Не беспокоить" поднимают его одинаково, а различить их можно только по
 * правилам. Ночь — это AutomaticZenRule с type=3 (TYPE_BEDTIME), DND —
 * manualRule либо implicit-правило нашего пакета.
 *
 * Дамп печатает несколько срезов конфигурации подряд (история + диффы),
 * поэтому сканируем всё и оставляем ПОСЛЕДНЕЕ значение по каждому правилу —
 * оно и есть текущее.
 */
object ZenDump {

    data class State(
        val dnd: Boolean,
        val night: Boolean,
        val bedtimeRuleId: String?,
        val bedtimeConditionId: String?,
    )

    private val ID = Regex("""ZenRule\[id=([^,]+),state=(STATE_\w+)""")
    private val TYPE = Regex("""type=(-?\d+)""")
    private val CONDITION_ID = Regex("""conditionId=([^,]*)""")

    fun read(): State? {
        val dump = Shell.exec("dumpsys notification") ?: run {
            Log.w(TAG, "Нет привилегированного доступа — дамп недоступен")
            return null
        }

        var manual = false
        var night = false
        var bedtimeId: String? = null
        var bedtimeCondition: String? = null

        // Каждое ZenRule печатается одной строкой, поэтому построчного разбора хватает.
        dump.lineSequence().forEach { line ->
            val match = ID.find(line) ?: return@forEach
            val id = match.groupValues[1]
            val active = match.groupValues[2] == "STATE_TRUE"

            when {
                id == "MANUAL_RULE" -> manual = active
                TYPE.find(line)?.groupValues?.get(1) == BEDTIME_TYPE -> {
                    night = active
                    bedtimeId = id
                    bedtimeCondition = CONDITION_ID.find(line)?.groupValues?.get(1)
                }
            }
        }

        return State(
            dnd = manual,
            night = night,
            bedtimeRuleId = bedtimeId,
            bedtimeConditionId = bedtimeCondition,
        ).also { Log.d(TAG, "дамп → $it") }
    }

    private const val TAG = "ZenDump"
    private const val BEDTIME_TYPE = "3"
}
