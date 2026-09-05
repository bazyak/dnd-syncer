package com.bazyak.dndsyncer.phone

import android.net.Uri
import android.os.IBinder

/**
 * Запускается НЕ как часть приложения, а отдельным процессом через app_process
 * из-под uid 1000 (система):
 *
 *   su 1000 -c "CLASSPATH=<apk> app_process / com.bazyak.dndsyncer.phone.NightHelper <id> <uri> on"
 *
 * Смысл в uid. ZenModeHelper разрешает активировать ЧУЖОЕ правило только когда
 * вызов пришёл от системы; из процесса приложения тот же самый вызов молча
 * игнорируется — что мы и наблюдали. Правило ночного режима принадлежит
 * Digital Wellbeing, и владеть правилами TYPE_BEDTIME по документации может
 * только оно, так что завести своё нельзя — можно лишь дёрнуть чужое от имени
 * системы.
 *
 * Всё через рефлексию: INotificationManager и ServiceManager скрыты из SDK.
 */
object NightHelper {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            System.err.println("usage: NightHelper <ruleId> <conditionUri> <on|off>")
            return
        }
        val ruleId = args[0]
        val conditionUri = args[1]
        val on = args[2] == "on"

        runCatching { setState(ruleId, conditionUri, on) }
            .onSuccess { println("OK") }
            .onFailure {
                System.err.println("FAIL: $it")
                it.printStackTrace()
            }
    }

    private fun setState(ruleId: String, conditionUri: String, on: Boolean) {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager
            .getMethod("getService", String::class.java)
            .invoke(null, "notification") as IBinder

        val stub = Class.forName("android.app.INotificationManager\$Stub")
        val nm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)

        val conditionClass = Class.forName("android.service.notification.Condition")
        val state = if (on) STATE_TRUE else STATE_FALSE

        // Конструктор с источником появился в API 35. SOURCE_USER_ACTION говорит
        // системе, что это ручное переключение, а не попытка приложения
        // управлять чужим правилом.
        val condition = runCatching {
            conditionClass.getConstructor(
                Uri::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).newInstance(Uri.parse(conditionUri), "DND syncer", state, SOURCE_USER_ACTION)
        }.getOrElse {
            conditionClass.getConstructor(
                Uri::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            ).newInstance(Uri.parse(conditionUri), "DND syncer", state)
        }

        val method = nm.javaClass.methods.first {
            it.name == "setAutomaticZenRuleState" && it.parameterTypes.size == 2
        }
        method.invoke(nm, ruleId, condition)
    }

    private const val STATE_FALSE = 0
    private const val STATE_TRUE = 1
    private const val SOURCE_USER_ACTION = 1
}
