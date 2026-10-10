package com.bazyak.dndsyncer.wear

import android.content.Context
import android.provider.Settings
import com.bazyak.dndsyncer.core.Access
import com.bazyak.dndsyncer.core.Zen

/**
 * Включение службы специальных возможностей самим приложением.
 *
 * С правом WRITE_SECURE_SETTINGS список включённых служб пишется напрямую,
 * поэтому кнопка "Разрешить" нужна лишь до выдачи этого права.
 */
object WatchAccess {

    fun restore(context: Context): Boolean {
        if (!Zen.canWriteSecure(context)) return false
        return Access.restoreAccessibility(context, WatchSyncService::class.java) { key, value ->
            runCatching { Settings.Secure.putString(context.contentResolver, key, value) }
                .getOrDefault(false)
        }
    }
}
