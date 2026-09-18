package com.bazyak.dndsyncer.phone

import android.content.Context
import com.bazyak.dndsyncer.core.FileLog
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

    /** Ручной сбор полного среза в лог — кнопкой с экрана телефона. */
    fun logSnapshot() {
        FileLog.d(TAG, "--- ручной срез ---")
        ZenDump.read(verbose = true)
    }

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
            FileLog.w(TAG, "правило ночного режима не найдено")
            return false
        }
        val conditionId = dump.bedtimeConditionId?.takeIf { it.isNotBlank() } ?: run {
            FileLog.w(TAG, "у правила нет conditionId")
            return false
        }

        val apk = context.applicationInfo.sourceDir
        val command = "CLASSPATH=$apk app_process / $HELPER " +
            "'$id' '$conditionId' ${if (on) "on" else "off"}"

        // Сначала от системы — именно этот uid проходит проверку.
        // Если Magisk не даст сменить uid, пробуем от рута.
        FileLog.d(TAG, "команда: $command")
        for (uid in listOf(SYSTEM_UID, null)) {
            val output = RootShell.execAs(uid, command)
            FileLog.d(TAG, "uid=${uid ?: "root"} → ${output?.trim()?.ifEmpty { "(пусто)" }}")
            if (output != null && output.contains("OK")) {
                // Проверяем по факту: команда может отработать вхолостую,
                // если система откажется трогать чужое правило.
                Thread.sleep(VERIFY_DELAY_MS)
                val after = ZenDump.read()?.night
                FileLog.d(TAG, "после команды night=$after (ожидалось $on)")
                if (after == on) return true
                FileLog.w(TAG, "команда прошла, но состояние не изменилось")
            }
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
    private const val VERIFY_DELAY_MS = 600L
}
