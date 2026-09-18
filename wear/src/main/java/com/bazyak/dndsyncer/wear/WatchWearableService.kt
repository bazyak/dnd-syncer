package com.bazyak.dndsyncer.wear

import com.bazyak.dndsyncer.core.Dnd
import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.Shell
import com.bazyak.dndsyncer.core.Sync
import com.bazyak.dndsyncer.core.SyncReceiver
import com.bazyak.dndsyncer.core.Zen
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

/**
 * Применяет на часах снимок с телефона.
 *
 * Отдельной защиты от эха нет и не нужно: после применения часы опубликуют
 * своё новое состояние, телефон увидит совпадение и остановится.
 */
class WatchWearableService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val update = SyncReceiver.parse(dataEvents, Sync.PATH_PHONE) ?: run {
            FileLog.d(TAG, "событие не про нас, пропускаю")
            return
        }
        val incoming = update.state
        val current = Zen.snapshot(contentResolver)
        FileLog.d(
            TAG,
            "ПОЛУЧЕНО с телефона: dnd=${incoming.dnd}${mark(update.dndChanged)} " +
                "night=${incoming.night}${mark(update.nightChanged)} " +
                "причина=${update.reason} | у себя: dnd=${current.dnd} night=${current.night} | " +
                Zen.describe(contentResolver),
        )

        // Применяем только то, что телефон у себя ИЗМЕНИЛ. Остальные поля в
        // снимке — просто его текущее состояние, а не указание что-то делать.
        val applyDnd = update.dndChanged && incoming.dnd != current.dnd
        val applyNight = update.nightChanged && incoming.night != current.night
        if (!applyDnd && !applyNight) {
            FileLog.d(TAG, "менять нечего")
            return
        }

        if (!Shell.isAvailable()) {
            FileLog.w(TAG, "shell недоступен, пробую поднять Shizuku")
            val result = ShizukuStarter.ensureRunning(this)
            FileLog.d(TAG, "подъём Shizuku: $result")
            when (result) {
                ShizukuStarter.Result.NO_WIFI -> {
                    Notify.noWifi(this)
                    return
                }
                ShizukuStarter.Result.FAILED -> FileLog.w(TAG, "Shizuku поднять не удалось")
                else -> Notify.clear(this)
            }
        }

        if (applyNight) {
            FileLog.d(TAG, "МЕНЯЮ ночь: ${current.night} → ${incoming.night}")
            setNight(incoming.night)
        }
        if (applyDnd) {
            FileLog.d(TAG, "МЕНЯЮ dnd: ${current.dnd} → ${incoming.dnd}")
            setDnd(incoming.dnd)
        }
        FileLog.d(TAG, "после применения: ${Zen.describe(contentResolver)}")
    }

    private fun setNight(on: Boolean) {
        if (!Zen.canWriteSecure(this)) {
            FileLog.w(TAG, "нет WRITE_SECURE_SETTINGS — ночной режим не применён")
            return
        }
        val ok = Zen.putGlobal(this, Zen.KEY_BEDTIME, if (on) 1 else 0)
        FileLog.d(TAG, "запись bedtime_mode=${if (on) 1 else 0} успех=$ok")
    }

    private fun setDnd(on: Boolean) {
        if (on) {
            // Если тишина уже поднята театром или ночью, вызов будет no-op —
            // и это нормально, DND по факту уже действует.
            Dnd.set(this, true)
            return
        }
        // Сначала снимаем театр, иначе он удержит zen_mode.
        if (Zen.theater(contentResolver)) {
            if (Zen.canWriteSecure(this)) {
                val ok = Zen.putGlobal(this, Zen.KEY_THEATER, 0)
                FileLog.d(TAG, "снимаю театр, успех=$ok")
            } else {
                FileLog.w(TAG, "нет WRITE_SECURE_SETTINGS — театр не снят")
            }
        }
        Dnd.set(this, false)
    }

    private fun mark(changed: Boolean) = if (changed) " (ИЗМЕНЕНО)" else ""

    private companion object {
        const val TAG = "WatchWearable"
    }
}
