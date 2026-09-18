package com.bazyak.dndsyncer.phone

import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.Shell

/**
 * Разбор `dumpsys notification`.
 *
 * Нужен потому, что zen_mode на телефоне — общий флаг: ночной режим и
 * "Не беспокоить" поднимают его одинаково, а различить их можно только по
 * правилам. Ночь — это AutomaticZenRule с type=3 (TYPE_BEDTIME), DND —
 * manualRule (его же ставит наша команда через shell, поэтому ручной и наш
 * неразличимы, что и требуется).
 *
 * Дамп печатает несколько срезов конфигурации подряд (история + диффы),
 * поэтому сканируем всё и оставляем ПОСЛЕДНЕЕ значение по каждому правилу.
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

    fun read(verbose: Boolean = false): State? {
        val dump = Shell.exec("dumpsys notification") ?: run {
            FileLog.w(TAG, "нет привилегированного доступа — дамп недоступен")
            return null
        }

        var manual = false
        var night = false
        var bedtimeId: String? = null
        var bedtimeCondition: String? = null

        // Порядок вхождений важен для отладки: если состояние «скачет»,
        // в логе будет видно всю последовательность, а не только итог.
        val trace = StringBuilder()

        dump.lineSequence().forEach { line ->
            val match = ID.find(line) ?: return@forEach
            val id = match.groupValues[1]
            val active = match.groupValues[2] == "STATE_TRUE"
            val type = TYPE.find(line)?.groupValues?.get(1)

            when {
                id == "MANUAL_RULE" -> {
                    manual = active
                    trace.append("MANUAL_RULE=$active\n")
                }
                type == BEDTIME_TYPE -> {
                    night = active
                    bedtimeId = id
                    bedtimeCondition = CONDITION_ID.find(line)?.groupValues?.get(1)
                    trace.append("BEDTIME($id)=$active\n")
                }
                active -> trace.append("прочее активное правило: $id type=$type\n")
            }
        }

        if (verbose) {
            FileLog.block(TAG, "последовательность состояний в дампе", trace.toString())
            // Строки Diff показывают, КТО и когда переключил правило —
            // главная зацепка при разборе петли.
            val diffs = dump.lineSequence().filter { it.contains("Diff[") }.joinToString("\n")
            if (diffs.isNotBlank()) FileLog.block(TAG, "переходы (Diff)", diffs)
        }

        return State(
            dnd = manual,
            night = night,
            bedtimeRuleId = bedtimeId,
            bedtimeConditionId = bedtimeCondition,
        ).also {
            FileLog.d(TAG, "дамп → dnd=${it.dnd} night=${it.night} ruleId=${it.bedtimeRuleId}")
        }
    }

    private const val TAG = "ZenDump"
    private const val BEDTIME_TYPE = "3"
}
