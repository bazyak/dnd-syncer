package com.bazyak.dndsyncer.phone

import android.content.Context
import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.RootShell

/**
 * Переключение чужого zen-правила: запускает ZenHelper отдельным процессом
 * от имени системы. Из процесса приложения тот же вызов проходит без ошибки,
 * но система его игнорирует — проверка смотрит на uid вызывающего.
 *
 * Отдельный dex не нужен: APK приложения уже на устройстве и годится
 * как CLASSPATH для app_process.
 */
object ZenRule {

    fun setState(context: Context, ruleId: String, conditionId: String, on: Boolean): Boolean {
        if (conditionId.isBlank()) {
            FileLog.w(TAG, "у правила $ruleId нет conditionId")
            return false
        }
        return run(context, ruleId, conditionId, if (on) "on" else "off")
    }

    fun remove(context: Context, ruleId: String): Boolean =
        run(context, ruleId, "-", "remove")

    private fun run(
        context: Context,
        ruleId: String,
        conditionId: String,
        action: String,
    ): Boolean {
        val apk = context.applicationInfo.sourceDir
        val command = "CLASSPATH=$apk app_process / $HELPER '$ruleId' '$conditionId' $action"

        FileLog.d(TAG, "команда: $command")
        for (uid in listOf(SYSTEM_UID, null)) {
            val output = RootShell.execAs(uid, command)
            FileLog.d(TAG, "uid=${uid ?: "root"} → ${output?.trim()?.ifEmpty { "(пусто)" }}")
            if (output != null && output.contains("OK")) return true
        }
        return false
    }

    private const val TAG = "ZenRule"
    private const val HELPER = "com.bazyak.dndsyncer.phone.ZenHelper"
    private const val SYSTEM_UID = 1000
}
