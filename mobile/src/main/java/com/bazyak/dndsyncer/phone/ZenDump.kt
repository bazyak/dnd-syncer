package com.bazyak.dndsyncer.phone

import android.content.Context
import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.Shell

/**
 * Разбор `dumpsys notification`.
 *
 * Нужен потому, что zen_mode на телефоне — общий флаг: ночной режим и
 * "Не беспокоить" поднимают его одинаково, а различить их можно только по
 * правилам.
 *
 * Дамп печатает несколько срезов конфигурации подряд (история + диффы),
 * поэтому сканируем всё и оставляем ПОСЛЕДНЕЕ значение по каждому правилу.
 */
object ZenDump {

    data class Rule(
        val id: String,
        val conditionId: String,
        val name: String,
        val pkg: String,
        val type: String,
        val active: Boolean,
    ) {
        val isBedtime: Boolean get() = type == BEDTIME_TYPE
        val isManual: Boolean get() = id == SyncFilter.MANUAL

        /** Активные правила бывают и безобидными: "Игровая панель" ничего не глушит. */
        val silences: Boolean get() = zenMode != "ZEN_MODE_OFF"

        internal var zenMode: String = ""
    }

    data class State(
        val dnd: Boolean,
        val night: Boolean,
        val bedtimeRuleId: String?,
        val bedtimeConditionId: String?,
        /** Активные правила, которые выбраны для синхронизации. */
        val activeDndRules: List<Rule> = emptyList(),
        /** Всё, что есть в системе — для экрана выбора. */
        val allRules: List<Rule> = emptyList(),
        /** Осколок implicit_<пакет> от ранних сборок, если он ещё висит. */
        val implicitRuleId: String? = null,
    )

    private val ID = Regex("""ZenRule\[id=([^,]+),state=(STATE_\w+)""")
    private val OVERRIDE = Regex("""conditionOverride=(OVERRIDE_\w+)""")
    private val TYPE = Regex("""type=(-?\d+)""")
    private val ZEN_MODE = Regex("""zenMode=(ZEN_MODE_\w+)""")
    private val CONDITION_ID = Regex("""conditionId=([^,]*)""")
    private val NAME = Regex("""name=([^,]*)""")
    private val PKG = Regex("""pkg=([^,]*)""")

    fun read(context: Context, verbose: Boolean = false): State? {
        val dump = Shell.exec("dumpsys notification") ?: run {
            FileLog.w(TAG, "нет привилегированного доступа — дамп недоступен")
            return null
        }

        // Правило встречается в дампе многократно; побеждает последнее вхождение.
        val rules = LinkedHashMap<String, Rule>()
        var implicit: String? = null
        val trace = StringBuilder()

        dump.lineSequence().forEach { line ->
            val match = ID.find(line) ?: return@forEach
            val id = match.groupValues[1]

            // С Android 15 ручное включение автоправила не меняет state,
            // а ставит поверх него conditionOverride: "Вождение" висело как
            // STATE_FALSE + OVERRIDE_ACTIVATE, будучи при этом включённым.
            val override = OVERRIDE.find(line)?.groupValues?.get(1)
            val active = when (override) {
                "OVERRIDE_ACTIVATE" -> true
                "OVERRIDE_DEACTIVATE" -> false
                else -> match.groupValues[2] == "STATE_TRUE"
            }

            if (id.startsWith("implicit_")) {
                implicit = id
                trace.append("осколок $id=$active\n")
                return@forEach
            }

            val rule = Rule(
                id = id,
                conditionId = CONDITION_ID.find(line)?.groupValues?.get(1).orEmpty(),
                name = NAME.find(line)?.groupValues?.get(1)?.takeIf { it != "null" }
                    ?: if (id == SyncFilter.MANUAL) "Не беспокоить" else id,
                pkg = PKG.find(line)?.groupValues?.get(1).orEmpty(),
                type = TYPE.find(line)?.groupValues?.get(1).orEmpty(),
                active = active,
            ).apply { zenMode = ZEN_MODE.find(line)?.groupValues?.get(1).orEmpty() }

            rules[id] = rule
            if (active) {
                val note = override?.takeIf { it != "OVERRIDE_NONE" }?.let { " ($it)" }.orEmpty()
                trace.append("активно: ${rule.name} [$id] type=${rule.type} ${rule.zenMode}$note\n")
            }
        }

        val all = rules.values.toList()
        val bedtime = all.firstOrNull { it.isBedtime }

        // Учитываем только выбранные для синхронизации режимы: на часах
        // фильтров нет, и "Вождение" там заглушило бы вообще всё.
        val activeDnd = all.filter {
            it.active && it.silences && !it.isBedtime && SyncFilter.isEnabled(context, it)
        }
        val night = bedtime?.active == true && SyncFilter.isEnabled(context, bedtime)

        if (verbose) {
            FileLog.block(TAG, "активные правила", trace.toString().ifBlank { "нет" })
            val diffs = dump.lineSequence().filter { it.contains("Diff[") }.joinToString("\n")
            if (diffs.isNotBlank()) FileLog.block(TAG, "переходы (Diff)", diffs)
        }

        return State(
            dnd = activeDnd.isNotEmpty(),
            night = night,
            bedtimeRuleId = bedtime?.id,
            bedtimeConditionId = bedtime?.conditionId,
            activeDndRules = activeDnd,
            allRules = all,
            implicitRuleId = implicit,
        ).also {
            FileLog.d(
                TAG,
                "дамп → dnd=${it.dnd} (${activeDnd.joinToString { r -> r.name }}) " +
                    "night=${it.night}",
            )
        }
    }

    private const val TAG = "ZenDump"
    private const val BEDTIME_TYPE = "3"
}
