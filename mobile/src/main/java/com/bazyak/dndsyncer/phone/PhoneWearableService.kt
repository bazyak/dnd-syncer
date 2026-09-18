package com.bazyak.dndsyncer.phone

import com.bazyak.dndsyncer.core.Dnd
import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.Sync
import com.bazyak.dndsyncer.core.SyncReceiver
import com.bazyak.dndsyncer.core.Zen
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

/**
 * Применяет на телефоне снимок с часов. DND и ночь ставятся независимо:
 * на телефоне эти режимы сосуществуют, в отличие от часов.
 */
class PhoneWearableService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val update = SyncReceiver.parse(dataEvents, Sync.PATH_WATCH) ?: run {
            FileLog.d(TAG, "событие не про нас, пропускаю")
            return
        }
        val incoming = update.state
        val current = PhoneSyncService.snapshot(this)
        FileLog.d(
            TAG,
            "ПОЛУЧЕНО с часов: dnd=${incoming.dnd}${mark(update.dndChanged)} " +
                "night=${incoming.night}${mark(update.nightChanged)} " +
                "причина=${update.reason} | у себя: dnd=${current.dnd} night=${current.night} | " +
                Zen.describe(contentResolver),
        )

        // Применяем только то, что часы у себя ИЗМЕНИЛИ. Остальные поля —
        // их текущее состояние, а не команда: именно так застарелый
        // bedtime_mode с часов возвращал телефон в уже законченную ночь.
        val applyDnd = update.dndChanged && incoming.dnd != current.dnd
        val applyNight = update.nightChanged && incoming.night != current.night
        if (!applyDnd && !applyNight) {
            FileLog.d(TAG, "менять нечего")
            return
        }

        if (applyNight) {
            FileLog.d(TAG, "МЕНЯЮ ночь: ${current.night} → ${incoming.night}")
            PhoneNight.setOn(this, incoming.night)
        }
        if (applyDnd) {
            FileLog.d(TAG, "МЕНЯЮ dnd: ${current.dnd} → ${incoming.dnd}")
            Dnd.set(this, incoming.dnd)
        }

        val after = PhoneSyncService.snapshot(this)
        FileLog.d(TAG, "после применения: dnd=${after.dnd} night=${after.night}")
    }

    private fun mark(changed: Boolean) = if (changed) " (ИЗМЕНЕНО)" else ""

    private companion object {
        const val TAG = "PhoneWearable"
    }
}
