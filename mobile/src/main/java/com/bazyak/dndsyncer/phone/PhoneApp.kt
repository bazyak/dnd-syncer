package com.bazyak.dndsyncer.phone

import android.app.Application
import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.LogSettings
import com.bazyak.dndsyncer.core.Shell

/**
 * Application нужен, чтобы лог открылся раньше любого компонента: сервис
 * специальных возможностей и приёмник Data Layer стартуют независимо, и оба
 * должны писать в один файл.
 */
class PhoneApp : Application() {

    override fun onCreate() {
        super.onCreate()
        store = LogStore(this)
        FileLog.enabled = LogSettings.isEnabled(this)
        FileLog.install { line -> store?.append(line) }
        FileLog.d(TAG, "Запуск процесса, привилегированный доступ: ${Shell.backend()}")
        FileLog.d(TAG, "Лог: ${store?.describe()}")

        // После принудительной остановки система снимает службу специальных
        // возможностей. Возвращаем её через рут, в фоне: su может ждать
        // подтверждения Magisk, а onCreate блокировать нельзя.
        Thread { PhoneAccess.restore(this) }.start()
    }

    companion object {
        private const val TAG = "PhoneApp"

        @Volatile
        var store: LogStore? = null
            private set
    }
}
