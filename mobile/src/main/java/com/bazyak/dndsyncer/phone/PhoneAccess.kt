package com.bazyak.dndsyncer.phone

import android.content.Context
import com.bazyak.dndsyncer.core.Access
import com.bazyak.dndsyncer.core.RootShell

/**
 * Включение службы специальных возможностей самим приложением.
 *
 * Через рут список включённых служб пишется напрямую, поэтому кнопка
 * "Разрешить" нужна лишь пока рута нет. Вызывать только в фоне: su может
 * ждать подтверждения Magisk.
 */
object PhoneAccess {

    fun restore(context: Context): Boolean =
        Access.restoreAccessibility(context, PhoneSyncService::class.java) { key, value ->
            RootShell.exec("settings put secure $key '$value'") != null
        }
}
