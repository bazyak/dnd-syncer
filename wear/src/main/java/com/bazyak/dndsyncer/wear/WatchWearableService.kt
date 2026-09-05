package com.bazyak.dndsyncer.wear

import android.util.Log
import com.bazyak.dndsyncer.core.Dnd
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
 * своё новое состояние, телефон увидит, что оно совпадает с его собственным,
 * и остановится. Цикл обрывается за один круг сам.
 */
class WatchWearableService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val (incoming, reason) = SyncReceiver.parse(dataEvents, Sync.PATH_PHONE) ?: return
        val current = Zen.snapshot(contentResolver)
        if (incoming == current) {
            Log.d(TAG, "← $incoming ($reason) — совпадает, ничего не делаю")
            return
        }

        Log.d(TAG, "← dnd=${incoming.dnd} night=${incoming.night} ($reason), было $current")

        // Shizuku после перезагрузки часов не поднимается сам — пробуем поднять
        // по факту первой же команды, иначе менять DND будет нечем.
        if (!Shell.isAvailable()) {
            when (ShizukuStarter.ensureRunning(this)) {
                ShizukuStarter.Result.NO_WIFI -> {
                    Notify.noWifi(this)
                    return
                }
                ShizukuStarter.Result.FAILED -> Log.w(TAG, "Shizuku поднять не удалось")
                else -> Notify.clear(this)
            }
        }

        if (incoming.night != current.night) applyNight(incoming.night)
        if (incoming.dnd != current.dnd) applyDnd(incoming.dnd)
    }

    private fun applyNight(on: Boolean) {
        if (!Zen.canWriteSecure(this)) {
            Log.w(TAG, "Нет WRITE_SECURE_SETTINGS — ночной режим не применён")
            return
        }
        Zen.putGlobal(this, Zen.KEY_BEDTIME, if (on) 1 else 0)
    }

    private fun applyDnd(on: Boolean) {
        if (on) {
            // Если тишина уже поднята театром или ночью, вызов будет no-op —
            // и это нормально, DND по факту уже действует.
            Dnd.set(this, true)
            return
        }
        // Сначала снимаем театр, иначе он удержит zen_mode и снятие фильтра
        // ничего не даст.
        if (Zen.theater(contentResolver)) {
            if (Zen.canWriteSecure(this)) {
                Zen.putGlobal(this, Zen.KEY_THEATER, 0)
            } else {
                Log.w(TAG, "Нет WRITE_SECURE_SETTINGS — театр не снят")
            }
        }
        Dnd.set(this, false)
    }

    private companion object {
        const val TAG = "WatchWearable"
    }
}
