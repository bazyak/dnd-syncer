package com.bazyak.dndsyncer.phone

import android.net.Uri
import android.os.IBinder

/**
 * Переключает ЛЮБОЕ zen-правило от имени системы.
 *
 * Запускается НЕ как часть приложения, а отдельным процессом через app_process
 * из-под uid 1000:
 *
 *   su 1000 -c "CLASSPATH=<apk> app_process / com.bazyak.dndsyncer.phone.ZenHelper <id> <uri> on"
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
object ZenHelper {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            System.err.println("usage: ZenHelper <ruleId> <conditionUri> <on|off|remove>")
            return
        }
        val ruleId = args[0]
        val conditionUri = args[1]
        val action = args[2]

        runCatching {
            if (action == "remove") remove(ruleId) else setState(ruleId, conditionUri, action == "on")
        }
            .onSuccess { println("OK") }
            .onFailure {
                System.err.println("FAIL: $it")
                it.printStackTrace()
            }
    }

    /**
     * Удаление правила. Нужно для осколка implicit_<пакет>, который оставил
     * setInterruptionFilter в ранних сборках: он висит в списке режимов
     * телефона как "Не беспокоить (DND syncer)" и только путает.
     *
     * Сигнатура removeAutomaticZenRule менялась между версиями Android
     * (добавлялись fromUser и пакет вызывающего), поэтому подбираем аргументы
     * по типам, а не фиксируем их.
     */
    private fun remove(ruleId: String) {
        val nm = notificationManager()
        val method = nm.javaClass.methods.first { it.name == "removeAutomaticZenRule" }
        // Тип указан явно: в when возвращаются String, Boolean и null,
        // и без этого toTypedArray() выводит их пересечение вместо Any?.
        val args: List<Any?> = method.parameterTypes.mapIndexed { index, type ->
            when {
                index == 0 -> ruleId
                type == Boolean::class.javaPrimitiveType -> true
                type == String::class.java -> "android"
                else -> null
            }
        }
        method.invoke(nm, *args.toTypedArray())
    }

    private fun notificationManager(): Any {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager
            .getMethod("getService", String::class.java)
            .invoke(null, "notification") as IBinder
        val stub = Class.forName("android.app.INotificationManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    private fun setState(ruleId: String, conditionUri: String, on: Boolean) {
        val nm = notificationManager()

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
