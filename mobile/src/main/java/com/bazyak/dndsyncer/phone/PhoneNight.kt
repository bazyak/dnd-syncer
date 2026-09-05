package com.bazyak.dndsyncer.phone

import android.content.Context
import android.util.Log
import com.bazyak.dndsyncer.core.RootShell

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

    fun isOn(): Boolean = ZenDump.read()?.night ?: false

    /**
     * Правило ночного режима принадлежит Digital Wellbeing, и активировать
     * чужое правило из процесса приложения нельзя: вызов проходит без ошибки,
     * но система его игнорирует (проверено — state оставался STATE_FALSE).
     * Проверка смотрит на uid вызывающего, поэтому запускаем тот же вызов
     * отдельным процессом от имени системы.
     *
     * Отдельный dex не нужен: наш APK уже лежит на устройстве и годится
     * как CLASSPATH для app_process.
     */
    fun setOn(context: Context, on: Boolean): Boolean {
        val dump = ZenDump.read()
        val id = dump?.bedtimeRuleId ?: run {
            Log.w(TAG, "Правило ночного режима не найдено")
            return false
        }
        val conditionId = dump.bedtimeConditionId?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "У правила нет conditionId")
            return false
        }

        val apk = context.applicationInfo.sourceDir
        val command = "CLASSPATH=$apk app_process / $HELPER " +
            "'$id' '$conditionId' ${if (on) "on" else "off"}"

        // Сначала от системы — именно этот uid проходит проверку.
        // Если Magisk не даст сменить uid, пробуем от рута.
        for (uid in listOf(SYSTEM_UID, null)) {
            val output = RootShell.execAs(uid, command)
            if (output != null && output.contains("OK")) {
                Log.d(TAG, "Ночной режим ${if (on) "включён" else "выключен"} (uid=$uid)")
                return true
            }
            Log.w(TAG, "uid=$uid не сработал: ${output?.trim()}")
        }
        return false
    }

    /** Диагностика для экрана телефона. */
    fun dump(): String {
        val state = ZenDump.read()
            ?: return "нет привилегированного доступа"
        return buildString {
            append("DND: ${if (state.dnd) "вкл" else "выкл"}\n")
            append("Ночь: ${if (state.night) "вкл" else "выкл"}\n")
            append("Правило ночи: ${state.bedtimeRuleId ?: "не найдено"}")
        }
    }

    private const val TAG = "PhoneNight"
    private const val HELPER = "com.bazyak.dndsyncer.phone.NightHelper"
    private const val SYSTEM_UID = 1000
}
